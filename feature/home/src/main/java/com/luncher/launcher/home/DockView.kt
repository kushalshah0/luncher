package com.luncher.launcher.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.luncher.domain.appearance.DockAppearance
import com.luncher.domain.appearance.DockShelf
import com.luncher.domain.apps.HomeApp
import com.luncher.domain.layout.DockLayout
import com.luncher.domain.layout.PixelBounds
import com.luncher.launcher.ui.TileFrost
import com.luncher.launcher.ui.shelfOutline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The home screen's dock (NETTV fork), in place of the tiles while the home mode is the dock: the
 * docked apps as round icons ([DockIcons]) on a curved glass shelf at the bottom, in the middle, as
 * the dock of a Mac. The focused icon is magnified, its neighbors less ([DockLayout]), the wave
 * gliding as Left and Right move the focus, the shelf widening under it; the focused app's name
 * shows above it in a label, as a Mac's: a capsule pointing down at it, and a small dot under
 * it, as the Mac's under an open app; no ring, the magnification shows the focus. OK opens the app, with a bounce; a long press of OK opens its menu
 * ([onMenu]); Up goes to the top bar, by Android's focus search.
 *
 * The shelf: its outline curved as a Mac dock's (continuous-curvature corners, [DockShape]), never
 * a rectangle; filled with the wallpaper's frosted copy behind it (the one the Frosted tiles use,
 * [frost]: no blur of its own) and a light or dark tint, edged with a hairline, as a Mac's, over a
 * soft shadow drawn as a few faint offset outlines (a blur's shadow is costly on the TV's GPU).
 *
 * One view, no child views: each frame of a glide draws a few paths and a bitmap per icon, and
 * allocates nothing. The animations run only while something moves; at rest nothing is scheduled.
 */
