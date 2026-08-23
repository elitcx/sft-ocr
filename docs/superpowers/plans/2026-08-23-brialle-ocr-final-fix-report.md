# BRaiLLE OCR MVP — final fix wave

**Branch:** `feat/ocr-mvp` · **Base:** `d3b7141` · **Head:** `09e43cf`
**Date:** 2026-08-23

| # | Finding | Commit |
|---|---------|--------|
| CRITICAL 1 | `ColumnSegmenter` collapses on any full-width line | `1cdec41` |
| CRITICAL 2 | `SkewEstimator` inflates every box | `5f4f00c` |
| IMPORTANT 3 | EXIF rotation transposes page dimensions | `d0a5627` |
| IMPORTANT 5 | Full-resolution decode risks OOM | `ebccc3c` |
| IMPORTANT 4 | Accuracy harness does not exist | `09e43cf` |

---

## CRITICAL 1 — full-width lines no longer collapse column detection

### What changed

`ColumnSegmenter.segment` gained a `pageWidth: Int` parameter, threaded from
`DocumentStructurer` as `deskewed.result.imageWidth`. Gutter detection now runs over
**column-bound lines only** — lines whose width is at most
`StructuringConfig.spanningLineWidthFraction` (new, `0.8f`, KDoc'd alongside the rest of
that file) times the page width. Spanning lines are excluded from the interval merge and
then assigned to a column by their centre like every other line, after the split is
decided.

Two secondary decisions, both deliberately conservative:

* The `minLinesPerColumn` populated-guard counts **only column-bound lines**. A spanning
  title lands in one column by centre and must not be able to prop up a column that has
  no real content of its own.
* Column `bounds` are likewise derived from column-bound lines, so a spanning title does
  not stretch a column's reported extent across the whole page and corrupt the downstream
  `indentLevel` and `alignment` measurements.

All three pre-existing guards (`minLinesForColumnSplit`, `minLinesPerColumn`, and the
collapse-when-underpopulated rule) are untouched. A new early return also collapses to a
single column when fewer than `minLinesForColumnSplit` **column-bound** lines survive the
filter, which keeps a page of nothing but full-width prose single-column.

### Before / after, concrete

Two-column worksheet, page 1600 wide: left column `100..600`, right column `900..1400`,
title `60..1400` (width 1340).

| | Before | After |
|---|---|---|
| x-runs after merge | `[60..1400]` — the title unions both | `[100..600], [900..1400]` |
| `runs.size` | 1, so `< 2`, so single column | 2 |
| gutter boundary | none | `750` |
| `columnCount` | **1** | **2** |
| question reading order | **1, 4, 2, 5, 3, 6** | **1, 2, 3, 4, 5, 6** |

The old behaviour was not a mis-format. With `columnCount == 1`, `ReadingOrderSorter`
bands by row and sorts left-to-right inside the band, so question 4 (top of the right
column) is emitted between questions 1 and 2. A blind student reading that under their
fingers has no way to notice.

### Tests

`ocr-core/src/test/kotlin/.../ColumnSegmenterTest.kt`

* **Replaced** `a full width heading above two columns does not break the split`. It
  asserted `columnCount == 1` and its comment defended that as "the correct conservative
  answer". It was not conservative, it was the bug: the heading was the *cause* of the
  collapse, not evidence that the page was genuinely ambiguous. Its replacement,
  `a full width heading above two columns no longer collapses the split`, asserts
  `columnCount == 2`, the per-line column assignment `[0, 0, 0, 1, 1]`, and that the
  column bounds ignore the spanning line (`100..600` and `900..1400`, not `60..1400`).
  No assertion was weakened — the replacement is strictly stronger.
* **Added** `a page of nothing but spanning lines stays single column` — five full-width
  lines, no column-bound evidence at all, must not split.
* **Added** `a spanning line cannot prop up an underpopulated column` — the right column
  holds one genuine line plus a spanning title whose centre lands right of the gutter.
  Pins the decision to count only column-bound lines in the populated guard.
* Existing single-column, wide-gutter, lone-line and too-few-lines tests unchanged apart
  from the new `pageWidth` argument.

`ocr-core/src/test/kotlin/.../DocumentStructurerTest.kt`

* **Added** `a full width title does not scramble two column reading order` — the
  end-to-end regression. Asserts `columnCount == 2`, markers `1.` through `6.` in order,
  and `columnIndex` `[0,0,0,0,1,1,1]`, i.e. the left column is read out entirely before
  the right.

`ocr-core/src/test/resources/fixtures/worksheet-two-column.json`

* Title changed from `100..600` (width 500 — narrower than the left column, which is
  exactly why this bug survived the fixture) to `60..1400` (width 1340, genuinely
  full-width at more than `0.8 x 1600`). Text updated to `LEMBAR KERJA IPA KELAS ENAM` to
  match the wider box. The raw `lines` array stays scrambled: raw order is
  `5, 2, 7, TITLE, 6, 3, 1, 4`, so a stable no-op sort yields markers `5, 2, 6, 3, 1, 4`,
  never `1..6`.

