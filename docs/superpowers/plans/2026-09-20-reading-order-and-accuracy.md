# Reading Order and Accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut app-level word error by roughly a third by making reading order correct on curved pages, then measure every remaining lever against human-verified ground truth.

**Architecture:** The dominant error is not recognition — it is assembly. `WordRowRebuilder` already fixes it (23.16% → 0.74% CER on the target page) but cannot be enabled, because rebuilding rows destroys the "short line at the frame edge" signal `FrameEdgeFragmentFilter` uses to reject an intruding facing page. The fix is to make facing-page rejection operate on **words** instead of lines, so it no longer depends on a grouping we are about to replace. Once that dependency is gone the rebuilder turns on safely, and the remaining levers (Gemini, ground truth) can be measured on a stable base.

**Tech Stack:** Kotlin, Gradle (AGP), JUnit5 + kotlin.test, Android instrumented tests, Python 3 for off-device analysis, Tesseract 5.4 CLI.

**Spec:** `docs/accuracy-and-training-report-2026-09-20.md` — every number quoted here comes from that report's measurements.

## Global Constraints

- **JDK:** Android Studio JBR 21 (`JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`). System Java 26 makes Gradle fail with a bare `26.0.1`.
- **Unit-test baseline:** 328 run / 324 passed / 0 failed / 4 skipped. No task may reduce passing count.
- **Device runs:** use `adb shell am instrument`, never `./gradlew connectedAndroidTest` — the Gradle task uninstalls the APK afterwards and deletes the results with it.
- **Emulator:** `Pixel_10_Pro` AVD, `hw.ramSize=4096`. 2048 crashes instrumentation on larger models.
- **Never commit:** `gemini_key.txt`, `artifacts/`. Both are already in `.gitignore`.
- **Accuracy metric:** corpus CER/WER weighted by reference length, matching `ErrorRate.kt`. Never quote `lstmeval`'s BCER as an app accuracy figure — different metric, different units.
- **Provenance:** any transcript used as ground truth must have `transcriptionSource: HUMAN_CORRECTED` in its `meta.json`. `AI_TRANSCRIBED` is a starting point, not truth.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `ocr-core/src/main/kotlin/.../pipeline/FrameGeometry.kt` | **Create.** Single definition of the upright frame width. |
| `ocr-core/src/main/kotlin/.../pipeline/FrameEdgeFragmentFilter.kt` | **Modify.** Add a word-level entry point beside the existing line-level one. |
| `ocr-core/src/main/kotlin/.../pipeline/WordRowRebuilder.kt` | **Modify.** Drop edge words before chaining; add gutter safety. |
| `ocr-core/src/main/kotlin/.../pipeline/DocumentStructurer.kt` | **Modify.** Pass rotation into the rebuild; flip the default. |
| `ocr-core/src/main/kotlin/.../pipeline/StructuringConfig.kt` | **Modify.** Flip `rebuildRowsFromWords` default. |
| `tools/ocr_eval/compare_dumps.py` | **Create.** Before/after comparison, moved out of scratch. |
| `tools/ocr_eval/gemini_eval.py` | **Create.** Gemini measurement, moved out of scratch. |
| `tools/ocr_eval/README.md` | **Create.** How to run a full measurement cycle. |
| `app/src/main/kotlin/.../GeminiCorrector.kt` | **Modify.** Constrained reordering; drop the harmful fallback. |

---

## Task 1: Move the measurement tooling into the repo

Nothing else in this plan is verifiable without it, and it currently lives in a session scratch directory that will be deleted.

**Files:**
- Create: `tools/ocr_eval/compare_dumps.py`
- Create: `tools/ocr_eval/gemini_eval.py`
- Create: `tools/ocr_eval/README.md`

**Interfaces:**
- Produces: `python tools/ocr_eval/compare_dumps.py <before.json> <after.json>` printing a per-page CER/WER table and a corpus row; `python tools/ocr_eval/gemini_eval.py --dump <dump.json>` printing offline-vs-Gemini CER/WER.

- [ ] **Step 1: Create the directory and copy both scripts**

