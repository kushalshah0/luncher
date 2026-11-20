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
import android.widget.EditText
import android.widget.Toast

/**
 * A small Wi-Fi picker over the home screen (NETTV fork), opened from the network pill: the networks
 * around, strongest first, the connected one marked; OK on one connects to it, asking for the
 * password of a secured network that isn't saved yet. Uses the pre-Android 10 WifiManager calls,
 * which still work on the Android 7 box this fork is for.
 */
@Suppress("DEPRECATION")
internal class WifiPicker(private val activity: Activity) {

    private val wifi = activity.applicationContext.getSystemService(Activity.WIFI_SERVICE) as WifiManager

    fun show() {
        if (!wifi.isWifiEnabled) {
            dialog().setMessage("Wi-Fi is off.")
                .setPositiveButton("Turn on") { _, _ -> wifi.isWifiEnabled = true; toast("Turning Wi-Fi on…") }
                .setNegativeButton("Close", null).show()
            return
        }
        // Scan results need the location permission from API 23: ask, and open the picker again after.
        if (Build.VERSION.SDK_INT >= 23 &&
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        wifi.startScan()   // for the next time; the list shows the last scan's results
        val current = wifi.connectionInfo?.ssid.orEmpty().removeSurrounding("\"")
        val networks = wifi.scanResults.orEmpty()
            .filter { it.SSID.isNotBlank() }
            .groupBy { it.SSID }.map { (_, same) -> same.maxByOrNull { it.level }!! }
            .sortedWith(compareByDescending<ScanResult> { it.SSID == current }.thenByDescending { it.level })
        if (networks.isEmpty()) {
            dialog().setMessage("No networks found yet. Try again in a few seconds.")
                .setPositiveButton("Refresh") { _, _ -> show() }
                .setNegativeButton("Close", null).show()
            return
        }
        val labels = networks.map { network ->
            val bars = WifiManager.calculateSignalLevel(network.level, 5)
            val signal = "▮".repeat(bars) + "▯".repeat(4 - bars)
            val lock = if (secured(network)) "  🔒" else ""
            val connected = if (network.SSID == current) "  ✓ Connected" else ""
            "${network.SSID}   $signal$lock$connected"
        }.toTypedArray()
        dialog()
            .setItems(labels) { _, which -> connect(networks[which]) }
            .setPositiveButton("Refresh") { _, _ -> show() }
            .setNeutralButton("Wi-Fi settings") { _, _ ->
                runCatching { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun dialog() = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Wi-Fi")

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
        dialog().setTitle(network.SSID).setView(field)
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
    }
}
