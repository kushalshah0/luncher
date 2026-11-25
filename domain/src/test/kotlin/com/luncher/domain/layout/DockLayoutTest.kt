package com.luncher.domain.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dock's places and sizes (NETTV fork). */
class DockLayoutTest {

    private val layout = DockLayout(count = 5, iconPx = 100f, gapPx = 20f, paddingPx = 10f, magnification = 1.5f)
    private val centers = FloatArray(5)
    private val scales = FloatArray(5)

    @Test
    fun `at rest every icon is its size, and the shelf fits them`() {
        val width = layout.place(DockLayout.NO_FOCUS, centers, scales)

        assertEquals(5 * 100f + 4 * 20f + 2 * 10f, width, 0.001f)
        assertEquals(layout.restingWidth, width, 0.001f)
        assertEquals(listOf(60f, 180f, 300f, 420f, 540f), centers.toList())
        scales.forEach { assertEquals(1f, it, 0f) }
    }

    @Test
    fun `the focused icon is magnified in full, its neighbors less the further they are`() {
        layout.place(2f, centers, scales)

        assertEquals(1.5f, scales[2], 0.001f)
        assertTrue(scales[1] in 1f..1.5f && scales[1] == scales[3])
        assertTrue(scales[0] < scales[1] && scales[0] == scales[4])
    }

    @Test
    fun `the magnification fades with its amount`() {
        layout.place(2f, centers, scales, amount = 0f)

        scales.forEach { assertEquals(1f, it, 0f) }
    }

    @Test
    fun `the shelf widens under the magnified icons`() {
        assertTrue(layout.place(2f, centers, scales) > layout.restingWidth)
    }

    @Test
    fun `a shelf that fits is in the middle; a wider one scrolls to the focus, its ends no further in than the space's`() {
        assertEquals(200f, layout.left(available = 1000f, width = 600f, focusCenter = 300f), 0f)
        assertEquals(0f, layout.left(available = 400f, width = 600f, focusCenter = 60f), 0f)
        assertEquals(-200f, layout.left(available = 400f, width = 600f, focusCenter = 540f), 0f)
        assertEquals(-100f, layout.left(available = 400f, width = 600f, focusCenter = 300f), 0f)
    }
}
