package com.luncher.launcher.home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.luncher.domain.appearance.Appearance
import com.luncher.domain.appearance.HomeMode
import com.luncher.domain.appearance.ThemeAppearance
import com.luncher.domain.appearance.colorRoles
import com.luncher.domain.apps.AppAction
import com.luncher.domain.apps.ArrangedApps
import com.luncher.domain.apps.HomeApp
import com.luncher.domain.apps.MAX_APP_NAME
import com.luncher.domain.apps.appActions
import com.luncher.domain.apps.appName
import com.luncher.domain.apps.dockActions
import com.luncher.domain.apps.homeApps
import com.luncher.launcher.settings.R as SettingsR
import com.luncher.launcher.settings.SettingsActivity
import com.luncher.launcher.settings.TileBannerActivity
import com.luncher.launcher.ui.Handover
import com.luncher.launcher.ui.DialogButton
import com.luncher.launcher.ui.NameDialog
import com.luncher.launcher.ui.color
import com.luncher.launcher.ui.homeTileSize
import com.luncher.launcher.ui.R as UiR
import java.util.Locale

/**
 * The home screen: the time, the date and a settings entry above the TV apps the user didn't hide,
 * as tiles; OK on a tile opens its app, OK on the settings entry (or the Menu key) opens the
 * settings panel. A long press of OK on a tile opens its [TileMenu], to move its app ([MoveMode],
 * which gets every key first while it's on), hide it, rename it, choose a picture for its tile
 * ([TileBannerActivity]), or open its info in the device's settings.
 *
 * How it looks follows the user's [Appearance] while it's shown: each part goes to the view that
 * draws it (the background to the window, [HomeWallpaper]), which compares it with what it has, so
 * an unchanged part costs nothing.
 */
class HomeActivity : Activity() {

    private val installedApps by lazy { graph.installedApps }
    private val arrangements by lazy { graph.appArrangements }
    private val customizations by lazy { graph.appCustomizations }
    private val images by lazy { graph.appImages }
    private val tileImages by lazy { graph.preparedTileImages }
    private val appInfoScreen by lazy { graph.appInfoScreen }
    private val clock by lazy { graph.clock }
    private val appearanceSettings by lazy { graph.appearanceSettings }
    private lateinit var topBar: TopBarView
    private lateinit var tiles: AppTilesView
    private lateinit var dock: DockView
    private lateinit var dockSounds: DockSounds

    /** Whether the dock ticks, as the appearance last said; and whether the screen is started, when it may. */
    private var dockSoundsOn = false
    private var started = false

    /** Tiles or the dock, as the appearance last said (NETTV fork). */
    private var mode = HomeMode.TILES
    private lateinit var clockView: ClockView

    /** The dock's idle screen (NETTV fork): shown after [IDLE_AFTER] without a key, in the dock's mode. */
    private lateinit var idleClock: IdleClockView
    private var idle = false
    private var resumed = false

    /** The key that woke the dock: its repeats and its release do nothing else. */
    private var swallowedKey = KeyEvent.KEYCODE_UNKNOWN
    private val goIdle = Runnable { enterIdle() }
    private lateinit var settingsEntry: View
    private lateinit var statusView: View
    private lateinit var status: StatusIndicators
    private lateinit var wifiPicker: WifiPicker
    private lateinit var vpnMenu: VpnMenu
    private lateinit var bluetoothMenu: BluetoothMenu
    private lateinit var empty: TextView
    private lateinit var move: MoveMode
    private lateinit var wallpaper: HomeWallpaper
    private lateinit var working: HomeWorking

    /** The tiles' menu: made the first time a long press opens it. */
    private var menu: TileMenu? = null

    /** The dialog Rename in a tile's menu opened; null when none was. */
    private var renameDialog: NameDialog? = null

    /** What the tiles show; null until the first [onStart]. */
    private var shown: List<HomeApp>? = null

    /** The apps as last read, shown and hidden: what the tiles' menu acts on. */
    private var arranged: ArrangedApps? = null

