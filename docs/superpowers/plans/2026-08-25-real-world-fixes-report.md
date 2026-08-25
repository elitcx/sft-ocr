# BRaiLLE OCR MVP — real-world fixes (from a photographed worksheet)

**Branch:** `feat/ocr-mvp` · **Base:** `898a490`
**Date:** 2026-08-25

| # | Finding | Commit |
|---|---------|--------|
| BUG 1 (critical) | Terminal punctuation splits justified paragraphs mid-prose | `25e13ff` |
| BUG 2 (minor) | A paper fold splits one line into two blocks | `b1af8f5` |

---

## BUG 1 — terminal punctuation splits paragraphs mid-prose

### Before

`LineMerger.continues()` broke a block whenever the previous line ended in `.!?:;`,
regardless of how far the line reached toward the column's right margin. In justified
body prose most wrapped lines end mid-paragraph with a period, so a multi-sentence
paragraph was torn apart at every sentence boundary that happened to fall at end-of-line.

**Real coordinates that exposed it** — one paragraph, split into two blocks by the old code:
- line A: `"The provision of free nutritious meals for school students has become an
  important topic of public discussion."` box left=238.66, top=203.65, right=913.63,
  bottom=224.17 (ends `.`, reaches the same right margin ~914 as every other body line)
- line B: `"Supporters argue that sucha program could improve chitdren's health, support
  acadernic performance, and reduce"` box left=214.14, top=223.26, right=914.10,
  bottom=243.16

Contrast — a genuine paragraph end, which correctly stayed split:
- line C: `"perspectives."` box left=214.05, top=343.29, right=290.73, bottom=357.45
  (ends `.`, stops far short of the ~914 margin)

### After

Terminal punctuation now ends a block only when the line **also** stops short of its
column's right margin by more than `lineEndToleranceFactor` (new, `3.0f`) median
character widths. A full-width line ending in a period is treated as a sentence boundary
inside a wrap; a short line ending in a period is still a paragraph end.

`LineMerger.merge()` computes a per-column right margin (the widest right edge seen in
that column) before the merge loop. Guard against a single anomalously long line (e.g. a
spanning line pinned to the column) skewing that margin: with 3+ lines in the column, if
the widest line's right edge is more than `lineEndToleranceFactor` median char widths
ahead of the *second*-widest, the second-widest is trusted as the margin instead.
`merge()`'s public signature is unchanged; only the private `continues()` gained a
`columnRightMargins: Map<Int, Float>` parameter.

All other break conditions (column change, new marker, paragraph gap, negative-gap guard,
left-alignment/indent logic) are untouched.

### Tests added (`LineMergerTest.kt`)

1. `a full width line ending in a period merges with the next line (real worksheet
   regression)` — lines A/B above, real coordinates, asserts one block of two lines.
   **Confirmed this test genuinely fails before the fix** (ran it against the unmodified
   `LineMerger` — `AssertionFailedError`, old code produced two blocks).
2. `a short line ending in a period still splits (real worksheet regression)` — line C
   above followed by a full-width paragraph opener, real coordinates, asserts two blocks.
3. Existing test `terminal punctuation ends a block` was **renamed and its fixture
   changed** — see "Disagreements / changes to existing tests" below.

### Disagreements / changes to existing tests

`LineMergerTest > terminal punctuation ends a block` pinned the *old, wrong* behaviour: it
used two lines of **identical width** (both 600px), so under the fix a period at the end
of the first line no longer breaks the block (the line reaches the same margin as the
rest of the column) — that is precisely the BUG 1 scenario, not a genuine paragraph end.
I did not weaken the test; I changed its fixture so the first line is genuinely shorter
than the column's established width (300px vs. 600px) and renamed it to `terminal
punctuation ends a block only when the line also stops short of the margin`, matching what
the assertion actually now verifies. Verified this reshaped test also passes against the
pre-fix code (the old code split unconditionally on terminal punctuation, so any period +
gap combination passed it before too) — i.e. it is a genuine "preserved behaviour" test,
not a new requirement introduced by the fix.

---

## BUG 2 — a fold in the paper splits one line into two blocks

### Before

A physical crease produced two ML Kit line fragments on one visual row, which
`ReadingOrderSorter`/`LineMerger` treated as two separate lines and therefore two blocks.

**Real coordinates:**
- fragment A: `"Concerns about financialc"` box left=214.07, top=302.39, right=373.75,
  bottom=317.30
- fragment B: `"cost, food waste, administrative complexity, and the challenge of
  maintaining food quality"` box left=370.15, top=302.46, right=914.03, bottom=323.19

### After

