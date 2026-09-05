# Window insets fix report

## Defect

`targetSdk 35` draws edge-to-edge by default. The app never applied `WindowInsets`, so on
API 35 the status bar and navigation/gesture bar drew on top of app content: the result
screen's timing HUD sat under the status bar clock/signal icons, and a previously-added
settings button was unreachable because its tap target was under the status bar.

## Mechanism chosen

Standard Jetpack Compose `WindowInsets` APIs, applied per-screen rather than through one
top-level `Scaffold`:

- `MainActivity.onCreate` calls `enableEdgeToEdge()` once, which opts into transparent
  system bar backgrounds (the actual behavior on API 35 either way) so our own padding is
  the only thing standing between content and the bars.
- `Modifier.safeDrawingPadding()` on any screen/container that is otherwise plain full-bleed
  content with no reason to run under either bar (the `Centered` idle/working placeholder,
  `FailureView`, `SettingsScreen`'s root column).
- `Modifier.statusBarsPadding()` / `Modifier.navigationBarsPadding()` (the same
  `WindowInsets` family, just directional) wherever only one edge needs clearing and the
  opposite edge is meant to stay edge-to-edge — the camera preview's button row (bottom
  only) and the result screen's header (top only).
- `WindowInsets.navigationBars.asPaddingValues()` fed into a `LazyColumn`'s `contentPadding`
  where a scrolling list is allowed to run edge-to-edge but its first/last items must still
  be fully reachable.

A `Scaffold`-based approach was considered and rejected: `CaptureScreen`'s camera preview
and `ResultScreen`'s block list are explicitly supposed to extend under the bars (edge-to-edge
"looks intentional" there), so a single outer padding container would either force those to
inset unnecessarily or require exceptions per screen anyway. Applying `WindowInsets` directly
to just the chrome that needs it kept one mechanism but let each screen decide what bleeds
and what doesn't.

## Per-screen changes

- **`MainActivity.kt`**: `enableEdgeToEdge()` added to `onCreate`. The existing `Pengaturan`
  overlay button (added by a previous agent) already used `.statusBarsPadding()` — that
  was correct and not a duplicate of anything else, so it was kept, with the comment
  updated to explain why it still needs its own inset (the preview behind it is meant to
  bleed under the status bar). The `Centered` idle/working placeholder now has
  `.safeDrawingPadding()`.
- **`CaptureScreen.kt`**: the bottom `Row` holding "Ambil Foto" / "Dari Galeri" gained
  `.navigationBarsPadding()` before its existing `.padding(24.dp)`, so the buttons clear the
  gesture bar while the `PreviewView` behind them still fills the whole screen.
- **`ResultScreen.kt`** (the file with the actual defect — the timing HUD under the clock):
  `DocumentView` was split into a fixed header `Column` (`.statusBarsPadding()` +
  16dp padding, holding the HUD text, action buttons, training-data row, and view toggle)
  and a `Box(Modifier.weight(1f))` holding the scrolling block list. `ReadingView` and
  `DebugView`'s `LazyColumn`s now take `contentPadding` from a shared
  `blockListContentPadding()` helper: 16dp horizontal, 8dp top, and
  `navigationBars` bottom inset + 16dp, so the list can still scroll edge-to-edge but the
  last block is never left underneath the gesture bar. `FailureView` got
  `.safeDrawingPadding()`.
- **`SettingsScreen.kt`**: swapped the root column's `.statusBarsPadding()` for
  `.safeDrawingPadding()` so the "Tutup" button at the bottom also clears the nav bar (it
  previously only handled the top).

## Before / after, screen by screen (AVD `Pixel_10_Pro`, API 35)

- **Capture screen** — before: not screenshotted pre-fix, but the settings button's
  existing workaround meant it was already visually correct; the camera preview correctly
  bleeds under both bars. After: confirmed via screenshot — "Pengaturan" sits fully below
  the status bar and clock icons, "Ambil Foto" / "Dari Galeri" sit fully above the gesture
  pill, and the preview still fills the entire screen edge-to-edge with no dead band at top
  or bottom.
- **"Membaca teks…" (Working state)** — uses the same `Centered` composable as idle; text
  is vertically centered and unaffected by insets either way, but now carries
  `safeDrawingPadding()` for small-screen safety.
- **Reading view (`ResultScreen`, success)** — before: the HUD line
  ("1252 ms • 7 blok • 1 kolom • skew -2.1°") would have rendered flush with the top of the
  screen, under the clock. After: screenshot shows the HUD starting well below the status
  bar, all buttons and the view toggle fully visible, and the block content ending above
  the gesture bar with no scroll needed for this particular document (7 blocks) and no
  visible gap — it just stops where the content stops.
- **Detail view (`DebugView`)** — same header treatment. Scrolled to the bottom: the last
  card (`#6 LIST_ITEM … 500 m`) renders fully above the gesture pill with no clipping and no
  oversized empty gap below it, confirming the `navigationBars`-aware `contentPadding`
  works for a longer, actually-scrolling list.
- **Failure view** ("Tidak ada teks yang terbaca…") — text now starts below the status bar
  instead of at the very top edge; "Coba Lagi" is fully visible and tappable.
- **Settings screen** — title "Pengaturan" clears the status bar (unchanged from the prior
  agent's fix, since it already used the right mechanism) and "Tutup" now also has bottom
  inset padding, so it can't be shadowed by the gesture bar.

## Settings tap verification

Tapped "Pengaturan" on the capture screen at its actual on-screen bounds (confirmed via
`uiautomator dump`, not guessed): the tap registered and the Settings screen opened
immediately. Tapped "Tutup" and returned to the capture screen successfully. The
previously-reported defect (tap swallowed by the status bar) does not reproduce.

## Reverse check — no double-padding

Compared the capture screen, reading view and detail view screenshots against expectation:
no full-width empty band appears at the top or bottom of any screen beyond the height of
the actual status bar / gesture bar. The reading-view content in this test document simply
ends short of the bottom because the document is short (7 blocks); scrolling the detail
view confirmed the list itself does not carry excess reserved space — the last card sits
right above the gesture pill with a normal margin, not a padded-twice gap.

## Verification commands

- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
- `./gradlew :ocr-core:test :ocr-mlkit:testDebugUnitTest` — BUILD SUCCESSFUL, both reported
  UP-TO-DATE (no source in either module was touched).
- Installed `app-x86_64-debug.apk` on AVD `Pixel_10_Pro`, exercised capture → gallery pick
  (real worksheet photo from `Downloads/braille testing/`) → reading view → detail view →
  scroll → settings → close → capture-with-no-text → failure view, screenshotting each.

## Anything still visually imperfect

- `SettingsScreen` has no scrolling container; on a very small screen or with a much longer
  privacy paragraph (e.g. larger system font size), the content could in principle overflow
  past "Tutup" at the bottom. This is a pre-existing layout gap unrelated to window insets
  and was not in scope for this fix — flagged here rather than silently left.
- The 21st bug candidate: none of the app's screens have a landscape check, but
  `MainActivity`'s activity is locked to `android:screenOrientation="portrait"` in the
  manifest, so this is not currently reachable.
