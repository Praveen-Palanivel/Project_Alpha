package com.localstream.android.core

import com.localstream.android.ui.model.PeerDevice

interface TransferCoreAdapter {
    fun discoverDevices(onFound: (PeerDevice) -> Unit)

    fun startSend(
        filePath: String,
        targetIp: String,
        onProgress: (Long, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    )

    fun startReceive(
        savePath: String,
        onProgress: (Long, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    )

    fun cancelActiveTransfer()
    fun getTransferSpeed(): Long
}