The working versions are attached to the report as `artifacts/`-adjacent scratch files. If they are gone, both are ~120 lines and fully specified by their docstrings in `docs/accuracy-methods-2026-09-20.md`. `compare_dumps.py` must implement, exactly:

```python
def norm(t):
    return " ".join(t.split())

def lev(a, b):
    if not a: return len(b)
    if not b: return len(a)
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j-1] + (a[i-1] != b[j-1]), prev[j] + 1, cur[j-1] + 1)
        prev = cur
    return prev[len(b)]

def cer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    return lev(list(r), list(h)) / max(len(r), 1)
```

This mirrors `ErrorRate.kt` exactly — trim, collapse whitespace, Levenshtein over characters, divide by reference length. Do not substitute a library; a different normalization silently changes every number in the report.

- [ ] **Step 2: Add the order-free WER helper**

This is what separates an ordering defect from a recognition defect and is used as an acceptance check in Task 5.

```python
from collections import Counter

def orderfree_wer(ref, hyp):
    """WER with word order ignored: multiset difference over reference length."""
    r, h = norm(ref).split(), norm(hyp).split()
    cr, ch = Counter(r), Counter(h)
    return (sum((cr - ch).values()) + sum((ch - cr).values())) / 2 / max(len(r), 1)
```

- [ ] **Step 3: Write the README**

It must state: the JDK requirement, the `am instrument` rule, where dumps come from (`OcrTextDumpHarnessTest`), and that `gemini_key.txt` is read from the repo root and never logged.

- [ ] **Step 4: Verify both scripts run**

```bash
python tools/ocr_eval/compare_dumps.py artifacts/app-text-dump.json artifacts/app-text-dump.json
```

Expected: every delta `+0.00`, corpus row identical. A tool that cannot reproduce zero difference against itself is broken.

- [ ] **Step 5: Commit**

```bash
git add tools/ocr_eval
git commit -m "chore: move OCR accuracy tooling into the repo"
```

---

## Task 2: Define the upright frame width once

Two failed integration attempts traced to the same ambiguity: `RawTextResult.imageWidth/imageHeight` describe the **unrotated** bitmap, while ML Kit's word boxes come back already in reading orientation, because `OcrEngine` passes rotation into `InputImage.fromBitmap(scaled, rotation)`. On the curved fixture the frame is 1600×1200 but word x never exceeds 1117 — the real upright width is 1200.

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/FrameGeometry.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/FrameGeometryTest.kt`

**Interfaces:**
- Produces: `FrameGeometry.uprightWidth(raw: RawTextResult, rotationDegrees: Int): Int`

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameGeometryTest {

    private fun frame(w: Int, h: Int) = RawTextResult(imageWidth = w, imageHeight = h, lines = emptyList())

    @Test
    fun `a quarter turn swaps which dimension bounds reading direction`() {
        assertEquals(1200, FrameGeometry.uprightWidth(frame(1600, 1200), 90))
        assertEquals(1200, FrameGeometry.uprightWidth(frame(1600, 1200), 270))
    }

    @Test
    fun `an upright or inverted frame keeps its own width`() {
        assertEquals(1600, FrameGeometry.uprightWidth(frame(1600, 1200), 0))
        assertEquals(1600, FrameGeometry.uprightWidth(frame(1600, 1200), 180))
    }

    @Test
    fun `a negative or over-full rotation normalises`() {
        assertEquals(1200, FrameGeometry.uprightWidth(frame(1600, 1200), -90))
        assertEquals(1200, FrameGeometry.uprightWidth(frame(1600, 1200), 450))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test --tests "*FrameGeometryTest*"
```

Expected: FAIL, unresolved reference `FrameGeometry`.

- [ ] **Step 3: Implement**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawTextResult

/**
 * The width of the frame as the READER sees it, which is not always
 * [RawTextResult.imageWidth].
 *
 * The recognizer is handed the rotation up front, so its boxes come back in reading
 * orientation while the recorded frame size still describes the unrotated bitmap. On a
 * quarter-turn capture those disagree: the curved handbook fixture records 1600x1200 but
 * no word box exceeds x=1117, because the upright width is the recorded HEIGHT.
 *
 * Every stage that compares a box against "the edge of the page" must go through here.
 * Getting this wrong does not throw - it silently mis-classifies which text is at the
 * frame edge, which is how an intruding facing page survives into the output.
 */
