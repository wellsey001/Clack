# Clack

A clean, fast Android keyboard (Kotlin, no third-party libraries).

## Features
- Gboard-style look: flat keys, tinted function keys, pill-shaped enter key, light/dark themes
- Toolbar above the keys (emoji, paste, settings, switch keyboard, hide) that turns into
  word suggestions while you type; it learns the words you use (never in password fields)
- Full emoji panel: every emoji your phone supports, by category, recently used, skin tones
- Spell checker: suggests fixes for misspelt words and learns words you keep using
- Slide left from delete to delete whole words
- QWERTY with optional number row, symbols and extra symbols pages (includes £ and €)
- Long-press for accents and symbols, slide to pick
- Pop-up key preview, accent hints on keys
- Shift, double-tap shift for caps lock, automatic sentence capitals
- Double-tap space for a full stop
- Slide along the space bar to move the cursor
- Hold the space bar to switch keyboards
- Hold delete to keep deleting
- Enter key shows Go / Search / Send / Next / Done when the app asks for it
- Light, dark or follow-system theme; three keyboard heights
- Vibration and sound feedback
- Smooth two-thumb typing (a second finger never drops the first key)

## Build
Push to `main` on GitHub. The **Build APK** workflow builds the app and attaches
`Clack-apk` to the run. Download it, unzip, and install the APK on your phone.

The repo includes a fixed signing key (`app/debug.keystore`), so every new build
installs over the previous one without uninstalling.

## Set up on the phone
1. Open **Clack** from your app drawer.
2. Tap **Enable Clack in settings** and switch Clack on (Android shows a standard
   warning that keyboards can see what you type — Clack has no internet permission,
   so nothing leaves your phone).
3. Tap **Switch to Clack**.

## Data credits
- Word frequencies: [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit Dave
  (code MIT; word data CC BY-SA 4.0), filtered to the top 50,000 English words.
- Emoji list: [unicode-emoji-json](https://github.com/muan/unicode-emoji-json) by Mu-An Chiou (MIT).
