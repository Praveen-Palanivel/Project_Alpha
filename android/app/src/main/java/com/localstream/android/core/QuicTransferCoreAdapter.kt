package com.localstream.android.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.util.Log
import com.localstream.android.ui.model.PeerDevice
import com.localstream.android.ui.model.PendingTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * High-performance transfer adapter optimized for 100+ MB/s.
 * 
 * Features:
 * - Background server with auto-accept prompts
 * - 16MB chunks, 64MB socket buffers
 * - Direct streaming without intermediate buffering
 * - Saves to Downloads/project_alpha folder
 */
class QuicTransferCoreAdapter(
    private val context: Context,
    private val scope: CoroutineScope
) : TransferCoreAdapter {

    companion object {
        private const val TAG = "QuicTransferCore"
        
        // Protocol constants
        const val PROTOCOL_VERSION = "1.0.0"
        const val DEFAULT_PORT = 42424
        const val DISCOVERY_PORT = 42425
        
        // SPEED OPTIMIZATION: Maximum throughput settings
        const val CHUNK_SIZE = 16 * 1024 * 1024  // 16 MB chunks for max speed
        const val SOCKET_BUFFER_SIZE = 64 * 1024 * 1024  // 64 MB socket buffers
        const val RAW_IO_BUFFER_SIZE = 256 * 1024  // 256 KB raw I/O buffer
        
        // Message types
        const val MSG_HELLO = 0x01
        const val MSG_HELLO_ACK = 0x02
        const val MSG_FILE_OFFER = 0x10
        const val MSG_FILE_ACCEPT = 0x11
        const val MSG_FILE_REJECT = 0x12
        const val MSG_CHUNK_DATA = 0x20
        const val MSG_TRANSFER_COMPLETE = 0x30
        const val MSG_TRANSFER_VERIFIED = 0x31
        const val MSG_CANCEL = 0x40
        
        // Discovery
        const val DISCOVERY_ANNOUNCE = 0x01
        const val DISCOVERY_SEARCH = 0x02
        const val DISCOVERY_RESPONSE = 0x03
        const val DISCOVERY_ANNOUNCE_INTERVAL_MS = 2000L
        const val DISCOVERY_LISTEN_DURATION_MS = 10000L
        
        // Save folder
        const val SAVE_FOLDER_NAME = "project_alpha"
    }
    
    // Jobs
    private var activeTransferJob: Job? = null
    private var discoveryListenerJob: Job? = null
    private var discoveryBroadcasterJob: Job? = null
    private var backgroundServerJob: Job? = null
    
    // Speed tracking
    private val speedBytesPerSec = AtomicLong(0L)
    private val totalBytesTransferred = AtomicLong(0L)
    private var transferStartTime: Long = 0L
    
    // Device info
    private val deviceId: String = UUID.randomUUID().toString().take(8)
    private val deviceName: String = android.os.Build.MODEL
    
    // Discovery
    private val discoveredDevices = ConcurrentHashMap<String, PeerDevice>()
    private val isDiscoveryRunning = AtomicBoolean(false)
    
    // Background server state
    private val isServerRunning = AtomicBoolean(false)
    private var pendingSocket: Socket? = null
    private var pendingTransferInfo: PendingTransfer? = null
    
    // Callbacks for accept/reject flow
    private var onIncomingTransferCallback: ((PendingTransfer) -> Unit)? = null
    private var onTransferProgressCallback: ((Long, Long, Long) -> Unit)? = null
    private var onTransferCompleteCallback: (() -> Unit)? = null
    private var onTransferErrorCallback: ((String) -> Unit)? = null

    // ==========================================================================
    // Background Server (Auto-Accept Prompt)
    // ==========================================================================
    
    /**
     * Start background server that listens for incoming connections.
     * When a sender connects, calls onIncoming with file info for accept/reject.
     */
    fun startBackgroundServer(
        onIncoming: (PendingTransfer) -> Unit,
        onProgress: (Long, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isServerRunning.get()) {
            Log.d(TAG, "Server already running")
            return
        }
        
        onIncomingTransferCallback = onIncoming
        onTransferProgressCallback = onProgress
        onTransferCompleteCallback = onComplete
        onTransferErrorCallback = onError
        
        isServerRunning.set(true)
        
        backgroundServerJob = scope.launch(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(DEFAULT_PORT))
                serverSocket.soTimeout = 2000  // Check for shutdown every 2 seconds
                
                Log.d(TAG, "Background server started on port $DEFAULT_PORT")
                
                while (isActive && isServerRunning.get()) {
                    try {
                        val clientSocket = serverSocket.accept()
                        Log.d(TAG, "Incoming connection from ${clientSocket.inetAddress.hostAddress}")
                        
                        // Handle connection - read handshake and prompt user
                        handleIncomingConnection(clientSocket)
                        
                    } catch (e: java.net.SocketTimeoutException) {
                        // Expected - check if should continue
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background server error", e)
                withContext(Dispatchers.Main) {
                    onError("Server error: ${e.message}")
                }
            } finally {
                serverSocket?.close()
                isServerRunning.set(false)
                Log.d(TAG, "Background server stopped")
            }
        }
    }
    
    private suspend fun handleIncomingConnection(clientSocket: Socket) {
        try {
            // Configure for max speed
            clientSocket.receiveBufferSize = SOCKET_BUFFER_SIZE
            clientSocket.sendBufferSize = SOCKET_BUFFER_SIZE
            clientSocket.tcpNoDelay = true
            
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            
            // Read HELLO
            val helloBuffer = ByteArray(4096)
            val helloLen = input.read(helloBuffer)
            if (helloLen <= 4 || helloBuffer[4].toInt() != MSG_HELLO) {
                Log.e(TAG, "Invalid HELLO")
                clientSocket.close()
                return
            }
            
            // Parse sender info from HELLO
            val helloJson = String(helloBuffer, 5, helloLen - 5)
            val senderName = Regex("\"device_name\":\"([^\"]+)\"").find(helloJson)?.groupValues?.get(1) ?: "Unknown"
            val senderIp = clientSocket.inetAddress.hostAddress ?: "Unknown"
            
            // Send HELLO_ACK
            val ackPayload = "{\"accepted\":true,\"device_id\":\"$deviceId\",\"device_name\":\"$deviceName\"}".toByteArray()
            output.write(buildMessage(MSG_HELLO_ACK, ackPayload))
            output.flush()
            
            // Read FILE_OFFER
            val offerBuffer = ByteArray(4096)
            val offerLen = input.read(offerBuffer)
            if (offerLen <= 4 || offerBuffer[4].toInt() != MSG_FILE_OFFER) {
                Log.e(TAG, "Invalid FILE_OFFER")
                clientSocket.close()
                return
            }
            
            // Parse file info
            val offerJson = String(offerBuffer, 5, offerLen - 5)
            val fileName = Regex("\"file_name\":\"([^\"]+)\"").find(offerJson)?.groupValues?.get(1) ?: "unknown"
            val fileSize = Regex("\"file_size\":([0-9]+)").find(offerJson)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val transferId = Regex("\"transfer_id\":\"([^\"]+)\"").find(offerJson)?.groupValues?.get(1) ?: UUID.randomUUID().toString()
            
            Log.d(TAG, "Incoming file: $fileName ($fileSize bytes) from $senderName")
            
            // Store pending connection for accept/reject
            pendingSocket = clientSocket
            pendingTransferInfo = PendingTransfer(
                transferId = transferId,
                fileName = fileName,
                fileSize = fileSize,
                senderName = senderName,
                senderIp = senderIp
            )
            
            // Notify UI to show accept/reject dialog
            withContext(Dispatchers.Main) {
                onIncomingTransferCallback?.invoke(pendingTransferInfo!!)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming connection", e)
            clientSocket.close()
        }
    }
    
    /**
     * Accept the pending incoming transfer.
     */
    fun acceptTransfer() {
        val socket = pendingSocket ?: return
        val info = pendingTransferInfo ?: return
        
        activeTransferJob = scope.launch(Dispatchers.IO) {
            var fileOutputStream: FileOutputStream? = null
            try {
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                
                // Send FILE_ACCEPT
                val acceptPayload = "{\"accepted\":true}".toByteArray()
                output.write(buildMessage(MSG_FILE_ACCEPT, acceptPayload))
                output.flush()
                
                // Resolve save file - Downloads/project_alpha/
                val saveFile = resolveSaveFile(info.fileName)
                Log.d(TAG, "Saving to: ${saveFile.absolutePath}")
                
                // Initialize
                transferStartTime = System.currentTimeMillis()
                totalBytesTransferred.set(0L)
                speedBytesPerSec.set(0L)
                
                fileOutputStream = FileOutputStream(saveFile)
                val fileChannel = fileOutputStream.channel
                
                // Receive with optimized I/O
                val headerBuffer = ByteArray(12)
                val chunkBuffer = ByteArray(CHUNK_SIZE)
                var lastProgressTime = System.currentTimeMillis()
                var bytesInPeriod = 0L
                val totalBytes = info.fileSize
                
                while (isActive && totalBytesTransferred.get() < totalBytes) {
                    // Read chunk header
                    if (!readFully(input, headerBuffer, 12)) break
                    
                    val headerBuf = ByteBuffer.wrap(headerBuffer).order(ByteOrder.BIG_ENDIAN)
                    val chunkIndex = headerBuf.getInt()
                    val chunkSize = headerBuf.getInt()
                    
                    if (chunkSize <= 0 || chunkSize > CHUNK_SIZE) break
                    
                    // Read chunk data in optimal-size pieces
                    if (!readFully(input, chunkBuffer, chunkSize)) break
                    
                    // Write directly
                    val writeBuffer = ByteBuffer.wrap(chunkBuffer, 0, chunkSize)
                    fileChannel.write(writeBuffer)
                    
                    val currentTotal = totalBytesTransferred.addAndGet(chunkSize.toLong())
                    bytesInPeriod += chunkSize
                    
                    // Update speed
                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime >= 150) {
                        val elapsed = now - lastProgressTime
                        val speed = if (elapsed > 0) (bytesInPeriod * 1000) / elapsed else 0L
                        speedBytesPerSec.set(speed)
                        bytesInPeriod = 0L
                        lastProgressTime = now
                        
                        withContext(Dispatchers.Main) {
                            onTransferProgressCallback?.invoke(currentTotal, totalBytes, speed)
                        }
                    }
                }
                
                fileChannel.force(true)
                fileOutputStream.close()
                fileOutputStream = null
                
                // Read TRANSFER_COMPLETE
                val completeBuffer = ByteArray(256)
                runCatching { input.read(completeBuffer) }
                
                // Send verification
                val verified = saveFile.length() >= totalBytes * 0.99
                val verifyMsg = buildMessage(MSG_TRANSFER_VERIFIED, "{\"verified\":$verified}".toByteArray())
                output.write(verifyMsg)
                output.flush()
                
                speedBytesPerSec.set(0L)
                
                if (verified) {
                    val avgSpeed = if (System.currentTimeMillis() - transferStartTime > 0) {
                        (totalBytes * 1000) / (System.currentTimeMillis() - transferStartTime)
                    } else 0L
                    Log.d(TAG, "Transfer complete! Saved: ${saveFile.absolutePath}, Avg: ${avgSpeed / 1024 / 1024} MB/s")
                    withContext(Dispatchers.Main) { onTransferCompleteCallback?.invoke() }
                } else {
                    withContext(Dispatchers.Main) { onTransferErrorCallback?.invoke("Verification failed") }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Accept transfer error", e)
                speedBytesPerSec.set(0L)
                withContext(Dispatchers.Main) { onTransferErrorCallback?.invoke(e.message ?: "Transfer failed") }
            } finally {
                runCatching { fileOutputStream?.close() }
                runCatching { socket.close() }
                pendingSocket = null
                pendingTransferInfo = null
            }
        }
    }
    
    /**
     * Reject the pending incoming transfer.
     */
    fun rejectTransfer() {
        val socket = pendingSocket ?: return
        
        scope.launch(Dispatchers.IO) {
            try {
                val output = socket.getOutputStream()
                val rejectPayload = "{\"accepted\":false}".toByteArray()
                output.write(buildMessage(MSG_FILE_REJECT, rejectPayload))
                output.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Error sending reject", e)
            } finally {
                socket.close()
                pendingSocket = null
                pendingTransferInfo = null
            }
        }
    }
    
    fun stopBackgroundServer() {
        isServerRunning.set(false)
        backgroundServerJob?.cancel()
        backgroundServerJob = null
        pendingSocket?.close()
        pendingSocket = null
        pendingTransferInfo = null
    }
    
    fun isBackgroundServerRunning(): Boolean = isServerRunning.get()

    // ==========================================================================
    // Device Discovery
    // ==========================================================================
    
    override fun discoverDevices(onFound: (PeerDevice) -> Unit) {
        stopDiscovery()
        discoveredDevices.clear()
        isDiscoveryRunning.set(true)
        startDiscoveryListener(onFound)
        startDiscoveryBroadcaster()
    }
    
    private fun startDiscoveryListener(onFound: (PeerDevice) -> Unit) {
        discoveryListenerJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(DISCOVERY_PORT)
                socket.broadcast = true
                socket.soTimeout = 500
                socket.reuseAddress = true
                
                Log.d(TAG, "Discovery listener started on port $DISCOVERY_PORT")
                
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                val startTime = System.currentTimeMillis()
                while (isActive && isDiscoveryRunning.get() && 
                       System.currentTimeMillis() - startTime < DISCOVERY_LISTEN_DURATION_MS) {
                    try {
                        socket.receive(packet)
                        val sourceIp = packet.address.hostAddress ?: continue
                        val data = buffer.copyOf(packet.length)
                        handleDiscoveryPacket(data, sourceIp, socket, onFound)
                    } catch (e: java.net.SocketTimeoutException) {
                        // Expected
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery listener error", e)
            } finally {
                socket?.close()
            }
        }
    }
    
    private fun startDiscoveryBroadcaster() {
        discoveryBroadcasterJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                
                val broadcastAddr = getBroadcastAddress()
                
                while (isActive && isDiscoveryRunning.get()) {
                    try {
                        val searchPacket = buildDiscoverySearch()
                        socket.send(DatagramPacket(searchPacket, searchPacket.size, 
                            InetAddress.getByName(broadcastAddr), DISCOVERY_PORT))
                        
                        val announcePacket = buildDiscoveryAnnounce()
                        socket.send(DatagramPacket(announcePacket, announcePacket.size,
                            InetAddress.getByName(broadcastAddr), DISCOVERY_PORT))
                        
                        delay(DISCOVERY_ANNOUNCE_INTERVAL_MS)
                    } catch (e: Exception) {
                        Log.w(TAG, "Broadcast send error", e)
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery broadcaster error", e)
            } finally {
                socket?.close()
            }
        }
    }
    
    private suspend fun handleDiscoveryPacket(
        data: ByteArray, 
        sourceIp: String, 
        socket: DatagramSocket,
        onFound: (PeerDevice) -> Unit
    ) {
        if (data.isEmpty()) return
        val msgType = data[0].toInt() and 0xFF
        
        when (msgType) {
            DISCOVERY_SEARCH -> {
                val response = buildDiscoveryResponse()
                socket.send(DatagramPacket(response, response.size,
                    InetAddress.getByName(sourceIp), DISCOVERY_PORT))
            }
            DISCOVERY_ANNOUNCE, DISCOVERY_RESPONSE -> {
                parseDiscoveryPacket(data, sourceIp)?.let { device ->
                    if (device.id != deviceId && !discoveredDevices.containsKey(device.id)) {
                        discoveredDevices[device.id] = device
                        withContext(Dispatchers.Main) { onFound(device) }
                    }
                }
            }
        }
    }
    
    private fun stopDiscovery() {
        isDiscoveryRunning.set(false)
        discoveryListenerJob?.cancel()
        discoveryBroadcasterJob?.cancel()
    }
    
    private fun getBroadcastAddress(): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            if (dhcpInfo.ipAddress != 0) {
                val broadcast = (dhcpInfo.ipAddress and dhcpInfo.netmask) or dhcpInfo.netmask.inv()
                return String.format("%d.%d.%d.%d",
                    broadcast and 0xff, (broadcast shr 8) and 0xff,
                    (broadcast shr 16) and 0xff, (broadcast shr 24) and 0xff)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get broadcast address", e)
        }
        return "255.255.255.255"
    }
    
    private fun buildDiscoveryAnnounce() = buildDiscoveryMessage(DISCOVERY_ANNOUNCE)
    private fun buildDiscoverySearch(): ByteArray {
        val buffer = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DISCOVERY_SEARCH.toByte())
        buffer.putShort(deviceId.length.toShort())
        buffer.put(deviceId.toByteArray())
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }
    private fun buildDiscoveryResponse() = buildDiscoveryMessage(DISCOVERY_RESPONSE)
    
    private fun buildDiscoveryMessage(type: Int): ByteArray {
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
        buffer.put(type.toByte())
        buffer.putShort(PROTOCOL_VERSION.length.toShort())
        buffer.put(PROTOCOL_VERSION.toByteArray())
        buffer.putShort(deviceId.length.toShort())
        buffer.put(deviceId.toByteArray())
        buffer.putShort(deviceName.length.toShort())
        buffer.put(deviceName.toByteArray())
        buffer.putShort(DEFAULT_PORT.toShort())
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }
    
    private fun parseDiscoveryPacket(data: ByteArray, sourceIp: String): PeerDevice? {
        if (data.size < 10) return null
        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            buffer.get()
            val versionLen = buffer.short.toInt() and 0xFFFF
            if (versionLen > 100) return null
            buffer.position(buffer.position() + versionLen)
            val idLen = buffer.short.toInt() and 0xFFFF
            if (idLen > 100) return null
            val idBytes = ByteArray(idLen)
            buffer.get(idBytes)
            val nameLen = buffer.short.toInt() and 0xFFFF
            if (nameLen > 200) return null
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            return PeerDevice(id = String(idBytes), displayName = String(nameBytes), 
                ipAddress = sourceIp, protocol = "QUIC")
        } catch (e: Exception) { return null }
    }

    // ==========================================================================
    // High-Speed File Sending
    // ==========================================================================
    
    override fun startSend(
        filePath: String,
        targetIp: String,
        onProgress: (Long, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        cancelActiveTransfer()
        
        activeTransferJob = scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            var inputStream: InputStream? = null
            
            try {
                val sourceInfo = resolveSource(filePath)
                if (sourceInfo == null) {
                    withContext(Dispatchers.Main) { onError("File not found") }
                    return@launch
                }
                
                val (stream, fileName, totalBytes) = sourceInfo
                inputStream = stream
                
                transferStartTime = System.currentTimeMillis()
                totalBytesTransferred.set(0L)
                speedBytesPerSec.set(0L)
                
                Log.d(TAG, "Connecting to $targetIp:$DEFAULT_PORT for $fileName ($totalBytes bytes)")
                
                // Connect with MAX SPEED settings
                socket = Socket()
                socket.connect(InetSocketAddress(targetIp, DEFAULT_PORT), 10000)
                socket.tcpNoDelay = true  // Disable Nagle's algorithm
                socket.sendBufferSize = SOCKET_BUFFER_SIZE
                socket.receiveBufferSize = SOCKET_BUFFER_SIZE
                socket.setSoLinger(true, 10)
                
                // Use raw streams for maximum throughput
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                
                // Handshake
                if (!performSenderHandshake(fileName, totalBytes, input, output)) {
                    withContext(Dispatchers.Main) { onError("Handshake failed or rejected") }
                    return@launch
                }
                
                Log.d(TAG, "Handshake complete, starting high-speed transfer")
                
                // Transfer with optimized 16MB chunks
                val chunkBuffer = ByteArray(CHUNK_SIZE)
                var offset = 0L
                var chunkIndex = 0
                var lastProgressTime = System.currentTimeMillis()
                var bytesInPeriod = 0L
                
                while (isActive && offset < totalBytes) {
                    val remaining = (totalBytes - offset).toInt()
                    val toRead = min(CHUNK_SIZE, remaining)
                    
                    // Read in optimal-size pieces
                    var bytesRead = 0
                    while (bytesRead < toRead) {
                        val n = inputStream.read(chunkBuffer, bytesRead, min(RAW_IO_BUFFER_SIZE, toRead - bytesRead))
                        if (n < 0) break
                        bytesRead += n
                    }
                    
                    if (bytesRead <= 0) break
                    
                    // Send header + data in single write for efficiency
                    val header = buildChunkHeader(chunkIndex, bytesRead)
                    output.write(header)
                    output.write(chunkBuffer, 0, bytesRead)
                    // NO FLUSH - let TCP buffer optimize
                    
                    offset += bytesRead
                    chunkIndex++
                    totalBytesTransferred.set(offset)
                    bytesInPeriod += bytesRead
                    
                    // Update speed every 150ms for responsiveness
                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime >= 150) {
                        val elapsed = now - lastProgressTime
                        val speed = if (elapsed > 0) (bytesInPeriod * 1000) / elapsed else 0L
                        speedBytesPerSec.set(speed)
                        bytesInPeriod = 0L
                        lastProgressTime = now
                        
                        withContext(Dispatchers.Main) { onProgress(offset, totalBytes, speed) }
                    }
                }
                
                // Final flush
                output.flush()
                
                // Transfer complete
                val completeMsg = buildMessage(MSG_TRANSFER_COMPLETE, "{\"status\":\"complete\"}".toByteArray())
                output.write(completeMsg)
                output.flush()
                
                // Wait for verification
                val verifyBuffer = ByteArray(256)
                val verifyLen = input.read(verifyBuffer)
                
                speedBytesPerSec.set(0L)
                
                if (verifyLen > 4 && verifyBuffer[4].toInt() == MSG_TRANSFER_VERIFIED) {
                    val avgSpeed = if (System.currentTimeMillis() - transferStartTime > 0) {
                        (totalBytes * 1000) / (System.currentTimeMillis() - transferStartTime)
                    } else 0L
                    Log.d(TAG, "Transfer complete! Avg: ${avgSpeed / 1024 / 1024} MB/s")
                    withContext(Dispatchers.Main) { onComplete() }
                } else {
                    withContext(Dispatchers.Main) { onError("Verification failed") }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
                speedBytesPerSec.set(0L)
                withContext(Dispatchers.Main) { onError(e.message ?: "Transfer failed") }
            } finally {
                runCatching { inputStream?.close() }
                runCatching { socket?.close() }
            }
        }
    }
    
    private fun performSenderHandshake(
        fileName: String, totalBytes: Long,
        input: InputStream, output: OutputStream
    ): Boolean {
        // HELLO
        val helloPayload = "{\"device_id\":\"$deviceId\",\"device_name\":\"$deviceName\",\"platform\":\"android\",\"protocol_version\":\"$PROTOCOL_VERSION\"}".toByteArray()
        output.write(buildMessage(MSG_HELLO, helloPayload))
        output.flush()
        
        val ackBuffer = ByteArray(1024)
        val ackLen = input.read(ackBuffer)
        if (ackLen <= 4 || ackBuffer[4].toInt() != MSG_HELLO_ACK) return false
        
        // FILE_OFFER
        val chunkCount = ((totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        val offerPayload = "{\"transfer_id\":\"${UUID.randomUUID()}\",\"file_name\":\"$fileName\",\"file_size\":$totalBytes,\"chunk_size\":$CHUNK_SIZE,\"chunk_count\":$chunkCount}".toByteArray()
        output.write(buildMessage(MSG_FILE_OFFER, offerPayload))
        output.flush()
        
        val acceptBuffer = ByteArray(1024)
        val acceptLen = input.read(acceptBuffer)
        if (acceptLen <= 4 || acceptBuffer[4].toInt() != MSG_FILE_ACCEPT) return false
        
        return true
    }

    // ==========================================================================
    // Legacy Receive (for manual start)
    // ==========================================================================
    
    override fun startReceive(
        savePath: String,
        onProgress: (Long, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Start background server which will handle incoming connections
        startBackgroundServer(
            onIncoming = { pending ->
                // Auto-accept for legacy mode
                acceptTransfer()
            },
            onProgress = onProgress,
            onComplete = onComplete,
            onError = onError
        )
    }
    
    override fun cancelActiveTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        stopBackgroundServer()
        stopDiscovery()
        speedBytesPerSec.set(0L)
    }
    
    override fun getTransferSpeed(): Long = speedBytesPerSec.get()

    // ==========================================================================
    // File Resolution
    // ==========================================================================
    
    private fun resolveSource(filePath: String): Triple<InputStream, String, Long>? {
        val directFile = File(filePath)
        if (directFile.exists() && directFile.canRead()) {
            return Triple(FileInputStream(directFile), directFile.name, directFile.length())
        }
        
        val uri = runCatching { Uri.parse(filePath) }.getOrNull() ?: return null
        if (uri.scheme != "content") return null
        
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "shared_file"
                val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                Pair(name, size)
            } else null
        } ?: return null
        
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        return Triple(inputStream, displayName.first, displayName.second)
    }
    
    /**
     * Resolve save file to Downloads/project_alpha folder.
     */
    private fun resolveSaveFile(fileName: String): File {
        // Primary: Public Downloads/project_alpha
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val projectAlphaDir = File(publicDownloads, SAVE_FOLDER_NAME)
        
        if (projectAlphaDir.exists() || projectAlphaDir.mkdirs()) {
            return File(projectAlphaDir, sanitizeFileName(fileName))
        }
        
        // Fallback: App-specific Downloads
        val appDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appDownloads != null) {
            val appProjectAlpha = File(appDownloads, SAVE_FOLDER_NAME)
            if (appProjectAlpha.exists() || appProjectAlpha.mkdirs()) {
                return File(appProjectAlpha, sanitizeFileName(fileName))
            }
        }
        
        // Last resort
        return File(context.filesDir, sanitizeFileName(fileName))
    }
    
    private fun sanitizeFileName(name: String): String {
        var safe = name.replace(Regex("[<>:\"|?*\\\\]"), "_")
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val projectAlphaDir = File(baseDir, SAVE_FOLDER_NAME)
        val targetFile = File(projectAlphaDir, safe)
        if (targetFile.exists()) {
            val baseName = targetFile.nameWithoutExtension
            val ext = targetFile.extension
            safe = "${baseName}_${System.currentTimeMillis()}${if (ext.isNotEmpty()) ".$ext" else ""}"
        }
        return safe
    }

    // ==========================================================================
    // Message Building
    // ==========================================================================
    
    private fun buildMessage(msgType: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(5 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(payload.size)
        buffer.put(msgType.toByte())
        buffer.put(payload)
        return buffer.array()
    }
    
    private fun buildChunkHeader(chunkIndex: Int, chunkSize: Int): ByteArray {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(chunkIndex)
        buffer.putInt(chunkSize)
        buffer.putInt(0)  // Reserved
        return buffer.array()
    }
    
    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    // ==========================================================================
    // Connectivity Helpers
    // ==========================================================================
    
    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    fun getLocalIpAddress(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                return String.format("%d.%d.%d.%d",
                    ip and 0xff, (ip shr 8) and 0xff,
                    (ip shr 16) and 0xff, (ip shr 24) and 0xff)
            }
        } catch (e: Exception) { }
        return null
    }
}
