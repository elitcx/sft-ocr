# BRaiLLE labelled-sample dataset schema (v1)

This document defines what a "labelled sample" is for BRaiLLE's OCR accuracy measurement and
future training work, why it looks the way it does, and how to produce one. It exists because
the project had **zero** ground-truth transcripts before 2026-09-05 — `OcrAccuracyHarnessTest`
existed but always skipped, and the concept paper's ">90% accuracy" claim had never actually
been measured. This schema, the bootstrap workflow, and the seed dataset committed alongside
it are what make that measurement possible for the first time.

## The privacy constraint, restated

Everything below stays **on the device it was captured on**, always. There is no upload path,
no sync, no telemetry. The only way a sample leaves a device is a human physically pulling it
off with `adb pull` (developer bootstrap workflow) or a user deliberately exporting it
(nothing in the app does this today — exports are JSON/text sharing of the *current* result,
unrelated to stored samples). The in-app capture feature is **opt-in and off by default**; see
"In-app capture" below.

## Anatomy of one sample

A sample is a **directory** containing up to four files. Nothing is embedded inside another
format (no JSON-with-base64-image, no sidecar-less naming convention) so every part stays
inspectable with ordinary tools — an image viewer, a text editor, `jq`.

```
<sample-id>/
  image.jpg          # or .png — the untouched source photo
  text.txt           # the transcript: what OcrAccuracyHarnessTest reads as "expected"
  document.json       # optional: the full OcrDocument the pipeline produced (debug/labels)
  meta.json           # provenance: DatasetSampleMeta (see below)
```

The bootstrap harness and the committed seed dataset use `<image-name>.txt` /
`<image-name>.dataset.json` beside the image instead of a subdirectory, because that is the
layout `OcrAccuracyHarnessTest` already expected before any of this existed (see
`ocr-mlkit/src/androidTest/kotlin/.../OcrAccuracyHarnessTest.kt`) — matching it means a
corrected bootstrap transcript activates the harness with zero further work. The in-app
capture store (`TrainingSampleStore` in `:app`) uses the one-directory-per-sample layout
instead, because an app-private store has many samples with no natural shared "image name"
to hang them off. Both layouts hold the same four kinds of content; only the file layout
differs.

### `meta.json` — provenance (`DatasetSampleMeta` in `:ocr-core`)

Defined in
`ocr-core/src/main/kotlin/id/dotcode/braille/ocr/dataset/DatasetSampleMeta.kt` as a
`kotlinx.serialization` data class, so it is pure Kotlin with zero Android dependency and can
be produced by the on-device bootstrap harness, the in-app capture store, or a future offline
tool without duplicating the format.

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | Int | `1` today. Bump when a field's meaning changes incompatibly. |
| `sourceImageFileName` | String | File name only (not a path) — the directory/prefix is the real identity. |
| `sourceImageSha256` | String | SHA-256 of the image's raw bytes. A renamed or re-copied file (same bytes) still matches. |
| `pipelineVersion` | String | `PipelineVersion.CURRENT` at capture time — see below. |
| `capturedAtEpochMs` | Long | When the *raw OCR* was produced (not when a human corrected it). |
| `transcriptionSource` | `RAW_OCR` \| `HUMAN_CORRECTED` | **The field that matters.** See next section. |
| `blockRoleLabels` | List | Per-block `(blockId, role, marker, verified)` — weak supervision for a future block classifier. `verified=false` until a human confirms it. |
| `notes` | String? | Free text: how the sample was captured, anything unusual about it. |

`PipelineVersion.CURRENT` (`ocr-core/.../pipeline/PipelineVersion.kt`) is a single hand-bumped
string identifying "what the seven-stage structuring pipeline does right now" —
`StructuringConfig`'s ~25 tuned constants included. It exists so a sample's stored output
(and any accuracy number computed from it) can be traced to the exact pipeline behavior that
produced it; a config constant changing invalidates old numbers even though the module's
Gradle version does not change.

### `RAW_OCR` vs `HUMAN_CORRECTED` — the rule that protects the flywheel

**This is the most important rule in this document.** A dataset built by scoring a model
against its own unverified output does not measure accuracy — it measures agreement with
itself, which is trivially 100%. Every sample starts life as `RAW_OCR`: the pipeline's raw
output, nothing more. It becomes `HUMAN_CORRECTED` only when a person has read the `text.txt`
against the actual photo and fixed every mistake (or confirmed there were none). Nothing in
this codebase flips that flag automatically — not the bootstrap harness, not the in-app
capture store — on purpose. `OcrAccuracyHarnessTest` does not currently *read* `meta.json` at
all (it only needs an image + `.txt` pair), which makes this an honor system at the file
level; the discipline is: **never commit or feed into the accuracy harness a `.txt` that is
still `RAW_OCR`**. The seed dataset committed with this schema (four samples, see below) is
`HUMAN_CORRECTED` by construction — each transcript was produced by reading the committed
photo directly, not by copying pipeline output.

## The bootstrap workflow (turning 139 unlabelled photos into correctable drafts)

Transcribing 139 real worksheet/book photos from scratch is unrealistic for one person.
Instead, `OcrGroundTruthBootstrapHarnessTest`
(`ocr-mlkit/src/androidTest/kotlin/.../OcrGroundTruthBootstrapHarnessTest.kt`) runs the real
on-device pipeline over a corpus directory and writes a **draft** `.txt` + `.dataset.json`
(`transcriptionSource = RAW_OCR`) beside every image — turning "transcribe from nothing" into
"correct what's already mostly right", which is far faster.

