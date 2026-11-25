package com.luncher.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import com.luncher.domain.apps.HomeApp
import com.luncher.domain.apps.LaunchableApp

/**
 * The dock's round icons (NETTV fork): each app's launcher icon drawn once into a circle of the
 * size the dock shows it at its largest, then kept, so drawing the dock is a bitmap copy per icon.
 *
 * - An adaptive icon (API 26+) fills the circle, its background and foreground layers, as a
 *   launcher's round mask shows them.
 * - A square icon (opaque at its corners) fills the circle too, its corners cut.
 * - Any other (a round or shaped icon, see-through around it) sits on a light disc, smaller, as the
 *   dock of a Mac shows such icons.
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

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC }
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
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val icon = load(ComponentName(app.packageName, app.activityName))
        if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
            // The layers are 108 dp, of which a launcher's mask shows the middle 72: that middle fills the circle.
            val layer = size * ADAPTIVE_LAYER
            val inset = (layer - size) / 2
            for (part in listOf(icon.background, icon.foreground)) {
                part ?: continue
                part.setBounds(-inset.toInt(), -inset.toInt(), (size + inset).toInt(), (size + inset).toInt())
                part.draw(canvas)
            }
        } else {
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            if (!opaqueCorners(bitmap)) {
                // A shaped icon: on a light disc, smaller, so its shape shows whole.
                bitmap.eraseColor(Color.TRANSPARENT)
                canvas.drawCircle(size / 2f, size / 2f, size / 2f, discPaint)
                val inset = (size * (1 - SHAPED_ICON) / 2).toInt()
                icon.setBounds(inset, inset, size - inset, size - inset)
                icon.draw(canvas)
            }
        }
        // Cut to the circle, its edge smooth, with a faint light rim, as glass catching the light.
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, maskPaint)
        rimPaint.strokeWidth = (size / RIM_PER_SIZE).coerceAtLeast(1f)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - rimPaint.strokeWidth / 2, rimPaint)
        return bitmap
    }

    /** Whether the icon drawn into [bitmap] fills its corners: a square icon, which the circle can cut. */
    private fun opaqueCorners(bitmap: Bitmap): Boolean {
        val near = (bitmap.width * CORNER_PROBE).toInt()
        val far = bitmap.width - 1 - near
        return intArrayOf(near, far).all { x -> intArrayOf(near, far).all { y -> Color.alpha(bitmap.getPixel(x, y)) == 255 } }
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

        /** A shaped icon's size on its disc, as a part of the circle's. */
        const val SHAPED_ICON = 0.74f

        /** Where the corners are probed, as a part of the size in from each edge: outside the circle. */
        const val CORNER_PROBE = 0.08f

        /** The disc behind a shaped icon: near white, as a Mac's. */
        val DISC = Color.rgb(242, 242, 247)

        const val RIM_ALPHA = 70

        /** The rim's width: the size over this, a pixel at least. */
        const val RIM_PER_SIZE = 64f
    }
}
