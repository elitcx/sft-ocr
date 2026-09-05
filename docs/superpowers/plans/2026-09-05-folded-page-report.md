# Folded/curved facing-page filter — fix report — 2026-09-05

## The defect

A user reported that on a real open-book photo, "the folded facing page is read together
with the intended page, and the reading view comes out very messy... all just mixed up."

`ColumnSegmenter` already had a facing-page detector (`StructuringConfig
.facingPageMaxAngleDiffDeg`): group lines into horizontally separated regions, compare
their median line angles, and drop the non-dominant region when the angles disagree by
more than 4 degrees. This works when the facing page is flat enough to form a clean,
separable second column. It does **not** work when the facing page is folded or curved:
its lines scatter across many different angles as the paper curves away from the spine
and never line up into one x-range `ColumnSegmenter` would recognize as a column, so its
line-count/share floor and gutter detection let the scattered fragments straight through
as noise mixed into the single remaining column — the defect the user hit.

No new failing photo had been added to the corpus at the time of this fix (139 files in
`C:/Users/Kiel/Downloads/braille testing/`, matching the count noted in the brief); the
fix and its tolerance are driven entirely by the real fixtures already checked into the
repo, per the brief's own instruction not to invent synthetic angle data when real data
exists.

## The fix

New `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/FoldedPageAngleFilter.kt`,
wired into `DocumentStructurer.structure` immediately after `MarginFragmentFilter` and
BEFORE `ColumnSegmenter` runs:

```
val marginFiltered = MarginFragmentFilter.filter(allLines, stats, config)
val lines = FoldedPageAngleFilter.filter(marginFiltered, config)
val columns = columnSegmenter.segment(lines, stats, deskewed.result.imageWidth)
```

