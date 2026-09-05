# Real-corpus measurement and fix report — 2026-09-05

## Corpus and subset

139 real photos in `C:/Users/Kiel/Downloads/braille testing/`. EXIF orientation across the
corpus: 123 tagged `6` (rotate 90° CW), 5 tagged `3` (rotate 180°), 11 untagged (all
`IMG-*-WA*.jpg` — WhatsApp strips EXIF).

15-image subset pushed through the real pipeline on-device (AVD `Pixel_10_Pro`), chosen to
span every category named in the brief:

| file | EXIF orient | category | why picked |
|---|---|---|---|
| 20260731_231023.jpg | 6 | book page, rotated | explicit example from the brief |
| 20260731_230837.jpg | 6 | book page, rotated | |
| 20260731_230917.jpg | 6 | book page, rotated | |
| 20260731_231038.jpg | 6 | book page, rotated | |
| 20260731_231102.jpg | 6 | book page, rotated | |
| 20260731_231330.jpg | 6 | book page, rotated | 2nd-largest file — high quality-gate score proxy |
| 20260731_232147.jpg | 6 | book page, rotated | largest file in the corpus — high quality-gate score proxy |
| 20260731_230941.jpg | 3 | book page, 180° tag | |
| 20260731_230958.jpg | 3 | book page, 180° tag | |
| 20260731_231312.jpg | 3 | book page, 180° tag | |
| IMG-20260819-WA0002.jpg | untagged | WhatsApp worksheet | |
| IMG-20260820-WA0000.jpg | untagged | WhatsApp worksheet | |
| IMG-20260821-WA0033.jpg | untagged | WhatsApp worksheet | |
| IMG-20260819-WA0006.jpg | untagged | WhatsApp worksheet | smallest file in the corpus — low quality-gate score proxy |
| IMG-20260825-WA0008.jpg | untagged | WhatsApp worksheet | |

(Exact `CaptureQualityGate` scores weren't re-derived for the full 139 — the brief already
validated the gate on the whole corpus, 52–116 vs. threshold 40 — so "highest/lowest" here
is a file-size proxy, not the gate's own score. This is an approximation, noted for
transparency, not the gate itself, which was out of scope for this task.)

## Harness built

`braille-ocr/ocr-mlkit/src/androidTest/kotlin/id/dotcode/braille/ocr/mlkit/OcrBatchStructureHarnessTest.kt`
— the structural sibling of `OcrAccuracyHarnessTest`. Points at a device directory, runs the
real `OcrEngine` on every image in it, and writes each image's `OcrDocument.toJson()` (or a
`.failure.txt` with the reason) next to the source file. No ground truth is required, unlike
the CER/WER harness — this is for reading raw structural output, not scoring it. Same
`appops`/scoped-storage caveats as `OcrAccuracyHarnessTest`; see its KDoc for the exact
commands. It is part of the connected test suite (self-skips with no corpus pushed).

## Step 1 — measurement (BEFORE any fix)

All 15 images succeeded (no `NoTextFound`/`TooBlurry`/`ModelUnavailable` failures).

| file | pageWidth×pageHeight | skewDeg | columnCount | blocks | markers | notable |
|---|---|---|---|---|---|---|
| 230837 (6) | 1200×1600 | **-89.11** | 1 | — | — | bogus ~90° skew |
| 230917 (6) | 1200×1600 | **-90.92** | 1 | — | — | bogus ~90° skew |
| 231023 (6) | 1200×1600 | **-87.65** | 1 | 6 | 3 (3./4./5.) | bogus ~90° skew; text/order correct |
| 231038 (6) | 1200×1600 | **-83.26** | 2 | — | — | bogus ~90° skew |
| 231102 (6) | 1200×1600 | **-91.27** | 1 | — | — | bogus ~90° skew |
| 231330 (6) | 1200×1600 | **-89.47** | 1 | — | — | bogus ~90° skew |
| 232147 (6) | 1200×1600 | **-90.0** | 4 | 75 | 0 | bogus skew; also a genuinely chaotic multi-column awards board, see below |
| 230941 (3) | 1600×1200 | **-86.59** | 1 | 25 | 0 | bogus skew; text/order correct; curved-page fragments (see below) |
| 230958 (3) | 1600×1200 | **-87.70** | 1 | — | — | bogus skew |
| 231312 (3) | 1600×1200 | **-90.0** | 1 | — | — | bogus skew |
| WA0002 (untagged) | 1235×1280 | -1.35 | 1 | 8 | 1 | plausible |
| WA0000 (untagged) | 1200×1600 | 0.0 | 1 | 46 | 5 | plausible |
| WA0033 (untagged) | 1600×1533 | -1.54 | 1 | 8 | 1 | plausible |
| WA0006 (untagged) | 960×1280 | -1.90 | 1 | 22 | 10 | plausible |
| WA0008 (untagged) | 1200×1600 | 0.0 | 1 | 28 | 14 | plausible |

