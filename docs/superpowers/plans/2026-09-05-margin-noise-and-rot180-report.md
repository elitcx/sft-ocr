# Margin-noise injection and EXIF-180 skew — fix report — 2026-09-05

Both defects were reported, with evidence, in
`docs/superpowers/plans/2026-09-05-real-corpus-report.md`. This report documents the root
cause, fix, and verification for both.

## Defect 2 — EXIF orientation 3 (180°) still produces impossible skew

### Real data behind the root cause

Pushed `20260731_230941.jpg` (a real corpus photo, EXIF orientation tag confirmed as exactly
`3` with a plain EXIF reader — `PIL._getexif()[274] == 3`) to the AVD and dumped ML Kit's raw
`RawTextResult` (before any pipeline correction) with a one-off instrumented test (not kept,
mirroring how the 90-degree fixture was captured). Key findings:

- The raw physical pixels, viewed with EXIF ignored (`PIL.Image.open(...).save(...)` — no
  `exif_transpose`), show a book page that must be rotated **90° clockwise** to read upright
  — not 180°. This was confirmed visually (title "RIWAYAT SANTA ANGELA" runs vertically along
  the left edge, reading bottom-to-top).
- Every body line's `angleDeg` in the raw dump sat at **-79° to -84°** (e.g. "Santa Angela
  lahir pada tanggal 21 Maret" measured -79.998°) — consistent with text tilted ~90° in the
  raw frame, not upside-down-but-level text a genuine 180° rotation would produce.
- `FrameRotation`'s own geometry was independently re-derived in Python against this exact
  fixture and found correct: applying the declared 180° rotation reproduces the pipeline's
  measured ~93° residual skew exactly (median signed angle after 180° = +93.38°, matching the
  before-fix corpus measurement of 93.38° for this file). Applying 90° MORE (net 270° total)
  gives median angle ≈ **-175.3°** (upside down); applying 90° LESS (net 90° total) gives
  median angle ≈ **+3.4°** (correct, upright, matches physical inspection).

**Conclusion: `FrameRotation`, `OcrEngine`'s dimension handling, and
`ImagePreprocessor.rotationDegrees` all agree with each other and are all individually
correct.** `ImagePreprocessor.rotationDegrees` reads EXIF tag `3` and correctly maps it to
180 (`ExifInterface.ORIENTATION_ROTATE_180 -> 180`, verified against the exact PIL-reported
tag value). The defect is not in any of the three components named in the brief — it is that
**these 5 source photos' EXIF orientation metadata is itself wrong**: it declares 180° but
the true physical capture rotation is 90°. This is the seventeenth genuine defect found in
this project's real-corpus work, and it is a data defect, not a pipeline logic defect — flagged
per the brief's own framing rather than mis-attributed to working code.

However, the pipeline's behavior when handed a wrong EXIF tag was still a real bug worth
fixing: `SkewEstimator` (built only for a few degrees of genuine camera tilt) was asked to
"correct" a page still tilted ~90° after the declared rotation, which rotates every box about
a pivot sized for the wrong (unswapped) page dimensions — verified corrupting box geometry:
before the fix, one block's box spilled to `right: 1202.4` against a swapped dimension that
should have been in play. This is the exact same box-corruption failure mode the original
90-degree EXIF bug produced, one level further down the pipeline.

### The fix

New `ocr-core/.../pipeline/RotationPlausibilityGuard.kt`. Runs once, immediately after
`FrameRotation` applies the EXIF-declared rotation and before `SkewEstimator` ever sees the
lines:

- Computes the median signed line angle. If it is within
  `StructuringConfig.maxPlausibleSkewDeg` (45°, chosen well below the evidenced failure range
  of 83-93° and well above any plausible genuine camera tilt) of zero, returns the input
  unchanged — a correctly-tagged photo (the other 134 corpus photos) is never touched.
- Otherwise, tries a further +90° and -90° rotation (via `FrameRotation.apply` again, which
  correctly swaps page dimensions) and keeps whichever lands the median **signed** angle
  closest to zero — **not** just the smallest folded tilt. This distinction is load-bearing:
  a plain tilt-magnitude comparison cannot tell "upright" from "upside down" apart, since both
  ±90° candidates fold to the identical ~90° "how far from horizontal" answer; only the full
  signed angle (proven against the real fixture numbers above: +3.4° vs -175.3°) picks the
  direction that keeps text readable instead of flipping it upside down.
- Only applies the correction if the result is demonstrably within `maxPlausibleSkewDeg`;
  otherwise reports the page unchanged rather than guessing further.

`StructuringConfig.maxPlausibleSkewDeg: Float = 45f` — new field, documented with the
evidence above.

### Regression test

`DocumentStructurerTest`: `` `a real EXIF-3 (180-degree-tagged) capture whose true rotation
is 90 degrees deskews to near-zero`() ``. Loads
`ocr-core/src/test/resources/fixtures/real-rotated-230941-raw.json` (42 real `RawLine`
records captured from ML Kit on the real photo, raw 1600×1200 frame), calls
`structure(raw, rotationDegrees = 180)`, and asserts:

