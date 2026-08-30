# Capture quality gate: fix false "too blurry" rejections

## The bug, with numbers

`CaptureQualityGate.evaluateLuma` measured sharpness as the **mean** absolute horizontal
gradient over every pixel in the frame:

```kotlin
sharpness = (sum of |luma[x] - luma[x-1]| over every pixel) / (number of pixels)
```

and rejected as `TooBlurry` when that mean fell below `minSharpness = 4.0`.

That is edge **density**, not edge **sharpness**. A real worksheet photo is mostly flat —
white paper, desk, margins — so hard, perfectly sharp text edges only occupy a small
fraction of pixels, and averaging over the whole frame drags the mean toward zero no matter
how crisp the text itself is.

Reproduced with a synthetic 1600x1200 frame: white background (`luma=250`), with a
192x600 block (6% of the frame) of hard alternating black/white stripes
(`luma=20`/`235`, period 4px) standing in for a sharp line of worksheet text:

| Metric | Value | Old gate (`minSharpness=4.0`) |
|---|---|---|
| meanLuma | 242.65 | — |
| old mean gradient | **3.16** | **3.16 < 4.0 → rejected as `TooBlurry`** |

The image is maximally sharp (every edge in it is a hard 215-level step, no blur applied
at all) and still fails the old gate. This is the exact bug: a sharp, sparse-text worksheet
photo gets rejected before OCR ever runs. It survived the existing test suite because the
one instrumented test (`OcrEngineInstrumentedTest`) deliberately used a tightly cropped
1000x310 canvas with almost no margin, where edge density stays high — the opposite of a
full-frame phone capture.

## The new metric

Sharpness is now the **99th percentile of the absolute-gradient histogram** — how strong the
strongest edges are, not how many of them there are. Gradient magnitudes are bounded to
0..255 (the absolute difference of two lumas), so a 256-bin histogram gives the exact
percentile in one pass, with no sorting and no allocation proportional to frame size.

A sharp image has *some* pixels with a large gradient regardless of how sparse the content
is (glyph edges are still hard edges even if there are few of them). A genuinely blurred
image has none, anywhere, because blur spreads every transition across several pixels and
caps the per-pixel magnitude everywhere in the frame — no fraction of the histogram, however
small, is exempt from that. That is what makes a percentile robust where a mean is not: it
asks "does the sharpest part of this frame look sharp," not "is most of the frame busy."

Implementation (`ocr-mlkit/src/main/kotlin/id/dotcode/braille/ocr/mlkit/CaptureQualityGate.kt`):

```kotlin
class CaptureQualityGate(
    private val minMeanLuma: Int = 35,
    private val sharpnessPercentile: Double = 0.99,
    private val minSharpness: Int = 80,
) {
    data class Evaluation(val reason: FailureReason?, val detail: String)
    // ... 256-bin histogram of |luma[x]-luma[x-1]|, then percentile(histogram, samples, p)
}
```

Same sparse-text frame, new metric:

| Metric | Value | New gate (`minSharpness=80`, p99) |
|---|---|---|
| p99 gradient | **215** | **215 ≥ 80 → passes** |

## How the threshold was chosen

Both axes were checked against the same synthetic sparse-text frame (192x600 block of
text-density stripes in a 1600x1200 frame, 6% of pixels — chosen to match "a few percent" of
a real worksheet photo's text coverage):

- **Sharp**: hard 20/235 stripe edges → p99 gradient = **215**. Comfortably above threshold.
- **Blurred**: the same frame, each edge replaced with a gradual ramp averaged over a
  13-pixel window (±6px, simulating defocus/motion blur spreading a transition across
  several pixels) → p99 gradient = **17**. Comfortably below threshold.

`minSharpness = 80` sits roughly in the middle of that ~17-to-215 gap, closer to the blurred
side than the sharp side — biased toward permissiveness per the module's stated intent
(a false rejection gives the student nothing at all; ML Kit's own `NoTextFound` already
backstops genuinely unreadable input). `sharpnessPercentile = 0.99` was chosen so a text
region covering as little as ~1% of the frame still dominates the tail of the gradient
distribution and gets measured, while JPEG noise/antialiasing on flat regions (which is
real but low-magnitude) can't fake sharpness by sheer pixel count.

I did not attempt to salvage the old metric by tuning `minSharpness` alone: the numbers
above show why that can't work. The old mean (3.16) and the blurred-frame's old mean
(0.75) are both under any threshold that would also accept genuinely sharp dense-text pages
without accepting blurry ones — the metric conflates density and sharpness, and no single
cutoff on a density-weighted average separates "sparse and sharp" from "dense and blurry."
Only changing what's measured (peak vs. mean) fixes it.

## Preprocessing order: gate runs after `createScaledBitmap`, unchanged

`OcrEngine` calls `ImagePreprocessor.downscale()` (bilinear `createScaledBitmap`) before
`qualityGate.evaluate()`. I left this order alone. The percentile metric is robust to it:
bilinear downscaling smooths gradients somewhat, but it does not erase a hard edge — it
still leaves the strongest transitions well above the noise floor. The blurred-vs-sharp gap
above (17 vs. 215) already has enough headroom that ordinary resampling smoothing, which is
far gentler than the 13-pixel blur ramp modeling real defocus, would not close it.
Evaluating after the scale the recognizer actually sees is also more representative of what
ML Kit will be asked to read, so there is no correctness reason to move it before the scale.

## Diagnosis: failure detail now carries the numbers

`CaptureQualityGate.evaluate`/`evaluateLuma` now return an `Evaluation(reason, detail)`
instead of a bare `FailureReason?`. `OcrEngine.recognize` passes `detail` through to
`OcrResult.Failure(reason, detail)` — `FailureReason` (and the Indonesian user-facing
strings derived from it in `:app`) is untouched. Example detail strings:

- `"meanLuma=18.4 is below minMeanLuma=35"` (TooDark)
- `"meanLuma=242.6, p99 gradient=17 (min 80)"` (TooBlurry)
- `"meanLuma=242.6, p99 gradient=215 (min 80)"` (pass)

## Tests added (`ocr-mlkit/src/test/kotlin/id/dotcode/braille/ocr/mlkit/CaptureQualityGateTest.kt`)

Kept the original 4 (updated to read `.reason` off the new `Evaluation` result):
1. `sharp well lit content passes`
2. `a flat mid gray frame is too blurry`
3. `a near black frame is too dark`
4. `darkness is reported before blurriness`

Added 4 new regression tests, all built from a new `sparseTextOnFlat(...)` helper (a large
mostly-flat frame with one small block of sharp stripes) and a `blurHorizontally(...)`
helper (box-averages a ±rampWidth window per pixel, spreading hard edges into gradual
ramps):

5. **`sparse sharp text on a mostly flat worksheet frame passes`** — the exact bug. Computes
   the old mean-gradient formula inline first and asserts it's `< 4.0` (proving the old
   metric really does reject this frame), then asserts the new gate's `.reason` is `null`.
