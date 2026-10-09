package com.example.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.example.data.StorageTarget
import com.example.data.StorageType
import java.io.File

object StorageHelper {

    fun getDefaultStoragePath(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            read && write
        }
    }

    /**
     * Resolves SAF (Storage Access Framework) folder picker URI to a physical directory path & display title.
     */
    fun getPathFromTreeUri(context: Context, treeUri: Uri): StorageTarget? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
            val split = docId.split(":")
            if (split.isEmpty()) return null
            val type = split[0]
            val subPath = if (split.size > 1) split[1] else ""

            return if (type.equals("primary", ignoreCase = true)) {
                val root = Environment.getExternalStorageDirectory().absolutePath
                val fullPath = if (subPath.isNotEmpty()) "$root/$subPath" else root
                val folderName = if (subPath.isNotEmpty()) subPath.substringAfterLast('/') else "Internal Storage"
                val stat = try { StatFs(fullPath) } catch (_: Exception) { null }
                StorageTarget(
                    type = StorageType.CUSTOM,
                    path = fullPath,
                    displayName = "Folder: $folderName",
                    treeUriString = treeUri.toString(),
                    totalSpaceBytes = stat?.totalBytes ?: 0L,
                    freeSpaceBytes = stat?.availableBytes ?: 0L,
                    isAvailable = true
                )
            } else {
                // Removable SD Card / USB OTG
                val sdMount = "/storage/$type"
                val fullPath = if (subPath.isNotEmpty()) "$sdMount/$subPath" else sdMount
                val folderName = if (subPath.isNotEmpty()) subPath.substringAfterLast('/') else type
                val stat = try { StatFs(fullPath) } catch (_: Exception) { null }
                StorageTarget(
                    type = StorageType.SD_CARD,
                    path = fullPath,
                    displayName = "SD Card: $folderName",
                    treeUriString = treeUri.toString(),
                    totalSpaceBytes = stat?.totalBytes ?: 0L,
                    freeSpaceBytes = stat?.availableBytes ?: 0L,
                    isAvailable = true
                )
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun getAvailableStorageTargets(context: Context): List<StorageTarget> {
        val targets = mutableListOf<StorageTarget>()

        // 1. Primary Internal Storage (e.g. /storage/emulated/0)
        try {
            val internalDir = Environment.getExternalStorageDirectory()
            if (internalDir != null && internalDir.exists()) {
                val stat = try { StatFs(internalDir.path) } catch (_: Exception) { null }
                targets.add(
                    StorageTarget(
                        type = StorageType.INTERNAL,
                        path = internalDir.absolutePath,
                        displayName = "Internal Storage",
                        totalSpaceBytes = stat?.totalBytes ?: 0L,
                        freeSpaceBytes = stat?.availableBytes ?: 0L,
                        isAvailable = true
                    )
                )
            }
        } catch (_: Exception) {
            targets.add(
                StorageTarget(
                    type = StorageType.INTERNAL,
                    path = "/storage/emulated/0",
                    displayName = "Internal Storage",
                    isAvailable = true
                )
            )
        }

        // 2. Physical Removable SD Cards / External Drives
        try {
            val externalDirs = context.getExternalFilesDirs(null)
            for (dir in externalDirs) {
                if (dir != null) {
                    val isRemovable = Environment.isExternalStorageRemovable(dir)
                    if (isRemovable) {
                        val fullPath = dir.absolutePath
                        val storageIndex = fullPath.indexOf("/storage/")
                        if (storageIndex != -1) {
                            val parts = fullPath.substring(storageIndex).split("/")
                            if (parts.size >= 3) {
                                val sdRoot = "/storage/${parts[2]}"
                                val sdDir = File(sdRoot)
                                if (sdDir.exists() && sdDir.canRead() && !targets.any { it.path == sdRoot }) {
                                    val stat = try { StatFs(sdRoot) } catch (_: Exception) { null }
                                    targets.add(
                                        StorageTarget(
                                            type = StorageType.SD_CARD,
                                            path = sdRoot,
                                            displayName = "SD Card",
                                            totalSpaceBytes = stat?.totalBytes ?: 0L,
                                            freeSpaceBytes = stat?.availableBytes ?: 0L,
                                            isAvailable = true
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Also check /storage directory mounts for removable SD cards
            val storageRoot = File("/storage")
            if (storageRoot.exists() && storageRoot.isDirectory) {
                storageRoot.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.name != "emulated" && file.name != "self" && file.canRead()) {
                        val path = file.absolutePath
                        if (!targets.any { it.path == path }) {
                            val stat = try { StatFs(path) } catch (_: Exception) { null }
                            targets.add(
                                StorageTarget(
                                    type = StorageType.SD_CARD,
                                    path = path,
                                    displayName = "SD Card (${file.name})",
                                    totalSpaceBytes = stat?.totalBytes ?: 0L,
                                    freeSpaceBytes = stat?.availableBytes ?: 0L,
                                    isAvailable = true
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }

        return targets
    }

    fun getStorageSpace(path: String): Pair<Long, Long> {
        val targetPath = path.ifEmpty { getDefaultStoragePath() }
        var currentFile: File? = File(targetPath)
        while (currentFile != null && !currentFile.exists()) {
            currentFile = currentFile.parentFile
        }
        val checkPath = currentFile?.absolutePath ?: getDefaultStoragePath()
        return try {
            val stat = StatFs(checkPath)
            Pair(stat.availableBytes, stat.totalBytes)
        } catch (_: Exception) {
            try {
                val fallbackStat = StatFs(getDefaultStoragePath())
                Pair(fallbackStat.availableBytes, fallbackStat.totalBytes)
            } catch (_: Exception) {
                Pair(0L, 0L)
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val formatted = bytes / Math.pow(1024.0, digitGroups.toDouble())
        val unit = units.getOrNull(digitGroups) ?: "GB"
        return String.format(java.util.Locale.US, "%.1f %s", formatted, unit)
    }
}