```bash
# 1. Push the unlabelled corpus to the device
adb shell mkdir -p /sdcard/Download/braille-bootstrap
adb push "braille testing/"*.jpg /sdcard/Download/braille-bootstrap/

# 2. Build and install the instrumented test APK
./gradlew :ocr-mlkit:assembleDebugAndroidTest
adb install -r -t ocr-mlkit/build/outputs/apk/androidTest/debug/ocr-mlkit-debug-androidTest.apk
adb shell appops set --uid id.dotcode.braille.ocr.mlkit.test MANAGE_EXTERNAL_STORAGE allow

# 3. Run the bootstrap harness — writes <name>.txt and <name>.dataset.json per image
./gradlew :ocr-mlkit:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=id.dotcode.braille.ocr.mlkit.OcrGroundTruthBootstrapHarnessTest

# 4. Pull the drafts back
adb pull /sdcard/Download/braille-bootstrap ./pulled-bootstrap
```

Then, **per image**:

1. Open `<name>.jpg` and `<name>.txt` side by side.
2. Read the photo and fix every mistake in `<name>.txt` — correcting is far faster than
   retyping from scratch.
3. Edit `<name>.dataset.json`: change `"transcriptionSource"` from `"RAW_OCR"` to
   `"HUMAN_CORRECTED"`.
4. To make it a permanent, always-run regression sample: copy the `.jpg` + `.txt` (+
   `.dataset.json`, optional but recommended for provenance) into
   `ocr-mlkit/src/androidTest/assets/ground-truth/` and commit them — see next section. To run
   it once without committing: `adb push` the pair into
   `/sdcard/Download/braille-samples/` (the harness's default external directory).

The bootstrap harness never overwrites an existing `.txt` — re-running it after a correction
pass leaves labelled work alone.

## How `OcrAccuracyHarnessTest` finds the seed dataset

Before this change, `OcrAccuracyHarnessTest` only ever looked at a device directory
(`/sdcard/Download/braille-samples` by default) that a human had to `adb push` into — which is
exactly why it always skipped: nobody had ever done that. It now **also** unpacks
`ocr-mlkit/src/androidTest/assets/ground-truth/` (bundled inside the test APK, no `adb push`
required) and scores those pairs in addition to anything found on the device. That is what
makes `connectedDebugAndroidTest` measure real accuracy on every run, in CI or on a fresh
emulator, with nothing to set up.

## Worked example (real, committed)

`ocr-mlkit/src/androidTest/assets/ground-truth/IMG-20260821-WA0035.jpg` is one of the nine
real, sharp worksheet photos already committed for the capture-quality-gate tests. Its
`meta.json`:

```json
{
    "schemaVersion": 1,
    "sourceImageFileName": "IMG-20260821-WA0035.jpg",
    "sourceImageSha256": "0b33e64a5837aedf6f36b015f8d43eccf7fed6b455dd48a299dd46957d2186c0",
    "pipelineVersion": "2026.09.05-1",
    "capturedAtEpochMs": 1788595200000,
    "transcriptionSource": "HUMAN_CORRECTED",
    "blockRoleLabels": [],
    "notes": "Seed sample for the first real CER/WER measurement (2026-09-05). Transcribed by reading the committed photo directly; not derived from any OCR run."
}
```

and its `text.txt` (the actual printed multiple-choice physics question, transcribed by
reading the photo):

```
1. Sekelompok peneliti melakukan uji coba roket yang telah dikembangkannya. Roket diluncurkan dengan kecepatan 180 km/jam membentuk sudut 37° terhadap permukaan tanah. Jarak terjauh yang dapat dijangkau roket tersebut adalah . . . .
A. 45 m
B. 108 m
C. 120 m
D. 240 m
E. 360 m
```

Three more samples (`IMG-20260819-WA0006`, `IMG-20260821-WA0034`, `IMG-20260821-WA0036`) are
committed the same way — see `docs/superpowers/plans/2026-09-05-data-flywheel-report.md` for
the real CER/WER these four produced against the current pipeline.

## In-app capture (opt-in, on-device only)

`:app` adds a settings toggle ("Bantu Tingkatkan Akurasi" in Pengaturan), **off by default**,
that explains in Indonesian that turning it on lets the user save a photo + recognized text
locally when a result is wrong, and that nothing ever leaves the device. When enabled, the
result screen gains a "Tandai Hasil Salah & Simpan" control that copies the current
image + `OcrDocument` JSON + flattened text into app-private storage
(`context.filesDir/training-samples/<id>/`) via `TrainingSampleStore`
(`app/src/main/kotlin/id/dotcode/braille/ocr/app/TrainingSampleStore.kt`), stamped
`transcriptionSource = RAW_OCR` (a user flagging a result as wrong is real signal, but it is
not the same as typing a correction). Pengaturan also shows the live sample count and a
"Hapus Semua Sampel" (delete all) action with a confirmation dialog — the inspectable/
deletable requirement. See `docs/superpowers/plans/2026-09-05-data-flywheel-report.md` for
what was verified on-device (emulator walkthrough: toggle on, save a sample, see the count,
delete, see it hit zero and disappear from `run-as` filesystem inspection).

Turning these RAW_OCR in-app samples into usable training/eval data requires the exact same
human correction step as the bootstrap workflow — nothing about the in-app path skips it.

## What this does *not* yet do

- No automatic promotion from `RAW_OCR` to `HUMAN_CORRECTED` — deliberately; see above.
- No tool reads `blockRoleLabels` yet; they are recorded for a block classifier that does not
  exist yet.
- No aggregation across samples beyond what `OcrAccuracyHarnessTest`'s aggregate CER/WER
  already does — no dataset-wide stats file, no dashboard.
- 139 real corpus photos remain unlabelled; only 4 have been hand-corrected so far (see the
  report for why 4 and not more, and what a larger effort would look like).
