# SDD ledger — plan: docs/superpowers/plans/2026-08-23-brialle-ocr-mvp.md

Spec: docs/superpowers/specs/2026-08-23-brialle-ocr-design.md (read)
Repo: C:/Users/Kiel/Documents/SFT OCR — branch feat/ocr-mvp — base 259b365

## Pre-flight conflict scan

| Rows | What was checked | Finding |
| --- | --- | --- |
| T1 ↔ T13/T14 | `gradle/libs.versions.toml` written by T1, appended by T13 (exifinterface, coroutines) | Additive, no conflict |
| T1 internal | Step 6 build file vs Step 8 needing `kotlin("test")` | Step 8 instructs the addition explicitly — consistent |
| T1 ↔ T13/T14 | `jvmToolchain(21)` in :ocr-core vs Android modules `compileOptions VERSION_11` | **CONFLICT** — see R2 |
| T2 → T5,T9,T11,T13 | `BoxF`, `PointF`, `RawLine`, `RawWord` names and fields | Consistent |
| T3 → T11 | `TextLine(text, box, confidence, recognizedLanguage)` positional construction | Consistent |
| T4 → T5..T11 | `line()`/`page()` test helpers, `PageStats.median` internal visibility | Consistent, same module |
| T4 → T6,T7,T9,T11 | `ColumnAssignment`, `OrderedLine`, `LineGroup` shapes | Consistent |
| T6 → T7,T9,T10,T11 | `segment(lines, stats)` signature | Consistent |
| T7 → T9,T11 | `sort(lines, columns, stats)` signature | Consistent |
| T8 → T9,T10,T11 | `MarkerParser.parse` / `ParsedMarker` / `MarkerKind` | Consistent |
| T9 → T10,T11 | `LineMerger.reflow` as companion function | Consistent |
| T9 internal | private `String.endsWith(Regex)` shadows stdlib `endsWith` | **DEFECT** — see R7 |
| T10 → T11 | `RoleClassifier.relativeHeight` internal, called by structurer | Consistent |
| T10 internal | test arithmetic re-derived (title 56/30, heading 42/30, caption 24/40) | Thresholds hold |
| T11 internal | merge-gap arithmetic in worksheet test re-derived against `paragraphGapFactor` | Splits/merges as asserted |
| T13 internal | `measureTimeMillis` wrapping a suspending ML Kit call | **DEFECT** — see R3 |
| T13 ↔ T14 | coroutines declared `implementation` in :ocr-mlkit, used by :app | **CONFLICT** — see R4 |
| T13 internal | JUnit 5 platform under AGP unit tests | **RISK** — see R6 |
| T14 internal | `Preview.surfaceProvider =` property assignment (no getter exists) | **DEFECT** — see R5 |
| T14 internal | manifest `.MainActivity` vs adb component `id.dotcode.braille.ocr/.app.MainActivity` | Resolves correctly |

## Pre-flight rulings

Ruling: repo root is `SFT OCR/`, not `braille-ocr/`, on branch `feat/ocr-mvp` — keeps spec, plan and code in one history; supersedes T1 Step 10's `git init` location — cost if wrong: re-init, trivial.

Ruling: replace every `jvmToolchain(N)` with explicit Java 17 `sourceCompatibility`/`targetCompatibility` plus Kotlin `jvmTarget = JVM_17`, compiled by the Gradle JVM (JBR 21) — `:ocr-core` at toolchain 21 emits Java 21 bytecode that Android modules cannot consume, and toolchain 17 would force a JDK download — cost if wrong: build-config-only fix.

Ruling: in T13 `OcrEngine`, time the recognition stage with explicit `System.currentTimeMillis()` deltas instead of `measureTimeMillis` — its lambda is non-suspending and will not compile around the ML Kit await — cost if wrong: none, identical timing semantics.

Ruling: `:ocr-mlkit` declares coroutines as `api`, not `implementation` — `:app`'s `OcrViewModel` needs `kotlinx.coroutines.flow` on its compile classpath — cost if wrong: trivial dependency move.

Ruling: T14 uses `setSurfaceProvider(...)` rather than property assignment, and whichever `LocalLifecycleOwner` import compiles against the pinned Compose BOM — the property has no getter, so Kotlin synthetic-property syntax will not compile — cost if wrong: trivial.

Ruling: if JUnit 5 will not run under AGP unit tests, `:ocr-mlkit`'s four tests drop to JUnit 4 while `:ocr-core` stays on JUnit 5 — cost if wrong: test-harness-only change, no assertion changes.

Ruling: T9 drops the private `String.endsWith(Regex)` extension shadowing the stdlib and calls `TERMINAL_PUNCTUATION_REGEX.containsMatchIn(...)` directly — cost if wrong: none.

## Task log

