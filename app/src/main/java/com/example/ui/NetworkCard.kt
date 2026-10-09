package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.NetworkInterfaceInfo
import com.example.data.NetworkType
import com.example.data.ServerProtocol
import com.example.ui.theme.Cyan40
import com.example.ui.theme.Cyan80
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Emerald500
import com.example.ui.theme.Indigo500
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800

@Composable
fun NetworkInterfaceCard(
    networkInfo: NetworkInterfaceInfo,
    port: Int,
    protocol: ServerProtocol = ServerProtocol.FTP,
    isServerRunning: Boolean,
    onCopyUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val serverUrl = networkInfo.getServerUrl(protocol, port)
    val (icon, tintColor) = when (networkInfo.type) {
        NetworkType.USB_TETHERING -> Icons.Default.Usb to Cyan80
        NetworkType.HOTSPOT -> Icons.Default.WifiTethering to Emerald400
        NetworkType.WIFI -> Icons.Default.Wifi to Indigo500
        NetworkType.BLUETOOTH -> Icons.Default.Bluetooth to Cyan40
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = if (isServerRunning) tintColor.copy(alpha = 0.35f) else Slate700.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable {
                if (isServerRunning) {
                    onCopyUrl(serverUrl)
                }
            }
            .testTag("network_card_${networkInfo.name}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Icon container
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(tintColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = networkInfo.displayName,
                        tint = tintColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = networkInfo.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Connected badge
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Emerald500, CircleShape)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    if (isServerRunning) {
                        Text(
                            text = serverUrl,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "IP: ${networkInfo.ipAddress}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (networkInfo.extraInfo.isNotEmpty()) {
                        Text(
                            text = networkInfo.extraInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            if (isServerRunning) {
                IconButton(
                    onClick = { onCopyUrl(serverUrl) },
                    modifier = Modifier.testTag("copy_url_btn_${networkInfo.name}")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.copy),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
