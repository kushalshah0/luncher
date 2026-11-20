package com.luncher.launcher.home

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * A status pill's dropdown (NETTV fork): a dark rounded panel attached below the pill, a list of
 * rows; the focused row is white with black text. The Wi-Fi picker's and the VPN menu's.
 */
internal class PillMenu(private val activity: Activity) {

    private val density = activity.resources.displayMetrics.density
    private val text = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE))
    private var popup: PopupWindow? = null

    /** Shows [rows] below [anchor], the first focused, in place of what this menu showed. */
    fun show(anchor: View, rows: List<View>) {
        popup?.dismiss()
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach(list::addView)
        val panel = ScrollView(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xF2202124.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        popup = PopupWindow(panel, dp(PANEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))   // so Back and a touch outside close it
            elevation = dp(8).toFloat()
            showAsDropDown(anchor, 0, dp(10), Gravity.START)
        }
        list.getChildAt(0)?.requestFocus()
    }

    /** A row: [icon], [title], and at its end [status], green if [statusGreen]. OK closes the menu, then runs [onClick]. */
    fun row(icon: Int, title: String, status: String?, statusGreen: Boolean = false, onClick: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) })
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnClickListener { popup?.dismiss(); onClick() }
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = this@PillMenu.text
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(TextView(activity).apply {
                this.text = title
                setTextColor(this@PillMenu.text)
                textSize = 17f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                isDuplicateParentStateEnabled = true
                setPadding(dp(16), 0, dp(12), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (status != null) addView(TextView(activity).apply {
                this.text = status
                textSize = 13f
                setTextColor(if (statusGreen) 0xFF34C759.toInt() else 0x99FFFFFF.toInt())
            })
        }

    fun divider(): View = View(activity).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(dp(16), dp(8), dp(16), dp(8))
        }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val PANEL_WIDTH_DP = 360
    }
}
