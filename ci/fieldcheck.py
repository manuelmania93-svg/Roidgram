import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
expected = sys.argv[2]
for n in tree.iter('node'):
    if (n.get('text') or '') == expected:
        sys.exit(0)
sys.exit(1)
