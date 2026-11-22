package com.wellsey.clack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.ceil
import kotlin.math.max

/**
 * Clipboard history: recent copies as cards (tap to paste), pinned ones first.
 * Hold a card to pin or delete it.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onClipPaste(text: String)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var palette: Palette = Palette.DARK
        set(value) { field = value; invalidate() }

    var panelHeight: Int = 0
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val history = ClipHistory.get(context)
    private var items: List<ClipHistory.Item> = emptyList()
    /** Card whose Pin / Delete buttons are showing, if any. */
    private var actionsFor: ClipHistory.Item? = null

    private val headerH = dp(44f)
    private val cardH = dp(82f)
    private val gap = dp(8f)
    private val side = dp(10f)
    private var scrollPos = 0f

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(14f) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f)
        isFakeBoldText = true
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val rect = RectF()
    private val clearRect = RectF()
    private val scroller = OverScroller(context)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollPos = (scrollPos + dy).coerceIn(0f, maxScroll())
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollPos.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < headerH) {
                if (clearRect.contains(e.x, e.y)) {
                    history.clearUnpinned()
                    refresh()
                }
                return true
            }
            val i = indexAt(e.x, e.y)
            val item = items.getOrNull(i) ?: run { actionsFor = null; invalidate(); return true }
            val open = actionsFor
            if (open != null && open.text == item.text) {
                // Tapped one of the two buttons on the card: left half pins, right half deletes
                val r = cardRect(i)
                if (e.x < r.centerX()) history.togglePin(item) else history.delete(item)
                actionsFor = null
                refresh()
                return true
            }
            actionsFor = null
            listener.onClipPaste(item.text)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val item = items.getOrNull(indexAt(e.x, e.y)) ?: return
            actionsFor = item
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    })

    /** Reload from history (call when the panel opens or a copy happens). */
    fun refresh() {
        items = history.list()
        scrollPos = scrollPos.coerceIn(0f, maxScroll())
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), panelHeight)
    }

    private fun rowsCount() = ceil(items.size / 2.0).toInt()
    private fun contentHeight() = rowsCount() * (cardH + gap) + gap
    private fun maxScroll() = max(0f, contentHeight() - (height - headerH))

    private fun cardRect(i: Int): RectF {
        val colW = (width - side * 2 - gap) / 2f
        val col = i % 2
        val row = i / 2
        val left = side + col * (colW + gap)
        val top = headerH + gap + row * (cardH + gap) - scrollPos
        return RectF(left, top, left + colW, top + cardH)
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (y < headerH) return -1
        for (i in items.indices) if (cardRect(i).contains(x, y)) return i
        return -1
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)

        // cards
        canvas.save()
        canvas.clipRect(0f, headerH, width.toFloat(), height.toFloat())
        for (i in items.indices) {
            val r = cardRect(i)
            if (r.bottom < headerH || r.top > height) continue
            val item = items[i]
            fill.color = palette.key
            canvas.drawRoundRect(r, dp(14f), dp(14f), fill)
            if (actionsFor?.text == item.text) {
                drawActions(canvas, r, item)
                continue
            }
            val inset = dp(12f)
            val textW = (r.width() - inset * 2 - if (item.pinned) dp(16f) else 0f).toInt().coerceAtLeast(1)
            textPaint.color = palette.text
            val text = item.text.replace('\n', ' ')
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textW)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            canvas.save()
            canvas.translate(r.left + inset, r.top + dp(10f))
            layout.draw(canvas)
            canvas.restore()
            if (item.pinned) {
                iconPaint.color = palette.accent
                drawPin(canvas, r.right - dp(14f), r.top + dp(14f))
            }
        }
        canvas.restore()

        if (items.isEmpty()) {
            smallPaint.color = palette.hint
            canvas.drawText("Things you copy will show here", width / 2f, headerH + (height - headerH) / 2f, smallPaint)
        }

        // header
        fill.color = palette.background
        canvas.drawRect(0f, 0f, width.toFloat(), headerH, fill)
        titlePaint.color = palette.text
        val ty = headerH / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
        canvas.drawText("Clipboard", side + dp(4f), ty, titlePaint)
        smallPaint.color = palette.hint
        val label = "Clear"
        val lw = smallPaint.measureText(label)
        clearRect.set(width - side - lw - dp(24f), dp(6f), width - side, headerH - dp(6f))
        if (items.any { !it.pinned }) {
            fill.color = palette.fnKey
            canvas.drawRoundRect(clearRect, clearRect.height() / 2, clearRect.height() / 2, fill)
            smallPaint.color = palette.fnText
            canvas.drawText(label, clearRect.centerX(), ty, smallPaint)
        }
        val hint = "Hold to pin"
        smallPaint.color = palette.hint
        smallPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(hint, side + dp(4f) + titlePaint.measureText("Clipboard") + dp(10f), ty, smallPaint)
        smallPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawActions(canvas: Canvas, r: RectF, item: ClipHistory.Item) {
        val half = r.width() / 2f
        val cy = r.centerY()
        fill.color = palette.fnKey
        rect.set(r.left + dp(6f), r.top + dp(6f), r.left + half - dp(3f), r.bottom - dp(6f))
        canvas.drawRoundRect(rect, dp(10f), dp(10f), fill)
        rect.set(r.left + half + dp(3f), r.top + dp(6f), r.right - dp(6f), r.bottom - dp(6f))
        canvas.drawRoundRect(rect, dp(10f), dp(10f), fill)
        smallPaint.color = palette.fnText
        val ty = cy - (smallPaint.descent() + smallPaint.ascent()) / 2f
        canvas.drawText(if (item.pinned) "Unpin" else "Pin", r.left + half / 2f, ty, smallPaint)
        canvas.drawText("Delete", r.left + half * 1.5f, ty, smallPaint)
    }

    private fun drawPin(c: Canvas, cx: Float, cy: Float) {
        val s = dp(10f)
        c.drawCircle(cx, cy - s * 0.25f, s * 0.35f, iconPaint)
        c.drawLine(cx, cy + s * 0.1f, cx, cy + s * 0.6f, iconPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || true
}
