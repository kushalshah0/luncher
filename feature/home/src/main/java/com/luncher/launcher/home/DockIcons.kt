package com.luncher.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import com.luncher.domain.apps.HomeApp
import com.luncher.domain.apps.LaunchableApp

/**
 * The dock's round icons (NETTV fork): each app's launcher icon drawn once into a full circle of
 * the size the dock shows it at its largest, then kept, so drawing the dock is a bitmap copy per
 * icon. Every icon is a whole circle, filled edge to edge:
 *
 * - An adaptive icon (API 26+): its background and foreground layers, as a round mask shows them.
 * - An icon with a shape of its own (square, rounded square, circle), its see-through margin
 *   dropped: the shape scaled to fill the circle, and cut to it.
 * - A logo with no background: on a plate that fills the circle, light, or graphite under a light
 *   logo ([drawLegacy]).
 *
 * Every one gets the same finish, a light from the top and a faint rim, so the row reads as a set.
 *
 * The icon is loaded at the highest density the app has, so a large dock icon isn't blurred from a
 * small one. Only the docked apps' icons are kept; [keepOnly] frees the others, and a new [size]
 * all of them. Another app's resources are outside Luncher's control: one that fails is the
 * device's default icon.
 */
internal class DockIcons(context: Context) {

    private val packageManager = context.packageManager

    private val icons = HashMap<LaunchableApp, Bitmap>()

    /** The circle's diameter, in px, every kept icon is drawn at; another frees them all. */
    var size: Int = 0
        set(value) {
            if (field == value) return
            field = value
            icons.clear()
        }

