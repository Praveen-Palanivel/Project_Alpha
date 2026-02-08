package com.localstream.android.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localstream.android.core.ConnectionSetupHelper
import com.localstream.android.core.QuicTransferCoreAdapter
import com.localstream.android.core.TransferCoreAdapter
import com.localstream.android.ui.model.ConnectionStatus
import com.localstream.android.ui.model.ConnectionType
import com.localstream.android.ui.model.LocalStreamMode
import com.localstream.android.ui.model.LocalStreamUiState
import com.localstream.android.ui.model.PeerDevice
import com.localstream.android.ui.model.PendingTransfer
import com.localstream.android.ui.model.TransferSnapshot
import com.localstream.android.ui.model.TransferStatus
import com.localstream.android.ui.model.TransportMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

class LocalStreamViewModel(
    private val transferCoreAdapter: TransferCoreAdapter,
    private val connectionHelper: ConnectionSetupHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalStreamUiState())
    val uiState: StateFlow<LocalStreamUiState> = _uiState.asStateFlow()

    private var lastTransferMode: LocalStreamMode = LocalStreamMode.SEND
    
    // Cast for background server access
    private val quicAdapter: QuicTransferCoreAdapter?
        get() = transferCoreAdapter as? QuicTransferCoreAdapter

    init {
        checkConnection()
    }

    // ==========================================================================
    // Connection Management
    // ==========================================================================
    
    fun checkConnection() {
        val status = connectionHelper.checkConnectionStatus()
        _uiState.update { 
            it.copy(
                connection = ConnectionStatus(
                    isConnected = status.isConnected,
                    connectionType = when (status.connectionType) {
                        ConnectionSetupHelper.ConnectionType.WIFI -> ConnectionType.WIFI
                        ConnectionSetupHelper.ConnectionType.HOTSPOT -> ConnectionType.HOTSPOT
                        ConnectionSetupHelper.ConnectionType.NONE -> ConnectionType.NONE
                    },
                    localIpAddress = status.localIpAddress,
                    networkName = status.ssid,
                    statusMessage = status.message
                ),
                showConnectionPrompt = !status.isConnected
            )
        }
        
        // Auto-start background server if connected and in receive mode
        if (status.isConnected) {
            refreshDevices()
            if (_uiState.value.mode == LocalStreamMode.RECEIVE) {
                startBackgroundServer()
            }
        }
    }
    
    fun dismissConnectionPrompt() {
        _uiState.update { it.copy(showConnectionPrompt = false) }
    }
    
    fun getWifiSettingsIntent(): Intent = connectionHelper.getWifiSettingsIntent()
    
    fun getHotspotSettingsIntent(): Intent = connectionHelper.getHotspotSettingsIntent()
    
    fun getSetupInstructions(): String {
        val role = when (_uiState.value.mode) {
            LocalStreamMode.SEND -> ConnectionSetupHelper.TransferRole.SENDER
            LocalStreamMode.RECEIVE -> ConnectionSetupHelper.TransferRole.RECEIVER
        }
        return connectionHelper.getSetupInstructions(role)
    }

    // ==========================================================================
    // Transport Mode Selection
    // ==========================================================================
    
    fun setTransportMode(mode: TransportMode) {
        _uiState.update { 
            it.copy(
                transportMode = mode,
                lastActionHint = when (mode) {
                    TransportMode.WIFI -> "WiFi selected - connect to same network"
                    TransportMode.HOTSPOT -> "Hotspot selected - create hotspot for receiver"
                    TransportMode.BLUETOOTH -> "Bluetooth selected - slower, for small files"
                }
            )
        }
    }

    // ==========================================================================
    // Mode Selection
    // ==========================================================================
    
    fun setMode(mode: LocalStreamMode) {
        _uiState.update { it.copy(mode = mode) }
        
        // Check connection and show prompt if needed
        checkConnection()
        
        // Auto-start background server when in receive mode
        if (mode == LocalStreamMode.RECEIVE && _uiState.value.connection.isConnected) {
            startBackgroundServer()
        } else if (mode == LocalStreamMode.SEND) {
            // Stop background server when switching to send mode
            stopBackgroundServer()
        }
    }
    
    // ==========================================================================
    // Background Server (Auto-Accept Flow)
    // ==========================================================================
    
    private fun startBackgroundServer() {
        val adapter = quicAdapter ?: return
        
        if (adapter.isBackgroundServerRunning()) {
            _uiState.update { it.copy(isServerRunning = true) }
            return
        }
        
        adapter.startBackgroundServer(
            onIncoming = { pending ->
                // Show accept/reject dialog
                _uiState.update {
                    it.copy(
                        pendingTransfer = pending,
                        showAcceptDialog = true,
                        transfer = it.transfer.copy(
                            status = TransferStatus.WAITING_ACCEPT,
                            fileName = pending.fileName,
                            totalBytes = pending.fileSize
                        ),
                        lastActionHint = "Incoming: ${pending.fileName} (${formatBytes(pending.fileSize)}) from ${pending.senderName}"
                    )
                }
            },
            onProgress = { transferred, total, speed ->
                updateTransferProgress(transferred, total, speed)
            },
            onComplete = {
                _uiState.update {
                    it.copy(
                        transfer = it.transfer.copy(
                            status = TransferStatus.COMPLETED,
                            speedBytesPerSec = 0L
                        ),
                        pendingTransfer = null,
                        showAcceptDialog = false,
                        lastActionHint = "File saved to Downloads/project_alpha!"
                    )
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        transfer = it.transfer.copy(
                            status = TransferStatus.FAILED,
                            errorMessage = error,
                            speedBytesPerSec = 0L
                        ),
                        pendingTransfer = null,
                        showAcceptDialog = false,
                        lastActionHint = "Transfer failed: $error"
                    )
                }
            }
        )
        
        _uiState.update { 
            it.copy(
                isServerRunning = true,
                lastActionHint = "Ready to receive. Your IP: ${it.connection.localIpAddress ?: "Unknown"}"
            )
        }
    }
    
    private fun stopBackgroundServer() {
        quicAdapter?.stopBackgroundServer()
        _uiState.update { 
            it.copy(
                isServerRunning = false,
                showAcceptDialog = false,
                pendingTransfer = null
            )
        }
    }
    
    /**
     * Accept the pending incoming transfer.
     */
    fun acceptIncomingTransfer() {
        val adapter = quicAdapter ?: return
        
        _uiState.update {
            it.copy(
                showAcceptDialog = false,
                transfer = it.transfer.copy(
                    status = TransferStatus.TRANSFERRING
                ),
                lastActionHint = "Receiving file..."
            )
        }
        
        adapter.acceptTransfer()
    }
    
    /**
     * Reject the pending incoming transfer.
     */
    fun rejectIncomingTransfer() {
        val adapter = quicAdapter ?: return
        
        _uiState.update {
            it.copy(
                showAcceptDialog = false,
                pendingTransfer = null,
                transfer = TransferSnapshot(),
                lastActionHint = "Transfer rejected"
            )
        }
        
        adapter.rejectTransfer()
    }
    
    fun dismissAcceptDialog() {
        // Same as reject
        rejectIncomingTransfer()
    }

    // ==========================================================================
    // File Selection
    // ==========================================================================
    
    fun onFileSelected(fileName: String?, fileUri: String?) {
        _uiState.update {
            it.copy(
                selectedFileName = fileName,
                selectedFileUri = fileUri,
                lastActionHint = if (fileName == null) "No file selected" else "Selected $fileName"
            )
        }
    }

    fun onManualIpChanged(value: String) {
        _uiState.update { it.copy(manualIp = value.trim()) }
    }

    fun onSelectPeer(peerId: String) {
        _uiState.update { it.copy(selectedPeerId = peerId) }
    }

    // ==========================================================================
    // Device Discovery
    // ==========================================================================
    
    fun refreshDevices() {
        if (!_uiState.value.connection.isConnected) {
            _uiState.update { 
                it.copy(
                    showConnectionPrompt = true,
                    lastActionHint = "Connect to WiFi or enable Hotspot first"
                )
            }
            return
        }
        
        _uiState.update {
            it.copy(
                peers = emptyList(),
                transfer = it.transfer.copy(status = TransferStatus.DISCOVERING),
                lastActionHint = "Searching for nearby devices..."
            )
        }
        
        val peers = mutableListOf<PeerDevice>()
        transferCoreAdapter.discoverDevices { device ->
            if (!peers.any { it.id == device.id }) {
                peers.add(device)
                _uiState.update {
                    it.copy(
                        peers = peers.toList(),
                        lastActionHint = "Found ${peers.size} nearby device(s)"
                    )
                }
            }
        }
        
        // Update status after discovery starts
        viewModelScope.launch {
            delay(10000)  // Discovery runs for 10 seconds
            _uiState.update {
                it.copy(
                    transfer = if (it.transfer.status == TransferStatus.DISCOVERING) {
                        it.transfer.copy(status = TransferStatus.IDLE)
                    } else {
                        it.transfer
                    },
                    lastActionHint = if (peers.isEmpty()) "No devices found. Make sure both devices are on the same network." 
                                     else "Found ${peers.size} device(s). Select one to connect."
                )
            }
        }
    }

    // ==========================================================================
    // File Transfer
    // ==========================================================================
    
    fun startSend() {
        val state = _uiState.value
        
        // Check connection first
        if (!state.connection.isConnected) {
            _uiState.update { it.copy(showConnectionPrompt = true) }
            return
        }
        
        if (state.selectedFileUri == null || state.selectedFileName == null) {
            _uiState.update { it.copy(lastActionHint = "Pick a file before sending") }
            return
        }

        val selectedIp = state.peers.firstOrNull { it.id == state.selectedPeerId }?.ipAddress
        val targetIp = selectedIp ?: state.manualIp
        if (targetIp.isBlank()) {
            _uiState.update { it.copy(lastActionHint = "Select a device or enter IP address") }
            return
        }

        lastTransferMode = LocalStreamMode.SEND
        _uiState.update {
            it.copy(
                transfer = TransferSnapshot(
                    status = TransferStatus.CONNECTING,
                    fileName = state.selectedFileName,
                    transferredBytes = 0L,
                    totalBytes = 0L,
                    speedBytesPerSec = 0L,
                    averageBytesPerSec = 0L
                ),
                lastActionHint = "Connecting to $targetIp..."
            )
        }

        transferCoreAdapter.startSend(
            filePath = state.selectedFileUri,
            targetIp = targetIp,
            onProgress = { transferred, total, speed ->
                updateTransferProgress(transferred, total, speed)
            },
            onComplete = {
                _uiState.update {
                    it.copy(
                        transfer = it.transfer.copy(
                            status = TransferStatus.COMPLETED,
                            speedBytesPerSec = 0L
                        ),
                        lastActionHint = "Transfer completed successfully!"
                    )
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        transfer = it.transfer.copy(
                            status = TransferStatus.FAILED,
                            errorMessage = error,
                            speedBytesPerSec = 0L
                        ),
                        lastActionHint = "Transfer failed: $error"
                    )
                }
            }
        )
    }

    fun startReceive() {
        val state = _uiState.value
        
        // Check connection first
        if (!state.connection.isConnected) {
            _uiState.update { it.copy(showConnectionPrompt = true) }
            return
        }
        
        lastTransferMode = LocalStreamMode.RECEIVE
        
        // Just start the background server
        startBackgroundServer()
    }

    fun cancelTransfer() {
        transferCoreAdapter.cancelActiveTransfer()
        _uiState.update {
            it.copy(
                transfer = it.transfer.copy(
                    status = TransferStatus.CANCELLED,
                    speedBytesPerSec = 0L
                ),
                showAcceptDialog = false,
                pendingTransfer = null,
                lastActionHint = "Transfer cancelled"
            )
        }
    }

    fun retryLastTransfer() {
        when (lastTransferMode) {
            LocalStreamMode.SEND -> startSend()
            LocalStreamMode.RECEIVE -> startReceive()
        }
    }

    private fun updateTransferProgress(transferred: Long, total: Long, speed: Long) {
        _uiState.update { state ->
            val safeTotal = max(total, 1L)
            val nextAvg = if (transferred > 0L) {
                max(speed, (state.transfer.averageBytesPerSec + speed) / 2L)
            } else {
                speed
            }
            
            // Format speed for status
            val speedMbps = speed / (1024 * 1024)
            val percent = (transferred * 100 / safeTotal).toInt()
            
            state.copy(
                transfer = state.transfer.copy(
                    status = TransferStatus.TRANSFERRING,
                    transferredBytes = transferred,
                    totalBytes = safeTotal,
                    speedBytesPerSec = speed,
                    averageBytesPerSec = nextAvg,
                    errorMessage = null
                ),
                lastActionHint = "Transferring: $percent% @ $speedMbps MB/s"
            )
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> "%.1f GB".format(bytes / gb)
            bytes >= mb -> "%.1f MB".format(bytes / mb)
            bytes >= kb -> "%.1f KB".format(bytes / kb)
            else -> "$bytes B"
        }
    }
    
    // ==========================================================================
    // Factory
    // ==========================================================================
    
    class Factory(
        private val context: Context,
        private val useFakeAdapter: Boolean = false
    ) : ViewModelProvider.Factory {
        
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LocalStreamViewModel::class.java)) {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                val adapter: TransferCoreAdapter = if (useFakeAdapter) {
                    com.localstream.android.core.FakeTransferCoreAdapter(scope)
                } else {
                    QuicTransferCoreAdapter(context.applicationContext, scope)
                }
                val connectionHelper = ConnectionSetupHelper(context.applicationContext)
                return LocalStreamViewModel(adapter, connectionHelper) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
