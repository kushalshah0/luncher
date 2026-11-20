package com.luncher.launcher.home

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
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
import java.io.File
import java.util.Locale

/**
 * The home screen's status pills (NETTV fork), beside the settings entry: the network (Wi-Fi,
 * Ethernet, or offline), the download speed, the free memory, and USB
 * while a USB drive is mounted. Read every [INTERVAL_MS] while the home screen is visible: the
 * screen calls [start] and [stop] with the clock's, so a hidden home screen does no work.
 *
 * [cleanMemory] (OK on the RAM pill) stops other apps' background processes, asks the box's root
 * script to clear the recent apps too ([CLEAR_REQUEST]), and shows how much memory it freed.
 */
internal class StatusIndicators(row: View) {

    val network: TextView = row.findViewById(R.id.home_status_network)
    private val speed: TextView = row.findViewById(R.id.home_status_speed)
    val ram: TextView = row.findViewById(R.id.home_status_ram)
    private val usb: TextView = row.findViewById(R.id.home_status_usb)

    private val context: Context = row.context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val activities = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memory = ActivityManager.MemoryInfo()

    // The last reading of the received and sent bytes, for the speeds; -1 until the first.
    private var lastRx = -1L
    private var lastAt = 0L
    private var networkIcon = 0

    /** While the clean-up's message shows (elapsedRealtime), the RAM pill isn't updated. */
    private var ramMessageUntil = 0L

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
        if (SystemClock.elapsedRealtime() >= ramMessageUntil) ram.text = "${freeMb()} MB"
        usb.visibility = if (usbMounted()) View.VISIBLE else View.GONE
    }

    private fun freeMb(): Long {
        activities.getMemoryInfo(memory)
        return memory.availMem / MB
    }

    @Suppress("DEPRECATION") // NetworkInfo and WifiInfo: the only way that also works on API 22.
    private fun showNetwork() {
        val info = connectivity.activeNetworkInfo
        val (icon, text) = when {
            info == null || !info.isConnected -> R.drawable.home_ic_wifi_off to "Offline"
            info.type == ConnectivityManager.TYPE_ETHERNET -> R.drawable.home_ic_ethernet to "Ethernet"
            info.type == ConnectivityManager.TYPE_WIFI -> R.drawable.home_ic_wifi to "Wi-Fi"
            else -> R.drawable.home_ic_wifi to "Online"
        }
        if (icon != networkIcon) {   // only when it changes: setting a drawable lays the pill out again
            network.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
            networkIcon = icon
        }
        network.text = text
    }

    /** Download speed since the last reading ("1.2 MB/s"); zero on the first, or where the device doesn't count. */
    private fun speedText(): String {
        val rx = TrafficStats.getTotalRxBytes()
        val now = SystemClock.elapsedRealtime()
        val since = now - lastAt
        val down = if (lastRx < 0 || rx < 0 || since <= 0) 0 else (rx - lastRx).coerceAtLeast(0) * 1000 / since
        lastRx = rx
        lastAt = now
        return rate(down)
    }

    private fun rate(perSecond: Long): String =
        if (perSecond >= MB) String.format(Locale.US, "%.1f MB/s", perSecond.toDouble() / MB) else "${perSecond / KB} KB/s"

    /** Whether a removable drive is mounted; StorageVolume lists them from API 24 only. */
    private fun usbMounted(): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val storage = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return storage.storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
    }

    /**
     * Frees memory: stops every other app's background processes (Android lets a launcher do that),
     * and leaves [CLEAR_REQUEST] for the box's root script, which also clears the recent apps and
     * blinks the front LED. The pill pulses with "Cleaning…", then shows what was freed.
     */
    fun cleanMemory() {
        val before = freeMb()
        ramMessageUntil = SystemClock.elapsedRealtime() + CLEAN_MESSAGE_MS + CLEAN_WAIT_MS
        ram.text = "Cleaning…"
        ram.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).withEndAction {
            ram.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
        }.start()
        Thread {
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName && it.flags and ApplicationInfo.FLAG_PERSISTENT == 0 }
                .forEach { runCatching { activities.killBackgroundProcesses(it.packageName) } }
            runCatching { File(context.getExternalFilesDir(null), CLEAR_REQUEST).createNewFile() }
        }.start()
        handler.postDelayed({
            val freed = (freeMb() - before).coerceAtLeast(0)
            ram.text = "+$freed MB freed"
            ram.animate().alpha(0.4f).setDuration(120).withEndAction {
                ram.animate().alpha(1f).setDuration(200).start()
            }.start()
        }, CLEAN_WAIT_MS)
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
        const val KB = 1024L
        const val MB = 1024L * 1024L
        /** The root script clears within a second of seeing the request; the result is read after. */
        const val CLEAN_WAIT_MS = 2_500L
        const val CLEAN_MESSAGE_MS = 3_000L
        /** File in the app's external files folder that the box's remote-keys.sh script watches. */
        const val CLEAR_REQUEST = "clear-request"
    }
}
