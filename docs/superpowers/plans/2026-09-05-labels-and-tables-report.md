# Report: false labels on a missing-marker question and a vocabulary table's row numbers

Date: 2026-09-05
Branch: `feat/ocr-mvp`
Commits: `b3ad142` (Defect A), `064a306` (Defect B)

## Summary

Three defects were investigated, all observed by running the real app against a real
photographed worksheet and diagnosed from the committed recognizer fixtures
(`real-worksheet-exercises.json`, `real-worksheet-reading.json`) — no speculation.

- **Defect A (fixed):** question 4, whose leading "4." marker OCR never recognized,
  was labelled TITLE by height alone. Fixed in `RoleClassifier`.
- **Defect B (fixed):** the vocabulary table's row-number ("No.") column merged
  adjacent numbers into one block (`"18 19"`, `"20 21"`, `"23 24"`) and mislabelled a
  lone survivor (`"22"`) as CAPTION. Fixed in `LineMerger` and `RoleClassifier`.
- **Defect C (investigated, NOT implemented):** a row-number gutter signal for
  splitting table rows during paragraph merging. The signal is unreliable in this
  fixture — see the evidence below — so no change was made. Table-row prose merging
  remains the existing, accepted behaviour.

A fourth, related-but-out-of-scope defect was found while investigating B and is
reported at the end rather than fixed, per the instructions.

## Defect A: question 4 labelled TITLE

### What the fixture showed

`real-worksheet-exercises.json`'s last question (the "poorly managed" / "universal
meal program" question, question 4 of the True/False section) has no "4." anywhere in
the raw ML Kit output — confirmed by grepping the fixture for "4." near that text and
finding none. `MarkerParser.parse` therefore returns `null` for it, so it falls
through past the marker-based QUESTION/LIST_ITEM branch straight to height-based
classification in `RoleClassifier.roleOf`.

Pre-fix block dump (block IDs from `DocumentStructurer.structure`):

```
#38 LIST_ITEM  marker=B.  :: True or False: Evidence-Based Reading Instructions...
#39 QUESTION   marker=2.  :: The passage states that providing free meals...
#40 QUESTION   marker=3.  :: If the program is poorly managed...
#39 CAPTION    marker=null :: Reason:
#40 TITLE      marker=null :: Auniversal meal program could help reduce inequalities...
```

(IDs above are from the pre-fix dump; the last question sits at index 40, its own
recognized height comfortably clearing `titleHeightRatio` (1.6x) against its local
baseline — the same perspective/font-size artifact that made other blocks measure
inconsistently throughout this photo.) A blind student reading this document would be
told "this is the page's title" about an exam question — worse than no label at all,
per the project's stated priority.

### The rule implemented

`RoleClassifier.classify` now runs a second pass after every block's preliminary role
is decided (marker-based roles and height-based TITLE/HEADING/CAPTION/PARAGRAPH are
all resolved first). For any block that came out TITLE or HEADING, if a QUESTION
block sits within `StructuringConfig.questionRunAdjacencyWindow` (default 2) blocks
**before** it, in the same column, at the same indent level, the block is demoted to
PARAGRAPH.

Design decisions, with the reasoning:

- **Window > 1:** a "Reason: ______" answer line commonly sits between one question
  and the next (as it does here — the immediate predecessor of the mislabelled block
  is the CAPTION "Reason:", not a QUESTION). A window of exactly 1 (nearest neighbour
  only) would miss this case entirely; 2 reaches past exactly one intervening block.
- **Backward-only, not symmetric:** the first version of this rule scanned both
  directions and broke an existing golden-fixture test
  (`a centred full width title is the first block in reading order`): a worksheet's
  own document title legitimately sits immediately above its first question, at the
  same indent, and a symmetric window demoted it to PARAGRAPH the instant the
  question run started right after it. "Sitting in a run of questions" means coming
  during or after the run has already been established, not merely sitting next to
  its start — a real title is never preceded by an established question.
- **Same indent level, not raw left-edge equality:** the mislabelled block's raw box
  left (296) differs from the questions' raw box left (~235-241) by roughly the width
  of a missing "4." marker — comparing raw pixels directly would not match. Both
  values happen to saturate to the same `maxIndentLevel` (4) once quantized through
  the existing `indentLevelOf` formula, which is exactly the shared abstraction
  `DocumentStructurer` already used to report indent on `TextBlock`. `indentLevelOf`
  is now a single function on `RoleClassifier`, used by both places, instead of two
  independent copies of the same formula.

Post-fix: block 40 above comes out PARAGRAPH. Its `marker` stays `null` — the missing
"4." is upstream OCR data loss that cannot be recovered, and the fix does not
manufacture one.

### Why this rule and not something broader

The exercises fixture also contains several vocabulary-table "Definition" cells
mislabelled TITLE/HEADING by the same underlying height-variance mechanism (see the
"Found but not fixed" section below). Those blocks are not adjacent to any QUESTION
block, so the rule above does not touch them, deliberately: fixing the narrower,
specified pattern (a block in a run of QUESTION blocks) keeps the change auditable
against the one behaviour actually described, rather than broadening the rule until
it happens to also fix a different, unrelated mislabelling pattern by coincidence.