6. **`genuinely blurred sparse text is still rejected`** — same frame, edges blurred via a
   13px-window box average; asserts `TooBlurry`.
7. **`a fully flat frame with no edges anywhere is too blurry`** — 1600x1200 solid fill,
   asserts `TooBlurry` (p99 gradient is exactly 0).
8. **`sparse sharp text on a dim but legible background passes`** — background luma 40,
   stripe values 10/130 (meanLuma≈41.8, safely above `minMeanLuma=35`); asserts the
   darkness check doesn't double-reject a dim-but-sharp classroom photo (p99 gradient=120).

## Full test output

`:ocr-mlkit:testDebugUnitTest` — `CaptureQualityGateTest`, 8/8 passed:

```
CaptureQualityGateTest > a near black frame is too dark() PASSED
CaptureQualityGateTest > sparse sharp text on a dim but legible background passes() PASSED
CaptureQualityGateTest > darkness is reported before blurriness() PASSED
CaptureQualityGateTest > genuinely blurred sparse text is still rejected() PASSED
CaptureQualityGateTest > a fully flat frame with no edges anywhere is too blurry() PASSED
CaptureQualityGateTest > sharp well lit content passes() PASSED
CaptureQualityGateTest > a flat mid gray frame is too blurry() PASSED
CaptureQualityGateTest > sparse sharp text on a mostly flat worksheet frame passes() PASSED
```

`ImagePreprocessorTest`, 4/4 passed (unchanged). Total `:ocr-mlkit:testDebugUnitTest`: 12/12,
BUILD SUCCESSFUL.

`:ocr-core:test`: 79/79 passed, unchanged (module was not touched — zero Android
dependencies preserved).

`:app:assembleDebug`: BUILD SUCCESSFUL.

`:ocr-mlkit:connectedDebugAndroidTest` on AVD `Pixel_10_Pro` (booted with
`emulator -avd Pixel_10_Pro -no-window -no-audio`, API level shown as "17" by the AVD's
device profile name):

```
id.dotcode.braille.ocr.mlkit.OcrAccuracyHarnessTest > measuresCharacterAndWordErrorRateAgainstSampleWorksheets SKIPPED
id.dotcode.braille.ocr.mlkit.OcrEngineInstrumentedTest > recognizesSyntheticWorksheetAndProducesStructuredDocument PASSED
Pixel_10_Pro(AVD) - 17 Tests 2/2 completed. (1 skipped) (0 failed)
BUILD SUCCESSFUL
```

The accuracy-harness test's skip is pre-existing and unrelated to this change (it skips
when no sample worksheet corpus is present on disk). The end-to-end recognition test passed
against a real on-device ML Kit run.

## Residual risk

- **False accepts**: the gate now only catches catastrophic captures (lens cap, heavy
  motion blur, dark rooms), by design. A capture with moderate blur that still clears
  `minSharpness=80` at its sharpest 1% will be handed to ML Kit; if ML Kit itself can't read
  it, `NoTextFound` is the backstop, but a *misread* on a marginal image is not something
  this gate can catch — it never was, since it can't know ground truth. This is an accepted
  trade-off per the task's explicit bias-toward-permissiveness direction.
- **False rejects**: a worksheet with genuinely very small/thin print, heavy JPEG
  recompression, or extreme anti-aliasing could in principle have all its edges softened
  below `minSharpness=80` even when "readable" by a human. I don't have a real-device photo
  corpus to validate this against (same gap the original bug report identifies for the old
  metric); the `docs/.../real-worksheet` test fixtures did not include one. If real captures
  keep getting rejected, the next step is collecting failing photos and reading their
  `detail` string (now included in `OcrResult.Failure`) rather than guessing at another
  threshold change.
- **Percentile granularity at very small frames**: for a tiny image (e.g. the 64x64 unit
  test fixtures), the 99th percentile of a few thousand samples is coarse — this is fine for
  the unit tests (deliberately either uniformly sharp or uniformly flat) but not
  representative of production frames, which are always ≥1600px on the long edge after
  `ImagePreprocessor.downscale`.