Call sites in `LineMergerTest`, `ReadingOrderSorterTest` and `RoleClassifierTest` updated
for the new signature.

---

## CRITICAL 2 — deskew works from corner points, not from the AABB

### What changed

`SkewEstimator.rotatedBy` previously took the **already axis-aligned** `box`, rotated its
four corners, and re-enclosed them. That is the AABB of a rotated AABB. `cornerPoints` —
populated by `MlKitAdapter` since the adapter was written, consumed by nothing, and not
even rotated by the old code — is now the primary input: the corners are rotated into
upright space and the box is built from those. For a line that was genuinely skewed on the
page, that box is tight.

When `cornerPoints` is empty the old hull behaviour remains as an **explicit, commented
fallback** rather than an implicit one: there is no tighter answer derivable from an AABB
alone. `cornerPoints` are themselves rotated and carried forward on the returned line.
Word boxes necessarily take the fallback (the contract carries no per-word corners);
nothing downstream measures word geometry, and the comment says so.

### Before / after, concrete

A 200 x 40 line deskewed by 10 degrees:

| | Before | After (with corner points) |
|---|---|---|
| height | `200*sin10 + 40*cos10` = **74.12** | **40.0** |
| width | `200*cos10 + 40*sin10` = **203.90** | **200.0** |

The damage was not the absolute number, it was that inflation scales with line **width**,
so it is not uniform. On the new end-to-end test page at 8 degrees, a 600 x 56 title over
four 700 x 30 body lines:

| | Before | After |
|---|---|---|
| title height | `600*sin8 + 56*cos8` = **139.0** | **56.0** |
| body height | `700*sin8 + 30*cos8` = **127.1** | **30.0** |
| `medianLineHeight` | **127.1** | **30.0** |
| `relativeTextHeight` of title | **1.09** | **1.87** |
| role, vs `titleHeightRatio` 1.6 | **PARAGRAPH** | **TITLE** |
| `paragraphGapFactor` threshold (1.6x) | **203.4 px** | **48.0 px** |

That last row is the second downstream effect: an 85%-inflated `medianLineHeight` raises
LineMerger's gap threshold by the same factor, so paragraphs that should have been
separate blocks merge into one. `ReadingOrderSorter`'s `rowBandFactor` and the
`indentQuantumFactor` / `leftAlignToleranceFactor` measurements are scaled off the same
inflated stats.

### Tests

`ocr-core/src/test/kotlin/.../SkewEstimatorTest.kt`

* **Rewritten** `deskew rotates boxes back to upright` into `deskew recovers the true line
  height from corner points`. **This test previously pinned the bug.** The prior ruling
  that 74.12 was correct was an arithmetic check, not a behavioural one: 74.12 *is* the
  hull of a rotated AABB, and deskew must not produce that hull. Deskew's contract is to
  recover the line's true upright geometry, and a recognizer that reports a tilted quad
  gives us exactly the information needed to do so. The rewritten test first asserts the
  *input* box is the 74.12 hull (which is what ML Kit really reports for a tilted line),
  then asserts the deskewed box comes back at its true 40 x 200 within 0.1.
* **Added** `without corner points deskew falls back to the rotated hull` — retains the
  74.12 / 203.90 arithmetic as coverage of the documented fallback path, so removing the
  fallback cannot pass silently.

`ocr-core/src/test/kotlin/.../TestLines.kt`

* `line()` gained a `cornerPoints` parameter (default empty, so every existing call site
  keeps taking the fallback path deliberately).
* **Added** `skewedLine(text, x, y, w, h, angleDeg, pivot)`, which builds a line the way a
  recognizer actually reports one on a skewed page: an upright `w x h` rectangle rotated
  about `pivot`, with `cornerPoints` holding the true tilted quad and `box` holding its
  AABB.

`ocr-core/src/test/kotlin/.../DocumentStructurerTest.kt`

* **Added** `a skewed page still classifies its title as a title` — the end-to-end
  regression this finding is about, which no existing test covered. A skewed page with a
  large title over body text must still yield `BlockRole.TITLE` after deskew, with
  `relativeTextHeight > 1.6`.

---

## IMPORTANT 3 — EXIF rotation no longer transposes the page

`OcrEngine` passed `scaled.width` / `scaled.height` to `MlKitAdapter.toRawTextResult`,
but ML Kit returns coordinates in the **rotated** image space. For a 90 or 270 degree EXIF
photo — the norm for phone captures and this app's primary path — the page was transposed
relative to its own boxes:

```kotlin
val quarterTurn = rotationDegrees % 180 != 0
val pageWidth = if (quarterTurn) scaled.height else scaled.width
val pageHeight = if (quarterTurn) scaled.width else scaled.height
```

