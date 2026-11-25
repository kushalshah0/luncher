package com.luncher.domain.apps

import com.luncher.domain.apps.FakeInstalledApps.Companion.app
import com.luncher.domain.apps.FakeInstalledApps.Companion.homeApp
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dock's apps (NETTV fork): which they are, and putting apps in, taking them out and moving them. */
class DockedAppsTest {

    private val installed = listOf(app("games"), app("movies"), app("music"), app("news"))

    private fun arranged(arrangement: AppArrangement = AppArrangement.NONE) =
        homeApps(installed, "com.luncher.launcher", arrangement, emptyMap(), Locale.ENGLISH)

    private fun labels(apps: List<HomeApp>) = apps.map { it.name }

    private fun id(name: String) = app(name).launchable

    @Test
    fun `never changed, the dock holds the first shown apps, and stores nothing`() {
        val arranged = arranged()

        assertEquals(listOf("Games", "Movies", "Music", "News"), labels(arranged.docked))
        assertNull(arranged.arrangement.docked)
    }

    @Test
    fun `the stored dock keeps its order, a hidden app in it too`() {
        val arranged = arranged(AppArrangement(order = null, hidden = listOf(id("games")), docked = listOf(id("news"), id("games"))))

        assertEquals(listOf("News", "Games"), labels(arranged.docked))
    }

    @Test
    fun `docking puts an app last, once; undocking takes it out`() {
        val start = arranged(AppArrangement(order = null, hidden = emptyList(), docked = listOf(id("music"))))

        val docked = start.dock(homeApp("games")).dock(homeApp("games"))
        assertEquals(listOf("Music", "Games"), labels(docked.docked))
        assertEquals(listOf(id("music"), id("games")), docked.arrangement.docked)

        val undocked = docked.undock(homeApp("music"))
        assertEquals(listOf("Games"), labels(undocked.docked))
    }

    @Test
    fun `undocking every app stores an empty dock, not the default one`() {
        val arranged = arranged(AppArrangement(order = null, hidden = emptyList(), docked = listOf(id("music"))))

        val empty = arranged.undock(homeApp("music"))

        assertEquals(emptyList<LaunchableApp>(), empty.arrangement.docked)
        assertTrue(arranged(empty.arrangement).docked.isEmpty())
    }

    @Test
    fun `moving a docked app puts it at its new place, within the dock's ends`() {
        val arranged = arranged(AppArrangement(order = null, hidden = emptyList(), docked = listOf(id("games"), id("movies"), id("music"))))

        assertEquals(listOf("Movies", "Music", "Games"), labels(arranged.dockMoved(homeApp("games"), 2).docked))
        assertEquals(listOf("Music", "Games", "Movies"), labels(arranged.dockMoved(homeApp("music"), -5).docked))
    }

    @Test
    fun `an app not installed now keeps its place in the stored dock`() {
        val gone = LaunchableApp("com.example.gone", "com.example.gone.Main")
        val arranged = arranged(AppArrangement(order = null, hidden = emptyList(), docked = listOf(id("games"), gone, id("music"))))

        assertEquals(listOf("Games", "Music"), labels(arranged.docked))
        assertEquals(listOf(id("games"), gone, id("music"), id("news")), arranged.dock(homeApp("news")).arrangement.docked)
    }

    @Test
    fun `the list of apps says which are docked`() {
        val rows = arranged(AppArrangement(order = null, hidden = emptyList(), docked = listOf(id("music")))).byLabel()

        assertEquals(listOf(false, false, true, false), rows.map { it.docked })
    }
}
