package com.localstream.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localstream.android.core.FakeTransferCoreAdapter
import com.localstream.android.core.TransferCoreAdapter
import com.localstream.android.ui.model.LocalStreamMode
import com.localstream.android.ui.model.LocalStreamUiState
import com.localstream.android.ui.model.PeerDevice
import com.localstream.android.ui.model.TransferSnapshot
import com.localstream.android.ui.model.TransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

class LocalStreamViewModel : ViewModel() {
    private val transferCoreAdapter: TransferCoreAdapter = FakeTransferCoreAdapter(viewModelScope)

    private val _uiState = MutableStateFlow(LocalStreamUiState())
    val uiState: StateFlow<LocalStreamUiState> = _uiState.asStateFlow()

    private var lastTransferMode: LocalStreamMode = LocalStreamMode.SEND

    init {
        refreshDevices()
    }

    fun setMode(mode: LocalStreamMode) {
        _uiState.update { it.copy(mode = mode) }
    }

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

    fun refreshDevices() {
        val peers = mutableListOf<PeerDevice>()
        transferCoreAdapter.discoverDevices { peers.add(it) }
        _uiState.update {
            it.copy(
                peers = peers,
                transfer = it.transfer.copy(status = TransferStatus.DISCOVERING),
                lastActionHint = if (peers.isEmpty()) "No peers found" else "Found ${peers.size} nearby devices"
            )
        }
        _uiState.update {
            it.copy(
                transfer = it.transfer.copy(status = if (it.transfer.status == TransferStatus.TRANSFERRING) {
                    TransferStatus.TRANSFERRING
                } else {
                    TransferStatus.IDLE
                })
            )
        }
    }

    fun startSend() {
        val state = _uiState.value
        if (state.selectedFileUri == null || state.selectedFileName == null) {
            _uiState.update { it.copy(lastActionHint = "Pick a file before sending") }
            return
        }

        val selectedIp = state.peers.firstOrNull { it.id == state.selectedPeerId }?.ipAddress
        val targetIp = selectedIp ?: state.manualIp
        if (targetIp.isBlank()) {
            _uiState.update { it.copy(lastActionHint = "Select a peer or enter target IP") }
            return
        }

        lastTransferMode = LocalStreamMode.SEND
        _uiState.update {
            it.copy(
                transfer = TransferSnapshot(
                    status = TransferStatus.PREPARING,
                    fileName = state.selectedFileName,
                    transferredBytes = 0L,
                    totalBytes = 0L,
                    speedBytesPerSec = 0L,
                    averageBytesPerSec = 0L
                ),
                lastActionHint = "Starting send to $targetIp"
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
                        lastActionHint = "Send completed"
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
                        lastActionHint = error
                    )
                }
            }
        )
    }

    fun startReceive() {
        lastTransferMode = LocalStreamMode.RECEIVE
        _uiState.update {
            it.copy(
                transfer = TransferSnapshot(
                    status = TransferStatus.PREPARING,
                    fileName = "incoming.localstream",
                    transferredBytes = 0L,
                    totalBytes = 0L
                ),
                lastActionHint = "Waiting for sender"
            )
        }

        transferCoreAdapter.startReceive(
            savePath = "Downloads",
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
                        lastActionHint = "Receive completed"
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
                        lastActionHint = error
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
                // Lightweight average estimate for UI feedback.
                max(speed, (state.transfer.averageBytesPerSec + speed) / 2L)
            } else {
                speed
            }
            state.copy(
                transfer = state.transfer.copy(
                    status = TransferStatus.TRANSFERRING,
                    transferredBytes = transferred,
                    totalBytes = safeTotal,
                    speedBytesPerSec = speed,
                    averageBytesPerSec = nextAvg,
                    errorMessage = null
                ),
                lastActionHint = "Transferring..."
            )
        }
    }
}
