# Heading conservatism fix — closing the vocabulary-table false-HEADING defect

## The defect

On a real photographed worksheet, `RoleClassifier` promoted several vocabulary-table
Definition cells to `TITLE`/`HEADING` purely because camera perspective made them
measure taller than their local baseline (`RoleClassifier.localBaselines`). Real
observed examples from `real-worksheet-exercises.json`:

- `HEADING ×1.47` — "extremety careful, thorough, and exacting people responsible for
  developing or deciding public policies" (13 words)
- `HEADING ×1.25` — "the process of putting a plan, policy, or systerm into action in a
  way that can continue over the long term without exhausting resources" (24 words)
- `TITLE ×1.20` — "the goals that a plan or program was designed to achieve" — this one
  was **already** `PARAGRAPH` under the current default config (its 1.20 ratio falls
  short of `headingHeightRatio` = 1.25), so it needed no code change, only a regression
  test to keep it that way.

## Signals tested against the real fixtures

I decoded `real-worksheet-exercises.json` end-to-end through `DocumentStructurer` and
dumped every block that was NOT `PARAGRAPH`, with word count, line count, and
terminal-punctuation, to see empirically which of the three candidate signals actually
separate a real heading from a false one. This surfaced **ten** mislabelled definition
blocks, not just the three quoted above — the task's three examples turned out to be a
sample, not the exhaustive list.

