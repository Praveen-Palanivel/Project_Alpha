package com.localstream.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstream.android.ui.model.ConnectionStatus
import com.localstream.android.ui.model.ConnectionType
import com.localstream.android.ui.model.LocalStreamMode
import com.localstream.android.ui.model.LocalStreamUiState
import com.localstream.android.ui.model.TransferSnapshot
import com.localstream.android.ui.model.TransferStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalStreamApp() {
    val context = LocalContext.current
    
    // Use the real QuicTransferCoreAdapter instead of FakeTransferCoreAdapter
    val localStreamViewModel: LocalStreamViewModel = viewModel(
        factory = LocalStreamViewModel.Factory(
            context = context.applicationContext,
            useFakeAdapter = false  // Set to true during development/testing
        )
    )
    
    val uiState by localStreamViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var permissionStateVersion by remember { mutableIntStateOf(0) }

    val missingPermissions = remember(permissionStateVersion) {
        runtimePermissionsForDevice().filterNot { permission ->
            isPermissionGranted(context, permission)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionStateVersion++
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        context.persistReadAccess(uri)
        val selectedFileName = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment
        localStreamViewModel.onFileSelected(
            fileName = selectedFileName,
            fileUri = uri.toString()
        )
        scope.launch {
            snackbarHostState.showSnackbar("File selected: ${selectedFileName ?: "unknown"}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "LocalStream Android") }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HeroCard(
                    transfer = uiState.transfer,
                    connection = uiState.connection
                )
            }

            item {
                ModeCard(
                    selectedMode = uiState.mode,
                    onModeSelected = localStreamViewModel::setMode
                )
            }

            // Connection status card
            item {
                ConnectionCard(
                    connection = uiState.connection,
                    onCheckConnection = localStreamViewModel::checkConnection,
                    onOpenWifiSettings = {
                        context.startActivity(localStreamViewModel.getWifiSettingsIntent())
                    },
                    onOpenHotspotSettings = {
                        context.startActivity(localStreamViewModel.getHotspotSettingsIntent())
                    }
                )
            }

            item {
                PermissionCard(
                    missingPermissions = missingPermissions,
                    onGrantPermissions = {
                        permissionLauncher.launch(runtimePermissionsForDevice().toTypedArray())
                    }
                )
            }

            item {
                when (uiState.mode) {
                    LocalStreamMode.SEND -> SendSetupCard(
                        uiState = uiState,
                        hasAllPermissions = missingPermissions.isEmpty(),
                        onPickFile = { openDocumentLauncher.launch(arrayOf("*/*")) },
                        onManualIpChanged = localStreamViewModel::onManualIpChanged,
                        onSelectPeer = localStreamViewModel::onSelectPeer,
                        onRefreshPeers = localStreamViewModel::refreshDevices,
                        onStartSend = {
                            localStreamViewModel.startSend()
                            scope.launch { snackbarHostState.showSnackbar("Send started") }
                        }
                    )
                    LocalStreamMode.RECEIVE -> ReceiveSetupCard(
                        hasAllPermissions = missingPermissions.isEmpty(),
                        onRefreshPeers = localStreamViewModel::refreshDevices,
                        onStartReceive = {
                            localStreamViewModel.startReceive()
                            scope.launch { snackbarHostState.showSnackbar("Receive started") }
                        }
                    )
                }
            }

            item {
                TransferProgressCard(
                    transfer = uiState.transfer,
                    onCancel = {
                        localStreamViewModel.cancelTransfer()
                        scope.launch { snackbarHostState.showSnackbar("Transfer cancelled") }
                    },
                    onRetry = {
                        localStreamViewModel.retryLastTransfer()
                        scope.launch { snackbarHostState.showSnackbar("Retrying transfer") }
                    }
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Status: ${uiState.lastActionHint}",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(transfer: TransferSnapshot, connection: ConnectionStatus) {
    val speedMbs = toMbps(transfer.speedBytesPerSec)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "ULTRA HIGH SPEED TRANSFER",
                style = MaterialTheme.typography.titleMedium
            )
            if (connection.isConnected) {
                Text(
                    text = "Your IP: ${connection.localIpAddress ?: "Unknown"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "Live speed: ${"%.1f".format(speedMbs)} MB/s",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: ConnectionStatus,
    onCheckConnection: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenHotspotSettings: () -> Unit
) {
    val connectionIcon = when (connection.connectionType) {
        ConnectionType.WIFI -> "📶"
        ConnectionType.HOTSPOT -> "📡"
        ConnectionType.NONE -> "❌"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connection.isConnected) 
                MaterialTheme.colorScheme.secondaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$connectionIcon Network",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(onClick = onCheckConnection) {
                    Text("Refresh")
                }
            }
            
            Text(
                text = connection.statusMessage,
                style = MaterialTheme.typography.bodyMedium
            )
            
            if (connection.localIpAddress != null) {
                Text(
                    text = "IP: ${connection.localIpAddress}",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            
            if (!connection.isConnected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenWifiSettings) {
                        Text("WiFi Settings")
                    }
                    OutlinedButton(onClick = onOpenHotspotSettings) {
                        Text("Hotspot")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    selectedMode: LocalStreamMode,
    onModeSelected: (LocalStreamMode) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedMode == LocalStreamMode.SEND,
                onClick = { onModeSelected(LocalStreamMode.SEND) },
                label = { Text("Send") }
            )
            FilterChip(
                selected = selectedMode == LocalStreamMode.RECEIVE,
                onClick = { onModeSelected(LocalStreamMode.RECEIVE) },
                label = { Text("Receive") }
            )
        }
    }
}

@Composable
private fun PermissionCard(
    missingPermissions: List<String>,
    onGrantPermissions: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Permissions", style = MaterialTheme.typography.titleMedium)
            if (missingPermissions.isEmpty()) {
                Text("All required runtime permissions are granted.")
            } else {
                Text("Missing: ${missingPermissions.joinToString()}")
                Button(onClick = onGrantPermissions) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}

@Composable
private fun SendSetupCard(
    uiState: LocalStreamUiState,
    hasAllPermissions: Boolean,
    onPickFile: () -> Unit,
    onManualIpChanged: (String) -> Unit,
    onSelectPeer: (String) -> Unit,
    onRefreshPeers: () -> Unit,
    onStartSend: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "Send Setup", style = MaterialTheme.typography.titleMedium)
            Text(text = "Selected file: ${uiState.selectedFileName ?: "No file selected"}")
            Button(onClick = onPickFile) {
                Text(text = "Pick File")
            }

            OutlinedButton(onClick = onRefreshPeers) {
                Text("Refresh Nearby Devices")
            }

            if (uiState.peers.isEmpty()) {
                Text("No peers discovered yet.")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.peers.forEach { peer ->
                        FilterChip(
                            selected = uiState.selectedPeerId == peer.id,
                            onClick = { onSelectPeer(peer.id) },
                            label = { Text("${peer.displayName} (${peer.ipAddress})") }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = uiState.manualIp,
                onValueChange = onManualIpChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Manual target IP") },
                singleLine = true
            )

            Button(
                onClick = onStartSend,
                enabled = hasAllPermissions && uiState.selectedFileUri != null
            ) {
                Text("Start Send")
            }
        }
    }
}

@Composable
private fun ReceiveSetupCard(
    hasAllPermissions: Boolean,
    onRefreshPeers: () -> Unit,
    onStartReceive: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "Receive Setup", style = MaterialTheme.typography.titleMedium)
            Text("Save target: Downloads")
            Text("Protocol preference: QUIC (fallback TCP)")
            OutlinedButton(onClick = onRefreshPeers) {
                Text("Refresh Sender Discovery")
            }
            Button(onClick = onStartReceive, enabled = hasAllPermissions) {
                Text("Start Receive")
            }
        }
    }
}

