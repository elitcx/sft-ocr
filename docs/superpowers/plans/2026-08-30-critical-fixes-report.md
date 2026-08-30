# Critical fixes report — 2026-08-30

Two Critical defects from the BRaiLLE OCR MVP review, both capable of silently corrupting
output for a student who cannot see the page to catch the error. Both are fixed, tested, and
committed on `feat/ocr-mvp`.

- Commit 1 (RowFragmentJoiner): `11680f4`
- Commit 2 (CaptureQualityGate): `9d030ce`

---

## CRITICAL 1 — `RowFragmentJoiner` could silently reverse text within a line

**File:** `braille-ocr/ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/RowFragmentJoiner.kt`

### The bug

`ReadingOrderSorter` sorts lines by `(columnIndex, floor(centerY / bandHeight), left)` — band
before left. When a crease clips one fragment's vertical extent, its `centerY` shifts enough
that it lands in a different band from its row-mate, even though the two fragments still
overlap vertically enough to pass `RowFragmentJoiner.sameRow`'s overlap check. In that case the
sort order is decided entirely by band, ignoring `left`, so the physically-right fragment can
sort before the physically-left one. `joinLines` previously concatenated `"${a.text} ${b.text}"`
in whatever order the list gave it — i.e. `"<right> <left>"`: reversed text inside a single
line, with nothing downstream able to detect it.

### Before / after, with concrete numbers

Fixture: `left` fragment at `(x=100, y=302, w=150, h=28)` (text "Concerns about financialc"),
`right` fragment at `(x=400, y=295, w=200, h=17)` (text "cost and food waste").

- `medianLineHeight = median(28, 17) = 22.5`, `bandHeight = 22.5 * 0.7 = 15.75`
- `left.centerY = 316` → `floor(316 / 15.75) = 20`
- `right.centerY = 303.5` → `floor(303.5 / 15.75) = 19`
- Different bands (19 < 20) → `ReadingOrderSorter` emits `[right, left]`, even though `right`
  sits at x=400 and `left` sits at x=100.
- Vertical overlap = `min(330,312) - max(302,295) = 10`, `smallerHeight * 0.5 = 8.5` → passes
  `sameRow`'s overlap check, so the join fires.

**Before:** `joinLines` emitted `"cost and food waste Concerns about financialc"` — reversed.
**After:** `joinLines` emitted `"Concerns about financialc cost and food waste"` — correct.

### The fix

`joinLines` now sorts its two inputs by `box.left` before concatenating, instead of trusting
caller order:

```kotlin
private fun joinLines(first: RawLine, second: RawLine): RawLine {
    val a = if (first.box.left <= second.box.left) first else second
    val b = if (a === first) second else first
    ...
    return a.copy(text = "${a.text} ${b.text}", box = a.box.union(b.box), ...)
}
```

`box.union` is order-independent, so the resulting box was already correct either way; only
the text order was wrong. Sorting inside `joinLines` (rather than requiring `sameRow` to enforce
`a.left <= b.left`) means the fix is local to the one place that assembles the joined text, and
it does not depend on which of the two arguments the caller happens to pass first.

### Does this fix the "silent non-join" variant too?

**No.** The same band-straddling mechanism can also make `sameRow`'s overlap/gap check fail
when it should have passed (or vice versa), because the check still runs on `a`/`b` in caller
order and uses `a.line.box.right`/`b.line.box.left` for the gap calculation, which is
order-sensitive. That failure mode degrades safely — it produces two separate blocks instead of
one joined line, not corrupted text — so per the instructions it is left for a follow-up rather
than fixed here.

### Tests added

`braille-ocr/ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/RowFragmentJoinerTest.kt`:

- `fragments straddling a band boundary join in left-to-right reading order` — reproduces the
  exact scenario above. First asserts the *premise* (that `ReadingOrderSorter` really does put
  the right fragment first — `["cost and food waste", "Concerns about financialc"]`), then
  asserts the joined text reads left-to-right, and asserts the union box spans `[100, 600]`.

**Confirmed failing before the fix:** ran the new test against the pre-fix code —
`RowFragmentJoinerTest > fragments straddling a band boundary join in left-to-right reading
order() FAILED` at the joined-text assertion (the premise assertion passed, confirming the
sorter really does misorder the fragments; the join then emitted the reversed string).