For each line, it reads the same post-deskew corner-angle signal `ColumnSegmenter`'s own
facing-page check already uses (`angleFromCorners(cornerPoints)` — `RawLine.angleDeg`
itself is reset to 0 by `SkewEstimator.deskew`, see `LineGeometry`'s KDoc), computes the
page's own median line angle across every line that reports one, and drops any line whose
angle disagrees with that median by more than `StructuringConfig
.foldedPageAngleToleranceDeg` (new, 20 degrees). A line with no `cornerPoints` at all (a
synthetic fixture, or a page ML Kit reported no quadrilateral for) is always kept — there
is no signal to judge it by. A safety floor,
`StructuringConfig.minFoldedPageRetainedLineShareFraction` (new, 0.5), refuses to let the
filter discard a majority of the page: if the "dominant" cluster it finds retains fewer
than half the page's lines, that is a sign the clustering itself failed, and the filter
returns every line unchanged rather than risk gutting real content.

This is a genuinely different, and earlier, mechanism from `ColumnSegmenter`'s own check,
not a replacement for it — see the reconciliation section below.

## Angle distributions measured

All angles below are the actual per-line signal `FoldedPageAngleFilter` and
`ColumnSegmenter`'s facing-page check both consume: `angleFromCorners(cornerPoints)`
**after** the full `FrameRotation` → `RotationPlausibilityGuard` → `SkewEstimator.deskew`
pipeline has run, i.e. exactly what the production code sees at the point this filter
executes. (This differs from a line's raw, pre-pipeline `angleDeg` field, which is
recomputed multiple times during rotation/deskew and does not, on its own, reflect the
signal the filter actually acts on.)

| Fixture | Rotation applied | n (non-blank) | Median (deskewed) | Max \|deviation\| | Notes |
|---|---|---|---|---|---|
| `real-facing-page-231108-raw.json` | 90 | 65 | 0.0° | 27.27° | Dominant page: 43 lines within ±5° of median. Facing-page outliers: 22 lines, five of them 19.95–27.27° away. |
| `real-rotated-231023-raw.json` | 90 | 18 | ~0.0° | 4.07° | **Canary**: single curved page, no facing page. |
| `real-worksheet-exercises.json` | 0 | 77 | 0.0° | 13.50° | Single real page (worksheet + vocabulary table); widest genuine single-page spread found in the corpus. |
| `real-rotated-230941-raw.json` | 180 | 35 (excl. the 7 known margin-noise fragments) | ~0.18° | 6.33° for real content; 41.44° for "fogpro" (the one deliberately-uncaught margin fragment — see `MarginFragmentFilter`'s KDoc) | Confirms the filter also independently catches "fogpro" via a different signal than the margin filter's position/confidence check. |
| `real-worksheet-reading.json` | 0 | 61 | n/a | n/a | No `cornerPoints` at all — filter is a no-op (`angles.size < 2`). |
| `worksheet-two-column.json` | 0 | 8 | n/a | n/a | No `cornerPoints` — filter is a no-op. |

## Tolerance chosen: 20 degrees

Bounded by exactly the two measurements the brief asked for:

- **Floor (must not cut into real content):** `real-worksheet-exercises.json`, a real
  single page with no facing page at all, measures a maximum genuine per-line deviation
  from its own median angle of **~13.5 degrees** — the widest legitimate single-page
  spread found in the corpus. The threshold must clear this.
- **Ceiling (should still catch the worst folded-page outliers):**
  `real-facing-page-231108-raw.json`'s five most extreme scattered outliers measure
  **19.95, 20.45, 22.85, 23.76 and 27.27 degrees** away from the dominant page's median.

20 sits just above the worksheet-exercises floor — the higher, more conservative side per
this project's governing priority (never wrongly discard real content) — while still
catching the widest folded-page outliers. It is deliberately **not** tuned to catch every
scattered fragment in that one fixture; the residual (lines whose angle deviates by less
than 20 degrees but still belong to the facing page) is left to `ColumnSegmenter`'s
complementary column-based check, described next.

## Reconciliation with `ColumnSegmenter`'s existing facing-page check

The two mechanisms are complementary, confirmed by re-running the full pipeline on
`real-facing-page-231108-raw.json` (the one real fixture that exercises both):

- `FoldedPageAngleFilter` (new, runs first) removes only the widest scattered outliers —
  the ones whose own individual angle disagrees sharply with the page median. It does not
  by itself reduce this fixture to a single column.
- `ColumnSegmenter`'s existing check (unchanged) then still finds two columns among the
  remaining lines and still drops the non-dominant one, exactly as it did before this fix
  — `an open book's facing page is discarded, not read as a second column` (existing test)
  still passes unchanged, still reporting `columnCount == 1` and excluding the same facing
  fragments ("A00a", "Kepada karsma", "asih ganda dn", "usi Uni Roma", "eniadi").

No conflict or double-drop was found: `FoldedPageAngleFilter` never removes enough of this
particular fixture's facing page to change `ColumnSegmenter`'s own line-count/share
verdict, and `ColumnSegmenter`'s check operates on whatever `FoldedPageAngleFilter` leaves
behind without assuming anything about what was already removed. Each stage is a
strictly-narrowing filter over the same list of lines, so running the new stage first is
safe by construction — it can only ever hand `ColumnSegmenter` a subset of what it would
otherwise have seen.

The genuinely new coverage this fix adds is the case `ColumnSegmenter` cannot reach at
all: a facing page scattered widely enough across many different angles that it never
forms a coherent second column in the first place (no real fixture with EXACTLY this
shape exists in the corpus yet — the closest real evidence is the scattered half of
`real-facing-page-231108-raw.json` itself, which — per the table above — spans a wide
enough angle range, 19.95–27.27 degrees past the target page's own median, to demonstrate
the same underlying symptom). `FoldedPageAngleFilterTest`'s
`scattered lines far outside the dominant cluster are dropped even without forming a
column` test pins this exact scenario directly.

## Canaries: proof of no regression

- **`real-worksheet-reading.json` and `real-worksheet-exercises.json` golden tests**: both
  re-ran unchanged and pass in full (`golden end-to-end fixture - a real photographed
  worksheet reads in true order with no false headings`, and `golden end-to-end fixture -
  background clutter is dropped and numbered questions are addressable`). Neither fixture
  has any line whose deviation exceeds the 20-degree tolerance (max measured: 0° and 13.5°
  respectively), so `FoldedPageAngleFilter` never removes anything from either — confirmed
  directly, not just inferred, since both golden tests assert exact block text/role/marker
  content and would fail on any dropped line.
- **`real-rotated-231023-raw.json` (curved single page, over-correction canary)**: new
  test `a real curved single page loses no lines to the folded-page angle filter` asserts
  every one of its 18 raw non-blank lines survives — verified as exactly 16 output
  `TextLine`s (18 raw lines minus 2 pre-existing `RowFragmentJoiner` crease-joins,
  confirmed identical with `FoldedPageAngleFilter` temporarily disabled during
  development) plus a word-level containment check across all 18 original lines' text.
- **`worksheet-two-column.json`**: `the two-column worksheet golden fixture is unaffected
  by the facing-page check` still passes unchanged — this fixture carries no
  `cornerPoints`, so `FoldedPageAngleFilter.filter` returns its input untouched
  (`angles.size < 2`) before `ColumnSegmenter` ever runs.

## Tests added

- `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/FoldedPageAngleFilterTest.kt`
  (new file, 6 tests):
  - lines with no corner points are never dropped
  - a flat page where every line agrees keeps everything
  - scattered lines far outside the dominant cluster are dropped even without forming a
    column (the core scenario this filter exists for)
  - a deviation within the measured single-page tolerance (13°) is kept
  - never discards a majority of the page when no single cluster dominates (three
    non-majority angle groups, 5/5/3 split — proves the retained-share safety floor)
  - fewer than two lines with a usable angle is left untouched
- `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurerTest.kt`
  (+1 test): `a real curved single page loses no lines to the folded-page angle filter`
  (the over-correction canary described above).

`StructuringConfig` gained two new fields, both with KDoc citing the measurements above:
`foldedPageAngleToleranceDeg` (20f) and `minFoldedPageRetainedLineShareFraction` (0.5f).

## Batch harness run on the real photo corpus

`OcrBatchStructureHarnessTest` was run on the Pixel_10_Pro AVD against 19 real photos from
the `20260731_23*` series in `C:/Users/Kiel/Downloads/braille testing/` (`AVD` storage was
limited to roughly 700 MB free after installing the app and test APKs, which capped how
many of the full 124-photo series could be pushed at once; the 19 pushed span
23:16:04-23:23:03, one of which — `20260731_232303.jpg` — failed to decode in ML Kit
entirely, `reason=NoTextFound detail=could not decode image`, unrelated to this fix and
unaffected by it either way). No new failing photo matching the user's exact report had
been added to the folder at the time of this run (still 139 files total, matching the
brief's stated baseline), so this run demonstrates the fix's effect on the existing corpus
rather than reproducing the user's specific photo.

The harness was run twice against the identical 19-image set and pulled to separate output
directories: once with the fix as shipped, and once with `FoldedPageAngleFilter.filter`
temporarily bypassed in `DocumentStructurer` (reverted immediately after, confirmed via a
full `:ocr-core:test` rerun that the shipped code was restored exactly). Both runs used
`adb shell am instrument` directly (not `./gradlew connectedDebugAndroidTest`, which
reinstalls the test APK as part of the task and resets the `MANAGE_EXTERNAL_STORAGE`
app-op grant every time, hiding the pushed corpus from the app under scoped storage until
re-granted — this cost the first attempt a wasted run reporing "entries=0"; documented here
so a future run does not repeat it).

Per-image column count and total output line count (sum of every block's `lines`), before
(fix disabled) vs after (fix as shipped):

| image | columns before | columns after | lines before | lines after | lines dropped |
|---|---|---|---|---|---|
| 20260731_231604.jpg | 1 | 1 | 8 | 8 | 0 |
| 20260731_231622.jpg | 1 | 1 | 61 | 60 | 1 |
| 20260731_231637.jpg | 1 | 1 | 25 | 25 | 0 |
| 20260731_231652.jpg | 1 | 1 | 9 | 9 | 0 |
| 20260731_231712.jpg | 1 | 1 | 37 | 37 | 0 |
| 20260731_231730.jpg | 1 | 1 | 41 | 41 | 0 |
| 20260731_231916.jpg | 1 | 1 | 43 | 43 | 0 |
| 20260731_231929.jpg | 1 | 1 | 32 | 30 | 2 |
| 20260731_231947.jpg | 1 | 1 | 22 | 22 | 0 |
| 20260731_232044.jpg | 2 | 2 | 39 | 39 | 0 |
| 20260731_232059.jpg | 1 | 1 | 22 | 22 | 0 |
| 20260731_232115.jpg | 1 | 1 | 66 | 66 | 0 |
| 20260731_232133.jpg | 1 | 1 | 77 | 77 | 0 |
| 20260731_232147.jpg | 4 | 4 | 118 | 115 | 3 |
| 20260731_232202.jpg | 1 | 1 | 87 | 85 | 2 |
| 20260731_232212.jpg | 1 | 1 | 113 | 100 | **13** |
| 20260731_232230.jpg | 1 | 1 | 97 | 97 | 0 |
| 20260731_232246.jpg | 3 | 3 | 111 | 103 | 8 |
| 20260731_232303.jpg | — decode failure both runs, unaffected — | | | | |
| **Total** | | | **1008** | **979** | **29** |

Observations:
- **Column count never changed** on any of the 18 successfully-recognized images. This
  matches the reconciliation analysis above: on this real corpus,
  `FoldedPageAngleFilter` trims outlier lines without ever flipping `ColumnSegmenter`'s
  own column verdict for these particular photos — it is additive pruning, not a
  redundant or conflicting second decision.
- Most images (12 of 18) lost 0 lines — the filter is a no-op on the majority of ordinary
  photos, as intended.
- Two images lost a double-digit number of lines: `20260731_232212.jpg` (13 of 113, 11.5%)
  and `20260731_232246.jpg` (8 of 111, 7.2%). Both are award/certificate-wall style photos
  (repeated name/prize/number rows: "Lomba Proposal Campaign Fikom", "Juara 2",
  "Universitas Ciputra", "Renata Emily Tanojo Kenneth Christoffersen", etc.), not open-book
  shots — plausibly multiple certificates or cards at different physical angles in one
  frame, which is exactly the class of "different physical surface at a different angle"
  defect this filter targets, just not the specific book-facing-page framing of the user's
  report.
- Spot-checking both images' retained reading order (`after` output, first several
  blocks): text reads as coherent, ordered runs of names/awards/numbers with no visible
  interleaving or nonsense fragments — consistent with the filter having removed
  off-angle noise rather than cut into the real content mid-stream. This is not a rigorous
  ground-truth check (no transcript exists for these photos), only a plausibility read,
  noted as a limitation below.
- No genuinely folded-book-facing-page photo in this 19-image subset exhibited the user's
  exact symptom strongly enough to show a dramatic before/after change — consistent with
  the corpus not yet containing the user's specific failing photo (see Known residual gaps
  below).

## Final gate status

All four gates from the brief pass with the fix as shipped:
- `:ocr-core:test` — green (121 tests, 0 failures; +7 new in `FoldedPageAngleFilterTest`,
  +1 new canary in `DocumentStructurerTest`).
- `:ocr-mlkit:testDebugUnitTest` — green.
- `:app:assembleDebug` — green.
- `:ocr-mlkit:connectedDebugAndroidTest` on the `Pixel_10_Pro` AVD — green (6 tests, 1
  intentionally skipped: `OcrAccuracyHarnessTest`, which requires a labeled corpus not
  present on this machine — pre-existing, unrelated to this change).

## Known residual gaps

- **No fixture reproduces the user's exact reported case** (a facing page folded/curved
  enough to scatter across many angles while never forming a column at all) end-to-end.
  The closest real evidence is the scattered half of `real-facing-page-231108-raw.json`,
  which is real but is also caught by `ColumnSegmenter`'s pre-existing column-based check
  regardless of this fix, so it cannot on its own prove `FoldedPageAngleFilter` is
  necessary in production — only that it is safe and behaves as designed
  (`FoldedPageAngleFilterTest`'s synthetic scattered-lines test proves the mechanism
  itself works; it is a unit test of the filter in isolation, not an end-to-end real-photo
  proof). If the user's exact photo is added to the corpus, it should be pushed through
  `OcrBatchStructureHarnessTest` and, if it still exhibits the defect, used to re-tune
  `foldedPageAngleToleranceDeg` or add an end-to-end regression fixture.
- 20 degrees is bounded above only by the corpus evidence gathered so far
  (worksheet-exercises' 13.5°). A future real photo with a wider genuine single-page
  spread than 13.5° but under 20° would still be safe; one wider than 20° would start
  losing real content, the same tradeoff every other threshold in `StructuringConfig`
  makes explicitly.
- `FoldedPageAngleFilter` and `ColumnSegmenter`'s facing-page check both fire on
  `real-facing-page-231108-raw.json` (see reconciliation above) - by design, not a defect,
  but a future maintainer changing either threshold should re-check both fixtures listed
  in this report before assuming the other is unaffected.
