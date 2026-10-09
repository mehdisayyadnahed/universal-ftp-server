package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.Cyan40
import com.example.ui.theme.Cyan80
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Indigo500
import com.example.ui.theme.Slate700

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionGuideDialog(
    port: Int,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val tabs = listOf(
        "USB Tethering" to Icons.Default.Usb,
        "Mobile Hotspot" to Icons.Default.WifiTethering,
        "Wi-Fi Network" to Icons.Default.Wifi,
        "Bluetooth Network" to Icons.Default.Bluetooth,
        "Windows (This PC)" to Icons.Default.Computer,
        "FileZilla / WinSCP" to Icons.Default.Lan
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("guide_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.connection_guide),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp,
                containerColor = Color.Transparent,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                tabs.forEachIndexed { index, (title, icon) ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(title, fontSize = 13.sp)
                            }
                        }
                    )
                }
            }

            // Tab Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTab) {
                    0 -> UsbTetheringGuide(port)
                    1 -> HotspotGuide(port)
                    2 -> WifiGuide(port)
                    3 -> BluetoothNetworkGuide(port)
                    4 -> WindowsExplorerGuide(port)
                    5 -> FileZillaGuide(port)
                }
            }
        }
    }
}

@Composable
private fun UsbTetheringGuide(port: Int) {
    GuideSection(
        title = "High-speed connection via USB cable (No router or internet required)",
        icon = Icons.Default.Usb,
        color = Cyan80,
        steps = listOf(
            "Connect your phone to your computer using a USB charging / data cable.",
            "On your phone, go to Settings > Network & internet > Hotspot & tethering.",
            "Turn on 'USB tethering'.",
            "In this app, tap 'Start Server'. Copy the address displayed for the USB interface (usually 192.168.42.x:$port).",
            "On your computer, open File Explorer / This PC or browser, paste the address in the top bar, and press Enter!"
        ),
        tip = "USB cable transfer speeds are typically significantly faster than Wi-Fi and do not consume any mobile internet data."
    )
}

@Composable
private fun HotspotGuide(port: Int) {
    GuideSection(
        title = "Connect via Phone Mobile Hotspot",
        icon = Icons.Default.WifiTethering,
        color = Emerald400,
        steps = listOf(
            "Turn on Mobile Hotspot (Wi-Fi AP) on your phone.",
            "On your computer or laptop, connect to the Wi-Fi network created by your phone.",
            "Start the server in this app. The hotspot address (e.g. ftp://192.168.43.1:$port) will be displayed.",
            "Enter the address on your computer to access all device storage files."
        ),
        tip = "Direct connection between phone and computer works completely offline even with cellular mobile data turned off."
    )
}

@Composable
private fun WifiGuide(port: Int) {
    GuideSection(
        title = "Connect via Shared Wi-Fi Network (Wi-Fi Router)",
        icon = Icons.Default.Wifi,
        color = Indigo500,
        steps = listOf(
            "Make sure your phone and computer are connected to the same Wi-Fi network / router.",
            "Tap 'Start Server' in this app.",
            "The Wi-Fi server address (e.g. ftp://192.168.1.50:$port) will appear on the home screen.",
            "Enter the address on your computer to browse and manage files."
        ),
        tip = "If connection fails, check whether 'AP Isolation' or 'Guest Network Isolation' is enabled on your router."
    )
}

@Composable
private fun BluetoothNetworkGuide(port: Int) {
    GuideSection(
        title = "Connect via Bluetooth Network (Bluetooth PAN / Tethering)",
        icon = Icons.Default.Bluetooth,
        color = Cyan40,
        steps = listOf(
            "Pair your phone and computer (or second device) via Bluetooth.",
            "Enable 'Bluetooth tethering' in Settings > Network & internet > Hotspot & tethering.",
            "On your computer or client device, connect to the phone's Bluetooth Personal Area Network (PAN / Access Point).",
            "Tap 'Start Server' in this app and use the displayed Bluetooth Network IP address (typically 192.168.44.x:$port)."
        ),
        tip = "Bluetooth PAN allows network file access with very low battery consumption when Wi-Fi or USB is unavailable."
    )
}

@Composable
private fun WindowsExplorerGuide(port: Int) {
    GuideSection(
        title = "Open Directly in Windows (File Explorer / This PC)",
        icon = Icons.Default.Computer,
        color = Cyan40,
        steps = listOf(
            "On Windows, open This PC or File Explorer.",
            "Click on the top address bar (where it says 'This PC').",
            "Type the server address exactly (e.g. ftp://192.168.43.1:$port) and press Enter.",
            "If credentials are configured, enter your username and password when prompted.",
            "Your phone's storage opens like a native Windows drive—you can drag, copy, and paste files directly!"
        ),
        tip = "You can right-click empty space in This PC and select 'Add a network location' to permanently map your phone as a network drive."
    )
}

@Composable
private fun FileZillaGuide(port: Int) {
    GuideSection(
        title = "Connect with FTP Clients (FileZilla / WinSCP)",
        icon = Icons.Default.Lan,
        color = MaterialTheme.colorScheme.primary,
        steps = listOf(
            "Open FileZilla or WinSCP on your computer.",
            "In the Host field, enter your phone's IP address (without ftp:// or sftp:// prefix).",
            "In the Port field, enter the port number ($port).",
            "In the Username and Password fields, enter the credentials configured in the app.",
            "Click 'Quickconnect' or 'Connect' to begin high-speed dual-pane file transfers."
        ),
        tip = "FileZilla supports download resuming, queueing, and transferring entire folder hierarchies in parallel."
    )
}

@Composable
private fun GuideSection(
    title: String,
    icon: ImageVector,
    color: Color,
    steps: List<String>,
    tip: String
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(color.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tip Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💡 Tip: $tip",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
