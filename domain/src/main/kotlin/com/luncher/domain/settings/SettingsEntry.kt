package com.luncher.domain.settings

/**
 * One entry of the settings panel. Each kind of entry is a type of its own, so :feature:settings
 * handles every one of them (its label, what OK on it does) in an exhaustive `when`: a new entry
 * that it doesn't handle yet doesn't compile.
 */
sealed interface SettingsEntry {

    /** Lists every app, to hide it from the home screen or show it again. */
    data object HideApps : SettingsEntry

    /** Opens the editor of the home screen's appearance. */
    data object Appearance : SettingsEntry

    /** Opens the TV's own settings app (com.android.tv.settings), else the device's settings. */
    data object SystemSettings : SettingsEntry

    /**
     * Opens Android's phone-style settings app (com.android.settings), which some TV firmwares
     * keep beside the TV's: it has screens the TV's lacks, such as VPN.
     */
    data object AndroidSettings : SettingsEntry

    /** Restarts the box (NETTV fork): through its root script, after a second OK. */
    data object Restart : SettingsEntry
}