@Composable
private fun TransferProgressCard(
    transfer: TransferSnapshot,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val progress = if (transfer.totalBytes > 0L) {
        transfer.transferredBytes.toFloat() / transfer.totalBytes.toFloat()
    } else {
        0f
    }
    val isInProgress = transfer.status == TransferStatus.TRANSFERRING || transfer.status == TransferStatus.PREPARING
    val canRetry = transfer.status == TransferStatus.FAILED || transfer.status == TransferStatus.CANCELLED

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "Transfer Progress", style = MaterialTheme.typography.titleMedium)
            Text(text = "File: ${transfer.fileName ?: "N/A"}")
            Text(text = "State: ${transfer.status}")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("Transferred: ${formatBytes(transfer.transferredBytes)} / ${formatBytes(transfer.totalBytes)}")
            Text("Current speed: ${"%.1f".format(toMbps(transfer.speedBytesPerSec))} MB/s")
            Text("Average speed: ${"%.1f".format(toMbps(transfer.averageBytesPerSec))} MB/s")
            transfer.errorMessage?.let { Text("Error: $it") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCancel, enabled = isInProgress) {
                    Text("Cancel")
                }
                OutlinedButton(onClick = onRetry, enabled = canRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

private fun runtimePermissionsForDevice(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun Context.persistReadAccess(uri: Uri) {
    val readFlag = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
    runCatching { contentResolver.takePersistableUriPermission(uri, readFlag) }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> "${"%.2f".format(bytes / gb)} GB"
        bytes >= mb -> "${"%.2f".format(bytes / mb)} MB"
        bytes >= kb -> "${"%.2f".format(bytes / kb)} KB"
        else -> "$bytes B"
    }
}

private fun toMbps(bytesPerSec: Long): Double {
    if (bytesPerSec <= 0L) return 0.0
    return bytesPerSec.toDouble() / (1024.0 * 1024.0)
}
