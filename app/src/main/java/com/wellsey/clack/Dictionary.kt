package com.wellsey.clack

import android.content.Context
import java.io.File

/**
 * Word completions from a built-in list of common English words plus the words
 * you type yourself (kept privately on the phone, never in password fields).
 */
class Dictionary(context: Context) {

    private val file = File(context.filesDir, "learned_words.tsv")
    private val learned = HashMap<String, Int>()
    private val baseRank = HashMap<String, Int>()
    private var dirty = false

    init {
        BASE.split(' ').filter { it.isNotBlank() }.forEachIndexed { i, w ->
            baseRank.putIfAbsent(w.lowercase(), i)
        }
        try {
            if (file.exists()) {
                file.forEachLine { line ->
                    val parts = line.split('\t')
                    if (parts.size == 2) parts[1].toIntOrNull()?.let { learned[parts[0]] = it }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun isKnown(word: String): Boolean {
        val w = word.lowercase()
        return baseRank.containsKey(w) || (learned[w] ?: 0) >= 2
    }

    fun learn(word: String) {
        if (word.length < 2 || word.length > 32) return
        val w = word.lowercase()
        learned[w] = (learned[w] ?: 0) + 1
        dirty = true
    }

    /** Best completions for [prefix], most likely first. */
    fun complete(prefix: String, limit: Int): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        val scores = HashMap<String, Double>()
        val baseSize = baseRank.size.toDouble()
        for ((w, rank) in baseRank) {
            if (w.length > p.length && w.startsWith(p)) scores[w] = 5.0 * (1.0 - rank / baseSize)
        }
        for ((w, count) in learned) {
            if (w.length > p.length && w.startsWith(p)) {
                scores[w] = (scores[w] ?: 0.0) + count * 3.0
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key.length })
            .take(limit)
            .map { matchCase(it.key, prefix) }
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

        // Roughly ordered from most to least common.
        private const val BASE =
            "the to and a of i you it in is that for be have this on with not are was we my but " +
                "so just me your do at what can if all about like will get there they one up out " +
                "know no how now go he she time when think good from yes yeah ok okay thanks thank " +
                "love see well want would been more today really back some then them going his her " +
                "here day did make our people because could right work also need tomorrow tonight " +
                "much new still only home night morning great way even where why something nice " +
                "sure come take let's got never always after before over again first last year week " +
                "things thing other into than which who very lot little long sorry please hope feel " +
                "said say tell told look looking soon later maybe actually probably anyway though " +
                "through should those these were had has does doing done being best better any every " +
                "everyone everything anything nothing someone friend friends family mum dad home house " +
                "school weekend monday tuesday wednesday thursday friday saturday sunday january " +
                "february march april may june july august september october november december " +
                "happy birthday christmas meeting call phone message text email send sent pick picture " +
                "photo video keyboard work working worked job money food dinner lunch breakfast coffee " +
                "tea pub drink car train bus walk running game watch watching film music song play " +
                "playing ready free busy tired fine cool awesome amazing lovely brilliant cheers mate " +
                "hello hey hi bye goodnight morning evening afternoon minutes hour hours half past " +
                "already almost enough quite pretty around between without while until since another " +
                "different same both each few many most own help start started stop leave left keep " +
                "kept bring brought buy bought find found give gave live put read run show turn try " +
                "trying use using wait waiting wanted wish write talk talking ask asked believe " +
                "remember forget forgot understand mean means meant gonna wanna i'm i'll i've i'd " +
                "you're you'll you've don't doesn't didn't can't couldn't won't wouldn't shouldn't " +
                "isn't aren't wasn't weren't haven't hasn't it's that's there's what's let's we're " +
                "they're he's she's"
    }
}
