import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
target = sys.argv[2].lower()
for n in tree.iter('node'):
    if target in (n.get('text') or '').strip().lower() and n.get('focused') == 'true':
        sys.exit(0)
sys.exit(1)
