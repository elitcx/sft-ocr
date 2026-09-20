# OCR accuracy tooling

Scripts for measuring the app's real accuracy and for the Tesseract fine-tuning
experiment. All of them mirror `ocr-core/.../accuracy/ErrorRate.kt` exactly — trim,
collapse whitespace, Levenshtein over characters (CER) and over space-split words (WER),
divided by reference length. **Do not swap in a library metric**: a different
normalization silently changes every number in `docs/accuracy-and-training-report-2026-09-20.md`.

## Prerequisites

| Thing | Value |
| --- | --- |
| JDK | Android Studio JBR 21 — `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` |
| Emulator | `Pixel_10_Pro` AVD with `hw.ramSize=4096` (2048 crashes instrumentation) |
| Tesseract | 5.4.0, `winget install UB-Mannheim.TesseractOCR` (only needed for `ocr_eval.py` / `build_lines.py`) |
| Gemini key | one line in `gemini_key.txt` at the repo root; gitignored, never logged |

## The scripts

| Script | What it does |
| --- | --- |
| `compare_dumps.py` | Per-page CER/WER between two app text dumps, plus how much of the remaining error is ordering |
| `gemini_eval.py` | Sends dumped page text through `GeminiCorrector`'s exact protocol and scores before/after, both raw and **as the app would keep it** |
| `analyze_dump.py` | Error composition of one dump: insertions vs deletions vs substitutions, largest inserted runs |
| `make_splits.py` | Deterministic, session-stratified train/val/test splits over the labelled dataset |
| `ocr_eval.py` | Standalone Tesseract CLI scoring — a **component** measurement, not the app |
| `build_lines.py` | Bootstraps line-level ground truth from page transcripts for Tesseract fine-tuning |
| `consolidate.py` | Rolls the harness logs up into one results table |

## A full measurement cycle

1. Bundle the pages you want scored into `ocr-mlkit/src/androidTest/assets/ground-truth/`
   as `<id>.jpg` + `<id>.txt` pairs.

2. Build and install the test APK:

   ```bash
   JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :ocr-mlkit:assembleDebugAndroidTest
   ```

   ```bash
   adb install -r -t braille-ocr/ocr-mlkit/build/outputs/apk/androidTest/debug/ocr-mlkit-debug-androidTest.apk
   ```

3. Run the dump harness. **Use `am instrument`, not `./gradlew connectedAndroidTest`** —
   the Gradle task uninstalls the APK when it finishes, which deletes the app's media
   directory and the results with it:

   ```bash
   adb shell am instrument -w -e class id.dotcode.braille.ocr.mlkit.OcrTextDumpHarnessTest id.dotcode.braille.ocr.mlkit.test/androidx.test.runner.AndroidJUnitRunner
   ```

4. Pull and compare:

   ```bash
   adb pull /storage/emulated/0/Android/media/id.dotcode.braille.ocr.mlkit.test/text-dump/dump.json after.json
   ```

   ```bash
   python tools/ocr_eval/compare_dumps.py artifacts/app-text-dump.json after.json
   ```

**Always check the scored-page count before trusting an aggregate.** If the harness only
finds the bundled seed pairs it still reports a number, just for four pages.

## Sanity check

```bash
python tools/ocr_eval/compare_dumps.py artifacts/app-text-dump.json artifacts/app-text-dump.json
```

Every delta must be `+0.00`. A tool that cannot reproduce zero difference against itself
is broken.

## Measuring the Gemini pass

```bash
python tools/ocr_eval/gemini_eval.py --dump artifacts/app-text-dump-task5.json --out artifacts/gemini-baseline.json
```

Add `--reorder` to run the prompt variant that permits constrained word reordering.

**Read the `CER app` column, not `CER +Gemini`.** `GeminiProtocol.apply` rejects any block
whose correction rewrites more than `MAX_CHANGE_RATIO` (35%) of its characters, or that
changes a number. Reordering a scrambled sentence routinely exceeds that, so Gemini's raw
output can look like a large win while the app keeps almost none of it. `CER app` applies
that same guard; `CER +Gemini` does not.

The `wordDrift` column is the word-multiset difference between before and after. Reordering
must not add or drop words, so a drift above ~5% means the model invented or lost text —
which for a reader who cannot see the page is worse than bad ordering.

**Quota.** Roughly 14 requests exhaust the free-tier daily allowance, and a run that
crashes partway still spends them. Verify the scoring path against an existing results
file before spending requests on a fresh run.

