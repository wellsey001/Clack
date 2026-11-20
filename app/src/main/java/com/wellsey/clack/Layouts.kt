package com.wellsey.clack

enum class ShiftState { OFF, ON, LOCKED }

data class Key(
    val label: String,
    val code: Int = TEXT,
    val width: Float = 1f,
    /** Characters offered when the key is long-pressed. */
    val popup: List<String> = emptyList(),
    val text: String = label,
) {
    val isCharacter: Boolean get() = code == TEXT
    /** Tinted "function" keys: shift, delete, ?123, ABC, emoji… */
    val isFunction: Boolean get() = code < 0 && code != ENTER

    companion object {
        const val TEXT = 0
        const val SPACE = 1
        const val SHIFT = -1
        const val DELETE = -2
        const val ENTER = -3
        const val TO_SYMBOLS = -4
        const val TO_LETTERS = -5
        const val TO_MORE = -6
        const val EMOJI = -7
    }
}

/** Colours modelled on Gboard's Material You look. */
data class Palette(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val text: Int,
    val fnKey: Int,
    val fnText: Int,
    val on: Int,
    val onText: Int,
    val accent: Int,
    val accentText: Int,
    val hint: Int,
    val popup: Int,
    val divider: Int,
    val icon: Int,
) {
    val isDark: Boolean get() = this === DARK

    companion object {
        val DARK = Palette(
            background = 0xFF1F2023.toInt(),
            key = 0xFF3C3E43.toInt(),
            keyPressed = 0xFF55585E.toInt(),
            text = 0xFFE8EAED.toInt(),
            fnKey = 0xFF2E3A4D.toInt(),
            fnText = 0xFFD3E3FD.toInt(),
            on = 0xFFA8C7FA.toInt(),
            onText = 0xFF062E6F.toInt(),
            accent = 0xFFA8C7FA.toInt(),
            accentText = 0xFF062E6F.toInt(),
            hint = 0xFF9AA0A6.toInt(),
            popup = 0xFF4A4D53.toInt(),
            divider = 0xFF3C3E43.toInt(),
            icon = 0xFFC4C7C5.toInt(),
        )
        val LIGHT = Palette(
            background = 0xFFE9EBF0.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFD5D8DE.toInt(),
            text = 0xFF1F1F1F.toInt(),
            fnKey = 0xFFD3E3FD.toInt(),
            fnText = 0xFF041E49.toInt(),
            on = 0xFF0B57D0.toInt(),
            onText = 0xFFFFFFFF.toInt(),
            accent = 0xFF0B57D0.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            hint = 0xFF6B6F74.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            divider = 0xFFC4C7C5.toInt(),
            icon = 0xFF444746.toInt(),
        )
    }
}

object Layouts {
    private fun t(label: String, popup: String = "") =
        Key(label, Key.TEXT, 1f, popup.map { it.toString() })

    private fun row(chars: String) = chars.map { t(it.toString()) }

    private val shift = Key("shift", Key.SHIFT, 1.5f)
    private val delete = Key("delete", Key.DELETE, 1.5f)
    private val enter = Key("enter", Key.ENTER, 1.5f)
    private val emoji = Key("emoji", Key.EMOJI, 1f)
    private val space = Key(" ", Key.SPACE, 4f)
    private val abc = Key("ABC", Key.TO_LETTERS, 1.5f)

    private val numberRow = listOf(
        t("1", "¹½⅓¼"), t("2", "²⅔"), t("3", "³¾"), t("4", "⁴"), t("5"),
        t("6"), t("7"), t("8"), t("9"), t("0", "°∅"),
    )

    private val letterRows = listOf(
        listOf(
            t("q", "1"), t("w", "2"), t("e", "3éèêëē"), t("r", "4"), t("t", "5"),
            t("y", "6ý"), t("u", "7úùûüū"), t("i", "8íìîïī"), t("o", "9óòôöõøœ"), t("p", "0"),
        ),
        listOf(
            t("a", "àáâäãåæ"), t("s", "ßś"), t("d"), t("f"), t("g"),
            t("h"), t("j"), t("k"), t("l", "ł"),
        ),
        listOf(shift, t("z"), t("x"), t("c", "çć"), t("v"), t("b"), t("n", "ñń"), t("m"), delete),
        listOf(
            Key("?123", Key.TO_SYMBOLS, 1.5f),
            t(",", "'\";:!?"),
            emoji,
            space,
            t(".", "?!…-@#/"),
            enter,
        ),
    )

    val letters: List<List<Key>> = letterRows
    val lettersWithNumbers: List<List<Key>> = listOf(numberRow) + letterRows

    private val symbolsBottom = listOf(abc, t(","), emoji, space, t("."), enter)

    val symbols: List<List<Key>> = listOf(
        numberRow,
        listOf(
            t("@"), t("#"), t("£", "€$¥¢"), t("_"), t("&"),
            t("-", "–—·"), t("+", "±"), t("(", "<{["), t(")", ">}]"), t("/"),
        ),
        listOf(Key("=\\<", Key.TO_MORE, 1.5f)) + row("*\"':;!?") + delete,
        symbolsBottom,
    )

    val more: List<List<Key>> = listOf(
        row("~`|•√π÷×¶∆"),
        row("€$¥^°={}[]"),
        listOf(Key("?123", Key.TO_SYMBOLS, 1.5f)) + row("%©®✓\\<>") + delete,
        symbolsBottom,
    )

    /** Bottom row shown under the emoji panel. */
    val emojiBottom: List<List<Key>> = listOf(listOf(abc, Key(" ", Key.SPACE, 7f), delete))
}
