package com.localstream.android.ui.model

enum class LocalStreamMode {
    SEND,
    RECEIVE
}

enum class TransferStatus {
    IDLE,
    PREPARING,
    DISCOVERING,
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ConnectionType {
    WIFI,
    HOTSPOT,
    NONE
}

data class PeerDevice(
    val id: String,
    val displayName: String,
    val ipAddress: String,
    val protocol: String = "QUIC"
)

data class ConnectionStatus(
    val isConnected: Boolean = false,
    val connectionType: ConnectionType = ConnectionType.NONE,
    val localIpAddress: String? = null,
    val networkName: String? = null,
    val statusMessage: String = "Not connected"
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
    val connection: ConnectionStatus = ConnectionStatus(),
    val selectedFileName: String? = null,
    val selectedFileUri: String? = null,
    val selectedPeerId: String? = null,
    val manualIp: String = "",
    val peers: List<PeerDevice> = emptyList(),
    val transfer: TransferSnapshot = TransferSnapshot(),
    val lastActionHint: String = "Ready",
    val showConnectionPrompt: Boolean = false
)

