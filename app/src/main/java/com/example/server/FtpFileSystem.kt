package com.example.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.utils.StorageHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FtpFileSystem(
    private val context: Context? = null,
    rootPath: String,
    val rootTreeUriString: String = "",
    val isReadOnly: Boolean = false
) {
    private val effectiveRootPath = if (rootPath.isBlank()) {
        StorageHelper.getDefaultStoragePath()
    } else {
        rootPath
    }

    private val rootDir: File = try {
        val f = File(effectiveRootPath)
        if (!f.exists()) f.mkdirs()
        f.canonicalFile
    } catch (_: Exception) {
        File(effectiveRootPath).absoluteFile
    }

    private var currentVirtualDir = "/"

    fun getRootDirectory(): File = rootDir

    fun getWorkingVirtualPath(): String = currentVirtualDir

    companion object {
        fun cleanFtpPath(raw: String): String {
            var s = raw.trim()
            // Strip surrounding double or single quotes (often added by Windows Explorer)
            if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                s = s.substring(1, s.length - 1).trim()
            } else if (s.startsWith("'") && s.endsWith("'") && s.length >= 2) {
                s = s.substring(1, s.length - 1).trim()
            }
            // Normalize Windows backslashes to forward slashes
            s = s.replace('\\', '/')
            // Remove trailing slash unless it's just "/"
            if (s.length > 1 && s.endsWith("/")) {
                s = s.substring(0, s.length - 1)
            }
            return s
        }
    }

    fun changeDirectory(path: String): Boolean {
        val targetFile = resolveVirtualPath(path)
        if (targetFile.exists() && targetFile.isDirectory && isWithinRoot(targetFile)) {
            currentVirtualDir = toVirtualPath(targetFile)
            return true
        }
        val relPath = toVirtualPath(targetFile).trimStart('/')
        val doc = findDocument(relPath)
        if (doc != null && doc.isDirectory) {
            currentVirtualDir = toVirtualPath(targetFile)
            return true
        }
        return false
    }

    fun changeToParentDirectory(): Boolean {
        if (currentVirtualDir == "/") return true
        val target = resolveVirtualPath("..")
        return if (target.exists() && target.isDirectory && isWithinRoot(target)) {
            currentVirtualDir = toVirtualPath(target)
            true
        } else {
            currentVirtualDir = "/"
            true
        }
    }

    fun getFile(path: String): File {
        return resolveVirtualPath(path)
    }

    fun resolveVirtualPath(rawPath: String): File {
        val clean = cleanFtpPath(rawPath)

        if (clean.isEmpty() || clean == "." || clean == "/") {
            return if (currentVirtualDir == "/" || currentVirtualDir.isEmpty()) {
                rootDir
            } else {
                File(rootDir, currentVirtualDir.trimStart('/'))
            }
        }

        val rootAbs = rootDir.absolutePath
        val rootCanon = try { rootDir.canonicalPath } catch (_: Exception) { rootAbs }

        // If the client sent a full physical path on the device
        if (clean == rootAbs || clean == rootCanon) {
            return rootDir
        }
        if (clean.startsWith("$rootCanon/")) {
            val rel = clean.removePrefix("$rootCanon/").trimStart('/')
            return if (rel.isEmpty()) rootDir else File(rootDir, rel)
        }
        if (clean.startsWith("$rootAbs/")) {
            val rel = clean.removePrefix("$rootAbs/").trimStart('/')
            return if (rel.isEmpty()) rootDir else File(rootDir, rel)
        }
        if (clean.startsWith("/storage/emulated/0/")) {
            val rel = clean.removePrefix("/storage/emulated/0/").trimStart('/')
            return if (rel.isEmpty()) rootDir else File(rootDir, rel)
        }
        if (clean.startsWith("/sdcard/")) {
            val rel = clean.removePrefix("/sdcard/").trimStart('/')
            return if (rel.isEmpty()) rootDir else File(rootDir, rel)
        }

        val target = if (clean.startsWith("/")) {
            // Absolute virtual path
            val rel = clean.trimStart('/')
            if (rel.isEmpty()) rootDir else File(rootDir, rel)
        } else {
            // Relative from current working virtual directory
            val currentRel = currentVirtualDir.trimStart('/').trimEnd('/')
            val currentActual = if (currentRel.isEmpty()) rootDir else File(rootDir, currentRel)
            File(currentActual, clean)
        }

        val normalized = try {
            target.canonicalFile
        } catch (_: Exception) {
            target.absoluteFile
        }

        return if (isWithinRoot(normalized)) normalized else rootDir
    }

    private fun isWithinRoot(file: File): Boolean {
        return try {
            val fileCanon = file.canonicalPath
            val rootCanon = rootDir.canonicalPath
            fileCanon == rootCanon || fileCanon.startsWith(rootCanon + File.separator) ||
                    file.absolutePath == rootDir.absolutePath || file.absolutePath.startsWith(rootDir.absolutePath + File.separator)
        } catch (_: Exception) {
            val abs = file.absolutePath
            val rootAbs = rootDir.absolutePath
            abs == rootAbs || abs.startsWith(rootAbs + File.separator)
        }
    }

    fun toVirtualPath(file: File): String {
        return try {
            val fileCanon = file.canonicalPath
            val rootCanon = rootDir.canonicalPath
            when {
                fileCanon == rootCanon -> "/"
                fileCanon.startsWith(rootCanon) -> {
                    val rel = fileCanon.substring(rootCanon.length).replace('\\', '/')
                    if (rel.startsWith("/")) rel else "/$rel"
                }
                file.absolutePath.startsWith(rootDir.absolutePath) -> {
                    val rel = file.absolutePath.substring(rootDir.absolutePath.length).replace('\\', '/')
                    if (rel.startsWith("/")) rel else "/$rel"
                }
                else -> "/"
            }
        } catch (_: Exception) {
            "/"
        }
    }

    // ==========================================
    // SAF (Storage Access Framework) Helpers
    // ==========================================

    private fun getRootDocumentFile(): DocumentFile? {
        val ctx = context ?: return null
        if (rootTreeUriString.isNotEmpty()) {
            try {
                val uri = Uri.parse(rootTreeUriString)
                val doc = DocumentFile.fromTreeUri(ctx, uri)
                if (doc != null && doc.canRead()) return doc
            } catch (_: Exception) {}
        }
        // Check persisted URI permissions from context
        try {
            val permissions = ctx.contentResolver.persistedUriPermissions
            for (perm in permissions) {
                if (perm.isWritePermission || perm.isReadPermission) {
                    val doc = DocumentFile.fromTreeUri(ctx, perm.uri)
                    if (doc != null && doc.canRead()) return doc
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun findDocument(relativePath: String): DocumentFile? {
        val rootDoc = getRootDocumentFile() ?: return null
        val clean = relativePath.trimStart('/').trimEnd('/')
        if (clean.isEmpty()) return rootDoc
        val parts = clean.split("/").filter { it.isNotEmpty() && it != "." }
        var current: DocumentFile = rootDoc
        for (part in parts) {
            if (part == "..") {
                current = current.parentFile ?: current
                continue
            }
            val next = current.findFile(part) ?: return null
            current = next
        }
        return current
    }

    private fun findOrCreateDirectoryDoc(relativePath: String): DocumentFile? {
        val rootDoc = getRootDocumentFile() ?: return null
        val clean = relativePath.trimStart('/').trimEnd('/')
        if (clean.isEmpty()) return rootDoc
        val parts = clean.split("/").filter { it.isNotEmpty() && it != "." }
        var current: DocumentFile = rootDoc
        for (part in parts) {
            if (part == "..") {
                current = current.parentFile ?: current
                continue
            }
            var next = current.findFile(part)
            if (next == null || !next.isDirectory) {
                next = current.createDirectory(part)
            }
            if (next == null) return null
            current = next
        }
        return current
    }

    // ==========================================
    // File & Directory Operations
    // ==========================================

    fun makeDirectory(path: String): Boolean {
        if (isReadOnly) return false
        val file = resolveVirtualPath(path)
        if (file == rootDir && currentVirtualDir == "/") return false

        // 1. Try POSIX File API
        try {
            file.parentFile?.mkdirs()
            if (file.mkdirs() || file.mkdir() || (file.exists() && file.isDirectory)) {
                return true
            }
        } catch (_: Exception) {}

        // 2. Try SAF DocumentFile
        val relPath = toVirtualPath(file).trimStart('/')
        if (relPath.isNotEmpty()) {
            val created = findOrCreateDirectoryDoc(relPath)
            if (created != null && created.isDirectory) {
                return true
            }
        }

        return false
    }

    fun openOutputStream(path: String, append: Boolean): OutputStream? {
        if (isReadOnly) return null
        val file = resolveVirtualPath(path)

        // 1. Try POSIX FileOutputStream
        try {
            file.parentFile?.mkdirs()
            return FileOutputStream(file, append)
        } catch (_: Exception) {}

        // 2. Try SAF DocumentFile
        val ctx = context ?: return null
        val relPath = toVirtualPath(file).trimStart('/')
        val segments = relPath.split("/").filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null

        val fileName = segments.last()
        val parentRel = segments.dropLast(1).joinToString("/")
        val parentDoc = if (parentRel.isEmpty()) {
            getRootDocumentFile()
        } else {
            findOrCreateDirectoryDoc(parentRel)
        } ?: return null

        val existing = parentDoc.findFile(fileName)
        val targetDoc = if (existing != null && existing.isFile) {
            existing
        } else {
            parentDoc.createFile("application/octet-stream", fileName)
        } ?: return null

        return try {
            ctx.contentResolver.openOutputStream(targetDoc.uri, if (append) "wa" else "wt")
        } catch (_: Exception) {
            null
        }
    }

    fun openInputStream(path: String): InputStream? {
        val file = resolveVirtualPath(path)

        // 1. Try POSIX FileInputStream
        try {
            if (file.exists() && file.canRead()) {
                return FileInputStream(file)
            }
        } catch (_: Exception) {}

        // 2. Try SAF DocumentFile
        val ctx = context ?: return null
        val relPath = toVirtualPath(file).trimStart('/')
        val doc = findDocument(relPath) ?: return null
        return try {
            ctx.contentResolver.openInputStream(doc.uri)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteFile(path: String): Boolean {
        if (isReadOnly) return false
        val file = resolveVirtualPath(path)
        try {
            if (file.exists() && file.isFile && file.delete()) return true
        } catch (_: Exception) {}

        val relPath = toVirtualPath(file).trimStart('/')
        val doc = findDocument(relPath)
        return if (doc != null && doc.isFile) doc.delete() else false
    }

    fun removeDirectory(path: String): Boolean {
        if (isReadOnly) return false
        val file = resolveVirtualPath(path)
        try {
            if (file.exists() && file.isDirectory && file != rootDir && file.deleteRecursively()) return true
        } catch (_: Exception) {}

        val relPath = toVirtualPath(file).trimStart('/')
        if (relPath.isEmpty()) return false
        val doc = findDocument(relPath)
        return if (doc != null && doc.isDirectory) doc.delete() else false
    }

    fun rename(fromPath: String, toPath: String): Boolean {
        if (isReadOnly) return false
        val fromFile = resolveVirtualPath(fromPath)
        val toFile = resolveVirtualPath(toPath)
        try {
            toFile.parentFile?.mkdirs()
            if (fromFile.exists() && !toFile.exists() && fromFile.renameTo(toFile)) return true
        } catch (_: Exception) {}

        val fromRel = toVirtualPath(fromFile).trimStart('/')
        val doc = findDocument(fromRel) ?: return false
        val toName = toFile.name
        return doc.renameTo(toName)
    }

    fun exists(path: String): Boolean {
        val file = resolveVirtualPath(path)
        if (file.exists()) return true
        val relPath = toVirtualPath(file).trimStart('/')
        return findDocument(relPath) != null
    }

    fun isDirectory(path: String): Boolean {
        val file = resolveVirtualPath(path)
        if (file.exists()) return file.isDirectory
        val relPath = toVirtualPath(file).trimStart('/')
        val doc = findDocument(relPath)
        return doc?.isDirectory == true
    }

    fun getFileLength(path: String): Long {
        val file = resolveVirtualPath(path)
        if (file.exists() && file.isFile) return file.length()
        val relPath = toVirtualPath(file).trimStart('/')
        val doc = findDocument(relPath)
        return if (doc != null && doc.isFile) doc.length() else -1L
    }

    fun getFileLastModified(path: String): Long {
        val file = resolveVirtualPath(path)
        if (file.exists()) return file.lastModified()
        val relPath = toVirtualPath(file).trimStart('/')
        val doc = findDocument(relPath)
        return doc?.lastModified() ?: 0L
    }

    // ==========================================
    // Directory Listing
    // ==========================================

    data class FtpItem(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    private fun getDirectoryItems(path: String): List<FtpItem> {
        val target = if (path.isEmpty()) resolveVirtualPath(currentVirtualDir) else resolveVirtualPath(path)
        val files = target.listFiles()
        if (files != null && files.isNotEmpty()) {
            return files.map {
                FtpItem(it.name, it.isDirectory, if (it.isDirectory) 4096L else it.length(), it.lastModified())
            }
        }

        // Try SAF DocumentFile
        val relPath = toVirtualPath(target).trimStart('/')
        val doc = findDocument(relPath)
        if (doc != null && doc.isDirectory) {
            val childDocs = doc.listFiles()
            return childDocs.map {
                FtpItem(
                    name = it.name ?: "unnamed",
                    isDirectory = it.isDirectory,
                    size = if (it.isDirectory) 4096L else it.length(),
                    lastModified = it.lastModified()
                )
            }
        }

        if (files != null) return emptyList()
        return emptyList()
    }

    fun listUnix(path: String = ""): List<String> {
        val items = getDirectoryItems(path)
        val dateFormatRecent = SimpleDateFormat("MMM dd HH:mm", Locale.ENGLISH)
        val dateFormatOld = SimpleDateFormat("MMM dd  yyyy", Locale.ENGLISH)
        val sixMonthsAgo = System.currentTimeMillis() - 180L * 24 * 3600 * 1000

        val lines = mutableListOf<String>()
        val sorted = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        for (item in sorted) {
            val permissions = if (item.isDirectory) "drwxrwxrwx" else "-rw-rw-rw-"
            val links = if (item.isDirectory) "3" else "1"
            val owner = "ftp"
            val group = "ftp"
            val dateStr = if (item.lastModified > sixMonthsAgo && item.lastModified > 0) {
                dateFormatRecent.format(Date(item.lastModified))
            } else {
                dateFormatOld.format(Date(if (item.lastModified > 0) item.lastModified else System.currentTimeMillis()))
            }
            lines.add("$permissions $links $owner $group ${String.format(Locale.US, "%10d", item.size)} $dateStr ${item.name}")
        }
        return lines
    }

    fun listMlsd(path: String = ""): List<String> {
        val items = getDirectoryItems(path)
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH)

        val lines = mutableListOf<String>()
        val sorted = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        for (item in sorted) {
            val type = if (item.isDirectory) "dir" else "file"
            val modify = dateFormat.format(Date(if (item.lastModified > 0) item.lastModified else System.currentTimeMillis()))
            val perms = if (isReadOnly) {
                if (item.isDirectory) "el" else "r"
            } else {
                if (item.isDirectory) "cdeflmp" else "adfrw"
            }
            lines.add("Type=$type;Size=${item.size};Modify=$modify;Perm=$perms; ${item.name}")
        }
        return lines
    }

    fun listNames(path: String = ""): List<String> {
        return getDirectoryItems(path).map { it.name }
    }
}
