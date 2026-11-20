package com.luncher.launcher.home

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * A status pill's dropdown (NETTV fork): a white rounded panel below the pill, graphite text, the
 * focused row inverted (graphite, white text). Its first row may be a [header]: the title, a status
 * line and an on/off switch. As wide as its rows, within [MIN_WIDTH_DP]..[MAX_WIDTH_DP]. The Wi-Fi
 * picker's and the VPN menu's.
 */
internal class PillMenu(private val activity: Activity) {

    private val density = activity.resources.displayMetrics.density
    private val text = focusedOr(Color.WHITE, INK)
    private val secondary = focusedOr(0xB3FFFFFF.toInt(), GREY)
    private val green = focusedOr(BRIGHT_GREEN, GREEN)
    private var popup: PopupWindow? = null

    /** Shows [rows] below [anchor], [focus] focused (else the first that can be), in place of what this menu showed. */
    fun show(anchor: View, rows: List<View>, focus: View? = null) {
        popup?.dismiss()
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach(list::addView)
        val panel = ScrollView(activity).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        // As wide as the rows want, at most MAX_WIDTH_DP: a row's title, 0 wide with a weight, is
        // measured as wide as its text when the width isn't exact.
        panel.measure(View.MeasureSpec.makeMeasureSpec(dp(MAX_WIDTH_DP), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val width = panel.measuredWidth.coerceIn(dp(MIN_WIDTH_DP), dp(MAX_WIDTH_DP))
        popup = PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            // The panel's shape as the window's background: its outline casts the shadow, and a
            // background lets Back and a touch outside close it.
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), RIM)
            })
            elevation = dp(12).toFloat()
            showAsDropDown(anchor, 0, dp(10), Gravity.START)
        }
        (focus ?: rows.firstOrNull { it.isFocusable })?.requestFocus()
    }

    /** A row: [icon], [title], and at its end [status], green if [statusGreen]. OK closes the menu, then runs [onClick]. */
    fun row(icon: Int, title: String, status: String?, statusGreen: Boolean = false, onClick: () -> Unit): View =
        focusableRow(onClick).apply {
            addView(iconView(icon), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
            addView(label(title, TEXT_SP, text).apply { setPadding(dp(12), 0, dp(10), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (status != null) addView(label(status, SMALL_SP, if (statusGreen) green else secondary))
        }

    /**
     * The menu's first row: [title], medium weight, [status] under it if any, and a switch showing [on].
     * OK closes the menu, then runs [onToggle].
     */
    fun header(title: String, status: String?, on: Boolean, onToggle: () -> Unit): View =
        focusableRow(onToggle).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                isDuplicateParentStateEnabled = true
                addView(label(title, TITLE_SP, text).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                if (status != null) addView(label(status, SMALL_SP, secondary).apply { setPadding(0, dp(1), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
            addView(switch(on), LinearLayout.LayoutParams(dp(SWITCH_WIDTH_DP), dp(SWITCH_HEIGHT_DP)))
        }

    /**
     * Two buttons side by side: a wide one, [icon] and [title], and a square one at its end, only
     * [sideIcon]. Left and Right move between them; OK closes the menu, then runs theirs.
     */
    fun splitRow(icon: Int, title: String, onClick: () -> Unit, sideIcon: Int, onSide: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(focusableRow(onClick).apply {
                addView(iconView(icon), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
                addView(label(title, TEXT_SP, text).apply { setPadding(dp(12), 0, 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(focusableRow(onSide).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                addView(iconView(sideIcon), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
            }, LinearLayout.LayoutParams(dp(ROW_HEIGHT_DP), dp(ROW_HEIGHT_DP)).apply { leftMargin = dp(4) })
        }

    /** A line of grey text, not focusable: e.g. why there's nothing to list. */
    fun note(text: String): View = label(text, TEXT_SP, ColorStateList.valueOf(GREY)).apply {
        setSingleLine(false)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    /**
     * A line across the menu. As wide as it's told to be, else none: a plain View asks for all the
     * width it may have, which would make every menu MAX_WIDTH_DP wide.
     */
    fun divider(): View = object : View(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val exact = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
            setMeasuredDimension(if (exact) MeasureSpec.getSize(widthMeasureSpec) else 0, MeasureSpec.getSize(heightMeasureSpec))
        }
    }.apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(dp(12), dp(6), dp(12), dp(6))
        }
    }

    private fun focusableRow(onClick: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = dp(ROW_HEIGHT_DP)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = focusedOr(rounded(INK, 12), ColorDrawable(Color.TRANSPARENT))
        setOnClickListener { popup?.dismiss(); onClick() }
    }

    /**
     * A small switch, its knob at the end while [on]: graphite while on, light grey while off; on a
     * focused (graphite) row, white while on, a dark grey while off.
     */
    private fun switch(on: Boolean) = FrameLayout(activity).apply {
        isDuplicateParentStateEnabled = true
        background = if (on) focusedOr(pill(Color.WHITE), pill(INK)) else focusedOr(pill(TRACK_OFF_FOCUSED), pill(TRACK_OFF))
        val knob = dp(SWITCH_HEIGHT_DP - 2 * KNOB_INSET_DP)
        addView(View(activity).apply {
            isDuplicateParentStateEnabled = true
            background = if (on) focusedOr(circle(INK), circle(Color.WHITE)) else circle(Color.WHITE)
        }, FrameLayout.LayoutParams(knob, knob, (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL).apply {
            setMargins(dp(KNOB_INSET_DP), 0, dp(KNOB_INSET_DP), 0)
        })
    }

    private fun iconView(resource: Int) = ImageView(activity).apply {
        setImageResource(resource)
        imageTintList = text
        isDuplicateParentStateEnabled = true
    }

    private fun label(text: String, size: Float, color: ColorStateList) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        isDuplicateParentStateEnabled = true
    }

    private fun focusedOr(focused: Int, normal: Int) =
        ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(focused, normal))

    private fun focusedOr(focused: Drawable, normal: Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), normal)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(color) }

    private fun pill(color: Int) = rounded(color, SWITCH_HEIGHT_DP)

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val MIN_WIDTH_DP = 220
        const val MAX_WIDTH_DP = 280
        const val ROW_HEIGHT_DP = 42
        const val ICON_DP = 18
        const val TITLE_SP = 16f
        const val TEXT_SP = 15f
        const val SMALL_SP = 12f
        const val SWITCH_WIDTH_DP = 34
        const val SWITCH_HEIGHT_DP = 20
        const val KNOB_INSET_DP = 3

        // Mono graphite.
        const val INK = 0xFF1C1C1E.toInt()
        const val GREY = 0xFF8E8E93.toInt()
        const val DIVIDER = 0xFFE5E5EA.toInt()
        const val RIM = 0x14000000
        const val TRACK_OFF = 0xFFE5E5EA.toInt()
        const val TRACK_OFF_FOCUSED = 0xFF48484A.toInt()
        const val GREEN = 0xFF1E8E3E.toInt()
        const val BRIGHT_GREEN = 0xFF34C759.toInt()
    }
}
