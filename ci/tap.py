import re, subprocess, sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
target = sys.argv[2].lower()
prefer_edit = len(sys.argv) > 3 and sys.argv[3] == 'edit'
def tap(n):
    x0, y0, x1, y1 = map(int, re.findall(r'\d+', n.get('bounds')))
    subprocess.run(['adb', 'shell', 'input', 'tap', str((x0 + x1) // 2), str((y0 + y1) // 2)], check=True)
    sys.exit(0)
nodes = list(tree.iter('node'))
if prefer_edit:
    for n in nodes:
        ident = ((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).strip().lower()
        if 'edittext' in (n.get('class') or '').lower() and target in ident:
            tap(n)
else:
    for n in nodes:
        ident = ((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).strip().lower()
    if target in ident:
            tap(n)
print('node not found: ' + sys.argv[2])
sys.exit(1)
