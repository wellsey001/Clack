package com.wellsey.clack

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Text shortcuts: type a trigger like "@@" and tap the suggestion to get the full text. */
class ShortcutsActivity : Activity() {

    private lateinit var shortcuts: Shortcuts
    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shortcuts = Shortcuts.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "Text shortcuts"
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = "Type a shortcut and the full text appears in the suggestion bar. Tap it to " +
                "insert it. Nothing changes unless you tap, so shortcuts can't fire by accident."
            textSize = 14f
            alpha = 0.8f
            setPadding(0, dp(6), 0, dp(14))
        })

        val trigger = EditText(this).apply {
            hint = "Shortcut, e.g. @@"
            isSingleLine = true
        }
        val text = EditText(this).apply {
            hint = "Full text, e.g. your email address"
            minLines = 1
        }
        root.addView(trigger)
        root.addView(text)
        root.addView(Button(this).apply {
            this.text = "Add shortcut"
            setOnClickListener {
                val t = trigger.text.toString().trim()
                val full = text.text.toString().trim()
                when {
                    t.isEmpty() || full.isEmpty() -> toast("Enter a shortcut and its text")
                    t.any { it.isWhitespace() } -> toast("Shortcuts can't contain spaces")
                    else -> {
                        shortcuts.put(t, full)
                        trigger.setText("")
                        text.setText("")
                        refresh()
                    }
                }
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun refresh() {
        list.removeAllViews()
        val all = shortcuts.all()
        if (all.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No shortcuts yet. Ideas: @@ → your email, addr → your address, " +
                    "omw → On my way!"
                textSize = 14f
                alpha = 0.7f
            })
            return
        }
        for (s in all) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = s.trigger
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            })
            texts.addView(TextView(this).apply {
                text = s.text
                textSize = 14f
                alpha = 0.75f
                maxLines = 2
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                contentDescription = "Delete shortcut ${s.trigger}"
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                setOnClickListener {
                    shortcuts.remove(s.trigger)
                    refresh()
                }
            })
            list.addView(row)
            list.addView(View(this).apply { alpha = 0.12f; setBackgroundColor(0xFF888888.toInt()) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
    }
}
