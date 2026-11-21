package com.luncher.launcher.home

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Toast
import com.luncher.launcher.ui.R as UiR

/**
 * The Bluetooth pill's dropdown (NETTV fork), a [PillMenu] as the Wi-Fi one: a switch for
 * Bluetooth with what's connected; the paired devices, the connected first, OK connecting or
 * disconnecting one; then the devices around that aren't paired, found by a search started as the
 * menu opens, OK pairing one (the TV asks to confirm) and connecting it once paired; then Android's
 * Bluetooth settings. While it's open it follows Bluetooth: turned on or off, a device found,
 * paired, connected or gone, it shows again, the focused row keeping its focus.
 */
@SuppressLint("MissingPermission") // the permissions asked for from API 31 (show)
internal class BluetoothMenu(private val activity: Activity) {

    private val app: Context = activity.applicationContext
    private val menu = PillMenu(activity)
    private var anchor: View? = null

    /** Devices the search found that aren't paired, by address, in the order found. */
    private val found = linkedMapOf<String, BluetoothDevice>()

    /** A device being paired from this menu: connected once it's paired. */
    private var pairing: String? = null

    /** The rows last shown, by a key (a device's address, or a fixed one): the focused one keeps its focus. */
    private var rows: Map<String, View> = emptyMap()
    private var focusKey: String? = null

