import json, sys
groups = json.load(open(sys.argv[1], encoding='utf-8'))
by = {g['slug']: g for g in groups}
order = [
    ('Smileys & people', ['smileys_emotion', 'people_body']),
    ('Animals & nature', ['animals_nature']),
    ('Food & drink', ['food_drink']),
    ('Travel & places', ['travel_places']),
    ('Activities', ['activities']),
    ('Objects', ['objects']),
    ('Symbols', ['symbols']),
    ('Flags', ['flags']),
]
lines = []
total = 0
for name, slugs in order:
    items = []
    for s in slugs:
        for e in by[s]['emojis']:
            ch = e['emoji']
            assert ' ' not in ch and '~' not in ch and '\t' not in ch
            items.append(('~' if e.get('skin_tone_support') else '') + ch)
    total += len(items)
    lines.append(name + '\t' + ' '.join(items))
open(sys.argv[2], 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print('emoji', total)
