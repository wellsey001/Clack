"""Prototype of Clack's glide-typing decoder (mirrors GlideDecoder.kt), tested on
simulated swipes: the ideal path through each word's keys, wobbled and corner-cut."""
import math, random, sys

words = [w.strip() for w in open(sys.argv[1]) if w.strip()]
rank = {}
for i, w in enumerate(words): rank.setdefault(w, i)

ROWS = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)]
KEY_H = 1.35   # keys are taller than wide (54dp tall vs ~40dp wide)
CENTER = {}
for y, (letters, off) in enumerate(ROWS):
    for x, c in enumerate(letters):
        CENTER[c] = (x + off + 0.5, y * KEY_H + KEY_H / 2)

N = 40              # points each path is resampled to
NEAR_START = 1.25   # start/end keys within this many key widths of the touch
PASS = 1.6          # every letter's key must be this close to the swipe
SHAPE_WEIGHT = 3.0
PRIOR_WEIGHT = 0.5

def letters_of(w):
    out = []
    for ch in w:
        if ch.isalpha() and (not out or out[-1] != ch):   # "hello" -> h e l o
            out.append(ch)
    return out

def length(pts):
    return sum(math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1))

def resample(pts, n=N):
    if len(pts) == 1: return [pts[0]] * n
    total = length(pts)
    if total == 0: return [pts[0]] * n
    step = total / (n - 1)
    out = [pts[0]]
    acc = 0.0
    pts = list(pts)
    i = 1
    prev = pts[0]
    while i < len(pts) and len(out) < n:
        d = math.dist(prev, pts[i])
        if acc + d >= step and d > 0:
            t = (step - acc) / d
            q = (prev[0] + t * (pts[i][0] - prev[0]), prev[1] + t * (pts[i][1] - prev[1]))
            out.append(q)
            prev = q
            acc = 0.0
        else:
            acc += d
            prev = pts[i]
            i += 1
    while len(out) < n: out.append(pts[-1])
    return out

def dist_to_path(p, pts):
    best = 1e9
    for i in range(len(pts) - 1):
        (x1, y1), (x2, y2) = pts[i], pts[i + 1]
        dx, dy = x2 - x1, y2 - y1
        L = dx * dx + dy * dy
        t = 0 if L == 0 else max(0, min(1, ((p[0] - x1) * dx + (p[1] - y1) * dy) / L))
        best = min(best, math.dist(p, (x1 + t * dx, y1 + t * dy)))
    return best

def near_keys(p, limit):
    return {c for c, q in CENTER.items() if math.dist(p, q) <= limit}

BY_INITIAL = {}
for w in words:
    ls = letters_of(w)
    if len(ls) >= 2:
        BY_INITIAL.setdefault(ls[0], []).append(w)

def align(inner, g):
    """Match each inner letter to a point on the swipe, in order, as closely as possible.
    Returns the mean squared distance, or None if any letter is too far from the swipe."""
    if not inner: return 0.0
    INF = float('inf')
    prev = [0.0] * N                     # best total up to letter i-1 ending at or before k
    for i, c in enumerate(inner):
        cur = [INF] * N
        best_before = INF if i else 0.0
        for k in range(N):
            if i: best_before = min(best_before, prev[k])
            d = math.dist(c, g[k])
            if d <= PASS and best_before < INF:
                cur[k] = best_before + d * d
        prev = cur
    total = min(prev)
    return None if total == INF else total / len(inner)

W = dict(shape=6.0, ends=3.0, keys=1.0, prior=0.4)

