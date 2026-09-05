# Reading view: report

Replaced the developer-facing block-card dump with a role-driven reading view as the
default post-recognition screen, kept the old dump as a "Detail" toggle, added TalkBack
semantics, and added a plain-text export next to the JSON export. Presentation-only
change: `:ocr-core` and `:ocr-mlkit` are untouched.

Files touched:
- `braille-ocr/app/src/main/kotlin/id/dotcode/braille/ocr/app/ResultScreen.kt` (rewritten)
- `braille-ocr/app/src/main/kotlin/id/dotcode/braille/ocr/app/TextExport.kt` (new)

## Typographic scale

Driven by `BlockRole`, applied in `ReadingBlock()`:

| Role | Style | Notes |
|---|---|---|
| `TITLE` | `headlineMedium`, bold, 34sp line height | largest, most prominent |
| `HEADING` | `titleLarge`, bold, 28sp line height | subordinate to title, above body |
| `PARAGRAPH` / `QUESTION` / `LIST_ITEM` | `bodyLarge`, 26sp line height | same visual weight — see rationale below |
| `CAPTION` | `bodySmall`, `onSurfaceVariant` | smaller, quieter |
| `PAGE_NUMBER` | `labelSmall`, `onSurfaceVariant` | small, unobtrusive |

`QUESTION` and `LIST_ITEM` render identically to `PARAGRAPH` rather than getting their
own distinct look. That's deliberate: the only structural signal they carry beyond
"body text" is the marker prefix and indentation, both of which are already rendered
(marker restored as `"$marker $text"`, indent as real leading space via
`Modifier.padding(start = indentLevel * 20.dp)`). Giving them a different font size or
weight would invent a visual distinction the data doesn't support.

Alignment (`Alignment.LEFT/CENTER/RIGHT`) maps directly to `TextAlign` on a
`fillMaxWidth()` text, so a centered title or right-aligned caption actually centers or
right-aligns instead of just sitting at the left edge with centered-looking text wrapped
oddly.

Vertical rhythm between blocks (`topPadding` in `ReadingBlock`): 0dp for the first block,
28dp above a `HEADING` (a real gap so it reads as a new section), 8dp above a `TITLE`,
24dp above a `PAGE_NUMBER`, 4dp above a `CAPTION`, 16dp everywhere else (paragraph-to-
paragraph). This is what makes consecutive paragraphs read as separate paragraphs
without needing a divider or card border.

