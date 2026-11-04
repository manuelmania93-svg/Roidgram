import re, subprocess, sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
target = sys.argv[2].lower()
for n in tree.iter('node'):
    if target in (n.get('text') or '').strip().lower():
        x0, y0, x1, y1 = map(int, re.findall(r'\d+', n.get('bounds')))
        subprocess.run(['adb', 'shell', 'input', 'tap', str((x0 + x1) // 2), str((y0 + y1) // 2)], check=True)
        sys.exit(0)
print('node not found: ' + sys.argv[2])
sys.exit(1)
