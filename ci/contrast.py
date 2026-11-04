import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('L')
w, h = im.size
px = [im.getpixel((x, y)) for y in range(0, max(1, int(h * 0.035))) for x in range(0, w, 2)]
c = max(px) - min(px)
print('status bar contrast:', c)
assert c > 60, 'status bar icons unreadable (low contrast)'