## Defect B: table row numbers merge and mislabel

### What the fixture showed

The vocabulary table's "No." column is a run of single, bare-numeric raw lines: `"5"`,
`"7"`, `"8"`, `"10"` … `"24"`, each its own ML Kit line, confirmed present verbatim in
the raw fixture (`grep '"text": "18"'` etc. — all present as standalone lines).

Two things went wrong downstream:

1. **`LineMerger` merged adjacent bare numbers.** `MarkerParser.parse("19")` returns
   `null` (there is no remainder text after a bare number for the NUMERIC regex to
   split off), so a bare number is not excluded by the existing
   `MarkerParser.parse(next.line.text) != null` guard. Two bare numbers stacked
   vertically with a tight leading gap and aligned left edges satisfy every other
   paragraph-continuation test `LineMerger.continues()` runs, so they merged:
   pre-fix, blocks read `"15 16"`, `"18 19"`, `"20 21"`, `"23 24"` — none of which
   identifies either row it fuses.
2. **A lone bare number fell through to CAPTION.** Row 22's number happened not to
   merge with a neighbour (its neighbours on both sides DID merge with theirs — see
   the pre-fix dump: `#29 CAPTION :: 22`), so it survived as its own block, then fell
   through the marker check to height-based classification and came out CAPTION —
   asserting something specific and false about what "22" is.

### The rule implemented

- `LineMerger.continues()` gained an explicit check: two lines that are both bare
  numbers (`^\d{1,4}$`, matched independently) never continue one block, regardless
  of column/gap/alignment agreement. This is a structural pattern check in the same
  style as the existing `TERMINAL_PUNCTUATION_REGEX`/`HYPHEN_WRAP` companion regexes,
  not a new tunable — there is no meaningful threshold to expose, only a yes/no
  structural fact about the two strings.
- `RoleClassifier.roleOf` gained an explicit check, placed right after the existing
  `isPageNumber` branch: a single-line block whose text is a bare number (reusing the
  same `PAGE_NUMBER_REGEX` pattern, since "a number and nothing else" is the same
  shape whether or not it's in the bottom band) is routed straight to PARAGRAPH,
  before any height/caption logic runs. PARAGRAPH is the project's existing "honest
  fallback" for every other ambiguous case (see the CAPTION/TITLE KDoc already in
  this file) — a bare number by itself is not confidently a caption, heading, or
  title, so it gets no confident label at all.

Post-fix dump (relevant portion): `"5"`, `"7"`, `"8"`, `"10"`, `"11"`, `"12"`, `"13"`,
`"15"`, `"16"`, `"18"`, `"19"`, `"20"`, `"21"`, `"22"`, `"23"`, `"24"` are each their
own block, every one PARAGRAPH.

### What this fix does NOT cover (confirmed absent or separately caused)

- Rows **6** and **9**: their numbers are not present ANYWHERE in the raw fixture
  (`grep '"text": "6"'` / `'"text": "9"'` returns nothing) — OCR never recognized
  them at all. This is the same class of unrecoverable data loss as question 4's
  missing marker in Defect A, not a structuring defect.
- Rows **14** and **17**: their numbers ARE present, but `RowFragmentJoiner` fuses
  them onto the FOLLOWING row's definition text (`"14 to become worse in quality or
  condition"`, `"17 poor, inefficient, or improper management"`) because the number
  and that definition happen to overlap vertically by more than
  `rowOverlapFraction` and sit within `rowFragmentGapFactor` horizontally — the exact
  same-row heuristic that (correctly) fuses a crease-split line. This is a genuine,
  separate defect (see "Found but not fixed" below), not the merging-with-a-neighbouring-number
  defect this task asked for.

## Defect C: is the row-number gutter a reliable signal for splitting table rows?

**Verdict: NOT reliable in this fixture — the row-number gutter does not line up
consistently with row boundaries, so it was not implemented; table-row prose merging
is left as the existing, accepted behaviour.**

### Evidence

The instructions proposed using the row-number gutter (a vertical run of short
numeric-only lines to the left of the Definition column) to mark each definition
line's own row band, suppressing paragraph merging across that band. Testing this
idea against the actual geometry in `real-worksheet-exercises.json` shows the gutter
does not behave as one-number-per-row-boundary:

1. **Some row numbers fuse with a DIFFERENT row's definition, not their own**, via
   `RowFragmentJoiner`'s same-row heuristic (rows 14 and 17, above). If the gutter
   were used to mark row bands, row 14's number's vertical band would incorrectly
   claim to bound the definition of a DIFFERENT row's content that RowFragmentJoiner
   has already (independently, and for defensible same-row reasons) glued onto it.
   Building a row-splitting rule on top of a signal that is itself inconsistently
   fused to the wrong content would encode that inconsistency into a "fix."