**Every one of the 10 EXIF-tagged (rotated) photos measured -83° to -91° of "skew" — a
physically implausible value for a real photograph a human would call straight. Every one of
the 5 untagged (already-upright) photos measured -1.9° to 0°, exactly as expected.** This
correlation, present in 10/10 vs. 0/5, is the headline finding: not a per-image glitch but a
systemic defect in how rotated captures are handled.

Despite the bogus `skewDeg`, **reading order and text content were correct** for every
rotated image actually read in full (231023, 230941 — see below): numbered items in order,
paragraphs in order, no scrambling. The bug was silently corrupting `skewDeg` and (as shown
below) block box bounds, not reading order — which is exactly the kind of defect that's easy
to miss without measuring real captures, since the visible symptom (garbage text order) never
appeared.

### Two further findings from reading the JSON in full

1. **Curved-page fragment injection** (230941.json, a book page with visible page curvature
   at the binding). Between real paragraph lines, the pipeline injects extra
   single-line, right-aligned, low-confidence PARAGRAPH blocks: `"lseder"`, `"pert"`,
   `"keadaa"`, `"A"`, `"pandai"`, `"Uga sed"`, `"Ja yar"` — apparent OCR noise from the
   curved/warped right margin, positioned to the right of the preceding real line's end.
   These are FOUND, NOT FIXED (see below).

2. **20260731_232147.jpg is not a worksheet** — it's a photo of a school achievement/awards
   bulletin board with a repeating certificate-logo pattern across many rows
   ("...Fazzio Youth Festival Dance Competition..." repeated dozens of times) and a dense
   4-column name/class/rank table. The pipeline produced 75 blocks with heavy text
   duplication/garbling ("Jniversitas" for "Universitas", multi-line runs concatenated into
   one block) — this is a genuinely hard, arguably out-of-scope input for a worksheet/book
   OCR pipeline. FOUND, NOT FIXED — flagged as likely out of this task's scope rather than
   attempted with a speculative fix.

## Step 2 — root cause and fix

### The bug: raw ML Kit coordinates and declared page dimensions were in different frames

`ImagePreprocessor` deliberately does NOT bake EXIF rotation into pixels — it hands
`rotationDegrees` to ML Kit's `InputImage.fromBitmap` instead (avoids a full-frame copy).
`OcrEngine` then computed the EXIF-swapped page dimensions (e.g. 1200×1600 for a 90°
capture) and passed THOSE to `MlKitAdapter.toRawTextResult` as `imageWidth`/`imageHeight`.