object FrameGeometry {

    fun uprightWidth(raw: RawTextResult, rotationDegrees: Int): Int {
        val turns = ((rotationDegrees % 360) + 360) % 360
        return if (turns == 90 || turns == 270) raw.imageHeight else raw.imageWidth
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test --tests "*FrameGeometryTest*"
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/FrameGeometry.kt ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/FrameGeometryTest.kt
git commit -m "feat(pipeline): single definition of the upright frame width"
```

---

## Task 3: Reject facing-page fragments at word level

This is the task that unblocks everything. `FrameEdgeFragmentFilter.filter` classifies whole **lines**; once rows are rebuilt those lines no longer exist, so the facing page survives. A word-level pass does the same job on a unit the rebuilder does not change.

**Files:**
- Modify: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/FrameEdgeFragmentFilter.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/FrameEdgeWordFilterTest.kt`

**Interfaces:**
- Consumes: `FrameGeometry.uprightWidth` from Task 2.
- Produces: `FrameEdgeFragmentFilter.edgeWords(lines: List<RawLine>, frameWidth: Int, config: StructuringConfig): Set<RawWord>` — the words that belong to an intruding surface. Identity-based set; callers filter with `!in`.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameEdgeWordFilterTest {

    private fun word(text: String, left: Float, right: Float) =
        RawWord(text = text, box = BoxF(left, 100f, right, 130f), confidence = 0.9f)

    private fun line(vararg words: RawWord): RawLine {
        val box = BoxF(words.minOf { it.box.left }, 100f, words.maxOf { it.box.right }, 130f)
        return RawLine(text = words.joinToString(" ") { it.text }, box = box,
            confidence = 0.9f, words = words.toList())
    }

    /** Frame is 1000 wide; margin = 15px, a fragment must be under 350px to qualify. */
    private val config = StructuringConfig()

    @Test
    fun `a stack of cut-off fragments at the right edge is rejected`() {
        val body = (1..4).map { line(word("kalimat", 100f, 700f)) }
        val fragments = (1..3).map { line(word("menyet", 960f, 998f)) }
        val edge = FrameEdgeFragmentFilter.edgeWords(body + fragments, 1000, config)
        assertEquals(3, edge.size, "the three cut-off fragments must be rejected")
        assertTrue(edge.all { it.text == "menyet" }, "no body word may be rejected")
    }

    @Test
    fun `a full-width line touching the edge is kept`() {
        val lines = (1..4).map { line(word("judul-yang-panjang-sekali", 10f, 990f)) }
        assertTrue(
            FrameEdgeFragmentFilter.edgeWords(lines, 1000, config).isEmpty(),
            "a tightly cropped page has full-width lines at the edge; they are real text",
        )
    }

    @Test
    fun `a lone confident short line at the edge is kept`() {
        val lines = listOf(
            line(word("kalimat", 100f, 700f)),
            line(word("penuh", 100f, 700f)),
            line(RawWord("akhir", BoxF(960f, 100f, 998f, 130f), confidence = 0.95f)),
        )
        assertTrue(
            FrameEdgeFragmentFilter.edgeWords(lines, 1000, config).isEmpty(),
            "one confident short line can legitimately end at the margin",
        )
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test --tests "*FrameEdgeWordFilterTest*"
```

Expected: FAIL, unresolved reference `edgeWords`.

- [ ] **Step 3: Implement, reusing the existing thresholds**

Add to `FrameEdgeFragmentFilter`, leaving `filter` untouched so the current pipeline is unaffected:

```kotlin
    /**
     * The same judgement as [filter], expressed over words instead of lines.
     *
     * [WordRowRebuilder] replaces the recognizer's line grouping, which destroys the
     * "short line at the frame edge" signal [filter] depends on - measured, that let 469
     * characters of facing-page text through on an open-book spread. Deciding per word,
     * before any regrouping, keeps the judgement independent of a grouping we are about
     * to discard.
     *
     * Identity semantics: the returned set is compared by reference, so callers must
     * filter the SAME [RawWord] instances that were passed in.
     */
    fun edgeWords(
        lines: List<RawLine>,
        frameWidth: Int,
        config: StructuringConfig,
    ): Set<RawWord> {
        if (frameWidth <= 0 || lines.size < 2) return emptySet()
        val margin = frameWidth * config.frameEdgeMarginFraction
        val maxWidth = frameWidth * config.frameEdgeMaxWidthFraction

        fun sideOf(line: RawLine): Side? = when {
            line.box.width >= maxWidth -> null
            line.box.left < margin -> Side.LEFT
            line.box.right > frameWidth - margin -> Side.RIGHT
            else -> null
        }

        val stacks = lines.mapNotNull(::sideOf).groupingBy { it }.eachCount()
        val rejected = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RawWord, Boolean>())
        for (line in lines) {
            val side = sideOf(line) ?: continue
            val stacked = (stacks[side] ?: 0) >= config.frameEdgeMinStack
            val unsure = (line.confidence ?: 1f) < config.frameEdgeLoneMaxConfidence
            if (stacked || unsure) rejected += line.words
        }
        // Same guard as filter(): a page that is nothing but edge fragments is better
        // read badly than not at all.
        val total = lines.sumOf { it.words.size }
        return if (rejected.size >= total) emptySet() else rejected
    }
```

- [ ] **Step 4: Run and confirm it passes**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test --tests "*FrameEdgeWordFilterTest*"
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Run the whole suite — nothing may regress**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 331 run / 327 passed / 0 failed / 4 skipped.

- [ ] **Step 6: Commit**

```bash
git add ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/FrameEdgeFragmentFilter.kt ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/FrameEdgeWordFilterTest.kt
git commit -m "feat(pipeline): word-level facing-page rejection"
```

---

## Task 4: Teach the rebuilder to skip edge words

**Files:**
- Modify: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/WordRowRebuilder.kt`
- Modify: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurer.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/CurvedPageWordOrderTest.kt`

**Interfaces:**
- Consumes: `FrameEdgeFragmentFilter.edgeWords` (Task 3), `FrameGeometry.uprightWidth` (Task 2).
- Produces: `WordRowRebuilder.rebuild(raw: RawTextResult, config: StructuringConfig, rotationDegrees: Int): RawTextResult` — note the new third parameter.

- [ ] **Step 1: Write the failing test**

Add to `CurvedPageWordOrderTest`:

```kotlin
    @Test
    fun `an intruding facing page is not absorbed into a rebuilt row`() {
        val text = structure("real-facing-page-231108-raw.json")
        // The facing page's cut-off fragments must not appear. Before word-level edge
        // rejection, rebuilding pulled them into long rows and they survived.
        assertFalse(
            text.contains("menyet") || text.contains("mengusap r"),
            "facing-page fragments leaked into the rebuilt rows.\nGot: ${text.take(240)}",
        )
    }
```

Add the import `kotlin.test.assertFalse`.

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test --tests "*CurvedPageWordOrderTest*"
```

Expected: the new test FAILS (or the signature change breaks compilation first — fix that, then confirm this test is red before proceeding).

- [ ] **Step 3: Thread rotation through and drop edge words**

In `WordRowRebuilder.rebuild`, change the signature and filter the pool:

```kotlin
    fun rebuild(
        raw: RawTextResult,
        config: StructuringConfig,
        rotationDegrees: Int,
    ): RawTextResult {
        val withWords = raw.lines.filter { it.words.isNotEmpty() }
        val withoutWords = raw.lines.filter { it.words.isEmpty() }

        // Decide which words belong to an intruding surface BEFORE regrouping, while the
        // recognizer's line boxes still carry the evidence.
        val frameWidth = FrameGeometry.uprightWidth(raw, rotationDegrees)
        val edge = FrameEdgeFragmentFilter.edgeWords(withWords, frameWidth, config)

        val words = withWords.flatMap { line -> line.words.mapNotNull { if (it in edge) null else it to line } }
        if (words.size < 2) return raw
```

Leave the rest of the function unchanged. Because the edge words never enter the pool, the rows built from it cannot contain them, and the lines they came from are rebuilt without them — so the downstream `filter` call still sees nothing to keep.

In `DocumentStructurer.structure`, pass the rotation:

```kotlin
        val regrouped =
            if (config.rebuildRowsFromWords) WordRowRebuilder.rebuild(raw, config, rotationDegrees) else raw
```

- [ ] **Step 4: Run the curved-page tests**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test --tests "*CurvedPageWordOrderTest*"
```

Expected: PASS, 3 tests — the two ordering assertions and the new facing-page one.

- [ ] **Step 5: Run the whole suite**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 0 failed. The flag is still `false`, so only the opted-in tests exercise the new path.

- [ ] **Step 6: Commit**

```bash
git add ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/WordRowRebuilder.kt ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurer.kt ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/CurvedPageWordOrderTest.kt
git commit -m "fix(pipeline): keep facing-page fragments out of rebuilt rows"
```

---

## Task 5: Turn the rebuilder on and prove it on device

**Files:**
- Modify: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/StructuringConfig.kt`

- [ ] **Step 1: Flip the default**

```kotlin
    val rebuildRowsFromWords: Boolean = true,
```

Update the KDoc: it currently explains why the stage ships off. Replace that paragraph with the measured result from Step 5 of this task.

- [ ] **Step 2: Run the whole suite**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 0 failed. If any of the seven previously-fragile tests fail here — `a real 90-degree EXIF capture deskews to near-zero`, `a real curved single page loses no lines`, `isolated low-confidence margin fragments`, `an open book's facing page is discarded`, `golden end-to-end fixture`, `a real EXIF-3 capture`, `real captures get exactly the reviewed corrections` — **stop**. Do not re-baseline them. Their failure means the rebuilder is still changing pages it should not, and the cross-chain gate needs tightening instead.

- [ ] **Step 3: Bundle the diagnostic pages and build the test APK**

```bash
cp "/c/Users/Kiel/Downloads/braille testing/20260731_230858.jpg" ocr-mlkit/src/androidTest/assets/ground-truth/
cp braille-ocr/dataset/ai-transcribed/20260731_230858.txt ocr-mlkit/src/androidTest/assets/ground-truth/
```

Repeat for `20260919_223954`, `20260919_222813`, `20260919_222952`, `20260919_224138`, `20260919_224351`, `20260919_223105`, `20260731_230849`, `20260731_230855`, `IMG_5720`. Then:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-mlkit:assembleDebugAndroidTest
```

- [ ] **Step 4: Install and run the dump harness**

```bash
adb install -r -t ocr-mlkit/build/outputs/apk/androidTest/debug/ocr-mlkit-debug-androidTest.apk
```

```bash
adb shell am instrument -w -e class id.dotcode.braille.ocr.mlkit.OcrTextDumpHarnessTest id.dotcode.braille.ocr.mlkit.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb pull /storage/emulated/0/Android/media/id.dotcode.braille.ocr.mlkit.test/text-dump/dump.json after.json
```

- [ ] **Step 5: Compare against the committed baseline**

```bash
python tools/ocr_eval/compare_dumps.py artifacts/app-text-dump.json after.json
```

**Acceptance criteria — all four must hold:**

1. Corpus CER **at or below 14.5%** (baseline 18.49%).
2. `20260731_230858` at or below **1.5%** (baseline 23.16%).
3. `20260919_223954` **no worse than 39%** (baseline 38.76%) — this is the page the previous attempt broke to 67.65%.
4. No page regresses by more than 1 pp.

If criterion 3 fails, word-level edge rejection is not catching that page's facing fragments; return to Task 3 and check `frameEdgeMinStack` against that capture's actual fragment count rather than loosening the acceptance bar.

- [ ] **Step 6: Restore the bundled assets and commit**

```bash
git checkout -- ocr-mlkit/src/androidTest/assets/ground-truth/
git clean -fd ocr-mlkit/src/androidTest/assets/ground-truth/
```

```bash
git add ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/StructuringConfig.kt
git commit -m "feat(pipeline): rebuild rows from words by default"
```

---

## Task 6: Guard the column gutter with a regression test

The rebuilder must never weld two columns into one row. Nothing currently proves this beyond `wordRowMaxGapCharWidths = 6.0`, and the two-column fixtures have no word boxes, so they do not exercise the new path at all.

**Files:**
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/WordRowRebuilderGutterTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.raw.RawWord
import kotlin.test.Test
import kotlin.test.assertTrue

class WordRowRebuilderGutterTest {

    private fun word(text: String, left: Float, top: Float) =
        RawWord(text, BoxF(left, top, left + text.length * 10f, top + 30f), confidence = 0.9f)

    private fun line(vararg w: RawWord) = RawLine(
        text = w.joinToString(" ") { it.text },
        box = BoxF(w.minOf { it.box.left }, w.minOf { it.box.top },
                   w.maxOf { it.box.right }, w.maxOf { it.box.bottom }),
        confidence = 0.9f, words = w.toList(),
    )

    @Test
    fun `two columns are never welded into one row`() {
        // Left column ends near x=380; right column starts at x=700. A 320px gutter is far
        // wider than any word space at this 10px character width.
        val lines = (0..5).map { r ->
            val y = 100f + r * 45f
            line(word("kiri", 40f, y), word("teks", 120f, y),
                 word("kanan", 700f, y), word("lain", 800f, y))
        }
        val cross = RawTextResult(1000, 400, lines)
        val out = WordRowRebuilder.rebuild(cross, StructuringConfig(), rotationDegrees = 0)
        assertTrue(
            out.lines.none { it.text.contains("teks") && it.text.contains("kanan") },
            "a row spanned the gutter: ${out.lines.map { it.text }}",
        )
    }
}
```

- [ ] **Step 2: Run it**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-core:test --tests "*WordRowRebuilderGutterTest*"
```

Expected: PASS. If it fails, the gutter is being crossed — lower `wordRowMaxGapCharWidths` and re-run Task 5's acceptance criteria, because that constant also governs contents-page leaders.

- [ ] **Step 3: Commit**

```bash
git add ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/WordRowRebuilderGutterTest.kt
git commit -m "test(pipeline): pin column-gutter safety for row rebuilding"
```

---

## Task 7: Let Gemini repair order, under a hard constraint

Measured, the Gemini pass cuts CER 16.56% → 14.19% (−14.3% relative) — about 30× the offline layer. But its prompt says *"Never rephrase, translate, summarize, reorder"*, so it cannot touch the dominant error. On `20260919_222813` it drove CER to 16.10% and stopped almost exactly at that page's order-free floor of 12.2%.

This task is **gated on measurement** and must be reverted if it fails. Reordering is the one instruction relaxation that risks fabrication, so the constraint is that the words must be preserved exactly.

**Files:**
- Modify: `app/src/main/kotlin/id/dotcode/braille/ocr/app/GeminiCorrector.kt`

- [ ] **Step 1: Amend the instruction**

Replace the `Never rephrase, translate, summarize, reorder, add or remove content.` sentence with:

```
Never rephrase, translate, summarize, add or remove content. You may reorder words
within a block, and only when the recognizer clearly emitted them out of order - a
sentence whose words are shuffled. When you reorder, the block must keep exactly the
same words: do not add, drop, merge or split any word to make a sentence read better.
If you cannot restore the order with the words given, leave the block unchanged.
```

- [ ] **Step 2: Measure before changing anything else**

```bash
python tools/ocr_eval/gemini_eval.py --dump after.json --out gemini_reorder.json
```

Compare against `artifacts/gemini-correction-results.json`.

**Acceptance criteria:**
1. Corpus CER strictly better than the 14.19% already recorded.
2. No page's word multiset changes by more than 5% — check with `orderfree_wer` from Task 1. A jump here means Gemini invented or dropped words, which is worse than bad order.

- [ ] **Step 3: If criterion 2 fails, revert**

```bash
git checkout -- app/src/main/kotlin/id/dotcode/braille/ocr/app/GeminiCorrector.kt
```

Record the negative result in the report. A correction pass that fabricates text is unacceptable for a reader who cannot see the page.

- [ ] **Step 4: Remove the harmful fallback**

Measured, `gemini-3.5-flash-lite` gave CER 14.54% → 14.90% across three pages: 0 improved, 1 unchanged, 2 made worse. Silently degrading output is worse than skipping the pass.

```kotlin
    val FALLBACK_MODELS = listOf("gemini-3.5-flash", "gemini-3.6-flash")
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/id/dotcode/braille/ocr/app/GeminiCorrector.kt
git commit -m "feat(gemini): allow constrained word reordering; drop the lite fallback"
```

---

## Task 8: Build human-verified ground truth

Every number in the report is currently AI-vs-AI: all 37 transcripts are `AI_TRANSCRIBED`. Until this is done, no accuracy figure is defensible, including the improvements from Tasks 5 and 7.

**Files:**
- Modify: `braille-ocr/dataset/ai-transcribed/*.txt` and `*.dataset.json` (25 of them)

- [ ] **Step 1: Pick 25 pages spanning all four capture sessions**

At least 6 from `20260731`, 6 from `20260919`, 4 from `20260920`, 3 `IMG_*`, and all pages currently in the test split. Sessions differ in lighting, camera and book, so a set drawn from one is not a corpus.

- [ ] **Step 2: Correct each against the photo**

Open `<id>.jpg` beside `<id>.txt` and fix every character. Conventions, which must be applied consistently or the CER is meaningless:

- Main page only; ignore a facing page even if legible.
- Reading order as a person reads it.
- Keep printed typos exactly as printed.
- Join hyphenated line breaks into whole words (`ke-` + `mudian` → `kemudian`).
- Contents pages: keep the entry text and the page number, drop the dotted leaders.

- [ ] **Step 3: Flip provenance in each `meta.json`**

```json
"transcriptionSource": "HUMAN_CORRECTED",
```

- [ ] **Step 4: Verify none were missed**

```bash
grep -L HUMAN_CORRECTED braille-ocr/dataset/ai-transcribed/*.dataset.json | head -20
```

Expected: only the files you did not select.

- [ ] **Step 5: Re-run the accuracy harness against verified pages only and commit**

```bash
git add braille-ocr/dataset/ai-transcribed
git commit -m "data: human-verify 25 transcripts"
```

---

## Task 9: Record the final numbers

**Files:**
- Modify: `docs/accuracy-and-training-report-2026-09-20.md`

- [ ] **Step 1: Replace §1 with measurements against human-verified ground truth**

State n, the split, and that pages are `HUMAN_CORRECTED`. Report recognizer-only, +offline, +ordering, +Gemini as four rows so each lever's contribution is separable.

- [ ] **Step 2: Update §4.5.1 with the measured ordering result**

Replace the current "one page −22 pp, one page +29 pp, net zero" table with the Task 5 outcome.

- [ ] **Step 3: Commit**

```bash
git add docs/accuracy-and-training-report-2026-09-20.md
git commit -m "docs: record accuracy after reading-order fix"
```

---

## Expected outcome

| Lever | Status | Measured or expected effect |
| --- | --- | --- |
| Reading order (Tasks 2–6) | fix written, blocked on Task 3 | corpus CER 18.49% → ~14% on the diagnostic set |
| Gemini pass (Task 7) | measured, prompt change untested | −14.3% relative already; more if reordering is permitted |
| Offline correction layer | measured | −0.08 pp CER, −0.41 pp WER |
| Tesseract fine-tune | measured, rejected | 0.00 pp at app level; 8.25 MB cost |
| Human ground truth (Task 8) | not started | makes every figure above defensible |

## What this plan deliberately does not do

- **Replace ML Kit with PaddleOCR.** Four to eight weeks, most of it labelling, and it targets substitutions — which are only 3.6% of reference length. The ordering work addresses 66% of word error for a fraction of the cost.
- **Re-baseline the seven fragile tests.** They encode real decisions about real pages. If the rebuilder changes them, the rebuilder is wrong.
- **Ship the fine-tuned Tesseract model.** Measured at zero app-level effect for 7.4× the model size.
