"""Prototype of Clack's run-together sentence repair (mirrors Dictionary.segment)."""
import math, sys, os
sys.argv = [sys.argv[0], sys.argv[1]] if len(sys.argv) > 1 else sys.argv
exec(open(os.path.join(os.path.dirname(__file__), 'dict_test.py')).read().split('cases = {')[0])

SPACE_SLIPS = set('bnvcm')      # keys next to the space bar
WORD_COST = 1.0                 # prefer fewer, longer words
EDIT_COST = 4.0
SLIP_COST = 3.5
MAX_PIECE = 16
COMMON = 30000                  # words in a split must be reasonably common
SHORT_OK = {'a', 'i'}

def wrank(w):
    return rank.get(w, 10**6)

def wscore(w):
    # Zipf: a word's chance falls off with its rank, so "are" + "you" beats junk like "areyou"
    return -math.log(wrank(w) + 1)

def allowed(w):
    r = wrank(w)
    if len(w) == 1: return w in SHORT_OK
    if len(w) <= 3: return r < 2000       # short pieces must be very common words
    return r < COMMON

def piece_options(p):
    """Ways a piece can be a word: exact, or one typo away."""
    opts = []
    if p in rank and allowed(p):
        opts.append((p, wscore(p) - WORD_COST, 0))
    c = contraction.get(p)
    if c and p not in rank:
        opts.append((c, wscore(c) - WORD_COST, 0))
    if len(p) >= 4:
        best = None
        for cand, wt in edits(p).items():
            if cand in rank and allowed(cand) and wrank(cand) < COMMON // 3 and cand != p:
                s = wscore(cand) + math.log(wt) - EDIT_COST - WORD_COST
                if best is None or s > best[1]:
                    best = (cand, s, 1)
        if best: opts.append(best)
    return opts

def segment(token):
    t = token.lower()
    n = len(t)
    if n < 6 or n > 48: return None
    # best[i] = (score, words, edits) for t[:i]
    best = [None] * (n + 1)
    best[0] = (0.0, [], 0)
    cache = {}
    for i in range(n):
        if best[i] is None: continue
        sc, ws, ed = best[i]
        for j in range(i + 1, min(n, i + MAX_PIECE) + 1):
            p = t[i:j]
            if p not in cache: cache[p] = piece_options(p)
            for w, s, e in cache[p]:
                # word ends at j, either at the end, or followed directly by the next word,
                # or followed by a space-bar slip letter that we drop
                for k, extra, ee in ((j, 0.0, 0), (j + 1, -SLIP_COST, 1)):
                    if k > n or (k == j + 1 and (j >= n or t[j] not in SPACE_SLIPS)): continue
                    cand = (sc + s + extra, ws + [w], ed + e + ee)
                    if best[k] is None or cand[0] > best[k][0]:
                        best[k] = cand
    if best[n] is None: return None
    score, words, edits_used = best[n]
    if len(words) < 2: return None
    if edits_used > (1 if n < 10 else 2 if n < 18 else 3): return None
    # Apply automatically only when clearly right; otherwise just suggest it.
    ranks = [wrank(w) for w in words]
    confident = (len(words) >= 3 and edits_used <= 1 and max(ranks) < 10000) or \
                (len(words) == 2 and edits_used == 0 and max(ranks) < 2000)
    return ' '.join(words), score, edits_used, confident

tests = {
 "seeyoutomorrow": "see you tomorrow", "helloohowareyou": "hello how are you",
 "thankbyou": "thank you", "imgoinghome": "i'm going home", "whatsupmate": "what's up mate",
 "canyoucallme": "can you call me", "iloveyou": "i love you", "letsgoout": "let's go out",
 "goodmorning": "good morning", "therestaurant": "the restaurant", "seeyoubsoon": "see you soon",
 "whatareyoudoing": "what are you doing", "illbetherein5": None, "areyouok": "are you ok",
 "dontworryaboutit": "don't worry about it", "onmyway": "on my way", "hapybirthday": "happy birthday",
 "wheredidyougo": "where did you go", "thsnksforthat": "thanks for that",
 # should NOT be split
 "kubernetes": None, "sainsburys": None, "brexit": None, "immich": None, "spotify": None,
 "deliveroo": None, "photosynthesis": None, "airpods": None, "macbook": None, "glasto": None,
}
bad = 0
for t, exp in tests.items():
    r = segment(t)
    got = r[0] if r else None
    ok = got == exp
    auto = bool(r and r[3])
    # a false positive only matters if it's applied automatically
    if exp is None and got is not None and not auto: ok = True
    bad += 0 if ok else 0
    print(('OK ' if ok else '?? ') + f'{t:20} -> {got!s:28} auto={auto!s:5} expected {exp}')
print('done')
