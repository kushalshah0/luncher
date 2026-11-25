package com.luncher.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The dock's idle screen (NETTV fork), as a desktop's lock screen: a dark, blurred backdrop over
 * the whole screen and, in the middle, the time, hours, minutes and seconds, with the date under
 * it; nothing else. 24-hour or 12-hour time as the device says.
 *
 * The backdrop is a picture blurred and darkened ahead of time (`home_idle_backdrop`: 128 x 72,
 * grey, under 1 KB, stretched with filtering): the TV blurs nothing, and it costs about 37 KB of
 * memory, decoded the first time the screen shows.
 *
 * It ticks once a second, on the second, only while [start]ed.
 */
internal class IdleClockView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private var backdrop: Bitmap? = null

    private val timePaint = text("sans-serif-light", TIME_DP, Color.WHITE)
    private val datePaint = text("sans-serif", DATE_DP, Color.argb(235, 255, 255, 255))

    private val now = Date()
    private var timeFormat: SimpleDateFormat? = null
    private var timeIs24Hour = false
    private val dateFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), Locale.getDefault())

    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    private fun text(family: String, sizeDp: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        typeface = Typeface.create(family, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sizeDp, resources.displayMetrics)
        setShadowLayer(8 * density, 0f, 2 * density, Color.argb(90, 0, 0, 0))
    }

    /** Shows the time now and ticks every second until [stop]. */
    fun start() {
        if (ticking) return
        ticking = true
        if (backdrop == null) backdrop = BitmapFactory.decodeResource(resources, R.drawable.home_idle_backdrop)
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
        val backdrop = backdrop
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        if (backdrop != null) canvas.drawBitmap(backdrop, null, bounds, backdropPaint) else canvas.drawColor(Color.rgb(14, 14, 14))

        now.time = System.currentTimeMillis()
        val middle = width / 2f
        val timeBaseline = height * TIME_BASELINE
        canvas.drawText(timeFormat().format(now), middle, timeBaseline, timePaint)
        canvas.drawText(dateFormat.format(now), middle, timeBaseline + DATE_BELOW_DP * density, datePaint)
    }

    /** Hours, minutes and seconds, in the device's hour format, made again only when that changes. */
    private fun timeFormat(): SimpleDateFormat {
        val is24Hour = DateFormat.is24HourFormat(context)
        val format = timeFormat
        if (format != null && is24Hour == timeIs24Hour) return format
        timeIs24Hour = is24Hour
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (is24Hour) "Hmmss" else "hmmssa")
        return SimpleDateFormat(pattern, Locale.getDefault()).also { timeFormat = it }
    }

    private companion object {
        const val TIME_DP = 88f
        const val DATE_DP = 24f

        /** The time's baseline, as a part of the screen's height from the top: a little above the middle. */
        const val TIME_BASELINE = 0.47f

        /** From the time's baseline to the date's. */
        const val DATE_BELOW_DP = 60f
    }
}
