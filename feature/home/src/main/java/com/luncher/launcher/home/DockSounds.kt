package com.luncher.launcher.home

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import kotlin.random.Random

/**
 * The dock's tick (NETTV fork): a short, soft click as the focus moves from one icon to the next,
 * in the character of a media center's cursor sound: a 2 ms tap near 4.6 kHz over a faint 1.6 kHz ring. Original, synthesised: 31 ms, 1.5 KB
 * (`res/raw/dock_tick.wav`).
 *
 * A [SoundPool] holding the one clip, decoded once: playing it is a call to the mixer, nothing
 * decoded or allocated. Made by [start], only while the home screen shows the dock with its sounds
 * on, and released by [stop], so the tiles' mode, or the home screen hidden, holds no audio at all.
 *
 * Held keys repeat fast: a tick at most every [MIN_GAP_MS], so a held arrow is a light patter, not
 * a buzz. Each tick's pitch is a hair off the last ([PITCH_SPREAD]), so a run doesn't sound
 * mechanical. Its volume follows the TV's system sounds (sonification).
 */
internal class DockSounds(private val context: Context) {

    private var pool: SoundPool? = null
    private var tick = 0
    private var loaded = false
    private var lastTick = 0L

    /** Loads the tick, if it isn't. */
    fun start() {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
        pool.setOnLoadCompleteListener { _, id, status -> if (id == tick && status == 0) loaded = true }
        tick = pool.load(context, R.raw.dock_tick, 1)
        this.pool = pool
    }

    /** Releases the tick and its player. */
    fun stop() {
        pool?.release()
        pool = null
        loaded = false
    }

    /** One tick, unless one played a moment ago, or it isn't loaded yet. */
    fun tick() {
        val pool = pool ?: return
        if (!loaded) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < MIN_GAP_MS) return
        lastTick = now
        val rate = 1f + (Random.nextFloat() * 2 - 1) * PITCH_SPREAD
        pool.play(tick, VOLUME, VOLUME, 1, 0, rate)
    }

    private companion object {
        const val MIN_GAP_MS = 60L
        const val PITCH_SPREAD = 0.03f
        const val VOLUME = 1f
    }
}
