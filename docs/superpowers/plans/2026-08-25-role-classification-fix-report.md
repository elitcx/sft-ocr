# Role-classification fix — real-worksheet regression report

Date: 2026-08-25
Branch: `feat/ocr-mvp`

## The bug

`RoleClassifier` decided `TITLE`/`HEADING`/`CAPTION` from `relativeTextHeight`, a
block's median line height divided by the *whole page's* median line height. That
comparison assumes a flat scan. On a handheld photo, lines nearer the bottom of the
page are physically closer to the camera and measure taller purely from perspective,
even at identical printed font size.

Observed on the real photographed worksheet:

| Block | Content | Old ratio (page-wide) | Old label | Correct label |
|---|---|---|---|---|
| 19, 20, 21 | four-line, full-width body paragraphs, page bottom | 1.256 / 1.254 / 1.251 | HEADING | PARAGRAPH |
| 0, 12, 13 | ordinary content, page top | 0.778 / 0.794 / 0.790 | CAPTION | PARAGRAPH |
| 2 | the passage's actual bold heading (bold, not larger) | 0.874 | PARAGRAPH | not fixable by height alone — see Limitations |

A wrong `HEADING` label actively corrupts a blind reader's interpretation of an
ordinary paragraph, which is worse than no label. The fix trades "guess harder" for
"guess less."

## What changed, by part

### Part 1 — a wrapped, full-width block is body text

`RoleClassifier.isWrappedFullWidth` (new): a `LineGroup` with more than one line, where
every line except the last reaches the column's right margin (within
`lineEndToleranceFactor` median char widths — reusing the exact tolerance
`LineMerger` already uses for its own paragraph-end decision), is forced to
`PARAGRAPH` before any height estimate is even consulted. Headings and captions do
not wrap across multiple full-width lines; only body text does.

This required exposing the column-right-margin computation `LineMerger` already had
(`LineMerger.columnRightMargins`, changed from `private` to `internal`) rather than
recomputing it a second way. `DocumentStructurer` now computes it once from the
joined lines and threads it into `RoleClassifier.classify`.

- Before: blocks 19/20/21 → HEADING (ratio ~1.25, page-wide).
- After: blocks 19/20/21 → PARAGRAPH, unconditionally, because they are 4-line
  full-width wraps. No ratio is even computed for the decision.

### Part 2 — compare locally, not page-wide

`RoleClassifier.localBaselines` (new): for each block, in reading order, takes the
median line height of a window of `StructuringConfig.localHeightWindowSize` (default
5) neighbouring blocks on either side, including itself. Perspective distortion is a
smooth gradient down the page, so this cancels it while still catching a genuinely
larger heading sitting among normal text (the gradient near any single heading is
locally ~flat).

When the window holds fewer than `StructuringConfig.minNeighboursForLocalBaseline`
(default 3) blocks — e.g. a lone title with only one neighbour — the local baseline
falls back to the whole page's median line height, matching the old behaviour for
that edge case (this is exercised by `a skewed page still classifies its title as a
title`, whose window only ever holds 2 blocks).

`TextBlock.relativeTextHeight` still carries the ratio, but it is now the block's
height divided by its **local** baseline, not the page median. Its KDoc was rewritten
to say so.

- Before: blocks 0/12/13 → CAPTION (ratio ~0.78-0.79, page-wide).
- After: with local comparison, their ratio moves toward 1.0 relative to their true
  neighbours → PARAGRAPH (and Part 3 below adds a second, independent guard).

### Part 3 — make CAPTION conservative

`RoleClassifier.isCaption` (new) requires **all three**: relative height at or below
`captionHeightRatio`, exactly one line, **and** stopping at least
`captionMinShortfallFactor` (default 4.0) median character widths short of the
column's right margin (new config field). A short block that still reaches the
margin, or that wraps, is body text that happens to be brief — not a caption.

- A single short block slightly below the local baseline but still reaching the
  margin → PARAGRAPH (was CAPTION under the old "any block below the ratio" rule).
- A genuinely small, short, single-line block that stops well short of the margin
  (e.g. "Gambar 1 rantai makanan" under a full-width paragraph) → still CAPTION.

## StructuringConfig additions (all KDoc'd, no inline magic numbers)

```kotlin
val localHeightWindowSize: Int = 5
val minNeighboursForLocalBaseline: Int = 3
val captionMinShortfallFactor: Float = 4.0f
```

## Tests added (`RoleClassifierTest.kt`)

1. `a wrapped full-width paragraph at the page foot is not a heading` — four-line,
   full-width block with lines ~25% taller than surrounding text (mirrors blocks
   19-21); asserts PARAGRAPH.
2. `a genuinely larger heading among normal text is still a heading` — a real 1.5x
   single-line heading among six ordinary body blocks; asserts HEADING, proving the
   fix did not disable heading detection.
3. `a smooth top-to-bottom perspective gradient yields no headings or captions` — the
   core regression test: 15 ordinary single-line body blocks with line height
   growing linearly from 14px to 24px down the page, simulating perspective; asserts
   no block anywhere in the page is classified HEADING, CAPTION, or TITLE.