Existing `RowFragmentJoinerTest` cases (crease join, left/right column non-join) still pass
unchanged.

---

## CRITICAL 2 — the quality gate's sharpness metric still depended on text density

**File:** `braille-ocr/ocr-mlkit/src/main/kotlin/id/dotcode/braille/ocr/mlkit/CaptureQualityGate.kt`

### The bug

The prior fix (commits `8e73d21`/`2c75e80`, already on this branch before this session) replaced
a mean-gradient-over-all-pixels metric (which measured edge *density* and rejected sharp photos
of mostly-white worksheets) with the 99th percentile of the gradient histogram — but that
percentile was still taken over **all** pixels. It only reflects the text once high-gradient
pixels exceed roughly `1 - 0.99 = 1%` of the frame. The passing fixture in the existing test sat
at ~6% coverage (well clear of the cliff), which is why it passed — but a genuinely sharp photo
of a sparse worksheet (a title and a few short questions with wide margins — a completely normal
page) can fall under 1% text coverage and would be falsely rejected as `TooBlurry` again.

### The fix — density-independent metric

Separated "how much of the frame is text" (irrelevant to sharpness) from "how strong are the
edges that exist" (the actual signal):

1. A pixel counts as an **edge pixel** when its absolute horizontal gradient exceeds
   `edgeGradientFloor = 10` — a noise floor above camera-sensor/JPEG noise on flat regions, and
   well below the ~18 a blurred glyph edge scores (see below), so it doesn't itself risk
   misclassifying blur as "no edges".
2. If the fraction of all gradient samples that are edge pixels is below
   `minEdgeFraction = 0.0001` (0.01%), the verdict is `FailureReason.NoTextFound`, not
   `TooBlurry` — a blank sheet or lens-cap shot has ~zero edge pixels, and "hold the phone
   steadier" is misleading advice for it.
3. Otherwise, the sharpness score is the **median** (`sharpnessPercentile = 0.5`) of gradient
   magnitude **computed only over edge pixels**. Restricting to edge pixels already isolates the
   text-edge population, so the median describes its typical strength; a high percentile (the
   old choice) would just chase the tail and be needlessly sensitive to a stray strong pixel.
4. Pass/fail against `minSharpness = 80`, unchanged threshold value, re-justified below.

### Numbers (from an offline simulation of the exact algorithm, then confirmed by the Kotlin
test suite)

Synthetic sharp text: alternating stripes, period 4px, `low=20, high=235` (hard step,
magnitude 215 at each transition). Blurred: same stripes through a 13px (`rampWidth=6`) box
blur.

| text coverage | sharp median edge-gradient | blurred median edge-gradient |
|---|---|---|
| 0.2% | 215 | 17 |
| 0.5% | 215 | 17 |
| 1.0% | 215 | 17 |
| 5.0% | 215 | 17 |

The score is **coverage-independent** in both the sharp and blurred cases — exactly the property
the old metric lacked. `minSharpness = 80` sits centrally in the wide gap between 17 and 215,
biased toward permissiveness as the class KDoc requires.

Edge-pixel fraction at the smallest tested coverage (0.2%, sharp): ~0.050% — five times above
`minEdgeFraction = 0.0001` (0.01%), so genuine sparse content is never mistaken for a blank
page. A fully flat frame measures exactly 0 edge pixels, always below the threshold.

### Tests added / changed

`braille-ocr/ocr-mlkit/src/test/kotlin/id/dotcode/braille/ocr/mlkit/CaptureQualityGateTest.kt`:

- **Added** `sharp text passes and blurred text is rejected across a coverage sweep` — sweeps
  0.2%, 0.5%, 1%, 5% text coverage; asserts the sharp frame passes and the blurred frame is
  `TooBlurry` at every level.
- **Changed expectation** on `a flat mid gray frame is ...` and `a fully flat frame with no
  edges anywhere is ...`: both now expect `FailureReason.NoTextFound` instead of `TooBlurry`.
  This is the deliberate consequence of the fix's third requirement (a frame with essentially no
  edges is "no text", not "blurry") — both fixtures have exactly zero gradient anywhere, which is
  precisely the case the new distinction exists to separate from genuine blur.
