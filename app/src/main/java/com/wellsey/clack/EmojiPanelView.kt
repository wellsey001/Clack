package com.wellsey.clack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Gboard-style emoji panel: category tabs, one long scrolling grid of every emoji
 * the phone can show, recently used emoji first, and long-press for skin tones.
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onEmoji(emoji: String)
    }

    private class Entry(val emoji: String, val tones: Boolean)
    private class Category(val name: String, val icon: String, val entries: List<Entry>)
    private class Row(val top: Float, val height: Float, val category: Int, val header: String?, val entries: List<Entry>)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var palette: Palette = Palette.DARK
        set(value) { field = value; invalidate() }

    /** Height in px, set by the keyboard so switching to emoji doesn't resize anything. */
    var panelHeight: Int = 0
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val prefs = context.getSharedPreferences("clack_emoji", Context.MODE_PRIVATE)
    private var allCategories: List<Category> = emptyList()
    private var categories: List<Category> = emptyList()
    private val rows = ArrayList<Row>()
    private var contentHeight = 0f
    private var scrollPos = 0f

    private val tabH = dp(38f)
    private val headerH = dp(26f)
    private val cols = 9

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    private val scroller = OverScroller(context)
    private var popup: List<String> = emptyList()
    private val popupRect = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (popup.isNotEmpty()) { popup = emptyList() }
            scrollTo(scrollPos + dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollPos.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (popup.isNotEmpty()) {
                if (popupRect.contains(e.x, e.y)) {
                    val cell = popupRect.width() / popup.size
                    val i = ((e.x - popupRect.left) / cell).toInt().coerceIn(0, popup.size - 1)
                    emit(popup[i])
                }
                popup = emptyList()
                invalidate()
                return true
            }
            if (e.y < tabH) {
                val i = (e.x / (width / max(1, categories.size).toFloat())).toInt()
                jumpTo(i)
                return true
            }
            entryAt(e.x, e.y)?.let { emit(it.emoji) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (e.y < tabH) return
            val entry = entryAt(e.x, e.y) ?: return
            if (!entry.tones) return
            val variants = listOf(entry.emoji) + TONES.map { withTone(entry.emoji, it) }.filter { emojiPaint.hasGlyph(it) }
            if (variants.size < 2) return
            val cell = width / cols.toFloat()
            val w = cell * variants.size
            val left = (e.x - w / 2).coerceIn(0f, max(0f, width - w))
            val top = max(tabH, e.y - cell * 1.6f)
            popupRect.set(left, top, left + w, top + cell)
            popup = variants
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    })

    init {
        loadInBackground()
    }

    private fun loadInBackground() {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val checker = Paint()
            val list = ArrayList<Category>()
            try {
                context.assets.open("emoji.txt").bufferedReader().useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@forEachIndexed
                        val name = line.substring(0, tab)
                        val entries = line.substring(tab + 1).split(' ').mapNotNull { raw ->
                            if (raw.isEmpty()) return@mapNotNull null
                            val tones = raw.startsWith("~")
                            val e = if (tones) raw.substring(1) else raw
                            // Only show emoji this phone's font can actually draw.
                            if (checker.hasGlyph(e)) Entry(e, tones) else null
                        }
                        list.add(Category(name, ICONS.getOrElse(i) { "🙂" }, entries))
                    }
                }
            } catch (_: Exception) {
            }
            main.post {
                allCategories = list
                refresh()
            }
        }.apply { isDaemon = true; start() }
    }

    /** Rebuild with the latest recently-used emoji (call when the panel opens). */
    fun refresh() {
        val recent = recents().map { Entry(it, false) }
        categories = buildList {
            if (recent.isNotEmpty()) add(Category("Recently used", RECENT_ICON, recent))
            addAll(allCategories)
        }
        popup = emptyList()
        layoutRows()
        scrollPos = scrollPos.coerceIn(0f, maxScroll())
        invalidate()
    }

    private fun recents(): List<String> =
        prefs.getString("recent", "")!!.split(' ').filter { it.isNotEmpty() }

    private fun emit(emoji: String) {
        val list = recents().toMutableList()
        list.remove(emoji)
        list.add(0, emoji)
        prefs.edit().putString("recent", list.take(MAX_RECENT).joinToString(" ")).apply()
        listener.onEmoji(emoji)
    }

    // ---- layout -----------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), panelHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutRows()
    }

    private fun cellSize() = width / cols.toFloat()

    private fun layoutRows() {
        rows.clear()
        if (width == 0) return
        val cell = cellSize()
        var y = 0f
        categories.forEachIndexed { ci, cat ->
            rows.add(Row(y, headerH, ci, cat.name, emptyList()))
            y += headerH
            val n = ceil(cat.entries.size / cols.toFloat()).toInt()
            for (r in 0 until n) {
                val slice = cat.entries.subList(r * cols, min(cat.entries.size, (r + 1) * cols))
                rows.add(Row(y, cell, ci, null, slice))
                y += cell
            }
        }
        contentHeight = y
    }

    private fun gridHeight() = max(0f, height - tabH)
    private fun maxScroll() = max(0f, contentHeight - gridHeight())

    private fun scrollTo(y: Float) {
        scrollPos = y.coerceIn(0f, maxScroll())
        invalidate()
    }

    private fun jumpTo(category: Int) {
        val row = rows.firstOrNull { it.category == category && it.header != null } ?: return
        scroller.forceFinished(true)
        scrollTo(row.top)
    }

    private fun currentCategory(): Int {
        var c = 0
        for (r in rows) {
            if (r.header != null && r.top <= scrollPos + 1f) c = r.category
            if (r.top > scrollPos + 1f) break
        }
        return c
    }

    private fun entryAt(x: Float, y: Float): Entry? {
        if (y < tabH) return null
        val cy = y - tabH + scrollPos
        val row = rows.firstOrNull { cy >= it.top && cy < it.top + it.height } ?: return null
        if (row.header != null) return null
        val i = (x / cellSize()).toInt()
        return row.entries.getOrNull(i)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    // ---- drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val cell = cellSize()

        if (rows.isEmpty()) {
            headerPaint.color = palette.hint
            headerPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Loading emoji…", width / 2f, height / 2f, headerPaint)
        }

        // grid
        canvas.save()
        canvas.clipRect(0f, tabH, width.toFloat(), height.toFloat())
        canvas.translate(0f, tabH - scrollPos)
        emojiPaint.textSize = cell * 0.62f
        val bottom = scrollPos + gridHeight()
        for (r in rows) {
            if (r.top + r.height < scrollPos) continue
            if (r.top > bottom) break
            if (r.header != null) {
                headerPaint.color = palette.hint
                headerPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(r.header, dp(12f), r.top + r.height * 0.68f, headerPaint)
            } else {
                val ty = r.top + r.height / 2f - (emojiPaint.descent() + emojiPaint.ascent()) / 2f
                r.entries.forEachIndexed { i, e ->
                    canvas.drawText(e.emoji, i * cell + cell / 2f, ty, emojiPaint)
                }
            }
        }
        canvas.restore()

        // category tabs
        fill.color = palette.background
        canvas.drawRect(0f, 0f, width.toFloat(), tabH, fill)
        fill.color = palette.divider
        canvas.drawRect(0f, tabH - dp(0.7f), width.toFloat(), tabH, fill)
        if (categories.isNotEmpty()) {
            val tabW = width / categories.size.toFloat()
            val current = currentCategory()
            categories.forEachIndexed { i, cat ->
                val cx = i * tabW + tabW / 2f
                val cy = tabH / 2f
                if (i == current) {
                    fill.color = palette.fnKey
                    val half = min(tabW / 2f - dp(3f), dp(22f))
                    rect.set(cx - half, cy - dp(13f), cx + half, cy + dp(13f))
                    canvas.drawRoundRect(rect, dp(13f), dp(13f), fill)
                }
                if (cat.icon == RECENT_ICON) {
                    iconPaint.color = if (i == current) palette.fnText else palette.icon
                    Icons.clock(canvas, iconPaint, cx, cy, dp(17f))
                } else {
                    emojiPaint.textSize = dp(17f)
                    emojiPaint.alpha = if (i == current) 255 else 150
                    canvas.drawText(cat.icon, cx, cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2f, emojiPaint)
                    emojiPaint.alpha = 255
                }
            }
        }

        // skin-tone popup
        if (popup.isNotEmpty()) {
            fill.color = palette.divider
            rect.set(popupRect)
            rect.offset(0f, dp(1.5f))
            canvas.drawRoundRect(rect, dp(10f), dp(10f), fill)
            fill.color = palette.popup
            canvas.drawRoundRect(popupRect, dp(10f), dp(10f), fill)
            val pc = popupRect.width() / popup.size
            emojiPaint.textSize = pc * 0.62f
            val ty = popupRect.centerY() - (emojiPaint.descent() + emojiPaint.ascent()) / 2f
            popup.forEachIndexed { i, e -> canvas.drawText(e, popupRect.left + i * pc + pc / 2f, ty, emojiPaint) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || true

    companion object {
        private const val MAX_RECENT = 36
        private const val RECENT_ICON = "recent"
        private val ICONS = listOf("😀", "🐻", "🍔", "🚗", "⚽", "💡", "🔣", "🏁")
        private val TONES = (0x1F3FB..0x1F3FF).map { String(Character.toChars(it)) }

        /** Adds a skin tone after the first character (dropping any FE0F style marker). */
        fun withTone(emoji: String, tone: String): String {
            val first = Character.charCount(emoji.codePointAt(0))
            var rest = emoji.substring(first)
            if (rest.startsWith("️")) rest = rest.substring(1)
            return emoji.substring(0, first) + tone + rest
        }
    }
}
