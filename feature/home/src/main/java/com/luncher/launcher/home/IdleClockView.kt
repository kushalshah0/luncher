package com.luncher.launcher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.luncher.launcher.ui.TileFrost
import java.util.Calendar

/**
 * The dock's idle screen (NETTV fork): the wallpaper's frosted copy over the whole screen, a little
 * darker, and the time in one line in the middle, hours, minutes and seconds, nothing else. 24-hour
 * or 12-hour as the device says, without AM/PM. Without a wallpaper, a dark screen.
 *
 * It ticks once a second, on the second, only while [start]ed: the home screen starts it as it
 * shows it and stops it as it hides it. A tick draws a bitmap and a line of text: nothing is
 * allocated but the line's characters.
 */
internal class IdleClockView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** The wallpaper's frosted copy, the backdrop; none without a wallpaper. */
    var frost: TileFrost? = null
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    private val frostPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint().apply { color = Color.argb(TINT_ALPHA, 0, 0, 0) }
    private val bounds = RectF()
    private val calendar = Calendar.getInstance()
    private val chars = CharArray(8)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_DP, resources.displayMetrics)
        letterSpacing = 0.02f
        setShadowLayer(6 * resources.displayMetrics.density, 0f, 2 * resources.displayMetrics.density, Color.argb(70, 0, 0, 0))
    }

    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            val now = System.currentTimeMillis()
            postAtTime(this, SystemClock.uptimeMillis() + (1000 - now % 1000))
        }
    }

    /** Shows the time now and ticks every second until [stop]. */
    fun start() {
        if (ticking) return
        ticking = true
        removeCallbacks(tick)
        tick.run()
    }

    fun stop() {
        ticking = false
        removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val frost = frost
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        if (frost != null) canvas.drawBitmap(frost.image, null, bounds, frostPaint) else canvas.drawColor(Color.rgb(16, 16, 18))
        canvas.drawRect(bounds, tintPaint)

        calendar.timeInMillis = System.currentTimeMillis()
        val hours24 = DateFormat.is24HourFormat(context)
        val hour = if (hours24) calendar.get(Calendar.HOUR_OF_DAY) else calendar.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val count = format(hour, calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND), hours24)
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(chars, chars.size - count, count, width / 2f, baseline, textPaint)
    }

    /** "HH:MM:SS" at the end of [chars], the hour without a leading zero in 12-hour time; its length. */
    private fun format(hour: Int, minute: Int, second: Int, hours24: Boolean): Int {
        chars[7] = '0' + second % 10
        chars[6] = '0' + second / 10
        chars[5] = ':'
        chars[4] = '0' + minute % 10
        chars[3] = '0' + minute / 10
        chars[2] = ':'
        chars[1] = '0' + hour % 10
        chars[0] = '0' + hour / 10
        return if (hour < 10 && !hours24) 7 else 8
    }

    private companion object {
        const val TEXT_DP = 120f
        const val TINT_ALPHA = 70
    }
}
