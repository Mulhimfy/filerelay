"""Writes small test files (PDF, PNG, text, ZIP) for the emulator smoke test."""
import struct, sys, zlib, zipfile, os

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

# Two-page PDF with text.
objs = []
def obj(s): objs.append(s); return len(objs)
font = obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
pages_id = len(objs) + 1 + 4  # placeholder, fixed below
content_ids = []
for i, line in enumerate(["FileRelay test PDF - page 1", "Page 2: it works"]):
    stream = f"BT /F1 28 Tf 60 700 Td ({line}) Tj ET"
    content_ids.append(obj(f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream"))
page_ids = []
pages_index = len(objs) + len(content_ids) + 1
for c in content_ids:
    page_ids.append(obj(f"<< /Type /Page /Parent {pages_index} 0 R /MediaBox [0 0 612 792] "
                        f"/Contents {c} 0 R /Resources << /Font << /F1 {font} 0 R >> >> >>"))
pages = obj(f"<< /Type /Pages /Kids [{' '.join(f'{p} 0 R' for p in page_ids)}] /Count {len(page_ids)} >>")
assert pages == pages_index, (pages, pages_index)
catalog = obj(f"<< /Type /Catalog /Pages {pages} 0 R >>")
pdf = b"%PDF-1.4\n"
offsets = []
for n, body in enumerate(objs, 1):
    offsets.append(len(pdf))
    pdf += f"{n} 0 obj\n{body}\nendobj\n".encode()
xref = len(pdf)
pdf += f"xref\n0 {len(objs)+1}\n0000000000 65535 f \n".encode()
for o in offsets:
    pdf += f"{o:010d} 00000 n \n".encode()
pdf += f"trailer\n<< /Size {len(objs)+1} /Root {catalog} 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
open(f"{out}/Report.pdf", "wb").write(pdf)

# 480x320 gradient PNG.
w, h = 480, 320
rows = b"".join(b"\x00" + bytes(v for x in range(w) for v in (x * 255 // w, y * 255 // h, 180)) for y in range(h))
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) \
    + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")
open(f"{out}/IMG-20261017-WA0001.png", "wb").write(png)

text = "".join(f"Line {i}: FileRelay text viewer test\n" for i in range(1, 301))
open(f"{out}/notes.txt", "w").write(text)

with zipfile.ZipFile(f"{out}/bundle.zip", "w") as z:
    z.writestr("docs/readme.txt", "Inside the zip\n")
    z.writestr("docs/notes.txt", text)
    z.write(f"{out}/IMG-20261017-WA0001.png", "pics/photo.png")
print("ok")
