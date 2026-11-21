package com.luncher.domain.appearance

/**
 * How the home screen's top bar is arranged: changing it moves the bar's items, or shows or hides
 * them, not the tiles. [com.luncher.domain.layout.topBarLayout] decides from it where each item goes.
 */
data class TopBarAppearance(
    /** The side of the bar the time and the date are on; the settings entry is on the other. */
    val clockSide: Side = Side.LEFT,
    /** The side of the date the time is on. */
    val timeSide: Side = Side.LEFT,
    /** Whether the bar shows the time. */
    val showTime: Boolean = true,
    /** Whether the bar shows the date. */
    val showDate: Boolean = true,
    /** Whether the bar shows the network pill (NETTV fork); it opens the Wi-Fi menu. */
    val showNetwork: Boolean = true,
    /** Whether the bar shows the free-memory pill (NETTV fork). */
    val showMemory: Boolean = true,
    /** Whether the bar shows the Bluetooth pill (NETTV fork); it opens the Bluetooth menu. */
    val showBluetooth: Boolean = true,
    /** Whether the bar shows the USB pill (NETTV fork), while a drive is mounted. */
    val showUsb: Boolean = true,
    /** Whether the bar shows the VPN pill (NETTV fork), while a VPN is connected. */
    val showVpn: Boolean = true,
)
