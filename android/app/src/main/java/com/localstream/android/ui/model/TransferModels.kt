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
    WAITING_ACCEPT,  // New: waiting for user to accept incoming transfer
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

/**
 * Transport mode for file transfer.
 * Helps users choose optimal connection based on their role.
 */
enum class TransportMode {
    WIFI,       // Connect to existing WiFi network (best for sender)
    HOTSPOT,    // Create hotspot for receiver (receiver creates, sender joins)
    BLUETOOTH   // For smaller files when no WiFi available
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

/**
 * Represents an incoming transfer waiting for user acceptance.
 */
data class PendingTransfer(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val senderName: String,
    val senderIp: String
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
    val transportMode: TransportMode = TransportMode.WIFI,
    val connection: ConnectionStatus = ConnectionStatus(),
    val selectedFileName: String? = null,
    val selectedFileUri: String? = null,
    val selectedPeerId: String? = null,
    val manualIp: String = "",
    val peers: List<PeerDevice> = emptyList(),
    val transfer: TransferSnapshot = TransferSnapshot(),
    val pendingTransfer: PendingTransfer? = null,  // Incoming transfer waiting for accept
    val showAcceptDialog: Boolean = false,
    val lastActionHint: String = "Ready",
    val showConnectionPrompt: Boolean = false,
    val isServerRunning: Boolean = false  // Background server status
)