Concretely, a 1200 x 1600 scaled bitmap at 90 degrees produced boxes in a 1600-wide,
1200-tall space while reporting `pageHeight = 1600`. Both `RoleClassifier` position bands
are fractions of `pageHeight`, so `topBandFraction` (0.15, TITLE promotion) and
`bottomBandFraction` (0.92, PAGE_NUMBER) landed on the wrong parts of the page, and the
exported JSON shipped wrong `pageWidth` / `pageHeight` to the downstream braille engine.

No new test: this needs a real EXIF-rotated capture on a device, and the existing
instrumented test path (`recognize(bitmap)`) runs at rotation 0 by construction. Flagged
under **Concerns** below.

---

## IMPORTANT 5 — subsampled decode

`recognize(uri)` called `BitmapFactory.decodeStream` with no `inSampleSize`. A 12MP
capture allocates ~48 MB as ARGB_8888 before any downscale, and `CaptureQualityGate`
then allocates two more `IntArray(w*h)` (~48 MB each at full res) on top of it.

`ImagePreprocessor` gained:

* `sampleSizeFor(width, height, targetLongEdge)` — the largest power of two that still
  leaves the long edge **at or above** `TARGET_LONG_EDGE`, so nothing is lost that the
  subsequent `downscale` would have kept. Pure arithmetic, hence JVM-testable.
* `decodeSampled(data, targetLongEdge)` — `inJustDecodeBounds` for the header, then a real
  decode at that factor.

`OcrEngine.recognize(uri)` now reads the file **once** into a byte array shared by EXIF,
the bounds pass and the real decode, which also removes the pre-existing double stream
open (the optional half of this finding). The compressed bytes are a few MB; the decoded
bitmap would be an order of magnitude larger, so buffering the file is the cheap half.

For a 4000 x 3000 capture: `inSampleSize = 2`, giving 2000 x 1500 and ~12 MB peak instead
of 48 MB; after `downscale` to 1600 x 1200 the quality gate's two `IntArray`s are ~7.7 MB
each instead of ~48 MB each.

**Test added:** `ocr-mlkit/src/test/kotlin/.../ImagePreprocessorTest.kt`, four JVM cases —
images at or below target are not subsampled; a 4000 x 3000 12MP capture gets exactly
factor 2 and doubling it would fall below target; the factor is always a power of two and
never overshoots below target across a range of sizes; undecodable bounds (`-1 x -1`, which
is what `BitmapFactory` reports on a bad header) fall back to 1.

---

## IMPORTANT 4 — the accuracy harness

**New:** `ocr-mlkit/src/androidTest/kotlin/.../OcrAccuracyHarnessTest.kt`.

Scans a device directory — default `/sdcard/Download/braille-samples`, overridable with the
`brailleSamplesDir` instrumentation argument — for `*.jpg` / `*.jpeg` / `*.png` each paired
with a same-named `.txt`, sorted by name for determinism. Each image runs through a real
`OcrEngine` via `Uri.fromFile`, exercising the full production path including the new
subsampled decode. The document is flattened in reading order (marker plus text per block,
newline-joined), scored with `ErrorRate.compare`, and logged per-image and in aggregate
under the `OcrAccuracyHarness` tag. Aggregate character accuracy is asserted above 0.9
**only when at least one pair was found**.

A `FailureReason` from the quality gate scores as empty recognized text rather than
dropping the sample — a rejected capture is a real accuracy outcome, not an excuse.

With no samples the test **skips** via `Assume.assumeTrue`, with a message naming the
directory, showing `exists` / `readable` / `entries`, and giving the exact `adb push`. It
does not fail (an unpopulated device is not a regression) and does not pass silently.

**Also new:** `ocr-mlkit/src/androidTest/AndroidManifest.xml`, carrying
`MANAGE_EXTERNAL_STORAGE` (plus `READ_EXTERNAL_STORAGE` capped at API 32). This merges
into the **test apk only** — nothing in the shipped library requests it. It is needed
because under scoped storage the test app can see the shared-storage *directory* but none
of the files another process wrote into it, so `adb shell ls` shows the samples while
`File.listFiles()` returns empty. I hit exactly that during verification, which is why the
skip message calls it out by name.

### How to run it

```
adb shell mkdir -p /sdcard/Download/braille-samples
adb push samples/worksheet-01.png /sdcard/Download/braille-samples/
adb push samples/worksheet-01.txt /sdcard/Download/braille-samples/

./gradlew :ocr-mlkit:assembleDebugAndroidTest
adb install -r -t ocr-mlkit/build/outputs/apk/androidTest/debug/ocr-mlkit-debug-androidTest.apk
adb shell appops set --uid id.dotcode.braille.ocr.mlkit.test MANAGE_EXTERNAL_STORAGE allow

./gradlew :ocr-mlkit:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=id.dotcode.braille.ocr.mlkit.OcrAccuracyHarnessTest
adb logcat -d -s OcrAccuracyHarness
```

The `appops` line is required and easy to miss; the skip message tells you so if you do.
An app-private directory such as
`/sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test/braille-samples` is readable with
no grant at all, if you would rather not use appops — pass it via `brailleSamplesDir`.

