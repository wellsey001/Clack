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

    var keyPreview: Boolean
        get() = bool("key_preview", true)
        set(v) = setBool("key_preview", v)

    var popupHints: Boolean
        get() = bool("popup_hints", true)
        set(v) = setBool("popup_hints", v)

    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /** "short", "normal" or "tall". */
    var height: String
        get() = sp.getString("height", "normal") ?: "normal"
        set(v) = sp.edit().putString("height", v).apply()
}