    /** Fills the circle with the icon drawn square: the circle cut, its edge smooth, on every Android version. */
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(RIM_ALPHA, 255, 255, 255)
    }

    /** [app]'s round icon at [size], drawn the first time it's asked for. */
    fun icon(app: HomeApp): Bitmap = icons.getOrPut(app.launchable) { draw(app.launchable) }

    /** Frees the icons of apps no longer in the dock. */
    fun keepOnly(apps: List<HomeApp>) {
        val kept = apps.mapTo(HashSet()) { it.launchable }
        icons.keys.retainAll(kept)
    }

    private fun draw(app: LaunchableApp): Bitmap {
        val size = size.coerceAtLeast(1)
        // The icon drawn square first, then the circle filled with it: a blend that erases outside
        // a drawn circle (DST_IN) leaves the corners on Android 7's software canvas, which applies
        // it only where the circle covers.
        val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        val icon = load(ComponentName(app.packageName, app.activityName))
        if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
            // The layers are 108 dp, of which a launcher's mask shows the middle 72: that middle fills the circle.
            val layer = size * ADAPTIVE_LAYER
            val inset = ((layer - size) / 2).toInt()
            for (part in listOf(icon.background, icon.foreground)) {
                part ?: continue
                part.setBounds(-inset, -inset, size + inset, size + inset)
                part.draw(canvas)
            }
        } else {
            drawLegacy(icon, canvas, size)
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val round = Canvas(bitmap)
        cutPaint.shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        round.drawCircle(size / 2f, size / 2f, size / 2f, cutPaint)
        cutPaint.shader = null
        square.recycle()
        // The same finish on every icon: a light from the top, and a faint rim, as glass catching it.
        glossPaint.shader = LinearGradient(0f, 0f, 0f, size * GLOSS_DEPTH, Color.argb(GLOSS_ALPHA, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        round.drawCircle(size / 2f, size / 2f, size / 2f, glossPaint)
        rimPaint.strokeWidth = (size / RIM_PER_SIZE).coerceAtLeast(1f)
        round.drawCircle(size / 2f, size / 2f, size / 2f - rimPaint.strokeWidth / 2, rimPaint)
        return bitmap
    }

    /**
     * An icon that isn't adaptive, always filling the circle: drawn once on its own, its
     * see-through margin found, then either its shape scaled to fill the circle (a square, a
     * rounded square, a circle: whatever covers the middle of each edge, so the circle cut leaves
     * no gap), or, for a logo with no background of its own, the logo on a plate filling the
     * circle: light, or graphite under a light logo, so it never vanishes.
     */
    private fun drawLegacy(icon: Drawable, canvas: Canvas, size: Int) {
        val scratch = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(scratch))
        val pixels = IntArray(size * size)
        scratch.getPixels(pixels, 0, size, 0, 0, size, size)
        val bounds = visibleBounds(pixels, size)
        if (bounds == null) {
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, platePaint.apply { color = LIGHT_PLATE })
            scratch.recycle()
            return
        }
        val target = RectF()
        if (fillsItsShape(pixels, size, bounds)) {
            // Its shape, a little larger than the circle, so the cut is inside its edge everywhere.
            val grow = size * (FILL_OVERSCALE - 1) / 2
            target.set(-grow, -grow, size + grow, size + grow)
        } else {
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, platePaint.apply { color = if (isLight(pixels)) DARK_PLATE else LIGHT_PLATE })
            // The logo whole, its longer side a part of the circle, in the middle.
            val longer = maxOf(bounds.width(), bounds.height()).toFloat()
            val scale = size * LOGO / longer
            val w = bounds.width() * scale
            val h = bounds.height() * scale
            target.set((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
        }
        canvas.drawBitmap(scratch, bounds, target, iconPaint)
        scratch.recycle()
    }

    /** The smallest rectangle holding every pixel that shows; null for an icon that's all see-through. */
    private fun visibleBounds(pixels: IntArray, size: Int): Rect? {
        var left = size
        var top = size
        var right = -1
        var bottom = -1
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (pixels[y * size + x] ushr 24 < VISIBLE) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
    }

    /** Whether the icon covers the middle of each edge of its [bounds], and its middle: a shape the circle can be cut from. */
    private fun fillsItsShape(pixels: IntArray, size: Int, bounds: Rect): Boolean {
        // Not square enough to fill a circle without stretching it: a wide or tall logo goes on a plate.
        val ratio = bounds.width().toFloat() / bounds.height()
        if (ratio > SQUARE_ENOUGH || ratio < 1 / SQUARE_ENOUGH) return false
        val insetX = (bounds.width() * EDGE_PROBE).toInt()
        val insetY = (bounds.height() * EDGE_PROBE).toInt()
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val probes = arrayOf(
            cx to bounds.top + insetY, cx to bounds.bottom - 1 - insetY,
            bounds.left + insetX to cy, bounds.right - 1 - insetX to cy, cx to cy,
        )
        return probes.all { (x, y) -> pixels[y * size + x] ushr 24 >= OPAQUE }
    }

    /** Whether the icon's visible pixels are light on average: a white logo, which a light plate would hide. */
    private fun isLight(pixels: IntArray): Boolean {
        var sum = 0.0
        var count = 0
        for (pixel in pixels) {
            if (pixel ushr 24 < VISIBLE) continue
            sum += 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
            count++
        }
        return count > 0 && sum / count > LIGHT_LOGO
    }

    /** [component]'s icon at the highest density the app has it, else as Android gives it, else the default. */
    private fun load(component: ComponentName): Drawable {
        try {
            val activity = packageManager.getActivityInfo(component, 0)
            val id = activity.iconResource
            if (id != 0) {
                val resources = packageManager.getResourcesForApplication(activity.applicationInfo)
                resources.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)?.let { return it }
            }
            return packageManager.getActivityIcon(component)
        } catch (e: PackageManager.NameNotFoundException) {
            return packageManager.defaultActivityIcon
        } catch (e: RuntimeException) {
            return packageManager.defaultActivityIcon
        }
    }

    private companion object {
        /** An adaptive icon's layer, as a part of what its mask shows: 108 dp for 72. */
        const val ADAPTIVE_LAYER = 1.5f

        /** A shape that fills the circle, a little larger than it, so the cut never shows its edge. */
        const val FILL_OVERSCALE = 1.06f

        /** A logo on its plate: its longer side, as a part of the circle's. */
        const val LOGO = 0.68f

        /** Where the edges' middles are probed, as a part of the visible size in from each edge. */
        const val EDGE_PROBE = 0.04f

        /** How far from square a shape may be and still fill the circle. */
        const val SQUARE_ENOUGH = 1.12f

        /** A pixel's alpha that shows, and one that's solid. */
        const val VISIBLE = 24
        const val OPAQUE = 230

        /** A logo lighter than this on average (0 to 255) goes on the dark plate. */
        const val LIGHT_LOGO = 190.0

        /** The plates: near white, as a Mac's, and graphite. */
        val LIGHT_PLATE = Color.rgb(242, 242, 247)
        val DARK_PLATE = Color.rgb(44, 44, 46)

        /** The light from the top: how strong, and how far down the circle it fades out. */
        const val GLOSS_ALPHA = 46
        const val GLOSS_DEPTH = 0.55f

        const val RIM_ALPHA = 70

        /** The rim's width: the size over this, a pixel at least. */
        const val RIM_PER_SIZE = 64f
    }
}
