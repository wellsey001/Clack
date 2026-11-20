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

    /** "see you" → {"soon": 3, …}: which words you write after which. */
    private val pairs = HashMap<String, HashMap<String, Int>>()
    private val pairsFile = File(appContext.filesDir, "learned_pairs.tsv")

    /** "sarah" → ("Sarah", times you've written it that way mid-sentence). */
    private val caps = HashMap<String, Pair<String, Int>>()
    private val capsFile = File(appContext.filesDir, "learned_caps.tsv")
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
        try {
            if (pairsFile.exists()) {
                pairsFile.forEachLine { line ->
                    val p = line.split('\t')
                    if (p.size == 3) p[2].toIntOrNull()?.let { pairs.getOrPut(p[0]) { HashMap() }[p[1]] = it }
                }
            }
            if (capsFile.exists()) {
                capsFile.forEachLine { line ->
                    val p = line.split('\t')
                    if (p.size == 3) p[2].toIntOrNull()?.let { caps[p[0]] = p[1] to it }
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
    fun suggest(typed: String, limit: Int, prev2: String? = null, prev: String? = null): List<String> {
        val key = "$typed|$limit|$loaded|$prev2|$prev"
        if (key == cacheKey) return cacheValue
        val t = typed.lowercase()
        val scores = HashMap<String, Double>()
        if (t.isNotEmpty()) {
            addCompletions(t, scores)
            SPLIT_FIXES[t]?.let { scores[it] = 1e6 }
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
        // Words that usually follow the previous one get a boost ("see you" + "s…" → "soon").
        if (prev != null) for (e in scores.entries) e.setValue(e.value * pairBonus(prev2, prev, e.key))
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
        if (typed.drop(1).any { it.isUpperCase() }) return null // iPhone, ColorOS, McDonald
        val t = typed.lowercase()
        if (t in rejected) return null
        // Words that should be two: "alot" → "a lot", "aswell" → "as well".
        SPLIT_FIXES[t]?.let { return matchCase(it, typed) }
        if (isKnown(t)) return null
        // A contraction missing its apostrophe is always fixed: "isnt" → "isn't", "im" → "I'm".
        if (isMissingApostrophe(t)) contractionOf[t]?.let { return matchCase(it, typed) }
        val scores = HashMap<String, Double>()
        for ((cand, weight) in edits(t)) {
            if (!isCandidate(cand)) continue
            val sc = freqScore(cand) * weight
            if (sc > (scores[cand] ?: 0.0)) scores[cand] = sc
        }
        // Two mistakes in one word: only for longer words, and only when very sure.
        val twoEdits = scores.isEmpty() && t.length in 6..12
        if (twoEdits) addTwoEditFixes(t, scores)
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0.0
        val margin = if (t.length <= 3) 2.5 else 2.0
        val needed = if (twoEdits) MIN_TWO_EDIT_SCORE else MIN_AUTOCORRECT_SCORE
        if (best.value < needed || best.value < second * margin) return null
        return matchCase(best.key, typed)
    }

    /** You undid an auto-correction: leave this word alone from now on. */
    @Synchronized
    fun reject(word: String) {
        autoKey = null
        rejected.add(word.lowercase())
        caps.remove(word.lowercase()) // undoing a capital also forgets it
        dirty = true
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
                put(String(chars), 1.5) // swapped letters are the most common typing slip
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
                    (i > 0 && w[i - 1] == c) || (i < n && w[i] == c) -> 1.4 // missed a double letter
                    else -> 0.6
                }
                put(w.substring(0, i) + c + w.substring(i), weight)
            }
        }
        return out
    }

    // ---- next-word prediction ---------------------------------------------

    /** Remember that [next] followed [prev] (and "[prev2] [prev]"). */
    @Synchronized
    fun learnPair(prev2: String?, prev: String, next: String) {
        val n = next.lowercase()
        if (n.isEmpty() || !n.all { it.isLetter() || it == '\'' } || isMissingApostrophe(n)) return
        bump(prev, n)
        if (prev2 != null && prev != StarterPredictions.START) bump("$prev2 $prev", n)
        dirty = true
        cacheKey = null
    }

    private fun bump(key: String, next: String) {
        val m = pairs.getOrPut(key) { HashMap() }
        m[next] = (m[next] ?: 0) + 1
    }

    /** Likely next words after "[prev2] [prev]", best first. */
    @Synchronized
    fun predict(prev2: String?, prev: String, limit: Int): List<String> {
        val scores = HashMap<String, Double>()
        fun addFrom(key: String, weight: Double) {
            pairs[key]?.forEach { (w, c) -> scores[w] = (scores[w] ?: 0.0) + c * 10.0 * weight }
            StarterPredictions.table[key]?.forEachIndexed { i, w ->
                scores[w] = (scores[w] ?: 0.0) + (6.0 - i * 0.5).coerceAtLeast(1.0) * weight
            }
        }
        if (prev2 != null && prev != StarterPredictions.START) addFrom("$prev2 $prev", 3.0)
        addFrom(prev, 1.0)
        return scores.entries.sortedByDescending { it.value }
            .map { it.key }
            .filter { !isMissingApostrophe(it) }
            .take(limit)
            .map { capitalisation(it) ?: it }
    }

    private fun pairBonus(prev2: String?, prev: String, cand: String): Double {
        var b = 0.0
        pairs[prev]?.get(cand)?.let { b += it * 0.5 }
        if (prev2 != null) pairs["$prev2 $prev"]?.get(cand)?.let { b += it.toDouble() }
        if (StarterPredictions.table[prev]?.contains(cand) == true) b += 1.0
        return 1.0 + b.coerceAtMost(8.0)
    }

    // ---- capitalisation ------------------------------------------------------

    /** You wrote [word] with a capital in the middle of a sentence, e.g. a name. */
    @Synchronized
    fun learnCapitalised(word: String) {
        if (word.length < 2 || !word[0].isUpperCase()) return
        if (word.all { !it.isLetter() || it.isUpperCase() }) return // ALL CAPS
        if (!word.all { it.isLetter() || it == '\'' || it == '-' }) return
        val lower = word.lowercase()
        if (lower == "i" || lower.startsWith("i'")) return
        // Very common words ("Hello", "Yes") are capitalised for other reasons (quotes,
        // headings), not because they're names: don't learn those.
        val r = rank[lower]
        if (r != null && r < COMMON_WORD_RANK) return
        val old = caps[lower]
        caps[lower] = word to ((if (old?.first == word) old.second else 0) + 1)
        dirty = true
    }

    /** You wrote [word] in lowercase mid-sentence: count that against any learned capital. */
    @Synchronized
    fun learnLowercase(word: String) {
        val lower = word.lowercase()
        val old = caps[lower] ?: return
        if (old.second <= 1) caps.remove(lower) else caps[lower] = old.first to old.second - 1
        dirty = true
    }

    /** "i" → "I", "monday" → "Monday", "sarah" → "Sarah" once you've written it that way twice. */
    @Synchronized
    fun capitalisation(word: String): String? {
        if (word.isEmpty() || word.any { it.isUpperCase() }) return null
        if (word in rejected) return null
        if (word == "i") return "I"
        if (word.startsWith("i'") && rank.containsKey(word)) return "I" + word.substring(1)
        ALWAYS_CAPS[word]?.let { return it }
        caps[word]?.let { (form, count) -> if (count >= LEARN_THRESHOLD) return form }
        return null
    }

    @Synchronized
    fun save() {
        if (!dirty) return
        try {
            // Drop any "isnt"-style words learned before contractions were in the dictionary.
            if (loaded) learned.keys.removeAll { isMissingApostrophe(it) }
            val top = learned.entries.sortedByDescending { it.value }.take(MAX_LEARNED)
            file.writeText(top.joinToString("\n") { "${it.key}\t${it.value}" })
            val topPairs = pairs.flatMap { (k, m) -> m.map { (w, c) -> Triple(k, w, c) } }
                .sortedByDescending { it.third }.take(MAX_PAIRS)
            pairsFile.writeText(topPairs.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" })
            capsFile.writeText(caps.entries.joinToString("\n") { "${it.key}\t${it.value.first}\t${it.value.second}" })
            dirty = false
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_LEARNED = 5000
        private const val MAX_PAIRS = 20000
        private const val COMMON_WORD_RANK = 300

        /** Common run-together words and their two-word fixes. */
        private val SPLIT_FIXES = mapOf(
            "alot" to "a lot", "aswell" to "as well", "infact" to "in fact", "ofcourse" to "of course",
            "eachother" to "each other", "thankyou" to "thank you", "atleast" to "at least",
            "incase" to "in case", "inbetween" to "in between", "alright" to "alright",
            "nevermind" to "never mind", "everytime" to "every time", "abit" to "a bit",
        ).filter { it.key != it.value }

        /** Always capitalised. ("may" and "march" are left out: they're ordinary words too.) */
        private val ALWAYS_CAPS: Map<String, String> = HashMap<String, String>().apply {
            ("monday tuesday wednesday thursday friday saturday sunday january february april june " +
                "july august september october november december christmas easter english british " +
                "england scotland wales ireland britain london europe european").split(' ')
                .forEach { put(it, it.replaceFirstChar { c -> c.uppercase() }) }
            put("uk", "UK")
            put("usa", "USA")
            put("tv", "TV")
        }
        const val LEARN_THRESHOLD = 2
        private const val MIN_AUTOCORRECT_SCORE = 90.0
        private const val MIN_TWO_EDIT_SCORE = 150.0
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