Ruling: dispatch in 8 waves rather than 14 — T2+T3+T4 (pure data/model transcription), T5+T6+T7 and T8+T9+T10 (same-shape single-stage-plus-test transcriptions) each go to one implementer and are reviewed as one diff; T1, T11, T12, T13, T14 stay solo — the plan supplies complete code for the batched tasks, so they are transcription plus a test run, not independent judgment — cost if wrong: a review surface covers three files instead of one.

Task 1: dispatched (base 259b365)
Task 1: complete (commits 259b365..babafc7, review clean)
Task 1: minor (deferred): ScaffoldTest asserts classpath purity only, not build config
Task 1: minor (deferred): duplicate .gitignore at repo root and braille-ocr/
Tasks 2-4: complete (commits babafc7..6ea1a26, review clean)
Tasks 2-4: minor (deferred): PageStats.median even-count branch untested with distinct values
Tasks 2-4: minor (deferred): PageStats.from uses a bare 1f sentinel on empty input, not a config constant
Tasks 2-4: minor (deferred): FailureReason uses PascalCase while BlockRole/Alignment use UPPER_SNAKE
Tasks 5-7: implemented (commits 6ea1a26..20f73eb); implementer flagged a wrong assertion in my brief
Ruling: SkewEstimatorTest's post-deskew height bound was arithmetically wrong in the plan (30f..60f; true enclosing-hull height for a 200x40 box at 10 deg is w*sin+h*cos = 74.12). The implementer's widening to 30f..80f is correct but asserts almost nothing, so I ordered it tightened to 74.12 +/- 0.5 plus a matching width assertion (203.9) — a loosened bound would let real rotation bugs through — cost if wrong: a test bound to retune, implementation untouched.
Tasks 5-7: fix round 1/5 (1 addressed, 0 open — deskew hull assertion tightened; commits 20f73eb..87d3246)
Tasks 5-7: complete (commits 6ea1a26..87d3246, review clean)
Tasks 5-7: minor (deferred): ReadingOrderSorter bandHeight coerceAtLeast(1f) is an inline literal, not a config constant
Tasks 8-10: dispatched (base 87d3246)
Tasks 8-10: implemented (commits 87d3246..dcf3e30); review found 1 Important, plan-mandated
Ruling: LineMerger's `indentTolerance * 4f` literal came from my brief but violates the project's no-magic-numbers constraint, and it is a genuine tunable (first-line-indent tolerance) that Task 11+ may need to retune — promoted to StructuringConfig.firstLineIndentFactor = 4.0f, default keeps behaviour byte-identical. MarkerParser's regex bounds and ReadingOrderSorter's degenerate clamp deliberately left alone — cost if wrong: one config field to inline again.
Tasks 8-10: fix round 1/5 (1 addressed, 0 open — firstLineIndentFactor promoted to config; commits dcf3e30..cea9780)
Tasks 8-10: complete (commits 87d3246..cea9780, review clean)
Task 11: dispatched (base cea9780)
Task 11: implemented (commits cea9780..e1522ae); review found 2 Important
Ruling: the two-column fixture was a tautology — its raw line array was already in marker order, so a no-op sort would also pass. Ordered the array scrambled so only a correct column-major sort yields 1..6 — a regression test that cannot fail is worse than none, because it buys false confidence in the exact behaviour the braille engine depends on — cost if wrong: fixture regenerated again.
Ruling: alignment()'s side-margin guard preceding the centre check STANDS against the review — a block filling >~85% of its column is indistinguishable from full-width text, and reporting CENTER there is a false positive the braille renderer would act on. Ordered a KDoc note plus a test pinning the intent instead of a logic change — cost if wrong: genuinely centered wide blocks report LEFT, a cosmetic mislabel with no effect on text content or order.
Task 11: fix round 1/5 (3 addressed, 0 open — fixture scrambled, alignment intent pinned, null-confidence covered; commits e1522ae..3f23111)
Task 11: complete (commits cea9780..3f23111, review clean, 56 tests)
Task 12: dispatched (base 3f23111)
Task 12: complete (commits 3f23111..ed75e4b, review clean, 62 tests)
Task 12: minor (deferred): CER decomposes by UTF-16 char, not grapheme cluster; NFD combining diacritics would count inconsistently
Task 12: minor (deferred): no direct tests for levenshtein empty-input guards, both-empty compare(), or the CER/WER convenience wrappers
Task 13: dispatched (base ed75e4b)
Task 13: implemented (commits ed75e4b..5e89774); review found 2 Important
Ruling: OcrEngine registered no cancel listener, so a cancelled ML Kit Task would hang the caller forever — ordered addOnCanceledListener plus a switch to suspendCancellableCoroutine, since Task 14 binds this to a ViewModel scope that dies on rotation. FailureReason.Cancelled existed but was never produced, which was the tell — cost if wrong: a cancellation path that returns Failure instead of propagating, still no hang.
Ruling: close() unsynchronized against in-flight recognize() — ordered an idempotent close flag with a post-close guard returning Failure(Cancelled) rather than mere documentation. ML Kit's own teardown cannot be made atomic and I did not ask for that; the bar is that no caller hangs or hits undefined behaviour — cost if wrong: a redundant guard on a path that was already safe.
Task 13: minor (deferred): recognize(uri) opens the input stream twice, once for EXIF and once for the bitmap decode
Task 13: fix round 1/5 (2 addressed, 0 open — cancel listener + suspendCancellableCoroutine, idempotent close guard; commits 5e89774..b55f133)
Task 13: complete (commits ed75e4b..b55f133, review clean)
Task 13: minor (deferred): closed-engine guard untestable on plain JVM (TextRecognition.getClient hits Android stubs); would need Robolectric, ruled out of scope
Task 13: minor (deferred): residual race if recognize() passes its closed check exactly as close() fires
Task 14: dispatched (base b55f133)
Task 14: implemented (commits b55f133..665caa7), DONE_WITH_CONCERNS — failure path observed on device, success path never was (headless AVD Photo Picker auto-dismisses)
Ruling: rather than chase the Photo Picker on a headless emulator, ordered an instrumented test in :ocr-mlkit that renders a synthetic Indonesian worksheet with Canvas.drawText, runs it through a real OcrEngine, and asserts markers 1-3 in order with the marker stripped from each block's text. The structured output IS the deliverable and had never once been observed; a test proves it permanently where a manual click proves it once. Also closes the spec's deferred instrumented-test gap, which I had dropped only for lack of a sample worksheet — cost if wrong: a flaky test if ML Kit reads drawText output poorly, which the dispatch forbids papering over.
Task 14: fix round 1/5 (1 addressed, 0 open — instrumented test proves success path on device; commits 665caa7..d3b7141)
Task 14: complete (commits b55f133..d3b7141, review clean)
ALL 14 TASKS COMPLETE. Dispatching final whole-branch review (base 259b365).

