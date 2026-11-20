import bisect, sys
words = [w.strip() for w in open(sys.argv[1]) if w.strip()]
rank = {}
for i, w in enumerate(words): rank.setdefault(w, i)
srt = sorted(words)
contraction = {}
for w in words:
    if "'" in w: contraction.setdefault(w.replace("'", ""), w)
byinit = {}
for w in words: byinit.setdefault(w[0], []).append(w)
L = "abcdefghijklmnopqrstuvwxyz"
rows = [("qwertyuiop",0),("asdfghjkl",.5),("zxcvbnm",1.5)]
pos = {c:(x+o,y) for y,(r,o) in enumerate(rows) for x,c in enumerate(r)}
def adj(a,b):
    if a not in pos or b not in pos: return False
    return abs(pos[a][0]-pos[b][0])<=1.1 and abs(pos[a][1]-pos[b][1])<=1
def missing(w): return w not in rank and w in contraction
def known(w): return w in rank
def freq(w):
    r = rank.get(w); return 1e6/(r+100) if r is not None else 0
def edits(w):
    out = {}
    def put(s, x):
        if s and x > out.get(s, 0): out[s] = x
    n = len(w)
    for i in range(n):
        d = w[:i]+w[i+1:]
        doubled = (i>0 and w[i-1]==w[i]) or (i<n-1 and w[i+1]==w[i])
        put(d, 0.9 if doubled else (0.05 if i==n-1 else 0.6))
        if i < n-1: put(w[:i]+w[i+1]+w[i]+w[i+2:], 1.5)
        for c in L:
            if c != w[i]: put(w[:i]+c+w[i+1:], 0.8 if adj(w[i],c) else 0.3)
    for i in range(n+1):
        for c in L+"'":
            wt = 2.5 if c=="'" else (1.4 if (i>0 and w[i-1]==c) or (i<n and w[i]==c) else 0.6)
            put(w[:i]+c+w[i:], wt)
    return out
def dist(a,b,mx):
    m=len(b); p2=[0]*(m+1); p=list(range(m+1)); cur=[0]*(m+1)
    for i in range(1,len(a)+1):
        cur[0]=i; rm=i
        for j in range(1,m+1):
            c=0 if a[i-1]==b[j-1] else 1
            v=min(p[j]+1,cur[j-1]+1,p[j-1]+c)
            if i>1 and j>1 and a[i-1]==b[j-2] and a[i-2]==b[j-1]: v=min(v,p2[j-2]+1)
            cur[j]=v; rm=min(rm,v)
        if rm>mx: return mx+1
        p2,p,cur=p,cur,p2
    return p[m]
def two(t, sc):
    for w in byinit.get(t[0], []):
        if abs(len(w)-len(t))>2 or w==t: continue
        if dist(t,w,2)<=2:
            s=freq(w)*0.15
            if s>sc.get(w,0): sc[w]=s
MIN=float(__import__('os').environ.get('MIN','90'))
SPLIT={"alot":"a lot","aswell":"as well","infact":"in fact","ofcourse":"of course"}
def autocorrect(typed):
    if any(c.isupper() for c in typed[1:]) and not typed.isupper(): return None
    t=typed.lower()
    if t in SPLIT: return SPLIT[t]
    if known(t): return None
    if missing(t): return contraction[t]
    sc={}
    for c,wt in edits(t).items():
        if c in rank:
            s=freq(c)*wt
            if s>sc.get(c,0): sc[c]=s
    twoEdit=False
    if not sc and 6<=len(t)<=12:
        two(t,sc); twoEdit=True
    r=sorted(sc.items(), key=lambda x:-x[1])
    if not r: return None
    best=r[0]; second=r[1][1] if len(r)>1 else 0
    margin=2.5 if len(t)<=3 else 2.0
    need = 150 if twoEdit else MIN
    if best[1]<need or best[1]<second*margin: return None
    return best[0]
def suggest(typed, limit=2):
    t=typed.lower(); sc={}
    i=bisect.bisect_left(srt,t)
    while i<len(srt) and srt[i].startswith(t):
        w=srt[i]
        if len(w)>len(t): sc[w]=freq(w)*0.6*0.8**(len(w)-len(t))
        i+=1
    c=contraction.get(t.replace("'",""))
    if c and c!=t:
        s=freq(c)*(0.6 if known(t) else 5.0)
        if s>sc.get(c,0): sc[c]=s
    if not known(t):
        found=False
        for cand,wt in edits(t).items():
            if cand not in rank: continue
            found=True
            s=freq(cand)*wt*(0.25 if len(t)<=3 else 1)
            if s>sc.get(cand,0): sc[cand]=s
        if not found and not sc and 4<=len(t)<=12: two(t,sc)
    sc.pop(t,None)
    return [w for w,_ in sorted(sc.items(), key=lambda x:(-x[1],len(x[0])))[:limit]]

cases = {"teh":"the","recieve":"receive","definately":"definitely","wierd":"weird","tomorow":"tomorrow",
 "becuase":"because","isnt":"isn't","wasnt":"wasn't","im":"i'm","dont":"don't","cant":"can't",
 "thier":"their","freind":"friend","beleive":"believe","untill":"until","alot":"a lot","seperate":"separate",
 "goign":"going","jsut":"just","waht":"what","adn":"and","thnaks":"thanks","helo":"hello","pleese":"please",
 "accomodate":"accommodate","occured":"occurred","tommorrow":"tomorrow","realy":"really","hte":"the",
 "hello":None,"were":None,"ill":None,"its":None,"well":None,"london":None,"sarah":None,"brb":None,"pls":None,"idk":None,"seperate":"separate","goin":"going","sory":"sorry","aswell":"as well","waht":"what","adn":"and","hte":"the","hlep":"help","yuo":"you","taht":"that","form":None,"from":None,"lol":None,"tho":None,"ur":None,"helo":"hello"}
bad=0
for typed, exp in cases.items():
    got=autocorrect(typed)
    ok = got==exp
    bad += not ok
    print(("OK " if ok else "?? ")+f"{typed:12} -> auto: {str(got):14} expected {str(exp):12} | bar: {suggest(typed)}")
print("mismatches:", bad)

print("\n-- words that should NOT be auto-corrected --")
risky = "github wifi netflix spotify kubernetes ahmed siobhan immich oppo colorOS whatsapp tesco sainsburys aldi greggs nhs gp covid uni telly quid fiver tenner brexit selfie emoji hashtag vlog podcast airpods macbook lidl primark asda ikea uber deliveroo glasto wetherspoons cheers mate cuppa bruv gutted chuffed knackered".split()
fp = [(w, autocorrect(w)) for w in risky if autocorrect(w)]
for w, f in fp: print(f"  {w} -> {f}")
print("wrongly corrected:", len(fp), "of", len(risky))