### Verified both ways on the AVD

Skip path (empty directory), from `adb logcat -d -s OcrAccuracyHarness`:

```
W OcrAccuracyHarness: SKIPPED: no sample worksheets found in /sdcard/Download/braille-samples
 — populate it with matching pairs (worksheet-01.jpg or .png plus worksheet-01.txt) via
 `adb push samples/worksheet-01.png /sdcard/Download/braille-samples/`, or point the harness
 elsewhere with -Pandroid.testInstrumentationRunnerArguments.brailleSamplesDir=/path/on/device.
 Directory exists=false readable=false entries=-1. If the directory exists and is readable but
 lists nothing, scoped storage is hiding files this app did not write: run
 `adb shell appops set --uid id.dotcode.braille.ocr.mlkit.test MANAGE_EXTERNAL_STORAGE allow`
 and re-run.
```

Measuring path — one rendered worksheet PNG plus its `.txt` pushed to the device:

```
Starting 1 tests on Pixel_10_Pro(AVD) - 17
Pixel_10_Pro(AVD) - 17 Tests 1/1 completed. (0 skipped) (0 failed)
BUILD SUCCESSFUL in 36s

I OcrAccuracyHarness: worksheet-01.png: CER=0.0000 WER=0.0000 charAcc=1.0000 wordAcc=1.0000 expectedChars=122 expectedWords=20
I OcrAccuracyHarness: === aggregate over 1 image(s) in /sdcard/Download/braille-samples ===
I OcrAccuracyHarness: AGGREGATE: CER=0.0000 WER=0.0000 charAcc=1.0000 wordAcc=1.0000 expectedChars=122 expectedWords=20
```

The sample was a locally rendered clean image, not a photograph, so 1.0000 measures the
harness rather than real-world accuracy. It proves the harness genuinely runs end to end
and is not merely a skip with an assertion hidden behind it. The samples were removed from
the device afterwards, so the committed state's `connectedDebugAndroidTest` skips cleanly.

---

## Test summary

| Module | Command | Result |
|---|---|---|
| `:ocr-core` | `test` | **67 tests, 0 failures, 0 skipped** (was 62; +5) |
| `:ocr-mlkit` | `testDebugUnitTest` | **8 tests, 0 failures, 0 skipped** (was 4; +4) |
| `:app` | `assembleDebug` | **BUILD SUCCESSFUL** |
| `:ocr-mlkit` | `connectedDebugAndroidTest` | **2 tests on Pixel_10_Pro(AVD) - 17, 0 failed, 1 skipped** (the harness, correctly) |

### Every test added or amended

| Test | File | Why |
|---|---|---|
| `a full width heading above two columns no longer collapses the split` | `ColumnSegmenterTest` | **Replaces** a test that pinned CRITICAL 1. Asserts 2 columns, the assignment, and spanning-line-free bounds. |
| `a page of nothing but spanning lines stays single column` | `ColumnSegmenterTest` | **New.** No column-bound evidence must not split. |
| `a spanning line cannot prop up an underpopulated column` | `ColumnSegmenterTest` | **New.** Pins counting only column-bound lines in the populated guard. |
| `a full width title does not scramble two column reading order` | `DocumentStructurerTest` | **New.** End-to-end CRITICAL 1 regression: 2 columns, markers 1-6, left column read first. |
| `a skewed page still classifies its title as a title` | `DocumentStructurerTest` | **New.** End-to-end CRITICAL 2 regression, uncovered before. |
| `deskew recovers the true line height from corner points` | `SkewEstimatorTest` | **Rewritten** from `deskew rotates boxes back to upright`, which pinned CRITICAL 2. |
| `without corner points deskew falls back to the rotated hull` | `SkewEstimatorTest` | **New.** Keeps the hull arithmetic as coverage of the documented fallback. |
| 4 cases in `ImagePreprocessorTest` | `:ocr-mlkit` unit | **New.** Subsample factor math for IMPORTANT 5. |
| `measuresCharacterAndWordErrorRateAgainstSampleWorksheets` | `OcrAccuracyHarnessTest` | **New.** IMPORTANT 4 itself. |

Two assertions were changed. Both had pinned behaviour that was wrong, both are argued
above, and neither was weakened — each replacement asserts something strictly stronger.

---

## Concerns, and one thing I think is a sixth issue

### 1. A centred full-width title can be assigned to the RIGHT column (likely a sixth bug)

The brief specified assigning spanning lines to a column "by their centre", and that is
what I implemented. But for a symmetric two-column layout, the gutter midpoint sits at
roughly the page centre — and so does the centre of a genuinely centred full-width title.
Which side it lands on is a coin flip decided by a few pixels of asymmetry.

If it lands in the right column, `ReadingOrderSorter` (column-major) emits the page title
**after every question in the left column**. Reading order is then still not what a
sighted reader follows, which is success criterion 1.

