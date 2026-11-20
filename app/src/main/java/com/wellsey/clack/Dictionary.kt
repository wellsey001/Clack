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
    /** "isnt" → "isn't", "im" → "i'm", "ill" → "i'll" … */
    @Volatile private var contractionOf: Map<String, String> = emptyMap()
    /** Words grouped by first letter, for the two-mistake search. */
    @Volatile private var byInitial: Map<Char, Array<String>> = emptyMap()

    private var autoKey: String? = null
    private var autoValue: String? = null

    /** Words you undid an auto-correction for: never auto-correct these again. */
    private val rejected = HashSet<String>()

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
                val c = HashMap<String, String>()
                for (w in list) if ('\'' in w) c.putIfAbsent(w.replace("'", ""), w)
                contractionOf = c
                byInitial = list.groupBy { it[0] }.mapValues { it.value.toTypedArray() }
                rank = r
                words = sorted
                loaded = true
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
    }

    /** True if the word is in the dictionary or you've used it at least twice. */
    @Synchronized
    fun isKnown(word: String): Boolean {
        if (!loaded) return true // don't flag everything while still loading
        val w = word.lowercase()
        if (rank.containsKey(w)) return true
        if (isMissingApostrophe(w)) return false
        return (learned[w] ?: 0) >= LEARN_THRESHOLD
    }

    /** True for "isnt", "dont", "im": a contraction typed without its apostrophe. */
    private fun isMissingApostrophe(w: String) = !rank.containsKey(w) && contractionOf.containsKey(w)

    @Synchronized
    fun isLearned(word: String) = learned.containsKey(word.lowercase())

    @Synchronized
    fun learn(word: String) {
        if (word.length < 2 || word.length > 32) return
        if (!word.all { it.isLetter() || it == '\'' }) return
        val w = word.lowercase()
        if (isMissingApostrophe(w)) return // never learn "isnt" as a word
        learned[w] = (learned[w] ?: 0) + 1
        dirty = true
        cacheKey = null
        autoKey = null
    }

    /** Removes a word you've taught it. Built-in words can't be removed. */
    @Synchronized
    fun forget(word: String): Boolean {
        val removed = learned.remove(word.lowercase()) != null
        if (removed) {
            dirty = true
            cacheKey = null
            autoKey = null
            save()
        }
        return removed
    }

    /**
     * Best words for what's been typed so far: completions ("hel" → "hello")
     * and, if it doesn't look like a real word, spelling fixes ("teh" → "the").
     */
    @Synchronized
    fun suggest(typed: String, limit: Int): List<String> {
        val key = "$typed|$limit|$loaded"
        if (key == cacheKey) return cacheValue
        val t = typed.lowercase()
        val scores = HashMap<String, Double>()
        if (t.isNotEmpty()) {
            addCompletions(t, scores)
            // Always offer the apostrophe version: "isnt" → "isn't", "ill" → "I'll".
            contractionOf[t.replace("'", "")]?.let { c ->
                if (c != t) {
                    val sc = freqScore(c) * (if (isKnown(t)) 0.6 else 5.0)
                    if (sc > (scores[c] ?: 0.0)) scores[c] = sc
                }
            }
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
                if (!found && scores.isEmpty() && t.length in 4..12) addTwoEditFixes(t, scores)
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

    /**
     * The fix to apply automatically when you press space, or null if the word
     * looks fine or no fix is clearly better than the alternatives.
     */
    @Synchronized
    fun autocorrection(typed: String): String? {
        val key = "$typed|$loaded"
        if (key == autoKey) return autoValue
        val result = findAutocorrection(typed)
        autoKey = key
        autoValue = result
        return result
    }

    private fun findAutocorrection(typed: String): String? {
        if (!loaded || typed.length < 2 || typed.length > 20) return null
        if (!typed.all { it.isLetter() || it == '\'' }) return null
        if (typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }) return null // ACRONYMS
        val t = typed.lowercase()
        if (isKnown(t) || t in rejected) return null
        // A contraction missing its apostrophe is always fixed: "isnt" → "isn't", "im" → "I'm".
        if (isMissingApostrophe(t)) contractionOf[t]?.let { return matchCase(it, typed) }
        val scores = HashMap<String, Double>()
        for ((cand, weight) in edits(t)) {
            if (!isCandidate(cand)) continue
            val sc = freqScore(cand) * weight
            if (sc > (scores[cand] ?: 0.0)) scores[cand] = sc
        }
        if (scores.isEmpty() && t.length in 5..12) addTwoEditFixes(t, scores)
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0.0
        val margin = if (t.length <= 3) 3.0 else 2.0
        if (best.value < MIN_AUTOCORRECT_SCORE || best.value < second * margin) return null
        return matchCase(best.key, typed)
    }

    /** You undid an auto-correction: leave this word alone from now on. */
    @Synchronized
    fun reject(word: String) {
        autoKey = null
        rejected.add(word.lowercase())
        learn(word)
        learn(word) // counts as known straight away
    }

    /**
     * Words two mistakes away ("recieeve" → "receive"). Rather than trying every
     * possible double typo (hundreds of thousands of strings, which caused lag),
     * compare against words with the same first letter and a similar length.
     */
    private fun addTwoEditFixes(t: String, scores: HashMap<String, Double>) {
        val bucket = byInitial[t[0]] ?: return
        fun consider(w: String) {
            if (Math.abs(w.length - t.length) > 2 || w == t) return
            if (distance(t, w, 2) <= 2) {
                val sc = freqScore(w) * 0.15
                if (sc > (scores[w] ?: 0.0)) scores[w] = sc
            }
        }
        for (w in bucket) consider(w)
        for (w in learned.keys) if (w[0] == t[0] && !isMissingApostrophe(w)) consider(w)
    }

    /** Edit distance counting swapped neighbours as one mistake; stops early past [max]. */
    private fun distance(a: String, b: String, max: Int): Int {
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prev2[j - 2] + 1)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val tmp = prev2
            prev2 = prev
            prev = cur
            cur = tmp
        }
        return prev[m]
    }

    private fun isCandidate(w: String) =
        rank.containsKey(w) || (learned.containsKey(w) && !isMissingApostrophe(w))

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
            if (w.length > p.length && w.startsWith(p) && !isMissingApostrophe(w)) {
                scores[w] = completionScore(w, p.length)
            }
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
                    c == '\'' -> 2.5
                    (i > 0 && w[i - 1] == c) || (i < n && w[i] == c) -> 0.9
                    else -> 0.6
                }
                put(w.substring(0, i) + c + w.substring(i), weight)
            }
        }
        return out
    }

    @Synchronized
    fun save() {
        if (!dirty) return
        try {
            // Drop any "isnt"-style words learned before contractions were in the dictionary.
            if (loaded) learned.keys.removeAll { isMissingApostrophe(it) }
            val top = learned.entries.sortedByDescending { it.value }.take(MAX_LEARNED)
            file.writeText(top.joinToString("\n") { "${it.key}\t${it.value}" })
            dirty = false
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_LEARNED = 5000
        const val LEARN_THRESHOLD = 2
        private const val MIN_AUTOCORRECT_SCORE = 30.0
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
