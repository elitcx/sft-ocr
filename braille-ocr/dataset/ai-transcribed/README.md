# AI-transcribed answer key

Transcripts of 16 photos from the `braille testing` corpus (a school handbook plus two
physics worksheets), used to compare OCR pipeline changes on real captures.

**Status: `AI_TRANSCRIBED`, not `HUMAN_CORRECTED`.** An AI model read each photo directly
and typed what it saw; it never saw the pipeline's output, so these measure the pipeline
against the page rather than against itself. They have not been checked by a person, so
use them to rank pipeline variants, not to claim an accuracy figure. See
`docs/dataset-schema.md`.

The photos are not committed (4-8 MB each; the handbook is the school's document). Each
`<name>.dataset.json` names its photo and its SHA-256, so the right file can be matched.

## Transcription rules

- The main page only: no facing page, no background, no text bleeding through the paper.
- Top to bottom; tables row by row, left to right, cells separated by a space.
- Spelling exactly as printed, including real typos ("yanga", "Perputakaan").
- A stacked fraction is written inline (`1/2`); answer circles and icons are left out.
- Whitespace and line breaks do not affect the score (`ErrorRate` normalizes them).

## Promoting a sample

Open the photo and the `.txt` side by side, fix anything wrong, then change
`"transcriptionSource"` to `"HUMAN_CORRECTED"` in the `.dataset.json`.

## Running the comparison

`ocr-mlkit/src/androidTest/.../OcrExperimentHarnessTest.kt` explains how to push the
photos and these transcripts to a device and score several pipeline settings side by side.
