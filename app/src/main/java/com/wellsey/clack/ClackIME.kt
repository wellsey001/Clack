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
    EmojiPanelView.Listener {

    private enum class Mode { LETTERS, SYMBOLS, MORE, EMOJI }

    private lateinit var prefs: Prefs
    private lateinit var dictionary: Dictionary
    private var keyboard: KeyboardView? = null
    private var strip: SuggestionStripView? = null
    private var emojiPanel: EmojiPanelView? = null

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
        dictionary = Dictionary(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        dictionary.save()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val s = SuggestionStripView(this, this)
        val k = KeyboardView(this, this)
        val e = EmojiPanelView(this, this).apply { visibility = View.GONE }
        strip = s
        keyboard = k
        emojiPanel = e
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(s)
            addView(e)
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
            mode = when (info.inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME -> Mode.SYMBOLS
                else -> Mode.LETTERS
            }
            shift = ShiftState.OFF
        }
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        configureForField(info)
        lastSpaceTap = 0
        autoSpaced = false
        updateAutoShift()
        keyboard?.enterLabel = enterLabelFor(info)
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        dictionary.save()
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
        updateAutoShift()
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
        val palette = if (dark) Palette.DARK else Palette.LIGHT
        kb.palette = palette
        strip?.palette = palette
        emojiPanel?.palette = palette

        val baseDp = when (prefs.height) {
            "short" -> 46f
            "tall" -> 62f
            else -> 54f
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        kb.keyHeightPx = baseDp * (if (landscape) 0.78f else 1f) * resources.displayMetrics.density
        kb.showPreview = prefs.keyPreview
        tintNavigationBar(palette)
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
        kb.lettersMode = mode == Mode.LETTERS
        kb.shiftState = shift
        kb.showHints = prefs.popupHints && !prefs.numberRow
        kb.rows = when (mode) {
            Mode.LETTERS -> if (prefs.numberRow) Layouts.lettersWithNumbers else Layouts.letters
            Mode.SYMBOLS -> Layouts.symbols
            Mode.MORE -> Layouts.more
            Mode.EMOJI -> Layouts.emojiBottom
        }
        emojiPanel?.let { panel ->
            if (mode == Mode.EMOJI) {
                // Same overall height as the letters, so nothing jumps when switching.
                val letterRows = if (prefs.numberRow) 5 else 4
                panel.panelHeight = ((letterRows - 1) * kb.keyHeightPx + 6 * resources.displayMetrics.density).toInt()
                panel.visibility = View.VISIBLE
            } else {
                panel.visibility = View.GONE
            }
        }
        updateStrip()
        uiHandler.removeCallbacks(refreshStrip)
        uiHandler.postDelayed(refreshStrip, 80)
    }

    private fun updateStrip() {
        val s = strip ?: return
        val ic = currentInputConnection
        val word = if (ic != null && suggestionsAllowed && mode != Mode.EMOJI) currentWord(ic) else ""
        if (word.isEmpty()) {
            s.suggestions = null
            return
        }
        // (Re-checked shortly after each key in case the app was slow to update.)
        val best = dictionary.suggest(word, 2)
        if (dictionary.isKnown(word)) {
            s.quoteLeft = false
            s.suggestions = listOf(best.getOrNull(0), word, best.getOrNull(1))
        } else {
            // Looks misspelt: keep what you typed on the left, the best fix in the middle.
            s.quoteLeft = true
            s.suggestions = listOf(word, best.getOrNull(0), best.getOrNull(1))
        }
    }

    /** The word directly before the cursor (empty if there's a selection). */
    private fun currentWord(ic: InputConnection): String {
        // Ask the app directly: some apps report the cursor late, so our own
        // record of the selection can be stale.
        if (selStart != selEnd && !ic.getSelectedText(0).isNullOrEmpty()) return ""
        val before = ic.getTextBeforeCursor(48, 0) ?: return ""
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || isApostrophe(before[i - 1]))) i--
        // Some apps turn ' into a curly ’; treat both the same.
        return before.substring(i).trimStart('\'', '’').replace('’', '\'')
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’'

    /** (typed word, fix) if the word before the cursor should be auto-corrected. */
    private fun autoCorrection(ic: InputConnection): Pair<String, String>? {
        if (!prefs.autoCorrect || !autoCorrectAllowed) return null
        val word = currentWord(ic)
        if (word.isEmpty()) return null
        val fix = dictionary.autocorrection(word) ?: return null
        return word to fix
    }

    private fun replaceBeforeCursor(ic: InputConnection, length: Int, text: String) {
        ic.beginBatchEdit()
        ic.deleteSurroundingText(length, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    private fun learnCurrentWord(ic: InputConnection) {
        if (!learningAllowed) return
        val w = currentWord(ic)
        if (w.length >= 2) dictionary.learn(w)
    }

    // ---- key handling -----------------------------------------------------

    override fun onKeyDown(key: Key) {
        if (prefs.vibrate) vibrate()
        if (prefs.sound) {
            val fx = when (key.code) {
                Key.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                Key.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                Key.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, -1f)
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
        val ic = currentInputConnection ?: return
        val wasAutoSpaced = autoSpaced
        autoSpaced = false
        val pendingUndo = undo
        undo = null
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
                val fix = autoCorrection(ic)
                if (fix != null) replaceBeforeCursor(ic, fix.first.length, fix.second)
                else learnCurrentWord(ic)
                handleEnter(ic)
            }
            Key.SPACE -> {
                val fix = autoCorrection(ic)
                if (fix != null) {
                    replaceBeforeCursor(ic, fix.first.length, fix.second + " ")
                    undo = Undo(fix.first, fix.second + " ")
                    lastSpaceTap = SystemClock.uptimeMillis()
                } else {
                    learnCurrentWord(ic)
                    handleSpace(ic)
                }
            }
            else -> {
                val punct = key.text.length == 1 && key.text[0] in PUNCTUATION
                val fix = if (punct) autoCorrection(ic) else null
                if (punct && fix == null) learnCurrentWord(ic)
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
                }
            }
        }
        if (key.code != Key.SPACE) lastSpaceTap = 0
        render()
    }

    override fun onPopupText(text: String) {
        val ic = currentInputConnection ?: return
        autoSpaced = false
        undo = null
        commitText(ic, text)
        lastSpaceTap = 0
        render()
    }

    override fun onCursorMove(steps: Int) {
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onSpaceLongPress() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    // ---- delete swipe: slide left from delete to select whole words -------

    override fun onDeleteSwipe(words: Int) {
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
        val typed = currentWord(ic)
        ic.beginBatchEdit()
        if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (learningAllowed) dictionary.learn(word)
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
                val cm = getSystemService(ClipboardManager::class.java)
                val text = try {
                    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
                } catch (_: SecurityException) {
                    null
                }
                if (!text.isNullOrEmpty()) currentInputConnection?.commitText(text, 1)
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
    }
}