    private var listening = false

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val anchor = anchor
            if (!menu.isShowing || anchor == null) {
                BluetoothProfiles.adapter(app)?.takeIf { it.isDiscovering }?.cancelDiscovery()
                return stopListening()
            }
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> { search(); showList(anchor, focus = HEADER) }
                    BluetoothAdapter.STATE_OFF -> { found.clear(); showList(anchor, focus = HEADER) }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    if (device == null || device.bondState == BluetoothDevice.BOND_BONDED || device.name.isNullOrBlank()) return
                    if (found.put(device.address, device) == null) showList(anchor)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (device != null && device.bondState == BluetoothDevice.BOND_BONDED) {
                        found.remove(device.address)
                        if (device.address == pairing) { pairing = null; BluetoothProfiles.connect(device) }
                    }
                    showList(anchor)
                }
                else -> showList(anchor)   // a search started or ended, a device connected or gone
            }
        }
    }

    fun show(anchor: View) {
        this.anchor = anchor
        if (BluetoothProfiles.adapter(activity) == null) return toast("No Bluetooth on this device")
        // Connecting and searching need their permissions from API 31; before, the search needs the location one.
        val needed = when {
            Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            Build.VERSION.SDK_INT >= 23 -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> emptyList()
        }
        val missing = needed.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) return activity.requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
        BluetoothProfiles.ensure(activity)
        // The profiles answer a moment after they're first asked for: the devices' states with them.
        BluetoothProfiles.onChange = { if (menu.isShowing) this.anchor?.let { showList(it) } }
        focusKey = null
        rows = emptyMap()
        listen()
        search()
        showList(anchor)
    }

    /** After the Bluetooth permissions were granted. */
    fun showAgain() { anchor?.let(::show) }

    /** A new search for the devices around (about 12 s); what was found before stays listed till the list is drawn again. */
    private fun search() {
        val adapter = BluetoothProfiles.adapter(activity) ?: return
        if (!adapter.isEnabled) return
        found.clear()
        runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        }
    }

    /** The menu's one page; [focus]: the key of the row to focus, else the one focused before. */
    private fun showList(anchor: View, focus: String? = null) {
        val adapter = BluetoothProfiles.adapter(activity) ?: return
        focusKey = focus ?: rows.entries.firstOrNull { it.value.hasFocus() }?.key ?: focusKey
        val on = adapter.isEnabled
        val paired = if (on) adapter.bondedDevices.orEmpty().sortedWith(
            compareByDescending<BluetoothDevice> { BluetoothProfiles.isConnected(it) }.thenBy { name(it).lowercase() },
        ) else emptyList()
        val connected = paired.filter { BluetoothProfiles.isConnected(it) }
        val keyed = linkedMapOf<String, View>()
        val list = mutableListOf<View>()

        val header = menu.header(
            "Bluetooth",
            status = connected.firstOrNull()?.let { "Connected to ${name(it)}" },
            on = on,
            closes = false,
        ) {
            if (on) adapter.disable() else adapter.enable()
            toast(if (on) "Turning Bluetooth off…" else "Turning Bluetooth on…")
        }
        keyed[HEADER] = header
        list.add(header)
        if (!on) {
            list.add(menu.divider())
            list.add(menu.note("Turn on Bluetooth to see devices"))
        } else {
            for (device in paired) {
                val isConnected = device in connected
                val row = menu.row(
                    R.drawable.home_ic_bluetooth, name(device), if (isConnected) "Connected" else "Paired",
                    statusGreen = isConnected, closes = false,
                ) { toggle(device, isConnected) }
                keyed[device.address] = row
                list.add(row)
            }
            list.add(menu.divider())
            val searching = runCatching { adapter.isDiscovering }.getOrDefault(false)
            for (device in found.values) {
                val row = menu.row(
                    R.drawable.home_ic_bluetooth, name(device), if (device.address == pairing) "Pairing…" else "Available",
                    closes = false,
                ) { pair(device) }
                keyed[device.address] = row
                list.add(row)
            }
            if (searching) {
                list.add(menu.note(if (found.isEmpty()) "Searching for devices…" else "Searching…"))
            } else {
                if (found.isEmpty()) list.add(menu.note("No other devices found"))
                val again = menu.row(UiR.drawable.icon_plus, "Search again", null, closes = false) { search() }
                keyed[SEARCH] = again
                list.add(again)
            }
        }
        list.add(menu.divider())
        val settings = menu.row(R.drawable.home_ic_settings, "Bluetooth settings", null) {
            BluetoothProfiles.adapter(app)?.takeIf { it.isDiscovering }?.cancelDiscovery()
            start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        keyed[SETTINGS] = settings
        list.add(settings)

        rows = keyed
        // The row focused before, else the first device (turning Bluetooth off takes Up first), else the switch.
        val target = focusKey?.let(keyed::get)
            ?: keyed.entries.firstOrNull { it.key != HEADER && it.key != SEARCH && it.key != SETTINGS }?.value
            ?: header
        menu.show(anchor, list, target)
    }

    private fun toggle(device: BluetoothDevice, connected: Boolean) {
        BluetoothProfiles.adapter(app)?.takeIf { it.isDiscovering }?.cancelDiscovery()   // a search slows connecting
        when {
            connected -> if (BluetoothProfiles.disconnect(device)) toast("Disconnecting ${name(device)}…") else toast("Couldn't disconnect ${name(device)}")
            BluetoothProfiles.connect(device) -> toast("Connecting to ${name(device)}…")
            else -> toast("Couldn't connect to ${name(device)}")
        }
    }

    /** Pairs [device]: the TV shows its own confirmation; once paired, it's connected ([events]). */
    private fun pair(device: BluetoothDevice) {
        BluetoothProfiles.adapter(app)?.takeIf { it.isDiscovering }?.cancelDiscovery()
        if (device.createBond()) {
            pairing = device.address
            toast("Pairing with ${name(device)}…")
        } else {
            toast("Couldn't pair with ${name(device)}")
        }
    }

    private fun start(intent: Intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {   // not found, or not exported to other apps
            toast("No Bluetooth settings on this device")
        }
    }

    private fun listen() {
        if (listening) return
        listening = true
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(events, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(events, filter)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { app.unregisterReceiver(events) }
    }

    private fun name(device: BluetoothDevice): String = device.name?.takeIf { it.isNotBlank() } ?: device.address

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQUEST_BLUETOOTH = 44
        private const val HEADER = "#header"
        private const val SEARCH = "#search"
        private const val SETTINGS = "#settings"
    }
}
