package com.wellsey.clack

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/**
 * Glide typing: turns a swipe across the letter keys into the likeliest words.
 *
 * For each dictionary word that starts near where the finger went down and ends
 * near where it lifted, it builds the word's ideal path through its keys, then scores:
 *  - how closely the swipe's shape follows that path,
 *  - how close the swipe starts and ends to the first and last keys,
 *  - how close every other key is to the swipe, matched in order,
 *  - how common the word is, and how well it follows the previous word.
 *
 * Positions are in key widths. Tuned on simulated swipes in tools/glide_test.py
 * (keep the two in step): about 87% right first time, 96% in the top three.
 */
object GlideDecoder {
    private const val N = 40              // points each path is resampled to
    private const val NEAR_START = 1.25   // first/last key within this many key widths
    private const val PASS = 1.6          // every key must be this close to the swipe
    private const val W_SHAPE = 6.0
    private const val W_ENDS = 3.0
    private const val W_KEYS = 1.0
    private const val W_PRIOR = 0.4
    private const val W_CONTEXT = 0.8

    /**
     * @param xs, ys the swipe, in key widths
     * @param centers each letter key's centre, in key widths
     * @param candidatesFor words starting with a given letter
     * @param logPrior how common a word is (log scale)
     * @param contextBonus how well a word follows the previous words (1 = no opinion)
     */
    fun decode(
        xs: FloatArray,
        ys: FloatArray,
        centers: Map<Char, FloatArray>,
        candidatesFor: (Char) -> List<String>,
        logPrior: (String) -> Double,
        contextBonus: (String) -> Double,
        top: Int = 3,
    ): List<String> {
        if (xs.size < 2) return emptyList()
        val gx = DoubleArray(xs.size) { xs[it].toDouble() }
        val gy = DoubleArray(ys.size) { ys[it].toDouble() }
        val swipeLen = pathLength(gx, gy)
        val (rx, ry) = resample(gx, gy)

        val firsts = centers.filter { dist(it.value, gx[0], gy[0]) <= NEAR_START }.keys
        val lasts = centers.filter { dist(it.value, gx.last(), gy.last()) <= NEAR_START }.keys.toSet()

        val scored = ArrayList<Pair<Double, String>>()
        for (first in firsts) {
            for (word in candidatesFor(first)) {
                val letters = lettersOf(word)
                if (letters.size < 2 || letters.last() !in lasts) continue
                val ix = DoubleArray(letters.size)
                val iy = DoubleArray(letters.size)
                var missing = false
                for ((i, c) in letters.withIndex()) {
                    val p = centers[c]
                    if (p == null) { missing = true; break }
                    ix[i] = p[0].toDouble()
                    iy[i] = p[1].toDouble()
                }
                if (missing) continue
                val idealLen = pathLength(ix, iy)
                if (abs(swipeLen - idealLen) > 1.5 + 0.6 * idealLen) continue

                val keyErr = align(ix, iy, rx, ry) ?: continue
                val ends = sq(gx[0] - ix[0], gy[0] - iy[0]) + sq(gx.last() - ix.last(), gy.last() - iy.last())
                val (px, py) = resample(ix, iy)
                var d = 0.0
                for (k in 0 until N) d += hypot(rx[k] - px[k], ry[k] - py[k])
                d /= N
                val score = -W_SHAPE * d * d - W_ENDS * ends - W_KEYS * keyErr +
                    W_PRIOR * logPrior(word) + W_CONTEXT * ln(contextBonus(word))
                scored.add(score to word)
            }
        }
        return scored.sortedByDescending { it.first }.map { it.second }.distinct().take(top)
    }

    /** "hello" → h e l o: letters only, repeats collapsed (a double letter is one key). */
    private fun lettersOf(word: String): List<Char> {
        val out = ArrayList<Char>(word.length)
        for (ch in word) {
            if (!ch.isLetter()) continue
            if (out.isEmpty() || out.last() != ch) out.add(ch)
        }
        return out
    }

    /**
     * Matches each key between the first and last to a point on the swipe, in order,
     * as closely as possible. Mean squared distance, or null if a key is too far away.
     */
    private fun align(ix: DoubleArray, iy: DoubleArray, rx: DoubleArray, ry: DoubleArray): Double? {
        val inner = ix.size - 2
        if (inner <= 0) return 0.0
        var prev = DoubleArray(N) // first row: nothing before
        for (i in 1..inner) {
            val cur = DoubleArray(N) { Double.POSITIVE_INFINITY }
            var bestBefore = if (i == 1) 0.0 else Double.POSITIVE_INFINITY
            for (k in 0 until N) {
                if (i > 1) bestBefore = min(bestBefore, prev[k])
                val d = hypot(ix[i] - rx[k], iy[i] - ry[k])
                if (d <= PASS && bestBefore < Double.POSITIVE_INFINITY) cur[k] = bestBefore + d * d
            }
            prev = cur
        }
        val total = prev.min()
        return if (total == Double.POSITIVE_INFINITY) null else total / inner
    }

    private fun resample(xs: DoubleArray, ys: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val ox = DoubleArray(N)
        val oy = DoubleArray(N)
        val total = pathLength(xs, ys)
        if (xs.size == 1 || total == 0.0) {
            ox.fill(xs[0]); oy.fill(ys[0])
            return ox to oy
        }
        val step = total / (N - 1)
        ox[0] = xs[0]; oy[0] = ys[0]
        var count = 1
        var acc = 0.0
        var px = xs[0]
        var py = ys[0]
        var i = 1
        while (i < xs.size && count < N) {
            val d = hypot(xs[i] - px, ys[i] - py)
            if (acc + d >= step && d > 0) {
                val t = (step - acc) / d
                px += t * (xs[i] - px)
                py += t * (ys[i] - py)
                ox[count] = px; oy[count] = py
                count++
                acc = 0.0
            } else {
                acc += d
                px = xs[i]; py = ys[i]
                i++
            }
        }
        while (count < N) {
            ox[count] = xs.last(); oy[count] = ys.last()
            count++
        }
        return ox to oy
    }

    private fun pathLength(xs: DoubleArray, ys: DoubleArray): Double {
        var t = 0.0
        for (i in 0 until xs.size - 1) t += hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
        return t
    }

    private fun dist(p: FloatArray, x: Double, y: Double) = hypot(p[0] - x, p[1] - y)
    private fun sq(dx: Double, dy: Double) = dx * dx + dy * dy
}
