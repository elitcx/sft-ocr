# BRaiLLE OCR MVP — Design

**Date:** 2026-08-23
**Status:** Approved for planning
**Scope:** The OCR half of the BRaiLLE pipeline. Camera image in, structured document tree out.

## 1. Purpose

BRaiLLE converts printed Indonesian classroom material into braille and audio on a
Samsung Galaxy device, fully offline. This MVP builds the first stage of that pipeline:
read a photo taken with a phone camera, run Google ML Kit Text Recognition v2 on-device,
and emit a structured document that preserves layout formatting.

The downstream Braille Indonesia Conversion Engine needs more than a flat string. It needs
to know where a question starts, which text is a heading, what the reading order is, and
where line breaks fall — because capital indicators, number indicators and per-question
answer tracking all derive from that structure. Producing that structure is this MVP's job.

### Success criteria

1. A photographed worksheet yields a document tree whose reading order matches what a
   sighted reader would follow, including two-column layouts.
2. Numbered questions are individually addressable, each with its marker separated from
   its text.
3. End-to-end latency under 1 second on mid-range hardware for a single worksheet page,
   measured and reported by the code itself.
4. The structuring pipeline is verified by JVM unit tests that run without an emulator.
5. Character error rate is measurable on demand against a folder of sample images.

### Non-goals

Braille conversion, text-to-speech, BLE transport, speech recognition, and the teacher
dashboard are all out of scope. So is handwriting recognition and any model fine-tuning;
this MVP uses ML Kit as shipped, matching Fase 1 of the concept paper.

## 2. Module architecture

Three Gradle modules. The split exists so the algorithmic work is testable on a desktop
JVM and so the OCR provider can be replaced without touching anything downstream.

| Module | Type | Responsibility |
| --- | --- | --- |
| `:ocr-core` | Kotlin/JVM | Document model and the full structuring pipeline. Input is `RawTextResult`, a provider-agnostic geometry record. No Android dependencies. |
| `:ocr-mlkit` | Android library | Image preprocessing, `TextRecognizer` lifecycle, and translation of `com.google.mlkit.vision.text.Text` into `RawTextResult`. |
| `:app` | Android app | Compose demo. CameraX still capture, gallery import, result rendering, JSON export, timing HUD. |

Dependency direction is strictly `:app` → `:ocr-mlkit` → `:ocr-core`. `:ocr-core` depends on
nothing.

Two consequences matter. First, the Braille engine will consume `:ocr-core`'s document tree
and never learn that ML Kit exists. Second, the Fase 2 move to a fine-tuned
EasyOCR or Tesseract model becomes a second adapter module implementing the same
`RawTextResult` contract, not a rewrite.

### The provider boundary

```kotlin
// :ocr-core — what any OCR provider must produce
data class RawTextResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val lines: List<RawLine>,
)

data class RawLine(
    val text: String,
    val box: BoxF,                    // axis-aligned, image pixel space
    val cornerPoints: List<PointF>,   // for skew estimation
    val angleDeg: Float,
    val recognizedLanguage: String?,
    val confidence: Float?,
    val words: List<RawWord>,
)

data class RawWord(
    val text: String,
    val box: BoxF,
    val confidence: Float?,
)
```

ML Kit's own block grouping is deliberately discarded at this boundary. Its blocks are
geometric clusters, not semantic units, and re-deriving them inside `:ocr-core` under rules
we control produces better paragraph boundaries than trying to patch ML Kit's.

## 3. The structuring pipeline

Six deterministic stages run in order inside `:ocr-core`. Each is a separate class with its
own tests. Nothing here is probabilistic; braille output demands reproducibility.

### 3.1 SkewEstimator

Takes the median of per-line angles derived from corner points. If the magnitude exceeds a
small threshold, rotates every coordinate into upright page space so downstream stages can
assume axis-aligned boxes. The estimated angle is preserved on the document as `skewDeg`.

Median rather than mean: a single badly-recognized line at a wild angle should not tilt the
whole page.

### 3.2 ColumnSegmenter

Builds a vertical projection profile over line x-ranges. A gutter wider than
`COLUMN_GUTTER_FACTOR` times the median character width splits the page into columns.
Single-column pages fall out of this naturally as one column spanning the full width.

This stage exists because a two-column worksheet processed without it interleaves left and
right columns line by line, producing text that is not merely misformatted but semantically
scrambled.

### 3.3 ReadingOrderSorter

Sorts column-major, then top-to-bottom within each column. Lines whose vertical centers
fall within a y-band tolerance of each other are treated as the same visual row and ordered
left to right, which keeps slightly-off-baseline lines from jumping position.

### 3.4 LineMerger

Groups lines into blocks. Two consecutive lines merge when all of the following hold:

- they belong to the same column
- the vertical gap between them is at most `PARAGRAPH_GAP_FACTOR` times the median line height
- their left edges align, or the first shows a consistent first-line indent
- the earlier line does not end in terminal punctuation

A trailing hyphen on the earlier line joins the two without an intervening space. Original
line breaks are retained on the block's `lines` list even after reflow, since the braille
renderer may want them.

### 3.5 MarkerParser

Matches list and question markers at line start:

| Pattern | Example |
| --- | --- |
| `^(\d{1,3})[.)]\s` | `1.` `12)` |
| `^([a-z])[.)]\s` | `a.` `c)` |
| `^([ivxIVX]+)[.)]\s` | `iii.` `IV)` |
| `^([•\-*])\s` | `•` `-` |

The marker moves into the block's `marker` field and is stripped from `text`. This is what
makes "jawaban per nomor soal" possible downstream: question 7 is an addressable object,
not a substring the answer recorder has to hunt for.

### 3.6 RoleClassifier

