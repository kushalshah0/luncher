package com.luncher.launcher.home

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import kotlin.random.Random

/**
 * The dock's tick (NETTV fork): a short, soft click as the focus moves from one icon to the next,
 * in the character of a media center's cursor sound: a 2 ms tap near 4.6 kHz over a faint 1.6 kHz ring. Original, synthesised: 31 ms, at the TV's
 * own 48 kHz, in three pitches (`res/raw/dock_tick*.wav`, 3 KB each).
 *
 * A [SoundPool] holding the one clip, decoded once: playing it is a call to the mixer, nothing
 * decoded or allocated. Made by [start], only while the home screen shows the dock with its sounds
 * on, and released by [stop], so the tiles' mode, or the home screen hidden, holds no audio at all.
 *
 * Held keys repeat fast: a tick at most every [MIN_GAP_MS], so a held arrow is a light patter, not
 * a buzz. Each tick is one of the three pitches at random, so a run doesn't sound
 * mechanical: baked in, not by the player's rate, and at the output's own sample rate, so Android
 * plays it on its low-latency path, without resampling. Its volume follows the TV's system sounds (sonification).
 */
internal class DockSounds(private val context: Context) {

    private var pool: SoundPool? = null
    private var ticks = IntArray(0)
    private var loaded = 0
    private var lastTick = 0L

    /** Loads the tick, if it isn't. */
    fun start() {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
        pool.setOnLoadCompleteListener { _, _, status -> if (status == 0) loaded++ }
        ticks = intArrayOf(
            pool.load(context, R.raw.dock_tick_low, 1),
            pool.load(context, R.raw.dock_tick, 1),
            pool.load(context, R.raw.dock_tick_high, 1),
        )
        this.pool = pool
    }

    /** Releases the tick and its player. */
    fun stop() {
        pool?.release()
        pool = null
        loaded = 0
    }

    /** One tick, unless one played a moment ago, or it isn't loaded yet. */
    fun tick() {
        val pool = pool ?: return
        if (loaded < ticks.size) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < MIN_GAP_MS) return
        lastTick = now
        pool.play(ticks[Random.nextInt(ticks.size)], VOLUME, VOLUME, 1, 0, 1f)
    }

    private companion object {
        const val MIN_GAP_MS = 60L
        const val VOLUME = 1f
    }
}
