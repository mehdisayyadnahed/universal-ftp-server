package com.example.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.example.data.NetworkInterfaceInfo
import com.example.data.NetworkType
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkHelper {

    fun getActiveNetworkInterfaces(context: Context): List<NetworkInterfaceInfo> {
        val result = mutableListOf<NetworkInterfaceInfo>()
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        val wifiTransportInterfaces = mutableSetOf<String>()
        val bluetoothTransportInterfaces = mutableSetOf<String>()
        val excludedTransportInterfaces = mutableSetOf<String>()

        try {
            connectivityManager?.allNetworks?.forEach { network ->
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@forEach
                val ifaceName = connectivityManager.getLinkProperties(network)?.interfaceName?.lowercase() ?: return@forEach

                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                        excludedTransportInterfaces.add(ifaceName)
                    }
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> {
                        bluetoothTransportInterfaces.add(ifaceName)
                    }
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        wifiTransportInterfaces.add(ifaceName)
                    }
                }
            }
        } catch (_: Exception) {
        }

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val name = networkInterface.name.lowercase()
                if (excludedTransportInterfaces.contains(name) || isExcludedInterface(name)) continue

                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        val ip = address.hostAddress ?: continue

                        // Only include the 4 supported network modes:
                        // 1. Wi-Fi
                        // 2. Mobile Hotspot (Phone as Host)
                        // 3. USB Tethering
                        // 4. Bluetooth Network (PAN / Bluetooth Tethering)
                        val classified = classifySupportedInterface(
                            ifName = name,
                            ip = ip,
                            wifiManager = wifiManager,
                            wifiTransportInterfaces = wifiTransportInterfaces,
                            bluetoothTransportInterfaces = bluetoothTransportInterfaces
                        ) ?: continue

                        val (type, displayName, extra) = classified

                        result.add(
                            NetworkInterfaceInfo(
                                type = type,
                                name = networkInterface.name,
                                displayName = displayName,
                                ipAddress = ip,
                                isConnected = true,
                                extraInfo = extra
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback if Wi-Fi is connected via WifiManager but interface enumeration returned empty
        if (result.isEmpty()) {
            val wifiIp = getWifiIpFallback(wifiManager)
            if (wifiIp != null && wifiIp != "0.0.0.0") {
                result.add(
                    NetworkInterfaceInfo(
                        type = NetworkType.WIFI,
                        name = "wlan0",
                        displayName = "Wi-Fi",
                        ipAddress = wifiIp,
                        isConnected = true,
                        extraInfo = "Connected via Wi-Fi"
                    )
                )
            }
        }

        // Sort order: Wi-Fi, Mobile Hotspot, USB Tethering, Bluetooth Network
        return result.sortedBy {
            when (it.type) {
                NetworkType.WIFI -> 1
                NetworkType.HOTSPOT -> 2
                NetworkType.USB_TETHERING -> 3
                NetworkType.BLUETOOTH -> 4
            }
        }
    }

    internal fun classifySupportedInterface(
        ifName: String,
        ip: String,
        wifiManager: WifiManager? = null,
        wifiTransportInterfaces: Set<String> = emptySet(),
        bluetoothTransportInterfaces: Set<String> = emptySet()
    ): Triple<NetworkType, String, String>? {
        val lower = ifName.lowercase()
        if (isExcludedInterface(lower)) return null

        // 1. USB Tethering interfaces: rndis0, usb0, ncm0, ecm0
        if (lower.startsWith("rndis") ||
            lower.startsWith("usb") ||
            lower.startsWith("ncm") ||
            lower.startsWith("ecm")
        ) {
            return Triple(
                NetworkType.USB_TETHERING,
                "USB Tethering",
                "Direct High-Speed USB Connection ($ifName)"
            )
        }

        // 2. Bluetooth Network (PAN / Bluetooth Tethering): bt-pan, bnep0, btpan0, bt0
        if (lower.startsWith("bt-pan") ||
            lower.startsWith("bnep") ||
            lower.startsWith("btpan") ||
            lower.startsWith("bt_pan") ||
            lower == "bt0" ||
            bluetoothTransportInterfaces.contains(lower) ||
            (lower.startsWith("pan") && ip.startsWith("192.168.44."))
        ) {
            return Triple(
                NetworkType.BLUETOOTH,
                "Bluetooth Network",
                "Bluetooth PAN ($ifName)"
            )
        }

        // 3. Mobile Hotspot (Phone as Host / Access Point): ap0, softap0, swlan0, wlan_ap, wifi_sap
        // or wlan interface serving Android's standard Hotspot DHCP subnet (192.168.43.x / 192.168.50.x)
        val isDedicatedHotspotInterface = lower.startsWith("ap") ||
            lower.startsWith("softap") ||
            lower.startsWith("swlan") ||
            lower.startsWith("wlan_ap") ||
            lower.startsWith("wifi_sap") ||
            lower == "sap0"

        val isWlanHotspotSubnet = (lower.startsWith("wlan") || lower.startsWith("wifi")) &&
            (ip.startsWith("192.168.43.") || ip.startsWith("192.168.50.")) &&
            !wifiTransportInterfaces.contains(lower)

        if (isDedicatedHotspotInterface || isWlanHotspotSubnet) {
            return Triple(
                NetworkType.HOTSPOT,
                "Mobile Hotspot",
                "Wi-Fi Access Point ($ifName)"
            )
        }

        // 4. Wi-Fi Network: wlan0, wlan1, wifi0, tiwlan0, mlan0
        if (lower.startsWith("wlan") ||
            lower.startsWith("wifi") ||
            lower.startsWith("tiwlan") ||
            lower.startsWith("mlan") ||
            wifiTransportInterfaces.contains(lower)
        ) {
            val ssid = getWifiSsid(wifiManager)
            val extra = if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                "SSID: $ssid"
            } else {
                "Connected Wi-Fi ($ifName)"
            }
            return Triple(
                NetworkType.WIFI,
                "Wi-Fi",
                extra
            )
        }

        // Strictly reject all other interfaces (e.g., Ethernet eth0, cellular seth_lte8, rmnet, etc.)
        return null
    }

    internal fun isExcludedInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("seth") ||
            lower.startsWith("rmnet") ||
            lower.startsWith("r_rmnet") ||
            lower.startsWith("rev_rmnet") ||
            lower.startsWith("ccmni") ||
            lower.startsWith("pdp") ||
            lower.startsWith("wwan") ||
            lower.startsWith("clat") ||
            lower.startsWith("v4-") ||
            lower.startsWith("ims") ||
            lower.startsWith("vsnet") ||
            lower.startsWith("cdma") ||
            lower.startsWith("ril") ||
            lower.startsWith("qmi") ||
            lower.startsWith("tun") ||
            lower.startsWith("tap") ||
            lower.startsWith("wg") ||
            lower.startsWith("dummy") ||
            lower.startsWith("p2p") ||
            lower.startsWith("sit") ||
            lower.startsWith("ip6") ||
            lower.startsWith("ppp") ||
            lower.startsWith("virbr") ||
            lower.startsWith("vboxnet") ||
            lower.startsWith("eth") ||
            lower.startsWith("veth") ||
            lower.startsWith("lan") ||
            lower.startsWith("docker") ||
            lower.startsWith("br-") ||
            lower.startsWith("epdg") ||
            lower.startsWith("iwlan") ||
            lower.startsWith("lo")
    }

    fun isConnectionOnSupportedNetwork(localAddress: InetAddress?): Boolean {
        if (localAddress == null || localAddress.isLoopbackAddress) return true
        return try {
            val netIf = NetworkInterface.getByInetAddress(localAddress) ?: return false
            val ip = localAddress.hostAddress ?: return false
            classifySupportedInterface(netIf.name, ip) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun getWifiSsid(wifiManager: WifiManager?): String {
        return try {
            val info = wifiManager?.connectionInfo
            val ssid = info?.ssid ?: ""
            if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length > 2) {
                ssid.substring(1, ssid.length - 1)
            } else {
                ssid
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun getWifiIpFallback(wifiManager: WifiManager?): String? {
        return try {
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: return null
            if (ipInt == 0) return null
            String.format(
                java.util.Locale.US,
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (_: Exception) {
            null
        }
    }
}
