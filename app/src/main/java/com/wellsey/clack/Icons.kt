package com.wellsey.clack

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** Simple line icons drawn straight onto the canvas (size = icon box in px). */
object Icons {
    private val rect = RectF()
    private val path = Path()

    fun smiley(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE
        c.drawCircle(cx, cy, s / 2, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx - s * 0.17f, cy - s * 0.1f, s * 0.06f, p)
        c.drawCircle(cx + s * 0.17f, cy - s * 0.1f, s * 0.06f, p)
        p.style = Paint.Style.STROKE
        rect.set(cx - s * 0.24f, cy - s * 0.12f, cx + s * 0.24f, cy + s * 0.27f)
        c.drawArc(rect, 25f, 130f, false, p)
    }

    fun clipboard(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE
        rect.set(cx - s * 0.36f, cy - s * 0.4f, cx + s * 0.36f, cy + s * 0.5f)
        c.drawRoundRect(rect, s * 0.08f, s * 0.08f, p)
        p.style = Paint.Style.FILL_AND_STROKE
        rect.set(cx - s * 0.17f, cy - s * 0.52f, cx + s * 0.17f, cy - s * 0.32f)
        c.drawRoundRect(rect, s * 0.05f, s * 0.05f, p)
        p.style = Paint.Style.STROKE
    }

    fun gear(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE
        c.drawCircle(cx, cy, s * 0.14f, p)
        c.drawCircle(cx, cy, s * 0.33f, p)
        val w = p.strokeWidth
        p.strokeWidth = w * 2.2f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * s * 0.36f, cy + sa * s * 0.36f, cx + ca * s * 0.47f, cy + sa * s * 0.47f, p)
        }
        p.strokeWidth = w
    }

    fun globe(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE
        c.drawCircle(cx, cy, s / 2, p)
        rect.set(cx - s * 0.2f, cy - s / 2, cx + s * 0.2f, cy + s / 2)
        c.drawOval(rect, p)
        c.drawLine(cx - s / 2, cy, cx + s / 2, cy, p)
    }

    fun chevronDown(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE
        path.reset()
        path.moveTo(cx - s * 0.35f, cy - s * 0.15f)
        path.lineTo(cx, cy + s * 0.2f)
        path.lineTo(cx + s * 0.35f, cy - s * 0.15f)
        c.drawPath(path, p)
    }

    fun clock(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE
        c.drawCircle(cx, cy, s / 2, p)
        c.drawLine(cx, cy, cx, cy - s * 0.28f, p)
        c.drawLine(cx, cy, cx + s * 0.2f, cy + s * 0.12f, p)
    }

    fun search(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE
        val r = s * 0.3f
        val ox = cx - s * 0.1f
        val oy = cy - s * 0.1f
        c.drawCircle(ox, oy, r, p)
        c.drawLine(ox + r * 0.72f, oy + r * 0.72f, cx + s * 0.42f, cy + s * 0.42f, p)
    }

    fun back(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE
        c.drawLine(cx - s * 0.4f, cy, cx + s * 0.4f, cy, p)
        c.drawLine(cx - s * 0.4f, cy, cx - s * 0.1f, cy - s * 0.3f, p)
        c.drawLine(cx - s * 0.4f, cy, cx - s * 0.1f, cy + s * 0.3f, p)
    }
}
