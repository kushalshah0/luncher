package com.luncher.launcher.settings

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * The settings panel's Restart (NETTV fork). Android lets no app restart the box, but its root
 * script (/system/bin/remote-keys.sh) does: it watches the app's files folder each second, as for
 * the RAM pill's clear-request, and restarts the box when [REQUEST] appears.
 *
 * The first OK asks for a second, in the entry's title, for [CONFIRM_MS]; the second leaves the
 * request and fades in "Restarting…" over everything. If the box is still up after [TIMEOUT_MS],
 * its script isn't running: the request goes, so it can't restart the box out of the blue later.
 */
internal class BoxRestart(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val request = File(activity.getExternalFilesDir(null), REQUEST)
    private var armed = false

    fun press(title: TextView) {
        if (!armed) {
            armed = true
            title.setText(R.string.settings_restart_confirm)
            handler.postDelayed({ armed = false; title.setText(R.string.settings_restart) }, CONFIRM_MS)
            return
        }
        armed = false
        handler.removeCallbacksAndMessages(null)
        title.setText(R.string.settings_restart)
        if (!runCatching { request.createNewFile() || request.exists() }.getOrDefault(false)) {
            failed()
            return
        }
        val restarting = restarting()
        handler.postDelayed({
            request.delete()
            restarting.dismiss()
            failed()
        }, TIMEOUT_MS)
    }

    private fun failed() = Toast.makeText(activity, R.string.settings_restart_failed, Toast.LENGTH_LONG).show()

    /** Black over the whole screen, "Restarting…" and a spinner fading in. */
    private fun restarting() = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
            addView(ProgressBar(activity).apply { isIndeterminate = true })
            addView(TextView(activity).apply {
                setText(R.string.settings_restarting)
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setPadding(0, (16 * activity.resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
        setContentView(content)
        setCancelable(false)
        show()
        content.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    companion object {
        /** In the app's external files folder, which the box's root script watches. */
        const val REQUEST = "restart-request"
        const val CONFIRM_MS = 3_000L
        const val TIMEOUT_MS = 15_000L
        const val FADE_MS = 400L
    }
}
