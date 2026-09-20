# BRaiLLE OCR — Testing, Accuracy and Fine-Tuning Report

**Date:** 2026-09-20 · **Scope:** unit tests, app-level accuracy measurement, Tesseract fine-tuning with overfitting control

---

## 1. Headline numbers

> **Updated 2026-09-20 (later session).** The reading-order fix is now on by default and
> re-measured. The unit-test count and §4.5.1 have been updated with it.

All accuracy figures below come from the app's own instrumented harness
(`OcrTextDumpHarnessTest` / `OcrAccuracyHarnessTest`) running the **real pipeline** — ML Kit
as primary recognizer, Tesseract as second reader, plus the offline correction layer — on a
headless Android emulator. They are not standalone-Tesseract numbers.

### Where the error goes, lever by lever

Diagnostic corpus, **n = 14 pages, 15,597 reference characters**. Each row adds one lever to
the row above it, so the deltas are separable.

| Lever | CER | WER |
| --- | --- | --- |
| Recognizer only (ML Kit, no correction, no reordering) | 18.54% | 29.04% |
| + offline correction layer | 18.49% | 28.73% |
| **+ reading-order fix** (`rebuildRowsFromWords`, this session) | **15.44%** | **25.23%** |
| + Gemini online pass | *not measured — see below* | |

The ordering fix is worth **−3.05 pp CER and −3.50 pp WER**, roughly **60× the offline
correction layer's contribution** on the same corpus. 3 pages improved, 11 unchanged, 0
made worse. Full per-page table and the acceptance criteria in §4.5.1.

The Gemini row is blank because the free-tier daily quota was exhausted mid-measurement
(§4.6). A partial run under the **current** prompt, before the quota ran out, showed
`20260919_222813` improving 30.75% → 22.64% and most other pages moving under 1 pp; that
run is indicative only — it was not scored through the app's own `MAX_CHANGE_RATIO`
acceptance guard, which is what decides whether the app actually keeps a correction.

| Other measurements | Value |
| --- | --- |
| JVM unit tests | **340 run, 336 passed, 0 failed, 4 skipped** |
| App CER, all 36 labelled pages (earlier corpus, pre-fix) | 12.67% (WER 19.66%) |
| Committed ground-truth pages (n=4) | 3.19% CER |
| Out-of-distribution holdout (n=5) | 12.51% CER |

### The caveat that governs every number above

**The reference transcripts are `AI_TRANSCRIBED`, not human-verified.** Every figure on this
page except the four committed ground-truth worksheets is therefore AI-vs-AI: it measures
agreement with another model's reading of the page, not correctness. The deltas are still
meaningful — before and after are scored against the *same* reference, so a flawed
reference largely cancels — but the **absolute** levels are not defensible and should not be
quoted as accuracy. Fixing this is the single highest-value remaining task and it needs a
human: see §5.

---

## 2. What was built and measured

### 2.1 Dataset

The supplied folder grew to 506 photos across five capture sessions (two phones,
three books, one worksheet set). Labels are the bottleneck, not images.

| Item | Count |
| --- | --- |
| Source photos supplied | 506 |
| Labelled transcripts at session start | 16 |
| Labelled transcripts now | **37** |
| Committed ground-truth pairs (pre-existing) | 4 |
| Total transcript volume | ~48,000 characters |

Every transcript added carries `transcriptionSource: AI_TRANSCRIBED` in its
`meta.json`, matching the repo's existing schema. **None are human-verified.**
This is the most important caveat in the report and is discussed in §5.

### 2.2 Splits — designed to prevent leakage

`make_splits.py`, seed 20260920, 60/20/20, **stratified within capture session**.
Consecutive pages of one book photographed in one sitting are highly correlated;
a naive random split would put near-duplicates in both train and test and make
the test number look better than it is.

| Split | Pages | Purpose |
| --- | --- | --- |
| train | 20 | fine-tuning only |
| val | 6 | checkpoint selection only |
| test | 6 | scored once, never used for any decision |
| holdout | 5 | labelled *after* splits froze; unseen camera and sessions |

---

## 3. App-level accuracy

| Group | n | chars | CER (recognizer) | CER (corrected) | WER (corrected) |
| --- | --- | --- | --- | --- | --- |
| All labelled pages | 36 | 42,366 | 12.75% | **12.67%** | 19.66% |
| train split | 20 | 24,544 | 10.60% | 10.55% | 16.45% |
| val split | 6 | 7,477 | 11.45% | 11.22% | 18.82% |
| test split | 6 | 7,747 | 23.97% | 23.97% | 31.32% |
| committed seed GT | 4 | 2,598 | 3.27% | 3.19% | 18.33% |
| OOD holdout | 5 | 8,638 | 12.59% | 12.51% | 18.41% |

