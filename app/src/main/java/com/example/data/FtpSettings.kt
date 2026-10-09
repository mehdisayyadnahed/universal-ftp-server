package com.example.data

enum class ServerProtocol(val scheme: String, val defaultPort: Int, val displayName: String) {
    FTP("ftp", 2121, "FTP (Standard Protocol)"),
    SFTP("sftp", 2222, "SFTP (Secure over SSH)")
}

enum class AppThemeMode(val displayName: String) {
    AUTO("Auto"),
    LIGHT("Light"),
    DARK("Dark")
}

data class FtpSettings(
    val protocol: ServerProtocol = ServerProtocol.FTP,
    val port: Int = 2121,
    val isAnonymous: Boolean = false,
    val username: String = "android",
    val password: String = "12345678",
    val rootPath: String = "",
    val rootDisplayName: String = "Internal Storage",
    val rootTreeUriString: String = "",
    val isReadOnly: Boolean = false,
    val isWakeLockEnabled: Boolean = true,
    val keepScreenOn: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.AUTO,
    val pasvPortRangeStart: Int = 30000,
    val pasvPortRangeEnd: Int = 30050
)

enum class StorageType {
    INTERNAL,
    SD_CARD,
    CUSTOM
}

data class StorageTarget(
    val type: StorageType,
    val path: String,
    val displayName: String,
    val treeUriString: String = "",
    val totalSpaceBytes: Long = 0L,
    val freeSpaceBytes: Long = 0L,
    val isAvailable: Boolean = true
)
