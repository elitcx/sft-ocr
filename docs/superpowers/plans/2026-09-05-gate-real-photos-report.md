# Capture quality gate: fixing the metric against real photos (third attempt)

## Root cause

`CaptureQualityGate` measures sharpness as a percentile of gradient magnitude computed
over "edge" pixels (those clearing `edgeGradientFloor = 10`). The second attempt at this
gate used the **median** (p50) of that edge-pixel population, reasoned as: restricting to
edge pixels already isolates the text-edge population, so the score should describe that
population's typical strength.

That reasoning held only on this gate's own synthetic fixtures, which have clean binary
edges - nearly every pixel that clears the floor really is a glyph edge, so the median and
a high percentile land in the same place (~215 sharp, ~17 blurred).

It broke on real photographs. Replicating the gate's exact pipeline (1600px bilinear
downscale, luma weights, horizontal gradient) in Python against ten real worksheet photos
supplied by the project owner (nine unique files - one, `IMG-20260821-WA0035.jpg`, was
present twice as a byte-identical duplicate download) showed that **7-19% of all gradient
samples** clear the floor of 10, not because the pages are unusually busy but because JPEG
compression artifacts and paper/lighting texture put a real camera's noise floor well above
a synthetic image's exact-zero flat regions. The overwhelming majority of that population is
weak noise, not glyph edges - real glyph edges are a small, high-magnitude minority. The
median therefore measured the noise floor, landing at 25-40, comfortably under the old
`minSharpness = 80` - so every one of the ten "obviously legible to a human" photos was
rejected as too blurry.

This is the same failure shape as the first attempt (a whole-frame percentile that measured
text *density* instead of text *sharpness*): a statistic whose value depends on the
composition of the pixel population being measured, rather than on the actual sharpness
signal within it.

## The fix

- `sharpnessPercentile`: `0.5` (median) -> `0.95`.
- `minSharpness`: `80` -> `40`.

A high percentile targets the population's strong tail - real glyph edges, the most
step-like transitions in the frame - rather than its noisy bulk, so it is largely immune to
how much of the edge-pixel population is JPEG/texture noise.

### Measured before/after, per real photo (p50 -> p95, at the exact same floor=10)

| file | meanLuma | edge fraction | p50 (old) | p95 (new) | old verdict (min 80) | new verdict (min 40) |
|---|---|---|---|---|---|---|
| IMG-20260819-WA0002 | 160.0 | 19.16% | 25 | 71 | rejected | **pass** |
| IMG-20260819-WA0006 | 120.9 | 7.11% | 34 | 101 | rejected | **pass** |
| IMG-20260820-WA0000 | 146.2 | 11.76% | 28 | 82 | rejected | **pass** |
| IMG-20260820-WA0001 | 146.3 | 11.80% | 30 | 79 | rejected | **pass** |
| IMG-20260820-WA0002 | 138.4 | 14.82% | 31 | 70 | rejected | **pass** |
| IMG-20260821-WA0033 | 172.0 | 19.23% | 40 | 116 | rejected | **pass** |
| IMG-20260821-WA0034 | 174.3 | 8.83% | 32 | 84 | rejected | **pass** |
| IMG-20260821-WA0035 | 179.1 | 9.86% | 27 | 95 | rejected | **pass** |
| IMG-20260821-WA0036 | 179.2 | 14.29% | 30 | 81 | rejected | **pass** |

Box-blurred versions of the same nine photos (radius 4, simulating genuine motion
blur/defocus) scored p95 = 14-22 - never close to 40 - confirming the margin holds in both
directions. A synthetic density sweep (2%-100% text coverage) held sharp at 64-88 and
blurred at 11.5-15.

## The interaction bug found while checking the ordering

The brief asked to check whether `minEdgeFraction` (checked before sharpness) could make a
heavily blurred page return `NoTextFound` instead of `TooBlurry`. It does, if left
unchanged: pushing the same real photo through increasing box-blur radius showed the
`edgeGradientFloor`-based edge fraction hit **exactly 0.0** by radius 8 (out of 30 tested),
because heavy blur spreads every transition so far that its per-step magnitude falls
entirely below the sharpness floor of 10 - even though the frame plainly still has real
content on it, just badly out of focus.

**Fix:** split the single gradient floor into two. `edgeGradientFloor` (10) is now used only
for the sharpness score. A new, much lower `contentGradientFloor` (1) is used only for the
"does any content exist at all" check that gates `NoTextFound`. Measured on the same photo,
the `contentGradientFloor`-based fraction stayed at 1.6% even at blur radius 30, because even
heavily-blurred real content retains far more pixel-to-pixel variation than a genuinely flat
frame (a lens cap or blank sheet has *exactly* zero gradient at any floor). This is verified
by `heavilyBlurredRealPhotoIsReportedAsTooBlurryNotNoTextFound`.

## Tests added

- `braille-ocr/ocr-mlkit/src/androidTest/assets/quality/` - the nine real worksheet photos
  (as instrumented-test assets; the duplicate WA0035 download was not duplicated into assets).
- `braille-ocr/ocr-mlkit/src/androidTest/kotlin/id/dotcode/braille/ocr/mlkit/CaptureQualityGateRealPhotosTest.kt`:
  - `allRealSharpWorksheetPhotosPass` - loads and downscales every real photo exactly as
    `ImagePreprocessor` does, runs `CaptureQualityGate.evaluate`, and asserts none fail,
    reporting filename + detail string on any failure. This is the test that would have
    caught all three prior regressions.
  - `heavilyBlurredRealPhotoIsReportedAsTooBlurryNotNoTextFound` - applies a box blur
    (radius 10, implemented in the test, separable horizontal+vertical) to one real photo and
    asserts `TooBlurry`, not `NoTextFound`.
- `CaptureQualityGateTest.kt`: updated the boundary-pinning test
  (`sharpness decision flips exactly at the documented threshold`) from G=80/79 to G=40/39,
  and adjusted its low/high luma values (20/60, 20/59) so mean luma still clears
  `minMeanLuma=35` at the new step size - this was a necessary side-effect of moving the
  threshold, not a weakening of the assertion; it still pins an exact one-level boundary.
  No other existing test's expectation changed.

## Test output

`:ocr-core:test` - unaffected, not modified (constraint honored). Passed, up-to-date.

`:ocr-mlkit:testDebugUnitTest` - 15 tests, 0 failures (14 unit tests + updated boundary test).

`:app:assembleDebug` - BUILD SUCCESSFUL.

`:ocr-mlkit:connectedDebugAndroidTest` on AVD `Pixel_10_Pro` (booted for this run):

```
<testsuite name="id.dotcode.braille.ocr.mlkit.CaptureQualityGateRealPhotosTest" tests="2" failures="0" errors="0" skipped="0" time="9.071">
  <testcase name="allRealSharpWorksheetPhotosPass" time="6.272" />
  <testcase name="heavilyBlurredRealPhotoIsReportedAsTooBlurryNotNoTextFound" time="0.842" />
</testsuite>
```

Full connected suite (5 tests, including the pre-existing `OcrEngineInstrumentedTest` and the
`OcrAccuracyHarnessTest` which self-skips without externally pushed samples): BUILD
SUCCESSFUL, 0 failures.

All ten (nine unique) real photos genuinely passed the gate on-device.