**Read the spread, not just the mean.** The test split's 23.97% is not evidence
of overfitting — nothing was trained when these were measured. It reflects which
pages landed there: contents pages with dotted leaders, a heavily shadowed page,
and a two-column spread. With only six pages per split, split-to-split variance
dominates. The out-of-distribution holdout at 12.51% — a different phone and
different books, labelled after the splits were frozen — is the better
generalization signal.

### 3.1 The correction layer

| Metric | Value |
| --- | --- |
| Corrections applied across 36 pages | 37 |
| Pages improved | 10 |
| Pages unchanged | 25 |
| Pages made worse | 1 |
| CER gain | 0.08 pp (12.75% → 12.67%) |
| WER gain | 0.41 pp (20.07% → 19.66%) |

The layer is **net positive but small**, and it helps word accuracy more than
character accuracy — expected, since it repairs whole misread words. Good fixes:
`Scorang → Seorang`, `kapastas → kapasitas`, `talbungan → tabungan`,
`laryer → lawyer`. Bad fixes it also made: `aears → a ears`, `mudian → median`
(the true word was `kemudian`), `ditelaah → ditelan`. Precision, not recall, is
this layer's weak spot.

### 3.2 Timing (emulator — not representative of a phone)

| Stage | Median |
| --- | --- |
| ML Kit | 10,388 ms |
| Tesseract second read | 11,298 ms (max 17,985 ms) |
| Correction | 35 ms |

A headless emulator on swiftshader is roughly an order of magnitude slower than
a real phone. Use these only to compare stages against each other, never as a
user-facing latency claim.

---

## 4. Fine-tuning the Tesseract second reader

### 4.1 Getting line-level training data

Tesseract's LSTM trains on (line image, line text) pairs; the labels here are
page-level. `build_lines.py` bootstraps line GT: Tesseract line boxes → difflib
word alignment against the page transcript → each line receives a contiguous
slice of the reference, with slices tiling the page.

| Split | Lines kept | Lines dropped |
| --- | --- | --- |
| train | 316 | 231 |
| val | 68 | 100 |
| test | 91 | 156 |

**A first version of this silently truncated labels.** Projecting only the span
between *matched* anchors cut off any line whose first or last word was misread
(`'...sertifikasi mutu internasi'` → GT `'...sertifikasi mutu'`), which would have
taught the model to stop early. Caught by inspecting the highest-disagreement
lines before training, then fixed by tiling instead of anchoring. Roughly 39% of
candidate lines are still dropped by quality filters — deliberately conservative,
because a wrong line label is worse than a missing one.

### 4.2 Training and early stopping

`lstmtraining` fine-tuned from the `tessdata_best` Indonesian checkpoint
(`tessdata_fast`, which the app ships, cannot be fine-tuned). Training was
stopped when training error plateaued, then **checkpoints were ranked on the
validation split** and only the winner was scored on test.

| Checkpoint | Val BCER | Selected |
| --- | --- | --- |
| baseline (no fine-tuning) | 19.96% | |
| iteration 84 | 19.35% | |
| iteration 150 | 17.38% | |
| iteration 208 | 16.85% | |
| **iteration 415** | **16.19%** | ✅ |
| iteration 619 | 16.60% | overfitting begins |

Validation error falls to iteration 415 and then rises — a textbook early-stopping
curve, and the reason model selection was done on val rather than on training loss.

### 4.3 Held-out result (component level)

| Model | Test BCER | Test BWER |
| --- | --- | --- |
| Baseline `ind` | 16.62% | 26.07% |
| Fine-tuned (iter 415) | **14.13%** | **21.43%** |
| Relative improvement | −15.0% | −17.8% |

This is measured on the test split, which was never used for training or
checkpoint selection.

### 4.4 Does it help the *app*? No measurable change.

The fine-tuned model was packaged into the app (verified: 8,253,606 bytes in the
test APK) and the harness re-run. On the four committed ground-truth pages — the
only pages that completed under **both** models — output was **identical to the
last decimal**:

| Page | Shipped model CER | Fine-tuned CER |
| --- | --- | --- |
| IMG-20260819-WA0006 | 3.38% | 3.38% |
| IMG-20260821-WA0034 | 6.54% | 6.54% |
| IMG-20260821-WA0035 | 3.26% | 3.26% |
| IMG-20260821-WA0036 | 0.46% | 0.46% |
| **Aggregate** | **3.19%** | **3.19%** |

This is the expected result once you trace the architecture: **Tesseract is not
the app's recognizer.** ML Kit produces the text; Tesseract supplies a second
reading that only feeds the correction vote, which changed 37 words across 36
pages. A better second reader can only help where it disagrees with ML Kit *and*
the spell corrector acts on that disagreement — a narrow path.

Two further practical blockers surfaced:

- The `tessdata_best` model is **7.4× larger** (8.25 MB vs 1.12 MB) and slower.
- Attempting the full 11-page A/B crashed the instrumentation process on a 2 GB
  emulator. That is not proof of an on-device OOM, but it is a warning sign for
  a float model on low-end hardware — exactly the devices this project targets.

**Conclusion: the fine-tune is a genuine component-level win and a non-result at
product level.** Reported as such rather than presented as an accuracy gain.

---

## 4.5 The main finding: two thirds of the error is word ordering, not recognition

Added `OcrTextDumpHarnessTest` (same pipeline as the accuracy harness, but it writes
the actual text out instead of only an error rate) and diagnosed 14 pages.

**Error composition across 15,597 reference characters:**

| Type | Share of reference length |
| --- | --- |
| Insertions | 9.9% |
| Deletions | 9.2% |
| **Substitutions** | **3.6%** |

Substitutions are low and insertions ≈ deletions. That is the signature of
*reordering*, not misreading: the same words appear, in the wrong places.

Confirmed directly by re-scoring word error with order ignored (multiset
difference instead of edit distance):

| Page | WER | WER, order ignored | Explained by ordering |
| --- | --- | --- | --- |
| 20260731_230858 | 30.89% | 3.09% | **90%** |
| IMG_5720 | 26.64% | 4.67% | 82% |
| 20260919_222952 | 45.15% | 8.74% | 81% |
| IMG-20260821-WA0034 | 28.57% | 8.16% | 71% |
| 20260919_222813 | 41.05% | 12.23% | 70% |
| 20260731_230849 | 65.00% | 20.00% | 69% |
| 20260919_223954 | 55.11% | 17.56% | 68% |
| **Corpus** | **28.38%** | **9.66%** | **66%** |

What it looks like in practice, from `20260731_230858`:

> **Reference:** Tujuh puluh lima tahun adalah sebuah perjalanan panjang yang sarat akan dedikasi, cinta kasih, dan pengabdian tanpa henti. Kisah luhur ini bermula pada 17 Juli 1951…
>
> **App output:** Tujuh puluh *panjang yang* lima tahun adalah sebuah perjalanan *dedikasi, cinta* tanpa henti. Kisah luhur *sarat kasih, dan pengabdian akan* 1951, ini bermula pada *Juli 17*…

Nearly every word is recognised correctly. The order is wrong. "17 Juli" became
"Juli 17". Phrase fragments are interleaved.

**Why this matters more than the CER number suggests.** For a blind reader, a
scrambled word order is worse than a few misread characters — a misread word is
recoverable from context, a shuffled sentence is not. This is a *readability*
failure, not just an accuracy one.

**Where it lives:** `ocr-core/pipeline` — `RowFragmentJoiner`, `LineMerger`,
`ReadingOrderSorter` — on curved pages where a single printed line does not sit on
one straight row. This is the same defect class `FoldedPageAngleFilter`'s own header
describes as "very messy… all just mixed up". It is your own code, no new model
required, and it is worth roughly two thirds of your current word error.

The pages that are already good are good: `20260919_224351` at 1.21% CER,
`IMG-20260821-WA0036` at 0.46%. The bad pages are bad almost entirely because of
ordering.

### 4.5.1 The fix, measured and now on by default

> **Updated 2026-09-20 (later session).** This section previously reported the rebuild as
> net-zero and shipped off. That result stood on one unresolved interaction, which has
> since been fixed and re-measured. The superseded numbers are kept at the end of the
> section so the change is auditable.

`WordRowRebuilder` re-derives printed rows from word boxes. It seeds a row at the leftmost
unused word and grows it rightward, picking the next word by horizontal gap plus twice the
vertical step. Comparing against the row's *last* word is what lets a row follow curvature:
each step is short, so drift accumulates along the chain instead of being measured against
a fixed baseline. On the target page it turns 53 broken lines into 31 correctly ordered rows.

