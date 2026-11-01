# Original publication fixtures

These small publications contain original Leaf fixture text and geometric artwork.
Their contents are dedicated to the public domain under CC0 1.0 (see `LICENSE`).
The generator uses Python's standard library; no external books, fonts, photographs,
or downloaded content are included. All timestamps and ZIP metadata are fixed.

| File | Intended test |
| --- | --- |
| `standard.epub` | EPUB 3 reflowable text, three chapters, navigation, selection, search, locator restoration |
| `rtl.epub` | Original Arabic text with RTL content and spine progression |
| `fixed-layout.epub` | Two pre-paginated pages with 600 × 800 viewports |
| `image-heavy.epub` | Six original PNG illustrations with accessible alternative text |
| `ordinary.pdf` | Three pages of selectable text with unique page markers |
| `scanned.pdf` | Two image-only pages, with no text layer |
| `malformed.epub` | Invalid ZIP bytes; import must fail safely |
| `malformed.pdf` | PDF header with missing page tree, cross-reference table, and trailer |

The standard EPUB has exactly three case-insensitive matches for `silver fern`,
one in each chapter. The first selectable passage is `A silver fern remembers the
light.` and its element ID is `passage`. Ordinary PDF page markers are
`LEAF-PDF-PAGE-1` through `LEAF-PDF-PAGE-3`. Fixed layout page two contains
`LEAF-FXL-TWO`.

Regenerate from the repository root:

```sh
python3 tools/generate_test_books.py
```

Generate a real approximately 64 MiB EPUB without adding it to the repository:

```sh
python3 tools/generate_test_books.py --large-mib 64
```

The large output defaults to `/tmp/leaf-fixtures/large-64mib.epub`; chapter entries
are stored uncompressed to exercise actual file I/O. `--large-output` can select a
different test-device staging path. Large fixtures must not be committed.

`fixture-manifest.json` records small-fixture sizes and hashes. The generator checks
EPUB ZIP CRCs, required entries, XML parsing, manifest resources and spine IDs. For
PDFs it checks object offsets and the cross-reference table. These checks do not
establish Readium rendering, EPUB conformance, text selection, RTL behavior, or
fixed layout behavior. Android navigator tests must verify those independently.

The PDF fixture metadata and artwork are original test content, not scans of any
third-party publication. PDF search/highlighting are not promised Leaf features.
