package com.luncher.launcher.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import java.net.Inet4Address

/**
 * The VPN pill's dropdown (NETTV fork), a [PillMenu]: a switch with the tunnel and its address,
 * then opening WireGuard, and Android's VPN settings (Always-on). The switch turns it off: asks WireGuard
 * (its SET_TUNNEL_DOWN broadcast), which needs the CONTROL_TUNNELS permission, asked for the first
 * time, and "Allow remote control apps" on in WireGuard's settings, else WireGuard ignores it.
 */
internal class VpnMenu(private val activity: Activity) {

    private val connectivity = activity.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val menu = PillMenu(activity)
    private val handler = Handler(Looper.getMainLooper())

    fun show(anchor: View) {
        val address = vpnNetwork()?.let(::address)
        val wireGuard = menu.row(R.drawable.home_ic_vpn, "Open WireGuard", null) { openWireGuard() }
        menu.show(anchor, listOf(
            menu.header("VPN", listOfNotNull(TUNNEL, address).joinToString("  ·  "), on = true) { turnOff() },
            menu.divider(),
            wireGuard,
            menu.row(R.drawable.home_ic_settings, "VPN settings", null) {
                // Settings.ACTION_VPN_SETTINGS, API 24; the action itself is older.
                start(Intent("android.net.vpn.SETTINGS"), "No VPN settings on this device")
            },
        ), focus = wireGuard)   // not the switch: turning the VPN off takes Up first
    }

    /** After the CONTROL_TUNNELS permission was granted. */
    fun turnOff() {
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(CONTROL_TUNNELS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(CONTROL_TUNNELS), REQUEST_CONTROL_TUNNELS)
            return
        }
        activity.sendBroadcast(Intent(SET_TUNNEL_DOWN).setPackage(WIREGUARD).putExtra("tunnel", TUNNEL))
        toast("Turning VPN off…")
        // WireGuard ignores the broadcast, silently, unless remote control is allowed: say so.
        handler.postDelayed({
            if (vpnNetwork() != null) toast("Still on: allow remote control apps in WireGuard's settings")
        }, CHECK_AFTER_MS)
    }

    private fun openWireGuard() {
        val launch = activity.packageManager.getLeanbackLaunchIntentForPackage(WIREGUARD)
            ?: activity.packageManager.getLaunchIntentForPackage(WIREGUARD)
        if (launch == null) toast("WireGuard isn't installed") else start(launch, "Couldn't open WireGuard")
    }

    private fun start(intent: Intent, failure: String) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            toast(failure)
        }
    }

    @Suppress("DEPRECATION") // allNetworks (API 31), as StatusIndicators reads it.
    private fun vpnNetwork(): Network? = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** The tunnel's own address, e.g. 10.8.0.3. */
    private fun address(network: Network): String? =
        connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    companion object {
        const val REQUEST_CONTROL_TUNNELS = 43
        const val WIREGUARD = "com.wireguard.android"
        const val CONTROL_TUNNELS = "com.wireguard.android.permission.CONTROL_TUNNELS"
        const val SET_TUNNEL_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"
        /** shortcut: the box's one WireGuard tunnel, by name (WireGuard can't list them to other apps); a setting if it ever has more. */
        const val TUNNEL = "tv"
        const val CHECK_AFTER_MS = 3_000L
    }
}
