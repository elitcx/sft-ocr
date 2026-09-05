# Data flywheel: ground-truth bootstrap, dataset schema, in-app capture, first real accuracy

Date: 2026-09-05. Branch: `feat/ocr-mvp`.

## Why this exists

BRaiLLE had a seven-stage rule-based structuring pipeline, ~25 hand-tuned constants, 121 +
14 passing tests — and had **never measured its own accuracy against a real transcript**.
`OcrAccuracyHarnessTest` existed and always skipped ("no sample worksheets found"). The
concept paper's ">90% accuracy across 50 SLB-A worksheets" was an aspiration with no number
behind it. This work turns that into a real, repeatable measurement, and lays the groundwork
(schema + opt-in capture) for a training-data flywheel later — without touching the
structuring pipeline's behavior at all.

## 1. Dataset schema

Full write-up: `docs/dataset-schema.md`. Summary:

- `DatasetSampleMeta` (`ocr-core/src/main/kotlin/id/dotcode/braille/ocr/dataset/`), pure
  Kotlin (`kotlinx.serialization`), zero Android dependency: `sourceImageFileName`,
  `sourceImageSha256` (survives a rename), `pipelineVersion`
  (`PipelineVersion.CURRENT = "2026.09.05-1"`, bumped whenever `StructuringConfig` or a
  structuring stage's behavior changes), `capturedAtEpochMs`, `transcriptionSource`
  (`RAW_OCR` | `HUMAN_CORRECTED`), optional `blockRoleLabels` (per-block role/marker, for a
  future block classifier), `notes`.
- **The rule that matters:** everything starts `RAW_OCR`. Nothing in the codebase — not the
  bootstrap harness, not the in-app capture store — ever flips it to `HUMAN_CORRECTED`
  automatically. That happens only when a human has read the transcript against the photo.
  This is what stops the flywheel from training on its own mistakes.
- Real committed example: `ocr-mlkit/src/androidTest/assets/ground-truth/IMG-20260821-WA0035.{jpg,txt,dataset.json}`
  (full JSON reproduced in `docs/dataset-schema.md`).

## 2. Bootstrap workflow

New harness: `OcrGroundTruthBootstrapHarnessTest`
(`ocr-mlkit/src/androidTest/kotlin/.../OcrGroundTruthBootstrapHarnessTest.kt`). Step by step:

1. `adb push "braille testing/"*.jpg /sdcard/Download/braille-bootstrap/` (the 139-photo
   corpus).
2. Build + install the instrumented test APK, grant `MANAGE_EXTERNAL_STORAGE` to the test
   package (required under scoped storage — see the existing `OcrAccuracyHarnessTest` KDoc for
   why).
3. Run `OcrGroundTruthBootstrapHarnessTest` via `connectedDebugAndroidTest`. For every image it
   writes a **draft** `<name>.txt` (the real pipeline's flattened output — the exact same
   flattening `OcrAccuracyHarnessTest` scores against, via the new shared
   `DocumentFlattener` in `:ocr-core`) and `<name>.dataset.json`
   (`transcriptionSource = RAW_OCR`), or `<name>.failure.txt` if recognition itself failed. It
   never overwrites an existing `.txt`, so re-running after a correction pass is safe.
4. `adb pull` the drafts back.
5. **Human step, per image:** read the `.txt` against the photo, fix every mistake (correcting
   is far faster than transcribing from nothing), flip `transcriptionSource` to
   `HUMAN_CORRECTED` in the `.dataset.json`.
6. Commit the corrected pair into `ocr-mlkit/src/androidTest/assets/ground-truth/` to make it
   a permanent, always-run regression sample, or `adb push` it into
   `/sdcard/Download/braille-samples/` for a one-off run.

`OcrAccuracyHarnessTest` was extended to unpack `assets/ground-truth/` from the test APK on
every run (in addition to whatever is `adb push`ed to the device), which is what makes the
harness **run instead of skip** with zero device setup.

## 3. First real accuracy measurement

Four images from the already-committed `assets/quality/` real-photo set (sharp, legible A4
worksheet photos) were hand-transcribed by reading the photos directly (not by copying OCR
output) and committed as the seed dataset. This is the **first genuine accuracy number this
project has ever produced.**

Run: `./gradlew :ocr-mlkit:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=id.dotcode.braille.ocr.mlkit.OcrAccuracyHarnessTest` on AVD `Pixel_10_Pro`, read via `adb logcat -d -s OcrAccuracyHarness`:

| Image | CER | WER | Char accuracy | Word accuracy | Expected chars/words |
|---|---|---|---|---|---|
| IMG-20260819-WA0006.jpg (10-question physics worksheet, math notation) | 0.0350 | 0.2213 | 96.50% | 77.87% | 1627 / 253 |
| IMG-20260821-WA0034.jpg (single MC question) | 0.0654 | 0.2857 | 93.46% | 71.43% | 260 / 49 |
| IMG-20260821-WA0035.jpg (single MC question) | 0.0326 | 0.1020 | 96.74% | 89.80% | 276 / 49 |
| IMG-20260821-WA0036.jpg (question + Benar/Salah table) | 0.0046 | 0.0290 | 99.54% | 97.10% | 435 / 69 |
| **Aggregate (4 images, 2601 chars / 420 words)** | **0.0327** | **0.1833** | **96.73%** | **81.67%** | — |

`connectedDebugAndroidTest` **passes**: aggregate character accuracy (96.73%) clears the
harness's pre-existing 90% gate (`MIN_CHARACTER_ACCURACY`, untouched). Word error rate is
much higher than CER — expected, since a single missed/garbled character (a superscript `²`,
`α`, `√`, or a stray period) turns a whole word wrong under WER even though CER barely moves.

**Read this honestly, not as ">90% accuracy achieved":** four images, all printed (not
handwritten), all from the already-curated "sharp/legible" subset, none photographed at an
extreme angle. This says "the pipeline is good at clean printed text," not "the pipeline
meets the concept paper's 50-worksheet claim." See "What's left" below.

## 4. In-app opt-in capture — what was built and verified

- `TrainingDataPrefs` (`app/.../TrainingDataPrefs.kt`): a `SharedPreferences` boolean, private
  to the app, **off by default**.
- Pengaturan (settings) screen: toggle "Bantu Tingkatkan Akurasi" with an Indonesian
  explanation ("...Semua data HANYA tersimpan di perangkat ini — tidak pernah diunggah atau
  dikirim ke internet. Fitur ini mati secara default..."), live "`N` sampel tersimpan di
  perangkat ini," and "Hapus Semua Sampel" with a confirmation dialog.
- Result screen: when the toggle is on and recognition succeeded, a "Tandai Hasil Salah &
  Simpan" control appears; tapping it copies the image + `OcrDocument` JSON + flattened text
  into `context.filesDir/training-samples/<id>/` via `TrainingSampleStore`
  (`app/.../TrainingSampleStore.kt`), stamped `RAW_OCR`.

**Verified live on the AVD** (`Pixel_10_Pro`, package `id.dotcode.braille.ocr`):

1. Installed the debug APK, granted camera permission, confirmed the toggle starts **off**
   and delete is disabled with 0 samples.
2. Turned the toggle on.
3. Picked a real worksheet photo via "Dari Galeri" (the emulator's synthetic camera feed has
   no text, so `Ambil Foto` correctly reports "Tidak ada teks yang terbaca" — a real, correct
   negative, not a bug); got a `Success` result.
4. Tapped "Tandai Hasil Salah & Simpan"; the UI showed "Tersimpan untuk membantu perbaikan
   akurasi."
5. Confirmed via `adb shell run-as id.dotcode.braille.ocr find files/training-samples`: the
   four expected files existed (`image.jpg`, `document.json`, `text.txt`, `meta.json`), and
   `meta.json`'s `sourceImageSha256` matched the seed dataset's hash for the same source image
   byte-for-byte, `transcriptionSource` was `RAW_OCR`, and `blockRoleLabels` was populated from
   the real classifier output.
6. Reopened Pengaturan: count showed "1 sampel tersimpan."
7. Tapped "Hapus Semua Sampel," confirmed the dialog ("Tindakan ini akan menghapus 1 sampel
   ... secara permanen"), and confirmed count dropped to 0 and the message "1 sampel telah
   dihapus" appeared.
8. Re-ran the `run-as find` command: `training-samples/` was empty. Deletion is real, not
   cosmetic.

No network permission exists in the app; nothing added here touches `AndroidManifest.xml`'s
permissions or adds any networking dependency.

## 5. A twentieth finding

While wiring the settings button onto the capture screen, taps on it were silently swallowed
on the emulator. Cause: `targetSdk 35` (Android 15) draws app content edge-to-edge by default,
so a button placed with plain `.padding(16.dp)` from the top of the screen landed inside the
status-bar / `mandatorySystemGestures` inset (`dumpsys window displays` showed
`statusBars frame=[0,0][1280,156]`, `mandatorySystemGestures frame=[0,0][1280,192]`) — a
region whose taps never reach the app window. This is a **pre-existing, app-wide gap**: none
of `MainActivity`, `CaptureScreen`, `ResultScreen`'s `FailureView`, or `DocumentView` account
for system-bar insets anywhere (confirmed visually: `FailureView`'s "Tidak ada teks yang
terbaca" text is overlapped by the status bar clock/icons on this same AVD). Fixed narrowly for
the two screens this work added (`Modifier.statusBarsPadding()` on the new Pengaturan button
and on `SettingsScreen`'s root `Column`) since that was required to make the new feature
usable at all. **Not fixed**: the pre-existing overlap on `CaptureScreen`'s bottom buttons
against the navigation bar, and `ResultScreen`/`FailureView`'s top content against the status
bar — left as found, since fixing the whole app's inset handling is outside this task's scope
and risks touching UI this task was not asked to change.

## 6. What still has to happen before any model can actually be trained

- **139 real corpus photos, 4 labelled.** The bootstrap harness makes correcting fast, but a
  human still has to sit through all 139 (or a representative sample) — this was not attempted
  here beyond the 4-image seed, deliberately (the task asked for 3–5).
- **No handwriting, no skew, no multi-column samples in the seed set.** All four seed images
  are clean, upright, single-column, printed text — exactly the case the pipeline already
  handles best. Nothing here validates the harder real-world cases (the `assets/quality/`
  set's other 5 images, or anything from the broader 139-photo corpus with visible skew/glare/
  curl) — that is precisely why the aggregate 96.73% must not be read as the project's real
  accuracy.
- **`blockRoleLabels` are unverified and unused.** They are recorded (from the same
  unverified pipeline output as the transcript) for a block-role classifier that does not
  exist yet; nothing currently trains on them or checks them against ground truth.
- **No actual model/classifier training code exists anywhere in this repo.** This work is
  entirely about *measuring* and *collecting*, per the task's explicit constraint ("this task
  adds measurement and data capture, it does not tune anything"). Turning collected
  `HUMAN_CORRECTED` samples into an actual accuracy improvement (retuning
  `StructuringConfig`, training a block classifier, or anything else) is unstarted follow-up
  work.
- **In-app capture has produced zero real samples from real users** — it was exercised once,
  manually, in this session, and then deleted as part of verification. The feature exists and
  works; it has not yet collected anything toward the flywheel because it shipped today.

## Test results

- `./gradlew :ocr-core:test` — all tests pass, including new `Sha256Test` and
  `DatasetSampleMetaTest`.
- `./gradlew :ocr-mlkit:testDebugUnitTest` — passes (unchanged; no unit-test-visible logic
  touched).
- `./gradlew :app:assembleDebug` — builds.
- `./gradlew :ocr-mlkit:connectedDebugAndroidTest` on AVD `Pixel_10_Pro` — **passes**, 10
  tests run, 3 skipped (the two batch-dump harnesses and, appropriately, none of the seeded
  accuracy/quality-gate tests — see numbers above). This is the first time this suite has run
  with the accuracy harness actually measuring something instead of skipping.
