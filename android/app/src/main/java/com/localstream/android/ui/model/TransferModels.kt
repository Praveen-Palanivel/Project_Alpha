package com.localstream.android.ui.model

enum class LocalStreamMode {
    SEND,
    RECEIVE
}

enum class TransferStatus {
    IDLE,
    PREPARING,
    DISCOVERING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class PeerDevice(
    val id: String,
    val displayName: String,
    val ipAddress: String,
    val protocol: String = "QUIC"
)

data class TransferSnapshot(
    val status: TransferStatus = TransferStatus.IDLE,
    val fileName: String? = null,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val averageBytesPerSec: Long = 0L,
    val errorMessage: String? = null
)

data class LocalStreamUiState(
    val mode: LocalStreamMode = LocalStreamMode.SEND,
    val selectedFileName: String? = null,
    val selectedFileUri: String? = null,
    val selectedPeerId: String? = null,
    val manualIp: String = "",
    val peers: List<PeerDevice> = emptyList(),
    val transfer: TransferSnapshot = TransferSnapshot(),
    val lastActionHint: String = "Ready"
)
