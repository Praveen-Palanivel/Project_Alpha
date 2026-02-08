package com.localstream.android.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Helper class for managing network connectivity requirements for file transfer.
 * 
 * Provides:
 * - WiFi/Hotspot status checking
 * - Intent creation for settings navigation
 * - Connection requirement validation per role
 */
class ConnectionSetupHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "ConnectionSetup"
    }
    
    enum class ConnectionType {
        WIFI,
        HOTSPOT,
        NONE
    }
    
    enum class TransferRole {
        SENDER,
        RECEIVER
    }
    
    data class ConnectionStatus(
        val isConnected: Boolean,
        val connectionType: ConnectionType,
        val localIpAddress: String?,
        val ssid: String?,
        val message: String
    )
    
    /**
     * Check the current network connection status.
     */
    fun checkConnectionStatus(): ConnectionStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // Check if WiFi is connected
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val isWifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        
        if (isWifiConnected) {
            val ip = getLocalIpAddress()
            val ssid = getConnectedSsid()
            return ConnectionStatus(
                isConnected = true,
                connectionType = ConnectionType.WIFI,
                localIpAddress = ip,
                ssid = ssid,
                message = "Connected to $ssid"
            )
        }
        
        // Check if hotspot is enabled
        val isHotspotEnabled = isHotspotActive()
        if (isHotspotEnabled) {
            val ip = getHotspotIpAddress()
            return ConnectionStatus(
                isConnected = true,
                connectionType = ConnectionType.HOTSPOT,
                localIpAddress = ip,
                ssid = null,
                message = "Hotspot active at $ip"
            )
        }
        
        return ConnectionStatus(
            isConnected = false,
            connectionType = ConnectionType.NONE,
            localIpAddress = null,
            ssid = null,
            message = "No network connection"
        )
    }
    
    /**
     * Get instructions for setting up connection based on role.
     */
    fun getSetupInstructions(role: TransferRole): String {
        return when (role) {
            TransferRole.SENDER -> """
                To send files:
                1. Connect to the same WiFi as the receiver
                   OR
                2. Connect to the receiver's hotspot
                
                Then select the receiver from nearby devices.
            """.trimIndent()
            
            TransferRole.RECEIVER -> """
                To receive files:
                1. Connect to the same WiFi as the sender
                   OR
                2. Turn on your mobile hotspot for the sender to connect
                
                Then wait for incoming files.
            """.trimIndent()
        }
    }
    
    /**
     * Create intent to open WiFi settings.
     */
    fun getWifiSettingsIntent(): Intent {
        return Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    
    /**
     * Create intent to open hotspot settings.
     */
    fun getHotspotSettingsIntent(): Intent {
        return Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    
    /**
     * Check if connection is sufficient for the given role.
     */
    fun isConnectionSufficientForRole(role: TransferRole): Boolean {
        val status = checkConnectionStatus()
        return when (role) {
            TransferRole.SENDER -> status.isConnected
            TransferRole.RECEIVER -> status.isConnected
        }
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                return formatIpAddress(ip)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local IP", e)
        }
        return null
    }
    
    private fun getConnectedSsid(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            val ssid = info.ssid
            return if (ssid == "<unknown ssid>") null else ssid?.trim('"')
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get SSID", e)
        }
        return null
    }
    
    private fun isHotspotActive(): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check hotspot status", e)
            false
        }
    }
    
    private fun getHotspotIpAddress(): String {
        // Hotspot typically uses 192.168.43.1 on most Android devices
        return "192.168.43.1"
    }
    
    private fun formatIpAddress(ip: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            (ip shr 8) and 0xff,
            (ip shr 16) and 0xff,
            (ip shr 24) and 0xff
        )
    }
}