    private val showAppearance: () -> Unit = { show(appearanceSettings.read()) }

    /** The theme the screen shows, so an equal one changes nothing; null before the first. */
    private var shownTheme: ThemeAppearance? = null

    // Hidden or shown in the settings, over the home screen, or hidden from a tile's menu: shown at
    // once. Not while moving an app, whose own save this is, with the tiles showing it already.
    private val showArrangement: () -> Unit = { if (!move.active) refresh() }

    /** Apps installed, updated or removed while the user moves an app, read when the mode ends. */
    private var appsChangedWhileMoving = false

    // Installed, updated or removed, or renamed, while shown: shown at once, or, while moving an
    // app, whose tiles are the user's work in progress, once it ends.
    private val showAppChanges: () -> Unit = {
        if (move.active) appsChangedWhileMoving = true else refresh()
    }

    // A tile's picture prepared while shown: that tile drawn again with it, the others as they are.
    private val showTileImages: () -> Unit = { followTileImages() }

    /**
     * The settings panel closing takes its animation from the window under it, the top full-screen
     * one of Android's transition (seen on Android 7.0 to 13, not on 16): Android's own, four times
     * as long as the panel's fade. So this window has the panel's closing animation while the panel
     * is over it, and its own again a moment after it's back, by when that transition has started:
     * Android plays that one as the Home key comes back from an app.
     */
    private var animations = 0
    private val ownAnimations = Runnable { window.setWindowAnimations(animations) }

    private val openApp = View.OnClickListener { open((it as AppTileView).app) }

