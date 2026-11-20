package com.wellsey.clack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Emoji search, shown above the letter keys: the search box on top and a row of
 * matching emoji below it (drag sideways to see more).
 */
@SuppressLint("ViewConstructor")
class EmojiSearchView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onSearchBack()
        fun onSearchClear()
        fun onSearchResult(emoji: String)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var palette: Palette = Palette.DARK
        set(value) { field = value; invalidate() }

    var query: String = ""
        set(value) { field = value; invalidate() }

    var results: List<String> = emptyList()
        set(value) { field = value; scrollX2 = 0f; invalidate() }

    private val barH = dp(44f)
    private val resultsH = dp(50f)
    private val cellW = dp(48f)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(16f) }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(28f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var scrollX2 = 0f
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (barH + resultsH).toInt())
    }

    private fun maxScroll() = max(0f, results.size * cellW - width)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)

        // back arrow
        iconPaint.color = palette.icon
        Icons.back(canvas, iconPaint, barH / 2f + dp(4f), barH / 2f, dp(18f))

        // search box
        rect.set(barH + dp(4f), dp(6f), width - dp(8f), barH - dp(4f))
        fill.color = palette.key
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
        Icons.search(canvas, iconPaint, rect.left + dp(18f), rect.centerY(), dp(15f))
        val textLeft = rect.left + dp(34f)
        val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        val clearSpace = if (query.isNotEmpty()) dp(36f) else 0f
        if (query.isEmpty()) {
            textPaint.color = palette.hint
            canvas.drawText("Search emoji", textLeft, y, textPaint)
        } else {
            textPaint.color = palette.text
            val avail = rect.right - textLeft - clearSpace - dp(8f)
            // keep the end of the query (where you're typing) visible
            val shown = TextUtils.ellipsize(query, textPaint, avail, TextUtils.TruncateAt.START).toString()
            canvas.drawText(shown, textLeft, y, textPaint)
            val caretX = textLeft + textPaint.measureText(shown) + dp(2f)
            fill.color = palette.accent
            canvas.drawRect(caretX, rect.centerY() - dp(10f), caretX + dp(2f), rect.centerY() + dp(10f), fill)
            // clear ✕
            val cx = rect.right - dp(18f)
            val cy = rect.centerY()
            val d = dp(5f)
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, iconPaint)
            canvas.drawLine(cx - d, cy + d, cx + d, cy - d, iconPaint)
        }

        // results
        val top = barH
        if (results.isEmpty()) {
            textPaint.color = palette.hint
            val msg = if (query.isBlank()) "Type to search, e.g. pizza, love, cat" else "No emoji found"
            canvas.drawText(msg, dp(16f), top + resultsH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            return
        }
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), top + resultsH)
        val ey = top + resultsH / 2f - (emojiPaint.descent() + emojiPaint.ascent()) / 2f
        results.forEachIndexed { i, e ->
            val cx = i * cellW + cellW / 2f - scrollX2
            if (cx < -cellW || cx > width + cellW) return@forEachIndexed
            canvas.drawText(e, cx, ey, emojiPaint)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                lastX = e.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.y >= barH && (dragging || abs(e.x - downX) > dp(8f))) {
                    dragging = true
                    scrollX2 = (scrollX2 - (e.x - lastX)).coerceIn(0f, maxScroll())
                    invalidate()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> if (!dragging) tap(e.x, e.y)
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (y < barH) {
            when {
                x < barH + dp(4f) -> listener.onSearchBack()
                query.isNotEmpty() && x > width - dp(52f) -> listener.onSearchClear()
            }
            return
        }
        val i = ((x + scrollX2) / cellW).toInt()
        results.getOrNull(i)?.let { listener.onSearchResult(it) }
    }
}
