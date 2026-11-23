import sys
W, H = 1280, 520
out = []
a = out.append
a(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">')
a('<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0f1a2e"/><stop offset="1" stop-color="#1f2023"/></linearGradient></defs>')
a(f'<rect width="{W}" height="{H}" rx="28" fill="url(#bg)"/>')
# title block
# the keycap icon (same drawing as the app icon), 100 units scaled to 100px
a('<g transform="translate(80 98)">'
  '<rect width="100" height="100" rx="23" fill="#1A73E8"/>'
  '<rect x="22" y="30" width="56" height="50" rx="12" fill="#B9CDF3"/>'
  '<rect x="22" y="22" width="56" height="50" rx="12" fill="#FFFFFF"/>'
  '<path d="M58.49 38.51 A12 12 0 1 0 58.49 55.49" fill="none" stroke="#1A73E8" stroke-width="7" stroke-linecap="round"/>'
  '</g>')
a('<text x="204" y="190" font-family="Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="108" font-weight="700" fill="#e8eaed" letter-spacing="-2">Clack</text>')
a('<text x="84" y="242" font-family="Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="30" fill="#a8c7fa">A Gboard-style keyboard for Android</text>')
a('<text x="84" y="290" font-family="Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="22" fill="#9aa0a6">Fast · private · no internet permission</text>')
# chips
chips = ['Spell check', 'Auto-correct', '1,900+ emoji', 'Learns your words']
x = 84
cy0 = 340
for n, c in enumerate(chips):
    if n == 2:
        x = 84; cy0 = 392
    w = 16 + len(c) * 11.5 + 16
    a(f'<rect x="{x}" y="{cy0}" width="{w:.0f}" height="40" rx="20" fill="#2e3a4d"/>')
    a(f'<text x="{x + w/2:.0f}" y="{cy0 + 26}" text-anchor="middle" font-family="Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="18" fill="#d3e3fd">{c}</text>')
    x += w + 12
# keyboard illustration
kx, ky, kw = 680, 105, 540
a(f'<rect x="{kx}" y="{ky}" width="{kw}" height="310" rx="22" fill="#1f2023" stroke="#3c3e43" stroke-width="2"/>')
# suggestion strip
sy = ky + 16
for i, (wd, bold) in enumerate([('“helo”', False), ('hello', True), ('help', False)]):
    cx = kx + kw / 6 + i * kw / 3
    wt = '700' if bold else '400'
    a(f'<text x="{cx:.0f}" y="{sy + 22}" text-anchor="middle" font-family="Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="19" font-weight="{wt}" fill="#e8eaed">{wd}</text>')
    if i:
        a(f'<rect x="{kx + i * kw / 3:.0f}" y="{sy + 6}" width="1.5" height="22" fill="#3c3e43"/>')
rows = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']
unit = (kw - 24) / 10
top = ky + 62
kh = 58
def key(x, y, w, label, fill='#3c3e43', color='#e8eaed', size=24, rx=8):
    a(f'<rect x="{x + 3:.1f}" y="{y + 4:.1f}" width="{w - 6:.1f}" height="{kh - 10}" rx="{rx}" fill="{fill}"/>')
    if label:
        a(f'<text x="{x + w/2:.1f}" y="{y + kh/2 + 8:.1f}" text-anchor="middle" font-family="Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="{size}" fill="{color}">{label}</text>')
for r, letters in enumerate(rows):
    y = top + r * kh
    if r == 0:
        x = kx + 12
        for i, ch in enumerate(letters):
            key(x, y, unit, ch)
            a(f'<text x="{x + unit - 12:.1f}" y="{y + 20:.1f}" text-anchor="middle" font-family="Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="12" fill="#9aa0a6">{(i + 1) % 10}</text>')
            x += unit
    elif r == 1:
        x = kx + 12 + unit / 2
        for ch in letters:
            key(x, y, unit, ch); x += unit
    else:
        x = kx + 12
        key(x, y, unit * 1.5, '⇧', '#a8c7fa', '#062e6f'); x += unit * 1.5
        for ch in letters:
            key(x, y, unit, ch); x += unit
        key(x, y, unit * 1.5, '⌫', '#2e3a4d', '#d3e3fd')
y = top + 3 * kh
x = kx + 12
key(x, y, unit * 1.5, '?123', '#2e3a4d', '#d3e3fd', 17); x += unit * 1.5
key(x, y, unit, ','); x += unit
key(x, y, unit, '☺', '#2e3a4d', '#d3e3fd'); x += unit
key(x, y, unit * 4, ''); x += unit * 4
key(x, y, unit, '.'); x += unit
key(x, y, unit * 1.5, '↵', '#a8c7fa', '#062e6f', 24, 24)
a('</svg>')
open(sys.argv[1], 'w', encoding='utf-8').write('\n'.join(out))
