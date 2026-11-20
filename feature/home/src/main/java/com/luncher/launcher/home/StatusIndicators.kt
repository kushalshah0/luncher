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
import android.widget.TextView
import java.util.Locale

/**
 * The home screen's status row, beside the settings entry: the network (WiFi with its signal, or
 * Ethernet), the download speed, the free memory, and "USB" while a USB drive is mounted. Read
 * every [INTERVAL_MS] while the home screen is visible: the screen calls [start] and [stop] with
 * the clock's, so a hidden home screen does no work.
 */
internal class StatusIndicators(private val view: TextView) {

    private val context: Context = view.context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val activities = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memory = ActivityManager.MemoryInfo()

    // The last reading of the received bytes, for the speed; -1 until the first.
    private var lastRx = -1L
    private var lastRxAt = 0L

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
        view.text = listOfNotNull(network(), speed(), freeMemory(), usb()).joinToString(SEPARATOR)
    }

    @Suppress("DEPRECATION") // NetworkInfo and WifiInfo: the only way that also works on API 22.
    private fun network(): String {
        val info = connectivity.activeNetworkInfo
        if (info == null || !info.isConnected) return "Offline"
        return when (info.type) {
            ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
            ConnectivityManager.TYPE_WIFI -> "WiFi ${WifiManager.calculateSignalLevel(wifi.connectionInfo.rssi, 101)}%"
            else -> "Online"
        }
    }

    /** Download speed since the last reading; null on the first one, or where the device doesn't count. */
    private fun speed(): String? {
        val rx = TrafficStats.getTotalRxBytes()
        val now = SystemClock.elapsedRealtime()
        val previous = lastRx
        val since = now - lastRxAt
        lastRx = rx
        lastRxAt = now
        if (rx == TrafficStats.UNSUPPORTED.toLong() || previous < 0 || since <= 0) return null
        val perSecond = (rx - previous).coerceAtLeast(0) * 1000 / since
        return "↓ " + when {
            perSecond >= MB -> String.format(Locale.US, "%.1f MB/s", perSecond.toDouble() / MB)
            else -> "${perSecond / KB} KB/s"
        }
    }

    private fun freeMemory(): String {
        activities.getMemoryInfo(memory)
        return "RAM ${memory.availMem / MB} MB free"
    }

    /** "USB" while a removable drive is mounted; StorageVolume lists them from API 24 only. */
    private fun usb(): String? {
        if (Build.VERSION.SDK_INT < 24) return null
        val storage = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val mounted = storage.storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        return if (mounted) "USB" else null
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
        const val KB = 1024L
        const val MB = 1024L * 1024L
        const val SEPARATOR = "   ·   "
    }
}