Both the new fixture and the new tests are built so the title lands left (title `60..1400`
on a 1600 page: centre 730, gutter midpoint 750), so the tests pass — but that is 20 px of
margin, and it is luck, not design. I did not change the rule because the brief was
explicit about it, and because getting it wrong in the other direction is also possible.

The fix I would propose, for your call rather than mine: a spanning line should not be
assigned by centre at all. It should be ordered by its **vertical position relative to the
columns** — a spanning line above the topmost column-bound line belongs before all
columns, one below the bottommost belongs after all of them, and one in the middle splits
the page into stacked column groups. That is a change to `ReadingOrderSorter`'s model
(spanning lines as a separate ordering tier), not a one-line tweak, which is why I have
not done it inside a fix wave.

### 2. IMPORTANT 3 has no automated coverage

The swap is three lines and obviously correct by inspection, but nothing tests it. The
instrumented path calls `recognize(bitmap, rotationDegrees = 0)`, and `recognize(uri)`
needs a real EXIF-tagged file. A test would need a fixture JPEG with
`ORIENTATION_ROTATE_90` pushed to the device and an assertion that `pageWidth` matches the
bitmap's height. Worth adding; out of scope for this wave, and I did not want to smuggle a
sixth workstream in.

### 3. The accuracy harness needs an out-of-band permission grant

`adb shell appops set --uid ... MANAGE_EXTERNAL_STORAGE allow` is a manual step that a
plain `./gradlew :ocr-mlkit:connectedDebugAndroidTest` will not perform, so a first-time
user pushing samples to `/sdcard/Download` will see a skip and may believe the harness is
broken. I mitigated this with an explicit diagnostic in the skip message (it prints
`entries=` and names the exact appops command) and documented it in the KDoc, but it is
still friction. Switching the default to
`/sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test/braille-samples` would remove the
grant entirely; I kept `/sdcard/Download/braille-samples` because the brief named it.

### 4. The spec promises generated sample worksheets; those do not exist

Spec section 7 says the harness "ships with a small set of generated worksheets so it is
runnable before real samples exist." The brief for this wave instead specified a clean
skip when no samples are present, which is what I built. The two are in tension. I
followed the brief. If you want the spec's version, the generator would be a `:ocr-mlkit`
androidTest helper that renders worksheets to the app's external files dir on first run —
straightforward, but it changes the harness from "measures your samples" to "measures
synthetic text that is trivially 100% accurate", which I think is a worse default and is
exactly what the 1.0000 figure above illustrates.

### 5. Word boxes are still inflated by deskew

`RawWord.box` has no corner points in the contract, so word geometry necessarily takes the
hull fallback and remains inflated on skewed pages. Nothing downstream reads it — `TextLine`
does not carry words, and no pipeline stage measures them — so this is latent, not live.
It is commented in `SkewEstimator`. If a later phase starts using word boxes (character
cell mapping, say), `RawWord` will need a `cornerPoints` field and `MlKitAdapter` will need
to populate it from `Text.Element.cornerPoints`.

---

## Full final output of all four commands

Run against the committed head `09e43cf`, with `--rerun-tasks` so nothing was served
from the build cache, and with the sample directory removed from the device. Verbatim,
ANSI colour codes stripped.

### `./gradlew :ocr-core:test --rerun-tasks --console=plain`

