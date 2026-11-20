package com.wellsey.clack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Draws the whole keyboard on one canvas and turns touches into key presses.
 * Supports fast two-thumb typing, long-press accent popups, key previews,
 * delete auto-repeat and sliding along the space bar to move the cursor.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        /** Finger went down on a key (for vibration / sound). */
        fun onKeyDown(key: Key)
        fun onKey(key: Key)
        fun onPopupText(text: String)
        fun onCursorMove(steps: Int)
        fun onSpaceLongPress()
        /** Swiping left from delete: select this many words back (0 = none). */
        fun onDeleteSwipe(words: Int)
        fun onDeleteSwipeEnd(commit: Boolean)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---- appearance -------------------------------------------------------

    var rows: List<List<Key>> = emptyList()
        set(value) {
            if (field === value) return
            field = value
            cancelTouch()
            requestLayout()
            computeRects()
            invalidate()
        }

    var keyHeightPx: Float = dp(54f)
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            computeRects()
            invalidate()
        }

    var palette: Palette = Palette.DARK
        set(value) { field = value; invalidate() }

    var shiftState: ShiftState = ShiftState.OFF
        set(value) { if (field != value) { field = value; invalidate() } }

    var lettersMode: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Text shown on the enter key ("Go", "Send" …) or null for the return arrow. */
    var enterLabel: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    var showPreview = true
    var showHints = true

    // ---- geometry ---------------------------------------------------------

    private class KeyRect(val key: Key, val rect: RectF)

    private val keyRects = ArrayList<KeyRect>()
    private val padTop = dp(6f)
    private val padBottom = dp(4f)
    private val padSide = dp(3f)
    private val gapX = dp(5f)
    private val gapY = dp(8f)
    private val radius = dp(7f)

    // ---- paints -----------------------------------------------------------

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val tmp = RectF()
    private val tmp2 = RectF()

    // ---- touch state ------------------------------------------------------

    private val uiHandler = Handler(Looper.getMainLooper())
    private var activePointer = -1
    private var pressed: KeyRect? = null
    private var downX = 0f
    private var curX = 0f
    private var lastX = 0f
    private var spaceDragging = false
    private var longPressFired = false
    private var popupChars: List<String> = emptyList()
    private var popupIndex = 0
    private val popupRect = RectF()

    private var deleteRepeated = false
    private var deleteSwiping = false
    private var deleteWords = 0

    private val longPressRunnable = Runnable { onLongPress() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val p = pressed ?: return
            deleteRepeated = true
            listener.onKey(p.key)
            uiHandler.postDelayed(this, REPEAT_INTERVAL)
        }
    }

    // ---- layout -----------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (padTop + rows.size * keyHeightPx + padBottom).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRects()
    }

    private fun computeRects() {
        keyRects.clear()
        if (width == 0) return
        val unit = (width - 2 * padSide) / 10f
        var y = padTop
        for (row in rows) {
            val total = row.fold(0f) { acc, k -> acc + k.width }
            var x = padSide + (10f - total) / 2f * unit
            for (key in row) {
                val w = key.width * unit
                keyRects.add(KeyRect(key, RectF(x, y, x + w, y + keyHeightPx)))
                x += w
            }
            y += keyHeightPx
        }
    }

    private fun keyAt(x: Float, y: Float): KeyRect? {
        var best: KeyRect? = null
        var bestD = Float.MAX_VALUE
        for (kr in keyRects) {
            val r = kr.rect
            if (r.contains(x, y)) return kr
            val dx = max(0f, max(r.left - x, x - r.right))
            val dy = max(0f, max(r.top - y, y - r.bottom))
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = kr }
        }
        return best
    }

    // ---- drawing ----------------------------------------------------------

    private fun shown(s: String) =
        if (lettersMode && shiftState != ShiftState.OFF) s.uppercase() else s

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val p = pressed
        for (kr in keyRects) {
            drawKey(canvas, kr, kr === p && popupChars.isEmpty())
        }
        if (p != null && popupChars.isEmpty() && showPreview && p.key.isCharacter) {
            drawPreview(canvas, p)
        }
        if (popupChars.isNotEmpty()) drawPopup(canvas)
    }

    private fun drawKey(c: Canvas, kr: KeyRect, isPressed: Boolean) {
        val k = kr.key
        val r = kr.rect
        tmp.set(r.left + gapX / 2, r.top + gapY / 2, r.right - gapX / 2, r.bottom - gapY / 2)

        val shiftOn = k.code == Key.SHIFT && shiftState != ShiftState.OFF
        val bg: Int
        val fg: Int
        when {
            k.code == Key.ENTER -> { bg = palette.accent; fg = palette.accentText }
            shiftOn -> { bg = palette.on; fg = palette.onText }
            k.isFunction -> { bg = palette.fnKey; fg = palette.fnText }
            else -> { bg = if (isPressed) palette.keyPressed else palette.key; fg = palette.text }
        }
        // Flat keys like Gboard; the enter key is a pill.
        val rad = if (k.code == Key.ENTER) tmp.height() / 2f else radius
        keyPaint.color = bg
        c.drawRoundRect(tmp, rad, rad, keyPaint)
        if (isPressed && (k.isFunction || k.code == Key.ENTER || shiftOn)) {
            keyPaint.color = if (palette.isDark) 0x22FFFFFF else 0x22000000
            c.drawRoundRect(tmp, rad, rad, keyPaint)
        }

        val cx = tmp.centerX()
        val cy = tmp.centerY()
        when (k.code) {
            Key.SHIFT -> drawShift(c, cx, cy, fg)
            Key.DELETE -> drawDelete(c, cx, cy, fg)
            Key.ENTER -> {
                val label = enterLabel
                if (label != null) drawText(c, label, cx, cy, fg, keyHeightPx * 0.27f)
                else drawEnter(c, cx, cy, fg)
            }
            Key.EMOJI -> {
                iconPaint.color = fg
                iconPaint.strokeWidth = dp(1.8f)
                Icons.smiley(c, iconPaint, cx, cy, keyHeightPx * 0.4f)
            }
            Key.SPACE -> Unit
            Key.TEXT -> {
                val emoji = !k.label[0].isLetterOrDigit() && k.label.length > 1
                drawText(c, shown(k.label), cx, cy, fg, keyHeightPx * (if (emoji) 0.5f else 0.44f))
                // Gboard shows the number hints on the top row when the number row is off.
                if (showHints && k.popup.isNotEmpty() && k.popup[0][0].isDigit()) {
                    hintPaint.color = palette.hint
                    hintPaint.textSize = keyHeightPx * 0.22f
                    c.drawText(k.popup[0], tmp.right - dp(7f), tmp.top + keyHeightPx * 0.24f, hintPaint)
                }
            }
            else -> drawText(c, k.label, cx, cy, fg, keyHeightPx * 0.3f)
        }
    }

    private fun drawText(c: Canvas, s: String, cx: Float, cy: Float, color: Int, size: Float) {
        textPaint.color = color
        textPaint.textSize = size
        c.drawText(s, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun drawShift(c: Canvas, cx: Float, cy: Float, color: Int) {
        val s = keyHeightPx * 0.34f
        val top = cy - s * 0.6f
        path.reset()
        path.moveTo(cx, top)
        path.lineTo(cx + s / 2, top + s / 2)
        path.lineTo(cx + s / 4, top + s / 2)
        path.lineTo(cx + s / 4, top + s)
        path.lineTo(cx - s / 4, top + s)
        path.lineTo(cx - s / 4, top + s / 2)
        path.lineTo(cx - s / 2, top + s / 2)
        path.close()
        iconPaint.color = color
        iconPaint.strokeWidth = dp(1.8f)
        iconPaint.style = if (shiftState == ShiftState.OFF) Paint.Style.STROKE else Paint.Style.FILL_AND_STROKE
        c.drawPath(path, iconPaint)
        if (shiftState == ShiftState.LOCKED) {
            iconPaint.style = Paint.Style.STROKE
            val ly = top + s * 1.3f
            c.drawLine(cx - s / 4, ly, cx + s / 4, ly, iconPaint)
        }
    }

    private fun drawDelete(c: Canvas, cx: Float, cy: Float, color: Int) {
        val s = keyHeightPx * 0.42f
        val h = s * 0.32f
        path.reset()
        path.moveTo(cx - s / 2, cy)
        path.lineTo(cx - s / 5, cy - h)
        path.lineTo(cx + s / 2, cy - h)
        path.lineTo(cx + s / 2, cy + h)
        path.lineTo(cx - s / 5, cy + h)
        path.close()
        iconPaint.color = color
        iconPaint.strokeWidth = dp(1.8f)
        iconPaint.style = Paint.Style.STROKE
        c.drawPath(path, iconPaint)
        val xc = cx + s * 0.15f
        val d = h * 0.45f
        c.drawLine(xc - d, cy - d, xc + d, cy + d, iconPaint)
        c.drawLine(xc - d, cy + d, xc + d, cy - d, iconPaint)
    }

    private fun drawEnter(c: Canvas, cx: Float, cy: Float, color: Int) {
        val s = keyHeightPx * 0.38f
        iconPaint.color = color
        iconPaint.strokeWidth = dp(2f)
        iconPaint.style = Paint.Style.STROKE
        val right = cx + s / 2
        val left = cx - s / 2
        val baseY = cy + s * 0.15f
        path.reset()
        path.moveTo(right, cy - s * 0.4f)
        path.lineTo(right, baseY)
        path.lineTo(left, baseY)
        c.drawPath(path, iconPaint)
        val a = s * 0.25f
        c.drawLine(left, baseY, left + a, baseY - a, iconPaint)
        c.drawLine(left, baseY, left + a, baseY + a, iconPaint)
    }

    private fun drawPreview(c: Canvas, kr: KeyRect) {
        val r = kr.rect
        val w = r.width() + dp(6f)
        val h = keyHeightPx * 1.05f
        val left = (r.centerX() - w / 2).coerceIn(0f, max(0f, width - w))
        val top = max(0f, r.top - h + gapY / 2)
        tmp.set(left, top, left + w, top + h - gapY / 2)
        keyPaint.color = palette.divider
        tmp.offset(0f, dp(1.5f))
        c.drawRoundRect(tmp, radius, radius, keyPaint)
        tmp.offset(0f, -dp(1.5f))
        keyPaint.color = palette.popup
        c.drawRoundRect(tmp, radius, radius, keyPaint)
        drawText(c, shown(kr.key.label), tmp.centerX(), tmp.centerY(), palette.text, keyHeightPx * 0.5f)
    }

    private fun drawPopup(c: Canvas) {
        keyPaint.color = palette.divider
        tmp.set(popupRect)
        tmp.offset(0f, dp(1.5f))
        c.drawRoundRect(tmp, radius, radius, keyPaint)
        keyPaint.color = palette.popup
        c.drawRoundRect(popupRect, radius, radius, keyPaint)
        val cell = popupRect.width() / popupChars.size
        for (i in popupChars.indices) {
            val l = popupRect.left + i * cell
            tmp2.set(l + dp(3f), popupRect.top + dp(3f), l + cell - dp(3f), popupRect.bottom - dp(3f))
            var color = palette.text
            if (i == popupIndex) {
                keyPaint.color = palette.accent
                c.drawRoundRect(tmp2, radius, radius, keyPaint)
                color = palette.accentText
            }
            drawText(c, shown(popupChars[i]), tmp2.centerX(), tmp2.centerY(), color, keyHeightPx * 0.4f)
        }
    }

    // ---- touch ------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> startPress(e.getPointerId(0), e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landed: finish the first key straight away
                // so fast two-thumb typing never drops letters.
                val i = e.actionIndex
                if (pressed != null) releasePress(commit = true)
                startPress(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activePointer)
                if (i >= 0) movePress(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (e.getPointerId(e.actionIndex) == activePointer) releasePress(commit = true)
            }
            MotionEvent.ACTION_CANCEL -> releasePress(commit = false)
        }
        return true
    }

    private fun startPress(id: Int, x: Float, y: Float) {
        val kr = keyAt(x, y) ?: return
        activePointer = id
        pressed = kr
        downX = x; curX = x; lastX = x
        spaceDragging = false
        longPressFired = false
        popupChars = emptyList()
        deleteRepeated = false
        deleteSwiping = false
        deleteWords = 0
        listener.onKeyDown(kr.key)
        when (kr.key.code) {
            // Delete fires on release (or repeats when held) so a swipe can select words instead.
            Key.DELETE -> uiHandler.postDelayed(repeatRunnable, REPEAT_DELAY)
            Key.SPACE -> uiHandler.postDelayed(longPressRunnable, SPACE_LONG_PRESS)
            Key.TEXT -> if (kr.key.popup.isNotEmpty()) uiHandler.postDelayed(longPressRunnable, LONG_PRESS)
        }
        invalidate()
    }

    private fun movePress(x: Float, y: Float) {
        val p = pressed ?: return
        curX = x
        if (popupChars.isNotEmpty()) {
            val cell = popupRect.width() / popupChars.size
            val idx = ((x - popupRect.left) / cell).toInt().coerceIn(0, popupChars.size - 1)
            if (idx != popupIndex) { popupIndex = idx; invalidate() }
            return
        }
        when (p.key.code) {
            Key.DELETE -> {
                if (deleteRepeated) return
                val dx = downX - x
                val start = dp(24f)
                if (!deleteSwiping && dx > start) {
                    deleteSwiping = true
                    uiHandler.removeCallbacks(repeatRunnable)
                }
                if (deleteSwiping) {
                    val words = if (dx > start) 1 + ((dx - start) / dp(32f)).toInt() else 0
                    if (words != deleteWords) {
                        deleteWords = words
                        listener.onDeleteSwipe(words)
                    }
                }
            }
            Key.SPACE -> {
                val step = dp(12f)
                if (!spaceDragging && abs(x - downX) > dp(14f)) {
                    spaceDragging = true
                    uiHandler.removeCallbacks(longPressRunnable)
                    lastX = x
                }
                if (spaceDragging) {
                    val steps = ((x - lastX) / step).toInt()
                    if (steps != 0) {
                        listener.onCursorMove(steps)
                        lastX += steps * step
                    }
                }
            }
            Key.TEXT -> {
                // Let the finger slide onto a neighbouring letter before lifting.
                if (p.rect.contains(x, y)) return
                val kr = keyAt(x, y) ?: return
                if (kr === p || !kr.key.isCharacter) return
                uiHandler.removeCallbacks(longPressRunnable)
                pressed = kr
                downX = x
                if (kr.key.popup.isNotEmpty()) uiHandler.postDelayed(longPressRunnable, LONG_PRESS)
                invalidate()
            }
        }
    }

    private fun releasePress(commit: Boolean) {
        uiHandler.removeCallbacks(longPressRunnable)
        uiHandler.removeCallbacks(repeatRunnable)
        val p = pressed
        if (p != null && p.key.code == Key.DELETE && deleteSwiping) {
            listener.onDeleteSwipeEnd(commit)
        } else if (p != null && commit) {
            when {
                popupChars.isNotEmpty() -> listener.onPopupText(popupChars[popupIndex])
                p.key.code == Key.DELETE -> if (!deleteRepeated) listener.onKey(p.key)
                p.key.code == Key.SPACE && (spaceDragging || longPressFired) -> Unit
                else -> listener.onKey(p.key)
            }
        }
        deleteSwiping = false
        deleteRepeated = false
        deleteWords = 0
        pressed = null
        activePointer = -1
        popupChars = emptyList()
        spaceDragging = false
        longPressFired = false
        invalidate()
    }

    private fun cancelTouch() {
        if (pressed != null) releasePress(commit = false)
    }

    private fun onLongPress() {
        val p = pressed ?: return
        longPressFired = true
        if (p.key.code == Key.SPACE) {
            listener.onSpaceLongPress()
            return
        }
        val chars = p.key.popup
        if (chars.isEmpty()) return
        val cell = p.rect.width()
        val w = cell * chars.size
        val left = (p.rect.centerX() - cell / 2).coerceIn(padSide, max(padSide, width - padSide - w))
        val top = max(0f, p.rect.top - keyHeightPx)
        popupRect.set(left, top, left + w, top + keyHeightPx - gapY / 2)
        popupChars = chars
        popupIndex = ((curX - left) / cell).toInt().coerceIn(0, chars.size - 1)
        listener.onKeyDown(p.key)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val LONG_PRESS = 320L
        private const val SPACE_LONG_PRESS = 600L
        private const val REPEAT_DELAY = 400L
        private const val REPEAT_INTERVAL = 50L
    }
}
