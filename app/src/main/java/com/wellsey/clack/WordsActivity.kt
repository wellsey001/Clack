package com.wellsey.clack

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** "My words": every word Clack has learned, with search, add and delete. */
class WordsActivity : Activity() {

    private lateinit var dictionary: Dictionary
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var search: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dictionary = Dictionary.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "My words"
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = "Words Clack has learned from your typing. They're never corrected and are " +
                "offered as suggestions. Delete any typos it picked up."
            textSize = 14f
            alpha = 0.8f
            setPadding(0, dp(6), 0, dp(14))
        })

        // Add a word
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val addField = EditText(this).apply {
            hint = "Add a word (e.g. a name)"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val addButton = Button(this).apply { text = "Add" }
        addRow.addView(addField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(addButton)
        root.addView(addRow)
        val add = {
            val w = addField.text.toString().trim()
            if (w.isNotEmpty()) {
                dictionary.addWord(w)
                addField.setText("")
                refresh()
            }
        }
        addButton.setOnClickListener { add() }
        addField.setOnEditorActionListener { _, _, _ -> add(); true }

        search = EditText(this).apply {
            hint = "Search your words"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        summary = TextView(this).apply {
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(summary)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        val q = search.text.toString().trim().lowercase()
        val all = dictionary.learnedWords()
        val matches = if (q.isEmpty()) all else all.filter { it.first.lowercase().contains(q) }
        list.removeAllViews()
        val shown = matches.take(MAX_SHOWN)
        summary.text = when {
            all.isEmpty() -> "No learned words yet. Keep typing and they'll appear here."
            matches.size > shown.size -> "Showing ${shown.size} of ${matches.size}. Search to find more."
            else -> "${matches.size} word${if (matches.size == 1) "" else "s"}"
        }
        for ((word, count) in shown) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = word; textSize = 16f })
            texts.addView(TextView(this).apply {
                text = if (count == 1) "Used once" else "Used $count times"
                textSize = 12f
                alpha = 0.65f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                contentDescription = "Delete $word"
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                setOnClickListener {
                    dictionary.forget(word)
                    refresh()
                }
            })
            list.addView(row)
            list.addView(View(this).apply { alpha = 0.12f; setBackgroundColor(0xFF888888.toInt()) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    companion object {
        private const val MAX_SHOWN = 200
    }
}