| Signal | Result |
|---|---|
| **Word count ceiling** | Discriminates for the two largest false positives (13 and 24 words) and several others (10, 10, 10, 17 words). Does **not** discriminate three shorter ones (8, 9, 8 words) — these sit at or below the word count of the fixture's own genuine heading, "Should the Government Provide Free Nutritious Meals for Students?" (9 words). No ceiling can separate an 8-9 word false positive from a 9-word true positive by count alone. |
| **Terminal period** | Never fires either way on this fixture — none of the ten false-positive Definition cells end in a period (table cells, no sentence punctuation), and the genuine heading ends in "?" not ".". Harmless to keep as a defensive rule (it will catch a different shape of regression — a tall single-line sentence that does end in "." — that this fixture doesn't happen to contain) but it contributed nothing to closing today's defect. |
| **Line count (≤2)** | Both of the two examples named in the task are exactly 2 lines, so this rule alone doesn't exclude them either — word count does the actual work there. Kept as a backstop against a hypothetical 3+-line non-wrapping block (none exist in either real fixture today), verified by a synthetic unit test. |

**Conclusion:** word count is the only one of the three that does real work here, and it
only *partially* closes the gap (see Known residual gap below). Terminal-period and
line-count are implemented anyway as defense-in-depth per the task's request, verified
by synthetic tests, but neither was needed to fix any case actually present in the
fixtures.

## Rules implemented

All thresholds are in `StructuringConfig` with KDoc citing this evidence:

- `headingMaxWordCount = 9` — a block may only be promoted to `TITLE`/`HEADING` by
  height when it has at most 9 words. 9 is the word count of the real fixture's own
  genuine heading ("Should the Government Provide Free Nutritious Meals for
  Students?") — the highest ceiling that still fixes the two largest observed
  regressions without excluding a real heading this codebase has actually seen.
- `headingMaxLineCount = 2` — a block spanning more lines than this can never be
  promoted by height alone.
- Terminal-period rule (no separate config value; it's a fixed check, not a tunable
  threshold): a block whose text ends in `.` is never promoted, `?` is exempt.

`RoleClassifier.isHeadingShapeImplausible(group, text)` combines all three; failing any
one keeps the block at its pre-existing PARAGRAPH/CAPTION fallback instead of
TITLE/HEADING. It is consulted before every height-driven TITLE/HEADING branch in
`roleOf`; when it returns true the block falls through to the CAPTION check (which
rejects on height anyway) and then to PARAGRAPH.

## Known residual gap (documented, not fixed)

Word count cannot separate an 8-9 word Definition cell from a 9-word genuine heading —
that information genuinely isn't in the text. Three Definition cells remain mislabelled
under the new rule:

- `HEADING`, 8 words — "the process of distributing money, resources, or
  responsibilities"
- `HEADING`, 9 words — "the presence or introduction of harmful substances or
  organisms"
- `TITLE`, 8 words — "supervision, monitoring, or responsibility for ensuring proper
  performance"

Lowering `headingMaxWordCount` below 9 to catch these would also exclude the real
9-word heading, which under the stated priority (a wrong label is worse than none, but
losing a true heading is the lesser harm — *except when the fix itself introduces a
different false label*) is not a clear net improvement: it trades three known false
positives for one known false negative on a signal that cannot tell them apart. I left
these three as an explicitly documented gap rather than force a fix using a signal the
evidence shows doesn't work. No test asserts a specific role for these three blocks
either way, so this is not a regression against anything the suite currently checks —
it is an honestly reported limit, consistent with this codebase's existing "KNOWN
RESIDUAL GAP" comments (e.g. `DocumentStructurerTest`'s marker-loss gap for question 4).

## Tests added

`RoleClassifierTest.kt` (synthetic, unit-level):
- `a tall multi-word sentence-shaped block is not promoted to a heading` — 10-word tall
  single-line block, no terminal punctuation; proves the word-count guard alone.
- `a tall block ending in a terminal period is not promoted to a heading` — 4-word tall
  block ending in "."; proves the terminal-period guard alone.
- `a tall block spanning more than the heading line limit is not promoted` — three
  short lines (well under the word ceiling, no terminal punctuation) that merge into one
  3-line block; proves the line-count guard alone.
- `a short heading ending in a question mark is still eligible to be a heading` — the
  real fixture's own 9-word "?"-terminated heading text, placed among body paragraphs;
  proves none of the three new guards exclude a genuine heading (required test #3).

`DocumentStructurerTest.kt` (real fixture, end-to-end):
- Extended `golden end-to-end fixture - background clutter is dropped and numbered
  questions are addressable` (the `real-worksheet-exercises.json` golden test) with an
  assertion block checking all three of the task's named texts come back `PARAGRAPH`,
  including the one that was already correct before this change (to catch a future
  threshold regression on it too).

Existing tests left untouched and still passing without modification:
- `a genuinely larger heading among normal text is still a heading` (the "Bagian Kedua"
  guard against over-correction) — passes unchanged; 2 words, no terminal punctuation,
  1 line, comfortably inside all three new guards.
- Both golden end-to-end fixtures (`real-worksheet-reading.json`,
  `real-worksheet-exercises.json`) pass unchanged except for the new assertions added
  to the latter — no existing expectation in either changed.

## Full final test output

`:ocr-core:test` — 111 tests total (105 pre-existing/modified + 6 new: 4 in
`RoleClassifierTest`, plus the extended assertions in the existing
`DocumentStructurerTest` golden test, which added assertions rather than a new test
method), 0 failures:

```
BUILD SUCCESSFUL in 8s
5 actionable tasks: 5 executed
```

`:ocr-mlkit:testDebugUnitTest` — 14 tests, 0 failures:

```
BUILD SUCCESSFUL in 5s
18 actionable tasks: 5 executed, 13 up-to-date
```

`:app:assembleDebug`:

```
BUILD SUCCESSFUL in 4s
59 actionable tasks: 5 executed, 54 up-to-date
```

`:ocr-mlkit:connectedDebugAndroidTest` on AVD `Pixel_10_Pro`:

```
Starting 4 tests on Pixel_10_Pro(AVD) - 17
id.dotcode.braille.ocr.mlkit.OcrAccuracyHarnessTest > measuresCharacterAndWordErrorRateAgainstSampleWorksheets[...] SKIPPED
Finished 5 tests on Pixel_10_Pro(AVD) - 17
BUILD SUCCESSFUL in 29s
```

(The one `SKIPPED` test is a pre-existing accuracy harness gated on sample assets, unrelated to this change and unaffected by it.)

## Which true headings are now missed

None, as far as either real fixture demonstrates. `headingMaxWordCount = 9` was chosen
specifically to preserve the only genuine heading known to this codebase that pushes
close to a word-count boundary. No true heading in the test suite or either real
fixture is newly excluded by this change.

## Guard against over-correction

The pre-existing test `a genuinely larger heading among normal text is still a heading`
was **not modified** and still passes: "Bagian Kedua" (2 words, 1 line, no terminal
punctuation) clears all three new guards easily, confirming the fix did not simply
disable heading detection.

## Anything still wrong

- The three residual-gap Definition cells above remain mislabelled `HEADING`/`TITLE`.
  See "Known residual gap."
- This fix only guards the height-driven TITLE/HEADING branches in `roleOf`. It does not
  attempt table-structure detection (e.g. recognizing a "No. | Term | Definition"
  column layout), which would be the more complete fix for the residual gap but is a
  materially larger change than this task's scope.
- No new defects were found in the surrounding code while doing this work.
