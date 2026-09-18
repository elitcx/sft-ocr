# Overnight plan: OCR accuracy & performance (2026-09-17)

Goal: fewer reading errors on real photos, without overfitting and without slowing the
app down. Nothing is committed or sent anywhere; all changes stay local for review.

## Ground rules
- **Two answer-key sets.** `dataset/ai-transcribed/` (16 pages, used for tuning) and
  `dataset/ai-transcribed/holdout/` (~12 other pages, never tuned against). A change is
  kept only if it helps the tuning set **and** does not hurt the hold-out set.
- **Every rule needs evidence from more than one photo**, and gets a unit test.
- Existing tests (JVM + device) must stay green; reviewed-correction lists stay reviewed.
- Measure on the emulator; phone timings are the ones that matter for speed, so speed
  changes are judged by work removed, not emulator milliseconds.

## To do
1. [x] Finish the edge-filter measurement (1600/2400/3200 px) and record it here.
2. [x] Transcribe the hold-out set (~12 pages: dense prose, tables, lists, an English
       worksheet, a poster) and score the current pipeline on both sets.
3. [x] Decide the reading resolution from both sets (keep 1600 unless a larger size wins
       on both, and check the time cost).
4. [ ] Find the next biggest error source on the tuning set (per-page diffs): likely
       reading order on curved pages, table rows, and list bullets/arrows.
5. [ ] Fix what the evidence supports, one change at a time, re-measuring both sets.
6. [ ] Speed: skip Tesseract work that cannot change the result (e.g. pages where ML Kit
       is already confident everywhere), load the dictionary faster, avoid needless
       bitmap copies. Keep the 4 s time limit.
7. [ ] More tests: JVM fixtures from the saved uncorrected pages for any new rule;
       regression tests for every wrong result found.
8. [ ] Full test run (JVM + device), lint, APK build; update this file with results.
9. [ ] Delete the temporary emulator when done.

## Not tonight (needs you / a phone)
- Document Scanner ("Pindai Dokumen") needs a real phone with Google Play services.
- Promoting `AI_TRANSCRIBED` pages to `HUMAN_CORRECTED` needs a person to check them.

## Results log
### 1. Frame-edge filter (tuning set, 16 pages, emulator)
| Setting | Before filter CER / WER | With filter CER / WER |
|---|---|---|
| ML Kit only, 1600 px | 11.44% / 16.61% | **8.97% / 13.62%** |
| + correction, 1600 px | 11.42% / 16.52% | **8.95% / 13.53%** |
| + correction, 2400 px | 12.09% / 18.39% | 9.40% / 14.96% |
| + correction, 3200 px | 10.51% / 15.80% | 9.14% / 14.06% |

Decision: keep 1600 px. Larger sizes are not better once the edge filter is in, vary a lot
page to page (e.g. one page 0.6% at 1600 vs 11.3% at 3200), and cost time. Tesseract's
"best" models changed nothing measurable (11.42% vs 11.42%), so they are not worth 23 MB.


### Stopped early (2026-09-17, on request)
Done in addition to the above:
- Hold-out set: 11 pages in `dataset/ai-transcribed/holdout/` (page 231317 skipped: a
  two-page spread with no single main page). Not yet scored.
- `PageAssembler` (ocr-core): the post-recognition steps the phone runs, shared with PC
  tools. `OcrCaptureHarnessTest` saves raw recognition per photo; `CaptureReplayReport`
  replays and scores both answer-key sets on a PC in seconds (set BRAILLE_CAPTURES).
  The first capture run was stopped before finishing, so there are no replay numbers yet.

Not done:
- Scoring the hold-out set, and items 4-7 (next error source, speed work, more tests).
- Idea for next time: let Tesseract read only the ~1/3 of lines that contain a doubtful
  word (measured on 139 photos) instead of the whole page - less work, and slow phones
  would still finish within the 4 s limit.
