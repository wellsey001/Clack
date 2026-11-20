# Data tools

Scripts that build the files in `app/src/main/assets/`. The source data isn't stored here; clone it first:

```bash
git clone --depth 1 https://github.com/hermitdave/FrequencyWords        # word counts
git clone --depth 1 https://github.com/muan/unicode-emoji-json           # emoji + categories
git clone --depth 1 https://github.com/muan/emojilib                     # emoji keywords
git clone --depth 1 https://github.com/codespell-project/codespell       # misspellings (build-time filter only)

python3 build_words.py FrequencyWords/content/2018/en/en_full.txt ../app/src/main/assets/words.txt \
        codespell/codespell_lib/data/dictionary.txt
python3 build_emoji.py unicode-emoji-json/data-by-group.json ../app/src/main/assets/emoji.txt
python3 build_emoji_search.py unicode-emoji-json/data-by-group.json emojilib/dist/emoji-en-US.json \
        ../app/src/main/assets/emoji_search.txt
python3 dict_test.py ../app/src/main/assets/words.txt    # typo / false-correction checks
python3 banner.py ../docs/banner.svg
```

`dict_test.py` mirrors the scoring in `Dictionary.kt`. Keep the two in step when tuning auto-correct.
