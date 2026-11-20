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
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var palette: Palette = Palette.DARK
        set(value) { field = value; invalidate() }

    /** Three slots (left, centre, right) or null to show the toolbar. */
    var suggestions: List<String?>? = null
        set(value) { if (field != value) { field = value; pressedIndex = -1; invalidate() } }

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

    private val toolbar = intArrayOf(ACTION_EMOJI, ACTION_CLIPBOARD, ACTION_SETTINGS, ACTION_SWITCH, ACTION_HIDE)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(46f).toInt())
    }

    private fun slotCount() = if (suggestions != null) 3 else toolbar.size

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val n = slotCount()
        val cell = width / n.toFloat()
        if (pressedIndex in 0 until n) {
            fill.color = palette.keyPressed
            rect.set(pressedIndex * cell + dp(4f), dp(5f), (pressedIndex + 1) * cell - dp(4f), height - dp(5f))
            canvas.drawRoundRect(rect, height / 2f, height / 2f, fill)
        }
        val s = suggestions
        if (s != null) {
            for (i in 0 until 3) {
                if (i > 0) {
                    fill.color = palette.divider
                    canvas.drawRect(i * cell - dp(0.5f), height * 0.3f, i * cell + dp(0.5f), height * 0.7f, fill)
                }
                val word = s[i] ?: continue
                val shown = if (i == 0 && quoteLeft) "“$word”" else word
                textPaint.color = palette.text
                textPaint.typeface = if (i == 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val fitted = TextUtils.ellipsize(shown, textPaint, cell - dp(12f), TextUtils.TruncateAt.END).toString()
                val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(fitted, i * cell + cell / 2, y, textPaint)
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
        val n = slotCount()
        val idx = (e.x / (width / n.toFloat())).toInt().coerceIn(0, n - 1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedIndex = idx; invalidate() }
            MotionEvent.ACTION_MOVE -> if (idx != pressedIndex) { pressedIndex = -1; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (idx == pressedIndex) {
                    val s = suggestions
                    if (s != null) s[idx]?.let { listener.onSuggestionPicked(it) }
                    else listener.onToolbarAction(toolbar[idx])
                }
                pressedIndex = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedIndex = -1; invalidate() }
        }
        return true
    }

    companion object {
        const val ACTION_EMOJI = 1
        const val ACTION_CLIPBOARD = 2
        const val ACTION_SETTINGS = 3
        const val ACTION_SWITCH = 4
        const val ACTION_HIDE = 5
    }
}
