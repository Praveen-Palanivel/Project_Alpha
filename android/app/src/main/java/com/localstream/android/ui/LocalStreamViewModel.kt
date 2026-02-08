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
import com.localstream.android.ui.model.TransferSnapshot
import com.localstream.android.ui.model.TransferStatus
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
        
        // Auto-start discovery if connected
        if (status.isConnected) {
            refreshDevices()
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
    // Mode Selection
    // ==========================================================================
    
    fun setMode(mode: LocalStreamMode) {
        _uiState.update { it.copy(mode = mode) }
        
        // Check connection and show prompt if needed
        checkConnection()
        
        // Auto-start receive server when in receive mode
        if (mode == LocalStreamMode.RECEIVE && _uiState.value.connection.isConnected) {
            startReceiveInBackground()
        }
    }
    
    private fun startReceiveInBackground() {
        viewModelScope.launch {
            // Small delay to allow UI to update
            delay(500)
            if (_uiState.value.mode == LocalStreamMode.RECEIVE && 
                _uiState.value.transfer.status == TransferStatus.IDLE) {
                startReceive()
            }
        }
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
        _uiState.update {
            it.copy(
                transfer = TransferSnapshot(
                    status = TransferStatus.PREPARING,
                    fileName = "Waiting for incoming file...",
                    transferredBytes = 0L,
                    totalBytes = 0L
                ),
                lastActionHint = "Ready to receive. Your IP: ${state.connection.localIpAddress ?: "Unknown"}"
            )
        }

        transferCoreAdapter.startReceive(
            savePath = "",  // Will use public Downloads folder
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
                        lastActionHint = "File received and saved to Downloads!"
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
                        lastActionHint = "Receive failed: $error"
                    )
                }
            }
        )
    }

    fun cancelTransfer() {
        transferCoreAdapter.cancelActiveTransfer()
        _uiState.update {
            it.copy(
                transfer = it.transfer.copy(
                    status = TransferStatus.CANCELLED,
                    speedBytesPerSec = 0L
                ),
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
