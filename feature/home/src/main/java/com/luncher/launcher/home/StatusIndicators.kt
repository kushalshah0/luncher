package com.luncher.launcher.home

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.widget.Toast
import android.view.View
import android.widget.TextView
import java.io.File

/**
 * The home screen's status pills (NETTV fork), beside the settings entry: the network (Wi-Fi,
 * Ethernet, or offline), the free memory, USB while a USB drive is mounted, a key while a VPN
 * is connected, and Bluetooth while a Bluetooth device is. Read every [INTERVAL_MS] while the home screen is visible: the
 * screen calls [start] and [stop] with the clock's, so a hidden home screen does no work.
 *
 * [openStorage] (OK on the USB pill) opens the TV's storage settings.
 *
 * The remote's Apps button clears the apps too, through the box's script: the RAM pill shows what
 * it freed ([APPS_CLEARED]).
 *
 * [cleanMemory] (OK on the RAM pill) stops other apps' background processes, asks the box's root
 * script to clear the recent apps too ([CLEAR_REQUEST]), and shows how much memory it freed.
 */
internal class StatusIndicators(row: View) {

    val network: TextView = row.findViewById(R.id.home_status_network)
    val ram: TextView = row.findViewById(R.id.home_status_ram)
    val usb: TextView = row.findViewById(R.id.home_status_usb)
    val vpn: TextView = row.findViewById(R.id.home_status_vpn)
    val bluetooth: TextView = row.findViewById(R.id.home_status_bluetooth)

    private val context: Context = row.context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val activities = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memory = ActivityManager.MemoryInfo()

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
        tick.run()
    }

    fun stop() = handler.removeCallbacks(tick)

    private fun show() {
        showNetwork()
        showAppsCleared()
        if (SystemClock.elapsedRealtime() >= ramMessageUntil) ram.text = "${freeMb()} MB"
        usb.visibility = if (usbMounted()) View.VISIBLE else View.GONE
        vpn.visibility = if (vpnConnected()) View.VISIBLE else View.GONE
        bluetooth.visibility = if (bluetoothConnected()) View.VISIBLE else View.GONE
    }

    /**
     * The kernel's MemAvailable: some boxes' firmware inflates what ActivityManager reports (a 1 GB
     * Q96+ claims 4 GB, and over 2 GB free), while /proc/meminfo's MemAvailable stays real.
     * ActivityManager's figure only where that line can't be read.
     */
    private fun freeMb(): Long {
        runCatching { memAvailableMb(File(MEMINFO).readText()) }.getOrNull()?.let { return it }
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

    /**
     * Whether a VPN is connected, e.g. WireGuard's tunnel: one of the networks is a VPN. Not
     * whether its server answers: WireGuard's tunnel is up from the moment it's switched on.
     */
    @Suppress("DEPRECATION") // allNetworks (API 31): a NetworkCallback would do, but this is read every tick anyway.
    private fun vpnConnected(): Boolean = connectivity.allNetworks.any {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /**
     * Whether a Bluetooth device is connected: headphones or a speaker (A2DP), a headset, or a
     * keyboard, remote or gamepad (HID host). From API 31 only with BLUETOOTH_CONNECT granted.
     */
    private fun bluetoothConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        return runCatching {
            adapter.isEnabled && BT_PROFILES.any { adapter.getProfileConnectionState(it) == BluetoothAdapter.STATE_CONNECTED }
        }.getOrDefault(false)
    }

    /** Whether a removable drive is mounted; StorageVolume lists them from API 24 only. */
    private fun usbMounted(): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val storage = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return storage.storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
    }

    /**
     * The TV's storage settings (Storage & reset), by package: some firmwares keep a phone-style
     * Settings app that answers the same action, and crashes (SettingsActivity in :feature:settings).
     */
    fun openStorage() {
        val storage = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(Intent(storage).setPackage(TV_SETTINGS), storage)) {
            try {
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // The next one.
            }
        }
        Toast.makeText(context, "No storage settings on this device", Toast.LENGTH_SHORT).show()
    }

    /**
     * Frees memory: stops every other app's background processes (Android lets a launcher do that),
     * and leaves [CLEAR_REQUEST] for the box's root script, which also clears the recent apps.
     * The pill pulses with "Cleaning…", then shows what was freed.
     */
    fun cleanMemory() {
        val before = freeMb()
        showCleaning(CLEAN_WAIT_MS)
        Thread {
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName && it.flags and ApplicationInfo.FLAG_PERSISTENT == 0 }
                .forEach { runCatching { activities.killBackgroundProcesses(it.packageName) } }
            runCatching { File(context.getExternalFilesDir(null), CLEAR_REQUEST).createNewFile() }
        }.start()
        handler.postDelayed({ showFreed((freeMb() - before).coerceAtLeast(0)) }, CLEAN_WAIT_MS)
    }

    /**
     * The remote's Apps button cleared the apps: the box's root script leaves how many MB it freed
     * in [APPS_CLEARED], and the pill shows it as after its own clean-up, the next time the home
     * screen is visible. Not if it's older than [APPS_CLEARED_STALE_MS]: that was a while ago.
     */
    private fun showAppsCleared() {
        val report = File(context.getExternalFilesDir(null) ?: return, APPS_CLEARED)
        if (!report.exists()) return
        val freed = runCatching { report.readText().trim().toLong() }.getOrNull()
        val fresh = System.currentTimeMillis() - report.lastModified() < APPS_CLEARED_STALE_MS
        report.delete()
        if (freed == null || !fresh) return
        showCleaning(REPORT_WAIT_MS)
        handler.postDelayed({ showFreed(freed) }, REPORT_WAIT_MS)
    }

    /** "Cleaning…" with a pulse, for [waitMs]; the pill shows no free memory till the message after it ends. */
    private fun showCleaning(waitMs: Long) {
        ramMessageUntil = SystemClock.elapsedRealtime() + waitMs + CLEAN_MESSAGE_MS
        ram.text = "Cleaning…"
        ram.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).withEndAction {
            ram.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
        }.start()
    }

    private fun showFreed(freed: Long) {
        ram.text = "+$freed MB freed"
        ram.animate().alpha(0.4f).setDuration(120).withEndAction {
            ram.animate().alpha(1f).setDuration(200).start()
        }.start()
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
        const val MB = 1024L * 1024L
        /** The root script clears within a second of seeing the request; the result is read after. */
        const val CLEAN_WAIT_MS = 2_500L
        const val CLEAN_MESSAGE_MS = 3_000L
        /** File in the app's external files folder that the box's remote-keys.sh script watches. */
        const val CLEAR_REQUEST = "clear-request"
        /** File the box's root script leaves after the remote's Apps button: the MB it freed. */
        const val APPS_CLEARED = "apps-cleared"
        const val APPS_CLEARED_STALE_MS = 60_000L
        /** How long "Cleaning…" shows before the Apps button's result: it's done already. */
        const val REPORT_WAIT_MS = 800L
        const val TV_SETTINGS = "com.android.tv.settings"
        const val MEMINFO = "/proc/meminfo"
        /** BluetoothProfile.HID_HOST is 4, but public only from API 33. */
        const val HID_HOST = 4
        val BT_PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, HID_HOST)
    }
}

/** MemAvailable from /proc/meminfo's [text], in MB; null when the line is missing (kernels before 3.14). */
internal fun memAvailableMb(text: String): Long? = text.lineSequence()
    .firstOrNull { it.startsWith("MemAvailable:") }
    ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
    ?.let { it / 1024 }
