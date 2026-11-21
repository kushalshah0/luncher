package com.luncher.launcher.home

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * Android's Bluetooth profiles (NETTV fork), for the Bluetooth menu ([BluetoothMenu]): the
 * headphones' (A2DP, headset) and the input devices' (HID host), asked for once and kept for the
 * process. Connecting and disconnecting a device are hidden methods of the profiles before API 33,
 * open to an app with BLUETOOTH_ADMIN on the Android 9 box this fork is for: called by reflection,
 * and false wherever they're refused.
 */
@SuppressLint("MissingPermission") // the callers check BLUETOOTH_CONNECT from API 31
internal object BluetoothProfiles {

    /** BluetoothProfile.HID_HOST, public from API 33 only. */
    const val HID_HOST = 4

    private val proxies = mutableMapOf<Int, BluetoothProfile>()
    private var asked = false

    /** Called when a profile becomes available, e.g. to show the menu again with the devices' states. */
    var onChange: (() -> Unit)? = null

    fun adapter(context: Context): BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Asks for the profiles, once; Android binds them again by itself after Bluetooth is turned on. */
    fun ensure(context: Context) {
        if (asked) return
        val adapter = adapter(context) ?: return
        asked = true
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, HID_HOST)) {
            runCatching {
                adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(which: Int, proxy: BluetoothProfile) {
                        proxies[which] = proxy
                        onChange?.invoke()
                    }

                    override fun onServiceDisconnected(which: Int) {
                        proxies.remove(which)
                    }
                }, profile)
            }
        }
    }

    /** Headphones, a speaker or a headset, by the class the device announces. */
    fun isAudio(device: BluetoothDevice): Boolean =
        device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO

    fun isConnected(device: BluetoothDevice): Boolean = proxies.values.any {
        runCatching { it.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
    }

    /** Whether headphones or a speaker are connected. */
    fun audioConnected(): Boolean =
        runCatching { proxies[BluetoothProfile.A2DP]?.connectedDevices?.isNotEmpty() == true }.getOrDefault(false)

    /** Connects [device] by its profiles: an audio device's, else an input device's. True if one took the request. */
    fun connect(device: BluetoothDevice): Boolean = call("connect", device, profilesFor(device))

    /** Disconnects [device] from every profile it's connected by. */
    fun disconnect(device: BluetoothDevice): Boolean = call("disconnect", device, proxies.keys.toList())

    private fun profilesFor(device: BluetoothDevice): List<Int> =
        if (isAudio(device)) listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET) else listOf(HID_HOST)

    private fun call(method: String, device: BluetoothDevice, profiles: List<Int>): Boolean =
        profiles.mapNotNull { proxies[it] }.map { proxy ->
            runCatching { proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, device) == true }
                .getOrDefault(false)
        }.any { it }
}
