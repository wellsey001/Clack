import json, sys, re
groups = json.load(open(sys.argv[1], encoding='utf-8'))
lib = json.load(open(sys.argv[2], encoding='utf-8'))
lines = []
for g in groups:
    for e in g['emojis']:
        ch = e['emoji']
        terms = []
        for t in [e['name']] + lib.get(ch, []):
            for w in re.split(r'[\s_\-:]+', t.lower()):
                w = re.sub(r"[^a-z0-9']", '', w)
                if len(w) >= 2 and w not in terms:
                    terms.append(w)
        if terms:
            lines.append(ch + '\t' + ' '.join(terms))
open(sys.argv[3], 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print(len(lines), 'emoji with search words')
