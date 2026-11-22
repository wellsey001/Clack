package com.wellsey.clack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

/**
 * The strip above the keys: a toolbar when you're not typing a word,
 * three word suggestions while you are (like Gboard).
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onSuggestionPicked(word: String)
        fun onToolbarAction(action: Int)
        fun onSuggestionLongPressed(word: String)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var palette: Palette = Palette.DARK
        set(value) { field = value; invalidate() }

    /** Three slots (left, centre, right) or null to show the toolbar. */
    var suggestions: List<String?>? = null
        set(value) { if (field != value) { field = value; pressedIndex = -1; invalidate() } }

    /** A verification code ready to paste, shown instead of the toolbar while it's fresh. */
    var otpCode: String? = null
        set(value) { if (field != value) { field = value; pressedIndex = -1; invalidate() } }

    private val otpMode get() = suggestions == null && otpCode != null
    private val closeWidth get() = height * 1.3f

    /** Display text for the left slot when it's the word as typed (shown in quotes). */
    var quoteLeft = false
        set(value) { field = value; invalidate() }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(17f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pressedIndex = -1
    private var longPressed = false
    private val longPressRunnable = Runnable {
        val word = suggestions?.getOrNull(pressedIndex)
        if (word != null) {
            longPressed = true
            listener.onSuggestionLongPressed(word)
        }
    }

    private val toolbar = intArrayOf(ACTION_EMOJI, ACTION_CLIPBOARD, ACTION_SETTINGS, ACTION_SWITCH, ACTION_HIDE)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(46f).toInt())
    }

    private fun slotCount() = if (suggestions != null) 3 else toolbar.size

    /** Left edges of each slot plus the right edge. A phrase in the middle gets extra room. */
    private fun slotEdges(): FloatArray {
        val n = slotCount()
        val w = width.toFloat()
        val center = suggestions?.getOrNull(1)
        if (n == 3 && center != null && ' ' in center) {
            return floatArrayOf(0f, w * 0.2f, w * 0.8f, w)
        }
        return FloatArray(n + 1) { it * w / n }
    }

    private fun indexAt(x: Float): Int {
        if (otpMode) return if (x > width - closeWidth) 1 else 0
        val edges = slotEdges()
        for (i in 0 until edges.size - 1) if (x < edges[i + 1]) return i
        return edges.size - 2
    }

    private fun drawOtp(canvas: Canvas, code: String) {
        // "Paste code 123456" pill, plus an ✕ to dismiss it
        val chipRight = width - closeWidth
        rect.set(dp(8f), dp(6f), chipRight, height - dp(6f))
        fill.color = palette.fnKey
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
        if (pressedIndex == 0) {
            fill.color = if (palette.isDark) 0x22FFFFFF else 0x22000000
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
        }
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        val label = "Paste code  "
        textPaint.typeface = Typeface.DEFAULT
        val labelW = textPaint.measureText(label)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        val codeW = textPaint.measureText(code)
        val start = rect.centerX() - (labelW + codeW) / 2f
        textPaint.color = palette.fnText
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText(label, start, y, textPaint)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(code, start + labelW, y, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT

        val cx = width - closeWidth / 2f
        val cy = height / 2f
        if (pressedIndex == 1) {
            fill.color = palette.keyPressed
            canvas.drawCircle(cx, cy, height * 0.36f, fill)
        }
        iconPaint.color = palette.icon
        iconPaint.style = Paint.Style.STROKE
        val d = dp(6f)
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, iconPaint)
        canvas.drawLine(cx - d, cy + d, cx + d, cy - d, iconPaint)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val otp = otpCode
        if (suggestions == null && otp != null) {
            drawOtp(canvas, otp)
            return
        }
        val n = slotCount()
        val cell = width / n.toFloat()
        val edges = slotEdges()
        if (pressedIndex in 0 until n) {
            fill.color = palette.keyPressed
            rect.set(edges[pressedIndex] + dp(4f), dp(5f), edges[pressedIndex + 1] - dp(4f), height - dp(5f))
            canvas.drawRoundRect(rect, height / 2f, height / 2f, fill)
        }
        val s = suggestions
        if (s != null) {
            for (i in 0 until 3) {
                if (i > 0) {
                    fill.color = palette.divider
                    canvas.drawRect(edges[i] - dp(0.5f), height * 0.3f, edges[i] + dp(0.5f), height * 0.7f, fill)
                }
                val word = s[i] ?: continue
                val shown = if (i == 0 && quoteLeft) "“$word”" else word
                textPaint.color = palette.text
                textPaint.typeface = if (i == 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val slotW = edges[i + 1] - edges[i]
                val fitted = TextUtils.ellipsize(shown, textPaint, slotW - dp(12f), TextUtils.TruncateAt.END).toString()
                val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(fitted, edges[i] + slotW / 2, y, textPaint)
            }
        } else {
            iconPaint.color = palette.icon
            val size = dp(21f)
            for (i in toolbar.indices) {
                val cx = i * cell + cell / 2
                val cy = height / 2f
                when (toolbar[i]) {
                    ACTION_EMOJI -> Icons.smiley(canvas, iconPaint, cx, cy, size)
                    ACTION_CLIPBOARD -> Icons.clipboard(canvas, iconPaint, cx, cy, size)
                    ACTION_SETTINGS -> Icons.gear(canvas, iconPaint, cx, cy, size)
                    ACTION_SWITCH -> Icons.globe(canvas, iconPaint, cx, cy, size)
                    ACTION_HIDE -> Icons.chevronDown(canvas, iconPaint, cx, cy, size)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = indexAt(e.x)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = idx
                longPressed = false
                if (suggestions != null) postDelayed(longPressRunnable, 500)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (idx != pressedIndex) {
                pressedIndex = -1
                removeCallbacks(longPressRunnable)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (idx == pressedIndex && !longPressed) {
                    val s = suggestions
                    when {
                        s != null -> s[idx]?.let { listener.onSuggestionPicked(it) }
                        otpMode -> listener.onToolbarAction(if (idx == 0) ACTION_PASTE_CODE else ACTION_DISMISS_CODE)
                        else -> listener.onToolbarAction(toolbar[idx])
                    }
                }
                pressedIndex = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    companion object {
        const val ACTION_EMOJI = 1
        const val ACTION_CLIPBOARD = 2
        const val ACTION_SETTINGS = 3
        const val ACTION_SWITCH = 4
        const val ACTION_HIDE = 5
        const val ACTION_PASTE_CODE = 6
        const val ACTION_DISMISS_CODE = 7
    }
}