4. `a short block that still reaches the margin is a paragraph, not a caption` —
   0.83x local ratio but full-width; asserts PARAGRAPH.
5. `a genuinely small block that stops well short of the margin is a caption` —
   confirms the true-caption case still works under the new stricter rule.

All five pass against the real geometry described above; none needed to be weakened.
No existing test's expectation had to be changed — the existing page-wide-median
tests (`large text at the top of the page is a title`, `moderately large text mid
page is a heading`, `small text is a caption`, and the `DocumentStructurerTest`
title/skew cases) all still pass unmodified, because their windows are small enough
that the local baseline coincides with (or, for the 2-block skewed-title case, falls
back to) the old page-wide median. No eighth error was found in the existing test
suite this time — the local-window design was checked by hand against every
pre-existing assertion before implementation, specifically to avoid a repeat of that
pattern.

## Full final test output

`:ocr-core:test` — 79 tests, 0 failures, 0 errors, 0 skipped (74 pre-existing + 5 new).

```
RoleClassifierTest > a short block that still reaches the margin is a paragraph, not a caption() PASSED
RoleClassifierTest > large text at the top of the page is a title() PASSED
RoleClassifierTest > a numeric only block at the page foot is a page number() PASSED
RoleClassifierTest > a bullet is always a list item() PASSED
RoleClassifierTest > small text is a caption() PASSED
RoleClassifierTest > a smooth top-to-bottom perspective gradient yields no headings or captions() PASSED
RoleClassifierTest > a wrapped full-width paragraph at the page foot is not a heading() PASSED
RoleClassifierTest > a genuinely small block that stops well short of the margin is a caption() PASSED
RoleClassifierTest > three or more numbered blocks become questions() PASSED
RoleClassifierTest > moderately large text mid page is a heading() PASSED
RoleClassifierTest > a lone numbered block stays a list item() PASSED
RoleClassifierTest > a genuinely larger heading among normal text is still a heading() PASSED
... (all other pre-existing ColumnSegmenterTest, DocumentStructurerTest, LineMergerTest,
    MarkerParserTest, PageStatsTest, ReadingOrderSorterTest, RowFragmentJoinerTest,
    SkewEstimatorTest, GeometryTest, ErrorRateTest, OcrDocumentTest suites) PASSED

BUILD SUCCESSFUL
```

`:ocr-mlkit:testDebugUnitTest :app:assembleDebug` — BUILD SUCCESSFUL, no changes made
to either module.

## What this still cannot detect (documented, not hidden)

- **Bold-but-same-size headings** (block 2 on the real worksheet). ML Kit's
  `RawLine` carries no bold/weight signal at all in this pipeline, and even if it
  did, a bold heading printed at the same point size as the body text produces
  *no* height difference for this classifier to notice — by design, height is the
  only signal in scope here. Such a heading will continue to classify PARAGRAPH.
  Per the stated priority (a wrong HEADING is worse than no label), this is the
  correct conservative outcome given the available signal, not a bug: a genuine
  fix would require either a bold/weight signal from the recognizer or a
  font-density heuristic (e.g. ink coverage per glyph box), neither of which
  exists in `:ocr-mlkit`'s current output. This is a known gap for a later phase,
  not something papered over here.
- **A heading that is both same-height and unbolded**, distinguished only by
  whitespace above it or by content (e.g. short imperative phrase, title case) —
  out of scope entirely; this classifier has no layout-whitespace or NLP signal.
- **Very short pages** (fewer than `minNeighboursForLocalBaseline` blocks total)
  fall back to the page-wide median for every block, which reintroduces the
  original perspective-distortion risk in the small-page case. This is an
  accepted trade-off: with too few blocks there is no local context to compare
  against in the first place, so page-wide is the only signal left, not a
  regression introduced by this fix.
- **A perspective gradient steep enough, or a page short enough, that even a
  0.85x/1.25x window ratio is crossed at the very top or bottom edge of the page**
  (asymmetric window — no neighbours exist on the far side). The fix reduces this
  risk sharply (verified down to a 14px→24px gradient over 15 blocks with 5-block
  windows, comfortably inside threshold) but cannot eliminate it for an arbitrarily
  steep or short page; `localHeightWindowSize` is a tunable trade-off between
  gradient-cancellation and responsiveness to a real nearby heading, not a proof of
  correctness in the limit.

## Files changed

- `braille-ocr/ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/RoleClassifier.kt`
- `braille-ocr/ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/StructuringConfig.kt`
- `braille-ocr/ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/LineMerger.kt`
  (`columnRightMargins` made `internal`)
- `braille-ocr/ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurer.kt`
- `braille-ocr/ocr-core/src/main/kotlin/id/dotcode/braille/ocr/model/OcrDocument.kt`
  (`relativeTextHeight` KDoc)
- `braille-ocr/ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/RoleClassifierTest.kt`

`:ocr-mlkit` and `:app` were not touched.