But ML Kit's `Text.Line.cornerPoints` are reported relative to the bitmap it actually
decoded — the ORIGINAL, un-rotated frame (e.g. 1600×1200 landscape) — not the upright frame
implied by the `rotationDegrees` hint. Captured proof, from a real recognizer run on
`20260731_231023.jpg` with the bug still present: line `"3. Sebagai Sekolah Katolik..."`
had `cornerPoints` spanning x:499–544, y:270–820 (a tall, narrow, near-vertical shape — the
true footprint of a horizontal line of text on a page that's sideways on the sensor) while
`imageWidth`/`imageHeight` passed alongside it were already swapped to 1200×1600. Rotating
those corner points by the exact 90° EXIF correction about the CORRECT raw-frame pivot
(800, 600) reproduces ML Kit's own `boundingBox` field exactly (380, 499, 930, 544) — proof
the fix's geometry is right and that `boundingBox` (unlike `cornerPoints`) already reports in
the rotated/upright frame, a real inconsistency between ML Kit's two coordinate fields for a
tilted quad.

`SkewEstimator.deskew()` — designed for a few degrees of genuine camera tilt — received
lines whose `angleDeg` (ML Kit's own value, derived from the mismatched raw-frame corners)
sat near ±90°, and "corrected" them by rotating about a pivot built from the WRONG (swapped)
page dimensions. This explains both measured symptoms: the bogus ~90° `skewDeg`, and block
boxes spilling outside the declared page bounds (e.g. one block's `right: 1391.2` against a
declared `pageWidth: 1200` — should be impossible).

### The fix

Discrete EXIF rotation (an exact multiple of 90°, known in advance) and continuous skew
correction (unknown until measured, typically a couple of degrees) were conflated into one
mechanism. They're now separated:

- **New**: `ocr-core/.../pipeline/FrameRotation.kt`. A pure function taking a `RawTextResult`
  whose `imageWidth`/`imageHeight` and every coordinate are all in the SAME (raw, un-rotated)
  frame, plus the EXIF `rotationDegrees`. It rotates every line's corner points (falling back
  to the axis-aligned box only when a provider supplies no corner points) about the CORRECT
  raw-frame pivot, swaps width/height for a quarter turn, and re-derives each line's
  `angleDeg` from the rotated geometry itself — not by adjusting the provider's original
  value — so it's correct regardless of the provider's own angle sign convention.
- **Changed**: `DocumentStructurer.structure()` now takes an explicit
  `rotationDegrees: Int = 0` and runs `FrameRotation.apply` as the very first step, before
  `SkewEstimator` ever sees the lines. `SkewEstimator` itself is untouched — it now only ever
  measures genuine residual tilt.
- **Changed**: `OcrEngine` (ocr-mlkit) now passes the RAW `scaled.width`/`scaled.height` to
  `MlKitAdapter.toRawTextResult` (matching the frame ML Kit's coordinates are actually in)
  and passes `rotationDegrees` through to `structurer.structure(...)` instead of
  pre-swapping dimensions itself. The prior swap-dimensions-at-the-call-site fix (visible in
  the removed code comment) was based on a false premise about ML Kit's coordinate contract;
  this is the sixteenth defect found in this project, per the brief's framing.

### Regression test

`RawTextResult` gained `toJson()`/`fromJson()` (mirroring `OcrDocument`'s existing pattern) so
a real recognizer capture can be replayed on the JVM. A one-off instrumented test (not kept —
pure capture utility) ran ML Kit directly against `20260731_231023.jpg` and dumped the RAW
(un-rotated, 1600×1200) `RawTextResult` — 116 real lines — to
`ocr-core/src/test/resources/fixtures/real-rotated-231023-raw.json`.

New test in `DocumentStructurerTest.kt`:
`a real 90-degree EXIF capture deskews to near-zero, not 90, when rotationDegrees is supplied`
— loads that fixture, calls `structure(raw, rotationDegrees = 90)`, and asserts: page swaps to
1200×1600, `abs(skewDeg) < 10`, every block box lies within the declared page bounds, and
reading order is still items 3/4/5 in order with the right marker and opening text each.

### Measured after the fix, on-device, same 15 images

| file | skewDeg before | skewDeg after |
|---|---|---|
| 230837 (6) | -89.11 | **0.79** |
| 230917 (6) | -90.92 | **-1.58** |
| 231023 (6) | -87.65 | **2.19** |
| 231038 (6) | -83.26 | **6.70** |
| 231102 (6) | -91.27 | **-1.34** |
| 231330 (6) | -89.47 | **0.0** |
| 232147 (6) | -90.0 | **0.0** |
| 230941 (3) | -86.59 | 93.38 (unchanged category — see below) |
| 230958 (3) | -87.70 | 91.89 (unchanged category — see below) |
| 231312 (3) | -90.0 | 90.0 (unchanged category — see below) |

All 7 EXIF-6 (90°) images in the subset went from a physically-impossible ~90° skew to a
plausible small value (0°–6.7°). `pageWidth`/`pageHeight` are correctly swapped, and every
block box now lies within the declared page bounds (spot-checked; asserted in the new test).
Reading order for 231023 (the only one read line-by-line both before and after) was already
correct and remains correct.

### Found, NOT fixed: EXIF-3 (180°) images still report ~90° skew

The 3 EXIF-3 images in the subset still measure 90°–93° skew after the fix, unlike the EXIF-6
images. This is a DIFFERENT defect from the one just fixed: for a 180° rotation,
`quarterTurn` is `false`, so the OLD code never swapped dimensions for these files — the raw
ML Kit frame and the declared page frame already matched even before this fix, so the
imageWidth/imageHeight mismatch this task fixed was never the cause here. Text content and
reading order are unaffected (verified byte-for-byte identical on 230941.json before vs.
after this fix — the paragraph sequence about Santa Angela's biography reads correctly both
times), so this is a cosmetic/metadata defect, not a reading-order one, and lower priority
under the brief's own stated ordering (text/order first, structural fields second).

Root cause not isolated within this task's budget. Confirming it requires capturing a RAW
(pre-structuring) fixture for one of these 180°-tagged files the same way the 90° fixture was
captured, and inspecting whether ML Kit's per-line `angle`/`cornerPoints` for a genuinely
180°-rotated capture behave the way this fix assumes (rotating flush by exactly `rotationDegrees`
about the raw-frame center) or whether these 5 files' EXIF tag itself is simply wrong for the
physical rotation applied. Flagged rather than guessed at with an unverified fix, per the
brief's explicit instruction to report rather than attempt a risky partial fix.

### Found, NOT fixed: curved-page margin fragments injected into paragraph flow

On `20260731_230941.jpg` (a book page with visible curvature near the binding), short,
low-confidence, right-aligned fragments — `"lseder"`, `"pert"`, `"keadaa"`, `"A"`, `"pandai"`,
`"Uga sed"`, `"Ja yar"` — are emitted as their own PARAGRAPH blocks interleaved between the
real paragraph's lines, positioned just past the end of the preceding real line. These are
almost certainly OCR noise from the warped/curved right margin. `ColumnSegmenter`'s existing
clutter-drop mechanism does not apply: it only fires when a whole detected COLUMN's line
share falls below `minColumnLineShareFraction`, and this page never splits into multiple
columns (`columnCount == 1`) — the fragments sit inside the single detected column's bounds,
not outside it.

This was not fixed because a safe fix requires distinguishing "genuine short line" (e.g. the
real `"Indonesia)"` continuation line on the same page, or a real one-word answer) from
"margin noise fragment" using a general, testable rule, and this task's remaining budget did
not allow deriving and validating such a rule against enough real examples to be confident it
would not create a false-negative regression (dropping or mis-merging a real short line)
elsewhere in the 139-image corpus. Flagged with concrete evidence rather than attempted.

## Tests added

- `braille-ocr/ocr-mlkit/src/androidTest/kotlin/id/dotcode/braille/ocr/mlkit/OcrBatchStructureHarnessTest.kt`
  (new, permanent infrastructure).
- `braille-ocr/ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/FrameRotation.kt` (new).
- `braille-ocr/ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurer.kt`
  (changed: new `rotationDegrees` parameter, applies `FrameRotation` first).
- `braille-ocr/ocr-core/src/main/kotlin/id/dotcode/braille/ocr/raw/RawTextResult.kt` (changed:
  added `toJson()`/`fromJson()`, mirroring `OcrDocument`).
- `braille-ocr/ocr-mlkit/src/main/kotlin/id/dotcode/braille/ocr/mlkit/OcrEngine.kt` (changed:
  passes raw dimensions + `rotationDegrees` instead of pre-swapped dimensions).
- `braille-ocr/ocr-core/src/test/resources/fixtures/real-rotated-231023-raw.json` (new fixture:
  116 real `RawLine` records captured from ML Kit on a real 90°-rotated photograph, in the raw
  un-rotated frame).
- `braille-ocr/ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurerTest.kt`
  (new test: `a real 90-degree EXIF capture deskews to near-zero, not 90, when rotationDegrees
  is supplied`).

No existing test's assertion was weakened. `ocr-core:test` went from 109 to 110 tests (the
one new test), 0 failures.

## Full final test output

`:ocr-core:test` — 110 tests, 0 failures, 0 errors.

`:ocr-mlkit:testDebugUnitTest` — 14 tests, 0 failures (unchanged).

`:app:assembleDebug` — BUILD SUCCESSFUL.

`:ocr-mlkit:connectedDebugAndroidTest` on AVD `Pixel_10_Pro` — 5 tests, 0 failures:

```
CaptureQualityGateRealPhotosTest.allRealSharpWorksheetPhotosPass
CaptureQualityGateRealPhotosTest.heavilyBlurredRealPhotoIsReportedAsTooBlurryNotNoTextFound
OcrAccuracyHarnessTest.measuresCharacterAndWordErrorRateAgainstSampleWorksheets (self-skips, no ground-truth samples pushed)
OcrBatchStructureHarnessTest.dumpsStructuredOutputForEveryCorpusImage
OcrEngineInstrumentedTest.recognizesSyntheticWorksheetAndProducesStructuredDocument
```

## Reading-order correctness, before vs. after, over the subset

Of the 15 images, full block-by-block reading order was read and checked by a human against
the visible text for 2 (231023, 230941) before any fix — both were already correct. The fix
did not change reading order for either (it corrects `skewDeg` and box bounds, which were
wrong, without changing which text ends up in which block or order). For the remaining 13,
`columnCount`/marker/role counts were checked as a proxy (all plausible) but full order was
not hand-verified against the source photo in this pass, given the time budget — this is
noted rather than glossed over.
