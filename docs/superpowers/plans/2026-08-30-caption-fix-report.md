# Report: fixing the golden fixture's false CAPTION on block 0

## The bug

On the golden end-to-end fixture (`ocr-core/src/test/resources/fixtures/real-worksheet-reading.json`,
61 real `RawLine` records from a photographed worksheet), block 0 - the document's own
title line, `"Academic Reading and Comprehension Worksheet"` - was classified `CAPTION`.

Root cause: `RoleClassifier.isCaption()` only checked three things - shorter than the
local baseline, a single line, and stopping well short of the column's right margin -
with no exemption for a block's position on the page. Block 0 satisfies all three:
its box measures left 400.85, top 130.66, right 728.71, bottom 144.67 (height ~14.0)
on a 1200x1600 page, against body paragraphs running 18-24px tall. Two things combine
to make it that short: the header line is set in a smaller print face than the essay
body, and it sits at the extreme top of a page photographed at an angle, where
perspective shrinks lines further the higher they are on the page.

This is precisely the class of error the project's governing priority forbids. A
`CAPTION` label tells a blind reader "this is subordinate explanatory text attached to
something else nearby" - the opposite of the truth, since this is the most important
line on the page. A wrong structural label actively misleads a reader who cannot see
the page to catch the error, which the project's stated priority calls worse than no
label at all.

The asymmetry that let this through: the `TITLE` branch in `roleOf()` already has a
top-band promotion (`relativeHeight >= headingHeightRatio && inTopBand -> TITLE`), but
the `CAPTION` branch had no corresponding top-band *exemption*. This was previously
identified and deliberately left unfixed - see
`docs/superpowers/plans/2026-08-30-test-hardening-report.md`, "Recommended follow-up
(not done here)" - as a sixth finding out of that task's five-finding scope, with the
golden test pinning the buggy behavior (`assertEquals(BlockRole.CAPTION, ...)`) rather
than silently asserting the ideal.

## The rule added

`RoleClassifier.isCaption()` now takes the same `inTopBand` boolean already computed in
`roleOf()` (`group.box.centerY <= pageHeight * config.topBandFraction`) and returns
`false` immediately when the block is in the top band - before checking height, line
count, or margin shortfall at all.

Rationale: a caption is subordinate text that belongs to something ABOVE it (a figure,
a table, an image). At the very top of a page there is nothing above the block for it
to be subordinate to, so `CAPTION` is never a plausible label there, regardless of how
short or narrow the block measures.

No new constant was introduced. The existing `StructuringConfig.topBandFraction`
(0.15) is reused, per the task's instruction to prefer reuse over a near-duplicate
threshold - the same value already gates the mirror-image (large-block) case, and
there is no reason a caption-exemption boundary and a title-promotion boundary should
sit at different heights on the page: both are asking "is this a plausible place to
find something over the very top of the reading order."

## What block 0 now classifies as, and why

`PARAGRAPH`. Not `TITLE`.

`PARAGRAPH` is the honest default here: it makes no claim about the block's structural
significance beyond what is directly observable (its text and position are preserved
verbatim), so the reader is told nothing false. It would have been tempting to reach
for `TITLE` since it happens to be correct on this specific page, but a short, single
line sitting in a page's top band is not reliably a title in general - it could just as
easily be a course code, a date, a running page header, or an instructor's note.
Confidently relabeling all such blocks `TITLE` would repeat the same class of error
(false structural confidence) in the opposite direction: instead of falsely demoting an
important line, it would falsely promote an unimportant one. `RoleClassifier` has no
independent signal here (this block's shortness is due to font size and perspective,
not to marker syntax or numbering) to distinguish "this short top-of-page line is the
title" from "this short top-of-page line is a running header" - so it defaults to the
label that asserts nothing extra.

## Tests added or amended

**`RoleClassifierTest.kt`** (`ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/RoleClassifierTest.kt`):

1. `a short block in the top band is not a caption` (new) - uses block 0's real
   geometry (left 400.85, top 130.66, right 728.71, bottom 144.67, 1200x1600 page)
   among taller body lines below it. Failed with `CAPTION` before the fix; asserts
   `PARAGRAPH` after.
2. `a genuinely small block in the middle of the page is still a caption after the
   top-band fix` (new) - a short, single-line block well clear of the top band, same
   shape as the pre-existing caption tests, asserting `CAPTION` still fires. This
   proves the fix is a scoped exemption, not a deletion of caption detection.

Both were run and observed to fail before the `RoleClassifier.kt` change (test 1
returned `CAPTION`) and pass after.

Note: the pre-existing tests `small text is a caption` and `a genuinely small block
that stops well short of the margin is a caption` already exercised mid-page caption
detection and continued to pass unchanged - they provide additional non-regression
coverage for the same property test 2 targets explicitly.

**`DocumentStructurerTest.kt`** (the golden end-to-end test, `golden end-to-end
fixture - a real photographed worksheet reads in true order with no false headings`):
block 0's expected role was changed from `BlockRole.CAPTION` to `BlockRole.PARAGRAPH`,
with the comment rewritten from "KNOWN DISCREPANCY" to "FIXED", explaining the new
top-band exemption and why `PARAGRAPH` (not `TITLE`) is the correct label. No other
assertion in that test was touched - the exact-text-match list, the line-count list,
and the "all body paragraphs (blocks 3-15) are PARAGRAPH" check are all unchanged and
still pass, since block 0 is outside `doc.blocks.drop(3)`.

No other test's expectation shifted.

## Full final test output

`:ocr-core:test` - 94 tests (92 pre-existing + 2 new), all PASSED, including:
- `DocumentStructurerTest > golden end-to-end fixture - a real photographed worksheet reads in true order with no false headings` PASSED
- `RoleClassifierTest > a short block in the top band is not a caption` PASSED
- `RoleClassifierTest > a genuinely small block in the middle of the page is still a caption after the top-band fix` PASSED
- every other `RoleClassifierTest`, `DocumentStructurerTest`, `LineMergerTest`, `RowFragmentJoinerTest`, `ReadingOrderSorterTest`, `PageStatsTest`, `MarkerParserTest`, `SkewEstimatorTest` case PASSED

`BUILD SUCCESSFUL in 4s` (rerun, no cache).

`:ocr-mlkit:testDebugUnitTest` - `BUILD SUCCESSFUL` (all 14 tests, no failures reported).

`:app:assembleDebug` - `BUILD SUCCESSFUL`.

## Remaining role-classification weakness noticed while in here

`RoleClassifier` still has no way to distinguish a true document TITLE from a page
header/course-code/date line when both are short and sit in the top band - it only
has geometry (height, position) to go on, no font-weight, font-family, or semantic
signal. This report's fix makes the honest choice (`PARAGRAPH`) for that ambiguous
case, but a worksheet whose real title happens to be short (e.g., a title set in the
same size as the body, or a very short title like "Quiz 3") will also fall through to
`PARAGRAPH` rather than `TITLE` or `HEADING`, even though a sighted reader would
recognize it as the title from its position and isolation (e.g., being the first block
on the page, standing alone with no other top-band siblings). A future improvement
could consider a position-only heuristic - e.g., "the single lone block whose vertical
centre is in the top band, if it is otherwise ambiguous in height" - but that is a
step beyond what this task's exemption was scoped to add, and risks the same kind of
overconfident mislabeling this report's `PARAGRAPH` choice was written to avoid. Flagging
it here rather than attempting it unrequested.
