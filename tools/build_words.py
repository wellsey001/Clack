import sys, re
USE_CODESPELL = len(sys.argv) > 3  # precise misspelling list replaces the rough typo filter
NT_FRAGMENTS = ['isn', 'wasn', 'aren', 'weren', 'didn', 'doesn', 'hasn', 'hadn', 'couldn', 'wouldn',
                'shouldn', 'mustn', 'needn', 'shan', 'ain', 'don', 'won', 'haven']
# n't words whose count can't come from a fragment
TIER1 = "i'm it's don't that's can't i'll you're didn't what's let's i've"
TIER2 = ("isn't wasn't won't doesn't we're they're there's he's she's couldn't wouldn't haven't "
         "you've you'll we'll i'd aren't o'clock here's who's where's")
TIER3 = ("weren't hasn't hadn't shouldn't they'll he'll she'll it'll that'll they've we've "
         "would've could've should've you'd we'd they'd he'd she'd y'all ma'am how's why's "
         "when's who'd who'll what'll what're there'll mustn't needn't shan't ain't")
# Halves of split contractions that aren't words on their own
FRAGMENTS = "isn wasn aren weren didn doesn hasn hadn couldn wouldn shouldn mustn needn shan ain ll ve re"
src, out = sys.argv[1], sys.argv[2]
words = []
for line in open(src, encoding='utf-8'):
    parts = line.split()
    if len(parts) != 2: continue
    w, c = parts[0], int(parts[1])
    if not re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w): continue
    if len(w) == 1 and w not in ('a', 'i'): continue
    words.append((w, c))
    if len(words) >= 52000: break
# The source data splits contractions ("isn" + "t"), so rebuild them.
src_counts = {}
for line in open(src, encoding='utf-8'):
    parts = line.split()
    if len(parts) == 2 and parts[0] in NT_FRAGMENTS:
        src_counts[parts[0]] = int(parts[1])
contractions = {}
for frag in NT_FRAGMENTS:
    full = 'can\'t' if frag == 'ca' else frag + "n't" if not frag.endswith('n') else frag + "'t"
    contractions[full] = src_counts.get(frag, 0)
for tier, count in ((TIER1, 2_000_000), (TIER2, 400_000), (TIER3, 60_000)):
    for w in tier.split():
        contractions[w] = max(contractions.get(w, 0), count)
# "don", "won" and "haven" are real words, but their counts include the contraction.
SHARED = {'don': 0.005, 'won': 0.5, 'haven': 0.05}
words = [(w, int(c * SHARED[w]) if w in SHARED else c) for w, c in words]
fragments = set(FRAGMENTS.split())
words = [(w, c) for w, c in words if w not in fragments and w not in contractions]
words += list(contractions.items())
# Texting words that subtitles rarely contain, so they're never "corrected"
SLANG = ("pls plz idk tbh u brb lmao lmk imo smh ttyl np ty omw jk nvm fyi asap rn tho bc ily xoxo "
         "aldi lidl tesco asda sainsburys morrisons primark argos currys greggs boots waitrose ocado deliveroo")
have = {w for w, _ in words}
words += [(w, 4000) for w in SLANG.split() if w not in have]
words.sort(key=lambda wc: -wc[1])
freq = dict(words)
junk = set("""im ive dont doesnt didnt cant couldnt wont wouldnt shouldnt isnt arent wasnt werent
havent hasnt hadnt youre youve youll youd theyre theyve theyll thats whats wheres theres heres
alot untill ok'd gonna'""".split())
letters = 'abcdefghijklmnopqrstuvwxyz'
def edits1(w):
    # Only typical typo patterns: swapped neighbours, doubled or undoubled letters.
    s = [(w[:i], w[i:]) for i in range(len(w)+1)]
    t = [a+b[1]+b[0]+b[2:] for a,b in s if len(b)>1]
    undouble = [a+b[1:] for a,b in s if len(b)>1 and a and a[-1]==b[0]]
    double = [a+b[0]+b for a,b in s if b]
    return set(t+undouble+double)
kept, dropped = [], []
for rank, (w, c) in enumerate(words):
    if w in junk: dropped.append(w); continue
    if not USE_CODESPELL and rank > 25000 and len(w) > 2:
        best = max((freq.get(e, 0) for e in edits1(w) if e != w), default=0)
        if best > c * 20: dropped.append(w); continue
    kept.append(w)
# Remove common misspellings ("seperate", "helo", "goin") using codespell's list, if given.
# The list is only used here to decide what to leave out; none of it ships in the app.
# Real-looking words that are almost always typos in everyday messages
EXTRA_MISSPELLINGS = set("helo".split())
KEEP_NAMES = set("""claus shepard chanel carmel tigger tiggers didi ned lew gage leary cristal ines gord
linz naan worthing tennant emmit reacher skelton patten winn flagg devine pinter licker mose fane ambien""".split())
if len(sys.argv) > 3:
    kept_set = set(kept)
    fixes = {}
    for line in open(sys.argv[3], encoding='utf-8'):
        if '->' in line:
            bad, good = line.strip().split('->', 1)
            fixes[bad] = [g.strip() for g in good.split(',') if g.strip()]
    before = len(kept)
    kept = [w for w in kept if w not in EXTRA_MISSPELLINGS]
    kept = [w for w in kept if w in KEEP_NAMES or w in SLANG.split() or not any(
        t in kept_set and ' ' not in t for t in fixes.get(w, []))]
    print('removed misspellings:', before - len(kept))
open(out, 'w').write('\n'.join(kept[:50000]) + '\n')
print('kept', len(kept[:50000]), 'dropped', len(dropped))
print('sample dropped:', ' '.join(dropped[:40]))
print('sample dropped late:', ' '.join(dropped[-30:]))