```
> Task :ocr-core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :ocr-core:processResources NO-SOURCE
> Task :ocr-core:processTestResources
> Task :ocr-core:compileKotlin
> Task :ocr-core:compileJava NO-SOURCE
> Task :ocr-core:classes UP-TO-DATE
> Task :ocr-core:jar
> Task :ocr-core:compileTestKotlin
> Task :ocr-core:compileTestJava NO-SOURCE
> Task :ocr-core:testClasses

> Task :ocr-core:test

ScaffoldTest > module has no android on the classpath() PASSED

ErrorRateTest > insertions and deletions both count() PASSED

ErrorRateTest > whitespace is normalized before comparison() PASSED

ErrorRateTest > empty expected text with output is fully wrong() PASSED

ErrorRateTest > one substituted character in sixteen() PASSED

ErrorRateTest > identical text has zero error() PASSED

ErrorRateTest > one wrong word in three() PASSED

GeometryTest > enclosing folds a list() PASSED

GeometryTest > union spans both boxes() PASSED

GeometryTest > box exposes derived dimensions() PASSED

GeometryTest > enclosing an empty list throws() PASSED

OcrDocumentTest > document survives a json round trip() PASSED

OcrDocumentTest > json is human readable with named roles() PASSED

OcrDocumentTest > timings total sums every stage() PASSED

ColumnSegmenterTest > a full width heading above two columns no longer collapses the split() PASSED

ColumnSegmenterTest > a lone line in a would be column collapses the split() PASSED

ColumnSegmenterTest > a wide gutter splits two columns() PASSED

ColumnSegmenterTest > a single column page yields one column() PASSED

ColumnSegmenterTest > a spanning line cannot prop up an underpopulated column() PASSED

ColumnSegmenterTest > too few lines never splits() PASSED

ColumnSegmenterTest > a page of nothing but spanning lines stays single column() PASSED

DocumentStructurerTest > block ids are sequential in reading order() PASSED

DocumentStructurerTest > an empty page yields an empty document() PASSED

DocumentStructurerTest > a numbered worksheet yields addressable questions with markers stripped() PASSED

DocumentStructurerTest > a full width title does not scramble two column reading order() PASSED

DocumentStructurerTest > timings pass through untouched and mean confidence is averaged() PASSED

DocumentStructurerTest > centered text is reported as centered() PASSED

DocumentStructurerTest > two column worksheet fixture keeps questions in order() PASSED

DocumentStructurerTest > a near full width block is not reported as centered() PASSED

DocumentStructurerTest > a skewed page still classifies its title as a title() PASSED

DocumentStructurerTest > mean confidence is null when no line reports confidence() PASSED

LineMergerTest > a new marker always starts a block even when tightly spaced() PASSED

LineMergerTest > reflow joins ordinary wraps with a single space() PASSED

LineMergerTest > a wide vertical gap starts a new block() PASSED

LineMergerTest > reflow joins a hyphenated wrap without a space() PASSED

LineMergerTest > a column change always starts a block() PASSED

LineMergerTest > a continuation line joins its marker block() PASSED

LineMergerTest > tight lines with aligned edges form one paragraph() PASSED

LineMergerTest > terminal punctuation ends a block() PASSED

MarkerParserTest > alpha marker() PASSED

MarkerParserTest > bullet marker() PASSED

MarkerParserTest > plain prose has no marker() PASSED

MarkerParserTest > roman marker is preferred over alpha() PASSED

MarkerParserTest > numeric marker with a parenthesis() PASSED

MarkerParserTest > numeric marker with a period() PASSED

MarkerParserTest > a marker with no following text is not a marker() PASSED

MarkerParserTest > an over long number is not a marker() PASSED

MarkerParserTest > a decimal number is not a marker() PASSED

PageStatsTest > median char width divides width by character count() PASSED

PageStatsTest > blank lines do not poison the char width median() PASSED

PageStatsTest > median line height is the middle value() PASSED

ReadingOrderSorterTest > two columns read fully down the left before the right() PASSED

ReadingOrderSorterTest > single column sorts top to bottom regardless of input order() PASSED

ReadingOrderSorterTest > lines on the same visual row sort left to right despite baseline jitter() PASSED

ReadingOrderSorterTest > sorting an empty page yields an empty list() PASSED

RoleClassifierTest > large text at the top of the page is a title() PASSED

RoleClassifierTest > a numeric only block at the page foot is a page number() PASSED

RoleClassifierTest > a bullet is always a list item() PASSED

RoleClassifierTest > small text is a caption() PASSED

RoleClassifierTest > three or more numbered blocks become questions() PASSED

RoleClassifierTest > moderately large text mid page is a heading() PASSED

RoleClassifierTest > a lone numbered block stays a list item() PASSED

SkewEstimatorTest > deskew recovers the true line height from corner points() PASSED

SkewEstimatorTest > without corner points deskew falls back to the rotated hull() PASSED

SkewEstimatorTest > skew below the threshold is left uncorrected() PASSED

SkewEstimatorTest > median ignores a single wild outlier() PASSED

SkewEstimatorTest > a straight page reports no skew() PASSED

BUILD SUCCESSFUL in 9s
5 actionable tasks: 5 executed
```

Result XML totals: **67 tests, 0 failures, 0 errors, 0 skipped.**

### `./gradlew :ocr-mlkit:testDebugUnitTest --rerun-tasks --console=plain`

```
> Task :ocr-mlkit:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :ocr-mlkit:preBuild UP-TO-DATE
> Task :ocr-core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :ocr-mlkit:preDebugBuild UP-TO-DATE
> Task :ocr-mlkit:generateDebugResValues
> Task :ocr-mlkit:generateDebugResources
> Task :ocr-mlkit:packageDebugResources
> Task :ocr-mlkit:processDebugNavigationResources
> Task :ocr-mlkit:preDebugUnitTestBuild UP-TO-DATE
> Task :ocr-mlkit:javaPreCompileDebug
> Task :ocr-mlkit:javaPreCompileDebugUnitTest
> Task :ocr-mlkit:parseDebugLocalResources
> Task :ocr-mlkit:generateDebugRFile
> Task :ocr-core:processResources NO-SOURCE
> Task :ocr-mlkit:generateDebugUnitTestStubRFile
> Task :ocr-core:compileKotlin
> Task :ocr-core:compileJava NO-SOURCE
> Task :ocr-core:classes UP-TO-DATE
> Task :ocr-core:jar
> Task :ocr-mlkit:compileDebugKotlin
> Task :ocr-mlkit:compileDebugJavaWithJavac NO-SOURCE
> Task :ocr-mlkit:bundleLibRuntimeToJarDebug
> Task :ocr-mlkit:bundleLibCompileToJarDebug
> Task :ocr-mlkit:processDebugJavaRes
> Task :ocr-mlkit:compileDebugUnitTestKotlin
> Task :ocr-mlkit:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :ocr-mlkit:processDebugUnitTestJavaRes
> Task :ocr-mlkit:testDebugUnitTest

BUILD SUCCESSFUL in 9s
18 actionable tasks: 18 executed
```

