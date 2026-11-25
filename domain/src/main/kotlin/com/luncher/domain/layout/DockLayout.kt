package com.luncher.domain.layout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Where the dock's [count] icons go and how big each is (NETTV fork), as a Mac's dock: every icon
 * [iconPx] wide at rest, [gapPx] apart, [paddingPx] from the shelf's ends; the icon at the focus
 * [magnification] times as big, its neighbors less the further they are ([scaleAt]), so the
 * focus moving is a wave along the dock, the shelf widening under it. The focus is a position, not
 * an index: between two icons while it moves, so the wave glides.
 *
 * Allocation-free: [place] fills arrays the view keeps, at each frame of the focus's move.
 */
class DockLayout(
    val count: Int,
    val iconPx: Float,
    val gapPx: Float,
    val paddingPx: Float,
    val magnification: Float,
) {

    /** The shelf's height: an icon at rest and the padding above and below it. Magnified icons rise above it. */
    val shelfHeight: Float get() = iconPx + 2 * paddingPx

    /** The largest an icon gets: the focused one's size. */
    val largestIconPx: Float get() = iconPx * magnification

    /**
     * How much bigger the icon [index] is than at rest, with the focus at [focus], magnified by
     * [amount] of the [magnification] (0 at rest, 1 in full: the dock gaining or losing the focus
     * fades it); 1 for every icon with no focus ([NO_FOCUS]).
     */
    fun scaleAt(index: Int, focus: Float, amount: Float = 1f): Float {
        if (focus.isNaN()) return 1f
        val distance = abs(index - focus)
        if (distance >= REACH) return 1f
        val falloff = ((1 + cos(PI * distance / REACH)) / 2).toFloat()
        return 1 + (magnification - 1) * falloff * amount
    }

    /**
     * Fills [centers] with each icon's middle, from the shelf's left end, and [scales] with its
     * size as a part of [iconPx], with the focus at [focus] magnified by [amount]; returns the shelf's width.
     */
    fun place(focus: Float, centers: FloatArray, scales: FloatArray, amount: Float = 1f): Float {
        var x = paddingPx
        for (i in 0 until count) {
            val scale = scaleAt(i, focus, amount)
            val width = iconPx * scale
            scales[i] = scale
            centers[i] = x + width / 2
            x += width + if (i < count - 1) gapPx else 0f
        }
        return x + paddingPx
    }

    /** The shelf's width with no icon magnified: what it settles to. */
    val restingWidth: Float get() = if (count == 0) 2 * paddingPx else count * iconPx + (count - 1) * gapPx + 2 * paddingPx

    /**
     * Where the shelf's left end goes in a space [available] px wide, for a shelf [width] px wide
     * whose focused icon's middle is [focusCenter] from its left end: in the middle while it fits;
     * else scrolled so the focused icon is in the middle, but neither end further in than the space's.
     */
    fun left(available: Float, width: Float, focusCenter: Float): Float {
        if (width <= available) return (available - width) / 2
        return (available / 2 - focusCenter).coerceIn(available - width, 0f)
    }

    companion object {
        /** No icon focused: every one at rest. */
        const val NO_FOCUS = Float.NaN

        /** How many icons away from the focus an icon still grows, a little: the wave's half width. */
        const val REACH = 2.5f
    }
}
