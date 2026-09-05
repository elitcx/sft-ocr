# Report: background clutter and unaddressable questions

Date: 2026-09-05
Branch: `feat/ocr-mvp`
Commits: `d1eb062` (defect 1), `c18c6e4` (defect 2)

## Summary

Two real defects, both observed by driving the app on the `Pixel_10_Pro` AVD against
a real photographed worksheet
(`ocr-mlkit/src/androidTest/assets/quality/IMG-20260820-WA0000.jpg`), were diagnosed
from real ML Kit recognizer data (not speculation) and fixed in `:ocr-core`:

1. Five small text fragments from a second sheet lying underneath the worksheet,
   upside down, partially visible at the left frame edge, formed a phantom second
   "column" and were read first.
2. Each "Reason: ____" answer line got concatenated onto the FOLLOWING numbered
   question's text by `RowFragmentJoiner`, burying that question's marker mid-string
   where `MarkerParser` could never find it - making questions 2, 3 and 4
   unaddressable for per-question answer tracking.

## Step 1: capturing real recognizer data

Added a temporary instrumented test (`RawResultDumpTest`, deleted after use) that ran
`ImagePreprocessor.decodeSampled` → `ImagePreprocessor.downscale` → ML Kit's
`TextRecognizer` → `MlKitAdapter.toRawTextResult` against the real asset, exactly the
production path in `OcrEngine.recognize`, and serialized the resulting
`RawTextResult` (kotlinx.serialization, already `@Serializable`) to
`/sdcard/Download/real-worksheet-exercises.json`, pulled with `adb pull`.

The recognizer returned **77 lines** for a **1200x1600** page (post-downscale,
rotation-corrected). That JSON is now committed as
`braille-ocr/ocr-core/src/test/resources/fixtures/real-worksheet-exercises.json` so
both fixes below have pure-JVM `:ocr-core` regression tests with no emulator needed.

### Key measurements

**Clutter fragments (background sheet) vs. main body, x-ranges:**

| idx | text | box (left, top, right, bottom) |
|---|---|---|
| 0 | `noN` | 56.0, -4.0, 105.0, 14.0 |
| 1 | `"ue` | 61.0, 36.0, 90.0, 58.0 |
| 2 | `Buou` | 72.0, 79.0, 104.0, 99.0 |
| 3 | `1snu` | 80.0, 125.0, 107.0, 142.0 |
| 4 | `pur` | 88.0, 172.0, 109.0, 186.0 |

Main body's leftmost real line: `In conclusion, providing free nutritious meals...`
at `left=218.0`. Whole-page `PageStats`: `medianCharWidth=6.264151`,
`medianLineHeight=15.0`. `ColumnSegmenter` (pre-fix) computed 2 runs -
`56.0..109.0` (clutter) and `191.0..954.0` (body) - a gutter of 82px, comfortably
above `columnGutterFactor(3.0) * medianCharWidth ≈ 18.8`, so the split fired. The
clutter run held 5 of 77 column-bound lines (6.5%), which cleared the old
`minLinesPerColumn = 2` floor and so counted as a real column: `columnCount = 2`,
reported to the user as "2 kolom" for a single-column page, with the clutter column
sorted first (column-major reading order) and its garbage text read before any real
content.

**`Reason:` lines and the following question's first line, exact boxes (post-deskew):**

| idx | text | box |
|---|---|---|
| 66 | `Reason:` | left=236.0, top=1220.0, right=286.0, bottom=1231.0 |
| 67 | `1.Providing free nutritious meals could support students' learning indirectly because well-nourished students` | left=235.0, top=1165.0, right=948.0, bottom=1198.0 |
| 68 | `may have more energy and better concentration.` | left=235.0, top=1195.0, right=532.0, bottom=1215.0 |
| 70 | `Reason:` | left=239.0, top=1272.0, right=287.0, bottom=1284.0 |
| 71 | `2. The passage states that providing free meals will automatically guarantee better academic performance for` | left=237.0, top=1221.0, right=945.0, bottom=1254.0 |
| 72 | `every student.` | left=238.0, top=1255.0, right=321.0, bottom=1268.0 |
| 73 | `Reason:` | left=243.0, top=1327.0, right=292.0, bottom=1338.0 |
| 74 | `3. If the program is poorly managed, food waste and distribution problems could reduce its efficiency and` | left=241.0, top=1276.0, right=918.0, bottom=1307.0 |
| 75 | `waste public resources.` | left=241.0, top=1308.0, right=383.0, bottom=1321.0 |
| 76 | `Auniversal meal program could help reduce inequalities associated with unequal access to nutritious food.` | left=296.0, top=1332.0, right=944.0, bottom=1361.0 |