## Final whole-branch review: 2 Critical, 3 Important
Ruling: both Criticals are real and both survived BECAUSE of my earlier rulings. The 74.12 hull ruling pinned deskew's box inflation as intended behaviour instead of asking why an already-axis-aligned box was being re-enclosed at all; the de-tautologized fixture ruling fixed the ordering but left the fixture unrepresentative (title narrower than the left column), so no test ever exercised a real full-width heading. Ordering both fixed, not parked — they corrupt the structured output that IS the deliverable — cost if wrong: rework in geometry code that 62 tests cover.
Ruling: spec success criterion 5 (accuracy measurable on demand against a folder of images) is genuinely unmet — Task 12 shipped only the CER/WER math. Ordered a real instrumented harness that scans a device directory for image/.txt pairs and reports CER/WER, skipping with a clear message when empty, rather than recording an accepted gap. The concept paper's >90% claim is the reason this module exists; shipping the arithmetic while calling the criterion satisfied would be the silent kind of incomplete — cost if wrong: a harness nobody runs until samples arrive.
Final fix wave: dispatched (base d3b7141)
Final fix wave: 5 findings fixed (commits d3b7141..09e43cf); implementer surfaced a SIXTH bug in my own briefed fix
Ruling: assigning spanning lines by centre — my instruction — is a coin flip on a symmetric two-column page, because a centred title's midpoint coincides with the gutter midpoint; when it lands in column 1 the title sorts after every left-column question, which is exactly the scrambled reading order Critical 1 existed to kill. Ordered the smallest deterministic fix (assign spanning lines to the leftmost column they overlap) plus a test whose title midpoint sits exactly on the gutter. Declined the full spanning-tier rewrite as too large for a fix wave; ordered the limitation commented in code instead — cost if wrong: a mid-page full-width line sorts among left-column content rather than ahead of both columns.
Ruling: the accuracy harness ships with NO synthetic worksheets, deviating from spec section 7. The implementer argued generated samples would score ~100% and measure nothing while making an empty harness look satisfied; that judgement is better than the spec's and I adopted it. The harness skips with a message naming the directory and the adb command — cost if wrong: the harness stays unexercised until real SLB-A samples arrive.
Ruling: accepted as deferred — no automated EXIF-rotation coverage (needs an EXIF fixture on device), and word boxes still take the inflated hull fallback since the contract carries no per-word corner points (latent; nothing reads them).
Final fix wave: agent killed mid-task by a spend limit after hand-verifying the spanning-line fix but before running tests or committing.
Ruling: I verified and committed the agent's completed work myself (ocr-core 69/0, ocr-mlkit 8/0, app assembles) rather than re-dispatching. This is completion of an interrupted agent's finished edit, not controller-authored fix work — the diff was already written and hand-verified; re-dispatching would have rebuilt full context to run gradle and git commit, on a session that just hit a spend limit — cost if wrong: the commit gets the same scoped re-review as any fix round, which follows next.
Final fix wave: complete (commits d3b7141..f458e69, 6 findings)
