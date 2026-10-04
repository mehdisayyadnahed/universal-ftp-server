package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.FtpSettings
import com.example.data.FtpSettingsRepository
import com.example.data.LogEntry
import com.example.data.LogLevel
import com.example.data.ServerProtocol
import com.example.utils.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FtpServerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ftpEngine: FtpServerEngine? = null
    private var sftpEngine: SftpServerEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var settingsRepository: FtpSettingsRepository

    inner class LocalBinder : Binder() {
        fun getService(): FtpServerService = this@FtpServerService
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = FtpSettingsRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                val settings = settingsRepository.loadSettings()
                startServer(settings)
            }
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startServer(settings: FtpSettings) {
        if (ftpEngine?.isServerRunning() == true || sftpEngine?.isServerRunning() == true) return

        // Immediately start foreground service to satisfy Android OS requirement
        val initialNotification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, initialNotification, type)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        _serverStatus.value = ServerStatus(
            isRunning = true,
            isStarting = true,
            protocol = settings.protocol,
            port = settings.port,
            rootPath = settings.rootPath,
            activeConnections = 0
        )

        // WakeLock & WifiLock
        if (settings.isWakeLockEnabled) {
            acquireLocks()
        }

        val started = try {
            if (settings.protocol == ServerProtocol.SFTP) {
                startSftpEngine(settings)
            } else {
                startFtpEngine(settings)
            }
        } catch (e: Throwable) {
            false
        }

        if (started) {
            updateNotification()
        } else {
            releaseLocks()
            _serverStatus.value = ServerStatus(
                isRunning = false,
                protocol = settings.protocol,
                errorMessage = "Failed to bind port ${settings.port}"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startFtpEngine(settings: FtpSettings): Boolean {
        val engine = FtpServerEngine(
            context = applicationContext,
            settings = settings,
            onStateChanged = { running, count, error ->
                _serverStatus.value = ServerStatus(
                    isRunning = running,
                    isStarting = false,
                    protocol = ServerProtocol.FTP,
                    port = settings.port,
                    rootPath = settings.rootPath,
                    activeConnections = count,
                    errorMessage = error,
                    startedAt = ftpEngine?.getStartedAt() ?: 0L
                )
                if (running) {
                    updateNotification()
                } else if (error != null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        )
        ftpEngine = engine

        val started = engine.start()
        if (started) {
            serviceScope.launch {
                engine.logs.collect {
                    _logsFlow.value = it
                }
            }
        }
        return started
    }

    private fun startSftpEngine(settings: FtpSettings): Boolean {
        val startTime = System.currentTimeMillis()
        val engine = SftpServerEngine(
            context = applicationContext,
            settings = settings,
            onLog = { level, message, clientIp ->
                addLog(level, message, clientIp)
            },
            onConnectionCountChanged = { count ->
                _serverStatus.value = _serverStatus.value.copy(
                    activeConnections = count
                )
                updateNotification()
            }
        )
        sftpEngine = engine

        val started = engine.start()
        if (started) {
            _serverStatus.value = ServerStatus(
                isRunning = true,
                isStarting = false,
                protocol = ServerProtocol.SFTP,
                port = settings.port,
                rootPath = settings.rootPath,
                activeConnections = 0,
                startedAt = startTime
            )
        }
        return started
    }

    private fun addLog(level: LogLevel, message: String, clientIp: String? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = "SFTP",
            message = message,
            clientIp = clientIp
        )
        val current = _logsFlow.value.toMutableList()
        if (current.size > 200) {
            current.removeAt(0)
        }
        current.add(entry)
        _logsFlow.value = current
    }

    private fun stopServer() {
        ftpEngine?.stop()
        ftpEngine = null
        sftpEngine?.stop()
        sftpEngine = null
        releaseLocks()
        _serverStatus.value = ServerStatus(isRunning = false)
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FtpServer:WakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L) // 12 hours max
                }
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(mode, "FtpServer:WifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null

            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FtpServerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val interfaces = NetworkHelper.getActiveNetworkInterfaces(this)
        val currentStatus = _serverStatus.value
        val port = currentStatus.port
        val protocol = currentStatus.protocol
        val primaryUrl = if (interfaces.isNotEmpty()) {
            interfaces.first().getServerUrl(protocol, port)
        } else {
            "${protocol.scheme}://0.0.0.0:$port"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (protocol == ServerProtocol.SFTP) "SFTP Server is Active" else getString(R.string.notification_title))
            .setContentText(primaryUrl)
            .setSmallIcon(R.drawable.universal_ftp_icon)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop),
                pendingStopIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "ftp_server_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.ftpserver.ACTION_START"
        const val ACTION_STOP = "com.example.ftpserver.ACTION_STOP"

        private val _serverStatus = MutableStateFlow(ServerStatus())
        val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

        private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
        val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

data class ServerStatus(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val protocol: ServerProtocol = ServerProtocol.FTP,
    val port: Int = 2121,
    val rootPath: String = "",
    val activeConnections: Int = 0,
    val startedAt: Long = 0L,
    val errorMessage: String? = null
)