    private val openMenu = View.OnLongClickListener {
        val arranged = arranged ?: return@OnLongClickListener false
        val tile = it as AppTileView
        val menu = menu ?: TileMenu(this).also { menu = it }
        menu.style(colorRoles(shownTheme ?: appearanceSettings.read().theme), tiles.tileStyle.corners)
        menu.show(tile, appActions(arranged, tile.app, appInfoScreen.exists())) { action -> act(action, tile) }
        true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.home_activity)
        animations = window.attributes.windowAnimations   // the theme's, once the window has its content
        topBar = findViewById(R.id.home_top_bar)
        tiles = findViewById(R.id.home_apps)
        dock = findViewById(R.id.home_dock)
        dock.onOpen = ::open
        dock.onMenu = ::openDockMenu
        dockSounds = DockSounds(this)
        dock.onStep = { dockSounds.tick() }
        dock.onMoved = { app, index -> arranged?.let { arrangements.save(it.dockMoved(app, index).arrangement) } }
        empty = findViewById(R.id.home_empty)
        clockView = findViewById(R.id.home_clock)
        idleClock = findViewById(R.id.home_idle)
        settingsEntry = findViewById(R.id.home_settings)
        settingsEntry.setOnClickListener { openSettings() }
        statusView = findViewById(R.id.home_status)
        status = StatusIndicators(findViewById(R.id.home_status))
        wifiPicker = WifiPicker(this)
        status.network.setOnClickListener { wifiPicker.show(status.network) }
        status.ram.setOnClickListener { status.cleanMemory() }
        vpnMenu = VpnMenu(this)
        status.vpn.setOnClickListener { vpnMenu.show(status.vpn) }
        status.usb.setOnClickListener { status.openStorage() }
        bluetoothMenu = BluetoothMenu(this)
        status.bluetooth.setOnClickListener { bluetoothMenu.show(status.bluetooth) }
        wallpaper = HomeWallpaper(this, graph.wallpaper, ::showOnPicture) {
            tiles.frost = it
            dock.frost = it
        }
        working = HomeWorking(this, graph.wallpaper, tileImages)
        move = MoveMode(
            tiles,
            normalBar = listOf(clockView, statusView, settingsEntry),
            title = findViewById(R.id.home_move_title),
            hint = findViewById(R.id.home_move_hint),
            arrangements,
            onEnd = ::movingEnded,
        )
        // A home activity must not finish on Back: Back only ends moving an app. From Android 16
        // (API 36) on, Back no longer calls onBackPressed in apps targeting it, nor reaches them
        // as a key, and closes the activity unless a callback takes it; before that,
        // onBackPressed below does.
        if (Build.VERSION.SDK_INT >= 36) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                back()
            }
        }
    }

    // Visible from onStart to onStop; paused only, e.g. behind a dialog or the settings panel, it
    // still shows. The appearance, the arrangement, the names and the installed apps, as the clock,
    // only while visible: a change made meanwhile (in the appearance or background editor, which
    // cover the home screen whole) is applied once, when it comes back. The apps are read again
    // here, not as it resumes: started again behind the settings panel, it shows what changed while
    // it was hidden (a picture deleted from the tiles that showed it) at once, not only once the
    // panel closes; and while it's started, the listeners show every change, so resuming needs no
    // read. Moving an app ended as it stopped.
    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed(ownAnimations, OWN_ANIMATIONS_AFTER)
        resumed = true
        armIdle()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        window.decorView.removeCallbacks(goIdle)
    }

    override fun onStart() {
        super.onStart()
        started = true
        clockView.start(clock)
        status.start()
        wallpaper.start()
        working.start()
        tileImages.addListener(showTileImages)
        appearanceSettings.addListener(showAppearance)
        showAppearance()
        arrangements.addListener(showArrangement)
        customizations.addListener(showAppChanges)
        installedApps.addListener(showAppChanges)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        started = false
        followDockSounds()
        // Leaving the home screen (another app, the screen off) closes the menu, or the rename
        // dialog, and ends moving.
        menu?.dismiss()
        renameDialog?.dismiss()
        move.end()
        dock.cancelMove()
        wake(animate = false)
        clockView.stop()
        status.stop()
        wallpaper.stop()
        working.stop()
        tileImages.removeListener(showTileImages)
        appearanceSettings.removeListener(showAppearance)
        arrangements.removeListener(showArrangement)
        customizations.removeListener(showAppChanges)
        installedApps.removeListener(showAppChanges)
    }

    /** A state list: [accent] in [shape] while focused, the glass drawable [glass] otherwise. */
    private fun glassWithFocus(shape: Int, glass: Int, accent: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            this.shape = shape
            if (shape == GradientDrawable.RECTANGLE) cornerRadius = 1000f
            setColor(accent)
        })
        addState(intArrayOf(), getDrawable(glass))
    }

    // The Wi-Fi picker asked for the location permission, or the VPN menu for WireGuard's: do what
    // it was asked for once it's granted.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        when (requestCode) {
            WifiPicker.REQUEST_LOCATION -> wifiPicker.showAgain()
            VpnMenu.REQUEST_CONTROL_TUNNELS -> vpnMenu.turnOff()
            BluetoothMenu.REQUEST_BLUETOOTH -> bluetoothMenu.showAgain()
        }
    }

    // Home while the home screen is in front.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        menu?.dismiss()
        renameDialog?.dismiss()
        move.end()
        dock.cancelMove()
        // Home, in the dock's mode: back to the dock, from the top bar too.
        if (mode == HomeMode.DOCK && dock.apps.isNotEmpty()) dock.requestFocus()
        wake(animate = true)
        armIdle()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The volume keys change the volume, idle or not, and leave the idle screen as it is.
        if (event.keyCode in VOLUME_KEYS) return super.dispatchKeyEvent(event)
        // Idle, a key only brings the dock back: it doesn't also open or move anything.
        if (idle && event.action == KeyEvent.ACTION_DOWN) {
            swallowedKey = event.keyCode
            wake(animate = true)
        }
        if (event.keyCode == swallowedKey) {
            if (event.action == KeyEvent.ACTION_UP) swallowedKey = KeyEvent.KEYCODE_UNKNOWN
            armIdle()
            return true
        }
        armIdle()
        return move.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    /** Counts [IDLE_AFTER] again from now, in the dock's mode while the screen is in front. */
    private fun armIdle() {
        val decor = window.decorView
        decor.removeCallbacks(goIdle)
        if (resumed && mode == HomeMode.DOCK && !idle) decor.postDelayed(goIdle, IDLE_AFTER)
    }

    /**
     * The dock slides down and away, the top bar fades, and the idle screen fades in over them. Not
     * while a menu or dialog is open (the window hasn't the focus then) or an app is being moved.
     */
    private fun enterIdle() {
        if (!resumed || mode != HomeMode.DOCK || idle) return
        if (!hasWindowFocus() || dock.moving || move.active) {
            armIdle()
            return
        }
        idle = true
        dock.animate().cancel()
        dock.animate().translationY(dock.height.toFloat()).setStartDelay(0).setDuration(SLIDE_MS)
            .setInterpolator(AccelerateInterpolator(1.5f))
        topBar.animate().cancel()
        topBar.animate().alpha(0f).setStartDelay(0).setDuration(SLIDE_MS)
        idleClock.animate().cancel()
        idleClock.alpha = 0f
        idleClock.visibility = View.VISIBLE
        idleClock.start()
        idleClock.animate().alpha(1f).setStartDelay(SLIDE_MS / 2).setDuration(IDLE_FADE_IN_MS)
    }

    /** The idle screen fades out and the dock slides back up, or, not [animate]d, both at once. */
    private fun wake(animate: Boolean) {
        if (!idle) return
        idle = false
        idleClock.animate().cancel()
        dock.animate().cancel()
        topBar.animate().cancel()
        if (!animate) {
            idleClock.stop()
            idleClock.visibility = View.GONE
            idleClock.alpha = 1f
            dock.translationY = 0f
            topBar.alpha = 1f
            return
        }
        idleClock.animate().alpha(0f).setStartDelay(0).setDuration(IDLE_FADE_OUT_MS).withEndAction {
            if (!idle) {
                idleClock.stop()
                idleClock.visibility = View.GONE
            }
        }
        dock.animate().translationY(0f).setStartDelay(IDLE_FADE_OUT_MS / 2).setDuration(SLIDE_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
        topBar.animate().alpha(1f).setStartDelay(IDLE_FADE_OUT_MS / 2).setDuration(SLIDE_MS)
    }

    /**
     * Reads the apps again: apps may have been installed or removed, hidden or shown in the
     * settings, or renamed, since the home screen was last shown.
     */
    private fun refresh() {
        val arranged = homeApps(installedApps.tvApps(), packageName, arrangements.read(), customizations.read(), Locale.getDefault())
        this.arranged = arranged
        showApps(arranged)
        prepareTiles()
    }

    /** [arranged]'s apps, as tiles or in the dock, as the home mode says; why there are none, if there aren't. */
    private fun showApps(arranged: ArrangedApps) {
        if (mode == HomeMode.DOCK) {
            dock.apps = arranged.docked
            showWhyDockEmpty(arranged)
            if (!topBar.hasFocus()) (if (arranged.docked.isNotEmpty()) dock else settingsEntry).requestFocus()
            return
        }
        // Unchanged, as on most returns to the home screen: keep the tiles, their images and focus.
        if (arranged.shown != shown) show(arranged.shown)
        showWhyEmpty(arranged)
    }

    /**
     * The home mode (NETTV fork): the tiles, or the dock in their place. The mode not shown holds
     * nothing: leaving the tiles drops them, and their images, and leaving the dock its icons, so
     * only the mode in use costs memory.
     */
    private fun showMode(mode: HomeMode) {
        if (mode == this.mode) return
        this.mode = mode
        wake(animate = false)
        armIdle()
        menu?.dismiss()
        renameDialog?.dismiss()
        move.end()
        dock.cancelMove()
        if (mode == HomeMode.DOCK) {
            tiles.removeAllViews()
            shown = null
            tiles.visibility = View.GONE
            dock.visibility = View.VISIBLE
        } else {
            dock.apps = emptyList()
            dock.visibility = View.GONE
            tiles.visibility = View.VISIBLE
        }
        arranged?.let(::showApps)
    }

    /**
     * The pictures the tiles show, hidden apps' too, prepared for tiles of the size they have now
     * if they aren't: asked only when what they're drawn from, or the screen, changed. Tiles made
     * meanwhile, or whose picture was prepared while the home screen was stopped, follow it too.
     */
    private fun prepareTiles() {
        if (mode == HomeMode.DOCK) return   // no tiles: prepared once they're back
        val arranged = arranged ?: return
        tileImages.prepare(arranged.tileDrawings(homeTileSize(tiles.tileGeometry)))
        followTileImages()
    }

    /** Each tile whose picture was prepared since it was drawn without it, drawn again with it. */
    private fun followTileImages() {
        for (i in 0 until tiles.childCount) (tiles.getChildAt(i) as AppTileView).followPreparing()
    }

    private fun show(appearance: Appearance) {
        dock.appearance = appearance.dock
        showMode(appearance.homeMode)
        dockSoundsOn = appearance.homeMode == HomeMode.DOCK && appearance.dock.sounds
        followDockSounds()
        tiles.tileGeometry = appearance.tileGeometry
        tiles.tileStyle = appearance.tileStyle
        topBar.appearance = appearance.topBar
        status.shown = appearance.topBar
        wallpaper.show(appearance.background)
        showTheme(appearance.theme)
        // Another tile size: the pictures prepared for it.
        prepareTiles()
    }

    /**
     * The theme's accent on the tiles' focus frame and the gear's disc, and its colors in the
     * tile's menu and the window while the wallpaper is drawn or the tiles' pictures prepared; the
     * home screen itself stays dark, on its wallpaper. Applied only when the theme changed.
     */
    private fun showTheme(theme: ThemeAppearance) {
        if (theme == shownTheme) return
        shownTheme = theme
        val roles = colorRoles(theme)
        tiles.accent = roles.homeAccent
        // NETTV fork: the clickable pills (settings among them) are frosted glass, and white with
        // black text and icon while focused, whatever the accent.
        for (pill in listOf(status.network, status.ram, status.usb, status.vpn, status.bluetooth, settingsEntry as TextView)) {
            pill.background = glassWithFocus(GradientDrawable.RECTANGLE, R.drawable.home_glass_pill, android.graphics.Color.WHITE)
            if (Build.VERSION.SDK_INT >= 23) pill.compoundDrawableTintList = pill.textColors
        }
        menu?.style(roles, tiles.tileStyle.corners)
        working.roles = roles
    }

    /**
     * The secondary texts (the date, the move hint, why there are no tiles) in an opaque gray over
     * a picture: translucent, they'd take its colors, muddy inside their dark edge on a light one.
     * On the plain color, translucent, as Luncher's other secondary texts.
     */
    private fun showOnPicture(onPicture: Boolean) {
        val color = color(if (onPicture) UiR.color.text_secondary_on_picture else UiR.color.text_secondary)
        for (id in intArrayOf(R.id.home_date, R.id.home_move_hint, R.id.home_empty)) findViewById<TextView>(id).setTextColor(color)
    }

    /** What OK on an entry of [tile]'s menu does. */
    private fun act(action: AppAction, tile: AppTileView) {
        val arranged = arranged ?: return
        when (action) {
            AppAction.Move -> move.start(arranged, tile)
            // Stored, and shown as any change of the arrangement is (showArrangement).
            AppAction.Hide -> arrangements.save(arranged.hide(tile.app).arrangement)
            AppAction.Rename -> askName(tile.app)
            // The two screens keep the menu up until they come over it (Handover.toScreen): the home
            // screen isn't left bare while they start.
            // Full-screen, so the home screen is stopped meanwhile, and shows the tile as stored once
            // it's back (onStart). Its preview shows the tile where it is here, over that part of the wallpaper.
            AppAction.TileBanner -> Handover.toScreen {
                startActivity(TileBannerActivity.intent(this, tile.app.launchable, tiles.placeOf(tile)))
                true
            }
            AppAction.Info -> Handover.toScreen { openAppInfo(tile.app) }
            AppAction.RemoveFromDock -> arrangements.save(arranged.undock(tile.app).arrangement)
        }
    }

    /** The menu of the dock's focused icon, [app]'s (NETTV fork): beside the icon, as a tile's is. */
    private fun openDockMenu(app: HomeApp) {
        val arranged = arranged ?: return
        if (arranged.docked.none { it.launchable == app.launchable }) return
        val menu = menu ?: TileMenu(this).also { menu = it }
        menu.style(colorRoles(shownTheme ?: appearanceSettings.read().theme), tiles.tileStyle.corners)
        menu.show(app, dock.focusedBounds(), dockActions(arranged, app, appInfoScreen.exists())) { action -> actInDock(action, app) }
    }

    /** What OK on an entry of a dock icon's menu does. */
    private fun actInDock(action: AppAction, app: HomeApp) {
        val arranged = arranged ?: return
        when (action) {
            AppAction.Move -> dock.startMove()
            // Stored, and shown as any change of the arrangement is (showArrangement).
            AppAction.RemoveFromDock -> arrangements.save(arranged.undock(app).arrangement)
            AppAction.Rename -> askName(app)
            AppAction.Info -> Handover.toScreen { openAppInfo(app) }
            // Not in a dock icon's menu.
            AppAction.Hide, AppAction.TileBanner -> Unit
        }
    }

    /**
     * Asks for [app]'s new name in a dialog, the field holding the name it shows; for an app the
     * user renamed, its original name below it, which Reset puts back (dimmed for one that has
     * it). Rename, or the keyboard's Done, stores the name typed ([appName]: an empty field, or
     * the original name, is the original name again), which shows as any change of the names does
     * (showAppChanges); nothing is refused.
     */
    private fun askName(app: HomeApp) {
        val renamed = app.customization.name != null
        val dialog = NameDialog(
            this,
            colorRoles(shownTheme ?: appearanceSettings.read().theme),
            getString(R.string.home_rename_title),
            app.name,
            getString(R.string.home_rename_cancel),
            getString(R.string.home_rename_confirm),
            note = if (renamed) getString(R.string.home_rename_original, app.installed.label) else null,
            extra = DialogButton(getString(R.string.home_rename_reset), DialogButton.Kind.TONAL, UiR.drawable.icon_reset, available = renamed) {
                rename(app, null)
            },
            maxLength = MAX_APP_NAME,
        ) { typed ->
            rename(app, appName(typed, app.installed.label))
            null
        }
        renameDialog = dialog
        dialog.show()
    }

    /** Stores [app] named [name], or by its own label for null, as the apps are now. */
    private fun rename(app: HomeApp, name: String?) {
        val arranged = arranged ?: return
        customizations.save(arranged.renamed(app, name))
    }

    /**
     * The app's page in the device's settings, in a task of its own, as any other app; Back from it
     * returns here. The menu offered it only if the page exists (AppInfoScreen), which the
     * intent's action and Uri must match; a settings app disabled since gets a message. Whether it
     * opened.
     */
    private fun openAppInfo(app: HomeApp): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.launchable.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.home_menu_info_unavailable, Toast.LENGTH_SHORT).show()
            return false
        }
    }

    /** Moving an app ended: the tiles show [arranged] already, unless apps changed meanwhile. */
    private fun movingEnded(arranged: ArrangedApps) {
        this.arranged = arranged
        shown = arranged.shown
        if (appsChangedWhileMoving) {
            appsChangedWhileMoving = false
            refresh()
        }
    }

    private fun show(apps: List<HomeApp>) {
        // The menu, or the rename dialog, is of a tile that may be gone, or of an app that changed.
        menu?.dismiss()
        renameDialog?.dismiss()
        val focusedTile = tiles.focusedChild as? AppTileView
        val focused = focusedTile?.app?.launchable
        val focusedIndex = focusedTile?.let { tiles.indexOfChild(it) } ?: 0
        // Focus in the top bar (on the settings entry) stays there; the tiles don't take it.
        val topBarFocused = topBar.hasFocus()
        // Apps still there keep their tile and its image: installing one app draws one banner. A
        // renamed app is another HomeApp, so its tile is made again, with its new name.
        val kept = (0 until tiles.childCount).map { tiles.getChildAt(it) as AppTileView }.associateBy { it.app }
        tiles.removeAllViews()
        for (app in apps) {
            tiles.addView(
                kept[app] ?: AppTileView(this, app, images, tileImages, tiles.paints).apply {
                    setOnClickListener(openApp)
                    setOnLongClickListener(openMenu)
                },
            )
        }
        shown = apps
        // The focused app gone (hidden, uninstalled): the tile now in its place, or the last one;
        // without tiles, the settings entry.
        val index = apps.indexOfFirst { it.launchable == focused }.takeIf { it >= 0 } ?: focusedIndex.coerceAtMost(apps.size - 1)
        if (!topBarFocused) (tiles.getChildAt(index) ?: settingsEntry).requestFocus()
    }

    /** The dock's tick loaded while the started screen shows the dock with its sounds on; else released. */
    private fun followDockSounds() {
        if (started && dockSoundsOn) dockSounds.start() else dockSounds.stop()
    }

    /** With an empty dock, says where to add apps (NETTV fork). */
    private fun showWhyDockEmpty(arranged: ArrangedApps) {
        if (arranged.docked.isNotEmpty()) {
            empty.visibility = View.GONE
            return
        }
        empty.text = if (arranged.shown.isEmpty() && arranged.hidden.isEmpty()) {
            getString(R.string.home_no_apps)
        } else {
            getString(R.string.home_dock_empty, getString(SettingsR.string.settings_title), getString(SettingsR.string.settings_hide_apps))
        }
        empty.visibility = View.VISIBLE
    }

    /** Without tiles, says why: nothing installed, or everything hidden (and where to show apps again). */
    private fun showWhyEmpty(arranged: ArrangedApps) {
        if (arranged.shown.isNotEmpty()) {
            empty.visibility = View.GONE
            return
        }
        empty.text = if (arranged.hidden.isEmpty()) {
            getString(R.string.home_no_apps)
        } else {
            getString(R.string.home_all_hidden, getString(SettingsR.string.settings_title), getString(SettingsR.string.settings_hide_apps))
        }
        empty.visibility = View.VISIBLE
    }

    private fun open(app: HomeApp) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(app.launchable.packageName, app.launchable.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            refresh()   // uninstalled since the tiles were read: drop its tile
        }
    }

    private fun openSettings() {
        window.decorView.removeCallbacks(ownAnimations)
        window.setWindowAnimations(R.style.Animation_Luncher_Home_UnderPanel)
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // The Menu key opens the settings too, on remotes that have one; the top bar's entry is the
    // way on those that don't. On release, and only for a press that started here: not the end
    // of a press that closed something else.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MENU) return super.onKeyDown(keyCode, event)
        event.startTracking()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MENU) return super.onKeyUp(keyCode, event)
        if (event.isTracking && !event.isCanceled) openSettings()
        return true
    }

    // Back on Android 15 (API 35) and older; onCreate handles Android 16, which lint doesn't see.
    // Deprecated, but its replacements need AndroidX (OnBackPressedCallback) or API 33
    // (OnBackInvokedCallback). Doesn't call super, which would close the home screen.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    /** Back: puts back an app being moved in the dock, or ends moving a tile; else nothing, as a home screen. */
    private fun back() {
        if (dock.moving) dock.cancelMove() else move.back()
    }
}

/** How long after the home screen is back its window has its own animations again, in ms: Android's transition starts well within it. */
private const val OWN_ANIMATIONS_AFTER = 1000L

/** How long the dock waits for a key before its idle screen shows, in ms (NETTV fork). */
private const val IDLE_AFTER = 120_000L

/** The dock sliding down or up, in ms; the idle screen fading in and out. */
private const val SLIDE_MS = 320L
private const val IDLE_FADE_IN_MS = 450L
private const val IDLE_FADE_OUT_MS = 220L

private val VOLUME_KEYS = intArrayOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
