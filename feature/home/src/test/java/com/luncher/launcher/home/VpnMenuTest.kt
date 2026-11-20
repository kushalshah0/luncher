package com.luncher.launcher.home

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VpnMenuTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `turning the VPN off asks WireGuard to take the tunnel down`() {
        shadowOf(activity.application).grantPermissions(VpnMenu.CONTROL_TUNNELS)

        VpnMenu(activity).turnOff()

        val sent = shadowOf(activity.application).broadcastIntents.last()
        assertEquals(VpnMenu.SET_TUNNEL_DOWN, sent.action)
        assertEquals(VpnMenu.WIREGUARD, sent.`package`)
        assertEquals(VpnMenu.TUNNEL, sent.getStringExtra("tunnel"))
    }

    @Test
    fun `asks for WireGuard's permission first, and sends nothing yet`() {
        shadowOf(activity.application).denyPermissions(VpnMenu.CONTROL_TUNNELS)

        VpnMenu(activity).turnOff()

        val request = shadowOf(activity).lastRequestedPermission
        assertEquals(VpnMenu.REQUEST_CONTROL_TUNNELS, request.requestCode)
        assertEquals(listOf(VpnMenu.CONTROL_TUNNELS), request.requestedPermissions.toList())
        assertTrue(shadowOf(activity.application).broadcastIntents.none { it.action == VpnMenu.SET_TUNNEL_DOWN })
    }
}
