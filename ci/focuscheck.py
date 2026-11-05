import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
target = sys.argv[2].lower()
for n in tree.iter('node'):
    ident = ((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).strip().lower()
    if target in ident and n.get('focused') == 'true':
        sys.exit(0)
sys.exit(1)