internal class DockView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val icons = DockIcons(context)

    /** The docked apps, in order, as shown: while moving one, in the order it's being moved to. */
    private var items: List<HomeApp> = emptyList()

    /** The docked apps, in order. The focus stays on the same app where it's still there; a move under way is dropped. */
    var apps: List<HomeApp>
        get() = items
        set(value) {
            if (moving) cancelMove()
            if (items == value) return
            val focused = items.getOrNull(selected)?.launchable
            items = value
            val kept = value.indexOfFirst { it.launchable == focused }
            selected = if (kept >= 0) kept else selected.coerceIn(0, (value.size - 1).coerceAtLeast(0))
            glideFrom = selected.toFloat()
            glideTo = selected.toFloat()
            glide.cancel()
            icons.keepOnly(value)
            relayout()
            describe()
        }

    /** How the dock looks; another size draws the icons again. */
    var appearance: DockAppearance = DockAppearance()
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    /** The wallpaper's frosted copy, the glass's backdrop; none without a wallpaper. */
    var frost: TileFrost? = null
        set(value) {
            if (field === value) return
            field = value
            placeFrost()
            invalidate()
        }

    /** Opens [HomeApp]: OK on its icon. */
    var onOpen: (HomeApp) -> Unit = {}

    /** Opens [HomeApp]'s menu: a long press of OK on its icon. */
    var onMenu: (HomeApp) -> Unit = {}

    /** The focus moved to another icon: the tick ([DockSounds]). */
    var onStep: () -> Unit = {}

    /** The docked app moved to its new place in the dock, as OK ends moving it. */
    var onMoved: (HomeApp, Int) -> Unit = { _, _ -> }

    /** The focused app; null with no apps. */
    val focusedApp: HomeApp? get() = apps.getOrNull(selected)

    /** Whether an app is being moved: Left and Right move it, OK drops it, Back puts it back. */
    var moving = false
        private set

    private var selected = 0

    /** The apps as they were when moving started, which [cancelMove] puts back. */
    private var beforeMove: List<HomeApp> = emptyList()

    private var layout: DockLayout? = null
    private var centers = FloatArray(0)
    private var scales = FloatArray(0)

    // Sizes, in px, set with the appearance.
    private var bottomMargin = 0f
    private var lift = 0f
    private var bounceHeight = 0f
    private val bubbleHeight = 30 * density
    private val bubbleGap = 12 * density
    private val caretWidth = 12 * density
    private val caretHeight = 6 * density
    private val dotRadius = 2 * density
    private val bubblePadding = 14 * density
    private val sidePadding = 48 * density
    private val shadowDrop = 3 * density

    private val shelfPath = Path()
    private val shelfBounds = RectF()
    private val iconBounds = RectF()
    private val bubble = RectF()

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frostMatrix = Matrix()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The shelf's edge: a hairline, light, as a Mac dock's. */
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (0.75f * density).coerceAtLeast(1f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val caret = Path()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 28, 28, 30) }
    private val bubbleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(60, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val moveHint = resources.getString(R.string.home_dock_move_hint)

    private val location = IntArray(2)

    // The focus gliding from one icon to another: a position between them, eased.
    private var glideFrom = 0f
    private var glideTo = 0f
    private val glide = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = GLIDE_MS
        interpolator = DecelerateInterpolator(1.6f)
        addUpdateListener { invalidate() }
    }

    // The magnification fading in as the dock gets the focus, out as it loses it.
    private var amountFrom = 0f
    private var amountTo = 0f
    private val fade = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = FADE_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener { invalidate() }
    }

    // The opened icon hopping, twice, lower the second time.
    private var bounced = -1
    private val bounce = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = BOUNCE_MS
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        setOnClickListener { focusedApp?.let(::open) }
        setOnLongClickListener {
            val app = focusedApp
            if (app != null && !moving) onMenu(app)
            app != null
        }
        relayout()
    }

    private fun position(): Float = if (glide.isRunning) lerp(glideFrom, glideTo, glide.animatedFraction) else glideTo

    private fun amount(): Float = if (fade.isRunning) lerp(amountFrom, amountTo, fade.animatedFraction) else amountTo

    /** The sizes and the layout for the appearance and the apps; the icons drawn again at another size. */
    private fun relayout() {
        val icon = appearance.iconSizeDp * density
        val layout = DockLayout(
            count = apps.size,
            iconPx = icon,
            gapPx = (icon * GAP).coerceAtLeast(6 * density),
            paddingPx = (icon * PADDING).coerceAtLeast(6 * density),
            magnification = appearance.magnification.scale,
        )
        this.layout = layout
        if (centers.size != apps.size) {
            centers = FloatArray(apps.size)
            scales = FloatArray(apps.size)
        }
        bottomMargin = resources.getDimension(R.dimen.home_padding_vertical)
        lift = icon * 0.22f
        bounceHeight = icon * 0.32f
        icons.size = (layout.largestIconPx * PRESSED_MAX).roundToInt()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val layout = layout
        val tall = if (layout == null) 0f else {
            bottomMargin + layout.paddingPx + layout.largestIconPx + maxOf(lift, bounceHeight) + bubbleGap + bubbleHeight + 8 * density
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(tall.roundToInt(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        placeFrost()
    }

    /** The frost drawn where it is on the screen: its copy is of the whole window, at its own scale. */
    private fun placeFrost() {
        val frost = frost
        val window = rootView
        if (frost == null || window.width == 0 || window.height == 0) {
            frostPaint.shader = null
            return
        }
        getLocationInWindow(location)
        frostMatrix.setScale(window.width / frost.image.width.toFloat(), window.height / frost.image.height.toFloat())
        frostMatrix.postTranslate(-location[0].toFloat(), -location[1].toFloat())
        frostPaint.shader = BitmapShader(frost.image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(frostMatrix) }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus && moving) endMove()
        amountFrom = amount()
        amountTo = if (gainFocus) 1f else 0f
        fade.start()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()   // the pressed icon dips
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> return step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return step(1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (moving) return true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (moving) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (moving && keyCode in OK_KEYS) {
            endMove()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * The focus one icon to the left ([by] -1) or right: the focused app with it while moving. At
     * either end, nothing: the keys stay in the dock.
     */
    private fun step(by: Int): Boolean {
        val to = selected + by
        if (to !in apps.indices) return true
        if (moving) {
            val order = items.toMutableList()
            order.add(to, order.removeAt(selected))
            items = order   // not through the setter: the move goes on, the icons are the same

        }
        select(to)
        onStep()
        return true
    }

    private fun select(index: Int) {
        glideFrom = position()
        glideTo = index.toFloat()
        selected = index
        glide.start()
        describe()
    }

    /** What a screen reader says: the focused app's name. */
    private fun describe() {
        contentDescription = focusedApp?.name
    }

    private fun open(app: HomeApp) {
        bounced = selected
        bounce.start()
        onOpen(app)
    }

    /** Starts moving the focused app: Left and Right take it along, OK drops it, Back puts it back. */
    fun startMove() {
        if (apps.size < 2 || moving) return
        beforeMove = apps
        moving = true
        invalidate()
    }

    /** Drops the moved app where it is, and tells [onMoved]. */
    fun endMove() {
        if (!moving) return
        moving = false
        val app = focusedApp
        invalidate()
        if (app != null && apps != beforeMove) onMoved(app, selected)
    }

    /** Puts the moved app back where it was. */
    fun cancelMove() {
        if (!moving) return
        val app = focusedApp
        moving = false
        items = beforeMove
        val index = beforeMove.indexOfFirst { it.launchable == app?.launchable }.coerceAtLeast(0)
        glideFrom = index.toFloat()
        glideTo = index.toFloat()
        selected = index
        invalidate()
    }

    /** Where the focused icon is on the screen, magnified, with its bubble: what its menu goes beside. */
    fun focusedBounds(): PixelBounds {
        getLocationOnScreen(location)
        return PixelBounds(
            location[0] + iconBounds.left.toInt(),
            location[1] + (iconBounds.top - bubbleGap - bubbleHeight).toInt(),
            location[0] + iconBounds.right.toInt(),
            location[1] + iconBounds.bottom.toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout ?: return
        val count = apps.size
        if (count == 0) return
        val position = position()
        val amount = amount()
        val shelfWidth = layout.place(position, centers, scales, amount)
        val left = sidePadding + layout.left(width - 2 * sidePadding, shelfWidth, centerAt(position))
        val shelfBottom = height - bottomMargin
        val shelfTop = shelfBottom - layout.shelfHeight

        if (appearance.shelf != DockShelf.NONE) drawShelf(canvas, left, shelfTop, left + shelfWidth, shelfBottom)

        val iconBottom = shelfBottom - layout.paddingPx
        for (i in 0 until count) if (i != selected) drawIcon(canvas, i, left, iconBottom, layout.iconPx, amount)
        drawIcon(canvas, selected, left, iconBottom, layout.iconPx, amount)
        if (amount > 0f) drawDot(canvas, left + centers[selected], shelfBottom, layout.paddingPx, amount)
        if (amount > 0f) drawBubble(canvas, amount)
    }

    /** The focus's middle at [position], between two icons while it glides. */
    private fun centerAt(position: Float): Float {
        val last = centers.size - 1
        val from = floor(position).toInt().coerceIn(0, last)
        val to = (from + 1).coerceAtMost(last)
        return lerp(centers[from], centers[to], (position - from).coerceIn(0f, 1f))
    }

    private fun drawShelf(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        shelfBounds.set(left, top, right, bottom)
        shelfOutline(shelfPath, shelfBounds, appearance.shape)
        val dark = appearance.shelf == DockShelf.DARK
        // A soft shadow under it: the outline, faint, a little lower each time.
        for (layer in 1..SHADOW_LAYERS) {
            shadowPaint.alpha = SHADOW_ALPHA / layer
            canvas.save()
            canvas.translate(0f, shadowDrop * layer)
            canvas.drawPath(shelfPath, shadowPaint)
            canvas.restore()
        }
        if (frostPaint.shader != null) canvas.drawPath(shelfPath, frostPaint)
        tintPaint.color = when {
            dark -> Color.argb(if (frostPaint.shader != null) 120 else 150, 18, 18, 20)
            frostPaint.shader != null -> Color.argb(46, 255, 255, 255)
            else -> Color.argb(52, 255, 255, 255)
        }
        canvas.drawPath(shelfPath, tintPaint)
        rimPaint.color = Color.argb(if (dark) 46 else 72, 255, 255, 255)
        canvas.drawPath(shelfPath, rimPaint)
    }

    private fun drawIcon(canvas: Canvas, index: Int, left: Float, bottom: Float, rest: Float, amount: Float) {
        val focused = index == selected
        var size = rest * scales[index]
        if (focused && isPressed && !moving) size *= PRESSED
        val center = left + centers[index]
        var lifted = bottom
        if (focused && moving) lifted -= lift * amount
        if (index == bounced && bounce.isRunning) {
            val f = bounce.animatedFraction
            lifted -= bounceHeight * abs(sin(2 * PI * f)).toFloat() * (1 - f * 0.6f)
        }
        iconBounds.set(center - size / 2, lifted - size, center + size / 2, lifted)
        canvas.drawCircle(iconBounds.centerX(), iconBounds.centerY() + shadowDrop, size / 2, iconShadowPaint)
        canvas.drawBitmap(icons.icon(apps[index]), null, iconBounds, iconPaint)
        // The focused icon is drawn last: its bounds stay for the label and the menu.
    }

    /** The dot under the focused icon, as a Mac's under an open app: its size fixed, in the shelf's bottom padding. */
    private fun drawDot(canvas: Canvas, center: Float, shelfBottom: Float, padding: Float, amount: Float) {
        dotPaint.alpha = (DOT_ALPHA * amount).toInt()
        canvas.drawCircle(center, shelfBottom - padding / 2, dotRadius, dotPaint)
    }

    /** The focused app's name, or how to move it, in a dark glass capsule above its icon, pointing down at it, as a Mac's. */
    private fun drawBubble(canvas: Canvas, amount: Float) {
        if (!appearance.showName && !moving) return
        val text = if (moving) moveHint else focusedApp?.name ?: return
        val half = textPaint.measureText(text) / 2 + bubblePadding
        val center = iconBounds.centerX().coerceIn(half, width - half)
        val bottom = iconBounds.top - bubbleGap
        bubble.set(center - half, bottom - bubbleHeight, center + half, bottom)
        caret.reset()
        caret.moveTo(iconBounds.centerX() - caretWidth / 2, bottom - 1)
        caret.lineTo(iconBounds.centerX() + caretWidth / 2, bottom - 1)
        caret.lineTo(iconBounds.centerX(), bottom + caretHeight)
        caret.close()
        val alpha = (255 * amount).toInt()
        bubblePaint.alpha = (205 * amount).toInt()
        bubbleRimPaint.alpha = (60 * amount).toInt()
        textPaint.alpha = alpha
        val radius = bubbleHeight / 2
        canvas.drawRoundRect(bubble, radius, radius, bubblePaint)
        canvas.drawPath(caret, bubblePaint)
        canvas.drawRoundRect(bubble, radius, radius, bubbleRimPaint)
        val baseline = bubble.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, bubble.centerX(), baseline, textPaint)
    }

    private companion object {
        const val GLIDE_MS = 220L
        const val FADE_MS = 200L
        const val BOUNCE_MS = 520L

        /** Between icons, and around them inside the shelf, as a part of an icon at rest. */
        const val GAP = 0.2f
        const val PADDING = 0.16f

        /** The focused icon while OK is held on it: it dips. */
        const val PRESSED = 0.92f

        /** The icons are drawn at the largest they show (never larger than magnified). */
        const val PRESSED_MAX = 1f

        const val SHADOW_LAYERS = 3
        const val SHADOW_ALPHA = 46

        /** The dot under the focused icon: about half white, as a Mac's. */
        const val DOT_ALPHA = 150

        val OK_KEYS = intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}

private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