New pipeline stage `pipeline/RowFragmentJoiner.kt`, wired into `DocumentStructurer`
between `ReadingOrderSorter` and `LineMerger`. Joins two consecutive ordered lines when:
same column; vertical extents overlap by more than `rowOverlapFraction` (new, `0.5f`) of
the smaller line's height; horizontal gap (later line's left minus earlier line's right)
is at most `rowFragmentGapFactor` (new, `2.5f`) median character widths — a negative gap
(slight overlap) counts as adjacent.

Joined text is `"$a $b"` (single space). Box is the union. Confidence is the mean of the
two (falls back to whichever is non-null if one is missing). `recognizedLanguage` is kept
from the first fragment via `a.copy(...)`. `cornerPoints` are dropped and set to
`emptyList()` with a comment explaining why: the union box has no well-defined tilted
quadrilateral, and `SkewEstimator` trusts `cornerPoints` over `box`, so carrying stale
per-fragment corners forward would silently corrupt deskewing. This is safe because
`RowFragmentJoiner` runs *after* `SkewEstimator`'s deskew pass.

### Tests added

`RowFragmentJoinerTest.kt` (new file):
1. `two fragments split by a crease join into one line` — fragments A/B above, real
   coordinates, asserts one joined line with the two texts space-joined.
2. `a left column line and a right column line sharing a row do not join` — two-column
   layout, same visual row on both sides, asserts all four lines stay separate (proves the
   same-column check prevents over-joining).

`DocumentStructurerTest.kt`:
3. `a justified paragraph with sentence-ending wraps stays one block, followed by a second
   paragraph` — end-to-end regression combining both bugs' shape: a synthetic
   multi-sentence justified paragraph (three full-width lines with sentence-ending
   periods, one short final line) followed by a second paragraph of the same shape.
   Asserts exactly two blocks, with the correct line counts and text boundaries.

---

## Final test output

`./gradlew :ocr-core:test` — **74 tests, 74 passed, 0 failed** (69 pre-existing + 5 new: 2
in `LineMergerTest`, 2 in `RowFragmentJoinerTest`, 1 in `DocumentStructurerTest`; the
`terminal punctuation...` test was reshaped, not added, so the net new test count is 5
while the file gained more assertions).

```
LineMergerTest > a full width line ending in a period merges with the next line (real worksheet regression)() PASSED
LineMergerTest > a short line ending in a period still splits (real worksheet regression)() PASSED
LineMergerTest > terminal punctuation ends a block only when the line also stops short of the margin() PASSED
LineMergerTest > tight lines with aligned edges form one paragraph() PASSED
LineMergerTest > a wide vertical gap starts a new block() PASSED
LineMergerTest > a new marker always starts a block even when tightly spaced() PASSED
LineMergerTest > a continuation line joins its marker block() PASSED
LineMergerTest > a column change always starts a block() PASSED
LineMergerTest > reflow joins a hyphenated wrap without a space() PASSED
LineMergerTest > reflow joins ordinary wraps with a single space() PASSED
RowFragmentJoinerTest > two fragments split by a crease join into one line() PASSED
RowFragmentJoinerTest > a left column line and a right column line sharing a row do not join() PASSED
DocumentStructurerTest > a justified paragraph with sentence-ending wraps stays one block, followed by a second paragraph() PASSED
DocumentStructurerTest > (... all other pre-existing DocumentStructurerTest cases) PASSED
(... all other pre-existing ColumnSegmenterTest / SkewEstimatorTest / ReadingOrderSorterTest /
     RoleClassifierTest / MarkerParserTest / PageStatsTest / ErrorRateTest / GeometryTest /
     OcrDocumentTest / ScaffoldTest cases) PASSED

BUILD SUCCESSFUL
```

`./gradlew :ocr-mlkit:testDebugUnitTest :app:assembleDebug` — **BUILD SUCCESSFUL**, no
changes were made to `:ocr-mlkit` or `:app`; both still compile and their tests still pass
against the modified `:ocr-core` API surface (no public signatures broke).

---

## Concerns / things I could not fully verify

- The `columnRightMargins` outlier guard in `LineMerger` (skip the widest right edge in
  favor of the second-widest when the gap between them exceeds
  `lineEndToleranceFactor * medianCharWidth`) is a judgment call the spec explicitly left
  open ("consider whether one anomalous over-long line could skew it and guard if so").
  It reuses `lineEndToleranceFactor` rather than adding a dedicated config knob, to avoid
  proliferating near-duplicate thresholds; if the two need to diverge in practice, they
  should be split into separate config fields.
- The `RowFragmentJoiner` "different-column" test uses two columns with a large visible
  gap (matching this codebase's existing two-column test fixtures) rather than a
  near-miss case where the gap alone would almost pass the join threshold. The
  same-column guard is unconditional in the code (checked first, short-circuits), so this
  does not weaken the actual guarantee, but a tighter adversarial fixture would make the
  test's intent more visually obvious.
- I did not attempt the "spanning-line ordering tier" improvement `ColumnSegmenter`'s
  KDoc flags as future work — out of scope for this task.
