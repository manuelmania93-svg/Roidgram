import re, sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
target = sys.argv[2].lower().replace('\u2019', "'")
# ":id/" in the target selects resource-id matching (stable across locale and
# punctuation); otherwise match against text + content-desc with apostrophe
# normalization (Android renders some labels with U+2019).
by_id = ':id/' in target
for n in tree.iter('node'):
    if by_id:
        ident = (n.get('resource-id') or '').strip().lower()
    else:
        ident = ((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).strip().lower().replace('\u2019', "'")
    if target in ident:
        x0, y0, x1, y1 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x0 + x1) // 2, (y0 + y1) // 2)
        sys.exit(0)
sys.exit(1)