- `abs(doc.skewDeg) < 15f` (measured: **3.3843927**, matching the Python-derived prediction).
- `pageWidth`/`pageHeight` come out swapped to 1200×1600 (the true 90°-total rotation's
  correct page shape, not 180°'s unswapped 1600×1200).
- Every block's box lies within the declared page bounds (±5px tolerance, wider than the
  ±1px used for the already-correctly-tagged 90° fixture test, to account for
  `SkewEstimator`'s own small residual-tilt rotation on top of this guard's correction over
  many real lines near the page edge — the same effect already documented on
  `SkewEstimator` itself).
- The real paragraph text ("Santa Angela lahir pada tanggal 21 Maret" ... "Dalam penampakan
  itu, saudarinya menyampaikan") survives intact and in the original top-to-bottom order.

### Measured result

| | before | after |
|---|---|---|
| skewDeg (230941) | 93.38 (from prior report) | **3.3843927** |
| pageWidth×pageHeight | 1600×1200 (unswapped) | **1200×1600** (correctly swapped) |
| block boxes | could spill page bounds (right: 1202.4 observed) | within bounds |
| reading order | correct | correct (unchanged) |

The other two EXIF-3 corpus photos in the original 15-image subset (230958, 231312) were not
re-measured on-device in this task (no new raw fixture captured for them) — the fix is
general (keyed off measured angle plausibility, not per-file), but this is noted rather than
claimed without direct evidence for those two specific files.

## Defect 1 — margin noise injected into real paragraphs

### Real data behind the root cause

Using the same `real-rotated-230941-raw.json` fixture (a curved book page — the same file
the original report's Defect 1 evidence came from), dumped per-line output at each pipeline
stage. The 7 reported junk fragments plus one previously-unreported eighth ("fogpro") all
cluster in the same real x-range:

| text | left | right | width | confidence |
|---|---|---|---|---|
| fogpro | 1108.85 | 1174.26 | 65.4 | 0.275 |
| lseder | 1122.72 | 1187.95 | 65.2 | 0.628 |
| pert | 1128.95 | 1184.62 | 55.7 | 0.389 |
| keadaa | 1130.55 | 1189.74 | 59.2 | 0.633 |
| A | 1171.78 | 1191.86 | 20.1 | 0.691 |
| pandai | 1135.80 | 1192.05 | 56.3 | 0.518 |
| Uga sed | 1139.33 | 1202.40 | 63.1 | 0.364 |
| Ja yar | 1143.22 | 1203.94 | 60.7 | 0.601 |

The rightmost edge any real (non-narrow) body line reaches anywhere on the page is
**1108.22** (line "Persekutuan bagi para agdis..."). Real body-line confidence on this page
ranges 0.76-0.89.

**Why `ColumnSegmenter`'s existing clutter-drop mechanism doesn't catch this**: it merges
line x-intervals to find gutters, and one real body line's right edge (1108.22) sits only
0.63px away from the nearest fragment's left edge (1108.85, "fogpro"). That's inside the
interval-merge's overlap tolerance, so the body run and the fragment run get unioned into one
run before any gutter is ever measured (`runs.size < 2` short-circuits to a single column).
The fragments are outliers past a shared run, not a separate detected run — confirmed by
reproducing `ColumnSegmenter.mergeIntervals` logic against the real coordinates.

**Why width alone, or confidence alone, is not safe** (both signals were tested against the
real fixture and rejected as sole signals):
- Width alone fails: the page's own genuine page-number block (`"[6]"`, width 37.1px, ~2.8
  median character widths) is narrower than 6 of the 8 junk fragments.
- Confidence alone fails: that same `"[6]"` block measured confidence **0.49** — lower than 6
  of the 8 junk fragments (up to 0.69).
- Position (narrow + past the established right margin) is the clean, unambiguous signal:
  every junk fragment's left edge (1108.85-1171.78) sits at or past the real body's maximum
  right edge (1108.22), while the next-highest real line's left edge is 957.20 ("tekun
  berdoa") — a 150px gap separating "real content, however far right" from "these fragments."

### The fix

New `ocr-core/.../pipeline/MarginFragmentFilter.kt`, run once on the deskewed line list,
before `ColumnSegmenter` ever sees it. A line is dropped only when **all three** of these
hold together (each was shown above to fail alone against real data):

1. `box.width < marginFragmentMaxWidthFactor (8.0) * medianCharWidth` — narrow.
2. `box.left - bodyRight >= marginFragmentGapFactor (1.0) * medianCharWidth`, where
   `bodyRight` is the rightmost edge reached by any non-narrow line on the page — isolated
   past the established margin.
3. `confidence < marginFragmentMaxConfidence (0.7)` — low-confidence.

All three thresholds are new `StructuringConfig` fields with KDoc citing the exact
measurements above.

**Deliberately conservative**: "fogpro" (gap of only 0.63px past `bodyRight`, well under the
1.0×medianCharWidth = 13.2px gap threshold) is **not** caught by this rule. Rather than
lower the gap threshold to catch it — which would start risking a false positive against a
real line that legitimately extends slightly past the page's typical margin (e.g. a
justified line's last short word) — it is left alone. Under the project's governing
priority, missing one artefact is a far smaller harm than corrupting one real word.

### Proof the golden fixtures are untouched

Both golden end-to-end tests in `DocumentStructurerTest` pass **unchanged**, with their
original exact-block-count and exact-text assertions intact (no assertion was loosened):

- `` `golden end-to-end fixture - a real photographed worksheet reads in true order with no
  false headings`() `` (`real-worksheet-reading.json`) — PASSED.
- `` `golden end-to-end fixture - background clutter is dropped and numbered questions are
  addressable`() `` (`real-worksheet-exercises.json`) — PASSED.

Since these tests assert exact block text/roles/counts, any line removed by
`MarginFragmentFilter` from either fixture would have failed them. Neither fixture contains a
line that is simultaneously narrow, past its page's established right margin by a full
character width, and below 0.7 confidence, so nothing was removed from either.

### Regression test

`DocumentStructurerTest`: `` `isolated low-confidence margin fragments from a curved book
page are dropped, not injected mid-paragraph`() ``. Loads the same
`real-rotated-230941-raw.json` fixture, runs the full `structure(raw, rotationDegrees = 180)`
pipeline, and asserts:

- None of "lseder", "pert", "keadaa", "A", "pandai", "Uga sed", "Ja yar" survive as a
  block's (trimmed) text.
- The real paragraph text on either side of where these fragments used to be injected is
  still present, intact, in the concatenated block text.

("fogpro" is deliberately excluded from the drop-assertion list, matching the fix's
documented, evidence-based decision not to catch it.)

## Tests added

- `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/RotationPlausibilityGuard.kt`
  (new).
- `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/MarginFragmentFilter.kt` (new).
- `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/StructuringConfig.kt` (changed:
  `maxPlausibleSkewDeg`, `marginFragmentMaxWidthFactor`, `marginFragmentGapFactor`,
  `marginFragmentMaxConfidence`, each with KDoc citing the exact measurements behind it).
- `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurer.kt` (changed:
  wires in `RotationPlausibilityGuard.correct` right after `FrameRotation.apply`, and
  `MarginFragmentFilter.filter` right after `PageStats.from`, both before `ColumnSegmenter`).
- `ocr-core/src/test/resources/fixtures/real-rotated-230941-raw.json` (new fixture: 42 real
  `RawLine` records captured from ML Kit on `20260731_230941.jpg`, raw 1600×1200 un-rotated
  frame — used by both new regression tests).
- `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurerTest.kt` (two
  new tests, listed above). No existing test's assertion was weakened or removed.

`:ocr-core:test` went from 110 to 112 tests (both new tests), 0 failures.

## Full final test output

```
:ocr-core:test              — 112 tests, 0 failures, 0 errors.
:ocr-mlkit:testDebugUnitTest — 14 tests, 0 failures (unchanged).
:app:assembleDebug          — BUILD SUCCESSFUL.
:ocr-mlkit:connectedDebugAndroidTest on AVD Pixel_10_Pro — 5 tests, 0 failures, 2 self-skips
  (unchanged from the prior report's baseline):
    CaptureQualityGateRealPhotosTest.allRealSharpWorksheetPhotosPass
    CaptureQualityGateRealPhotosTest.heavilyBlurredRealPhotoIsReportedAsTooBlurryNotNoTextFound
    OcrAccuracyHarnessTest.measuresCharacterAndWordErrorRateAgainstSampleWorksheets (self-skips)
    OcrBatchStructureHarnessTest.dumpsStructuredOutputForEveryCorpusImage (self-skips)
    OcrEngineInstrumentedTest.recognizesSyntheticWorksheetAndProducesStructuredDocument
```

## Commits

- `10f4652` — `fix(ocr-core): don't let a wrongly-tagged EXIF-180 photo report ~90 degrees of
  skew` (Defect 2: `RotationPlausibilityGuard`, `maxPlausibleSkewDeg`, the rotation
  regression test, and the shared fixture).
- `9f4c2c8` — `fix(ocr-core): drop isolated low-confidence margin fragments, not inject them`
  (Defect 1: `MarginFragmentFilter`, the three margin-fragment config fields, and the
  margin-fragment regression test).

## Known residual gaps

- "fogpro" (Defect 1) is deliberately left uncaught — see above. It is not one of the two
  fragments named in the original brief ("lseder", "pert"), but it is the same class of
  defect on the same page, left unfixed on purpose rather than risking a false positive.
- Only one of the 3 EXIF-3 photos from the original 15-image subset was re-measured
  on-device with a fresh raw fixture for Defect 2 (230941). The other two (230958, 231312)
  were not independently re-verified with new raw captures in this task; the fix is general
  (keyed off measured plausibility, not per-file), but this is flagged rather than assumed.
- `RotationPlausibilityGuard`'s "which quarter turn" resolution can, in principle, be fooled
  by a page whose real content happens to have no clear reading-direction signal in its
  corner-point ordering (the fix relies on the recognizer's own corner-point order
  reflecting reading direction, which was true and verified for the one real 180°-mistagged
  file examined here, but was not independently stress-tested against a synthetic
  counter-example in this task).