def decode(gesture, prev_pairs=None, top=3, w_=None):
    w_ = w_ or W
    g = resample(gesture)
    glen = length(gesture)
    firsts = near_keys(gesture[0], NEAR_START)
    lasts = near_keys(gesture[-1], NEAR_START)
    scored = []
    for f in firsts:
        for w in BY_INITIAL.get(f, []):
            ls = letters_of(w)
            if ls[-1] not in lasts: continue
            ideal = [CENTER[c] for c in ls if c in CENTER]
            if len(ideal) < 2: continue
            ilen = length(ideal)
            if abs(glen - ilen) > 1.5 + 0.6 * ilen: continue
            # every key in the word must be near the swipe, in order (best-fit alignment)
            key_err = align(ideal[1:-1], g)
            if key_err is None: continue
            ends = math.dist(gesture[0], ideal[0]) ** 2 + math.dist(gesture[-1], ideal[-1]) ** 2
            r = resample(ideal)
            d = sum(math.dist(g[k], r[k]) for k in range(N)) / N
            prior = math.log(1e6 / (rank[w] + 100))
            score = -w_['shape'] * d * d - w_['ends'] * ends - w_['keys'] * key_err + w_['prior'] * prior
            scored.append((score, w))
    scored.sort(reverse=True)
    return [w for _, w in scored[:top]]

def simulate(word, rng, noise=0.22, cut=0.35):
    ls = letters_of(word)
    pts = []
    for i, c in enumerate(ls):
        x, y = CENTER[c]
        # people don't hit key centres exactly
        pts.append((x + rng.gauss(0, noise), y + rng.gauss(0, noise * KEY_H)))
    # dense path with corner cutting (smoothing)
    dense = []
    for i in range(len(pts) - 1):
        for t in [k / 8 for k in range(8)]:
            dense.append((pts[i][0] + t * (pts[i + 1][0] - pts[i][0]), pts[i][1] + t * (pts[i + 1][1] - pts[i][1])))
    dense.append(pts[-1])
    w = max(1, int(cut * 8))
    smooth = []
    for i in range(len(dense)):
        a, b = max(0, i - w), min(len(dense), i + w + 1)
        if i == 0 or i == len(dense) - 1:
            smooth.append(dense[i])
        else:
            smooth.append((sum(p[0] for p in dense[a:b]) / (b - a), sum(p[1] for p in dense[a:b]) / (b - a)))
    return smooth

def evaluate(w_, n=200, seed=7, noise=0.22):
    rng = random.Random(seed)
    pool = [w for w in words[:6000] if w.isalpha() and 3 <= len(w) <= 9]
    sample = rng.sample(pool, n)
    gests = [(w, simulate(w, rng, noise=noise)) for w in sample]
    t1 = t3 = 0
    for w, g in gests:
        r = decode(g, w_=w_)
        t1 += bool(r) and r[0] == w
        t3 += w in r
    return t1 / n, t3 / n

if __name__ == '__main__' and len(sys.argv) > 2 and sys.argv[2] == 'tune':
    best = None
    for shape in (1.0, 3.0, 6.0):
        for ends in (1.0, 3.0, 6.0):
            for keys in (1.0, 3.0, 6.0):
                for prior in (0.2, 0.4):
                    w_ = dict(shape=shape, ends=ends, keys=keys, prior=prior)
                    a = evaluate(w_, n=120)
                    if best is None or a > best[0]: best = (a, w_)
                    print(w_, a, flush=True)
    print('BEST', best)
    sys.exit()

if __name__ == '__main__':
    rng = random.Random(7)
    pool = [w for w in words[:6000] if w.isalpha() and 3 <= len(w) <= 9]
    sample = rng.sample(pool, 300)
    top1 = top3 = 0
    misses = []
    import time
    t0 = time.time()
    for w in sample:
        res = decode(simulate(w, rng))
        top1 += bool(res) and res[0] == w
        top3 += w in res
        if not res or res[0] != w: misses.append((w, res))
    dt = (time.time() - t0) / len(sample)
    print(f"top-1: {top1/len(sample):.0%}   in top 3: {top3/len(sample):.0%}   ({dt*1000:.0f} ms/word in Python)")
    for w, r in misses[:25]: print("  ", w, "->", r)
