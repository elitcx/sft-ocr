# Facing-page discard — fix report — 2026-09-05

## The defect

A photographed open book (or bound document) puts two physical pages in one frame. The
intended page is flat and in focus; the facing page curves away toward the spine, tilted
and foreshortened by perspective. `ColumnSegmenter`'s gutter-detection logic can see the
facing page's text as a plausible second column — it clears the existing line-count and
share floors just like a real second column of a worksheet would — so `ReadingOrderSorter`
reads it as part of the document. A blind student hears a page of distorted, wrong-page
text before the page they pointed the camera at, which the project's own governing
priority ("read the text in order and interpret it the same way a sighted reader would")
calls the worst possible outcome.

Decision (made with the user before this work started): auto-detect the dominant page by
weight of text and silently discard the other. No UI change, no manual crop.

## Method

1. Extended `OcrEngine` with a debug-only `recognizeRawDebug(uri)` and added a throwaway
   on-device harness, `OcrBatchRawDumpHarnessTest`, that dumps ML Kit's raw
   `RawTextResult` (before any pipeline correction) for a batch of real corpus photos —
   the same technique already used to capture the existing `*-raw.json` fixtures.
2. Screened the 139-photo corpus visually for open-book/facing-page shots (the exact
   "How to Win Friends" photo the user described was not in the folder; two other photos
   of that same physical book *were* found, but neither happens to show the reported
   defect — see Residual gaps). Selected real equivalents instead:
   - `20260731_231108.jpg` — a bound handbook photographed open. The right page (a values
     list, items 3-4) is flat and in focus; the left page curves steeply toward the spine.
   - `20260731_230929.jpg`, `20260731_231146.jpg` — a school report photographed open,
     with a facing page's table peeking in at a corner.
   - `20260731_232204.jpg`, `20260731_232336.jpg` — an award/roster book spread, tables
     spanning both facing pages, used as same-page negative evidence.
3. Ran all six through the **full real pipeline** (`DocumentStructurer.structure`, i.e.
   `FrameRotation` → `RotationPlausibilityGuard` → `SkewEstimator` → `MarginFragmentFilter`
   → `ColumnSegmenter`), not a standalone approximation, before choosing thresholds —
   an earlier Python-only estimate (FrameRotation applied, `SkewEstimator` skipped) gave
   materially different, over-optimistic numbers for line-height scale (see below), which
   is exactly the kind of mistake "measure before building" is meant to catch.
4. Saved the one real photo that reaches the new check as a JVM fixture
   (`ocr-core/src/test/resources/fixtures/real-facing-page-231108-raw.json`), wrote a
   failing test against it, watched it fail, then implemented.

## Measurements: does the signal discriminate?

Per-region median line angle, measured from each line's own recognizer-reported
corner-point quadrilateral (not the `angleDeg` field — see "why not `angleDeg`" below),
**after the full pipeline's `SkewEstimator.deskew()` has already removed the page's one
shared skew**:

| Photo | Reaches 2-column check? | Dominant vs other angle diff | Outcome (existing floors alone) |
|---|---|---|---|
| `worksheet-two-column.json` (synthetic, one flat sheet) | yes | **0.00°** | 2 columns (correct) |
| `20260731_231108.jpg` (real, open handbook) | yes | **5.01°** | 2 columns (WRONG — facing page kept) |
| `20260731_230929.jpg` (real, facing table sliver) | no — collapses to 1 column before this check runs | n/a | 1 column (correct, but via `MarginFragmentFilter`/share-floor, not this fix) |
| `20260731_231146.jpg` (real, facing table sliver) | no — same as above | n/a | 1 column (correct, pre-existing) |
| `20260731_232204.jpg` (real, same-page multi-column table) | no — collapses to 1 column (gutter search lands inside one page's own table columns, not the book gutter) | n/a | 1 column |
| `20260731_232336.jpg` (real, same-page multi-column table) | no — produces 3 columns, which this check does not examine | n/a | 3 columns (unaffected either way) |

**Angle discriminates cleanly on the available real evidence** (0.00° vs 5.01°, the only
pair that actually reaches the check), and a threshold of **4.0°** sits with margin on
both sides.

**Text-scale (median line height ratio) does NOT discriminate reliably and was dropped.**
The spec's hypothesis — a facing page sits farther away and is foreshortened, so its text
measures smaller — is reasonable, but measured honestly through the real pipeline:

- A naive estimate (`FrameRotation` only, skipping `SkewEstimator`) gave `20260731_231108`
  a height ratio of **1.22** (39.5px vs 48.0px) — this looked like a usable signal.
- Measured through the **actual** pipeline (`SkewEstimator.deskew()` included), the same
  photo's ratio dropped to **1.02** (39.45px vs 38.50px) — indistinguishable from the
  synthetic same-sheet control's 1.00. `SkewEstimator.deskew()` re-encloses each line's
  *rotated* corners into a tight box (by design — see its own KDoc on why this avoids the
  `w·sin(t)+h·cos(t)` inflation of rotating an axis-aligned box), and that re-enclosure
  evidently absorbs most of the scale difference a facing page's distance/angle would
  otherwise leave in the axis-aligned box height. Angle survives the shared rotation
  intact; height does not.

This is reported as a real (if disappointing) finding rather than silently dropped: the
implementation uses **angle alone**. The evidence base for the angle threshold is honestly
thin — one real positive data point — because most real facing-page photos in this corpus
never reach the 2-column check at all (see the table); `MarginFragmentFilter` and the
pre-existing line-count/share floor already catch the smaller/sparser cases before this
new check would ever run. This fix targets the case those cannot catch: a facing page
substantial enough to look like a legitimate second column.

### Why not `RawLine.angleDeg`?

`SkewEstimator.deskew()` explicitly resets every line's `angleDeg` field to `0f` once the
page-level correction is applied (see its own `rotatedBy`) — the field is documented as
meaning "this line's angle relative to upright," and after deskewing, that's true for
*every* line regardless of which physical surface it came from. The genuine per-line tilt
that survives deskewing is only recoverable from each line's own rotated `cornerPoints`.
A small shared utility, `angleFromCorners` (extracted from `FrameRotation`, which had
identical private logic; `FrameRotation` now delegates to it — a straight
duplication-removal, no behavior change there), computes it from the same corner-order
convention both call sites already relied on.

## The rule

In `ColumnSegmenter.segment()`, after the existing count/share "populated" gate passes
(both columns already clear `minLinesPerColumn` and `minColumnLineShareFraction`) and
`columnCount == 2`:

1. Compute each column's median line angle from corner points.
2. "Dominant" = the column with more column-bound lines (weight of text, not position —
   the facing page is not always on the same side of the frame).
3. If the two columns' median angles differ by more than
   `StructuringConfig.facingPageMaxAngleDiffDeg` (4.0°), drop the non-dominant column's
   lines entirely (mirroring the existing clutter-drop mechanism a few lines above, which
   already drops lines rather than folding them into the reading order — sorting garbage
   by vertical position would scatter it through real content).
4. Otherwise: unchanged behavior, both columns kept.

Restricted to `columnCount == 2` deliberately — that is the only case with real supporting
evidence (an open book has exactly two facing surfaces); extending it to 3+ columns would
be an unevidenced generalization.

All thresholds live in `StructuringConfig` with full KDoc citing the measurements above
(`facingPageMaxAngleDiffDeg`). No inline magic numbers.

## Safety / conservatism

- **Golden fixtures untouched.** `real-worksheet-reading.json` and
  `real-worksheet-exercises.json` are single-column pages; this check only runs at
  `columnCount == 2` and never touches them. Both pass unchanged (see test run below).
- **The two-column golden fixture (`worksheet-two-column.json`) still returns
  `columnCount == 2` with all six questions, in order.** Its lines carry no
  `cornerPoints` (synthetic fixture), so both columns' angle defaults to 0 — the check
  can never fire on it. New regression test:
  `the two-column worksheet golden fixture is unaffected by the facing-page check`.
- **A missed discard leaves current behavior; a wrong discard destroys content.** The
  4.0° threshold sits at roughly 80% of the distance from the only negative evidence
  (0.00°) to the only positive evidence (5.01°), leaning toward the conservative (higher)
  side per the governing priority — a borderline page keeps both columns.
- Single-surface (`columnCount == 1`) pages are entirely unaffected — the check is inside
  the `columnCount == 2` branch only.
- Determinism: no `sortedWith` comparator was touched; the new logic is a pure
  threshold comparison over already-deterministic inputs (`counts`, corner-derived
  angles).

## Before / after (real corpus, full pipeline, `OcrBatchStructureHarnessTest` output)

**`20260731_231108.jpg` — before this fix** (captured via an instrumented debug run of the
new failing test, prior to the code change):

```
columnCount = 2
column 0 (intruding facing page, read FIRST — column-major order):
  "oman sar ni"
  "terwujud gnitif dan A00a serta leks iman"
  "dunia dengu ebahagiaan jm"
column 1 (real page): "menunjukkan bahwa bagi Santa Angela, integritas..." ... [17]
```

A blind student would hear "oman sar ni... A00a... dunia dengu ebahagiaan jm" — three
blocks of garbled, wrong-page text — *before* any real content, exactly the reported
defect.

**`20260731_231108.jpg` — after this fix** (real on-device run,
`OcrBatchStructureHarnessTest`, pulled and inspected):

```
columnCount = 1, 23 blocks
LIST_ITEM  3. | Keberanian - Ketangguhan
PARAGRAPH     | Keberanian dan Ketangguhan memiliki pengertian nilai yang
PARAGRAPH     | terwujud dalam
...
LIST_ITEM  4. | Semangat Persatuan (Insieme)
PARAGRAPH     | Persatuan memiliki pengertian nilai yang terwujud dalam
...
PARAGRAPH     | [17]
```

No facing-page fragment ("A00a", "Kepada karsma", "asih ganda dn", "usi Uni Roma",
"eniadi") survives anywhere in the output. The real page's content is intact and in the
correct order.

Other five photos, before vs. after (all unaffected by this change — pre-existing
behavior, included to show no regression):

| Photo | columnCount before | columnCount after |
|---|---|---|
| `20260731_231102.jpg` | 1 | 1 |
| `20260731_230929.jpg` | 1 | 1 |
| `20260731_231146.jpg` | 1 | 1 |
| `20260731_232204.jpg` | 1 | 1 |
| `20260731_232336.jpg` | 3 | 3 |

## Tests added

`ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurerTest.kt`:

- `an open book's facing page is discarded, not read as a second column` — the real
  231108 fixture; asserts `columnCount == 1`, both real sections' text survive intact and
  in order, and every known facing-page fragment is absent anywhere in the output. Written
  first, confirmed failing (`expected: <1> but was: <2>`) against the pre-fix code (via a
  `git stash` of just the implementation files, keeping the new test and fixture in
  place), then made to pass by the implementation.
- `the two-column worksheet golden fixture is unaffected by the facing-page check` —
  regression guard pinning `columnCount == 2` and all six question markers in order on
  the pre-existing synthetic fixture.

New fixture: `ocr-core/src/test/resources/fixtures/real-facing-page-231108-raw.json` (real
ML Kit output, captured on the AVD, replays on the JVM with no emulator).

New production code:
- `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/LineGeometry.kt` — the shared
  `angleFromCorners` utility (extracted from `FrameRotation`, no behavior change there).
- `ColumnSegmenter.kt` — the facing-page check itself, ~20 lines, gated on
  `columnCount == 2`.
- `StructuringConfig.kt` — one new field, `facingPageMaxAngleDiffDeg`, with full
  evidence-citing KDoc (including the rejected height-ratio signal and why).
- `OcrEngine.kt` — `recognizeRawDebug(uri)`, a debug/fixture-capture-only method, used to
  produce the new fixture and available for capturing future ones. Not used by any
  production path (`recognize`).
- `ocr-mlkit/src/androidTest/.../OcrBatchRawDumpHarnessTest.kt` — the on-demand raw-dump
  harness (sibling of the existing structure-dump harness), used to produce fixtures.

## Full verification

- `./gradlew :ocr-core:test` — **114 tests, 0 failures** (112 pre-existing + 2 new; one
  originally-planned third test for `20260731_230929.jpg` was removed — see Residual
  gaps — net +2).
- `./gradlew :ocr-mlkit:testDebugUnitTest` — pass.
- `./gradlew :app:assembleDebug` — pass.
- `./gradlew :ocr-mlkit:connectedDebugAndroidTest` on `Pixel_10_Pro` (AVD) — pass (6 run,
  1 skipped as designed — `OcrAccuracyHarnessTest` has no sample worksheets pushed, which
  is its documented no-op behavior, not a failure).

No existing assertion was weakened. `real-worksheet-reading.json` and
`real-worksheet-exercises.json`'s golden end-to-end tests pass unchanged.

## Residual gaps / what's still wrong

1. **The exact reported photo ("How to Win Friends", Indonesian translation) was not in
   the corpus.** The two photos of that physical book present
   (`20260811_223022.jpg`/`223036.jpg`, cover and title page) are single flat pages, not
   a facing-page shot, so they could not be used as evidence. Real equivalents from other
   photos in the same session were used instead, per the brief's own allowance.

2. **`20260731_230929.jpg` and `20260731_231146.jpg` are NOT fixed by this change** — they
   were already producing `columnCount == 1` beforehand, but investigation showed this is
   the plain `singleColumn()` fallback (no gutter found — the facing fragments are
   scattered across x-ranges that don't form two clean bounded runs), which does **not**
   drop any lines; it just assigns every line to one nominal column and lets
   `ReadingOrderSorter` sort everything, garbage included, by vertical position. A
   fragment like `"dibukanya"` or `"demi K"` can still surface as its own block, sorted
   into the reading order wherever its y-position lands — this was verified directly (an
   early draft of a regression test against `20260731_230929.jpg` failed exactly this
   way: `"dibukanya" must not survive as its own block`). This is a real, separate defect:
   a facing page's fragments that never form a clean second *column* are not caught by
   `ColumnSegmenter`'s facing-page check (which only compares two already-detected
   columns) or by `MarginFragmentFilter` (whose isolation test is about a right-margin
   gap, not a top-of-frame scatter). It is out of scope for the "auto-detect and discard
   the facing page as a column" decision this task implemented, and is flagged separately
   rather than folded into this fix's test suite (that draft test and its fixture were
   removed rather than weakened or misrepresented as passing).

3. **Angle-only evidence base is one real photo.** The threshold (4.0°) is chosen with
   margin on both sides of the only available real pair (0.00° vs 5.01°), but more real
   facing-page corpus photos that survive to the `columnCount == 2` check would sharpen
   confidence in exactly where within that margin the real boundary sits.

4. **Height/scale as a discriminator is confirmed unreliable** for this purpose (see
   Measurements) — not a bug, but worth recording so no future change re-adds it as a
   gating signal without re-deriving it from the full pipeline.

This is the eighteenth genuine finding surfaced by this project's real-corpus discipline
(the height-ratio-vanishes-after-deskew result, and the scattered-fragment gap in item 2
above).
