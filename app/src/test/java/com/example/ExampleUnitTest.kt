package com.example

import com.example.utils.NetworkHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun `virtual and tunnel interfaces are correctly identified`() {
    assertTrue(NetworkHelper.isVirtualOrTunnelInterface("tun0"))
    assertTrue(NetworkHelper.isVirtualOrTunnelInterface("tun1"))
    assertTrue(NetworkHelper.isVirtualOrTunnelInterface("tap0"))
    assertTrue(NetworkHelper.isVirtualOrTunnelInterface("wg0"))
    assertTrue(NetworkHelper.isVirtualOrTunnelInterface("dummy0"))
    assertTrue(NetworkHelper.isVirtualOrTunnelInterface("p2p0"))
    assertTrue(NetworkHelper.isVirtualOrTunnelInterface("virbr0"))
    assertTrue(NetworkHelper.isVirtualOrTunnelInterface("vboxnet0"))
  }

  @Test
  fun `real network interfaces are not identified as virtual`() {
    assertFalse(NetworkHelper.isVirtualOrTunnelInterface("wlan0"))
    assertFalse(NetworkHelper.isVirtualOrTunnelInterface("eth0"))
    assertFalse(NetworkHelper.isVirtualOrTunnelInterface("rmnet0"))
    assertFalse(NetworkHelper.isVirtualOrTunnelInterface("swlan0"))
  }
}
