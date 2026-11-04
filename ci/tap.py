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
        if 'edittext' in (n.get('class') or '').lower() and target in (n.get('text') or '').strip().lower():
            tap(n)
else:
    for n in nodes:
        if target in (n.get('text') or '').strip().lower():
            tap(n)
print('node not found: ' + sys.argv[2])
sys.exit(1)