Note line 76: the recognizer's output contains **no "4." anywhere** - the text
literally starts `Auniversal...`. Confirmed by grepping the raw JSON for any
fragment near that y-range: nothing else exists there. ML Kit dropped question 4's
marker glyphs entirely.

## Defect 1 root cause and fix

`ColumnSegmenter`'s "populated" guard only checked an absolute line-count floor
(`minLinesPerColumn = 2`). Five clutter fragments cleared that floor while being a
vanishingly small share (6.5%) of the page's real content.

**Fix** (`StructuringConfig.minColumnLineShareFraction = 0.15f`): a column must now
also hold at least 15% of the page's column-bound lines. A column failing *only*
this share floor (not the absolute-count floor) is additionally identified as
clutter, and when exactly one other column clearly dominates the page, its lines
are **dropped from the document entirely** (`ColumnAssignment.columnIndex` reports
`-1`, and `DocumentStructurer` filters those lines out before reading order is
computed) - not merely folded into the surviving column.

Both mechanisms from the prompt were implemented, not just one: the share threshold
alone fixes the column-count misreport, but leaving the clutter lines in a
collapsed single column would still scatter them through the reading order by
vertical position (their y-ranges interleave with the essay paragraphs, since the
clutter sits near the top of the frame overlapping the first several lines of real
text) - worse than reading them first, since they'd appear *scattered mid-paragraph*
instead of merely leading. The drop rule is deliberately conservative: it only
fires when exactly one column clearly dominates (fails only the count floor, not
the share floor too), so a genuinely small second column on a real two-column
worksheet is never discarded, only collapsed to a single reported column (see
`ColumnSegmenterTest`'s "an under-populated column with a fair share is collapsed
but not dropped").

Verified against both fixtures: `real-worksheet-reading.json`'s golden test still
passes unchanged (it never enters the multi-run branch - it's already reported as
1 column), and the new exercises golden test asserts none of the five clutter
fragments appear as blocks and that the first block is the real opening sentence.

## Defect 2 root cause and fix

Of the three candidate explanations in the brief, the real cause was closest to the
first: **`RowFragmentJoiner`, not `LineMerger`, silently fused `Reason:` onto the
next question's text before either `LineMerger` or `MarkerParser` ever saw the
question line on its own.**

Each `Reason:` line and the following question's first line are printed on
**separate physical rows** with tight leading. Their bounding boxes overlap
vertically (e.g. lines 66/71: overlap = 10px, `smallerHeight * rowOverlapFraction`
= 5.5px, so the vertical-overlap check passed), and because both text runs start
near the same left margin, `Reason:`'s box was almost entirely **contained inside**
the following full-width question line's x-range (gap = `237 - 286 = -49`). The old
rule ("a negative gap counts as adjacent too") accepted any negative gap
unconditionally, with no lower bound - so `RowFragmentJoiner.join` concatenated them
into one `RawLine`, `"Reason: 2. The passage states..."`, before `LineMerger` or
`MarkerParser` ran at all.

The "no space after the period" hypothesis was ALSO real, independently: block 32's
buried text read `...1.Providing free nutritious meals...` with no space after
`1.`. `MarkerParser.NUMERIC` required `[.)]\s+`, so even after `RowFragmentJoiner`
was fixed, this line's own marker still wouldn't have been found without a second
fix.

**Fixes:**

- `RowFragmentJoiner.sameRow` now bounds the allowed horizontal overlap to
  `StructuringConfig.rowFragmentMaxOverlapFactor` (1.5) median character widths,
  and separately requires the two candidate fragments' heights to be within
  `rowFragmentMaxHeightRatioFactor` (2.0x) of each other - two halves of one
  crease-split line are the same font size, but a short `Reason:` label and a
  three-line-tall question line are not. Both bounds are skipped for the
  deliberate "reading order reversed by a band-straddling crease" case (see the
  updated KDoc on `sameRow`), which is a pre-existing, still-passing test
  (`fragments straddling a band boundary join in left-to-right reading order`).
- `MarkerParser.NUMERIC` makes the space after the marker punctuation optional
  specifically when the following character is a **letter**, not a digit - this is
  what keeps `"3.14 adalah nilai pi"` and `"3.14adalah nilai pi"` unmatched (the
  character after the period is a digit) while accepting `"1.Providing..."`.

Together these split questions 1, 2 and 3 into their own blocks, each with its
numeric marker (`"1."`, `"2."`, `"3."`) extracted into `TextBlock.marker` and
stripped from `text`, classified `QUESTION` (three numbered blocks clears
`minNumberedBlocksForQuestions = 3`). Each `Reason:` line is now its own separate
block.

### Known residual gap (not fixed - reported, not worked around)

Question 4 **is** now its own separate block (both fixes still apply to it), but it
has **no marker**: ML Kit's recognition of this specific photo drops the `"4."`
marker text entirely, confirmed by inspecting the raw fixture - there is no `"4."`
token anywhere in the 77 recognized lines. `MarkerParser` cannot extract a marker
that was never recognized as text; this is upstream OCR data loss, not a
structuring-pipeline defect, and is pinned honestly in the golden test rather than
worked around (`DocumentStructurerTest`'s golden test asserts `lastBlock.marker ==
null` with a comment explaining why).

A secondary, cosmetic consequence: without a numeric marker, question 4's block
falls through to height-based role classification and comes out `TITLE` rather
than `PARAGRAPH`/`QUESTION` - a locally-skewed baseline among the short `Reason:`/
table-number blocks nearby. This is a `RoleClassifier` side effect of the same
upstream OCR loss, not something this fix attempts to compensate for; flagging it
here rather than adding a marker-shaped patch for one fixture's specific breakage.

This is a plausible **thirteenth genuine defect class** worth naming even though it
isn't fixed: the pipeline has no way to signal "this block was probably supposed to
be a numbered item, but the marker text itself never arrived" versus "this really
is unordered prose." Nothing in this change attempts that; it would need either
recognizer-level confidence signals ML Kit doesn't expose per-character, or a
heuristic (e.g. "block that directly follows a run of a `Reason:`+`QUESTION` pattern
inherits the pattern") explicitly out of scope for a two-defect fix.

## What changed

- `StructuringConfig.kt`: added `minColumnLineShareFraction`,
  `rowFragmentMaxOverlapFactor`, `rowFragmentMaxHeightRatioFactor`, each with KDoc.
- `Intermediates.kt`: documented `ColumnAssignment.columnIndex`'s `-1` "dropped"
  sentinel.
- `ColumnSegmenter.kt`: share-floor populated check; clutter-column drop when
  exactly one column dominates.
- `DocumentStructurer.kt`: filters `-1`-marked (dropped) lines out before
  `ReadingOrderSorter` runs; `meanConfidence` now excludes dropped lines too.
- `RowFragmentJoiner.kt`: bounded horizontal-overlap and height-ratio guards on
  `sameRow`, direction-aware (forward vs. reversed reading order) so the existing
  band-straddling-crease behavior is preserved exactly.
- `MarkerParser.kt`: `NUMERIC` regex's mandatory space made conditional on the next
  character being a digit vs. a letter.
- New fixture: `ocr-core/src/test/resources/fixtures/real-worksheet-exercises.json`
  (77 real `RawLine` records).
- Tests added:
  - `ColumnSegmenterTest`: `background clutter at the frame edge is dropped, not
    treated as a second column`; `an under-populated column with a fair share is
    collapsed but not dropped`.
  - `RowFragmentJoinerTest`: `a Reason label does not join with the next
    question's text despite overlapping boxes`.
  - `MarkerParserTest`: `a numeric marker with no space after the period is still
    recognized`; `a decimal number with no leading space is still not a marker`.
  - `DocumentStructurerTest`: `golden end-to-end fixture - background clutter is
    dropped and numbered questions are addressable` (covers both defects against
    the real fixture end-to-end, including the documented residual gap).

## Test output

`:ocr-core:test` - **100 tests, 0 failed** (was 94; +6 new). Includes both golden
fixtures (`real-worksheet-reading.json` unchanged, `real-worksheet-exercises.json`
new) passing unchanged/as designed.

`:ocr-mlkit:testDebugUnitTest` - **14 tests, 0 failed** (untouched by this change).

`:app:assembleDebug` - **BUILD SUCCESSFUL**.

`:ocr-mlkit:connectedDebugAndroidTest` on `Pixel_10_Pro(AVD)` - **5 tests run, 0
failed, 1 skipped** (`OcrAccuracyHarnessTest` skips by design with no device
samples pushed - pre-existing, unrelated to this change).

## Anything still wrong, not fixed here

- Question 4's marker cannot be recovered (OCR data loss) - documented above.
- Question 4's resulting `TITLE` misclassification - a `RoleClassifier` side
  effect of the same loss, not patched.
- The 24-row vocabulary table still merges rows (explicitly out of scope per the
  task - table-structure recognition is a separate, larger design question the
  user and prior work already agreed to defer).
- The temporary diagnostic harness (`RawResultDumpTest` in `:ocr-mlkit`) used to
  capture the fixture was deleted after use, per its own KDoc; the JSON it
  produced is the only lasting artifact.
