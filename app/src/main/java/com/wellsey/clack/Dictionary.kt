package com.wellsey.clack

import android.content.Context
import java.io.File
import java.util.Arrays

/**
 * Word completions and spelling corrections.
 *
 * Uses a 50,000-word English list ranked by how common each word is, plus the
 * words you type yourself. A word you've used twice counts as correctly spelt
 * from then on. Learned words stay on the phone and are never taken from
 * password, email or web address fields.
 */
class Dictionary(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "learned_words.tsv")
    private val learned = HashMap<String, Int>()
    private var dirty = false

    @Volatile private var words: Array<String> = emptyArray()
    @Volatile private var rank: Map<String, Int> = emptyMap()
    @Volatile private var loaded = false

    private var cacheKey: String? = null
    private var cacheValue: List<String> = emptyList()

    init {
        try {
            if (file.exists()) {
                file.forEachLine { line ->
                    val parts = line.split('\t')
                    if (parts.size == 2) parts[1].toIntOrNull()?.let { learned[parts[0]] = it }
                }
            }
        } catch (_: Exception) {
        }
        Thread {
            try {
                val list = appContext.assets.open("words.txt").bufferedReader().useLines { seq ->
                    seq.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                }
                val r = HashMap<String, Int>(list.size * 2)
                list.forEachIndexed { i, w -> r.putIfAbsent(w, i) }
                val sorted = list.toTypedArray()
                Arrays.sort(sorted)
                rank = r
                words = sorted
                loaded = true
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
    }

    /** True if the word is in the dictionary or you've used it at least twice. */
    fun isKnown(word: String): Boolean {
        if (!loaded) return true // don't flag everything while still loading
        val w = word.lowercase()
        return rank.containsKey(w) || (learned[w] ?: 0) >= LEARN_THRESHOLD
    }

    fun isLearned(word: String) = learned.containsKey(word.lowercase())

    fun learn(word: String) {
        if (word.length < 2 || word.length > 32) return
        if (!word.all { it.isLetter() || it == '\'' }) return
        val w = word.lowercase()
        learned[w] = (learned[w] ?: 0) + 1
        dirty = true
        cacheKey = null
    }

    /** Removes a word you've taught it. Built-in words can't be removed. */
    fun forget(word: String): Boolean {
        val removed = learned.remove(word.lowercase()) != null
        if (removed) {
            dirty = true
            cacheKey = null
            save()
        }
        return removed
    }

    /**
     * Best words for what's been typed so far: completions ("hel" → "hello")
     * and, if it doesn't look like a real word, spelling fixes ("teh" → "the").
     */
    fun suggest(typed: String, limit: Int): List<String> {
        val key = "$typed|$limit|$loaded"
        if (key == cacheKey) return cacheValue
        val t = typed.lowercase()
        val scores = HashMap<String, Double>()
        if (t.isNotEmpty()) {
            addCompletions(t, scores)
            if (loaded && !isKnown(t)) {
                var found = false
                for ((cand, weight) in edits(t)) {
                    if (!isCandidate(cand)) continue
                    found = true
                    var s = freqScore(cand) * weight
                    if (t.length <= 3) s *= 0.25
                    if (s > (scores[cand] ?: 0.0)) scores[cand] = s
                }
                // Two mistakes in one word: only worth the work for longer words.
                if (!found && scores.isEmpty() && t.length in 4..12) {
                    for ((e1, w1) in edits(t)) {
                        for ((e2, w2) in edits(e1)) {
                            if (e2 == t || !isCandidate(e2)) continue
                            val s = freqScore(e2) * w1 * w2 * 0.3
                            if (s > (scores[e2] ?: 0.0)) scores[e2] = s
                        }
                    }
                }
            }
        }
        scores.remove(t)
        val result = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key.length })
            .take(limit)
            .map { matchCase(it.key, typed) }
        cacheKey = key
        cacheValue = result
        return result
    }

    private fun isCandidate(w: String) = rank.containsKey(w) || learned.containsKey(w)

    private fun freqScore(w: String): Double {
        val r = rank[w]
        val base = if (r != null) 1_000_000.0 / (r + 100) else 0.0
        return base + (learned[w] ?: 0) * 1500.0
    }

    private fun addCompletions(p: String, scores: HashMap<String, Double>) {
        val arr = words
        val idx = Arrays.binarySearch(arr, p)
        var i = if (idx >= 0) idx else -idx - 1
        var n = 0
        while (i < arr.size && n < 20_000 && arr[i].startsWith(p)) {
            val w = arr[i]
            if (w.length > p.length) scores[w] = completionScore(w, p.length)
            i++
            n++
        }
        for (w in learned.keys) {
            if (w.length > p.length && w.startsWith(p)) scores[w] = completionScore(w, p.length)
        }
    }

    private fun completionScore(w: String, typedLen: Int): Double =
        freqScore(w) * 0.6 * Math.pow(0.8, (w.length - typedLen).toDouble())

    /** All single-edit variants of [w] with how likely each mistake is. */
    private fun edits(w: String): Map<String, Double> {
        val out = HashMap<String, Double>()
        fun put(s: String, weight: Double) {
            if (s.isNotEmpty() && weight > (out[s] ?: 0.0)) out[s] = weight
        }
        val n = w.length
        for (i in 0 until n) {
            // deletion (you typed an extra letter)
            val del = w.removeRange(i, i + 1)
            val doubled = (i > 0 && w[i - 1] == w[i]) || (i < n - 1 && w[i + 1] == w[i])
            put(del, when {
                doubled -> 0.9
                i == n - 1 -> 0.05 // probably still typing, not a mistake
                else -> 0.6
            })
            // transposition (letters swapped)
            if (i < n - 1) {
                val chars = w.toCharArray()
                val tmp = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = tmp
                put(String(chars), 1.0)
            }
            // replacement (hit the wrong key)
            for (c in LETTERS) {
                if (c == w[i]) continue
                put(w.substring(0, i) + c + w.substring(i + 1), if (isAdjacent(w[i], c)) 0.8 else 0.3)
            }
        }
        // insertion (missed a letter or apostrophe)
        for (i in 0..n) {
            for (c in INSERTABLE) {
                val weight = when {
                    c == '\'' -> 1.2
                    (i > 0 && w[i - 1] == c) || (i < n && w[i] == c) -> 0.9
                    else -> 0.6
                }
                put(w.substring(0, i) + c + w.substring(i), weight)
            }
        }
        return out
    }

    fun save() {
        if (!dirty) return
        try {
            val top = learned.entries.sortedByDescending { it.value }.take(MAX_LEARNED)
            file.writeText(top.joinToString("\n") { "${it.key}\t${it.value}" })
            dirty = false
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_LEARNED = 5000
        const val LEARN_THRESHOLD = 2
        private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
        private const val INSERTABLE = "abcdefghijklmnopqrstuvwxyz'"

        private val keyPos: Map<Char, Pair<Float, Float>> = HashMap<Char, Pair<Float, Float>>().apply {
            val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
            rows.forEachIndexed { y, (letters, offset) ->
                letters.forEachIndexed { x, ch -> put(ch, Pair(x + offset, y.toFloat())) }
            }
        }

        private fun isAdjacent(a: Char, b: Char): Boolean {
            val pa = keyPos[a] ?: return false
            val pb = keyPos[b] ?: return false
            return Math.abs(pa.first - pb.first) <= 1.1f && Math.abs(pa.second - pb.second) <= 1f
        }

        /** Apply the capitalisation the user typed (Hel → Hello, HEL → HELLO). */
        fun matchCase(word: String, typed: String): String {
            val fixed = when {
                word == "i" -> "I"
                word.startsWith("i'") -> "I" + word.substring(1)
                else -> word
            }
            if (typed.isEmpty()) return fixed
            return when {
                typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> fixed.uppercase()
                typed[0].isUpperCase() -> fixed.replaceFirstChar { it.uppercase() }
                else -> fixed
            }
        }
    }
}