Assigns a `BlockRole` from four signals: relative text height against the body median,
all-caps ratio, position on the page, and marker presence.

| Role | Primary signal |
| --- | --- |
| `TITLE` | relative height > 1.6, or > 1.25 and in the top page band |
| `HEADING` | relative height > 1.25 |
| `QUESTION` | has a numeric marker, and the page holds at least three such blocks |
| `LIST_ITEM` | has any other marker |
| `PAGE_NUMBER` | bottom page band, numeric-only, single line |
| `CAPTION` | relative height < 0.85 |
| `PARAGRAPH` | default |

The "at least three numbered blocks" guard on `QUESTION` prevents a lone numbered list item
in a prose passage from being mistaken for an exam question.

## 4. Output model

```kotlin
data class OcrDocument(
    val pageWidth: Int,
    val pageHeight: Int,
    val skewDeg: Float,
    val columnCount: Int,
    val blocks: List<TextBlock>,     // already in reading order
    val meanConfidence: Float?,
    val timings: Timings,
)

data class TextBlock(
    val id: Int,
    val role: BlockRole,
    val columnIndex: Int,
    val marker: String?,
    val indentLevel: Int,            // quantized left edge, 0-based
    val alignment: Alignment,        // LEFT, CENTER, RIGHT
    val relativeTextHeight: Float,   // 1.0 == body text
    val text: String,                // reflowed, marker stripped
    val lines: List<TextLine>,       // original breaks retained
    val box: BoxF,
    val confidence: Float?,
)

data class Timings(
    val decodeMs: Long,
    val preprocessMs: Long,
    val recognizeMs: Long,
    val structureMs: Long,
) { val totalMs: Long get() = decodeMs + preprocessMs + recognizeMs + structureMs }
```

The document serializes to JSON via kotlinx.serialization, which is both the demo app's
export format and the fixture format for tests.

### What "formatting" covers, and what it does not

Preserved: columns, reading order, line breaks, indentation level, relative text size,
alignment, list and question markers, and semantic role.

Not preserved: bold, italic, underline, font family, and color. ML Kit Text Recognition v2
does not report these, and no on-device OCR engine currently does. The preserved set is the
one the Braille Indonesia engine consumes, so this limitation does not block the pipeline —
but it is a real limitation and should not be described otherwise in the proposal.

## 5. Performance

Budget: under 1 second end-to-end on mid-range hardware, against the concept paper's stated
3-second target.

| Decision | Rationale |
| --- | --- |
| Downscale long edge to ~1600 px before recognition | Full-resolution capture costs time without improving accuracy. Below roughly 1000 px, small print begins to fail. |
| Pass EXIF orientation as `InputImage.rotationDegrees` | Avoids a full-frame bitmap copy just to rotate. |
| One long-lived `TextRecognizer` instance | Construction dominates; recognition on a warm instance is cheap. |
| Structuring is O(n log n) over lines | Single-digit milliseconds for a worksheet-sized page. |

Every stage records its duration into `Timings`, and the demo app displays them. Performance
is therefore a measured number visible on screen rather than an assertion.

## 6. Failure handling

A blur and contrast check runs on the downscaled grayscale image before recognition is
attempted. Variance of a Laplacian-style gradient below threshold means blurry; a
compressed luminance histogram means too dark.

```kotlin
sealed interface OcrResult {
    data class Success(val document: OcrDocument) : OcrResult
    data class Failure(val reason: FailureReason) : OcrResult
}

enum class FailureReason { TooBlurry, TooDark, NoTextFound, ModelUnavailable, Cancelled }
```

This pre-check matters more here than in a typical OCR app. A blind student reading garbage
braille under their fingers has no way to notice that the recognition failed. Refusing a bad
capture with actionable feedback is safer than producing confident nonsense.

## 7. Testing strategy

**`:ocr-core` — JVM unit tests, TDD.** Golden `RawTextResult` JSON fixtures drive assertions
about the resulting document. Fixture set covers: single column prose, two-column worksheet,
numbered question sheet, skewed capture, heading hierarchy, hyphenated line wrap, and an
empty page. These run in seconds without an emulator, which is why the pipeline lives in a
pure module.

**`:ocr-mlkit` — instrumented test.** One test asserting that a bundled sample image
produces a non-empty `RawTextResult` with plausible geometry. Verifies the adapter and the
bundled model, not the algorithm.

**`:app` — manual verification** on the existing `Pixel_10_Pro` AVD plus gallery import.

**Accuracy harness.** A runnable task that takes a directory of images paired with expected
`.txt` files and reports character and word error rate per image and in aggregate. This
operationalizes the concept paper's ">90% accuracy across 50 SLB-A worksheets" claim: once
the samples are collected, verifying it is one command. Ships with a small set of generated
worksheets so it is runnable before real samples exist.

## 8. Build environment

Android SDK 35 and 36.1, build-tools 37.0.0, and a `Pixel_10_Pro` AVD are already installed.

Java 26 is on `PATH` and current Android Gradle Plugin will reject it. The build pins
`org.gradle.java.home` to Android Studio's bundled JDK 21 in `gradle.properties`, so the
project builds identically from the command line and from Studio.

Dependencies: CameraX, ML Kit `text-recognition` (bundled model, not Play-Services-delivered,
so the app works offline on first run), Compose, and kotlinx.serialization.

The bundled-model choice is deliberate. A Play Services model downloads on first use, which
fails in exactly the 3T-region schools the project targets.

## 9. Open items for later phases

- Handwriting and whiteboard text via a fine-tuned model (Fase 2 per the concept paper).
- Live-preview capture as an accuracy-neutral UX improvement over still capture.
- Table structure recognition, which ML Kit does not provide and which would need its own
  line-detection pass.
