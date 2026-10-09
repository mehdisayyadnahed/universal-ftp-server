package com.example.data

enum class NetworkType {
    WIFI,
    HOTSPOT,
    USB_TETHERING,
    BLUETOOTH
}

data class NetworkInterfaceInfo(
    val type: NetworkType,
    val name: String, // e.g., "wlan0", "rndis0", "ap0"
    val displayName: String, // e.g. "Wi-Fi", "USB Tethering", "Hotspot"
    val ipAddress: String, // e.g. "192.168.43.1"
    val isConnected: Boolean = true,
    val extraInfo: String = "" // e.g. "SSID: HomeNetwork" or "USB RNDIS"
) {
    fun getFtpUrl(port: Int): String = "ftp://$ipAddress:$port"
    fun getServerUrl(protocol: ServerProtocol, port: Int): String = "${protocol.scheme}://$ipAddress:$port"
}

enum class LogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

data class LogEntry(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val tag: String = "FTP",
    val message: String,
    val clientIp: String? = null
)
