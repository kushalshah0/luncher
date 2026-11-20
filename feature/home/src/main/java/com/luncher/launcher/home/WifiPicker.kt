package com.luncher.launcher.home

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.luncher.launcher.ui.R as UiR

/**
 * The Wi-Fi dropdown (NETTV fork): a [PillMenu] below the network pill, listing
 * a switch for Wi-Fi itself, then the networks around, the connected one first and marked
 * "Connected". OK on a network connects to it, asking for the password of a secured network that
 * isn't saved yet; the last rows add a network by name (a page of the menu, e.g. for a hidden
 * one) and open Android's Wi-Fi settings. Uses the
 * pre-Android 10 WifiManager calls, which still work on the Android 7 box this fork is for.
 */
@Suppress("DEPRECATION")
internal class WifiPicker(private val activity: Activity) {

    private val wifi = activity.applicationContext.getSystemService(Activity.WIFI_SERVICE) as WifiManager
    private val menu = PillMenu(activity)
    private var anchor: View? = null
    private var page = Page.LIST

    private enum class Page { LIST, ADD }

    fun show(anchor: View) {
        this.anchor = anchor
        // Scan results need the location permission from API 23: ask, and open again after (showAgain).
        if (Build.VERSION.SDK_INT >= 23 &&
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        wifi.startScan()   // for the next time; the list shows the last scan's results
        showList(anchor)
    }

    /** The menu's main page: the switch, the networks, Add network and Wi-Fi settings. */
    private fun showList(anchor: View) {
        val list = mutableListOf<View>()
        val on = wifi.isWifiEnabled
        val current = wifi.connectionInfo?.ssid.orEmpty().removeSurrounding("\"").takeUnless { it == UNKNOWN_SSID }.orEmpty()
        val header = menu.header("Wi-Fi", status = null, on = on) {
            wifi.isWifiEnabled = !on
            toast(if (on) "Turning Wi-Fi off…" else "Turning Wi-Fi on…")
        }
        list.add(header)
        var focus = header
        if (!on) {
            list.add(menu.divider())
            list.add(menu.note("Turn on Wi-Fi to see networks"))
        } else {
            val networks = wifi.scanResults.orEmpty()
                .filter { it.SSID.isNotBlank() }
                .groupBy { it.SSID }.map { (_, same) -> same.maxByOrNull { it.level }!! }
                .sortedWith(compareByDescending<ScanResult> { it.SSID == current }.thenByDescending { it.level })
                .take(MAX_NETWORKS)
            val rows = networks.map { network ->
                val connected = network.SSID == current
                val status = when {
                    connected -> "Connected"
                    secured(network) -> "Secured"
                    else -> "Open"
                }
                menu.row(R.drawable.home_ic_wifi, network.SSID, status, statusGreen = connected) { connect(network) }
            }.ifEmpty { listOf(menu.note("No networks found")) }
            list.addAll(rows)
            // The first network, so turning Wi-Fi off takes Up first.
            focus = rows.first()
        }
        list.add(menu.divider())
        val addNetwork = menu.row(UiR.drawable.icon_plus, "Add network", null, closes = false) { showAddNetwork(anchor) }
        if (on) list.add(addNetwork)
        if (!focus.isFocusable) focus = addNetwork   // no networks found: a note
        list.add(menu.row(R.drawable.home_ic_settings, "Wi-Fi settings", null) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        })
        page = Page.LIST
        menu.show(anchor, list, focus)
    }

    /**
     * Add network, a page of the menu: a network by its name, e.g. a hidden one: open without a
     * password, else WPA/WPA2 (8 to 63 characters). WEP and enterprise networks are Wi-Fi
     * settings' to add. The back row, and Back, return to the list.
     */
    private fun showAddNetwork(anchor: View) {
        val name = menu.field("e.g. Home Wi-Fi")
        val (password, passwordRow) = menu.passwordField("None if open")
        val back = { showList(anchor) }
        page = Page.ADD
        menu.show(anchor, listOf(
            menu.backRow("Add network", back),
            menu.divider(),
            menu.caption("Network name"),
            name,
            menu.caption("Password"),
            passwordRow,
            menu.button("Connect") {
                val ssid = name.text.toString().trim()
                val key = password.text.toString()
                when {
                    ssid.isEmpty() -> toast("Enter the network's name")
                    key.isNotEmpty() && key.length !in 8..63 -> toast("A WPA password has 8 to 63 characters")
                    else -> {
                        menu.close()
                        add(WifiConfiguration().apply {
                            SSID = "\"$ssid\""
                            hiddenSSID = true   // connects whether or not the name is broadcast
                            if (key.isEmpty()) {
                                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                            } else {
                                preSharedKey = "\"$key\""
                            }
                        }, ssid)
                    }
                }
            },
        ), focus = name, onBack = back)
    }

    /** After the location permission was granted. */
    fun showAgain() { anchor?.let(::show) }

    private fun secured(network: ScanResult) = listOf("WEP", "PSK", "EAP", "SAE").any { it in network.capabilities }

    private fun connect(network: ScanResult) {
        val quoted = "\"${network.SSID}\""
        val saved = wifi.configuredNetworks.orEmpty().firstOrNull { it.SSID == quoted }
        when {
            saved != null -> enable(saved.networkId, network.SSID)
            !secured(network) -> add(WifiConfiguration().apply {
                SSID = quoted
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }, network.SSID)
            else -> askPassword(network)
        }
    }

    private fun askPassword(network: ScanResult) {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
        }
        AlertDialog.Builder(activity, DIALOG_THEME)
            .setTitle(network.SSID).setView(field)
            .setPositiveButton("Connect") { _, _ ->
                val password = field.text.toString()
                val config = WifiConfiguration().apply { SSID = "\"${network.SSID}\"" }
                if ("WEP" in network.capabilities) {
                    config.wepKeys[0] = "\"$password\""
                    config.wepTxKeyIndex = 0
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    config.preSharedKey = "\"$password\""
                }
                add(config, network.SSID)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun add(config: WifiConfiguration, name: String) {
        val id = wifi.addNetwork(config)
        if (id == -1) toast("Couldn't add $name") else { wifi.saveConfiguration(); enable(id, name) }
    }

    private fun enable(id: Int, name: String) {
        wifi.disconnect()
        if (wifi.enableNetwork(id, true)) { wifi.reconnect(); toast("Connecting to $name…") } else toast("Couldn't connect to $name")
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQUEST_LOCATION = 42
        const val MAX_NETWORKS = 8
        /** What WifiInfo says while connected to no network. */
        const val UNKNOWN_SSID = "<unknown ssid>"
        /** Light, as the menus. */
        const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    }
}
