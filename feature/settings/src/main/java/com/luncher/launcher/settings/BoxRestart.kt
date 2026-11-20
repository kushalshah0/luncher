package com.luncher.launcher.settings

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.luncher.launcher.ui.R as UiR
import java.io.File

/**
 * The settings panel's Restart (NETTV fork). Android lets no app restart the box, but its root
 * script (/system/bin/remote-keys.sh) does: it watches the app's files folder each second, as for
 * the RAM pill's clear-request, and restarts the box when [REQUEST] appears.
 *
 * OK on the entry opens a small modal over the dimmed screen, white like the home screen's Wi-Fi
 * and VPN menus: "Restart now?" with a restart icon, Cancel (focused, so a stray OK restarts nothing)
 * and Restart. Restart leaves the
 * request and fades in "Restarting…" over everything. If the box is still up after [TIMEOUT_MS],
 * its script isn't running: the request goes, so it can't restart the box out of the blue later.
 */
internal class BoxRestart(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val request = File(activity.getExternalFilesDir(null), REQUEST)
    private val density = activity.resources.displayMetrics.density

    /** The modal asking to restart, over the dimmed screen. */
    fun confirm() {
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val cancel = button(R.string.settings_restart_cancel) { dialog.dismiss() }
        val restart = button(R.string.settings_restart) {
            dialog.dismiss()
            restart()
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.WHITE) }
            elevation = dp(24).toFloat()
            setPadding(dp(22), dp(22), dp(22), dp(20))
            // The title, with the restart icon at its start.
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(ImageView(activity).apply {
                    setImageResource(UiR.drawable.icon_reset)
                    imageTintList = ColorStateList.valueOf(INK)
                }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(TextView(activity).apply {
                    setText(R.string.settings_restart_title)
                    textSize = 18f
                    setTextColor(INK)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(10), 0, 0, 0)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(22), 0, 0)
                addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(restart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(card, ViewGroup.LayoutParams(dp(WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))   // the card's own rounded white
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(DIM)
        }
        dialog.show()
        cancel.requestFocus()
    }

    private fun restart() {
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

    /** A pill button: light grey, graphite with white text while focused. */
    private fun button(label: Int, onClick: () -> Unit) = TextView(activity).apply {
        setText(label)
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER   // else the theme's start alignment wins
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.WHITE, INK)))
        minWidth = dp(BUTTON_MIN_WIDTH_DP)
        minHeight = dp(BUTTON_HEIGHT_DP)
        setPadding(dp(20), 0, dp(20), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(INK))
            addState(intArrayOf(), pill(FIELD))
        }
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(BUTTON_HEIGHT_DP / 2).toFloat(); setColor(color) }

    /** Black over the whole screen, "Restarting…" and a spinner fading in. */
    private fun restarting() = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
            addView(ProgressBar(activity).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Color.WHITE)   // not the theme's accent
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(activity).apply {
                setText(R.string.settings_restarting)
                textAlignment = View.TEXT_ALIGNMENT_CENTER   // else the theme's start alignment wins
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setPadding(0, dp(20), 0, 0)
            })
        }
        setContentView(content)
        setCancelable(false)
        show()
        content.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        /** In the app's external files folder, which the box's root script watches. */
        const val REQUEST = "restart-request"
        const val TIMEOUT_MS = 15_000L
        const val FADE_MS = 400L
        const val WIDTH_DP = 300
        /** How dark the screen goes behind the modal. */
        const val DIM = 0.6f
        const val BUTTON_HEIGHT_DP = 40
        const val BUTTON_MIN_WIDTH_DP = 104

        // Mono graphite, as the home screen's pill menus (PillMenu, in :feature:home).
        const val INK = 0xFF1C1C1E.toInt()
        const val FIELD = 0xFFF2F2F7.toInt()
    }
}
