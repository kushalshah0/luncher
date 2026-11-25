package com.luncher.launcher.ui

import android.graphics.Path
import android.graphics.RectF
import com.luncher.domain.appearance.DockShape

// The dock's shelf outline (NETTV fork), shared by the home screen's dock and the appearance
// editor's preview of it, so the two are the same shape.

/**
 * [path] made the outline of a dock shelf in [bounds], of [shape]: a pill's round ends, or
 * continuous-curvature corners (the curve easing into the straight edges, as Apple draws them,
 * not a circle's quarter that meets them at a corner of curvature), never square.
 */
fun shelfOutline(path: Path, bounds: RectF, shape: DockShape) {
    path.reset()
    val height = bounds.height()
    if (shape == DockShape.PILL) {
        path.addRoundRect(bounds, height / 2, height / 2, Path.Direction.CW)
        return
    }
    // A continuous corner runs 1.53 radii along each edge: no larger than both edges allow.
    val radius = minOf(shape.cornerRatio * height, bounds.width() / (2 * CURVE_REACH), height / (2 * CURVE_REACH))
    val l = bounds.left
    val t = bounds.top
    val r = bounds.right
    val b = bounds.bottom
    path.moveTo(l + CURVE_REACH * radius, t)
    corner(path, r, t, -1f, 0f, 0f, 1f, radius)
    corner(path, r, b, 0f, -1f, -1f, 0f, radius)
    corner(path, l, b, 1f, 0f, 0f, -1f, radius)
    corner(path, l, t, 0f, 1f, 1f, 0f, radius)
    path.close()
}

/** How far along each edge a continuous corner runs, in radii. */
private const val CURVE_REACH = 1.52866483f

/**
 * One continuous-curvature corner at ([cx], [cy]), drawn from the edge coming in, which runs from
 * the corner along ([inX], [inY]), to the edge going out, along ([outX], [outY]): the line to its
 * start, then three curves, the middle one round.
 */
private fun corner(path: Path, cx: Float, cy: Float, inX: Float, inY: Float, outX: Float, outY: Float, radius: Float) {
    fun x(along: Float, across: Float) = cx + (along * inX + across * outX) * radius
    fun y(along: Float, across: Float) = cy + (along * inY + across * outY) * radius
    path.lineTo(x(CURVE_REACH, 0f), y(CURVE_REACH, 0f))
    path.cubicTo(x(1.08849323f, 0f), y(1.08849323f, 0f), x(0.86840689f, 0f), y(0.86840689f, 0f), x(0.66993427f, 0.06549600f), y(0.66993427f, 0.06549600f))
    path.lineTo(x(0.63149399f, 0.07491100f), y(0.63149399f, 0.07491100f))
    path.cubicTo(x(0.37282392f, 0.16905899f), y(0.37282392f, 0.16905899f), x(0.16905899f, 0.37282392f), y(0.16905899f, 0.37282392f), x(0.07491100f, 0.63149399f), y(0.07491100f, 0.63149399f))
    path.lineTo(x(0.06549600f, 0.66993427f), y(0.06549600f, 0.66993427f))
    path.cubicTo(x(0f, 0.86840689f), y(0f, 0.86840689f), x(0f, 1.08849323f), y(0f, 1.08849323f), x(0f, CURVE_REACH), y(0f, CURVE_REACH))
}