- All other existing cases (`sharp well lit content passes`, `a near black frame is too dark`,
  `darkness is reported before blurriness`, `sparse sharp text on a mostly flat worksheet frame
  passes`, `genuinely blurred sparse text is still rejected`, `sparse sharp text on a dim but
  legible background passes`) kept their original assertions and pass unchanged.

**Confirmed failing before the fix:** ran the new/changed tests against the pre-fix (99th
percentile over all pixels) code:
```
CaptureQualityGateTest > a flat mid gray frame is no text found, not too blurry() FAILED
CaptureQualityGateTest > sharp text passes and blurred text is rejected across a coverage sweep() FAILED
CaptureQualityGateTest > a fully flat frame with no edges anywhere is no text found() FAILED
```

### Downstream: `FailureReason.NoTextFound` user-facing message

`NoTextFound` was already a handled `FailureReason` before this change (referenced in
`ResultScreen.kt` / `OcrResult.kt`), so no new Indonesian user-facing string was needed — this
change only makes the gate produce that existing verdict in a case (flat frame) that previously
produced `TooBlurry`. `:app`'s user-facing messages were not touched.

---

## Full test output

```
$ ./gradlew :ocr-core:test :ocr-mlkit:testDebugUnitTest
BUILD SUCCESSFUL
```

Test counts from the JUnit XML reports after the full run:
- `:ocr-core:test` — **80 tests**, 0 failures, 0 skipped (79 pre-existing + 1 new
  `RowFragmentJoinerTest` case).
- `:ocr-mlkit:testDebugUnitTest` — **13 tests**, 0 failures, 0 skipped (12 pre-existing + 1 new
  `CaptureQualityGateTest` coverage-sweep case; 2 existing cases had their expected
  `FailureReason` changed, not added/removed).

```
$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL
```

### Instrumented tests (`:ocr-mlkit:connectedDebugAndroidTest`)

Booted AVD `Pixel_10_Pro` (API 17 image, headless: `-no-window -no-audio -gpu
swiftshader_indirect`), waited for `sys.boot_completed=1`, then ran the module's connected
tests against it:

```
Starting 2 tests on Pixel_10_Pro(AVD) - 17

id.dotcode.braille.ocr.mlkit.OcrAccuracyHarnessTest > measuresCharacterAndWordErrorRateAgainstSampleWorksheets[Pixel_10_Pro(AVD) - 17] SKIPPED

Pixel_10_Pro(AVD) - 17 Tests 2/2 completed. (1 skipped) (0 failed)
Finished 3 tests on Pixel_10_Pro(AVD) - 17

BUILD SUCCESSFUL in 1m 17s
```

0 failures on device. `OcrAccuracyHarnessTest` is skipped (pre-existing — it requires bundled
sample worksheet images not present in this environment, unrelated to this change). No
instrumented test targets `CaptureQualityGate` directly; its unit tests (`testDebugUnitTest`,
above) cover the fixed logic — this run only confirms the module still builds and runs on a
real device with the change in place.

---

## Residual risk

- **RowFragmentJoiner "silent non-join" variant** (documented above) is not fixed: the same
  band-straddling mechanism can make an intended join fail to happen. It degrades safely (two
  blocks instead of one) rather than corrupting text, so it was left out of scope per the task
  instructions, but it is a real, still-open gap.
- **CaptureQualityGate thresholds are simulation-derived, not photographed-camera-derived.**
  `edgeGradientFloor=10`, `minEdgeFraction=0.0001`, and `minSharpness=80` were tuned against
  synthetic stripe/box-blur fixtures with a clean ~13x separation between sharp (215) and
  blurred (17) scores. Real camera JPEG compression, sensor noise, and non-horizontal edges
  (this gate only looks at horizontal gradient) could compress that margin in ways synthetic
  fixtures don't capture. The gate remains a coarse safety net (per its own KDoc, "catch only
  catastrophic captures"), not a general-purpose sharpness classifier, and should be revisited
  against real device captures before being tightened further.
- **`minEdgeFraction` interacts with `edgeGradientFloor`**: raising the noise floor without
  re-checking the fraction threshold (or vice versa) could reopen a gap where a real photo's
  legitimate low-density edges get classified as noise. The KDoc on both parameters cross-
  references the numbers that justify the current pairing; if either is retuned, both need to be
  re-validated together against the coverage sweep.
