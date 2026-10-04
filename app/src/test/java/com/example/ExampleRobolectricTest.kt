package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppThemeMode
import com.example.data.FtpSettingsRepository
import com.example.data.NetworkType
import com.example.utils.NetworkHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("UFTP Server", appName)
    }

    @Test
    fun `only wifi hotspot usb tethering and bluetooth network are supported`() {
        assertEquals(NetworkType.WIFI, NetworkHelper.classifySupportedInterface("wlan0", "192.168.1.10")?.first)
        assertEquals(NetworkType.HOTSPOT, NetworkHelper.classifySupportedInterface("ap0", "192.168.43.1")?.first)
        assertEquals(NetworkType.HOTSPOT, NetworkHelper.classifySupportedInterface("swlan0", "192.168.50.1")?.first)
        assertEquals(NetworkType.USB_TETHERING, NetworkHelper.classifySupportedInterface("rndis0", "192.168.42.129")?.first)
        assertEquals(NetworkType.USB_TETHERING, NetworkHelper.classifySupportedInterface("ncm0", "192.168.42.1")?.first)
        assertEquals(NetworkType.BLUETOOTH, NetworkHelper.classifySupportedInterface("bt-pan", "192.168.44.1")?.first)
        assertEquals(NetworkType.BLUETOOTH, NetworkHelper.classifySupportedInterface("bnep0", "192.168.44.2")?.first)

        // Cellular, Ethernet, and other interfaces must be rejected
        assertNull(NetworkHelper.classifySupportedInterface("seth_lte8", "10.123.45.67"))
        assertNull(NetworkHelper.classifySupportedInterface("rmnet_data0", "10.0.0.1"))
        assertNull(NetworkHelper.classifySupportedInterface("ccmni0", "100.64.0.1"))
        assertNull(NetworkHelper.classifySupportedInterface("eth0", "192.168.1.50"))
        assertNull(NetworkHelper.classifySupportedInterface("tun0", "10.8.0.2"))
    }

    @Test
    fun `settings repository loads and saves correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = FtpSettingsRepository(context)
        val initial = repo.loadSettings()
        assertNotNull(initial)
        assertEquals(2121, initial.port)
        assertEquals(AppThemeMode.AUTO, initial.themeMode)

        val updated = initial.copy(port = 8021, username = "testuser", isAnonymous = true, themeMode = AppThemeMode.LIGHT)
        repo.saveSettings(updated)
        val loaded = repo.loadSettings()
        assertEquals(8021, loaded.port)
        assertEquals("testuser", loaded.username)
        assertEquals(true, loaded.isAnonymous)
        assertEquals(AppThemeMode.LIGHT, loaded.themeMode)
    }
}