Result XML totals: **8 tests, 0 failures, 0 errors, 0 skipped.**

### `./gradlew :app:assembleDebug --rerun-tasks --console=plain`

```
> Task :app:preBuild UP-TO-DATE
> Task :ocr-mlkit:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :ocr-mlkit:preDebugBuild UP-TO-DATE
> Task :ocr-core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:mergeDebugNativeDebugMetadata NO-SOURCE
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :ocr-mlkit:writeDebugAarMetadata
> Task :app:generateDebugResValues
> Task :ocr-mlkit:processDebugNavigationResources
> Task :ocr-mlkit:generateDebugResValues
> Task :app:generateDebugResources
> Task :ocr-mlkit:generateDebugResources
> Task :ocr-mlkit:packageDebugResources
> Task :ocr-mlkit:extractDeepLinksDebug
> Task :ocr-mlkit:compileDebugLibraryResources
> Task :ocr-mlkit:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :ocr-mlkit:processDebugManifest
> Task :ocr-mlkit:javaPreCompileDebug
> Task :ocr-mlkit:mergeDebugShaders
> Task :ocr-mlkit:compileDebugShaders NO-SOURCE
> Task :ocr-mlkit:generateDebugAssets UP-TO-DATE
> Task :ocr-mlkit:parseDebugLocalResources
> Task :ocr-mlkit:mergeDebugAssets
> Task :ocr-mlkit:mergeDebugJniLibFolders
> Task :ocr-mlkit:mergeDebugNativeLibs NO-SOURCE
> Task :ocr-mlkit:copyDebugJniLibsProjectOnly
> Task :ocr-mlkit:generateDebugRFile
> Task :ocr-core:processResources NO-SOURCE
> Task :app:checkDebugAarMetadata
> Task :app:processDebugNavigationResources
> Task :app:compileDebugNavigationResources
> Task :app:mapDebugSourceSetPaths
> Task :ocr-core:compileKotlin
> Task :ocr-core:compileJava NO-SOURCE
> Task :ocr-core:classes UP-TO-DATE
> Task :ocr-core:jar
> Task :ocr-mlkit:compileDebugKotlin
> Task :ocr-mlkit:compileDebugJavaWithJavac NO-SOURCE
> Task :ocr-mlkit:bundleLibCompileToJarDebug
> Task :ocr-mlkit:bundleLibRuntimeToJarDebug
> Task :ocr-mlkit:processDebugJavaRes
> Task :app:packageDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:parseDebugLocalResources
> Task :ocr-mlkit:bundleLibRuntimeToDirDebug
> Task :app:processDebugMainManifest
> Task :app:processDebugManifest
> Task :app:javaPreCompileDebug
> Task :app:mergeDebugShaders
> Task :app:compileDebugShaders NO-SOURCE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:mergeDebugAssets
> Task :app:desugarDebugFileDependencies
> Task :app:mergeDebugJniLibFolders
> Task :app:validateSigningDebug
> Task :app:writeDebugAppMetadata
> Task :app:writeDebugSigningConfigVersions
> Task :app:processDebugManifestForPackage
> Task :app:checkDebugDuplicateClasses
> Task :app:compressDebugAssets
> Task :app:mergeDebugResources
> Task :app:mergeLibDexDebug
> Task :app:mergeDebugNativeLibs

> Task :app:stripDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so, libimage_processing_util_jni.so, libmlkit_google_ocr_pipeline.so, libsurface_util_jni.so. Run with --info option to learn more.

> Task :app:processDebugResources
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:dexBuilderDebug
> Task :app:processDebugJavaRes
> Task :app:mergeDebugGlobalSynthetics
> Task :app:mergeProjectDexDebug
> Task :app:mergeDebugJavaResource
> Task :app:mergeExtDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug

BUILD SUCCESSFUL in 18s
59 actionable tasks: 59 executed
```

### `./gradlew :ocr-mlkit:connectedDebugAndroidTest --rerun-tasks --console=plain`

