package com.luncher.launcher.home

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The Wi-Fi dropdown (NETTV fork): a dark rounded panel attached below the network pill, listing
 * the networks around, the connected one first and marked "Connected"; the focused row is white
 * with black text. OK on a network connects to it, asking for the password of a secured network
 * that isn't saved yet; the last rows rescan or open Android's Wi-Fi settings. Uses the
 * pre-Android 10 WifiManager calls, which still work on the Android 7 box this fork is for.
 */
@Suppress("DEPRECATION")
internal class WifiPicker(private val activity: Activity) {

    private val wifi = activity.applicationContext.getSystemService(Activity.WIFI_SERVICE) as WifiManager
    private val density = activity.resources.displayMetrics.density
    private val text = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE))
    private var popup: PopupWindow? = null
    private var anchor: View? = null

    fun show(anchor: View) {
        this.anchor = anchor
        // Scan results need the location permission from API 23: ask, and open again after (showAgain).
        if (Build.VERSION.SDK_INT >= 23 &&
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        popup?.dismiss()
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (!wifi.isWifiEnabled) {
            list.addView(row(R.drawable.home_ic_wifi_off, "Turn Wi-Fi on", null) { wifi.isWifiEnabled = true; toast("Turning Wi-Fi on…") })
        } else {
            wifi.startScan()   // for the next time; the list shows the last scan's results
            val current = wifi.connectionInfo?.ssid.orEmpty().removeSurrounding("\"")
            val networks = wifi.scanResults.orEmpty()
                .filter { it.SSID.isNotBlank() }
                .groupBy { it.SSID }.map { (_, same) -> same.maxByOrNull { it.level }!! }
                .sortedWith(compareByDescending<ScanResult> { it.SSID == current }.thenByDescending { it.level })
                .take(MAX_NETWORKS)
            if (networks.isEmpty()) list.addView(row(R.drawable.home_ic_wifi, "Searching…", null) { show(anchor) })
            for (network in networks) {
                val connected = network.SSID == current
                val status = when {
                    connected -> "●  Connected"
                    secured(network) -> "Secured"
                    else -> "Open"
                }
                list.addView(row(R.drawable.home_ic_wifi, network.SSID, status, statusGreen = connected) { connect(network) })
            }
        }
        list.addView(divider())
        list.addView(row(R.drawable.home_ic_updown, "Refresh", null) { show(anchor) })
        list.addView(row(R.drawable.home_ic_settings, "Wi-Fi settings", null) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        })
        val panel = ScrollView(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xF2202124.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        popup = PopupWindow(panel, dp(PANEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))   // so Back and a touch outside close it
            elevation = dp(8).toFloat()
            showAsDropDown(anchor, 0, dp(10), Gravity.START)
        }
        list.getChildAt(0)?.requestFocus()
    }

    /** After the location permission was granted. */
    fun showAgain() { anchor?.let(::show) }

    private fun row(icon: Int, title: String, status: String?, statusGreen: Boolean = false, onClick: () -> Unit) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) })
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnClickListener { popup?.dismiss(); onClick() }
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = text
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(TextView(activity).apply {
                this.text = title
                setTextColor(this@WifiPicker.text)
                textSize = 17f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                isDuplicateParentStateEnabled = true
                setPadding(dp(16), 0, dp(12), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (status != null) addView(TextView(activity).apply {
                this.text = status
                textSize = 13f
                setTextColor(if (statusGreen) 0xFF34C759.toInt() else 0x99FFFFFF.toInt())
            })
        }

    private fun divider() = View(activity).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(dp(16), dp(8), dp(16), dp(8))
        }
    }

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
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        const val REQUEST_LOCATION = 42
        const val PANEL_WIDTH_DP = 360
        const val MAX_NETWORKS = 8
    }
}
