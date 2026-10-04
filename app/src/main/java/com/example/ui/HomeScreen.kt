package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.FtpSettings
import com.example.data.ServerProtocol
import com.example.data.StorageTarget
import com.example.data.StorageType
import com.example.ui.theme.Amber400
import com.example.ui.theme.Cyan40
import com.example.ui.theme.Cyan80
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Emerald500
import com.example.ui.theme.Indigo500
import com.example.ui.theme.Rose500
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.utils.StorageHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: FtpViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val networkInterfaces by viewModel.networkInterfaces.collectAsStateWithLifecycle()
    val storageTargets by viewModel.storageTargets.collectAsStateWithLifecycle()
    val hasStoragePermission by viewModel.hasStoragePermission.collectAsStateWithLifecycle()
    val uptimeString by viewModel.uptimeString.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showLogsSheet by remember { mutableStateOf(false) }
    var showPermissionGuideDialog by remember { mutableStateOf(false) }

    // Permission launcher for Android 11+ / Legacy
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshState()
    }

    // Launch Android All Files Access settings
    val openStorageSettings: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    // SAF Document Tree Folder Picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.selectCustomFolderUri(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.universal_ftp_icon),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier.size(36.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.header_app_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Wi-Fi • Hotspot • USB • Bluetooth",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showLogsSheet = true },
                        modifier = Modifier.testTag("logs_top_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ListAlt,
                            contentDescription = stringResource(R.string.activity_logs),
                            tint = if (logs.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { showGuideDialog = true },
                        modifier = Modifier.testTag("guide_top_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = stringResource(R.string.connection_guide),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.testTag("settings_top_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Storage Permission Warning Card (if needed)
            if (!hasStoragePermission) {
                item {
                    StoragePermissionBanner(
                        onRequestPermission = { showPermissionGuideDialog = true },
                        onPickCustomFolder = { folderPickerLauncher.launch(null) }
                    )
                }
            }

            // 2. Main Server Hero Control Card
            item {
                ServerHeroCard(
                    isRunning = serverStatus.isRunning,
                    isStarting = serverStatus.isStarting,
                    protocol = settings.protocol,
                    port = settings.port,
                    activeConnections = serverStatus.activeConnections,
                    uptime = uptimeString,
                    primaryIp = networkInterfaces.firstOrNull()?.ipAddress ?: "0.0.0.0",
                    onToggleServer = {
                        if (!serverStatus.isRunning && !hasStoragePermission) {
                            showPermissionGuideDialog = true
                        } else {
                            viewModel.toggleServer()
                        }
                    },
                    onCopyUrl = { viewModel.copyToClipboard(it) },
                    onShareUrl = { url ->
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, url)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share Server Address"))
                    }
                )
            }

            // 3. Quick Specs / Current Settings Summary Card
            item {
                QuickConfigSummaryCard(
                    settings = settings,
                    onOpenSettings = { showSettingsSheet = true }
                )
            }

            // 4. Shared Storage Directory Section
            item {
                StorageFolderCard(
                    settings = settings,
                    storageTargets = storageTargets,
                    onSelectTarget = { viewModel.selectStorageTarget(it) },
                    onPickCustomFolder = { folderPickerLauncher.launch(null) },
                    onOpenSettings = { showSettingsSheet = true }
                )
            }

            // 5. Active Network Interfaces Section
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.network_interfaces),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        TextButton(
                            text = "Connection Guide",
                            onClick = { showGuideDialog = true }
                        )
                    }

                    if (networkInterfaces.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Amber400)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.no_active_network),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            networkInterfaces.forEach { netInfo ->
                                NetworkInterfaceCard(
                                    networkInfo = netInfo,
                                    port = settings.port,
                                    protocol = settings.protocol,
                                    isServerRunning = serverStatus.isRunning,
                                    onCopyUrl = { viewModel.copyToClipboard(it) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Bottom Sheets
    if (showSettingsSheet) {
        SettingsBottomSheet(
            currentSettings = settings,
            storageTargets = storageTargets,
            isServerRunning = serverStatus.isRunning,
            onSave = { viewModel.updateSettings(it) },
            onDismiss = { showSettingsSheet = false },
            onPickCustomFolder = { folderPickerLauncher.launch(null) }
        )
    }

    if (showGuideDialog) {
        ConnectionGuideDialog(
            port = settings.port,
            onDismiss = { showGuideDialog = false }
        )
    }

    if (showLogsSheet) {
        LogsBottomSheet(
            logs = logs,
            onClearLogs = { viewModel.toggleServer(); viewModel.toggleServer() /* or clear */ },
            onCopyLogs = { viewModel.copyToClipboard(it, "FTP Logs") },
            onDismiss = { showLogsSheet = false }
        )
    }

    if (showPermissionGuideDialog) {
        StoragePermissionGuideDialog(
            onOpenSettings = {
                showPermissionGuideDialog = false
                openStorageSettings()
            },
            onPickSaf = {
                showPermissionGuideDialog = false
                folderPickerLauncher.launch(null)
            },
            onDismiss = { showPermissionGuideDialog = false }
        )
    }
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun ServerHeroCard(
    isRunning: Boolean,
    isStarting: Boolean,
    protocol: ServerProtocol = ServerProtocol.FTP,
    port: Int,
    activeConnections: Int,
    uptime: String,
    primaryIp: String,
    onToggleServer: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onShareUrl: (String) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val serverScheme = if (protocol == ServerProtocol.SFTP) "sftp" else "ftp"
    val serverUrl = "$serverScheme://$primaryIp:$port"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    if (isRunning) listOf(Cyan80, Emerald400) else listOf(Slate700, Slate800)
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .testTag("hero_server_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Live Status Indicator & Protocol Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Status Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isRunning) Emerald500.copy(alpha = 0.15f) else Slate700.copy(alpha = 0.3f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (isRunning) Emerald500 else Rose500, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) stringResource(R.string.server_running) else stringResource(R.string.server_stopped),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Protocol Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (protocol == ServerProtocol.SFTP) MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (protocol == ServerProtocol.SFTP) "SFTP (SSH)" else "FTP",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (protocol == ServerProtocol.SFTP) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Big Power Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(120.dp)
            ) {
                // Outer glowing circle
                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(pulseScale)
                            .background(Cyan80.copy(alpha = 0.15f), CircleShape)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                if (isRunning) listOf(Emerald500, Cyan40) else listOf(Slate800, Slate700)
                            )
                        )
                        .clickable(onClick = onToggleServer)
                        .testTag("power_toggle_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = if (isRunning) stringResource(R.string.stop_server) else stringResource(R.string.start_server),
                            tint = if (isRunning) Color.White else Cyan80,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isRunning) stringResource(R.string.stop_server) else stringResource(R.string.start_server),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Primary Server URL Display (when running)
            AnimatedVisibility(visible = isRunning, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onCopyUrl(serverUrl) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            ) {
                                Text(
                                    text = if (protocol == ServerProtocol.SFTP) "SFTP Server Address" else stringResource(R.string.ftp_url_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = serverUrl,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (protocol == ServerProtocol.SFTP) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                    )
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onCopyUrl(serverUrl) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onShareUrl(serverUrl) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Live Stats Bar (Connections & Uptime)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$activeConnections",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = stringResource(R.string.active_connections),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uptime,
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.uptime),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickConfigSummaryCard(
    settings: FtpSettings,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenSettings)
            .testTag("quick_config_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Protocol
            Column {
                Text(
                    text = "Protocol",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = settings.protocol.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (settings.protocol == ServerProtocol.SFTP) Emerald400 else Cyan80
                )
            }

            // Port
            Column {
                Text(
                    text = "Port",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${settings.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Auth
            Column {
                Text(
                    text = "Auth",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (settings.isAnonymous) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (settings.isAnonymous) Amber400 else Emerald400
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (settings.isAnonymous) "Anonymous" else settings.username,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Permission
            Column {
                Text(
                    text = "Access",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (settings.isReadOnly) "Read-Only" else "Read & Write",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (settings.isReadOnly) Amber400 else Emerald400
                )
            }

            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Edit Settings",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun StorageFolderCard(
    settings: FtpSettings,
    storageTargets: List<StorageTarget>,
    onSelectTarget: (StorageTarget) -> Unit,
    onPickCustomFolder: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val selectedTarget = storageTargets.firstOrNull { it.path == settings.rootPath }
    val freeFormatted = selectedTarget?.freeSpaceBytes?.let { StorageHelper.formatBytes(it) } ?: ""
    val totalFormatted = selectedTarget?.totalSpaceBytes?.let { StorageHelper.formatBytes(it) } ?: ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .testTag("storage_folder_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.shared_storage),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                TextButton(
                    text = "Settings",
                    onClick = onOpenSettings
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = settings.rootDisplayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = settings.rootPath.ifEmpty { StorageHelper.getDefaultStoragePath() },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (selectedTarget != null && selectedTarget.totalSpaceBytes > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                val usedRatio = 1f - (selectedTarget.freeSpaceBytes.toFloat() / selectedTarget.totalSpaceBytes.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Slate700
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Free: $freeFormatted of $totalFormatted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Selection Chips for popular folders
            Text(
                text = "Quick select shared folder:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Button to pick any folder using SAF
                item {
                    FilterChip(
                        selected = false,
                        onClick = onPickCustomFolder,
                        label = { Text("📂 System Picker (SAF)") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }

                items(storageTargets) { target ->
                    val isSelected = target.path == settings.rootPath
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectTarget(target) },
                        label = {
                            Text(
                                text = target.displayName.substringBefore(" ("),
                                maxLines = 1
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = when (target.type) {
                                    StorageType.INTERNAL -> Icons.Default.Smartphone
                                    StorageType.SD_CARD -> Icons.Default.SdCard
                                    StorageType.CUSTOM -> Icons.Default.Folder
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun StoragePermissionBanner(
    onRequestPermission: () -> Unit,
    onPickCustomFolder: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Amber400.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Amber400.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = Amber400, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.permission_required),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "To grant full read, write, and directory creation access, allow All Files Access or select a specific folder using the system picker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .testTag("grant_permission_btn"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Amber400, contentColor = Slate900)
                ) {
                    Text("Grant Full Access", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onPickCustomFolder,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .testTag("grant_saf_folder_btn"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("Select Folder (SAF)", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StoragePermissionGuideDialog(
    onOpenSettings: () -> Unit,
    onPickSaf: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = Amber400,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Enable File Management Permission",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "On Android 11 and above, 'All Files Access' permission is required for clients to create folders, upload files, or rename files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "📌 Steps to enable:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Cyan80
                        )
                        Text(
                            text = "1. Tap 'Open Settings' below.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "2. In the opened screen, turn on 'Allow access to manage all files'.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "3. Return to the app and start the server.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Amber400, contentColor = Slate900),
                modifier = Modifier.testTag("open_settings_confirm_btn")
            ) {
                Text("Open Settings", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(),
                modifier = Modifier.testTag("permission_cancel_btn")
            ) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