```
> Task :ocr-mlkit:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :ocr-mlkit:preBuild UP-TO-DATE
> Task :ocr-core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :ocr-mlkit:preDebugBuild UP-TO-DATE
> Task :ocr-mlkit:generateDebugResValues
> Task :ocr-mlkit:generateDebugResources
> Task :ocr-mlkit:packageDebugResources
> Task :ocr-mlkit:processDebugNavigationResources
> Task :ocr-mlkit:preDebugAndroidTestBuild UP-TO-DATE
> Task :ocr-mlkit:writeDebugAarMetadata
> Task :ocr-mlkit:javaPreCompileDebug
> Task :ocr-mlkit:processDebugAndroidTestNavigationResources
> Task :ocr-mlkit:parseDebugLocalResources
> Task :ocr-core:processResources NO-SOURCE
> Task :ocr-mlkit:checkDebugAndroidTestAarMetadata
> Task :ocr-mlkit:generateDebugRFile
> Task :ocr-mlkit:compileDebugAndroidTestNavigationResources
> Task :ocr-mlkit:compileDebugLibraryResources
> Task :ocr-mlkit:generateDebugAndroidTestResValues
> Task :ocr-mlkit:mapDebugAndroidTestSourceSetPaths
> Task :ocr-mlkit:generateDebugAndroidTestResources
> Task :ocr-mlkit:extractDeepLinksDebug
> Task :ocr-mlkit:javaPreCompileDebugAndroidTest
> Task :ocr-mlkit:mergeDebugAndroidTestShaders
> Task :ocr-mlkit:compileDebugAndroidTestShaders NO-SOURCE
> Task :ocr-mlkit:generateDebugAndroidTestAssets UP-TO-DATE
> Task :ocr-mlkit:mergeDebugShaders
> Task :ocr-mlkit:compileDebugShaders NO-SOURCE
> Task :ocr-mlkit:generateDebugAssets UP-TO-DATE
> Task :ocr-mlkit:processDebugManifest
> Task :ocr-mlkit:mergeDebugAssets
> Task :ocr-mlkit:processDebugAndroidTestManifest
> Task :ocr-core:compileKotlin
> Task :ocr-core:compileJava NO-SOURCE
> Task :ocr-core:classes UP-TO-DATE
> Task :ocr-mlkit:mergeDebugAndroidTestAssets
> Task :ocr-core:jar
> Task :ocr-mlkit:desugarDebugAndroidTestFileDependencies
> Task :ocr-mlkit:checkDebugAndroidTestDuplicateClasses
> Task :ocr-mlkit:compressDebugAndroidTestAssets
> Task :ocr-mlkit:mergeDebugAndroidTestResources
> Task :ocr-mlkit:mergeDebugJniLibFolders
> Task :ocr-mlkit:mergeDebugNativeLibs NO-SOURCE
> Task :ocr-mlkit:copyDebugJniLibsProjectOnly
> Task :ocr-mlkit:mergeDebugAndroidTestJniLibFolders
> Task :ocr-mlkit:validateSigningDebugAndroidTest
> Task :ocr-mlkit:writeDebugAndroidTestSigningConfigVersions
> Task :ocr-mlkit:mergeDebugAndroidTestNativeLibs

> Task :ocr-mlkit:stripDebugAndroidTestDebugSymbols
Unable to strip the following libraries, packaging them as they are: libmlkit_google_ocr_pipeline.so. Run with --info option to learn more.

> Task :ocr-mlkit:compileDebugKotlin
> Task :ocr-mlkit:compileDebugJavaWithJavac NO-SOURCE
> Task :ocr-mlkit:bundleLibCompileToJarDebug
> Task :ocr-mlkit:bundleLibRuntimeToJarDebug
> Task :ocr-mlkit:processDebugJavaRes
> Task :ocr-mlkit:bundleLibRuntimeToDirDebug
> Task :ocr-mlkit:mergeLibDexDebugAndroidTest
> Task :ocr-mlkit:processDebugAndroidTestResources
> Task :ocr-mlkit:compileDebugAndroidTestKotlin
> Task :ocr-mlkit:compileDebugAndroidTestJavaWithJavac NO-SOURCE
> Task :ocr-mlkit:dexBuilderDebugAndroidTest
> Task :ocr-mlkit:processDebugAndroidTestJavaRes
> Task :ocr-mlkit:mergeDebugAndroidTestGlobalSynthetics
> Task :ocr-mlkit:mergeProjectDexDebugAndroidTest
> Task :ocr-mlkit:mergeExtDexDebugAndroidTest
> Task :ocr-mlkit:mergeDebugAndroidTestJavaResource
> Task :ocr-mlkit:packageDebugAndroidTest
> Task :ocr-mlkit:createDebugAndroidTestApkListingFileRedirect
> Task :ocr-mlkit:connectedDebugAndroidTest
Starting 2 tests on Pixel_10_Pro(AVD) - 17

id.dotcode.braille.ocr.mlkit.OcrAccuracyHarnessTest > measuresCharacterAndWordErrorRateAgainstSampleWorksheets[Pixel_10_Pro(AVD) - 17] SKIPPED 

Finished 3 tests on Pixel_10_Pro(AVD) - 17

BUILD SUCCESSFUL in 59s
53 actionable tasks: 53 executed
```

`OcrEngineInstrumentedTest.recognizesSyntheticWorksheetAndProducesStructuredDocument`
passed on the device; `OcrAccuracyHarnessTest` skipped, which is the correct outcome with
no samples present. Both genuinely executed on the `Pixel_10_Pro` AVD (Android 17), not on
a stub.
