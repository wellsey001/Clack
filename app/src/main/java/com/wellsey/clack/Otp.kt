package com.wellsey.clack

import android.app.Notification
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Spots one-time verification codes ("Your code is 482913") in text. */
object OtpDetector {
    private val KEYWORDS = Regex(
        "(?i)(code|otp|passcode|pass code|\\bpin\\b|verif\\w*|one[- ]time|2fa|two[- ]factor|" +
            "two[- ]step|authenticat\\w*|security|log ?in|sign[- ]?in|confirm\\w*|token|access)",
    )

    // 4–8 digits, or "123 456" / "123-456". Not part of a longer number, price or time.
    private val CANDIDATE = Regex("(?<![\\d£$€¥#.,:/])(\\d{3}[ -]\\d{3}|\\d{4,8})(?![\\d%]|[.,:/]\\d)")

    fun find(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        val s = text.toString()
        val keywords = KEYWORDS.findAll(s).map { it.range }.toList()
        if (keywords.isEmpty()) return null
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (m in CANDIDATE.findAll(s)) {
            val code = m.groupValues[1].filter { it.isDigit() }
            // Skip things that look like years ("© 2025")
            if (code.length == 4 && code.toInt() in 1900..2099) continue
            val r = m.range
            val distance = keywords.minOf { k ->
                when {
                    r.first > k.last -> r.first - k.last
                    k.first > r.last -> k.first - r.last
                    else -> 0
                }
            }
            val score = distance + (if (code.length == 6) 0 else 12)
            if (score < bestScore) {
                bestScore = score
                best = code
            }
        }
        return if (bestScore <= 80) best else null
    }

    /** A copied code on its own ("482913", "482 913") or a copied message containing one. */
    fun fromClipboard(text: CharSequence?): String? {
        val t = text?.toString()?.trim() ?: return null
        if (Regex("\\d{4,8}").matches(t)) return t
        if (Regex("\\d{3}[ -]\\d{3}").matches(t)) return t.filter { it.isDigit() }
        return if (t.length <= 400) find(t) else null
    }
}

/**
 * Holds the latest code in memory only — never saved to disk — for a few minutes,
 * or until it's pasted or dismissed.
 */
object OtpStore {
    private const val LIFETIME_MS = 5 * 60 * 1000L

    @Volatile private var code: String? = null
    @Volatile private var receivedAt = 0L
    private val listeners = CopyOnWriteArrayList<Runnable>()

    fun offer(newCode: String) {
        code = newCode
        receivedAt = SystemClock.elapsedRealtime()
        listeners.forEach { it.run() }
    }

    fun current(): String? {
        val c = code ?: return null
        if (SystemClock.elapsedRealtime() - receivedAt > LIFETIME_MS) {
            code = null
            return null
        }
        return c
    }

    /** Milliseconds until the current code expires (0 if none). */
    fun remainingMs(): Long =
        if (code == null) 0 else (LIFETIME_MS - (SystemClock.elapsedRealtime() - receivedAt)).coerceAtLeast(0)

    fun clear() {
        code = null
        listeners.forEach { it.run() }
    }

    fun addListener(l: Runnable) = listeners.add(l)
    fun removeListener(l: Runnable) = listeners.remove(l)
}

/**
 * With notification access, looks at new notifications for verification codes.
 * Only the code itself is kept (in memory); nothing else from notifications is stored.
 *
 * Note: Android 15+ hides codes in many notifications from apps like this one.
 * Copying the code (e.g. the "Copy code" button in Messages) still works everywhere.
 */
class OtpNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (!Prefs(this).otpCodes) return
        val extras = sbn.notification?.extras ?: return
        val code = OtpDetector.find(collectText(extras)) ?: return
        OtpStore.offer(code)
    }

    @Suppress("DEPRECATION")
    private fun collectText(e: Bundle): String {
        val parts = ArrayList<CharSequence>()
        e.getCharSequence(Notification.EXTRA_TITLE)?.let { parts.add(it) }
        e.getCharSequence(Notification.EXTRA_TEXT)?.let { parts.add(it) }
        e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { parts.add(it) }
        e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { parts.add(it) }
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { parts.addAll(it.filterNotNull()) }
        // Chat-style notifications (e.g. SMS apps) keep messages here; use the newest one.
        val messages = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        (messages?.lastOrNull() as? Bundle)?.getCharSequence("text")?.let { parts.add(it) }
        return parts.joinToString("\n")
    }
}
