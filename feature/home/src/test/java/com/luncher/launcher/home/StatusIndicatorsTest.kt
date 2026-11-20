package com.luncher.launcher.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
class StatusIndicatorsTest {

    private val context = RuntimeEnvironment.getApplication() as Context
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The pills' row, as the home screen's layout has it. */
    private val row = LinearLayout(context).apply {
        for (id in listOf(R.id.home_status_network, R.id.home_status_ram, R.id.home_status_usb, R.id.home_status_vpn)) {
            addView(TextView(context).also { it.id = id; it.visibility = View.GONE })
        }
    }

    private fun vpnPill(): Int {
        StatusIndicators(row).apply { start(); stop() }
        return row.findViewById<TextView>(R.id.home_status_vpn).visibility
    }

    @Test
    fun `no VPN pill without a VPN`() {
        assertEquals(View.GONE, vpnPill())
    }

    @Test
    fun `the VPN pill shows while a VPN is connected`() {
        @Suppress("DEPRECATION")
        val info = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_VPN, 0, true, NetworkInfo.State.CONNECTED,
        )
        val network = ShadowNetwork.newInstance(101)
        shadowOf(connectivity).addNetwork(network, info)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)

        assertEquals(View.VISIBLE, vpnPill())
    }
}
