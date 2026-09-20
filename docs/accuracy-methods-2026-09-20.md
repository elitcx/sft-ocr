# Methods — how every number in the report was produced

## Environment set up automatically this session

| Component | Detail |
| --- | --- |
| Tesseract | 5.4.0 (UB Mannheim build) installed via `winget`, includes `lstmtraining`, `lstmeval`, `combine_tessdata` |
| Training checkpoint | `ind.traineddata` from `tessdata_best` (8.25 MB float model — `tessdata_fast` cannot be fine-tuned) |
| JDK | Android Studio JBR 21.0.10; the system default (Java 26) is rejected by this Gradle/AGP |
| Emulator | existing `Pixel_10_Pro` AVD, `android-37.0 google_apis_playstore x86_64`, booted headless with `-wipe-data` (the original data partition was 93% full and rejected the test APK) |

## Unit tests

```
JAVA_HOME=".../Android Studio/jbr" ./gradlew :ocr-core:test :app:testDebugUnitTest
```
Counts parsed from `*/build/test-results/**/TEST-*.xml`.

## App-level accuracy

The app's own instrumented harness, unmodified:

```
adb push <pairs> /sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test/braille-samples
./gradlew :ocr-mlkit:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=id.dotcode.braille.ocr.mlkit.OcrAccuracyHarnessTest \
  -Pandroid.testInstrumentationRunnerArguments.brailleSamplesDir=<that dir>
adb logcat -d -s OcrAccuracyHarness
```

The harness reads every page twice — recognizer alone, then with the offline
correction layer — so the layer's contribution is measured rather than assumed.
It always also scores the 4 committed `assets/ground-truth/` pairs, so those are
reported as their own group. Per-image lines are parsed from logcat and
aggregated by split, weighting each page by its reference length (the standard
corpus-level CER/WER, matching `ErrorRate.kt`).

## Splits

`make_splits.py`, seed 20260920, ratios 60/20/20, **stratified within capture
session**. Grouping by session matters: consecutive pages of one book shot in one
sitting are correlated, so a naive random split leaks near-duplicates into test
and makes the number optimistic. A separate `holdout` group holds pages labelled
after the splits were frozen, from a camera and sessions absent from training —
an out-of-distribution check.

## Line ground truth for fine-tuning

Tesseract's LSTM trains on (line image, line text); the labels here are
page-level, so `build_lines.py` bootstraps line GT: Tesseract line boxes →
difflib word alignment against the page transcript → each line gets a contiguous
slice of the reference, with slices tiling the page so nothing is dropped.
Non-monotonic pages (multi-column, insets) are rejected whole. Lines are then
filtered on length, height, and OCR-vs-GT edit distance.

An earlier version projected only the span between *matched* anchors, which
silently truncated any line whose first or last word was misread — that would
have trained the model to stop early. Caught by inspecting the highest-
disagreement lines before training.

## Fine-tuning

```
lstmtraining --continue_from ind.lstm --traineddata tessdata_best/ind.traineddata \
  --train_listfile train.lstmf.txt --eval_listfile val.lstmf.txt --max_iterations 1500
```
Stopped once training error plateaued. Checkpoints were then scored on the
**validation** split with `lstmeval`, the best one selected, and only that one
scored on **test** — which was never used for any decision.

## Gotchas hit (all cost real time)

- `lstmeval` list files must use LF endings; Python's text mode writes CRLF on
  Windows and every path then fails to open, reported as "Deserialize header failed".
- A custom `TESSDATA_PREFIX` directory needs the `configs/` folder copied in, or
  `tsv`/`lstm.train` configs are silently ignored.
- `TesseractReader.DATA_VERSION` must be bumped when swapping bundled models, or
  the app keeps the copy already in `filesDir` and the swap does nothing.
- `emulator -partition-size` caps at 2047 MB; the AVD's own
  `disk.dataPartition.size` is the setting that matters.
- Reinstalling the test APK wipes `/sdcard/Android/media/<pkg>/`, which is where
  the sample pairs live. Any run whose APK changed silently falls back to the 4
  bundled seed pairs and reports a number for those alone. Push the samples
  **after** the install, and always check the scored-image count before trusting
  an aggregate.
