package com.localstream.android.core

import com.localstream.android.ui.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

class FakeTransferCoreAdapter(
    private val scope: CoroutineScope
) : TransferCoreAdapter {
    private var activeTransferJob: Job? = null
    private var speedBytesPerSec: Long = 0L

    override fun discoverDevices(onFound: (PeerDevice) -> Unit) {
        listOf(
            PeerDevice("peer-1", "Pixel 8 Pro", "192.168.1.20"),
            PeerDevice("peer-2", "Gaming Laptop", "192.168.1.35"),
            PeerDevice("peer-3", "Office Desktop", "192.168.1.44")
        ).forEach(onFound)
    }

    override fun startSend(
        filePath: String,
        targetIp: String,
        onProgress: (Long, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        startTransferSimulation(
            totalBytes = 2_000_000_000L,
            onProgress = onProgress,
            onComplete = onComplete,
            onError = onError
        )
    }

    override fun startReceive(
        savePath: String,
        onProgress: (Long, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        startTransferSimulation(
            totalBytes = 1_200_000_000L,
            onProgress = onProgress,
            onComplete = onComplete,
            onError = onError
        )
    }

    override fun cancelActiveTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        speedBytesPerSec = 0L
    }

    override fun getTransferSpeed(): Long = speedBytesPerSec

    private fun startTransferSimulation(
        totalBytes: Long,
        onProgress: (Long, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        cancelActiveTransfer()

        activeTransferJob = scope.launch {
            runCatching {
                var transferred = 0L
                while (isActive && transferred < totalBytes) {
                    delay(220L)
                    // Simulate high-throughput local QUIC speeds.
                    speedBytesPerSec = Random.nextLong(
                        from = 70L * 1024L * 1024L,
                        until = 145L * 1024L * 1024L
                    )
                    val step = (speedBytesPerSec * 220L) / 1000L
                    transferred = min(totalBytes, transferred + step)
                    onProgress(transferred, totalBytes, speedBytesPerSec)
                }
            }.onSuccess {
                if (isActive) {
                    speedBytesPerSec = 0L
                    onComplete()
                }
            }.onFailure { err ->
                speedBytesPerSec = 0L
                onError(err.message ?: "Transfer failed")
            }
        }
    }
}
