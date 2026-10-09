package com.example.server

import android.content.Context
import com.example.data.FtpSettings
import com.example.data.LogEntry
import com.example.data.LogLevel
import com.example.utils.NetworkHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FtpServerEngine(
    val context: Context? = null,
    val settings: FtpSettings,
    private val onStateChanged: ((isRunning: Boolean, activeConnections: Int, error: String?) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    private var executorService: ExecutorService? = null
    private val activeSessions = CopyOnWriteArrayList<FtpSession>()

    @Volatile
    private var isRunning = false
    private var startedAt: Long = 0L

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun isServerRunning(): Boolean = isRunning

    fun getStartedAt(): Long = startedAt

    fun getActiveConnectionsCount(): Int = activeSessions.size

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true

        return try {
            val port = settings.port
            val ss = ServerSocket(port)
            ss.reuseAddress = true
            serverSocket = ss
            executorService = Executors.newCachedThreadPool()
            isRunning = true
            startedAt = System.currentTimeMillis()

            addLog(LogLevel.SUCCESS, "FTP Server started on port $port (Root: ${settings.rootPath})")
            onStateChanged?.invoke(true, 0, null)

            // Listener thread
            Thread({
                while (isRunning && !ss.isClosed) {
                    try {
                        val socket = ss.accept()
                        if (!NetworkHelper.isConnectionOnSupportedNetwork(socket.localAddress)) {
                            try {
                                socket.close()
                            } catch (_: Exception) {}
                            continue
                        }
                        val session = FtpSession(
                            context = context,
                            controlSocket = socket,
                            settings = settings,
                            onLog = { level, message, clientIp ->
                                addLog(level, message, clientIp)
                            },
                            onSessionClosed = { closedSession ->
                                activeSessions.remove(closedSession)
                                onStateChanged?.invoke(isRunning, activeSessions.size, null)
                            }
                        )
                        activeSessions.add(session)
                        onStateChanged?.invoke(isRunning, activeSessions.size, null)
                        executorService?.submit(session)
                    } catch (e: Exception) {
                        if (isRunning) {
                            addLog(LogLevel.ERROR, "Accept error: ${e.localizedMessage}")
                        }
                    }
                }
            }, "FtpServerListener").start()

            true
        } catch (e: Exception) {
            isRunning = false
            addLog(LogLevel.ERROR, "Failed to start FTP server on port ${settings.port}: ${e.localizedMessage}")
            onStateChanged?.invoke(false, 0, e.localizedMessage)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning && serverSocket == null) return
        isRunning = false
        startedAt = 0L

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (session in activeSessions) {
            session.close()
        }
        activeSessions.clear()

        executorService?.shutdownNow()
        executorService = null

        addLog(LogLevel.INFO, "FTP Server stopped.")
        onStateChanged?.invoke(false, 0, null)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun addLog(level: LogLevel, message: String, clientIp: String? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            clientIp = clientIp
        )
        val current = _logs.value.toMutableList()
        if (current.size > 200) {
            current.removeAt(0)
        }
        current.add(entry)
        _logs.value = current
    }
}
