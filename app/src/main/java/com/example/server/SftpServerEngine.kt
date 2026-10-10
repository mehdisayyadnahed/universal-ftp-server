package com.example.server

import android.content.Context
import com.example.data.FtpSettings
import com.example.data.LogLevel
import com.example.utils.NetworkHelper
import com.example.utils.StorageHelper
import java.net.InetSocketAddress
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.concurrent.atomic.AtomicInteger

class SftpServerEngine(
    private val context: Context? = null,
    private val settings: FtpSettings,
    private val onLog: (LogLevel, String, String?) -> Unit,
    private val onConnectionCountChanged: (Int) -> Unit
) {
    private var sshServer: SshServer? = null
    private val activeConnections = AtomicInteger(0)

    @Volatile
    private var isRunning = false

    companion object {
        @Volatile
        private var sharedKeyPair: KeyPair? = null

        @Synchronized
        fun getOrCreateKeyPair(): KeyPair {
            sharedKeyPair?.let { return it }
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val pair = keyGen.generateKeyPair()
            sharedKeyPair = pair
            return pair
        }
    }

    fun isServerRunning(): Boolean = isRunning

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true

        return try {
            // Android compatibility setup
            try {
                OsUtils.setAndroid(true)
            } catch (_: Throwable) {}

            try {
                if (System.getProperty("user.home").isNullOrBlank()) {
                    System.setProperty("user.home", context?.filesDir?.absolutePath ?: "/sdcard")
                }
                if (System.getProperty("user.name").isNullOrBlank()) {
                    System.setProperty("user.name", "android")
                }
            } catch (_: Throwable) {}

            val server = SshServer.setUpDefaultServer()
            server.port = settings.port

            // Use in-memory RSA key provider to prevent Android file serialization / BouncyCastle crashes
            val keyPair = getOrCreateKeyPair()
            server.keyPairProvider = KeyPairProvider.wrap(keyPair)

            // Password authentication
            server.passwordAuthenticator = PasswordAuthenticator { username, password, session ->
                val localAddr = (session.ioSession?.localAddress as? InetSocketAddress)?.address
                if (!NetworkHelper.isConnectionOnSupportedNetwork(localAddr)) {
                    false
                } else {
                    val clientAddress = session.remoteAddress?.toString()
                    if (settings.isAnonymous) {
                        onLog(LogLevel.SUCCESS, "SFTP login accepted (Anonymous/User: $username) from $clientAddress", clientAddress)
                        true
                    } else {
                        if (username == settings.username && password == settings.password) {
                            onLog(LogLevel.SUCCESS, "SFTP User '$username' authenticated from $clientAddress", clientAddress)
                            true
                        } else {
                            onLog(LogLevel.WARNING, "SFTP Authentication failed for user '$username' from $clientAddress", clientAddress)
                            false
                        }
                    }
                }
            }

            // File system root
            val effectiveRootPath = if (settings.rootPath.isBlank()) {
                StorageHelper.getDefaultStoragePath()
            } else {
                settings.rootPath
            }
            val rootDir = File(effectiveRootPath)
            if (!rootDir.exists()) {
                rootDir.mkdirs()
            }
            val rootPath: Path = Paths.get(rootDir.absolutePath)
            server.fileSystemFactory = VirtualFileSystemFactory(rootPath)

            // SFTP subsystem
            val sftpFactory = SftpSubsystemFactory.Builder().build()
            server.subsystemFactories = listOf(sftpFactory)

            // Session tracking
            server.addSessionListener(object : SessionListener {
                override fun sessionCreated(session: Session?) {
                    val count = activeConnections.incrementAndGet()
                    val addr = session?.remoteAddress?.toString()
                    onLog(LogLevel.INFO, "SFTP client connected ($addr)", addr)
                    onConnectionCountChanged(count)
                }

                override fun sessionClosed(session: Session?) {
                    val count = (activeConnections.decrementAndGet()).coerceAtLeast(0)
                    val addr = session?.remoteAddress?.toString()
                    onLog(LogLevel.INFO, "SFTP client disconnected ($addr)", addr)
                    onConnectionCountChanged(count)
                }
            })

            server.start()
            sshServer = server
            isRunning = true

            onLog(LogLevel.SUCCESS, "SFTP Server started on port ${settings.port} (Root: ${rootDir.absolutePath})", null)
            true
        } catch (e: Throwable) {
            isRunning = false
            onLog(LogLevel.ERROR, "Failed to start SFTP server on port ${settings.port}: ${e.message}", null)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning && sshServer == null) return
        isRunning = false
        activeConnections.set(0)

        try {
            sshServer?.stop(true)
        } catch (_: Exception) {}
        sshServer = null

        onLog(LogLevel.INFO, "SFTP Server stopped.", null)
        onConnectionCountChanged(0)
    }
}