**On role reliability**: I ran the reading view against a real corpus photo
(`IMG-20260820-WA0000.jpg`, an English-worksheet page with a vocabulary table) and, as
expected per the classifier's conservative bias, 44 of 46 detected blocks came back
`PARAGRAPH`, including section headers like "Exercises" (see below). The reading view
renders that as clean continuous prose — no boxes, no "block #12 [PARAGRAPH]" chrome —
which was the point: it doesn't look broken when nothing gets tagged. Where the
classifier did detect something (block #12, "the process of distributing money,
resources, or responsibilities", `relativeTextHeight ×1.60`), it rendered visibly bolder
and larger as a `HEADING`, without any special-casing on my part — the role-driven
typography did exactly what it was supposed to do in both cases.

## The toggle

`ViewToggle` in `ResultScreen.kt`: two buttons, "Bacaan" (reading, default — filled when
active) and "Detail" (debug dump — outlined when active). Backed by a single
`var isReadingView by remember { mutableStateOf(true) }` in `DocumentView`. Each button
carries a `Modifier.semantics { contentDescription = ... }` with the state folded into
the text itself ("Tampilan bacaan, sedang aktif" / "Beralih ke tampilan detail") rather
than a separate `selected` semantic, to keep it simple and unambiguous for TalkBack.
Debug view is the original `BlockCard` per-block dump (id, role, column, indent,
alignment, relative height, marker), untouched in content, just extracted into
`DebugView()`. The timing HUD (two `Text` lines at the top with per-stage ms) sits above
the toggle and renders in both views.

## TalkBack semantics

Each `ReadingBlock` uses `Modifier.clearAndSetSemantics { if (isHeading) heading();
contentDescription = fullText }`. Two things this buys:

1. **No fragmentation.** The composable visually may be a marker prefix concatenated
   into the same `Text`, but `clearAndSetSemantics` collapses whatever the subtree would
   otherwise expose into exactly one node with one content description — verified by
   dumping the live accessibility node tree (see Verification below): every block,
   including a standalone table cell like `"5"`, appears as exactly one
   `android.view.View` node with the full text as its `content-desc`, not split into
   separate nodes for a marker and a body.
2. **Heading role.** `TITLE` and `HEADING` blocks additionally call `heading()` inside
   that same semantics block, which sets `AccessibilityNodeInfo.isHeading()` — the flag
   TalkBack uses both to announce "Heading" and to let a user jump between headings with
   its heading-navigation gesture.

### What I actually verified on device, and what I didn't

- Enabled TalkBack on the AVD (`settings put secure enabled_accessibility_services
  com.google.android.marvin.talkback/...TalkBackService` + `accessibility_enabled 1`) and
  confirmed via `dumpsys accessibility` that the service was bound and running, and saw
  TalkBack's accessibility-focus rectangle rendered live on screen around app controls
  (screenshot after dismissing the system "allow notifications" dialog shows a green
  focus outline around "Foto Lagi").
- Confirmed via `uiautomator dump` of the live accessibility node tree (the same API
  TalkBack itself consumes) that: the toggle buttons carry the exact Indonesian
  content descriptions written in code, and every reading block — including the
  detected heading block — is exactly one node with the full marker+text as its
  content description, i.e. no fragmentation.
- **Did not** get a clean audible confirmation of the heading announcement itself. I
  could enable TalkBack and see it focus elements, but driving its linear/heading
  navigation via `adb shell input swipe`-style synthetic gestures was not reliable in
  this headless emulator session — repeated swipe and long-press-tap attempts left
  accessibility focus stuck on the first control instead of advancing, and there was no
  audio path to capture a TTS utterance even if it had moved. So: TalkBack runs on this
  AVD and the semantics tree is structurally correct (single-node blocks, heading flag
  set in code for TITLE/HEADING), but I have not personally heard "Heading" announced —
  I'm not claiming that part as verified.

## Plain-text export

`TextExport.toPlainText()`: one block per line-group, `\n\n`-joined (blank line between
blocks), marker restored as `"$marker $text"`, indentation as literal leading spaces
(`indentLevel * 2` spaces). Staged to `cacheDir/export/braille-ocr-<timestamp>.txt` and
shared via `ACTION_SEND` / `text/plain`, same file-based pattern as `JsonExport`
(reusing a file, not `EXTRA_TEXT`, for consistency — a plain-text page is small enough
that `EXTRA_TEXT` would work, but sharing a real file behaves more predictably across
share targets). "Ekspor Teks" sits next to "Ekspor JSON" in the button row; "Ekspor JSON"
is untouched.

Real output, first three blocks of the corpus photo, pulled from the device
(`braille-ocr-20260905-155354.txt`):

```
In conclusion, providing free nutritious meals for students offers several potential advantages, particularty in relation to children's health, cognitive development, and socioeconomic equality. However, the program also presents significant challenges, including financial cost, food waste, distribution difficuties, and the need for rigorous foodsafety and administrative procedures.

A free nutritious meal program can be a worthwhile government initiative if it is implemented carefully, transparently, and systematically. Rather than simply distributing food, policymakers shouid establish clear nutritional standards, conduct regular evaluations, cooperate with reliable suppliers, and develop efficient monitoring systems. Schools could also provide nutrition education and encourage students to reduce unnecessary food waste. With appropriate planning and long-term evaluation, such a program could serve not onty as a social welfare measure but also as an investment in the health, education, and future productivity of younger generations.

Exercises
```

(The OCR typos — "particularty", "difficuties", "shouid", "onty" — are ML Kit's, not the
export's; the export is a faithful passthrough of `TextBlock.text`.)

## Device verification

- `./gradlew :app:assembleDebug` — succeeded.
- `:ocr-core:test` and `:ocr-mlkit:testDebugUnitTest` — both `UP-TO-DATE` / green,
  confirming those modules were not recompiled (untouched).
- Booted `Pixel_10_Pro` AVD, pushed `IMG-20260820-WA0000.jpg` (a worksheet with a
  vocabulary table) to `/sdcard/Pictures/`, installed the debug APK, ran it through
  "Dari Galeri" → system photo picker → OCR → result screen.
- Reading view rendered as continuous prose for the two intro paragraphs and "Exercises"
  (all `PARAGRAPH`), then table cells with real indentation, and visibly promoted the one
  block the classifier actually flagged as a heading — screenshots captured at each step
  (recognition result, reading view, detail/debug view, table section, export share
  sheets for both JSON and text).
- Switched to "Detail" and back to "Bacaan" repeatedly via the toggle; both views stayed
  in sync off the same `document.blocks` list, timing HUD numbers unchanged.
- Ran both exports through the real share sheet; "Ekspor JSON" produced
  `braille-ocr-20260905-155334.json`, "Ekspor Teks" produced
  `braille-ocr-20260905-155354.txt` (content shown above).

## Things that look off in the rendered result (not fixed, out of scope)

- **Heading detection is sparse, as expected.** Only 1 of 46 blocks on this page got
  `HEADING`; "Exercises" and "A. Vocabulary in Context" — visually obvious section
  headers on the source photo — came back `PARAGRAPH`. This is the known conservative-
  classifier behavior from `ocr-core` and is explicitly out of scope here, but it's worth
  restating: a teacher scanning a worksheet with several sub-headings will mostly not see
  them rendered as headings.
- **Table cells become a wall of single-line paragraph blocks.** The vocabulary table's
  "No." and "Word/Phrase" columns show up as isolated one-token blocks ("5", "7", "8"...)
  interleaved with the definition text, each a separate reading-view paragraph. It's
  readable but doesn't look like a table — again a structure-detection limitation
  upstream of this screen, not something the reading view can paper over without
  inventing table semantics that aren't in `TextBlock`.
- A couple of OCR misreads surfaced in the reading view text itself ("particularty",
  "difficuties", "shouid", "onty", "quickty") — unrelated to this change, flagged only
  because they're now much more visible in prose than they were in the old per-block
  dump.
