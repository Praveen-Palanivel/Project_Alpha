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
import androidx.compose.foundation.lazy.item
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalStreamApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFileUri by rememberSaveable { mutableStateOf<String?>(null) }
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
        selectedFileUri = uri.toString()
        selectedFileName = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment
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
                HeroCard()
            }

            item {
                PermissionsCard(
                    missingPermissions = missingPermissions,
                    onGrantPermissions = {
                        permissionLauncher.launch(runtimePermissionsForDevice().toTypedArray())
                    }
                )
            }

            item {
                FilePickerCard(
                    selectedFileName = selectedFileName,
                    onPickFile = {
                        openDocumentLauncher.launch(arrayOf("*/*"))
                    }
                )
            }

            item {
                PlaceholderTransferCard(
                    canSend = selectedFileUri != null && missingPermissions.isEmpty(),
                    canReceive = missingPermissions.isEmpty(),
                    onSend = {
                        scope.launch {
                            snackbarHostState.showSnackbar("Send placeholder: QUIC wiring in B4")
                        }
                    },
                    onReceive = {
                        scope.launch {
                            snackbarHostState.showSnackbar("Receive placeholder: QUIC wiring in B4")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun HeroCard() {
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
            Text(
                text = "QUIC-ready scaffold with instant file pick and transfer controls.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Live speed UI target: 100+ MB/s on gigabit LAN",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun PermissionsCard(
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
private fun FilePickerCard(
    selectedFileName: String?,
    onPickFile: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "File Selection", style = MaterialTheme.typography.titleMedium)
            Text(text = selectedFileName ?: "No file selected")
            Button(onClick = onPickFile) {
                Text(text = "Pick File")
            }
        }
    }
}

@Composable
private fun PlaceholderTransferCard(
    canSend: Boolean,
    canReceive: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "Transfer Controls (Placeholder)", style = MaterialTheme.typography.titleMedium)
            Text(text = "Current speed: 0.0 MB/s")
            Text(text = "Status: Ready for core integration")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSend, enabled = canSend) {
                    Text("Send")
                }
                OutlinedButton(onClick = onReceive, enabled = canReceive) {
                    Text("Receive")
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
            Manifest.permission.NEARBY_WIFI_DEVICES
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
