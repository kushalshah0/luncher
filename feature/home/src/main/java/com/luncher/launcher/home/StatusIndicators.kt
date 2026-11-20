package com.luncher.launcher.home

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.view.View
import android.widget.TextView
import java.util.Locale

/**
 * The home screen's status pills (NETTV fork), beside the settings entry: the network (Wi-Fi with
 * its signal, Ethernet, or offline), the download speed, the free memory, and USB while a USB drive
 * is mounted. Read every [INTERVAL_MS] while the home screen is visible: the screen calls [start]
 * and [stop] with the clock's, so a hidden home screen does no work.
 */
internal class StatusIndicators(row: View) {

    private val network: TextView = row.findViewById(R.id.home_status_network)
    private val speed: TextView = row.findViewById(R.id.home_status_speed)
    private val ram: TextView = row.findViewById(R.id.home_status_ram)
    private val usb: TextView = row.findViewById(R.id.home_status_usb)

    private val context: Context = row.context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val activities = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memory = ActivityManager.MemoryInfo()

    // The last reading of the received bytes, for the speed; -1 until the first.
    private var lastRx = -1L
    private var lastRxAt = 0L
    private var networkIcon = 0

    private val tick = object : Runnable {
        override fun run() {
            show()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        stop()
        lastRx = -1L
        tick.run()
    }

    fun stop() = handler.removeCallbacks(tick)

    private fun show() {
        showNetwork()
        speed.text = speedText()
        activities.getMemoryInfo(memory)
        ram.text = "${memory.availMem / MB} MB"
        usb.visibility = if (usbMounted()) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION") // NetworkInfo and WifiInfo: the only way that also works on API 22.
    private fun showNetwork() {
        val info = connectivity.activeNetworkInfo
        val (icon, text) = when {
            info == null || !info.isConnected -> R.drawable.home_ic_wifi_off to "Offline"
            info.type == ConnectivityManager.TYPE_ETHERNET -> R.drawable.home_ic_ethernet to "Ethernet"
            info.type == ConnectivityManager.TYPE_WIFI ->
                R.drawable.home_ic_wifi to "Wi-Fi ${WifiManager.calculateSignalLevel(wifi.connectionInfo.rssi, 101)}%"
            else -> R.drawable.home_ic_wifi to "Online"
        }
        if (icon != networkIcon) {   // only when it changes: setting a drawable lays the pill out again
            network.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
            networkIcon = icon
        }
        network.text = text
    }

    /** Download speed since the last reading; "0 KB/s" on the first, or where the device doesn't count. */
    private fun speedText(): String {
        val rx = TrafficStats.getTotalRxBytes()
        val now = SystemClock.elapsedRealtime()
        val previous = lastRx
        val since = now - lastRxAt
        lastRx = rx
        lastRxAt = now
        if (rx == TrafficStats.UNSUPPORTED.toLong() || previous < 0 || since <= 0) return "0 KB/s"
        val perSecond = (rx - previous).coerceAtLeast(0) * 1000 / since
        return if (perSecond >= MB) String.format(Locale.US, "%.1f MB/s", perSecond.toDouble() / MB)
        else "${perSecond / KB} KB/s"
    }

    /** Whether a removable drive is mounted; StorageVolume lists them from API 24 only. */
    private fun usbMounted(): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val storage = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return storage.storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
        const val KB = 1024L
        const val MB = 1024L * 1024L
    }
}
