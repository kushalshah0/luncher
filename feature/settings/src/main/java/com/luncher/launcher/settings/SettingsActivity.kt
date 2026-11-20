package com.luncher.launcher.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.luncher.domain.settings.SettingsEntry
import com.luncher.domain.settings.settingsMenu
import com.luncher.launcher.ui.stylePanel
import com.luncher.launcher.ui.R as UiR

/**
 * The settings panel: a side sheet over the home screen ([placeAsSheet]) that lists the entries of
 * [settingsMenu], each with its icon, its name, and where it goes. Back closes it, as any
 * activity, and the home screen's focus is where it was.
 *
 * What an entry shows and does is decided here, per kind of entry, in [label], [icon] and [open];
 * which entries exist and in which order is the domain's decision.
 */
class SettingsActivity : Activity() {

    private lateinit var panel: View
    private lateinit var entries: LinearLayout
    private val restart by lazy { BoxRestart(this) }
    private val theme by lazy { ScreenTheme(graph.appearanceSettings) { panel.stylePanel(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        panel = findViewById(R.id.settings_panel)
        placeAsSheet(panel, graph.appearanceSettings.read().topBar)
        entries = findViewById(R.id.settings_entries)
        show(settingsMenu())
        panel.stylePanel(theme.roles)
        entries.getChildAt(0)?.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        theme.start()
    }

    override fun onStop() {
        super.onStop()
        theme.stop()
    }

    private fun show(menu: List<SettingsEntry>) {
        for (entry in menu) {
            val view = layoutInflater.inflate(R.layout.settings_entry, entries, false)
            view.findViewById<TextView>(R.id.settings_entry_title).setText(label(entry))
            view.findViewById<ImageView>(R.id.settings_entry_icon).setImageResource(icon(entry))
            view.findViewById<ImageView>(R.id.settings_entry_end).setImageResource(end(entry))
            view.setOnClickListener { open(entry) }
            entries.addView(view)
        }
    }

    private fun label(entry: SettingsEntry): Int = when (entry) {
        SettingsEntry.HideApps -> R.string.settings_hide_apps
        SettingsEntry.Appearance -> R.string.settings_appearance
        SettingsEntry.SystemSettings -> R.string.settings_system
        SettingsEntry.AndroidSettings -> R.string.settings_android
        SettingsEntry.Restart -> R.string.settings_restart
    }

    private fun icon(entry: SettingsEntry): Int = when (entry) {
        SettingsEntry.HideApps -> UiR.drawable.icon_eye_off
        SettingsEntry.Appearance -> UiR.drawable.icon_palette
        SettingsEntry.SystemSettings -> UiR.drawable.icon_tv
        SettingsEntry.AndroidSettings -> R.drawable.settings_icon_phone
        SettingsEntry.Restart -> UiR.drawable.icon_reset
    }

    /** At the entry's end: a screen of Luncher's, or another app; nothing for an action. */
    private fun end(entry: SettingsEntry): Int = when (entry) {
        SettingsEntry.HideApps, SettingsEntry.Appearance -> UiR.drawable.icon_chevron
        SettingsEntry.SystemSettings, SettingsEntry.AndroidSettings -> UiR.drawable.icon_open
        SettingsEntry.Restart -> 0
    }

    private fun open(entry: SettingsEntry) = when (entry) {
        SettingsEntry.HideApps -> openOwnPanel(Intent(this, HideAppsActivity::class.java))
        SettingsEntry.Appearance -> openOwnPanel(Intent(this, AppearanceActivity::class.java))
        SettingsEntry.SystemSettings -> openSystemSettings()
        SettingsEntry.AndroidSettings -> openAndroidSettings()
        SettingsEntry.Restart -> restart.confirm()
    }

    /**
     * Opens a panel of its own over this one (its theme is `Theme.Luncher.Settings.Panel`), in its
     * place: the same side sheet ([placeAsSheet]), so it covers this one, which stays shown under
     * it as it fades in and out, and with it the dimming of the home screen behind. The appearance
     * editor is full-screen, and covers both.
     */
    private fun openOwnPanel(intent: Intent) = startActivity(intent)

    /**
     * In a task of its own, like any other app; Back from it returns to this panel. The TV's own
     * settings first: on some boxes (a Mi Box 3, Android 7.1) ACTION_SETTINGS opens a phone
     * Settings app left in the firmware, which crashes on its first entry.
     */
    private fun openSystemSettings() {
        try {
            startActivity((tvSettings() ?: Intent(Settings.ACTION_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_system_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Android's phone-style settings, by package: wherever ACTION_SETTINGS goes, this entry goes
     * there. In a task of its own, as [openSystemSettings].
     */
    private fun openAndroidSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).setPackage(ANDROID_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_android_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The TV's settings, e.g. com.android.tv.settings/.MainSettings, by name: their activity lacks
     * CATEGORY_DEFAULT, so startActivity can't resolve the intent itself.
     */
    private fun tvSettings(): Intent? =
        packageManager.queryIntentActivities(tvSettingsIntent(), 0).firstOrNull()?.activityInfo
            ?.let { Intent(Intent.ACTION_MAIN).setClassName(it.packageName, it.name) }
}

/** Android's phone-style settings app. */
internal const val ANDROID_SETTINGS = "com.android.settings"

/** What Android TV launchers open for the settings; in the manifest's <queries> too, for API 30+. */
internal fun tvSettingsIntent(): Intent =
    Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
