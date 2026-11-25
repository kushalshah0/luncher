package com.luncher.domain.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceOptionsTest {

    private val catalog = appearanceOptions()

    @Test
    fun `lists the home mode, the tiles' options, the dock's, the top bar's, the theme's, then the background's, after its picture`() {
        assertEquals(
            listOf(AppearanceTopic.HOME, AppearanceTopic.TILES, AppearanceTopic.DOCK, AppearanceTopic.TOP_BAR, AppearanceTopic.THEME, AppearanceTopic.BACKGROUND),
            catalog.groups.map { it.topic },
        )
        assertEquals(listOf(AppearanceOption.HomeMode), catalog.groups[0].options)
        assertEquals(listOf(AppearanceOption.AccentColor, AppearanceOption.ThemeMode), catalog.groups[4].options)
        assertEquals(
            listOf(AppearanceOption.BackgroundBlur, AppearanceOption.BackgroundPosition, AppearanceOption.BackgroundDim, AppearanceOption.BackgroundFit),
            catalog.groups[5].options,
        )
        assertEquals(listOf(false, false, false, false, false, true), catalog.groups.map { it.editedApart })
        assertEquals(
            listOf(
                AppearanceOption.VerticalAlignment,
                AppearanceOption.HorizontalAlignment,
                AppearanceOption.FullBottomRow,
                AppearanceOption.TileSize,
                AppearanceOption.CornerRadius,
                AppearanceOption.FocusFrame,
                AppearanceOption.AppName,
            ),
            catalog.groups[1].options,
        )
        assertEquals(
            listOf(
                AppearanceOption.DockSize, AppearanceOption.DockMagnification, AppearanceOption.DockName,
                AppearanceOption.DockShelf, AppearanceOption.DockShape, AppearanceOption.DockSound,
            ),
            catalog.groups[2].options,
        )
        assertEquals(
            listOf(
                AppearanceOption.ShowTime, AppearanceOption.ShowDate, AppearanceOption.ClockSide, AppearanceOption.TimeSide,
                AppearanceOption.ShowNetwork, AppearanceOption.ShowMemory, AppearanceOption.ShowBluetooth,
                AppearanceOption.ShowUsb, AppearanceOption.ShowVpn,
            ),
            catalog.groups[3].options,
        )
    }

    // NETTV fork: the tiles' options change nothing in the dock's mode, and the dock's nothing in the tiles'.
    @Test
    fun `the tiles' options apply in the tiles' mode, the dock's in the dock's`() {
        val tiles = Appearance()
        val dock = Appearance(homeMode = HomeMode.DOCK)
        for (option in catalog.groups[1].options) {
            assertTrue("$option", option.appliesTo(tiles, AppearanceContext.UNKNOWN))
            assertFalse("$option", option.appliesTo(dock, AppearanceContext.UNKNOWN))
        }
        for (option in catalog.groups[2].options) {
            assertFalse("$option", option.appliesTo(tiles, AppearanceContext.UNKNOWN))
            assertTrue("$option", option.appliesTo(dock, AppearanceContext.UNKNOWN))
        }
        assertFalse(AppearanceOption.DockShape.appliesTo(dock.copy(dock = DockAppearance(shelf = DockShelf.NONE)), AppearanceContext.UNKNOWN))
    }

    // An option that isn't listed couldn't be changed, nor would the catalog-wide tests cover it.
    @Test
    fun `lists every option, each once`() {
        val declared = AppearanceOption::class.java.classes
            .filter { AppearanceOption::class.java.isAssignableFrom(it) }
            .map { it.getField("INSTANCE").get(null) }
            .toSet()

        assertEquals(declared, catalog.all.toSet())
        assertEquals(catalog.all.distinct(), catalog.all)
    }

    @Test
    fun `has no empty group`() {
        catalog.groups.forEach { assertTrue(it.options.isNotEmpty()) }
    }

    // Every value of every option, each where it's offered: left and right with a wide picture,
    // top and bottom with a tall one.
    @Test
    fun `the changes the catalog-wide tests draw apply, and differ from their base`() {
        val changes = appearanceChanges()

        assertEquals(catalog.all.sumOf { it.values.size - 1 }, changes.size)
        for (change in changes) {
            assertTrue("$change", change.option.appliesTo(change.base, change.context))
            assertTrue("$change", change.value in change.option.values(change.context))
            assertTrue("$change", change.context in SAMPLE_CONTEXTS)
            assertTrue("$change", change.changed != change.base)
        }
        // Each applies to the defaults, the background's to the defaults with a picture, the dock's in the dock's mode.
        val withPicture = Appearance(background = Background(picture = SAMPLE_WALLPAPER))
        val docked = Appearance(homeMode = HomeMode.DOCK)
        for (change in changes) assertTrue("$change", change.base == Appearance() || change.base == withPicture || change.base == docked)
    }
}
