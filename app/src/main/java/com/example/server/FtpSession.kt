package com.example.server

import android.content.Context
import com.example.data.FtpSettings
import com.example.data.LogLevel
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FtpSession(
    private val context: Context? = null,
    private val controlSocket: Socket,
    private val settings: FtpSettings,
    private val onLog: (level: LogLevel, message: String, clientIp: String?) -> Unit,
    private val onSessionClosed: (FtpSession) -> Unit
) : Runnable {

    private val clientIp: String = controlSocket.inetAddress.hostAddress ?: "unknown"
    private val fileSystem = FtpFileSystem(
        context = context,
        rootPath = settings.rootPath,
        rootTreeUriString = settings.rootTreeUriString,
        isReadOnly = settings.isReadOnly
    )

    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private var isAuthenticated = false
    private var pendingUser: String? = null
    private var isBinaryMode = true
    private var restOffset: Long = 0L
    private var renameFrom: String? = null

    // Passive & Active data sockets
    private var passiveServerSocket: ServerSocket? = null
    private var activeDataAddress: InetAddress? = null
    private var activeDataPort: Int = 0

    @Volatile
    private var isRunning = true

    override fun run() {
        try {
            controlSocket.soTimeout = 300_000 // 5 minutes timeout
            reader = BufferedReader(InputStreamReader(controlSocket.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(controlSocket.getOutputStream(), Charsets.UTF_8))

            onLog(LogLevel.INFO, "Client connected from $clientIp", clientIp)
            sendResponse("220 Android FTP Server Ready")

            while (isRunning && !controlSocket.isClosed) {
                val line = reader?.readLine() ?: break
                handleCommand(line.trim())
            }
        } catch (e: Exception) {
            if (isRunning) {
                onLog(LogLevel.INFO, "Session closed: ${e.localizedMessage ?: "Disconnected"}", clientIp)
            }
        } finally {
            close()
        }
    }

    private fun handleCommand(commandLine: String) {
        if (commandLine.isEmpty()) return

        val spaceIndex = commandLine.indexOf(' ')
        val cmd = (if (spaceIndex != -1) commandLine.substring(0, spaceIndex) else commandLine).uppercase(Locale.US)
        val arg = if (spaceIndex != -1) commandLine.substring(spaceIndex + 1).trim() else ""

        when (cmd) {
            "USER" -> handleUser(arg)
            "PASS" -> handlePass(arg)
            "QUIT" -> {
                sendResponse("221 Goodbye.")
                close()
            }
            "NOOP" -> sendResponse("200 OK")
            "SYST" -> sendResponse("215 UNIX Type: L8")
            "FEAT" -> handleFeat()
            "OPTS" -> handleOpts(arg)
            "TYPE" -> handleType(arg)
            "MODE" -> sendResponse("200 Mode S ok.")
            "STRU" -> sendResponse("200 Structure F ok.")
            "PWD", "XPWD" -> {
                if (!checkAuth()) return
                sendResponse("257 \"${fileSystem.getWorkingVirtualPath()}\" is current directory.")
            }
            "CWD", "XCWD" -> {
                if (!checkAuth()) return
                val clean = FtpFileSystem.cleanFtpPath(arg)
                if (fileSystem.changeDirectory(clean)) {
                    sendResponse("250 Directory successfully changed to \"${fileSystem.getWorkingVirtualPath()}\".")
                } else {
                    sendResponse("550 Failed to change directory.")
                }
            }
            "CDUP", "XCUP" -> {
                if (!checkAuth()) return
                if (fileSystem.changeToParentDirectory()) {
                    sendResponse("250 Directory successfully changed to \"${fileSystem.getWorkingVirtualPath()}\".")
                } else {
                    sendResponse("550 Failed to change directory.")
                }
            }
            "PASV" -> {
                if (!checkAuth()) return
                handlePasv()
            }
            "EPSV" -> {
                if (!checkAuth()) return
                handleEpsv()
            }
            "PORT" -> {
                if (!checkAuth()) return
                handlePort(arg)
            }
            "EPRT" -> {
                if (!checkAuth()) return
                handleEprt(arg)
            }
            "LIST" -> {
                if (!checkAuth()) return
                handleList(arg)
            }
            "NLST" -> {
                if (!checkAuth()) return
                handleNlst(arg)
            }
            "MLSD" -> {
                if (!checkAuth()) return
                handleMlsd(arg)
            }
            "MLST" -> {
                if (!checkAuth()) return
                handleMlst(arg)
            }
            "SIZE" -> {
                if (!checkAuth()) return
                handleSize(arg)
            }
            "MDTM" -> {
                if (!checkAuth()) return
                handleMdtm(arg)
            }
            "REST" -> {
                if (!checkAuth()) return
                handleRest(arg)
            }
            "RETR" -> {
                if (!checkAuth()) return
                handleRetr(arg)
            }
            "STOR" -> {
                if (!checkAuth()) return
                handleStor(arg, append = false)
            }
            "APPE" -> {
                if (!checkAuth()) return
                handleStor(arg, append = true)
            }
            "DELE" -> {
                if (!checkAuth()) return
                handleDele(arg)
            }
            "MKD", "XMKD" -> {
                if (!checkAuth()) return
                handleMkd(arg)
            }
            "RMD", "XRMD" -> {
                if (!checkAuth()) return
                handleRmd(arg)
            }
            "RNFR" -> {
                if (!checkAuth()) return
                handleRnfr(arg)
            }
            "RNTO" -> {
                if (!checkAuth()) return
                handleRnto(arg)
            }
            "AUTH" -> sendResponse("504 Security mechanism not supported.")
            "ABOR" -> sendResponse("226 ABOR command successful.")
            else -> sendResponse("502 Command not implemented: $cmd")
        }
    }

    private fun checkAuth(): Boolean {
        if (!isAuthenticated) {
            sendResponse("530 Please login with USER and PASS.")
            return false
        }
        return true
    }

    private fun handleUser(username: String) {
        val cleanUser = username.trim()
        if (settings.isAnonymous) {
            if (cleanUser.isEmpty() || cleanUser.equals("anonymous", ignoreCase = true) || cleanUser.equals("ftp", ignoreCase = true)) {
                pendingUser = "anonymous"
                sendResponse("331 Guest login ok, send your complete email address as password.")
            } else {
                pendingUser = cleanUser
                sendResponse("331 User name okay, need password.")
            }
        } else {
            // Anonymous login is disabled - authentication is mandatory
            if (cleanUser.isEmpty() || cleanUser.equals("anonymous", ignoreCase = true) || cleanUser.equals("ftp", ignoreCase = true)) {
                pendingUser = "anonymous"
                // Inform client that anonymous is not accepted or ask for credentials
                sendResponse("331 Anonymous access disabled. Authentication required.")
            } else {
                pendingUser = cleanUser
                sendResponse("331 User name okay, need password.")
            }
        }
    }

    private fun handlePass(password: String) {
        val user = pendingUser
        if (user == null) {
            sendResponse("503 Login with USER first.")
            return
        }

        if (settings.isAnonymous) {
            if (user.equals("anonymous", ignoreCase = true) || user.equals("ftp", ignoreCase = true)) {
                isAuthenticated = true
                onLog(LogLevel.SUCCESS, "Anonymous user logged in from $clientIp", clientIp)
                sendResponse("230 Anonymous user logged in.")
            } else if (user == settings.username && password == settings.password) {
                isAuthenticated = true
                onLog(LogLevel.SUCCESS, "User '$user' successfully logged in from $clientIp", clientIp)
                sendResponse("230 User logged in, proceed.")
            } else {
                isAuthenticated = false
                onLog(LogLevel.WARNING, "Authentication failed for user '$user' from $clientIp", clientIp)
                sendResponse("530 Login incorrect.")
            }
        } else {
            // Password is REQUIRED (Anonymous disabled)
            if (user.equals("anonymous", ignoreCase = true) || user.equals("ftp", ignoreCase = true)) {
                isAuthenticated = false
                onLog(LogLevel.WARNING, "Anonymous login rejected from $clientIp (authentication required)", clientIp)
                sendResponse("530 Anonymous login is disabled. Please provide valid username and password.")
            } else if (user == settings.username && password == settings.password) {
                isAuthenticated = true
                onLog(LogLevel.SUCCESS, "User '$user' successfully logged in from $clientIp", clientIp)
                sendResponse("230 User logged in, proceed.")
            } else {
                isAuthenticated = false
                onLog(LogLevel.WARNING, "Authentication failed for user '$user' from $clientIp", clientIp)
                sendResponse("530 Login incorrect.")
            }
        }
    }

    private fun handleFeat() {
        val feats = listOf(
            "211-Features:",
            " UTF8",
            " MLSD",
            " MLST Type*;Size*;Modify*;Perm*;",
            " REST STREAM",
            " SIZE",
            " MDTM",
            " PASV",
            " EPSV",
            "211 End"
        )
        for (line in feats) {
            sendResponse(line)
        }
    }

    private fun handleOpts(arg: String) {
        if (arg.uppercase(Locale.US).startsWith("UTF8")) {
            sendResponse("200 UTF8 mode enabled.")
        } else {
            sendResponse("501 Option not recognized.")
        }
    }

    private fun handleType(arg: String) {
        when (arg.uppercase(Locale.US)) {
            "A", "A N" -> {
                isBinaryMode = false
                sendResponse("200 Type set to A (ASCII).")
            }
            "I", "L 8" -> {
                isBinaryMode = true
                sendResponse("200 Type set to I (Binary).")
            }
            else -> sendResponse("504 Command not implemented for that parameter.")
        }
    }

    private fun handlePasv() {
        closePassiveSocket()
        try {
            // Allocate ephemeral port
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(controlSocket.localAddress, 0))
            passiveServerSocket = ss

            val localAddress = controlSocket.localAddress
            val hostBytes = localAddress.address
            val port = ss.localPort
            val p1 = port shr 8
            val p2 = port and 0xFF

            val h1 = hostBytes[0].toInt() and 0xFF
            val h2 = hostBytes[1].toInt() and 0xFF
            val h3 = hostBytes[2].toInt() and 0xFF
            val h4 = hostBytes[3].toInt() and 0xFF

            sendResponse("227 Entering Passive Mode ($h1,$h2,$h3,$h4,$p1,$p2).")
        } catch (e: Exception) {
            sendResponse("425 Can't open passive connection: ${e.localizedMessage}")
        }
    }

    private fun handleEpsv() {
        closePassiveSocket()
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(controlSocket.localAddress, 0))
            passiveServerSocket = ss

            val port = ss.localPort
            sendResponse("229 Entering Extended Passive Mode (|||$port|)")
        } catch (e: Exception) {
            sendResponse("425 Can't open passive connection: ${e.localizedMessage}")
        }
    }

    private fun handlePort(arg: String) {
        val parts = arg.split(",")
        if (parts.size != 6) {
            sendResponse("501 Syntax error in parameters.")
            return
        }
        try {
            val ip = "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}"
            val port = (parts[4].toInt() shl 8) or parts[5].toInt()
            activeDataAddress = InetAddress.getByName(ip)
            activeDataPort = port
            sendResponse("200 PORT command successful.")
        } catch (e: Exception) {
            sendResponse("501 Parameter error: ${e.localizedMessage}")
        }
    }

    private fun handleEprt(arg: String) {
        val parts = arg.split("|")
        if (parts.size < 4) {
            sendResponse("501 Syntax error in parameters.")
            return
        }
        try {
            val ip = parts[2]
            val port = parts[3].toInt()
            activeDataAddress = InetAddress.getByName(ip)
            activeDataPort = port
            sendResponse("200 EPRT command successful.")
        } catch (e: Exception) {
            sendResponse("501 Parameter error: ${e.localizedMessage}")
        }
    }

    private fun openDataSocket(): Socket? {
        val ss = passiveServerSocket
        if (ss != null) {
            try {
                ss.soTimeout = 20_000
                val dataSocket = ss.accept()
                closePassiveSocket()
                return dataSocket
            } catch (e: Exception) {
                closePassiveSocket()
                return null
            }
        }

        val addr = activeDataAddress
        if (addr != null && activeDataPort > 0) {
            return try {
                val socket = Socket()
                socket.connect(InetSocketAddress(addr, activeDataPort), 20_000)
                socket
            } catch (e: Exception) {
                null
            } finally {
                activeDataAddress = null
                activeDataPort = 0
            }
        }

        return null
    }

    private fun closePassiveSocket() {
        try {
            passiveServerSocket?.close()
        } catch (_: Exception) {}
        passiveServerSocket = null
    }

    private fun handleList(path: String) {
        sendResponse("150 Here comes the directory listing.")
        Thread {
            val dataSocket = openDataSocket()
            if (dataSocket == null) {
                sendResponse("425 Can't open data connection.")
                return@Thread
            }
            try {
                val dataOut = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
                val clean = FtpFileSystem.cleanFtpPath(path.removePrefix("-a").removePrefix("-l").trim())
                val lines = fileSystem.listUnix(clean)
                for (line in lines) {
                    dataOut.write(line)
                    dataOut.write("\r\n")
                }
                dataOut.flush()
                dataSocket.close()
                sendResponse("226 Directory send OK.")
            } catch (e: Exception) {
                sendResponse("426 Connection closed; transfer aborted.")
            } finally {
                try { dataSocket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun handleNlst(path: String) {
        sendResponse("150 Here comes the directory listing.")
        Thread {
            val dataSocket = openDataSocket()
            if (dataSocket == null) {
                sendResponse("425 Can't open data connection.")
                return@Thread
            }
            try {
                val dataOut = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
                val clean = FtpFileSystem.cleanFtpPath(path)
                val lines = fileSystem.listNames(clean)
                for (line in lines) {
                    dataOut.write(line)
                    dataOut.write("\r\n")
                }
                dataOut.flush()
                dataSocket.close()
                sendResponse("226 Directory send OK.")
            } catch (e: Exception) {
                sendResponse("426 Connection closed; transfer aborted.")
            } finally {
                try { dataSocket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun handleMlsd(path: String) {
        sendResponse("150 Opening data connection for MLSD.")
        Thread {
            val dataSocket = openDataSocket()
            if (dataSocket == null) {
                sendResponse("425 Can't open data connection.")
                return@Thread
            }
            try {
                val dataOut = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
                val clean = FtpFileSystem.cleanFtpPath(path)
                val lines = fileSystem.listMlsd(clean)
                for (line in lines) {
                    dataOut.write(line)
                    dataOut.write("\r\n")
                }
                dataOut.flush()
                dataSocket.close()
                sendResponse("226 MLSD complete.")
            } catch (e: Exception) {
                sendResponse("426 Connection closed; transfer aborted.")
            } finally {
                try { dataSocket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun handleMlst(path: String) {
        val clean = FtpFileSystem.cleanFtpPath(path)
        val target = if (clean.isEmpty()) fileSystem.getFile(fileSystem.getWorkingVirtualPath()) else fileSystem.getFile(clean)
        val exists = fileSystem.exists(clean)
        if (!exists) {
            sendResponse("550 File not found.")
            return
        }
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH)
        val isDir = fileSystem.isDirectory(clean)
        val type = if (isDir) "dir" else "file"
        val size = fileSystem.getFileLength(clean)
        val lastMod = fileSystem.getFileLastModified(clean)
        val modify = dateFormat.format(Date(if (lastMod > 0) lastMod else System.currentTimeMillis()))
        val perms = if (settings.isReadOnly) "r" else "adfrw"
        sendResponse("250- Listing ${target.name}")
        sendResponse(" Type=$type;Size=$size;Modify=$modify;Perm=$perms; ${target.name}")
        sendResponse("250 End")
    }

    private fun handleSize(path: String) {
        val clean = FtpFileSystem.cleanFtpPath(path)
        val exists = fileSystem.exists(clean)
        val isDir = fileSystem.isDirectory(clean)
        if (exists && !isDir) {
            sendResponse("213 ${fileSystem.getFileLength(clean)}")
        } else {
            sendResponse("550 Could not get file size.")
        }
    }

    private fun handleMdtm(path: String) {
        val clean = FtpFileSystem.cleanFtpPath(path)
        if (fileSystem.exists(clean)) {
            val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH)
            val lastMod = fileSystem.getFileLastModified(clean)
            sendResponse("213 ${dateFormat.format(Date(if (lastMod > 0) lastMod else System.currentTimeMillis()))}")
        } else {
            sendResponse("550 File not found.")
        }
    }

    private fun handleRest(offsetStr: String) {
        try {
            restOffset = offsetStr.toLong()
            sendResponse("350 Restarting at $restOffset. Send STORE or RETRIEVE to initiate transfer.")
        } catch (e: Exception) {
            restOffset = 0L
            sendResponse("501 Invalid restart offset.")
        }
    }

    private fun handleRetr(path: String) {
        val clean = FtpFileSystem.cleanFtpPath(path)
        val file = fileSystem.getFile(clean)
        val exists = fileSystem.exists(clean)
        val isDir = fileSystem.isDirectory(clean)

        if (!exists || isDir) {
            sendResponse("550 Failed to open file.")
            restOffset = 0L
            return
        }

        val offset = restOffset
        restOffset = 0L
        val fileSize = fileSystem.getFileLength(clean)
        val fileName = file.name

        sendResponse("150 Opening data connection for $fileName ($fileSize bytes).")
        onLog(LogLevel.INFO, "Download started: $fileName ($fileSize bytes)", clientIp)

        Thread {
            val dataSocket = openDataSocket()
            if (dataSocket == null) {
                sendResponse("425 Can't open data connection.")
                return@Thread
            }

            var fis: java.io.InputStream? = null
            try {
                fis = fileSystem.openInputStream(clean)
                if (fis == null) {
                    sendResponse("550 Cannot open file for reading.")
                    return@Thread
                }
                if (offset > 0) {
                    fis.skip(offset)
                }
                val os = dataSocket.getOutputStream()
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalSent = 0L

                while (fis.read(buffer).also { bytesRead = it } != -1 && isRunning) {
                    os.write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                }
                os.flush()
                dataSocket.close()
                sendResponse("226 Transfer complete.")
                onLog(LogLevel.SUCCESS, "Download complete: $fileName ($totalSent bytes)", clientIp)
            } catch (e: Exception) {
                sendResponse("426 Connection closed; transfer aborted.")
                onLog(LogLevel.WARNING, "Download interrupted: $fileName (${e.localizedMessage})", clientIp)
            } finally {
                try { fis?.close() } catch (_: Exception) {}
                try { dataSocket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun handleStor(path: String, append: Boolean) {
        if (settings.isReadOnly) {
            sendResponse("550 Permission denied (Read-only mode).")
            restOffset = 0L
            return
        }

        val clean = FtpFileSystem.cleanFtpPath(path)
        val file = fileSystem.getFile(clean)
        val offset = restOffset
        restOffset = 0L

        sendResponse("150 Ok to send data.")
        onLog(LogLevel.INFO, "Upload started: ${file.name}", clientIp)

        Thread {
            val dataSocket = openDataSocket()
            if (dataSocket == null) {
                sendResponse("425 Can't open data connection.")
                return@Thread
            }

            var fos: java.io.OutputStream? = null
            try {
                fos = fileSystem.openOutputStream(clean, append || offset > 0)
                if (fos == null) {
                    sendResponse("550 Cannot create file or storage permission denied.")
                    onLog(LogLevel.ERROR, "Upload failed: Cannot write ${file.name}", clientIp)
                    return@Thread
                }
                val `is` = dataSocket.getInputStream()
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalReceived = 0L

                while (`is`.read(buffer).also { bytesRead = it } != -1 && isRunning) {
                    fos.write(buffer, 0, bytesRead)
                    totalReceived += bytesRead
                }
                fos.flush()
                dataSocket.close()
                sendResponse("226 Transfer complete.")
                onLog(LogLevel.SUCCESS, "Upload complete: ${file.name} ($totalReceived bytes)", clientIp)
            } catch (e: Exception) {
                sendResponse("426 Connection closed; transfer aborted.")
                onLog(LogLevel.WARNING, "Upload failed: ${file.name} (${e.localizedMessage})", clientIp)
            } finally {
                try { fos?.close() } catch (_: Exception) {}
                try { dataSocket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun handleDele(path: String) {
        if (settings.isReadOnly) {
            sendResponse("550 Permission denied (Read-only mode).")
            return
        }
        val clean = FtpFileSystem.cleanFtpPath(path)
        if (fileSystem.deleteFile(clean)) {
            sendResponse("250 File deleted successfully.")
            onLog(LogLevel.INFO, "Deleted file: $clean", clientIp)
        } else {
            sendResponse("550 Delete operation failed.")
        }
    }

    private fun handleMkd(path: String) {
        if (settings.isReadOnly) {
            sendResponse("550 Permission denied (Read-only mode).")
            onLog(LogLevel.WARNING, "MKD failed: Server is in Read-only mode", clientIp)
            return
        }
        val clean = FtpFileSystem.cleanFtpPath(path)
        if (clean.isEmpty()) {
            sendResponse("550 Invalid directory name.")
            return
        }

        val targetFile = fileSystem.resolveVirtualPath(clean)
        val success = fileSystem.makeDirectory(clean)
        if (success) {
            val virtualPath = fileSystem.toVirtualPath(targetFile)
            // RFC 959 requires: 257 "<pathname>" [optional text]
            sendResponse("257 \"$virtualPath\" directory created.")
            onLog(LogLevel.SUCCESS, "Created directory: $virtualPath", clientIp)
        } else {
            val isRootWritable = fileSystem.getRootDirectory().canWrite()
            val msg = if (!isRootWritable) {
                "Storage permission not granted or root path not writable"
            } else if (targetFile.exists()) {
                "Directory already exists"
            } else {
                "Create directory failed"
            }
            sendResponse("550 Create directory failed.")
            onLog(LogLevel.ERROR, "MKD failed for '$clean' ($msg)", clientIp)
        }
    }

    private fun handleRmd(path: String) {
        if (settings.isReadOnly) {
            sendResponse("550 Permission denied (Read-only mode).")
            onLog(LogLevel.WARNING, "RMD failed: Server is in Read-only mode", clientIp)
            return
        }
        val clean = FtpFileSystem.cleanFtpPath(path)
        val targetFile = fileSystem.resolveVirtualPath(clean)
        if (fileSystem.removeDirectory(clean)) {
            val virtualPath = fileSystem.toVirtualPath(targetFile)
            sendResponse("250 Directory removed.")
            onLog(LogLevel.INFO, "Removed directory: $virtualPath", clientIp)
        } else {
            sendResponse("550 Remove directory failed.")
            onLog(LogLevel.ERROR, "RMD failed for '$clean'", clientIp)
        }
    }

    private fun handleRnfr(path: String) {
        if (settings.isReadOnly) {
            sendResponse("550 Permission denied (Read-only mode).")
            return
        }
        val clean = FtpFileSystem.cleanFtpPath(path)
        val file = fileSystem.getFile(clean)
        if (file.exists()) {
            renameFrom = clean
            sendResponse("350 File exists, ready for destination name.")
        } else {
            sendResponse("550 File does not exist.")
        }
    }

    private fun handleRnto(path: String) {
        val from = renameFrom
        if (from == null) {
            sendResponse("503 Bad sequence of commands.")
            return
        }
        renameFrom = null
        val clean = FtpFileSystem.cleanFtpPath(path)
        if (fileSystem.rename(from, clean)) {
            sendResponse("250 File renamed successfully.")
            onLog(LogLevel.INFO, "Renamed $from -> $clean", clientIp)
        } else {
            sendResponse("550 Rename failed.")
            onLog(LogLevel.ERROR, "Rename failed: $from -> $clean", clientIp)
        }
    }

    private fun sendResponse(response: String) {
        try {
            writer?.write("$response\r\n")
            writer?.flush()
        } catch (_: Exception) {}
    }

    fun close() {
        isRunning = false
        closePassiveSocket()
        try { controlSocket.close() } catch (_: Exception) {}
        onSessionClosed(this)
    }
}
