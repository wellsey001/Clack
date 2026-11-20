package com.wellsey.clack

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Launcher screen: walks through turning the keyboard on, plus all settings. */
class SetupActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var enableButton: Button
    private lateinit var switchButton: Button
    private lateinit var otpAccessButton: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(40))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(body("A clean, fast keyboard. Two quick steps to start using it:"))

        enableButton = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        switchButton = Button(this).apply {
            setOnClickListener {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
        }
        root.addView(enableButton, fullWidth(top = 14))
        root.addView(switchButton, fullWidth(top = 6))

        root.addView(section("Try it"))
        root.addView(EditText(this).apply {
            hint = "Type here to test the keyboard"
            minLines = 2
        }, fullWidth())

        root.addView(section("Typing"))
        root.addView(toggle("Number row", prefs.numberRow) { prefs.numberRow = it })
        root.addView(toggle("Show word suggestions", prefs.suggestions) { prefs.suggestions = it })
        root.addView(toggle("Auto-correct when you press space", prefs.autoCorrect) { prefs.autoCorrect = it })
        root.addView(toggle("Capitalise sentences automatically", prefs.autoCaps) { prefs.autoCaps = it })
        root.addView(toggle("Double-tap space for full stop", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it })
        root.addView(toggle("Number hints on top row (when number row is off)", prefs.popupHints) { prefs.popupHints = it })
        root.addView(toggle("Pop-up preview on key press", prefs.keyPreview) { prefs.keyPreview = it })

        root.addView(section("Feedback"))
        root.addView(toggle("Vibrate on key press", prefs.vibrate) { prefs.vibrate = it })
        root.addView(toggle("Sound on key press", prefs.sound) { prefs.sound = it })

        root.addView(section("Verification codes"))
        root.addView(toggle("Offer codes from notifications and copied text", prefs.otpCodes) { prefs.otpCodes = it })
        root.addView(body(
            "When a sign-in code arrives, a \"Paste code\" button appears above the keys. " +
                "Codes are kept in memory for 5 minutes and never saved."
        ))
        otpAccessButton = Button(this).apply {
            setOnClickListener { openNotificationAccess() }
        }
        root.addView(otpAccessButton, fullWidth(top = 6))
        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(body(
                "If the switch is greyed out (\"Restricted setting\"), it's because Clack wasn't installed " +
                    "from the Play Store. Open Clack's app info, tap ⋮ (top right) → Allow restricted settings, " +
                    "then try again."
            ))
            root.addView(Button(this).apply {
                text = "Open Clack's app info"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }, fullWidth(top = 2))
        }
        if (Build.VERSION.SDK_INT >= 35) {
            root.addView(body(
                "Android 15 and newer hide codes in most notifications from apps like Clack. " +
                    "If no button appears, tap \"Copy code\" on the message (or copy the code) " +
                    "and Clack will offer it straight away."
            ))
        } else {
            root.addView(body("Copying a code also works, with or without notification access."))
        }

        root.addView(section("Theme"))
        root.addView(radio(
            listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark"),
            prefs.theme,
        ) { prefs.theme = it })

        root.addView(section("Keyboard height"))
        root.addView(radio(
            listOf("short" to "Short", "normal" to "Normal", "tall" to "Tall"),
            prefs.height,
        ) { prefs.height = it })

        root.addView(section("Tips"))
        root.addView(body(
            "• Hold a key for accents and symbols, then slide to pick one\n" +
                "• Tap a suggestion to finish or fix a word\n" +
                "• Words you use twice are learned; long-press a suggestion to forget one\n" +
                "• Press delete straight after an auto-correction to undo it\n" +
                "• Long-press an emoji to pick a skin tone\n" +
                "• Slide left from delete to delete whole words\n" +
                "• Slide along the space bar to move the cursor\n" +
                "• Hold the space bar to switch to another keyboard\n" +
                "• Double-tap shift for caps lock\n" +
                "• Hold delete to keep deleting"
        ))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog, so we regain focus when it closes.
        if (hasFocus) refreshStatus()
    }

    private fun refreshStatus() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val current = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.startsWith("$packageName/") == true
        } catch (e: Exception) {
            false
        }
        enableButton.text = if (enabled) "✓  Clack is enabled" else "1.  Enable Clack in settings"
        switchButton.isEnabled = enabled
        switchButton.text = if (current) "✓  Clack is your keyboard" else "2.  Switch to Clack"
        otpAccessButton.text =
            if (hasNotificationAccess()) "✓  Notification access is on" else "Allow notification access"
    }

    private fun listenerComponent() = ComponentName(this, OtpNotificationListener::class.java)

    private fun hasNotificationAccess(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 27) {
            getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(listenerComponent()) == true
        } else {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?.contains(packageName) == true
        }
    } catch (_: Exception) {
        false
    }

    private fun openNotificationAccess() {
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                listenerComponent().flattenToString(),
            )
        } else {
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
    }

    // ---- little view helpers --------------------------------------------

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun body(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
        alpha = 0.8f
        setPadding(0, dp(6), 0, dp(4))
        setLineSpacing(0f, 1.25f)
    }

    private fun section(s: String) = TextView(this).apply {
        text = s.uppercase()
        textSize = 13f
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        alpha = 0.65f
        setPadding(0, dp(26), 0, dp(6))
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = label
            textSize = 16f
            isChecked = value
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    private fun radio(
        options: List<Pair<String, String>>,
        current: String,
        onPick: (String) -> Unit,
    ): RadioGroup {
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((value, label) in options) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                tag = value
                setPadding(dp(4), 0, dp(16), 0)
            }
            group.addView(rb)
            if (value == current) group.check(rb.id)
        }
        group.setOnCheckedChangeListener { g, id ->
            val v = g.findViewById<RadioButton>(id)?.tag as? String
            if (v != null) onPick(v)
        }
        return group
    }
}
