#!/usr/bin/env python3
"""Generate small, deterministic CC0 publications using only Python's stdlib.

Run from any directory. Large stress fixtures are opt-in and go to /tmp by default.
This validates ZIP/XML/PDF structure, not Android navigator rendering.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import tempfile
import uuid
import xml.etree.ElementTree as ET
import zipfile
import zlib
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "app/src/androidTest/assets/publications"
FIXED_TIME = (2026, 1, 1, 0, 0, 0)
EPUB_MIMETYPE = b"application/epub+zip"
AUTHOR = "Leaf fixture contributors"
CONTAINER = b'''<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>'''
CSS = b'''body { font-family: serif; line-height: 1.6; margin: 5%; }
h1 { line-height: 1.25; } p { margin: 0 0 1em; }
img { max-width: 100%; height: auto; } figure { margin: 1em 0; }
figcaption { font-size: .85em; }'''


def zip_entry(archive: zipfile.ZipFile, name: str, content: bytes, *, stored: bool = False) -> None:
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    info.create_system = 3
    archive.writestr(info, content, compresslevel=9)


def xhtml(title: str, body: str, *, language: str = "en", direction: str = "ltr", fixed: bool = False) -> bytes:
    viewport = '<meta name="viewport" content="width=600,height=800"/>' if fixed else ""
    return f'''<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="{language}" lang="{language}" dir="{direction}">
<head><title>{escape(title)}</title><link rel="stylesheet" type="text/css" href="styles.css"/>{viewport}</head>
<body><h1>{escape(title)}</h1>{body}</body></html>'''.encode()


def image_rgb(width: int, height: int, variant: int) -> bytes:
    """Draw a geometric leaf and page rules. No fonts or external artwork are used."""
    data = bytearray(width * height * 3)
    for y in range(height):
        for x in range(width):
            color = (244, 238, 215)
            # Vary each image enough to catch accidental image caching/reordering.
            cx, cy = width * .5, height * .37
            dx, dy = (x - cx) / (width * .27), (y - cy) / (height * .22)
            if (dx + dy * .32) ** 2 + dy ** 2 < 1:
                color = (55 + variant * 9 % 60, 104 + variant * 7 % 65, 72)
            if abs(x - (cx - (y - cy) * .32)) < max(1, width // 150) and height * .18 < y < height * .62:
                color = (224, 216, 168)
            if height * .70 < y < height * .91 and (y // max(1, height // 45)) % 3 == 0:
                if width * .12 < x < width * (.83 - (y % 3) * .04):
                    color = (53, 58, 49)
            offset = (y * width + x) * 3
            data[offset:offset + 3] = bytes(color)
    return bytes(data)


def png(width: int, height: int, pixels: bytes) -> bytes:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    rows = b"".join(b"\x00" + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def package_xml(title: str, language: str, chapters: list[tuple[str, str]], images: list[str], *, rtl: bool, fixed: bool) -> bytes:
    identifier = uuid.uuid5(uuid.NAMESPACE_URL, f"https://example.invalid/leaf-fixtures/{title}")
    items = ['<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>',
             '<item id="css" href="styles.css" media-type="text/css"/>',
             '<item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>']
    items += [f'<item id="chapter{i}" href="chapter{i}.xhtml" media-type="application/xhtml+xml"/>' for i, _ in enumerate(chapters, 1)]
    items += [f'<item id="image{i}" href="images/{name}" media-type="image/png"/>' for i, name in enumerate(images, 1)]
    spine = "".join(f'<itemref idref="chapter{i}"/>' for i, _ in enumerate(chapters, 1))
    layout = "pre-paginated" if fixed else "reflowable"
    return f'''<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" prefix="rendition: http://www.idpf.org/vocab/rendition/#">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="book-id">urn:uuid:{identifier}</dc:identifier>
<dc:title>{escape(title)}</dc:title><dc:creator>{AUTHOR}</dc:creator><dc:language>{language}</dc:language>
<dc:description>An original CC0 Leaf test publication.</dc:description>
<dc:rights>CC0 1.0 Universal</dc:rights><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
<meta property="rendition:layout">{layout}</meta><meta property="rendition:spread">none</meta>
</metadata><manifest>{''.join(items)}</manifest>
<spine page-progression-direction="{'rtl' if rtl else 'ltr'}">{spine}</spine></package>'''.encode()


def write_epub(path: Path, title: str, chapters: list[tuple[str, str]], *, language: str = "en", rtl: bool = False, fixed: bool = False, images: dict[str, bytes] | None = None, stored_chapters: bool = False) -> None:
    images = images or {}
    direction = "rtl" if rtl else "ltr"
    links = "".join(f'<li><a href="chapter{i}.xhtml">{escape(chapter_title)}</a></li>' for i, (chapter_title, _) in enumerate(chapters, 1))
    nav = f'<nav epub:type="toc" id="toc"><h2>Contents</h2><ol>{links}</ol></nav>'
    with zipfile.ZipFile(path, "w") as archive:
        zip_entry(archive, "mimetype", EPUB_MIMETYPE, stored=True)
        zip_entry(archive, "META-INF/container.xml", CONTAINER)
        zip_entry(archive, "OEBPS/package.opf", package_xml(title, language, chapters, list(images), rtl=rtl, fixed=fixed))
        zip_entry(archive, "OEBPS/nav.xhtml", xhtml(title, nav, language=language, direction=direction))
        css = CSS + (b"\nbody { width: 540px; height: 740px; margin: 30px; overflow: hidden; font-size: 24px; }" if fixed else b"")
        zip_entry(archive, "OEBPS/styles.css", css)
        zip_entry(archive, "OEBPS/images/cover.png", png(128, 192, image_rgb(128, 192, 1)))
        for i, (chapter_title, body) in enumerate(chapters, 1):
            zip_entry(archive, f"OEBPS/chapter{i}.xhtml", xhtml(chapter_title, body, language=language, direction=direction, fixed=fixed), stored=stored_chapters)
        for name, content in images.items():
            zip_entry(archive, f"OEBPS/images/{name}", content)


def standard_chapters() -> list[tuple[str, str]]:
    subjects = ["A quiet beginning", "The river path", "A warm return"]
    paragraphs = [
        "The path behind the house was narrow enough for one careful walker. Birds settled in the branches as the morning opened. Nothing hurried the light across the stones.",
        "A reader left a chair beside the window and returned to the page. Each line carried a little of the day's stillness. Outside, a small leaf turned toward the sun.",
        "There was time to notice the grain of a wooden table and the soft edge of a cloud. The story continued without asking for anything more than attention.",
        "At the bend in the river, the water made a quiet circle. A bridge waited a little farther on. The walker stopped to remember the shape of the place.",
    ]
    result = []
    for number, title in enumerate(subjects, 1):
        body = f'<p id="passage">A silver fern remembers the light. This is searchable passage {number}.</p>'
        body += "".join(f'<p id="paragraph-{i}">{escape(paragraphs[(i + number) % len(paragraphs)])} This is chapter {number}, paragraph {i + 1}.</p>' for i in range(12))
        result.append((title, body))
    return result


class PdfBuilder:
    """Small PDF 1.4 writer with explicit byte offsets and indirect objects."""

    def __init__(self) -> None:
        self.objects: list[bytes | None] = []

    def reserve(self) -> int:
        self.objects.append(None)
        return len(self.objects)

    def put(self, number: int, data: bytes) -> None:
        self.objects[number - 1] = data

    def add(self, data: bytes) -> int:
        number = self.reserve()
        self.put(number, data)
        return number

    @staticmethod
    def stream(payload: bytes, entries: bytes = b"") -> bytes:
        return b"<< /Length " + str(len(payload)).encode() + b" " + entries + b">>\nstream\n" + payload + b"\nendstream"

    def write(self, path: Path, root: int, info: int) -> None:
        data = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = [0]
        for number, obj in enumerate(self.objects, 1):
            assert obj is not None
            offsets.append(len(data))
            data += f"{number} 0 obj\n".encode() + obj + b"\nendobj\n"
        xref = len(data)
        data += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
        data += b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets[1:])
        data += f"trailer\n<< /Size {len(offsets)} /Root {root} 0 R /Info {info} 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
        path.write_bytes(data)


def pdf_text(value: str) -> bytes:
    return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").encode("ascii")


def write_pdf(path: Path, *, scanned: bool) -> None:
    pdf = PdfBuilder()
    catalog = pdf.reserve()
    pages = pdf.reserve()
    font = pdf.add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    title = "Leaf Image Pages" if scanned else "Leaf Quiet Mornings"
    info = pdf.add(b"<< /Title (" + pdf_text(title) + b") /Author (Leaf fixture contributors) /Subject (Original CC0 test publication) /CreationDate (D:20260101000000Z) >>")
    page_ids = []
    for number in range(1, 3 if scanned else 4):
        page = pdf.reserve()
        page_ids.append(page)
        if scanned:
            width, height = 256, 384
            image = pdf.add(PdfBuilder.stream(zlib.compress(image_rgb(width, height, number), 9), f"/Type /XObject /Subtype /Image /Width {width} /Height {height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode ".encode()))
            content = pdf.add(PdfBuilder.stream(b"q\n396 0 0 570 12 12 cm\n/Scan Do\nQ"))
            resources = f"<< /XObject << /Scan {image} 0 R >> >>"
        else:
            lines = [f"Quiet Mornings - Page {number}", "", "A silver fern remembers the light.", "This is an original Leaf PDF test passage.", "", "The reader follows a path beside the river.", "Each page has selectable text and its own number.", "The imported private copy should reopen here.", "", f"Distinct locator marker: LEAF-PDF-PAGE-{number}."]
            commands = [b"BT /F1 20 Tf 36 540 Td (" + pdf_text(lines[0]) + b") Tj /F1 12 Tf"]
            commands += [b"0 -26 Td (" + pdf_text(line) + b") Tj" for line in lines[1:]]
            commands += [b"ET", b"0.22 0.42 0.28 rg 36 80 348 5 re f"]
            content = pdf.add(PdfBuilder.stream(b"\n".join(commands)))
            resources = f"<< /Font << /F1 {font} 0 R >> >>"
        pdf.put(page, f"<< /Type /Page /Parent {pages} 0 R /MediaBox [0 0 420 595] /Resources {resources} /Contents {content} 0 R >>".encode())
    pdf.put(catalog, f"<< /Type /Catalog /Pages {pages} 0 R >>".encode())
    pdf.put(pages, f"<< /Type /Pages /Count {len(page_ids)} /Kids [{' '.join(f'{number} 0 R' for number in page_ids)}] >>".encode())
    pdf.write(path, catalog, info)


def validate_epub(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        first = archive.infolist()[0]
        assert first.filename == "mimetype" and first.compress_type == zipfile.ZIP_STORED
        assert archive.read("mimetype") == EPUB_MIMETYPE
        assert archive.testzip() is None
        for name in archive.namelist():
            if name.endswith((".xml", ".opf", ".xhtml")):
                ET.fromstring(archive.read(name))
        package = ET.fromstring(archive.read("OEBPS/package.opf"))
        ns = {"opf": "http://www.idpf.org/2007/opf"}
        manifest = package.find("opf:manifest", ns)
        assert manifest is not None
        ids = set()
        for item in manifest:
            assert "OEBPS/" + item.attrib["href"] in archive.namelist()
            ids.add(item.attrib["id"])
        for item in package.find("opf:spine", ns):
            assert item.attrib["idref"] in ids


def validate_pdf(path: Path) -> None:
    data = path.read_bytes()
    assert data.startswith(b"%PDF-1.4\n") and data.endswith(b"%%EOF\n")
    xref_offset = int(data.rsplit(b"startxref\n", 1)[1].splitlines()[0])
    assert data[xref_offset:xref_offset + 5] == b"xref\n"
    lines = data[xref_offset:].splitlines()
    count = int(lines[1].split()[1])
    for number, entry in enumerate(lines[3:count + 2], 1):
        offset = int(entry.split()[0])
        assert data[offset:].startswith(f"{number} 0 obj\n".encode())


def small_fixtures(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    write_epub(output / "standard.epub", "Leaf Quiet Mornings", standard_chapters())
    rtl_paragraph = "هذه حديقة هادئة. تسير الورقة مع الضوء، ويستريح القارئ قرب النافذة. لكل صفحة مكان يمكن العودة إليه."
    write_epub(output / "rtl.epub", "رحلة الورقة", [
        ("الصباح", "".join(f'<p id="rtl-{i}">{rtl_paragraph}</p>' for i in range(12))),
        ("العودة", f"<p>{rtl_paragraph}</p><p>هذه هي الصفحة الأخيرة من القصة.</p>"),
    ], language="ar", rtl=True)
    write_epub(output / "fixed-layout.epub", "Leaf Fixed Pages", [
        ("One fixed page", '<p>This page uses a 600 by 800 viewport.</p><img src="images/scene.png" alt="An original green leaf shape on a warm background"/>'),
        ("A second fixed page", '<p>The content stays on its designed page.</p><p>Fixed layout locator marker: LEAF-FXL-TWO.</p>'),
    ], fixed=True, images={"scene.png": png(256, 384, image_rgb(256, 384, 2))})
    illustrations = {f"scene-{i}.png": png(192, 256, image_rgb(192, 256, i)) for i in range(1, 7)}
    image_body = "".join(f'<figure><img src="images/scene-{i}.png" alt="Original geometric leaf illustration {i}"/><figcaption>Leaf illustration {i}.</figcaption></figure>' for i in range(1, 7))
    write_epub(output / "image-heavy.epub", "Leaf Illustrated Pages", [("Six small illustrations", image_body)], images=illustrations)
    write_pdf(output / "ordinary.pdf", scanned=False)
    write_pdf(output / "scanned.pdf", scanned=True)
    (output / "malformed.epub").write_bytes(b"PK\x03\x04This is deliberately not a valid ZIP archive.\n")
    (output / "malformed.pdf").write_bytes(b"%PDF-1.4\nDeliberately missing pages, xref and trailer.\n")
    manifest = {}
    for path in sorted(output.iterdir()):
        if path.suffix not in (".epub", ".pdf"):
            continue
        valid = not path.name.startswith("malformed")
        if valid:
            validate_epub(path) if path.suffix == ".epub" else validate_pdf(path)
        manifest[path.name] = {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "structurally_valid": valid}
    (output / "fixture-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"Generated and structurally validated {len(manifest)} small fixtures in {output}")


def large_fixture(mib: int, destination: Path) -> None:
    if not 1 <= mib <= 2048:
        raise ValueError("Large fixture size must be between 1 and 2048 MiB.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Approximately one MiB per chapter, stored without ZIP compression so actual
    # file size exercises I/O/storage bounds rather than only decompression size.
    paragraph = "<p>The large fixture repeats original text to exercise bounded streaming imports. No downloaded publication is included.</p>\n"
    body = paragraph * ((1024 * 1024) // len(paragraph.encode()) + 1)
    chapters = [(f"Stress chapter {i + 1}", body) for i in range(mib)]
    write_epub(destination, f"Leaf Large Fixture {mib} MiB", chapters, stored_chapters=True)
    validate_epub(destination)
    print(f"Generated large fixture: {destination} ({destination.stat().st_size:,} bytes). Do not commit it.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--large-mib", type=int, help="Also generate an approximately N MiB EPUB for stress testing.")
    parser.add_argument("--large-output", type=Path, help="Large EPUB path; defaults to /tmp/leaf-fixtures/large-Nmib.epub.")
    args = parser.parse_args()
    small_fixtures(args.output_dir.resolve())
    if args.large_mib is not None:
        destination = args.large_output or Path(tempfile.gettempdir()) / "leaf-fixtures" / f"large-{args.large_mib}mib.epub"
        large_fixture(args.large_mib, destination.resolve())


if __name__ == "__main__":
    main()
