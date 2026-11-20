package com.luncher.launcher.home

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.luncher.launcher.ui.R as UiR

/**
 * A status pill's dropdown (NETTV fork): a white rounded panel below the pill, graphite text, the
 * focused row inverted (graphite, white text). Its first row may be a [header]: the title, a status
 * line and an on/off switch. As wide as its rows, within [MIN_WIDTH_DP]..[MAX_WIDTH_DP]. The Wi-Fi
 * picker's and the VPN menu's.
 *
 * It shows pages: [show] while it's open puts the new rows in place of the old, in the same window.
 * A page with a way back ([show]'s onBack, e.g. Add network's) goes back on Back too: Android's
 * popup closes on Back before its rows see the key, so the menu opens again, on that page.
 */
internal class PillMenu(private val activity: Activity) {

    private val density = activity.resources.displayMetrics.density
    private val text = focusedOr(Color.WHITE, INK)
    private val secondary = focusedOr(0xB3FFFFFF.toInt(), GREY)
    private val green = focusedOr(BRIGHT_GREEN, GREEN)
    private var popup: PopupWindow? = null
    private var list: LinearLayout? = null
    private var onBack: (() -> Unit)? = null
    /** Closed by a row (or [close]), not by Back or a touch outside: nothing to go back to. */
    private var leaving = false

    val isShowing: Boolean get() = popup?.isShowing == true

