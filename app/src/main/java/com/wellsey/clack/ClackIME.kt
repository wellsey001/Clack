package com.wellsey.clack

import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs

class ClackIME : InputMethodService(), KeyboardView.Listener, SuggestionStripView.Listener,
    EmojiPanelView.Listener, EmojiSearchView.Listener, ClipboardPanelView.Listener {

    private enum class Mode { LETTERS, SYMBOLS, MORE, EMOJI, SEARCH, NUMPAD, CLIPBOARD }

    /** What's before the cursor: the word being typed and the one or two words before it. */
    private class Ctx(
        val prev2: String?,
        val prev: String,
        val word: String,
        val afterSpace: Boolean,
        val fieldStart: Boolean,
    )

    private lateinit var prefs: Prefs
    private lateinit var dictionary: Dictionary
    private var keyboard: KeyboardView? = null
    private var strip: SuggestionStripView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var searchView: EmojiSearchView? = null
    private var searchQuery = ""
    private var clipPanel: ClipboardPanelView? = null
    private var inputRoot: LinearLayout? = null
    /** False if live blur would cover more than the keyboard on this phone. */
    private var blurFitsKeyboard = true
    private var phoneField = false
    private var passwordField = false
    private var baseKeyHeightPx = 0f
    /** The text shortcut currently offered in the bar, if any. */
    private var offeredShortcut: Shortcuts.Shortcut? = null
    private val shortcuts by lazy { Shortcuts.get(this) }
    private val clipHistory by lazy { ClipHistory.get(this) }

    private var mode = Mode.LETTERS
    private var shift = ShiftState.OFF
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L

    /** True right after a suggestion added its own space (so "." can replace it). */
    private var autoSpaced = false

    /** Last auto-correction, so pressing delete straight after can undo it. */
    private class Undo(val original: String, val tail: String)
    private var undo: Undo? = null
    /** Show the suggestion bar (everywhere except passwords). */
    private var suggestionsAllowed = true
    /** Auto-correct (not in web addresses, emails or fields that ask for no suggestions). */
    private var autoCorrectAllowed = true
    /** Remember new words (not in incognito tabs, emails or web addresses). */
    private var learningAllowed = true

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshStrip = Runnable { updateStrip() }

    // Slow work happens off the typing thread so keys never wait for it.
    private val dictThread = android.os.HandlerThread("clack-dictionary").apply { start() }
    private val dictHandler = android.os.Handler(dictThread.looper)
    private val feedbackThread = android.os.HandlerThread("clack-feedback").apply { start() }
    private val feedbackHandler = android.os.Handler(feedbackThread.looper)
    private var stripRequest = 0

    // Verification codes: refresh the bar when one arrives, and again when it expires.
    private val otpListener = Runnable { uiHandler.post { render() } }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkClipboardForCode()
        try {
            clipHistory.add(getSystemService(ClipboardManager::class.java)?.primaryClip, this)
        } catch (_: SecurityException) {
        }
        clipPanel?.refresh()
    }

    /** After typing a letter we know shift should be off, so skip asking the app. */
    private var skipCapsCheck = false

    private var selStart = -1
    private var selEnd = -1

    // delete-swipe state
    private var swipeBefore: CharSequence? = null
    private var swipeAnchor = -1
    private var swipeWords = 0

    private val vibrator: Vibrator? by lazy { getSystemService(Vibrator::class.java) }
    private val audio: AudioManager? by lazy { getSystemService(AudioManager::class.java) }

    // Kept as a field: SharedPreferences only holds listeners weakly.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyPrefs()
        render()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        dictionary = Dictionary.get(this)
        dictionary.learnThreshold = prefs.learnAfter
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        OtpStore.addListener(otpListener)
        getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        OtpStore.removeListener(otpListener)
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        dictThread.quitSafely()
        feedbackThread.quitSafely()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        dictionary.save()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val s = SuggestionStripView(this, this)
        val k = KeyboardView(this, this)
        val e = EmojiPanelView(this, this).apply { visibility = View.GONE }
        val c = ClipboardPanelView(this, this).apply { visibility = View.GONE }
        clipPanel = c
        val q = EmojiSearchView(this, this).apply { visibility = View.GONE }
        strip = s
        keyboard = k
        emojiPanel = e
        searchView = q
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            inputRoot = this
            addView(q)
            addView(s)
            addView(e)
            addView(c)
            addView(k)
        }
        applyPrefs()
        render()
        return root
    }

    /** Never take over the whole screen in landscape. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyPrefs()
        if (!restarting) {
            val cls = info.inputType and InputType.TYPE_MASK_CLASS
            phoneField = cls == InputType.TYPE_CLASS_PHONE
            mode = when (cls) {
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME -> Mode.NUMPAD
                else -> Mode.LETTERS
            }
            shift = ShiftState.OFF
        }
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        configureForField(info)
        lastSpaceTap = 0
        autoSpaced = false
        undo = null
        swipeBefore = null
        swipeAnchor = -1
        swipeWords = 0
        updateAutoShift()
        keyboard?.enterLabel = enterLabelFor(info)
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        dictHandler.post { dictionary.save() }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        if (swipeBefore != null) return // we're selecting words with the delete swipe
        if (skipCapsCheck) skipCapsCheck = false else updateAutoShift()
        render()
    }

    /**
     * Decide what to switch on for this text box. Like Gboard, suggestions show almost
     * everywhere (many apps, e.g. Messenger and browser search bars, mark their boxes
     * "no suggestions" or "web address" even when you're typing words), but auto-correct
     * and learning are more careful.
     */
    private fun configureForField(info: EditorInfo) {
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val isText = cls == InputType.TYPE_CLASS_TEXT || cls == InputType.TYPE_NULL
        val isPassword = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val isAddress = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        val noSuggestionsFlag = (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val incognito = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        passwordField = isPassword || (cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        suggestionsAllowed = prefs.suggestions && isText && !isPassword
        autoCorrectAllowed = suggestionsAllowed && !isAddress && !noSuggestionsFlag
        learningAllowed = suggestionsAllowed && !isAddress && !incognito
    }

    // ---- settings & drawing ----------------------------------------------

    private fun applyPrefs() {
        val kb = keyboard ?: return
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (prefs.theme) {
            "light" -> false
            "dark" -> true
            else -> night
        }
        dictionary.learnThreshold = prefs.learnAfter
        val glass = prefs.theme == "glass"
        val palette = when {
            glass -> Palette.GLASS
            dark -> Palette.DARK
            else -> Palette.LIGHT
        }
        kb.palette = palette
        strip?.palette = palette
        emojiPanel?.palette = palette
        searchView?.palette = palette
        clipPanel?.palette = palette

        val baseDp = when (prefs.height) {
            "short" -> 46f
            "tall" -> 62f
            else -> 54f
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        baseKeyHeightPx = baseDp * (if (landscape) 0.78f else 1f) * resources.displayMetrics.density
        kb.keyHeightPx = baseKeyHeightPx
        kb.showPreview = prefs.keyPreview
        kb.keyBorders = prefs.keyBorders
        kb.labelScale = when (prefs.labelSize) {
            "small" -> 0.88f
            "large" -> 1.15f
            else -> 1f
        }
        kb.setSpacing(when (prefs.keySpacing) {
            "tight" -> 0
            "roomy" -> 2
            else -> 1
        })
        kb.oneHanded = when (prefs.oneHanded) {
            "left" -> -1
            "right" -> 1
            else -> 0
        }
        tintNavigationBar(palette)
        applyGlass(glass)
    }

    /**
     * Glass theme: on Android 12+ the keyboard window blurs the app behind it, live.
     * Where that isn't available (older Android, battery saver, phones without it),
     * an Aurora gradient like Perch's sits behind the translucent keys instead.
     */
    private fun applyGlass(on: Boolean) {
        val w = window?.window ?: return
        val root = inputRoot ?: return
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        var blurring = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wm = getSystemService(android.view.WindowManager::class.java)
            blurring = on && blurFitsKeyboard && wm?.isCrossWindowBlurEnabled == true
            w.setBackgroundBlurRadius(if (blurring) (34 * resources.displayMetrics.density).toInt() else 0)
            if (blurring) {
                // Safety check: if the keyboard's window is much taller than the keyboard,
                // a blur would smear the whole app. Fall back to the gradient there.
                root.post {
                    val decorH = w.decorView.height
                    if (decorH > 0 && root.height > 0 && decorH > root.height * 1.6f && blurFitsKeyboard) {
                        blurFitsKeyboard = false
                        applyGlass(prefs.theme == "glass")
                    }
                }
            }
        }
        root.background = if (on && !blurring) {
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF2B1B6B.toInt(), 0xFF12407A.toInt(), 0xFF0C5C6B.toInt()),
            )
        } else {
            null
        }
    }

    override fun onOneHandedAction(action: Int) {
        prefs.oneHanded = if (action == KeyboardView.ONE_HANDED_SWAP) {
            if (prefs.oneHanded == "left") "right" else "left"
        } else {
            "off"
        }
    }

    @Suppress("DEPRECATION")
    private fun tintNavigationBar(palette: Palette) {
        val w = window?.window ?: return
        w.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val flag = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (palette.isDark) 0 else flag, flag)
        } else {
            val decor = w.decorView
            val flag = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decor.systemUiVisibility =
                if (palette.isDark) decor.systemUiVisibility and flag.inv()
                else decor.systemUiVisibility or flag
        }
    }

    private fun render() {
        val kb = keyboard ?: return
        val searching = mode == Mode.SEARCH
        kb.lettersMode = mode == Mode.LETTERS || searching
        kb.shiftState = shift
        // The keypad's keys are taller so it fills the same space as the letters.
        if (baseKeyHeightPx > 0f) {
            kb.keyHeightPx = if (mode == Mode.NUMPAD) baseKeyHeightPx * 1.2f else baseKeyHeightPx
        }
        kb.showHints = prefs.popupHints && !prefs.numberRow
        kb.rows = when (mode) {
            Mode.LETTERS -> if (prefs.numberRow) Layouts.lettersWithNumbers else Layouts.letters
            Mode.SYMBOLS -> Layouts.symbols
            Mode.MORE -> Layouts.more
            Mode.EMOJI -> Layouts.emojiBottom
            Mode.SEARCH -> Layouts.letters
            Mode.NUMPAD -> if (phoneField) Layouts.phonePad else Layouts.numberPad
            Mode.CLIPBOARD -> Layouts.emojiBottom
        }
        searchView?.visibility = if (searching) View.VISIBLE else View.GONE
        strip?.visibility = if (searching) View.GONE else View.VISIBLE
        // Emoji and clipboard panels: same overall height as the letters, so nothing jumps.
        val letterRows = if (prefs.numberRow) 5 else 4
        val panelH = ((letterRows - 1) * kb.keyHeightPx + 6 * resources.displayMetrics.density).toInt()
        emojiPanel?.let { panel ->
            panel.panelHeight = panelH
            panel.visibility = if (mode == Mode.EMOJI) View.VISIBLE else View.GONE
        }
        clipPanel?.let { panel ->
            panel.panelHeight = panelH
            panel.visibility = if (mode == Mode.CLIPBOARD) View.VISIBLE else View.GONE
        }
        // Wait until typing pauses briefly, then update the bar once (not on every key event).
        uiHandler.removeCallbacks(refreshStrip)
        uiHandler.postDelayed(refreshStrip, STRIP_DELAY_MS)
    }

    private fun updateStrip() {
        val s = strip ?: return
        if (mode == Mode.SEARCH) return
        val ic = currentInputConnection
        val panelOpen = mode == Mode.EMOJI || mode == Mode.CLIPBOARD
        val request = ++stripRequest
        // A text shortcut ("@@" → your email) comes first, in any box except passwords.
        offeredShortcut = null
        if (ic != null && !panelOpen && !passwordField && shortcuts.all().isNotEmpty()) {
            val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
            var start = before.length
            while (start > 0 && !before[start - 1].isWhitespace()) start--
            val hit = shortcuts.match(before.substring(start))
            if (hit != null) {
                offeredShortcut = hit
                s.otpCode = null
                s.quoteLeft = false
                s.suggestions = listOf(null, hit.text, null)
                return
            }
        }
        val ctx = if (ic != null && suggestionsAllowed && !panelOpen) readContext(ic) else null
        val word = ctx?.word ?: ""
        val code = if (prefs.otpCodes && !panelOpen) OtpStore.current() else null
        s.otpCode = code
        if (code != null) {
            uiHandler.removeCallbacks(otpListener)
            uiHandler.postDelayed(otpListener, OtpStore.remainingMs() + 500)
        }
        if (word.isEmpty()) {
            s.suggestions = null
            // Next-word predictions after a space (not in an empty box, so the toolbar stays handy).
            if (ctx == null || code != null || !prefs.predictions || !ctx.afterSpace || ctx.fieldStart) return
            val capFirst = shift != ShiftState.OFF
            dictHandler.post {
                val preds = dictionary.predict(ctx.prev2, ctx.prev, 3)
                    .map { if (capFirst) it.replaceFirstChar { c -> c.uppercase() } else it }
                uiHandler.post {
                    if (request != stripRequest || preds.isEmpty()) return@post
                    s.quoteLeft = false
                    s.suggestions = listOf(preds.getOrNull(1), preds[0], preds.getOrNull(2))
                }
            }
            return
        }
        val warmAutoCorrect = prefs.autoCorrect && autoCorrectAllowed
        val prev2 = ctx?.prev2
        val prev = ctx?.prev
        dictHandler.post {
            val best = dictionary.suggest(word, 2, prev2, prev)
            val known = dictionary.isKnown(word)
            if (warmAutoCorrect) dictionary.autocorrection(word) // ready for when space is pressed
            uiHandler.post {
                if (request != stripRequest) return@post // you've typed more since
                if (known) {
                    s.quoteLeft = false
                    s.suggestions = listOf(best.getOrNull(0), word, best.getOrNull(1))
                } else {
                    // Looks misspelt: keep what you typed on the left, the best fix in the middle.
                    s.quoteLeft = true
                    s.suggestions = listOf(word, best.getOrNull(0), best.getOrNull(1))
                }
            }
        }
    }

    /** The word directly before the cursor (empty if there's a selection). */
    private fun currentWord(ic: InputConnection): String = readContext(ic)?.word ?: ""

    /** Reads the text before the cursor once: current word plus the words before it. */
    private fun readContext(ic: InputConnection): Ctx? {
        // Ask the app directly: some apps report the cursor late, so our own
        // record of the selection can be stale.
        if (selStart != selEnd && !ic.getSelectedText(0).isNullOrEmpty()) return null
        val before = ic.getTextBeforeCursor(80, 0)?.toString() ?: return null
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || isApostrophe(before[i - 1]))) i--
        // Some apps turn ' into a curly ’; treat both the same.
        val word = before.substring(i).trimStart('\'', '’').replace('’', '\'')
        val rest = before.substring(0, i)
        val (prev2, prev) = previousWords(rest)
        // Inside an email or web address ("name@gmail", "bbc.co.uk", "@sarah")? Then the
        // letters aren't a word to correct, suggest or learn: treat it as no word at all.
        val inAddress = word.isNotEmpty() && looksLikeAddress(before)
        return Ctx(
            prev2 = prev2,
            prev = prev,
            word = if (inAddress) "" else word,
            afterSpace = word.isEmpty() && (rest.endsWith(" ") || rest.endsWith("\n")),
            fieldStart = before.isBlank(),
        )
    }

    /** Whether the text run touching the cursor (back to the last space) is an address. */
    private fun looksLikeAddress(before: String): Boolean {
        var start = before.length
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        val token = before.substring(start)
        return '@' in token || "://" in token || '/' in token ||
            token.startsWith("www.", ignoreCase = true) || ADDRESS_DOT.containsMatchIn(token)
    }

    /** The one or two words before [rest], or START where a sentence begins. */
    private fun previousWords(rest: String): Pair<String?, String> {
        val start = StarterPredictions.START
        var j = rest.length
        while (j > 0 && !(rest[j - 1].isLetter() || isApostrophe(rest[j - 1]))) {
            if (rest[j - 1] in ".!?\n") return null to start
            j--
        }
        if (j == 0) return null to start
        var k = j
        while (k > 0 && (rest[k - 1].isLetter() || isApostrophe(rest[k - 1]))) k--
        val prev = rest.substring(k, j).replace('’', '\'').lowercase()
        var m = k
        while (m > 0 && rest[m - 1] == ' ') m--
        if (m == 0 || !(rest[m - 1].isLetter() || isApostrophe(rest[m - 1]))) return null to prev
        var n = m
        while (n > 0 && (rest[n - 1].isLetter() || isApostrophe(rest[n - 1]))) n--
        return rest.substring(n, m).replace('’', '\'').lowercase() to prev
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’'

    /**
     * (typed word, fix) if the word before the cursor should change when you press
     * space: a spelling fix ("teh" → "the") and/or a capital ("i" → "I", "monday" → "Monday").
     */
    private fun autoFix(word: String, ic: InputConnection? = null): Pair<String, String>? {
        if (word.isEmpty() || !autoCorrectAllowed) return null
        // Cursor in the middle of a word (e.g. splitting "hel|lo"): leave it alone.
        if (ic != null && lettersAfterCursor(ic) > 0) return null
        val corrected = if (prefs.autoCorrect) dictionary.autocorrection(word) else null
        val base = corrected ?: word
        val capped = if (prefs.autoCapWords) dictionary.capitalisation(base) else null
        val result = capped ?: corrected ?: return null
        return if (result == word) null else word to result
    }

    /** Learn which word followed which, and names you capitalise mid-sentence. */
    private fun learnContext(ctx: Ctx?, finalWord: String, typedAsIs: Boolean) {
        if (!learningAllowed || ctx == null || finalWord.isEmpty()) return
        dictionary.learnPair(ctx.prev2, ctx.prev, finalWord)
        if (typedAsIs && ctx.prev != StarterPredictions.START) {
            if (finalWord[0].isUpperCase()) dictionary.learnCapitalised(finalWord)
            else dictionary.learnLowercase(finalWord)
        }
    }

    /** How many letters of the current word come after the cursor. */
    private fun lettersAfterCursor(ic: InputConnection): Int {
        val after = ic.getTextAfterCursor(32, 0) ?: return 0
        var n = 0
        while (n < after.length && (after[n].isLetter() || isApostrophe(after[n]))) n++
        return n
    }

    private fun replaceBeforeCursor(ic: InputConnection, length: Int, text: String) {
        ic.beginBatchEdit()
        ic.deleteSurroundingText(length, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    private fun learnWord(w: String) {
        if (learningAllowed && w.length >= 2) dictionary.learn(w)
    }

    // ---- key handling -----------------------------------------------------

    override fun onKeyDown(key: Key) {
        val doVibrate = prefs.vibrate
        val doSound = prefs.sound
        if (!doVibrate && !doSound) return
        val fx = when (key.code) {
            Key.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            Key.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            Key.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        // Talking to the vibration and sound services can be slow; never make typing wait.
        feedbackHandler.post {
            if (doVibrate) vibrate()
            if (doSound) audio?.playSoundEffect(fx, -1f)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onKey(key: Key) {
        if (mode == Mode.SEARCH) {
            handleSearchKey(key)
            return
        }
        val ic = currentInputConnection ?: return
        val wasAutoSpaced = autoSpaced
        autoSpaced = false
        val pendingUndo = undo
        undo = null
        skipCapsCheck = false
        when (key.code) {
            Key.DELETE -> {
                val tail = pendingUndo?.tail
                if (pendingUndo != null && ic.getTextBeforeCursor(tail!!.length, 0)?.toString() == tail) {
                    // Undo the auto-correction and stop correcting that word.
                    replaceBeforeCursor(ic, tail.length, pendingUndo.original)
                    dictionary.reject(pendingUndo.original)
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
            }
            Key.SHIFT -> toggleShift()
            Key.TO_SYMBOLS -> mode = Mode.SYMBOLS
            Key.TO_MORE -> mode = Mode.MORE
            Key.EMOJI -> openEmoji()
            Key.TO_LETTERS -> {
                mode = Mode.LETTERS
                updateAutoShift()
            }
            Key.ENTER -> {
                val ctx = if (suggestionsAllowed) readContext(ic) else null
                val word = ctx?.word ?: ""
                val fix = autoFix(word, ic)
                if (fix != null) replaceBeforeCursor(ic, fix.first.length, fix.second)
                else learnWord(word)
                learnContext(ctx, fix?.second ?: word, typedAsIs = fix == null)
                handleEnter(ic)
            }
            Key.SPACE -> {
                if (wasAutoSpaced && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
                    // The suggestion already added a space: a second tap means "end the
                    // sentence" (like double-space), never two spaces.
                    if (prefs.doubleSpacePeriod) {
                        val before = ic.getTextBeforeCursor(2, 0)
                        if (before != null && before.length == 2 && before[0].isLetterOrDigit()) {
                            replaceBeforeCursor(ic, 1, ". ")
                        }
                    }
                    render()
                    return
                }
                val ctx = if (suggestionsAllowed) readContext(ic) else null
                val word = ctx?.word ?: ""
                val fix = autoFix(word, ic)
                if (fix != null) {
                    replaceBeforeCursor(ic, fix.first.length, fix.second + " ")
                    undo = Undo(fix.first, fix.second + " ")
                    lastSpaceTap = SystemClock.uptimeMillis()
                } else {
                    learnWord(word)
                    handleSpace(ic)
                }
                learnContext(ctx, fix?.second ?: word, typedAsIs = fix == null)
            }
            else -> {
                val punct = key.text.length == 1 && key.text[0] in PUNCTUATION
                val ctx = if (punct && suggestionsAllowed) readContext(ic) else null
                val word = ctx?.word ?: ""
                val fix = if (punct) autoFix(word, ic) else null
                if (punct && fix == null) learnWord(word)
                if (punct) learnContext(ctx, fix?.second ?: word, typedAsIs = fix == null)
                if (fix != null) {
                    replaceBeforeCursor(ic, fix.first.length, fix.second + key.text)
                    undo = Undo(fix.first, fix.second + key.text)
                } else if (punct && wasAutoSpaced) {
                    // "hello |" + "." → "hello. " like Gboard
                    val before = ic.getTextBeforeCursor(1, 0)
                    if (before != null && before.toString() == " ") {
                        ic.beginBatchEdit()
                        ic.deleteSurroundingText(1, 0)
                        ic.commitText(key.text + " ", 1)
                        ic.endBatchEdit()
                    } else {
                        commitText(ic, key.text)
                    }
                } else {
                    commitText(ic, key.text)
                    // A letter or number never starts a sentence, so no need to re-check capitals.
                    if (key.text.length == 1 && key.text[0].isLetterOrDigit()) skipCapsCheck = true
                }
            }
        }
        if (key.code != Key.SPACE) lastSpaceTap = 0
        render()
    }

    override fun onPopupText(text: String) {
        if (mode == Mode.SEARCH) {
            searchQuery += text.lowercase()
            updateSearch()
            return
        }
        val ic = currentInputConnection ?: return
        autoSpaced = false
        undo = null
        commitText(ic, text)
        lastSpaceTap = 0
        render()
    }

    override fun onCursorMove(steps: Int) {
        if (mode == Mode.SEARCH) return
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onSpaceLongPress() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    // ---- delete swipe: slide left from delete to select whole words -------

    override fun onDeleteSwipe(words: Int) {
        if (mode == Mode.SEARCH) return
        val ic = currentInputConnection ?: return
        if (swipeBefore == null) {
            swipeBefore = ic.getTextBeforeCursor(1000, 0) ?: ""
            swipeAnchor = selEnd
        }
        val before = swipeBefore ?: return
        if (swipeAnchor < 0) return
        var i = before.length
        repeat(words) {
            while (i > 0 && !before[i - 1].isLetterOrDigit()) i--
            while (i > 0 && before[i - 1].isLetterOrDigit()) i--
        }
        val start = swipeAnchor - (before.length - i)
        ic.setSelection(start, swipeAnchor)
        swipeWords = words
    }

    override fun onDeleteSwipeEnd(commit: Boolean) {
        val ic = currentInputConnection
        if (ic != null && swipeBefore != null && swipeAnchor >= 0) {
            if (commit && swipeWords > 0) ic.commitText("", 1)
            else ic.setSelection(swipeAnchor, swipeAnchor)
        }
        swipeBefore = null
        swipeAnchor = -1
        swipeWords = 0
        updateAutoShift()
        render()
    }

    // ---- suggestion strip -------------------------------------------------

    override fun onSuggestionPicked(word: String) {
        val ic = currentInputConnection ?: return
        val shortcut = offeredShortcut
        if (shortcut != null && shortcut.text == word) {
            // Replace the trigger ("@@") with the shortcut's text
            ic.beginBatchEdit()
            ic.deleteSurroundingText(shortcut.trigger.length, 0)
            ic.commitText("$word ", 1)
            ic.endBatchEdit()
            offeredShortcut = null
            autoSpaced = true
            render()
            return
        }
        val ctx = readContext(ic)
        val typed = ctx?.word ?: ""
        // If the cursor is inside a word ("hel|lo"), replace the whole word, not just the start.
        val after = if (typed.isNotEmpty()) lettersAfterCursor(ic) else 0
        ic.beginBatchEdit()
        if (typed.isNotEmpty() || after > 0) ic.deleteSurroundingText(typed.length, after)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (learningAllowed) {
            dictionary.learn(word)
            learnContext(ctx, word, typedAsIs = false)
        }
        if (shift == ShiftState.ON) shift = ShiftState.OFF
        autoSpaced = true
        lastSpaceTap = 0
        render()
    }

    override fun onToolbarAction(action: Int) {
        when (action) {
            SuggestionStripView.ACTION_EMOJI -> {
                openEmoji()
                render()
            }
            SuggestionStripView.ACTION_CLIPBOARD -> {
                // Make sure what's on the clipboard now is in the list, then show the panel
                try {
                    clipHistory.add(getSystemService(ClipboardManager::class.java)?.primaryClip, this)
                } catch (_: SecurityException) {
                }
                mode = Mode.CLIPBOARD
                clipPanel?.refresh()
                render()
            }
            SuggestionStripView.ACTION_SETTINGS -> {
                startActivity(
                    Intent(this, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
            SuggestionStripView.ACTION_SWITCH -> {
                val switched = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    switchToNextInputMethod(false)
                if (!switched) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
            SuggestionStripView.ACTION_HIDE -> requestHideSelf(0)
            SuggestionStripView.ACTION_ONE_HANDED -> {
                prefs.oneHanded = if (prefs.oneHanded == "off") "right" else "off"
            }
            SuggestionStripView.ACTION_PASTE_CODE -> {
                OtpStore.current()?.let { currentInputConnection?.commitText(it, 1) }
                OtpStore.clear(used = true)
            }
            SuggestionStripView.ACTION_DISMISS_CODE -> OtpStore.clear(used = true)
        }
    }

    override fun onSuggestionLongPressed(word: String) {
        val msg = if (dictionary.forget(word)) "Removed “$word” from learned words"
        else "“$word” is a built-in word"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        render()
    }

    override fun onEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
    }

    /** A code you've just copied (e.g. with "Copy code" on a message) is offered for pasting. */
    private fun checkClipboardForCode() {
        if (!prefs.otpCodes) return
        val text = try {
            getSystemService(ClipboardManager::class.java)?.primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        } catch (_: SecurityException) {
            null
        }
        OtpDetector.fromClipboard(text)?.let { OtpStore.offer(it) }
    }

    // ---- clipboard history ------------------------------------------------------

    override fun onClipPaste(text: String) {
        currentInputConnection?.commitText(text, 1)
        mode = Mode.LETTERS
        updateAutoShift()
        render()
    }

    // ---- emoji search -------------------------------------------------------

    override fun onEmojiSearchRequested() {
        mode = Mode.SEARCH
        searchQuery = ""
        shift = ShiftState.OFF
        updateSearch()
        render()
    }

    override fun onSearchBack() = closeSearch()

    override fun onSearchClear() {
        searchQuery = ""
        updateSearch()
    }

    override fun onSearchResult(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        emojiPanel?.remember(emoji)
    }

    private fun closeSearch() {
        openEmoji()
        render()
    }

    /** Keys type into the search box (not the app) while searching. */
    private fun handleSearchKey(key: Key) {
        when (key.code) {
            Key.TEXT -> searchQuery += key.text.lowercase()
            Key.DELETE -> searchQuery = searchQuery.dropLast(1)
            Key.SPACE -> if (searchQuery.isNotEmpty() && !searchQuery.endsWith(" ")) searchQuery += " "
            Key.ENTER, Key.TO_SYMBOLS, Key.EMOJI -> {
                closeSearch()
                return
            }
            else -> return
        }
        updateSearch()
    }

    private fun updateSearch() {
        val v = searchView ?: return
        v.query = searchQuery
        v.results = emojiPanel?.search(searchQuery) ?: emptyList()
    }

    private fun openEmoji() {
        mode = Mode.EMOJI
        emojiPanel?.refresh()
    }

    // ---- helpers ------------------------------------------------------------

    private fun commitText(ic: InputConnection, raw: String) {
        val text = if (mode == Mode.LETTERS && shift != ShiftState.OFF) raw.uppercase() else raw
        ic.commitText(text, 1)
        if (shift == ShiftState.ON) shift = ShiftState.OFF
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - lastShiftTap < DOUBLE_TAP_MS
        shift = when (shift) {
            ShiftState.OFF -> if (doubleTap) ShiftState.LOCKED else ShiftState.ON
            ShiftState.ON -> if (doubleTap) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        lastShiftTap = now
    }

    private fun handleSpace(ic: InputConnection) {
        val now = SystemClock.uptimeMillis()
        if (prefs.doubleSpacePeriod && now - lastSpaceTap < DOUBLE_SPACE_MS) {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                lastSpaceTap = 0
                return
            }
        }
        ic.commitText(" ", 1)
        lastSpaceTap = now
    }

    private fun handleEnter(ic: InputConnection) {
        val action = editorAction(currentInputEditorInfo)
        if (action != null) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** The "Go / Search / Send…" action the app wants, or null for a plain new line. */
    private fun editorAction(info: EditorInfo?): Int? {
        if (info == null) return null
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null
        return when (val a = info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS -> a
            else -> null
        }
    }

    private fun enterLabelFor(info: EditorInfo): String? = when (editorAction(info)) {
        EditorInfo.IME_ACTION_GO -> "Go"
        EditorInfo.IME_ACTION_SEARCH -> "Search"
        EditorInfo.IME_ACTION_SEND -> "Send"
        EditorInfo.IME_ACTION_NEXT -> "Next"
        EditorInfo.IME_ACTION_DONE -> "Done"
        EditorInfo.IME_ACTION_PREVIOUS -> "Back"
        else -> null
    }

    /** Capitalise automatically at the start of sentences, if the app asks for it. */
    private fun updateAutoShift() {
        if (!::prefs.isInitialized || !prefs.autoCaps) return
        if (shift == ShiftState.LOCKED || mode != Mode.LETTERS) return
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = if (info.inputType != 0) ic.getCursorCapsMode(info.inputType) else 0
        shift = if (caps != 0) ShiftState.ON else ShiftState.OFF
    }

    companion object {
        private const val DOUBLE_TAP_MS = 350L
        private const val DOUBLE_SPACE_MS = 450L
        private const val PUNCTUATION = ".,!?;:"
        /** "example.com", "bbc.co.uk": a dot joining letters with no space after it. */
        private val ADDRESS_DOT = Regex("[A-Za-z0-9]\\.[A-Za-z]")
        private const val STRIP_DELAY_MS = 30L
    }
}
