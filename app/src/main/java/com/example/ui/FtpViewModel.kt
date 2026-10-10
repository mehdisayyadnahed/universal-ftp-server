package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.FtpSettings
import com.example.data.FtpSettingsRepository
import com.example.data.LogEntry
import com.example.data.NetworkInterfaceInfo
import com.example.data.StorageTarget
import com.example.server.FtpServerService
import com.example.server.ServerStatus
import com.example.utils.NetworkHelper
import com.example.utils.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FtpViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = FtpSettingsRepository(application)

    val serverStatus: StateFlow<ServerStatus> = FtpServerService.serverStatus
    val logs: StateFlow<List<LogEntry>> = FtpServerService.logsFlow

    val settings: StateFlow<FtpSettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = settingsRepository.loadSettings()
    )

    private val _networkInterfaces = MutableStateFlow<List<NetworkInterfaceInfo>>(emptyList())
    val networkInterfaces: StateFlow<List<NetworkInterfaceInfo>> = _networkInterfaces.asStateFlow()

    private val _storageTargets = MutableStateFlow<List<StorageTarget>>(emptyList())
    val storageTargets: StateFlow<List<StorageTarget>> = _storageTargets.asStateFlow()

    private val _hasStoragePermission = MutableStateFlow(true)
    val hasStoragePermission: StateFlow<Boolean> = _hasStoragePermission.asStateFlow()

    private val _uptimeString = MutableStateFlow("00:00:00")
    val uptimeString: StateFlow<String> = _uptimeString.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            refreshStateInternal()
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 850L) {
                delay(850L - elapsed)
            }
            _isReady.value = true
        }
        startPeriodicRefresh()
        startUptimeTracker()
    }

    fun refreshState() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshStateInternal()
        }
    }

    private fun refreshStateInternal() {
        val context = getApplication<Application>()
        _networkInterfaces.value = NetworkHelper.getActiveNetworkInterfaces(context)
        _storageTargets.value = StorageHelper.getAvailableStorageTargets(context)
        _hasStoragePermission.value = StorageHelper.hasStoragePermission(context)

        // If settings rootPath is empty, set default
        val currentSettings = settings.value
        if (currentSettings.rootPath.isEmpty()) {
            val defaultPath = StorageHelper.getDefaultStoragePath()
            updateSettings(currentSettings.copy(rootPath = defaultPath), fromUserAction = false)
        }
    }

    private fun startPeriodicRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(3000)
                val context = getApplication<Application>()
                _networkInterfaces.value = NetworkHelper.getActiveNetworkInterfaces(context)
                _hasStoragePermission.value = StorageHelper.hasStoragePermission(context)
            }
        }
    }

    private fun startUptimeTracker() {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val status = serverStatus.value
                if (status.isRunning && status.startedAt > 0) {
                    val diff = (System.currentTimeMillis() - status.startedAt) / 1000
                    val hours = diff / 3600
                    val minutes = (diff % 3600) / 60
                    val seconds = diff % 60
                    _uptimeString.value = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    _uptimeString.value = "00:00:00"
                }
            }
        }
    }

    fun toggleServer() {
        val context = getApplication<Application>()
        val isRunning = serverStatus.value.isRunning

        if (isRunning) {
            FtpServerService.stopService(context)
        } else {
            FtpServerService.startService(context)
        }
    }

    private var restartToast: Toast? = null
    private var lastRestartToastTime: Long = 0L

    private fun showRestartServerToastIfNeeded() {
        val context = getApplication<Application>()
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartToastTime > 2000L) {
            restartToast?.cancel()
            restartToast = Toast.makeText(
                context,
                context.getString(R.string.restart_server_to_apply_changes),
                Toast.LENGTH_LONG
            ).also { it.show() }
            lastRestartToastTime = now
        }
    }

    fun updateSettings(newSettings: FtpSettings, fromUserAction: Boolean = true) {
        val previousSettings = settingsRepository.settingsFlow.value
        settingsRepository.saveSettings(newSettings)
        if (fromUserAction && serverStatus.value.isRunning && previousSettings != newSettings) {
            showRestartServerToastIfNeeded()
        }
    }

    fun selectStorageTarget(target: StorageTarget) {
        val current = settingsRepository.settingsFlow.value
        updateSettings(
            current.copy(
                rootPath = target.path,
                rootDisplayName = target.displayName,
                rootTreeUriString = target.treeUriString
            )
        )
    }

    fun selectCustomFolderUri(uri: android.net.Uri) {
        val context = getApplication<Application>()
        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Exception) {}

        val target = StorageHelper.getPathFromTreeUri(context, uri)
        if (target != null) {
            val currentTargets = _storageTargets.value.toMutableList()
            if (!currentTargets.any { it.path == target.path }) {
                currentTargets.add(0, target)
                _storageTargets.value = currentTargets
            }
            val wasChanged = settingsRepository.settingsFlow.value.rootPath != target.path ||
                settingsRepository.settingsFlow.value.rootTreeUriString != target.treeUriString
            selectStorageTarget(target)
            if (serverStatus.value.isRunning) {
                if (!wasChanged) {
                    showRestartServerToastIfNeeded()
                }
            } else {
                Toast.makeText(context, "Shared folder set: ${target.displayName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun copyToClipboard(text: String, label: String = "FTP URL") {
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }
}
