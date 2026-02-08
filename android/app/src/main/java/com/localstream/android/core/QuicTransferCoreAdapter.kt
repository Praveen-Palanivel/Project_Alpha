package com.localstream.android.core

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.util.Log
import com.localstream.android.ui.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Real implementation of TransferCoreAdapter using QUIC protocol.
 * 
 * Implements the LocalStream protocol as defined in docs/protocol.md and docs/discovery.md.
 * Uses TCP fallback when QUIC is not available (QUIC via Cronet requires additional setup).
 */
class QuicTransferCoreAdapter(
    private val context: Context,
    private val scope: CoroutineScope
) : TransferCoreAdapter {

    companion object {
        private const val TAG = "QuicTransferCore"
        
        // Protocol constants from docs/protocol.md
        const val PROTOCOL_VERSION = "1.0.0"
        const val DEFAULT_QUIC_PORT = 42424
        const val DISCOVERY_PORT = 42425
        const val CHUNK_SIZE = 4 * 1024 * 1024  // 4 MB
        const val PARALLEL_STREAMS = 4
        
        // Message types (from protocol.md)
        const val MSG_HELLO = 0x01
        const val MSG_HELLO_ACK = 0x02
        const val MSG_FILE_OFFER = 0x10
        const val MSG_FILE_ACCEPT = 0x11
        const val MSG_FILE_REJECT = 0x12
        const val MSG_CHUNK_ACK = 0x20
        const val MSG_TRANSFER_COMPLETE = 0x30
        const val MSG_TRANSFER_VERIFIED = 0x31
        const val MSG_CANCEL = 0x40
        const val MSG_ERROR = 0xFF
        
        // Discovery message types
        const val DISCOVERY_ANNOUNCE = 0x01
        const val DISCOVERY_SEARCH = 0x02
        const val DISCOVERY_RESPONSE = 0x03
    }
    
    private var activeTransferJob: Job? = null
    private var discoveryJob: Job? = null
    private var receiveServerJob: Job? = null
    
    private var speedBytesPerSec: Long = 0L
    private var transferStartTime: Long = 0L
    private var totalBytesTransferred: Long = 0L
    
    private val deviceId: String = UUID.randomUUID().toString().take(8)
    private val deviceName: String = android.os.Build.MODEL
    
    private val discoveredDevices = ConcurrentHashMap<String, PeerDevice>()
    
    // ==========================================================================
    // Device Discovery (UDP Broadcast per discovery.md)
    // ==========================================================================
    
    override fun discoverDevices(onFound: (PeerDevice) -> Unit) {
        discoveryJob?.cancel()
        discoveredDevices.clear()
        
        discoveryJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 100
                
                // Get broadcast address
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val dhcpInfo = wifiManager.dhcpInfo
                val broadcastAddr = getBroadcastAddress(dhcpInfo.ipAddress, dhcpInfo.netmask)
                
                Log.d(TAG, "Starting discovery on $broadcastAddr")
                
                // Send DISCOVERY_SEARCH
                val searchPacket = buildDiscoverySearch()
                val sendPacket = DatagramPacket(
                    searchPacket,
                    searchPacket.size,
                    InetAddress.getByName(broadcastAddr),
                    DISCOVERY_PORT
                )
                socket.send(sendPacket)
                
                // Also send our ANNOUNCE so others can find us
                val announcePacket = buildDiscoveryAnnounce()
                val announceDatagramPacket = DatagramPacket(
                    announcePacket,
                    announcePacket.size,
                    InetAddress.getByName(broadcastAddr),
                    DISCOVERY_PORT
                )
                socket.send(announceDatagramPacket)
                
                // Listen for responses
                val buffer = ByteArray(1024)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                
                repeat(30) { // Listen for 3 seconds
                    if (!isActive) return@launch
                    
                    try {
                        socket.receive(receivePacket)
                        val response = parseDiscoveryResponse(
                            buffer.copyOf(receivePacket.length),
                            receivePacket.address.hostAddress ?: ""
                        )
                        response?.let { device ->
                            if (device.id != deviceId && !discoveredDevices.containsKey(device.id)) {
                                discoveredDevices[device.id] = device
                                withContext(Dispatchers.Main) {
                                    onFound(device)
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Expected - continue listening
                    }
                    delay(100)
                }
                
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
            }
        }
    }
    
    private fun getBroadcastAddress(ip: Int, netmask: Int): String {
        val broadcast = (ip and netmask) or netmask.inv()
        return String.format(
            "%d.%d.%d.%d",
            broadcast and 0xff,
            (broadcast shr 8) and 0xff,
            (broadcast shr 16) and 0xff,
            (broadcast shr 24) and 0xff
        )
    }
    
    private fun buildDiscoveryAnnounce(): ByteArray {
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DISCOVERY_ANNOUNCE.toByte())
        buffer.putShort(PROTOCOL_VERSION.length.toShort())
        buffer.put(PROTOCOL_VERSION.toByteArray())
        buffer.putShort(deviceId.length.toShort())
        buffer.put(deviceId.toByteArray())
        buffer.putShort(deviceName.length.toShort())
        buffer.put(deviceName.toByteArray())
        buffer.putShort(DEFAULT_QUIC_PORT.toShort())
        
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }
    
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
    
    private fun parseDiscoveryResponse(data: ByteArray, sourceIp: String): PeerDevice? {
        if (data.isEmpty()) return null
        
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val msgType = buffer.get().toInt()
        
        if (msgType != DISCOVERY_ANNOUNCE && msgType != DISCOVERY_RESPONSE) {
            return null
        }
        
        try {
            // Skip protocol version
            val versionLen = buffer.short.toInt()
            val versionBytes = ByteArray(versionLen)
            buffer.get(versionBytes)
            
            // Read device ID
            val idLen = buffer.short.toInt()
            val idBytes = ByteArray(idLen)
            buffer.get(idBytes)
            val peerId = String(idBytes)
            
            // Read device name
            val nameLen = buffer.short.toInt()
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val peerName = String(nameBytes)
            
            // Read port
            val port = buffer.short.toInt() and 0xFFFF
            
            return PeerDevice(
                id = peerId,
                displayName = peerName,
                ipAddress = sourceIp,
                protocol = "QUIC"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse discovery response", e)
            return null
        }
    }
    
    // ==========================================================================
    // File Sending (TCP fallback - QUIC via Cronet requires additional setup)
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
            var temporarySourceFile: File? = null
            try {
                val source = resolveSourceFile(filePath)
                if (source == null) {
                    withContext(Dispatchers.Main) { onError("File not found") }
                    return@launch
                }
                val (file, displayName, isTemporary) = source
                if (isTemporary) {
                    temporarySourceFile = file
                }
                
                val totalBytes = file.length()
                transferStartTime = System.currentTimeMillis()
                totalBytesTransferred = 0L
                
                Log.d(TAG, "Connecting to $targetIp:$DEFAULT_QUIC_PORT")
                
                // Connect via TCP (fallback - production should use QUIC)
                val socket = Socket()
                socket.connect(InetSocketAddress(targetIp, DEFAULT_QUIC_PORT), 5000)
                socket.tcpNoDelay = true
                socket.sendBufferSize = 8 * 1024 * 1024
                
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                
                // Handshake
                val helloMsg = buildHelloMessage()
                output.write(helloMsg)
                output.flush()
                
                val helloAckBuffer = ByteArray(1024)
                val ackLen = input.read(helloAckBuffer)
                if (ackLen <= 0 || helloAckBuffer[4].toInt() != MSG_HELLO_ACK) {
                    withContext(Dispatchers.Main) { onError("Handshake failed") }
                    socket.close()
                    return@launch
                }
                
                Log.d(TAG, "Handshake complete, offering file: ${file.name}")
                
                // File offer
                val fileChecksum = computeFileChecksum(file)
                val offerMsg = buildFileOffer(displayName, totalBytes, fileChecksum)
                output.write(offerMsg)
                output.flush()
                
                val acceptBuffer = ByteArray(1024)
                val acceptLen = input.read(acceptBuffer)
                if (acceptLen <= 0 || acceptBuffer[4].toInt() != MSG_FILE_ACCEPT) {
                    withContext(Dispatchers.Main) { onError("File rejected") }
                    socket.close()
                    return@launch
                }
                
                Log.d(TAG, "File accepted, starting transfer")
                
                // Transfer chunks
                val raf = RandomAccessFile(file, "r")
                val chunkBuffer = ByteArray(CHUNK_SIZE)
                var offset = 0L
                var lastProgressTime = System.currentTimeMillis()
                var bytesInPeriod = 0L
                
                while (isActive && offset < totalBytes) {
                    val remaining = (totalBytes - offset).toInt()
                    val toRead = min(CHUNK_SIZE, remaining)
                    
                    raf.seek(offset)
                    raf.readFully(chunkBuffer, 0, toRead)
                    
                    // Send chunk header + data
                    val chunkIndex = (offset / CHUNK_SIZE).toInt()
                    val chunkHeader = buildChunkHeader(chunkIndex, toRead, chunkBuffer.copyOf(toRead))
                    output.write(chunkHeader)
                    output.write(chunkBuffer, 0, toRead)
                    output.flush()
                    
                    offset += toRead
                    totalBytesTransferred = offset
                    bytesInPeriod += toRead
                    
                    // Update speed every 200ms
                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime >= 200) {
                        speedBytesPerSec = (bytesInPeriod * 1000) / (now - lastProgressTime)
                        bytesInPeriod = 0L
                        lastProgressTime = now
                        
                        withContext(Dispatchers.Main) {
                            onProgress(totalBytesTransferred, totalBytes, speedBytesPerSec)
                        }
                    }
                }
                
                raf.close()
                
                // Send transfer complete
                val completeMsg = buildTransferComplete()
                output.write(completeMsg)
                output.flush()
                
                // Wait for verification
                val verifyBuffer = ByteArray(256)
                val verifyLen = input.read(verifyBuffer)
                
                socket.close()
                speedBytesPerSec = 0L
                
                if (verifyLen > 0 && verifyBuffer[4].toInt() == MSG_TRANSFER_VERIFIED) {
                    Log.d(TAG, "Transfer verified successfully")
                    withContext(Dispatchers.Main) { onComplete() }
                } else {
                    withContext(Dispatchers.Main) { onError("Transfer verification failed") }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
                speedBytesPerSec = 0L
                withContext(Dispatchers.Main) { onError(e.message ?: "Transfer failed") }
            } finally {
                runCatching { temporarySourceFile?.delete() }
            }
        }
    }
    
    // ==========================================================================
    // File Receiving
    // ==========================================================================
    
    override fun startReceive(
        savePath: String,
        onProgress: (Long, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        cancelActiveTransfer()
        
        receiveServerJob = scope.launch(Dispatchers.IO) {
            try {
                val serverSocket = java.net.ServerSocket(DEFAULT_QUIC_PORT)
                serverSocket.soTimeout = 60000  // 60 second timeout for connection
                
                Log.d(TAG, "Receiver listening on port $DEFAULT_QUIC_PORT")
                
                val clientSocket = serverSocket.accept()
                clientSocket.receiveBufferSize = 8 * 1024 * 1024
                
                val input = clientSocket.getInputStream()
                val output = clientSocket.getOutputStream()
                
                // Read HELLO
                val helloBuffer = ByteArray(1024)
                val helloLen = input.read(helloBuffer)
                if (helloLen <= 0 || helloBuffer[4].toInt() != MSG_HELLO) {
                    withContext(Dispatchers.Main) { onError("Invalid handshake") }
                    clientSocket.close()
                    serverSocket.close()
                    return@launch
                }
                
                // Send HELLO_ACK
                val ackMsg = buildHelloAck()
                output.write(ackMsg)
                output.flush()
                
                // Read FILE_OFFER
                val offerBuffer = ByteArray(4096)
                val offerLen = input.read(offerBuffer)
                if (offerLen <= 0 || offerBuffer[4].toInt() != MSG_FILE_OFFER) {
                    withContext(Dispatchers.Main) { onError("No file offer received") }
                    clientSocket.close()
                    serverSocket.close()
                    return@launch
                }
                
                val (fileName, totalBytes, expectedChecksum) = parseFileOffer(offerBuffer, offerLen)
                Log.d(TAG, "File offer: $fileName, $totalBytes bytes")
                
                // Send FILE_ACCEPT
                val receiveDir = resolveReceiveDirectory(savePath)
                if (!receiveDir.exists()) {
                    receiveDir.mkdirs()
                }
                val saveFile = File(receiveDir, fileName)
                val acceptMsg = buildFileAccept(saveFile.absolutePath)
                output.write(acceptMsg)
                output.flush()
                
                // Receive chunks
                transferStartTime = System.currentTimeMillis()
                totalBytesTransferred = 0L
                var lastProgressTime = System.currentTimeMillis()
                var bytesInPeriod = 0L
                
                val raf = RandomAccessFile(saveFile, "rw")
                raf.setLength(totalBytes)
                
                val chunkHeaderBuffer = ByteArray(20)  // Chunk header size
                val chunkDataBuffer = ByteArray(CHUNK_SIZE)
                
                while (isActive && totalBytesTransferred < totalBytes) {
                    // Read chunk header
                    var headerRead = 0
                    while (headerRead < 20) {
                        val n = input.read(chunkHeaderBuffer, headerRead, 20 - headerRead)
                        if (n < 0) break
                        headerRead += n
                    }
                    
                    if (headerRead < 20) break
                    
                    val headerBuf = ByteBuffer.wrap(chunkHeaderBuffer).order(ByteOrder.BIG_ENDIAN)
                    val chunkIndex = headerBuf.getInt()
                    val chunkSize = headerBuf.getInt()
                    // Skip checksum bytes for now
                    
                    // Read chunk data
                    var dataRead = 0
                    while (dataRead < chunkSize) {
                        val n = input.read(chunkDataBuffer, dataRead, chunkSize - dataRead)
                        if (n < 0) break
                        dataRead += n
                    }
                    
                    // Write to file
                    val offset = chunkIndex.toLong() * CHUNK_SIZE
                    raf.seek(offset)
                    raf.write(chunkDataBuffer, 0, chunkSize)
                    
                    totalBytesTransferred += chunkSize
                    bytesInPeriod += chunkSize
                    
                    // Update progress
                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime >= 200) {
                        speedBytesPerSec = (bytesInPeriod * 1000) / (now - lastProgressTime)
                        bytesInPeriod = 0L
                        lastProgressTime = now
                        
                        withContext(Dispatchers.Main) {
                            onProgress(totalBytesTransferred, totalBytes, speedBytesPerSec)
                        }
                    }
                }
                
                raf.close()
                
                // Wait for TRANSFER_COMPLETE
                val completeBuffer = ByteArray(256)
                input.read(completeBuffer)
                
                // Verify file
                val actualChecksum = computeFileChecksum(saveFile)
                val verified = actualChecksum == expectedChecksum
                
                // Send verification result
                val verifyMsg = buildTransferVerified(verified)
                output.write(verifyMsg)
                output.flush()
                
                clientSocket.close()
                serverSocket.close()
                speedBytesPerSec = 0L
                
                if (verified) {
                    Log.d(TAG, "File received and verified: ${saveFile.absolutePath}")
                    withContext(Dispatchers.Main) { onComplete() }
                } else {
                    Log.w(TAG, "File checksum mismatch!")
                    withContext(Dispatchers.Main) { onError("Checksum verification failed") }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Receive error", e)
                speedBytesPerSec = 0L
                withContext(Dispatchers.Main) { onError(e.message ?: "Receive failed") }
            }
        }
    }
    
    override fun cancelActiveTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        receiveServerJob?.cancel()
        receiveServerJob = null
        speedBytesPerSec = 0L
    }
    
    override fun getTransferSpeed(): Long = speedBytesPerSec
    
    // ==========================================================================
    // Message Builders (per protocol.md)
    // ==========================================================================
    
    private fun buildHelloMessage(): ByteArray {
        val payload = buildString {
            append("{\"device_id\":\"$deviceId\",")
            append("\"device_name\":\"$deviceName\",")
            append("\"platform\":\"android\",")
            append("\"protocol_version\":\"$PROTOCOL_VERSION\",")
            append("\"capabilities\":[\"quic\",\"tcp\",\"resume\"]}")
        }.toByteArray()
        
        return buildMessage(MSG_HELLO, payload)
    }
    
    private fun buildHelloAck(): ByteArray {
        val payload = "{\"accepted\":true,\"device_id\":\"$deviceId\",\"device_name\":\"$deviceName\"}".toByteArray()
        return buildMessage(MSG_HELLO_ACK, payload)
    }
    
    private fun buildFileOffer(fileName: String, fileSize: Long, checksum: String): ByteArray {
        val transferId = UUID.randomUUID().toString()
        val chunkCount = ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        
        val payload = buildString {
            append("{\"transfer_id\":\"$transferId\",")
            append("\"file_name\":\"$fileName\",")
            append("\"file_size\":$fileSize,")
            append("\"file_checksum\":\"$checksum\",")
            append("\"chunk_size\":$CHUNK_SIZE,")
            append("\"chunk_count\":$chunkCount}")
        }.toByteArray()
        
        return buildMessage(MSG_FILE_OFFER, payload)
    }
    
    private fun buildFileAccept(savePath: String): ByteArray {
        val payload = "{\"accepted\":true,\"save_path\":\"$savePath\"}".toByteArray()
        return buildMessage(MSG_FILE_ACCEPT, payload)
    }
    
    private fun buildChunkHeader(chunkIndex: Int, chunkSize: Int, data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(chunkIndex)
        buffer.putInt(chunkSize)
        // 8 bytes for checksum (xxHash64 placeholder - using simple hash for now)
        val hash = data.hashCode().toLong()
        buffer.putLong(hash)
        return buffer.array()
    }
    
    private fun buildTransferComplete(): ByteArray {
        val payload = "{\"status\":\"complete\"}".toByteArray()
        return buildMessage(MSG_TRANSFER_COMPLETE, payload)
    }
    
    private fun buildTransferVerified(success: Boolean): ByteArray {
        val payload = "{\"verified\":$success}".toByteArray()
        return buildMessage(MSG_TRANSFER_VERIFIED, payload)
    }
    
    private fun buildMessage(msgType: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(5 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(payload.size)
        buffer.put(msgType.toByte())
        buffer.put(payload)
        return buffer.array()
    }
    
    private fun parseFileOffer(data: ByteArray, len: Int): Triple<String, Long, String> {
        // Skip header (4 bytes length + 1 byte type)
        val jsonStr = String(data, 5, len - 5)
        
        // Simple JSON parsing (production should use proper JSON library)
        val fileName = Regex("\"file_name\":\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1) ?: "unknown"
        val fileSize = Regex("\"file_size\":([0-9]+)").find(jsonStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val checksum = Regex("\"file_checksum\":\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1) ?: ""
        
        return Triple(fileName, fileSize, checksum)
    }
    
    private fun computeFileChecksum(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveSourceFile(filePath: String): Triple<File, String, Boolean>? {
        val directFile = File(filePath)
        if (directFile.exists()) {
            return Triple(directFile, directFile.name, false)
        }

        val uri = runCatching { Uri.parse(filePath) }.getOrNull() ?: return null
        if (uri.scheme != "content") {
            return null
        }

        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        } ?: "shared_file"

        val tmpFile = File(context.cacheDir, "localstream_${System.currentTimeMillis()}_$name")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
        }.isSuccess

        return if (copied && tmpFile.exists()) {
            Triple(tmpFile, name, true)
        } else {
            null
        }
    }

    private fun resolveReceiveDirectory(savePath: String): File {
        if (savePath.isNotBlank()) {
            val candidate = File(savePath)
            if (candidate.isAbsolute) {
                return candidate
            }
        }

        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
    }
}