    /**
     * Shows [rows] below [anchor], [focus] focused (else the first that can be). While open, in place
     * of what it showed, as wide as it was if [onBack] is given (a page of the menu), else as wide
     * as the rows want. [onBack]: what Back does on this page.
     */
    fun show(anchor: View, rows: List<View>, focus: View? = null, onBack: (() -> Unit)? = null) {
        this.onBack = onBack
        val open = popup?.takeIf { it.isShowing }
        val list = if (open != null) list!!.apply { removeAllViews() } else LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach(list::addView)
        if (open != null) {
            val panel = list.parent as View
            val width = if (onBack != null) open.width else measuredWidth(panel)
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            open.update(anchor, 0, dp(10), width, panel.measuredHeight)
        } else {
            val panel = ScrollView(activity).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isVerticalScrollBarEnabled = false
                addView(list)
            }
            this.list = list
            popup = PopupWindow(panel, measuredWidth(panel), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                // The panel's shape as the window's background: its outline casts the shadow, and a
                // background lets Back and a touch outside close it.
                setBackgroundDrawable(GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), RIM)
                })
                elevation = dp(12).toFloat()
                setOnDismissListener {
                    val back = if (leaving) null else this@PillMenu.onBack
                    popup = null
                    leaving = false
                    back?.invoke()
                }
                showAsDropDown(anchor, 0, dp(10), Gravity.START)
            }
        }
        (focus ?: rows.firstOrNull { it.isFocusable })?.requestFocus()
    }

    /** Closes the menu: nothing to go back to. */
    fun close() {
        leaving = true
        popup?.dismiss()
    }

    /**
     * A row: [icon], [title], and at its end [status], green if [statusGreen]. OK runs [onClick],
     * closing the menu first if [closes].
     */
    fun row(icon: Int, title: String, status: String?, statusGreen: Boolean = false, closes: Boolean = true, onClick: () -> Unit): View =
        focusableRow(closes, onClick).apply {
            addView(iconView(icon), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
            addView(label(title, TEXT_SP, text).apply { setPadding(dp(12), 0, dp(10), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (status != null) addView(label(status, SMALL_SP, if (statusGreen) green else secondary))
        }

    /**
     * The menu's first row: [title], medium weight, [status] under it if any, and a switch showing
     * [on]. OK closes the menu, then runs [onToggle].
     */
    fun header(title: String, status: String?, on: Boolean, onToggle: () -> Unit): View =
        focusableRow(closes = true, onToggle).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                isDuplicateParentStateEnabled = true
                addView(title(title))
                if (status != null) addView(label(status, SMALL_SP, secondary).apply { setPadding(0, dp(1), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
            addView(switch(on), LinearLayout.LayoutParams(dp(SWITCH_WIDTH_DP), dp(SWITCH_HEIGHT_DP)))
        }

    /** A page's first row: a back chevron and [title]. OK runs [onBack], the menu staying open. */
    fun backRow(title: String, onBack: () -> Unit): View =
        focusableRow(closes = false, onBack).apply {
            addView(iconView(UiR.drawable.icon_chevron_back), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
            addView(title(title).apply { setPadding(dp(10), 0, 0, 0) })
        }

    /** A small grey caption over a [field], in capitals. */
    fun caption(text: String): View = label(text.uppercase(), CAPTION_SP, ColorStateList.valueOf(GREY)).apply {
        letterSpacing = 0.08f
        setPadding(dp(12), dp(10), dp(12), dp(4))
    }

    /** A text field on light grey, ringed in graphite while focused; its text hidden if [password]. */
    fun field(hint: String, password: Boolean = false): EditText = EditText(activity).apply {
        this.hint = hint
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
        typeface = Typeface.DEFAULT   // a password input type makes it monospace
        textSize = TEXT_SP
        setTextColor(INK)
        setHintTextColor(GREY)
        minHeight = dp(ROW_HEIGHT_DP)
        setPadding(dp(12), 0, dp(12), 0)
        background = focusedOr(
            rounded(FIELD, 10).apply { setStroke(dp(2), INK) },
            rounded(FIELD, 10),
        )
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), 0, dp(6), 0)
        }
    }

    /**
     * A password [field] with an eye at its end: OK on the eye shows the password, or hides it
     * again. Right from the end of the text reaches it. The field, and the view to add.
     */
    fun passwordField(hint: String): Pair<EditText, View> {
        val field = field(hint, password = true)
        val wrapper = FrameLayout(activity).apply { layoutParams = field.layoutParams }
        field.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        field.setPadding(dp(12), 0, dp(EYE_DP + 8), 0)
        wrapper.addView(field)
        var shown = false
        wrapper.addView(ImageView(activity).apply {
            setImageResource(R.drawable.home_ic_eye)
            imageTintList = focusedOr(Color.WHITE, GREY)
            background = focusedOr(rounded(INK, 8), ColorDrawable(Color.TRANSPARENT))
            val inset = dp(7)
            setPadding(inset, inset, inset, inset)
            isFocusable = true
            isClickable = true
            contentDescription = "Show password"
            setOnClickListener {
                shown = !shown
                field.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                field.typeface = Typeface.DEFAULT
                field.minHeight = dp(ROW_HEIGHT_DP)   // a new input type sets it to 1 line's
                field.setSelection(field.text.length)
                setImageResource(if (shown) UiR.drawable.icon_eye_off else R.drawable.home_ic_eye)
                contentDescription = if (shown) "Hide password" else "Show password"
            }
        }, FrameLayout.LayoutParams(dp(EYE_DP), dp(EYE_DP), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(4) })
        return field to wrapper
    }

    /** A button, light grey, graphite while focused, centered. OK runs [onClick], the menu staying open. */
    fun button(title: String, onClick: () -> Unit): View = TextView(activity).apply {
        this.text = title
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER   // else the theme's alignment wins over gravity
        textSize = TEXT_SP
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(this@PillMenu.text)
        isFocusable = true
        isClickable = true
        minHeight = dp(ROW_HEIGHT_DP)
        minWidth = dp(BUTTON_MIN_WIDTH_DP)
        setPadding(dp(24), 0, dp(24), 0)
        background = focusedOr(rounded(INK, ROW_HEIGHT_DP / 2), rounded(FIELD, ROW_HEIGHT_DP / 2))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(dp(6), dp(16), dp(6), dp(6))
        }
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

    /**
     * As wide as [panel]'s rows want, within MIN_WIDTH_DP..MAX_WIDTH_DP: a row's title, 0 wide with
     * a weight, is measured as wide as its text when the width isn't exact.
     */
    private fun measuredWidth(panel: View): Int {
        panel.measure(View.MeasureSpec.makeMeasureSpec(dp(MAX_WIDTH_DP), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        return panel.measuredWidth.coerceIn(dp(MIN_WIDTH_DP), dp(MAX_WIDTH_DP))
    }

    private fun focusableRow(closes: Boolean, onClick: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = dp(ROW_HEIGHT_DP)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = focusedOr(rounded(INK, 12), ColorDrawable(Color.TRANSPARENT))
        setOnClickListener {
            if (closes) close()
            onClick()
        }
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

    private fun title(title: String) = label(title, TITLE_SP, text).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        const val CAPTION_SP = 11f
        const val EYE_DP = 34
        const val BUTTON_MIN_WIDTH_DP = 140
        const val SWITCH_WIDTH_DP = 34
        const val SWITCH_HEIGHT_DP = 20
        const val KNOB_INSET_DP = 3

        // Mono graphite.
        const val INK = 0xFF1C1C1E.toInt()
        const val GREY = 0xFF8E8E93.toInt()
        const val FIELD = 0xFFF2F2F7.toInt()
        const val DIVIDER = 0xFFE5E5EA.toInt()
        const val RIM = 0x14000000
        const val TRACK_OFF = 0xFFE5E5EA.toInt()
        const val TRACK_OFF_FOCUSED = 0xFF48484A.toInt()
        const val GREEN = 0xFF1E8E3E.toInt()
        const val BRIGHT_GREEN = 0xFF34C759.toInt()
    }
}