It is **self-gating**. A page is only rebuilt when some line skips over text that physically
sits inside it — a wide intra-line gap containing two or more words from *other* lines at
the same height. A wide gap alone is not evidence (contents-page leaders and justified text
both produce them). Across the 10 diagnostic captures the gate fires on 3 and declines 7.

**What unblocked it.** The old regression was real: on an open-book spread, rebuilding
absorbed the facing page's cut-off fragments into long rows, which destroyed the "short
line at the frame edge" signal `FrameEdgeFragmentFilter` uses to drop them. The fix is to
make that judgement on **words**, before any regrouping, so it no longer depends on a
grouping the rebuilder is about to replace:

- `FrameGeometry.uprightWidth(raw, rotationDegrees)` — one definition of which recorded
  dimension bounds the reading direction. ML Kit is handed the rotation up front, so its
  boxes come back upright while `imageWidth`/`imageHeight` still describe the unrotated
  bitmap. On a quarter-turn capture the true upright width is the recorded **height**.
- `FrameEdgeFragmentFilter.edgeWords(...)` — the same judgement as `filter`, expressed over
  words. The rejected words never enter the pool rows are built from.

**Measured end to end on 14 pages, flag on** (`artifacts/app-text-dump.json` vs
`artifacts/app-text-dump-task5.json`, `tools/ocr_eval/compare_dumps.py`):

| Page | CER before | CER after | |
| --- | --- | --- | --- |
| 20260731_230858 | 23.16% | **0.74%** | −22.42 pp |
| 20260919_223954 | 38.76% | **37.09%** | −1.67 pp |
| 20260919_224138 | 2.36% | **0.74%** | −1.61 pp |
| 11 other pages | unchanged | unchanged | gate did not fire |
| **Corpus** | **18.49%** | **15.44%** | **−3.05 pp** |
| **Corpus WER** | **28.38%** | **25.07%** | **−3.31 pp** |

**3 pages improved, 11 unchanged, 0 made worse.** The 11 unchanged pages are byte-identical
to the baseline dump, which is what validates the measurement: the gate provably touched
only the pages it claimed to.

`20260919_223954` — the spread that previously regressed to 67.65% — now *improves*. Its
ordering share of word error fell from 68% to 67%, and the target page `20260731_230858`
fell from **90% to 6%**: where the rebuild fires, it removes essentially all the ordering
error on that page.

**Against the plan's acceptance criteria, 3 of 4 hold:**

| # | Criterion | Result |
| --- | --- | --- |
| 1 | Corpus CER at or below 14.5% | ✗ **15.44%** — missed by 0.94 pp |
| 2 | `20260731_230858` at or below 1.5% | ✓ 0.74% |
| 3 | `20260919_223954` no worse than 39% | ✓ 37.09% |
| 4 | No page regresses by more than 1 pp | ✓ no page regressed at all |

**Why criterion 1 missed, and what it costs.** The gate is conservative by design, and the
remaining corpus error sits almost entirely on pages it declines. Per-page cross-chain
evidence from the raw dumps:

| Page | max intruders in any gap | gate | ordering share of WER |
| --- | --- | --- | --- |
| 20260919_222813 | 1 | declines | 70% |
| 20260919_222952 | 1 | declines | 81% |
| 20260731_230849 | 0 | declines | 69% |
| IMG_5720 | 0 (no intra-line gaps at all) | declines | 82% |

Two pages sit at *exactly one* intruder — one below the `crossChainMinIntruders = 2`
threshold. Lowering it to 1 was measured: it improves `20260919_222813` by 4.84 pp and
`20260919_222952` by 2.13 pp with no page worse, **but it breaks
`an open book's facing page is discarded, not read as a second column`** — one of the seven
tests the plan forbids re-baselining. On `real-facing-page-231108-raw.json` the gate then
fires and the facing page survives as a second column, because that page's facing surface is
rejected by *angle* (`FoldedPageAngleFilter` / `ColumnSegmenter`), not by frame-edge
geometry, and rebuilding changes the angles. **So the threshold stays at 2**, and this is
recorded as a measured dead end rather than a tuning opportunity.

`IMG_5720` is a separate failure mode worth noting: it has **zero** intra-line gaps across
213 words — every adjacent word pair overlaps horizontally — so the gap-based gate cannot
see anything on it at all, despite an 82% ordering share. Whatever is wrong there is not
cross-chaining as this stage defines it.

<details>
<summary>Superseded: the pre-fix measurement (kept for audit)</summary>