2. **Two consecutive numbers are absent entirely** (rows 6 and 9) — OCR data loss.
   A gutter-based splitting rule has no band to anchor on for those rows at all, so
   it would necessarily miss splitting exactly the rows most likely to need it (their
   definitions are exactly the ones observed merging across rows, e.g. block #8:
   `"a lack of important nutrients in the body the extent to which something is
   common or wides[pread]"` — row 5's definition concatenated with row 6's, whose
   gutter number does not exist to anchor a split).
3. **The numbers that DO survive standalone don't reliably anchor a definition's
   START.** Compare row 15's number (top=835, per the post-Defect-B dump) against its
   own definition text's line span versus row 16's — on a table with variable
   row heights (the Definition column wraps to 1, 2, or 4 lines depending on the
   word), the numeric gutter cell's vertical position is set by the row's OWN
   fixed-height cell rendering, while the definition text's vertical position floats
   with however many lines it wrapped to. The two columns' rows are not
   height-synchronized in the OCR'd geometry, so "which definition line is at the
   same height as row N's number" is not a reliable question to ask of this data at
   all — sometimes the number sits above the correct definition, sometimes level
   with the previous row's tail line.

Given points 1–3, a gutter-based row-splitting rule built from this fixture's
geometry would either (a) fail to fire on the very rows most in need of splitting
(6, 9 — no gutter cell to anchor on), or (b) risk anchoring to the wrong row's
content where `RowFragmentJoiner` has already fused a number to a neighbour's text
(14, 17). Per the instructions' own standard — "a wrong split of real prose is worse
than a missed table split" — implementing this signal here would very plausibly
produce confidently wrong splits on exactly the ambiguous rows, which is worse than
the current behaviour of leaving them merged (still recoverable by a sighted-adjacent
reading of the whole block, whereas a wrong split asserts a row boundary that isn't
there).

**No code changes were made for Defect C.** The existing golden test on
`real-worksheet-reading.json` (pure prose, no table) is unaffected because no
table-related code path was touched.

## Tests added

- `LineMergerTest`:
  - `two stacked bare row numbers do not merge into one block (real worksheet regression)`
  - `a bare number followed by ordinary prose still merges normally`
- `RoleClassifierTest`:
  - `a tall block one Reason line past the last recognized question is not a title`
  - `an unrelated tall heading far from any question is unaffected`
  - `a bare row number is a paragraph, not a caption`
- `DocumentStructurerTest` (extended the existing golden `real-worksheet-exercises.json`
  test):
  - asserts the last block's role is PARAGRAPH, not TITLE (Defect A)
  - asserts every recoverable row number (5, 7, 8, 10-13, 15, 16, 18-24) is its own
    PARAGRAPH block, and that none of `"18 19"`, `"20 21"`, `"23 24"` appears anywhere
    in the document (Defect B)

All were written and watched fail against the pre-fix code, then made to pass by the
implementation, per TDD. The `real-worksheet-reading.json` golden test (pure prose,
no numbered questions, no table) required zero changes and continued passing
throughout — confirmed by running it in isolation after each change.

## Full final test output

```
:ocr-core:test        — 105 tests, 0 failures, 0 errors
:ocr-mlkit:testDebugUnitTest — 14 tests, 0 failures, 0 errors
:app:assembleDebug     — BUILD SUCCESSFUL
:ocr-mlkit:connectedDebugAndroidTest (Pixel_10_Pro AVD) — 4 tests, 0 failures, 1 skipped, 0 errors
```

(The 1 skipped instrumented test predates this work and is unrelated —
`CaptureQualityGateRealPhotosTest` skips one case not applicable in this environment;
not touched by this change.)

## Found but not fixed

A fourteenth-class finding, discovered while diagnosing Defect B/C but explicitly out
of scope for this task (the task named only the QUESTION-adjacency TITLE case, the
row-number merging/CAPTION case, and the row-splitting investigation):

**Several vocabulary-table "Definition" cells are mislabelled TITLE or HEADING by the
same height-variance mechanism as Defect A, but are not adjacent to any QUESTION
block, so Defect A's fix does not (and by design should not) touch them.** Examples
from the post-fix dump: block "the state of being mentally attentive and able to
respond quickty..." → TITLE; "wealthy or financially comfortable..." → TITLE;
"the process of distributing money, resources, or responsibilities" → HEADING; and
several others in the same table. A blind student reading this table would be told
several ordinary vocabulary definitions are the page's title or a section heading.
Fixing this properly likely needs a table-aware signal (e.g., "a block whose column
also contains a numeric row-number run at a matching row band is never TITLE/HEADING
regardless of height" — related to, but not the same as, the Defect C gutter idea
this report found unreliable for row-splitting; it may still be usable for role
suppression alone, which does not require the geometrically precise per-row band
alignment that splitting does). Left for a follow-up rather than folded into this
change, to keep Defect A's fix narrowly scoped to what was actually asked for and
auditable against the fixture evidence above.

Also found and left for the same follow-up: rows 14 and 17's numbers being fused by
`RowFragmentJoiner` onto the WRONG row's definition text (see Defect B's "not
covered" section above) — a same-row-heuristic false positive distinct from (and
smaller in scope than) the crease-split case `RowFragmentJoiner` was built for.
