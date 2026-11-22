package com.wellsey.clack

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("clack", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    var numberRow: Boolean
        get() = bool("number_row", true)
        set(v) = setBool("number_row", v)

    var vibrate: Boolean
        get() = bool("vibrate", true)
        set(v) = setBool("vibrate", v)

    var sound: Boolean
        get() = bool("sound", false)
        set(v) = setBool("sound", v)

    var autoCaps: Boolean
        get() = bool("auto_caps", true)
        set(v) = setBool("auto_caps", v)

    var doubleSpacePeriod: Boolean
        get() = bool("double_space", true)
        set(v) = setBool("double_space", v)

    var suggestions: Boolean
        get() = bool("suggestions", true)
        set(v) = setBool("suggestions", v)

    var autoCorrect: Boolean
        get() = bool("auto_correct", true)
        set(v) = setBool("auto_correct", v)

    /** Offer verification codes from notifications / the clipboard. */
    var otpCodes: Boolean
        get() = bool("otp_codes", true)
        set(v) = setBool("otp_codes", v)

    var predictions: Boolean
        get() = bool("predictions", true)
        set(v) = setBool("predictions", v)

    /** "i" → "I", days, months and names you write with a capital. */
    var autoCapWords: Boolean
        get() = bool("auto_cap_words", true)
        set(v) = setBool("auto_cap_words", v)

    var keyPreview: Boolean
        get() = bool("key_preview", true)
        set(v) = setBool("key_preview", v)

    var popupHints: Boolean
        get() = bool("popup_hints", true)
        set(v) = setBool("popup_hints", v)

    var keyBorders: Boolean
        get() = bool("key_borders", false)
        set(v) = setBool("key_borders", v)

    /** "small", "normal" or "large". */
    var labelSize: String
        get() = sp.getString("label_size", "normal") ?: "normal"
        set(v) = sp.edit().putString("label_size", v).apply()

    /** "tight", "normal" or "roomy". */
    var keySpacing: String
        get() = sp.getString("key_spacing", "normal") ?: "normal"
        set(v) = sp.edit().putString("key_spacing", v).apply()

    /** "off", "left" or "right". */
    var oneHanded: String
        get() = sp.getString("one_handed", "off") ?: "off"
        set(v) = sp.edit().putString("one_handed", v).apply()

    /** "system", "light", "dark" or "glass". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /** "short", "normal" or "tall". */
    var height: String
        get() = sp.getString("height", "normal") ?: "normal"
        set(v) = sp.edit().putString("height", v).apply()
}