Before word-level facing-page rejection, the same flag measured:

| Page | CER before | CER after |
| --- | --- | --- |
| 20260731_230858 | 23.16% | 0.74% |
| 20260919_224138 | 2.36% | 0.74% |
| 20260919_223954 | 38.76% | **67.65%** |
| **Corpus** | **18.49%** | **18.50%** |

One page improved by 22 pp, one regressed by 29 pp, and the corpus netted out at zero. The
regression was 469 characters of facing-page text surviving into the output. That is the
interaction the `edgeWords` change fixes, and it is now pinned by
`CurvedPageWordOrderTest.an intruding facing page is not absorbed into a rebuilt row`
against a committed fixture of that exact page (`real-spread-223954-raw.json`), which
reproduces 38.76% → 67.58% on the JVM with the fix removed.

</details>

### Two hypotheses this disproved

Both were mine, and both were wrong:

1. **"The app needs page isolation."** It already has four mechanisms —
   `FoldedPageAngleFilter`, `FrameEdgeFragmentFilter`, `MarginFragmentFilter`, and
   `ColumnSegmenter`'s facing-page angle check. Facing-page bleed does still appear
   on two pages (`20260919_222813` shows a 127-character run of cut-off facing-page
   text), but it is a minor contributor next to ordering.
2. **"Contents-page leader dots inflate CER."** Re-scoring with dot runs stripped
   from both sides changed the contents pages by *nothing* (38.88% → 38.88%). The
   app is not emitting the leaders. The 2.22× character inflation I measured earlier
   was a standalone-Tesseract artifact, not app behaviour.

---

## 4.6 The Gemini correction pass, measured

Measured off-device against the app's exact protocol — `GeminiCorrector.INSTRUCTIONS`
verbatim, `gemini-3.5-flash`, `temperature 0`, the same JSON-array response schema, and
the same one-string-per-block payload (`DocumentFlattener` joins blocks with `\n`). Text
only; no images left the machine, matching the app's own rule. Input is the offline-
corrected output already dumped from the device, so this isolates what Gemini adds.

**Primary model, `gemini-3.5-flash`, 10 pages, 11,808 reference characters:**

| Metric | Offline only | + Gemini | Change |
| --- | --- | --- | --- |
| CER | 16.56% | **14.19%** | −2.36 pp (−14.3% relative) |
| WER | 26.63% | **22.76%** | −3.87 pp (−14.5% relative) |

7 pages improved, 1 unchanged, 2 made worse.

This is roughly **30× the offline correction layer's contribution** (which moved CER by
0.08 pp across 36 pages). It is comfortably the most effective accuracy lever currently
in the codebase.

Per-page highlights:

| Page | Offline | + Gemini |
| --- | --- | --- |
| 20260919_222813 | 30.75% | **16.10%** |
| 20260919_224138 | 2.36% | **0.25%** |
| 20260919_223105 | 6.69% | 6.09% |
| IMG-20260819-WA0006 | 3.38% | 4.30% (worse) |
| 20260731_230849 | 38.88% | 39.08% (worse) |

