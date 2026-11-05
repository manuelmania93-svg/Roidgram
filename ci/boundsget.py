import re, sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
target = sys.argv[2].lower()
for n in tree.iter('node'):
    ident = ((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).strip().lower()
    if target in ident:
        x0, y0, x1, y1 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x0 + x1) // 2, (y0 + y1) // 2)
        sys.exit(0)
sys.exit(1)