**Why it cannot go further.** The prompt forbids reordering ("Never rephrase, translate,
summarize, reorder"), so Gemini repairs character and word garbles but leaves the
ordering damage from §4.5 untouched. On `20260919_222813` the order-free WER is 12.2%,
and Gemini drove CER from 30.75% down to 16.10% — close to that floor, then stopped.
The two pages it made worse are contents pages, where the scrambling is worst and there
is little intact context to reason from.

**The fallback model is not a substitute.** Four pages hit the free-tier quota on the
primary model; re-running three of them on `gemini-3.5-flash-lite` (the app's first
fallback) gave CER 14.54% → 14.90% — 0 improved, 1 unchanged, 2 made worse. When the app
silently falls back, it is not getting a slightly weaker correction; on this sample it is
getting a harmful one.

**Quota is a live demo risk.** Roughly a dozen requests exhausted the free-tier daily
quota for `gemini-3.5-flash` on this key, and every subsequent call returned HTTP 429.
A demo that leans on the online pass can therefore fail on stage for reasons unrelated to
the code. The offline path is unaffected, which is another argument for presenting the
offline result as the product and Gemini as a bonus tier.

One page (`20260919_223954`) could not be measured on either model before the quota ran
out, so n=10 rather than 14.

### 4.6.1 Constrained reordering — written, then reverted unmeasured (2026-09-20)

Since the prompt's reordering ban is what stops Gemini from touching the dominant error, a
variant was written that permits it under a hard constraint: *"You may reorder words within
a block, and only when the recognizer clearly emitted them out of order… the block must
keep exactly the same words."*

**It was reverted without shipping, because it could not be measured.** The free-tier daily
quota was exhausted partway through the measurement run and every later call returned
HTTP 429. Relaxing a fabrication guard on an unmeasured hunch is not acceptable for a
reader who cannot see the page and therefore cannot catch an invented sentence, so the
prompt in `GeminiCorrector` is unchanged.

Two things were kept, because both stand on their own:

- **`tools/ocr_eval/gemini_eval.py` can now run the experiment.** `--reorder` swaps in the
  variant prompt, and every page is additionally scored **through the app's own
  `MAX_CHANGE_RATIO` acceptance guard** and reported alongside a word-multiset drift
  column (the plan's acceptance criterion 2). This matters: the guard rejects any block
  whose correction rewrites more than 35% of its characters, and **reordering a scrambled
  sentence routinely exceeds that**. The raw-output number the old script reported would
  have overstated the app-level benefit, possibly to zero.
- **The `gemini-3.5-flash-lite` fallback was removed** (`GeminiProtocol.FALLBACK_MODELS`).
  That one *was* already measured — 0 pages improved, 2 made worse — so silently degrading
  to it is strictly worse than skipping the pass.

To finish the experiment when quota resets, run both arms against the same dump and compare
the `CER app` column, not `CER +Gemini`:

```
python tools/ocr_eval/gemini_eval.py --dump artifacts/app-text-dump-task5.json --out artifacts/gemini-baseline.json
python tools/ocr_eval/gemini_eval.py --dump artifacts/app-text-dump-task5.json --out artifacts/gemini-reorder.json --reorder
```

Ship the prompt change only if guarded corpus CER improves **and** no page's word-multiset
drift exceeds 5%.

---

## 5. Honest limitations

These materially affect how the numbers should be presented.

1. **No label is human-verified.** Every transcript, including the 21 added this
   session, is `AI_TRANSCRIBED`. Accuracy is therefore measured against another
   model's reading, not ground truth. The four pre-existing committed
   ground-truth pairs are the only exception and are reported separately.
2. **Line labels carry residual noise.** Tesseract's line segmentation cuts lines
   mid-word on curved pages, so some crops physically lack words the label
   contains. Part of the component-level gain in §4.3 may be the model learning
   to reproduce that noise rather than reading better.
3. **Six pages per split.** Split-level numbers carry wide error bars. The 36-page
   aggregate and the OOD holdout are the more trustworthy figures.
4. **Emulator, not a phone.** Accuracy transfers; timings do not.
5. **Contents pages are the weakest labels.** Dotted leaders and skew made some
   page numbers genuinely ambiguous; these should be a human reviewer's first stop.

---

## 6. Recommendations

0. **Fix reading order on curved pages.** This is now the top item by a wide margin:
   66% of your word error, no new model, entirely inside `ocr-core/pipeline`. Start
   with `RowFragmentJoiner` and `ReadingOrderSorter` on the worst pages
   (`20260731_230858`, `IMG_5720`, `20260919_222952`) — `artifacts/app-text-dump.json`
   holds the exact reference-versus-output text for each.
1. **Do not ship the fine-tuned model as-is.** It improves the Tesseract component
   on held-out lines, but it is `tessdata_best` (8.25 MB vs 1.12 MB shipped) and
   correspondingly slower, and Tesseract only feeds the correction vote — which
   moves app CER by 0.08 pp. The cost/benefit does not favour shipping it before
   the deadline.
2. **Human-verify 25 pages.** This is the highest-value hour available and it is
   what converts every number here from "AI-vs-AI" to a defensible accuracy claim.
3. **Tune correction-layer precision, not recall.** One in ~10 applied corrections
   was wrong. Raising the acceptance threshold would likely improve WER further at
   no latency cost — and unlike the LSTM, this is cheap to test.
4. **Fix the local build blocker.** The default JDK (Java 26) makes `./gradlew`
   fail immediately; the Android Studio JBR 21 works.

---

## 7. Reproducing this

See `METHODS.md` alongside this file for exact commands, environment setup, and
the four non-obvious failure modes that cost time (CRLF in `lstmeval` list files,
missing `configs/` in a custom `TESSDATA_PREFIX`, `DATA_VERSION` gating the
bundled-model swap, and test-APK reinstall wiping device sample directories).
