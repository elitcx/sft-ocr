# BRaiLLE OCR MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an on-device OCR module that reads a phone-camera photo of Indonesian printed classroom material with Google ML Kit Text Recognition v2 and returns a structured document tree preserving layout formatting.

**Architecture:** Three Gradle modules with a strict one-way dependency chain. `:ocr-core` is pure Kotlin/JVM and holds the entire structuring pipeline behind a provider-agnostic `RawTextResult` input contract, so the algorithmic work is unit-tested on the desktop JVM in seconds. `:ocr-mlkit` is a thin Android library that preprocesses images, gates on capture quality, and adapts ML Kit output into `RawTextResult`. `:app` is a Compose demo doing CameraX still capture, gallery import, result rendering, and JSON export.

**Tech Stack:** Kotlin 2.1.21, Gradle 8.14.3, Android Gradle Plugin 8.13.0, ML Kit `text-recognition` 16.0.1 (bundled model), CameraX 1.4.2, Jetpack Compose (BOM 2025.06.00), kotlinx-serialization-json 1.8.1, JUnit 5 for `:ocr-core`.

**Spec:** `docs/superpowers/specs/2026-08-23-brialle-ocr-design.md`

## Global Constraints

- Project root is `C:/Users/Kiel/Documents/SFT OCR/braille-ocr/`. All paths in this plan are relative to that directory unless prefixed with `docs/`.
- Base package is `id.dotcode.braille.ocr`.
- Dependency direction is strictly `:app` → `:ocr-mlkit` → `:ocr-core`. `:ocr-core` must have **zero** Android dependencies — no `android.*` imports, no `androidx.*` imports. This is what keeps its tests running on the desktop JVM.
- `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`. SDK 35 is installed; do not raise `compileSdk` to 36 without also raising AGP.
- Java 26 is on `PATH` and AGP will reject it. `gradle.properties` must pin `org.gradle.java.home` to Android Studio's bundled JDK 21 at `C:\\Program Files\\Android\\Android Studio\\jbr`.
- ML Kit must use the **bundled** model artifact `com.google.mlkit:text-recognition`, never the Play-Services-delivered `com.google.android.gms:play-services-mlkit-text-recognition`. The Play Services variant downloads on first use, which fails offline in the 3T-region schools this project targets.
- The structuring pipeline is deterministic. No randomness, no probabilistic tie-breaking, no ML inference beyond ML Kit itself. Braille output requires reproducibility.
- All tunable thresholds live in `StructuringConfig` as named constants with defaults. No magic numbers inline in pipeline stages.
- Kotlin comparators used with `sortedWith` must be transitive. Quantize continuous values into bands before comparing; do not write tolerance-based comparators, which throw `IllegalArgumentException` from Java's TimSort.
- If a pinned dependency version fails to resolve, bump to the nearest working version and record the change in a `## Version notes` section appended to this plan. Do not silently substitute.

---

## File Structure

**`:ocr-core`** (`ocr-core/src/main/kotlin/id/dotcode/braille/ocr/`)

| File | Responsibility |
| --- | --- |
| `geometry/Geometry.kt` | `PointF`, `BoxF` value types and their derived properties |
| `raw/RawTextResult.kt` | Provider-agnostic OCR input contract: `RawTextResult`, `RawLine`, `RawWord` |
| `model/OcrDocument.kt` | Output tree: `OcrDocument`, `TextBlock`, `TextLine`, `BlockRole`, `Alignment`, `Timings` |
| `model/OcrResult.kt` | `OcrResult` sealed interface, `FailureReason` enum |
| `pipeline/StructuringConfig.kt` | All tunable thresholds in one place |
| `pipeline/Intermediates.kt` | `OrderedLine`, `LineGroup`, `ColumnAssignment`, `PageStats` |
| `pipeline/SkewEstimator.kt` | Stage 1: median line angle, coordinate rotation |
| `pipeline/ColumnSegmenter.kt` | Stage 2: vertical projection profile, column assignment |
| `pipeline/ReadingOrderSorter.kt` | Stage 3: column-major, y-banded ordering |
| `pipeline/LineMerger.kt` | Stage 4: lines into paragraph groups |
| `pipeline/MarkerParser.kt` | Stage 5: list and question marker extraction |
| `pipeline/RoleClassifier.kt` | Stage 6: semantic role assignment |
| `pipeline/DocumentStructurer.kt` | Orchestrator: `RawTextResult` → `OcrDocument` |
| `accuracy/ErrorRate.kt` | Character and word error rate via Levenshtein distance |

**`:ocr-mlkit`** (`ocr-mlkit/src/main/kotlin/id/dotcode/braille/ocr/mlkit/`)

| File | Responsibility |
| --- | --- |
| `ImagePreprocessor.kt` | Downscale to target long edge, EXIF orientation handling |
| `CaptureQualityGate.kt` | Blur and darkness pre-check on the downscaled grayscale |
| `MlKitAdapter.kt` | `com.google.mlkit.vision.text.Text` → `RawTextResult` |
| `OcrEngine.kt` | Public facade: `Uri`/`Bitmap` → `OcrResult`, owns the recognizer, records `Timings` |

**`:app`** (`app/src/main/kotlin/id/dotcode/braille/ocr/app/`)

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Activity, permission request, nav between capture and result |
| `CaptureScreen.kt` | CameraX preview, shutter, gallery import launcher |
| `ResultScreen.kt` | Structured block list, role chips, timing HUD, JSON export |
| `OcrViewModel.kt` | Holds `OcrResult` state, runs the engine off the main thread |

---

## Task 1: Project scaffolding and a green JVM test loop

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `ocr-core/build.gradle.kts`
- Create: `.gitignore`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/ScaffoldTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: a working `./gradlew :ocr-core:test` command. Every later task depends on this loop.

- [ ] **Step 1: Bootstrap the Gradle wrapper**

There is no Gradle on `PATH` and no `~/.gradle` on this machine, so the wrapper must be
bootstrapped once from a downloaded distribution.

```bash
mkdir -p "/c/Users/Kiel/Documents/SFT OCR/braille-ocr"
cd "/c/Users/Kiel/Documents/SFT OCR/braille-ocr"
curl -L -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
unzip -q /tmp/gradle.zip -d /tmp/gradle-dist
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" /tmp/gradle-dist/gradle-8.14.3/bin/gradle wrapper --gradle-version 8.14.3
```

Expected: `gradlew`, `gradlew.bat`, and `gradle/wrapper/` appear in the project root.

- [ ] **Step 2: Write `gradle.properties`**

```properties
org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 3: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.13.0"
kotlin = "2.1.21"
serialization = "1.8.1"
junit = "5.11.4"
camerax = "1.4.2"
mlkitText = "16.0.1"
composeBom = "2025.06.00"
activityCompose = "1.10.1"
lifecycle = "2.9.0"
coreKtx = "1.16.0"

[libraries]
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version = "1.11.4" }
mlkit-text-recognition = { module = "com.google.mlkit:text-recognition", version.ref = "mlkitText" }
camerax-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
camerax-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
camerax-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 4: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "braille-ocr"
include(":ocr-core")
include(":ocr-mlkit")
include(":app")
```

- [ ] **Step 5: Write the root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

- [ ] **Step 6: Write `ocr-core/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    sourceSets.all { kotlin.srcDirs("src/${name}/kotlin") }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

- [ ] **Step 7: Write `.gitignore`**

```gitignore
.gradle/
build/
local.properties
*.iml
.idea/
.kotlin/
captures/
```

- [ ] **Step 8: Write the failing scaffold test**

Create `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/ScaffoldTest.kt`:

```kotlin
package id.dotcode.braille.ocr

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ScaffoldTest {
    @Test
    fun `module has no android on the classpath`() {
        val loaded = runCatching { Class.forName("android.graphics.Bitmap") }.isSuccess
        assertEquals(false, loaded, "ocr-core must stay free of Android dependencies")
    }
}
```

Add `testImplementation(kotlin("test"))` to `ocr-core/build.gradle.kts` dependencies so
`kotlin.test.assertEquals` resolves.

- [ ] **Step 9: Run the test**

```bash
./gradlew :ocr-core:test
```

Expected: PASS. If the build fails on the JDK, verify `org.gradle.java.home` points at a
directory containing `bin/java` reporting version 21.

- [ ] **Step 10: Commit**

```bash
git init
git add -A
git commit -m "chore: scaffold braille-ocr multi-module gradle project"
```

---

## Task 2: Geometry primitives and the RawTextResult contract

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/geometry/Geometry.kt`
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/raw/RawTextResult.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/geometry/GeometryTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `BoxF(left, top, right, bottom)` with `width`, `height`, `centerX`, `centerY`,
  `BoxF.union(other)`, and `BoxF.Companion.enclosing(boxes: List<BoxF>)`. `PointF(x, y)`.
  `RawTextResult(imageWidth, imageHeight, lines)`, `RawLine(text, box, cornerPoints, angleDeg,
  recognizedLanguage, confidence, words)`, `RawWord(text, box, confidence)`. Every later task
  builds on these exact names.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.geometry

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GeometryTest {
    @Test
    fun `box exposes derived dimensions`() {
        val box = BoxF(left = 10f, top = 20f, right = 40f, bottom = 60f)
        assertEquals(30f, box.width)
        assertEquals(40f, box.height)
        assertEquals(25f, box.centerX)
        assertEquals(40f, box.centerY)
    }

    @Test
    fun `union spans both boxes`() {
        val a = BoxF(0f, 0f, 10f, 10f)
        val b = BoxF(5f, 20f, 30f, 25f)
        assertEquals(BoxF(0f, 0f, 30f, 25f), a.union(b))
    }

    @Test
    fun `enclosing folds a list`() {
        val boxes = listOf(BoxF(4f, 4f, 6f, 6f), BoxF(0f, 8f, 2f, 9f), BoxF(1f, 1f, 3f, 3f))
        assertEquals(BoxF(0f, 1f, 6f, 9f), BoxF.enclosing(boxes))
    }

    @Test
    fun `enclosing an empty list throws`() {
        assertTrue(runCatching { BoxF.enclosing(emptyList()) }.isFailure)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*GeometryTest*"
```

Expected: FAIL — `Unresolved reference: BoxF`.

- [ ] **Step 3: Write `Geometry.kt`**

```kotlin
package id.dotcode.braille.ocr.geometry

import kotlinx.serialization.Serializable

@Serializable
data class PointF(val x: Float, val y: Float)

@Serializable
data class BoxF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: BoxF): BoxF = BoxF(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    companion object {
        fun enclosing(boxes: List<BoxF>): BoxF {
            require(boxes.isNotEmpty()) { "cannot enclose an empty list of boxes" }
            return boxes.reduce { acc, box -> acc.union(box) }
        }
    }
}
```

- [ ] **Step 4: Write `RawTextResult.kt`**

```kotlin
package id.dotcode.braille.ocr.raw

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.geometry.PointF
import kotlinx.serialization.Serializable

/**
 * Provider-agnostic OCR output. Any recognizer — ML Kit today, a fine-tuned
 * Tesseract in a later phase — produces this and nothing else crosses the boundary.
 *
 * Note that provider-level block grouping is deliberately absent. ML Kit's blocks are
 * geometric clusters rather than semantic units; [id.dotcode.braille.ocr.pipeline.LineMerger]
 * re-derives paragraph boundaries under rules this project controls.
 */
@Serializable
data class RawTextResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val lines: List<RawLine>,
)

@Serializable
data class RawLine(
    val text: String,
    val box: BoxF,
    val cornerPoints: List<PointF> = emptyList(),
    val angleDeg: Float = 0f,
    val recognizedLanguage: String? = null,
    val confidence: Float? = null,
    val words: List<RawWord> = emptyList(),
)

@Serializable
data class RawWord(
    val text: String,
    val box: BoxF,
    val confidence: Float? = null,
)
```

- [ ] **Step 5: Run the test**

```bash
./gradlew :ocr-core:test --tests "*GeometryTest*"
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add geometry primitives and RawTextResult contract"
```

---

## Task 3: Output document model and JSON serialization

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/model/OcrDocument.kt`
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/model/OcrResult.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/model/OcrDocumentTest.kt`

**Interfaces:**
- Consumes: `BoxF` from Task 2
- Produces: `OcrDocument`, `TextBlock`, `TextLine`, `BlockRole`, `Alignment`, `Timings`,
  `OcrResult` (`Success`/`Failure`), `FailureReason`. Also `OcrDocument.toJson()` and
  `OcrDocument.fromJson(String)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.model

import id.dotcode.braille.ocr.geometry.BoxF
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class OcrDocumentTest {
    private fun sample() = OcrDocument(
        pageWidth = 1600,
        pageHeight = 2000,
        skewDeg = -1.5f,
        columnCount = 2,
        blocks = listOf(
            TextBlock(
                id = 0,
                role = BlockRole.QUESTION,
                columnIndex = 0,
                marker = "1.",
                indentLevel = 0,
                alignment = Alignment.LEFT,
                relativeTextHeight = 1.0f,
                text = "Sebutkan tiga contoh sumber daya alam.",
                lines = listOf(
                    TextLine("1. Sebutkan tiga contoh", BoxF(0f, 0f, 400f, 30f), null, null),
                    TextLine("sumber daya alam.", BoxF(0f, 34f, 300f, 64f), null, null),
                ),
                box = BoxF(0f, 0f, 400f, 64f),
                confidence = 0.94f,
            ),
        ),
        meanConfidence = 0.94f,
        timings = Timings(decodeMs = 12, preprocessMs = 8, recognizeMs = 210, structureMs = 3),
    )

    @Test
    fun `timings total sums every stage`() {
        assertEquals(233L, sample().timings.totalMs)
    }

    @Test
    fun `document survives a json round trip`() {
        val original = sample()
        assertEquals(original, OcrDocument.fromJson(original.toJson()))
    }

    @Test
    fun `json is human readable with named roles`() {
        val json = sample().toJson()
        assertEquals(true, json.contains("\"role\": \"QUESTION\""))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*OcrDocumentTest*"
```

Expected: FAIL — `Unresolved reference: OcrDocument`.

- [ ] **Step 3: Write `OcrDocument.kt`**

```kotlin
package id.dotcode.braille.ocr.model

import id.dotcode.braille.ocr.geometry.BoxF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Semantic role of a block, assigned by RoleClassifier. */
enum class BlockRole { TITLE, HEADING, QUESTION, LIST_ITEM, PARAGRAPH, CAPTION, PAGE_NUMBER }

/** Horizontal alignment of a block within its column. */
enum class Alignment { LEFT, CENTER, RIGHT }

@Serializable
data class Timings(
    val decodeMs: Long = 0,
    val preprocessMs: Long = 0,
    val recognizeMs: Long = 0,
    val structureMs: Long = 0,
) {
    val totalMs: Long get() = decodeMs + preprocessMs + recognizeMs + structureMs
}

@Serializable
data class TextLine(
    val text: String,
    val box: BoxF,
    val confidence: Float? = null,
    val recognizedLanguage: String? = null,
)

@Serializable
data class TextBlock(
    val id: Int,
    val role: BlockRole,
    val columnIndex: Int,
    val marker: String? = null,
    val indentLevel: Int = 0,
    val alignment: Alignment = Alignment.LEFT,
    /** Median line height of this block divided by the page's body-text median. 1.0 is body text. */
    val relativeTextHeight: Float = 1f,
    /** Reflowed text with the marker stripped. Original breaks stay in [lines]. */
    val text: String,
    val lines: List<TextLine>,
    val box: BoxF,
    val confidence: Float? = null,
)

@Serializable
data class OcrDocument(
    val pageWidth: Int,
    val pageHeight: Int,
    val skewDeg: Float,
    val columnCount: Int,
    /** Already sorted into reading order. */
    val blocks: List<TextBlock>,
    val meanConfidence: Float? = null,
    val timings: Timings = Timings(),
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
        fun fromJson(text: String): OcrDocument = JSON.decodeFromString(serializer(), text)
    }
}
```

- [ ] **Step 4: Write `OcrResult.kt`**

```kotlin
package id.dotcode.braille.ocr.model

/**
 * Why a capture was rejected. Blur and darkness are caught before recognition runs:
 * a blind student reading garbage braille has no way to notice the recognition failed,
 * so refusing a bad capture is safer than emitting confident nonsense.
 */
enum class FailureReason { TooBlurry, TooDark, NoTextFound, ModelUnavailable, Cancelled }

sealed interface OcrResult {
    data class Success(val document: OcrDocument) : OcrResult
    data class Failure(val reason: FailureReason, val detail: String? = null) : OcrResult
}
```

- [ ] **Step 5: Run the test**

```bash
./gradlew :ocr-core:test --tests "*OcrDocumentTest*"
```

Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add OcrDocument model with json serialization"
```

---

## Task 4: Config, intermediates, and the test line builder

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/StructuringConfig.kt`
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/Intermediates.kt`
- Create: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/TestLines.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/PageStatsTest.kt`

**Interfaces:**
- Consumes: `RawLine`, `BoxF` from Task 2
- Produces: `StructuringConfig` (data class, all thresholds), `OrderedLine(line, columnIndex)`,
  `LineGroup(lines: List<RawLine>, columnIndex: Int)` with `LineGroup.box`,
  `ColumnAssignment(columnIndex: List<Int>, columnCount: Int, bounds: List<ClosedFloatingPointRange<Float>>)`,
  `PageStats(medianLineHeight, medianCharWidth)` with `PageStats.from(lines)`. Test helper
  `line(text, x, y, w, h)` used by every later pipeline test.

- [ ] **Step 1: Write `StructuringConfig.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

/**
 * Every tunable threshold in the structuring pipeline. Defaults were chosen for
 * A4 worksheets photographed at a ~1600px long edge.
 */
data class StructuringConfig(
    /** Below this many degrees, skew correction is skipped as noise. */
    val minSkewDeg: Float = 0.5f,
    /** A gutter wider than this many median character widths splits a column. */
    val columnGutterFactor: Float = 3.0f,
    /** A column must hold at least this many lines to be considered real. */
    val minLinesPerColumn: Int = 2,
    /** Below this many lines, never attempt column splitting. */
    val minLinesForColumnSplit: Int = 4,
    /** Row banding granularity for reading order, in median line heights. */
    val rowBandFactor: Float = 0.7f,
    /** Lines merge into one block when their gap is at most this many median line heights. */
    val paragraphGapFactor: Float = 1.6f,
    /** Left edges count as aligned within this many median character widths. */
    val leftAlignToleranceFactor: Float = 1.2f,
    /** Relative text height above which a block is a HEADING. */
    val headingHeightRatio: Float = 1.25f,
    /** Relative text height above which a block is a TITLE. */
    val titleHeightRatio: Float = 1.6f,
    /** Relative text height below which a block is a CAPTION. */
    val captionHeightRatio: Float = 0.85f,
    /** Fraction of page height counted as the top band for TITLE promotion. */
    val topBandFraction: Float = 0.15f,
    /** Fraction of page height counted as the bottom band for PAGE_NUMBER. */
    val bottomBandFraction: Float = 0.92f,
    /** Numeric-marker blocks become QUESTION only once the page holds this many. */
    val minNumberedBlocksForQuestions: Int = 3,
    /** Indent quantum, in median character widths. */
    val indentQuantumFactor: Float = 2.0f,
    /** Max indent level reported. */
    val maxIndentLevel: Int = 4,
    /** Centering tolerance as a fraction of column width. */
    val centerToleranceFraction: Float = 0.05f,
    /** A block must leave this fraction of the column free on the left to count as centered or right-aligned. */
    val minSideMarginFraction: Float = 0.15f,
)
```

- [ ] **Step 2: Write the failing PageStats test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class PageStatsTest {
    @Test
    fun `median line height is the middle value`() {
        val lines = listOf(
            line("aaa", x = 0f, y = 0f, w = 60f, h = 20f),
            line("bbb", x = 0f, y = 30f, w = 60f, h = 30f),
            line("ccc", x = 0f, y = 70f, w = 60f, h = 40f),
        )
        assertEquals(30f, PageStats.from(lines).medianLineHeight)
    }

    @Test
    fun `median char width divides width by character count`() {
        val lines = listOf(
            line("abcd", x = 0f, y = 0f, w = 40f, h = 20f),
            line("ab", x = 0f, y = 30f, w = 20f, h = 20f),
        )
        assertEquals(10f, PageStats.from(lines).medianCharWidth)
    }

    @Test
    fun `blank lines do not poison the char width median`() {
        val lines = listOf(
            line("abcd", x = 0f, y = 0f, w = 40f, h = 20f),
            line("", x = 0f, y = 30f, w = 20f, h = 20f),
            line("abcdef", x = 0f, y = 60f, w = 60f, h = 20f),
        )
        assertEquals(10f, PageStats.from(lines).medianCharWidth)
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*PageStatsTest*"
```

Expected: FAIL — `Unresolved reference: line` and `Unresolved reference: PageStats`.

- [ ] **Step 4: Write the test helper `TestLines.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult

/** Builds a RawLine from top-left origin plus size, which reads far better in tests than four edges. */
fun line(
    text: String,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    angleDeg: Float = 0f,
    confidence: Float? = 0.95f,
): RawLine = RawLine(
    text = text,
    box = BoxF(left = x, top = y, right = x + w, bottom = y + h),
    angleDeg = angleDeg,
    confidence = confidence,
)

fun page(
    vararg lines: RawLine,
    width: Int = 1600,
    height: Int = 2000,
): RawTextResult = RawTextResult(imageWidth = width, imageHeight = height, lines = lines.toList())
```

- [ ] **Step 5: Write `Intermediates.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine

/** A line with its resolved column, produced by ColumnSegmenter and consumed by ReadingOrderSorter. */
data class OrderedLine(val line: RawLine, val columnIndex: Int)

/** Consecutive lines that LineMerger decided form one block. */
data class LineGroup(val lines: List<RawLine>, val columnIndex: Int) {
    val box: BoxF get() = BoxF.enclosing(lines.map { it.box })
}

/** Column layout of a page. [columnIndex] is parallel to the input line list. */
data class ColumnAssignment(
    val columnIndex: List<Int>,
    val columnCount: Int,
    /** Horizontal extent of each column, indexed by column. */
    val bounds: List<ClosedFloatingPointRange<Float>>,
)

/** Page-level scale references. Everything downstream measures distances in these units. */
data class PageStats(val medianLineHeight: Float, val medianCharWidth: Float) {
    companion object {
        fun from(lines: List<RawLine>): PageStats {
            val heights = lines.map { it.box.height }.filter { it > 0f }
            val charWidths = lines
                .filter { it.text.isNotBlank() && it.box.width > 0f }
                .map { it.box.width / it.text.length }
            return PageStats(
                medianLineHeight = median(heights) ?: 1f,
                medianCharWidth = median(charWidths) ?: 1f,
            )
        }

        internal fun median(values: List<Float>): Float? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }
}
```

- [ ] **Step 6: Run the test**

```bash
./gradlew :ocr-core:test --tests "*PageStatsTest*"
```

Expected: PASS, 3 tests.

- [ ] **Step 7: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add structuring config, intermediates and page stats"
```

---

## Task 5: SkewEstimator

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/SkewEstimator.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/SkewEstimatorTest.kt`

**Interfaces:**
- Consumes: `RawTextResult`, `PageStats`, `StructuringConfig`
- Produces: `SkewEstimator(config)` with `estimateDeg(lines: List<RawLine>): Float` and
  `deskew(result: RawTextResult): DeskewResult`, where
  `DeskewResult(result: RawTextResult, skewDeg: Float)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SkewEstimatorTest {
    private val estimator = SkewEstimator(StructuringConfig())

    @Test
    fun `a straight page reports no skew`() {
        val lines = listOf(
            line("satu", 0f, 0f, 100f, 20f),
            line("dua", 0f, 30f, 100f, 20f),
        )
        assertEquals(0f, estimator.estimateDeg(lines))
    }

    @Test
    fun `median ignores a single wild outlier`() {
        val lines = listOf(
            line("satu", 0f, 0f, 100f, 20f, angleDeg = 2f),
            line("dua", 0f, 30f, 100f, 20f, angleDeg = 2f),
            line("tiga", 0f, 60f, 100f, 20f, angleDeg = 2f),
            line("empat", 0f, 90f, 100f, 20f, angleDeg = 47f),
        )
        assertTrue(abs(estimator.estimateDeg(lines) - 2f) < 0.01f)
    }

    @Test
    fun `skew below the threshold is left uncorrected`() {
        val input = page(line("satu", 100f, 100f, 200f, 20f, angleDeg = 0.2f))
        val result = estimator.deskew(input)
        assertEquals(0f, result.skewDeg)
        assertEquals(input.lines.first().box, result.result.lines.first().box)
    }

    @Test
    fun `deskew rotates boxes back to upright`() {
        // A line rotated +10 degrees about the page center should come back with a
        // near-horizontal box whose center is close to where an upright line would sit.
        val input = page(
            line("miring", 700f, 900f, 200f, 40f, angleDeg = 10f),
            line("miring dua", 700f, 960f, 200f, 40f, angleDeg = 10f),
            line("miring tiga", 700f, 1020f, 200f, 40f, angleDeg = 10f),
        )
        val result = estimator.deskew(input)
        assertTrue(abs(result.skewDeg - 10f) < 0.01f)
        // Rotation is area-preserving for the enclosing hull, so heights stay sane.
        result.result.lines.forEach { assertTrue(it.box.height in 30f..60f) }
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*SkewEstimatorTest*"
```

Expected: FAIL — `Unresolved reference: SkewEstimator`.

- [ ] **Step 3: Write `SkewEstimator.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.geometry.PointF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class DeskewResult(val result: RawTextResult, val skewDeg: Float)

/**
 * Stage 1. Estimates page rotation and maps every coordinate into upright page space so
 * later stages may assume axis-aligned boxes.
 *
 * The estimate is a median rather than a mean: one badly recognized line at a wild angle
 * should not tilt the whole page.
 */
class SkewEstimator(private val config: StructuringConfig) {

    fun estimateDeg(lines: List<RawLine>): Float {
        val angles = lines.filter { it.text.isNotBlank() }.map { it.angleDeg }
        return PageStats.median(angles) ?: 0f
    }

    fun deskew(result: RawTextResult): DeskewResult {
        val skew = estimateDeg(result.lines)
        if (abs(skew) < config.minSkewDeg) return DeskewResult(result, 0f)

        val pivot = PointF(result.imageWidth / 2f, result.imageHeight / 2f)
        val rotated = result.lines.map { it.rotatedBy(-skew, pivot) }
        return DeskewResult(result.copy(lines = rotated), skew)
    }

    private fun RawLine.rotatedBy(deg: Float, pivot: PointF): RawLine =
        copy(box = box.rotatedBy(deg, pivot), angleDeg = 0f, words = words.map { it.copy(box = it.box.rotatedBy(deg, pivot)) })

    private fun BoxF.rotatedBy(deg: Float, pivot: PointF): BoxF {
        val rad = deg * Math.PI.toFloat() / 180f
        val cos = cos(rad)
        val sin = sin(rad)
        val corners = listOf(
            PointF(left, top), PointF(right, top), PointF(right, bottom), PointF(left, bottom),
        ).map { p ->
            val dx = p.x - pivot.x
            val dy = p.y - pivot.y
            PointF(pivot.x + dx * cos - dy * sin, pivot.y + dx * sin + dy * cos)
        }
        return BoxF(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y },
        )
    }
}
```

Note: `RawWord` must be imported implicitly through `words.map`; no extra import is needed
because the lambda parameter type is inferred.

- [ ] **Step 4: Run the test**

```bash
./gradlew :ocr-core:test --tests "*SkewEstimatorTest*"
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add skew estimation and coordinate deskewing"
```

---

## Task 6: ColumnSegmenter

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/ColumnSegmenter.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/ColumnSegmenterTest.kt`

**Interfaces:**
- Consumes: `RawLine`, `PageStats`, `StructuringConfig`, `ColumnAssignment`
- Produces: `ColumnSegmenter(config)` with `segment(lines: List<RawLine>, stats: PageStats): ColumnAssignment`

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ColumnSegmenterTest {
    private val segmenter = ColumnSegmenter(StructuringConfig())

    @Test
    fun `a single column page yields one column`() {
        val lines = listOf(
            line("baris satu", 100f, 100f, 800f, 30f),
            line("baris dua", 100f, 140f, 800f, 30f),
            line("baris tiga", 100f, 180f, 800f, 30f),
            line("baris empat", 100f, 220f, 800f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines))
        assertEquals(1, result.columnCount)
        assertEquals(listOf(0, 0, 0, 0), result.columnIndex)
    }

    @Test
    fun `a wide gutter splits two columns`() {
        val lines = listOf(
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kiri dua", 100f, 140f, 500f, 30f),
            line("kanan satu", 900f, 100f, 500f, 30f),
            line("kanan dua", 900f, 140f, 500f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines))
        assertEquals(2, result.columnCount)
        assertEquals(listOf(0, 0, 1, 1), result.columnIndex)
    }

    @Test
    fun `a full width heading above two columns does not break the split`() {
        val lines = listOf(
            line("JUDUL LEMBAR KERJA", 100f, 40f, 1300f, 40f),
            line("kiri satu", 100f, 140f, 500f, 30f),
            line("kiri dua", 100f, 180f, 500f, 30f),
            line("kanan satu", 900f, 140f, 500f, 30f),
            line("kanan dua", 900f, 180f, 500f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines))
        // The heading spans the gutter, so its interval merges the two runs and the page
        // is reported as one column. That is the correct conservative answer: a wrong
        // split scrambles reading order, while a missed split only under-segments.
        assertEquals(1, result.columnCount)
    }

    @Test
    fun `a lone line in a would be column collapses the split`() {
        val lines = listOf(
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kiri dua", 100f, 140f, 500f, 30f),
            line("kiri tiga", 100f, 180f, 500f, 30f),
            line("9", 1400f, 1900f, 20f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines))
        assertEquals(1, result.columnCount)
    }

    @Test
    fun `too few lines never splits`() {
        val lines = listOf(
            line("kiri", 100f, 100f, 300f, 30f),
            line("kanan", 1200f, 100f, 300f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines))
        assertEquals(1, result.columnCount)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*ColumnSegmenterTest*"
```

Expected: FAIL — `Unresolved reference: ColumnSegmenter`.

- [ ] **Step 3: Write `ColumnSegmenter.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine

/**
 * Stage 2. Finds column gutters with a vertical projection profile over line x-ranges.
 *
 * This stage exists because a two-column worksheet processed without it interleaves the
 * left and right columns line by line, producing text that is not merely misformatted
 * but semantically scrambled. It is deliberately conservative: a wrong split is far more
 * damaging than a missed one, so ambiguous pages collapse to a single column.
 */
class ColumnSegmenter(private val config: StructuringConfig) {

    fun segment(lines: List<RawLine>, stats: PageStats): ColumnAssignment {
        val single = singleColumn(lines)
        if (lines.size < config.minLinesForColumnSplit) return single

        val runs = mergeIntervals(lines.map { it.box.left to it.box.right })
        if (runs.size < 2) return single

        val threshold = stats.medianCharWidth * config.columnGutterFactor
        val boundaries = runs.zipWithNext()
            .filter { (a, b) -> b.first - a.second >= threshold }
            .map { (a, b) -> (a.second + b.first) / 2f }
        if (boundaries.isEmpty()) return single

        val assignment = lines.map { l -> boundaries.count { it < l.box.centerX } }
        val columnCount = boundaries.size + 1

        // Every column must be substantial, otherwise a stray page number or margin note
        // masquerades as a column.
        val populated = (0 until columnCount).all { c -> assignment.count { it == c } >= config.minLinesPerColumn }
        if (!populated) return single

        val bounds = (0 until columnCount).map { c ->
            val boxes = lines.filterIndexed { i, _ -> assignment[i] == c }.map { it.box }
            boxes.minOf { it.left }..boxes.maxOf { it.right }
        }
        return ColumnAssignment(assignment, columnCount, bounds)
    }

    private fun singleColumn(lines: List<RawLine>): ColumnAssignment {
        val bounds = if (lines.isEmpty()) listOf(0f..0f)
        else listOf(lines.minOf { it.box.left }..lines.maxOf { it.box.right })
        return ColumnAssignment(List(lines.size) { 0 }, 1, bounds)
    }

    private fun mergeIntervals(intervals: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val sorted = intervals.sortedBy { it.first }
        val merged = mutableListOf<Pair<Float, Float>>()
        for ((left, right) in sorted) {
            val last = merged.lastOrNull()
            if (last != null && left <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, right)
            } else {
                merged.add(left to right)
            }
        }
        return merged
    }
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :ocr-core:test --tests "*ColumnSegmenterTest*"
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add column segmentation via projection profile"
```

---

## Task 7: ReadingOrderSorter

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/ReadingOrderSorter.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/ReadingOrderSorterTest.kt`

**Interfaces:**
- Consumes: `RawLine`, `ColumnAssignment`, `PageStats`, `StructuringConfig`, `OrderedLine`
- Produces: `ReadingOrderSorter(config)` with
  `sort(lines: List<RawLine>, columns: ColumnAssignment, stats: PageStats): List<OrderedLine>`

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ReadingOrderSorterTest {
    private val config = StructuringConfig()
    private val sorter = ReadingOrderSorter(config)

    private fun order(lines: List<id.dotcode.braille.ocr.raw.RawLine>): List<String> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(config).segment(lines, stats)
        return sorter.sort(lines, columns, stats).map { it.line.text }
    }

    @Test
    fun `single column sorts top to bottom regardless of input order`() {
        val lines = listOf(
            line("tiga", 100f, 200f, 400f, 30f),
            line("satu", 100f, 100f, 400f, 30f),
            line("dua", 100f, 150f, 400f, 30f),
        )
        assertEquals(listOf("satu", "dua", "tiga"), order(lines))
    }

    @Test
    fun `two columns read fully down the left before the right`() {
        val lines = listOf(
            line("kanan satu", 900f, 100f, 500f, 30f),
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kanan dua", 900f, 150f, 500f, 30f),
            line("kiri dua", 100f, 150f, 500f, 30f),
        )
        assertEquals(listOf("kiri satu", "kiri dua", "kanan satu", "kanan dua"), order(lines))
    }

    @Test
    fun `lines on the same visual row sort left to right despite baseline jitter`() {
        // Both fragments belong to one printed row; the right one sits 4px lower.
        val lines = listOf(
            line("kanan", 700f, 104f, 200f, 30f),
            line("kiri", 100f, 100f, 200f, 30f),
            line("bawah", 100f, 200f, 200f, 30f),
            line("bawah dua", 100f, 250f, 200f, 30f),
        )
        assertEquals(listOf("kiri", "kanan", "bawah", "bawah dua"), order(lines))
    }

    @Test
    fun `sorting an empty page yields an empty list`() {
        assertEquals(emptyList(), order(emptyList()))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*ReadingOrderSorterTest*"
```

Expected: FAIL — `Unresolved reference: ReadingOrderSorter`.

- [ ] **Step 3: Write `ReadingOrderSorter.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine
import kotlin.math.floor

/**
 * Stage 3. Column-major, then top to bottom, with lines banded into visual rows so that
 * baseline jitter does not reorder fragments that belong to the same printed row.
 *
 * The band is quantized rather than compared with a tolerance on purpose. A
 * tolerance-based comparator is not transitive, and Java's TimSort throws
 * IllegalArgumentException when it detects that.
 */
class ReadingOrderSorter(private val config: StructuringConfig) {

    fun sort(lines: List<RawLine>, columns: ColumnAssignment, stats: PageStats): List<OrderedLine> {
        if (lines.isEmpty()) return emptyList()
        val bandHeight = (stats.medianLineHeight * config.rowBandFactor).coerceAtLeast(1f)

        return lines
            .mapIndexed { index, l -> OrderedLine(l, columns.columnIndex.getOrElse(index) { 0 }) }
            .sortedWith(
                compareBy(
                    { it.columnIndex },
                    { floor(it.line.box.centerY / bandHeight).toInt() },
                    { it.line.box.left },
                )
            )
    }
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :ocr-core:test --tests "*ReadingOrderSorterTest*"
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add column-major reading order sorting"
```

---

## Task 8: MarkerParser

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/MarkerParser.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/MarkerParserTest.kt`

**Interfaces:**
- Consumes: nothing beyond Kotlin stdlib
- Produces: `MarkerParser` (object) with `parse(text: String): ParsedMarker?` where
  `ParsedMarker(marker: String, kind: MarkerKind, remainder: String)` and
  `MarkerKind` is `NUMERIC`, `ALPHA`, `ROMAN`, `BULLET`.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class MarkerParserTest {
    @Test
    fun `numeric marker with a period`() {
        val parsed = MarkerParser.parse("1. Sebutkan tiga contoh sumber daya alam.")
        assertEquals("1.", parsed?.marker)
        assertEquals(MarkerKind.NUMERIC, parsed?.kind)
        assertEquals("Sebutkan tiga contoh sumber daya alam.", parsed?.remainder)
    }

    @Test
    fun `numeric marker with a parenthesis`() {
        val parsed = MarkerParser.parse("12) Jelaskan proses fotosintesis")
        assertEquals("12)", parsed?.marker)
        assertEquals(MarkerKind.NUMERIC, parsed?.kind)
        assertEquals("Jelaskan proses fotosintesis", parsed?.remainder)
    }

    @Test
    fun `alpha marker`() {
        val parsed = MarkerParser.parse("a. Jakarta")
        assertEquals("a.", parsed?.marker)
        assertEquals(MarkerKind.ALPHA, parsed?.kind)
        assertEquals("Jakarta", parsed?.remainder)
    }

    @Test
    fun `roman marker is preferred over alpha`() {
        val parsed = MarkerParser.parse("iii. Bagian ketiga")
        assertEquals("iii.", parsed?.marker)
        assertEquals(MarkerKind.ROMAN, parsed?.kind)
    }

    @Test
    fun `bullet marker`() {
        val parsed = MarkerParser.parse("• Air bersih")
        assertEquals("•", parsed?.marker)
        assertEquals(MarkerKind.BULLET, parsed?.kind)
        assertEquals("Air bersih", parsed?.remainder)
    }

    @Test
    fun `plain prose has no marker`() {
        assertNull(MarkerParser.parse("Sumber daya alam adalah kekayaan alam."))
    }

    @Test
    fun `a decimal number is not a marker`() {
        assertNull(MarkerParser.parse("3.14 adalah nilai pi"))
    }

    @Test
    fun `a marker with no following text is not a marker`() {
        assertNull(MarkerParser.parse("1."))
    }

    @Test
    fun `an over long number is not a marker`() {
        assertNull(MarkerParser.parse("2024. Tahun itu penting"))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*MarkerParserTest*"
```

Expected: FAIL — `Unresolved reference: MarkerParser`.

- [ ] **Step 3: Write `MarkerParser.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

enum class MarkerKind { NUMERIC, ALPHA, ROMAN, BULLET }

data class ParsedMarker(val marker: String, val kind: MarkerKind, val remainder: String)

/**
 * Stage 5. Splits a leading list or question marker away from the text body.
 *
 * This is what makes per-question answer tracking possible downstream: question 7 becomes
 * an addressable object rather than a substring the answer recorder has to hunt for.
 *
 * Order matters — roman numerals are tested before single letters so that "i." and "v."
 * are not misread as alphabetic markers.
 */
object MarkerParser {

    private val ROMAN = Regex("^([ivxlIVXL]{1,6})[.)]\\s+(\\S.*)$")
    private val NUMERIC = Regex("^(\\d{1,3})[.)]\\s+(\\S.*)$")
    private val ALPHA = Regex("^([a-zA-Z])[.)]\\s+(\\S.*)$")
    private val BULLET = Regex("^([\u2022\u00b7\u25cf*\\-\u2013])\\s+(\\S.*)$")

    fun parse(text: String): ParsedMarker? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        ROMAN.matchEntire(trimmed)?.let { m ->
            val token = m.groupValues[1]
            // "i" and "x" are also valid single letters; treat length-1 as roman only for i, v, x.
            if (token.length > 1 || token.lowercase() in setOf("i", "v", "x")) {
                return ParsedMarker(token + trimmed[token.length], MarkerKind.ROMAN, m.groupValues[2])
            }
        }
        NUMERIC.matchEntire(trimmed)?.let { m ->
            val token = m.groupValues[1]
            return ParsedMarker(token + trimmed[token.length], MarkerKind.NUMERIC, m.groupValues[2])
        }
        ALPHA.matchEntire(trimmed)?.let { m ->
            val token = m.groupValues[1]
            return ParsedMarker(token + trimmed[token.length], MarkerKind.ALPHA, m.groupValues[2])
        }
        BULLET.matchEntire(trimmed)?.let { m ->
            return ParsedMarker(m.groupValues[1], MarkerKind.BULLET, m.groupValues[2])
        }
        return null
    }
}
```

The `\\s+` after the delimiter is what rejects `3.14`: a decimal point is followed by a
digit, not whitespace. The `{1,3}` bound on `NUMERIC` is what rejects a year like `2024.`.

- [ ] **Step 4: Run the test**

```bash
./gradlew :ocr-core:test --tests "*MarkerParserTest*"
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add list and question marker parsing"
```

---

## Task 9: LineMerger

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/LineMerger.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/LineMergerTest.kt`

**Interfaces:**
- Consumes: `OrderedLine`, `PageStats`, `StructuringConfig`, `LineGroup`, `MarkerParser`
- Produces: `LineMerger(config)` with `merge(ordered: List<OrderedLine>, stats: PageStats): List<LineGroup>`,
  plus `LineMerger.reflow(lines: List<RawLine>): String` for joining text with hyphen handling.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class LineMergerTest {
    private val config = StructuringConfig()
    private val merger = LineMerger(config)

    private fun groups(lines: List<id.dotcode.braille.ocr.raw.RawLine>): List<LineGroup> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(config).segment(lines, stats)
        val ordered = ReadingOrderSorter(config).sort(lines, columns, stats)
        return merger.merge(ordered, stats)
    }

    @Test
    fun `tight lines with aligned edges form one paragraph`() {
        val lines = listOf(
            line("Sumber daya alam adalah kekayaan", 100f, 100f, 600f, 30f),
            line("yang tersedia di alam sekitar kita", 100f, 136f, 600f, 30f),
        )
        val result = groups(lines)
        assertEquals(1, result.size)
        assertEquals(2, result.first().lines.size)
    }

    @Test
    fun `a wide vertical gap starts a new block`() {
        val lines = listOf(
            line("Paragraf pertama di sini", 100f, 100f, 600f, 30f),
            line("Paragraf kedua jauh di bawah", 100f, 400f, 600f, 30f),
        )
        assertEquals(2, groups(lines).size)
    }

    @Test
    fun `terminal punctuation ends a block`() {
        val lines = listOf(
            line("Kalimat pertama selesai.", 100f, 100f, 600f, 30f),
            line("Kalimat kedua dimulai", 100f, 136f, 600f, 30f),
        )
        assertEquals(2, groups(lines).size)
    }

    @Test
    fun `a new marker always starts a block even when tightly spaced`() {
        val lines = listOf(
            line("1. Soal pertama", 100f, 100f, 600f, 30f),
            line("2. Soal kedua", 100f, 136f, 600f, 30f),
            line("3. Soal ketiga", 100f, 172f, 600f, 30f),
        )
        assertEquals(3, groups(lines).size)
    }

    @Test
    fun `a continuation line joins its marker block`() {
        val lines = listOf(
            line("1. Sebutkan tiga contoh sumber", 100f, 100f, 600f, 30f),
            line("daya alam di Indonesia", 100f, 136f, 600f, 30f),
            line("2. Jelaskan fotosintesis", 100f, 172f, 600f, 30f),
        )
        val result = groups(lines)
        assertEquals(2, result.size)
        assertEquals(2, result[0].lines.size)
        assertEquals(1, result[1].lines.size)
    }

    @Test
    fun `a column change always starts a block`() {
        val lines = listOf(
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kiri dua", 100f, 136f, 500f, 30f),
            line("kanan satu", 900f, 100f, 500f, 30f),
            line("kanan dua", 900f, 136f, 500f, 30f),
        )
        assertEquals(2, groups(lines).size)
    }

    @Test
    fun `reflow joins a hyphenated wrap without a space`() {
        val lines = listOf(
            line("pembela-", 100f, 100f, 300f, 30f),
            line("jaran", 100f, 136f, 200f, 30f),
        )
        assertEquals("pembelajaran", LineMerger.reflow(lines))
    }

    @Test
    fun `reflow joins ordinary wraps with a single space`() {
        val lines = listOf(
            line("sumber daya", 100f, 100f, 300f, 30f),
            line("alam", 100f, 136f, 200f, 30f),
        )
        assertEquals("sumber daya alam", LineMerger.reflow(lines))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*LineMergerTest*"
```

Expected: FAIL — `Unresolved reference: LineMerger`.

- [ ] **Step 3: Write `LineMerger.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine
import kotlin.math.abs

/**
 * Stage 4. Groups consecutive lines into blocks.
 *
 * Two lines merge only when they share a column, sit within a paragraph-sized vertical
 * gap, have aligned left edges (or a consistent first-line indent), the earlier line does
 * not end in terminal punctuation, and the later line does not open a new marker.
 */
class LineMerger(private val config: StructuringConfig) {

    fun merge(ordered: List<OrderedLine>, stats: PageStats): List<LineGroup> {
        if (ordered.isEmpty()) return emptyList()

        val groups = mutableListOf<LineGroup>()
        var current = mutableListOf(ordered.first().line)
        var currentColumn = ordered.first().columnIndex

        for (i in 1 until ordered.size) {
            val prev = ordered[i - 1]
            val next = ordered[i]
            if (continues(prev, next, stats)) {
                current.add(next.line)
            } else {
                groups.add(LineGroup(current.toList(), currentColumn))
                current = mutableListOf(next.line)
                currentColumn = next.columnIndex
            }
        }
        groups.add(LineGroup(current.toList(), currentColumn))
        return groups
    }

    private fun continues(prev: OrderedLine, next: OrderedLine, stats: PageStats): Boolean {
        if (prev.columnIndex != next.columnIndex) return false
        if (MarkerParser.parse(next.line.text) != null) return false
        if (prev.line.text.trimEnd().endsWith(TERMINAL_PUNCTUATION_REGEX)) return false

        val gap = next.line.box.top - prev.line.box.bottom
        if (gap > stats.medianLineHeight * config.paragraphGapFactor) return false
        if (gap < -stats.medianLineHeight) return false // overlapping rows are not a wrap

        val indentTolerance = stats.medianCharWidth * config.leftAlignToleranceFactor
        val leftDelta = abs(next.line.box.left - prev.line.box.left)
        // A first-line indent means the CONTINUATION sits further left than the opener.
        val isIndentedOpener = prev.line.box.left - next.line.box.left in 0f..(indentTolerance * 4f)
        return leftDelta <= indentTolerance || isIndentedOpener
    }

    private fun String.endsWith(regex: Regex): Boolean = regex.containsMatchIn(this)

    companion object {
        private val TERMINAL_PUNCTUATION_REGEX = Regex("[.!?:;]$")
        private val HYPHEN_WRAP = Regex("[-\u2010\u2011]$")

        /** Joins block lines into one string, collapsing hyphenated wraps. */
        fun reflow(lines: List<RawLine>): String {
            if (lines.isEmpty()) return ""
            val builder = StringBuilder(lines.first().text.trim())
            for (i in 1 until lines.size) {
                val text = lines[i].text.trim()
                if (HYPHEN_WRAP.containsMatchIn(builder)) {
                    builder.deleteCharAt(builder.lastIndex)
                    builder.append(text)
                } else {
                    builder.append(' ').append(text)
                }
            }
            return builder.toString()
        }
    }
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :ocr-core:test --tests "*LineMergerTest*"
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add paragraph merging with hyphen-aware reflow"
```

---

## Task 10: RoleClassifier

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/RoleClassifier.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/RoleClassifierTest.kt`

**Interfaces:**
- Consumes: `LineGroup`, `PageStats`, `StructuringConfig`, `MarkerParser`, `BlockRole`
- Produces: `RoleClassifier(config)` with
  `classify(groups: List<LineGroup>, stats: PageStats, pageHeight: Int): List<BlockRole>`
  returning one role per group, parallel to the input list.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.raw.RawLine
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class RoleClassifierTest {
    private val config = StructuringConfig()
    private val classifier = RoleClassifier(config)

    private fun classify(lines: List<RawLine>, pageHeight: Int = 2000): List<BlockRole> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(config).segment(lines, stats)
        val ordered = ReadingOrderSorter(config).sort(lines, columns, stats)
        val groups = LineMerger(config).merge(ordered, stats)
        return classifier.classify(groups, stats, pageHeight)
    }

    @Test
    fun `large text at the top of the page is a title`() {
        val lines = listOf(
            line("LEMBAR KERJA SISWA", 100f, 60f, 700f, 56f),
            line("Isi paragraf biasa di sini", 100f, 300f, 700f, 30f),
            line("Paragraf lain lagi di sini", 100f, 400f, 700f, 30f),
        )
        assertEquals(BlockRole.TITLE, classify(lines).first())
    }

    @Test
    fun `moderately large text mid page is a heading`() {
        val lines = listOf(
            line("Isi paragraf biasa di sini", 100f, 300f, 700f, 30f),
            line("Bagian Kedua", 100f, 700f, 400f, 42f),
            line("Paragraf lain lagi di sini", 100f, 800f, 700f, 30f),
        )
        assertEquals(BlockRole.HEADING, classify(lines)[1])
    }

    @Test
    fun `three or more numbered blocks become questions`() {
        val lines = listOf(
            line("1. Soal pertama di sini", 100f, 300f, 700f, 30f),
            line("2. Soal kedua di sini", 100f, 400f, 700f, 30f),
            line("3. Soal ketiga di sini", 100f, 500f, 700f, 30f),
        )
        assertEquals(listOf(BlockRole.QUESTION, BlockRole.QUESTION, BlockRole.QUESTION), classify(lines))
    }

    @Test
    fun `a lone numbered block stays a list item`() {
        val lines = listOf(
            line("Paragraf pembuka yang panjang", 100f, 300f, 700f, 30f),
            line("1. Satu satunya butir", 100f, 400f, 700f, 30f),
            line("Paragraf penutup yang panjang", 100f, 500f, 700f, 30f),
        )
        assertEquals(BlockRole.LIST_ITEM, classify(lines)[1])
    }

    @Test
    fun `a bullet is always a list item`() {
        val lines = listOf(
            line("• Air bersih", 100f, 300f, 700f, 30f),
            line("Paragraf biasa di sini saja", 100f, 400f, 700f, 30f),
            line("Paragraf lain di sini saja", 100f, 500f, 700f, 30f),
        )
        assertEquals(BlockRole.LIST_ITEM, classify(lines).first())
    }

    @Test
    fun `a numeric only block at the page foot is a page number`() {
        val lines = listOf(
            line("Paragraf biasa di sini saja", 100f, 300f, 700f, 30f),
            line("Paragraf lain di sini saja", 100f, 400f, 700f, 30f),
            line("9", 800f, 1930f, 20f, 28f),
        )
        assertEquals(BlockRole.PAGE_NUMBER, classify(lines).last())
    }

    @Test
    fun `small text is a caption`() {
        val lines = listOf(
            line("Paragraf biasa di sini saja", 100f, 300f, 700f, 40f),
            line("Paragraf lain di sini saja", 100f, 400f, 700f, 40f),
            line("Gambar 1 rantai makanan", 100f, 600f, 400f, 24f),
        )
        assertEquals(BlockRole.CAPTION, classify(lines).last())
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*RoleClassifierTest*"
```

Expected: FAIL — `Unresolved reference: RoleClassifier`.

- [ ] **Step 3: Write `RoleClassifier.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.BlockRole

/**
 * Stage 6. Assigns a semantic role from relative text height, marker kind, and page position.
 *
 * The "at least three numbered blocks" guard on QUESTION exists so that a lone numbered
 * bullet inside a prose passage is not mistaken for an exam question.
 */
class RoleClassifier(private val config: StructuringConfig) {

    fun classify(groups: List<LineGroup>, stats: PageStats, pageHeight: Int): List<BlockRole> {
        val numericCount = groups.count { MarkerParser.parse(it.reflowed())?.kind == MarkerKind.NUMERIC }
        val questionsLikely = numericCount >= config.minNumberedBlocksForQuestions

        return groups.map { group -> roleOf(group, stats, pageHeight, questionsLikely) }
    }

    private fun roleOf(
        group: LineGroup,
        stats: PageStats,
        pageHeight: Int,
        questionsLikely: Boolean,
    ): BlockRole {
        val text = group.reflowed()
        val relativeHeight = relativeHeight(group, stats)

        MarkerParser.parse(text)?.let { marker ->
            return if (marker.kind == MarkerKind.NUMERIC && questionsLikely) {
                BlockRole.QUESTION
            } else {
                BlockRole.LIST_ITEM
            }
        }

        if (isPageNumber(group, text, pageHeight)) return BlockRole.PAGE_NUMBER

        val inTopBand = group.box.centerY <= pageHeight * config.topBandFraction
        return when {
            relativeHeight >= config.titleHeightRatio -> BlockRole.TITLE
            relativeHeight >= config.headingHeightRatio && inTopBand -> BlockRole.TITLE
            relativeHeight >= config.headingHeightRatio -> BlockRole.HEADING
            relativeHeight <= config.captionHeightRatio -> BlockRole.CAPTION
            else -> BlockRole.PARAGRAPH
        }
    }

    private fun isPageNumber(group: LineGroup, text: String, pageHeight: Int): Boolean =
        group.lines.size == 1 &&
            group.box.top >= pageHeight * config.bottomBandFraction &&
            text.trim().matches(PAGE_NUMBER_REGEX)

    internal fun relativeHeight(group: LineGroup, stats: PageStats): Float {
        val groupMedian = PageStats.median(group.lines.map { it.box.height }) ?: return 1f
        return if (stats.medianLineHeight <= 0f) 1f else groupMedian / stats.medianLineHeight
    }

    companion object {
        private val PAGE_NUMBER_REGEX = Regex("^\\d{1,4}$")
    }
}

internal fun LineGroup.reflowed(): String = LineMerger.reflow(lines)
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :ocr-core:test --tests "*RoleClassifierTest*"
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add semantic role classification"
```

---

## Task 11: DocumentStructurer — the full pipeline

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurer.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/pipeline/DocumentStructurerTest.kt`
- Test fixture: `ocr-core/src/test/resources/fixtures/worksheet-two-column.json`

**Interfaces:**
- Consumes: every stage from Tasks 5–10
- Produces: `DocumentStructurer(config = StructuringConfig())` with
  `structure(raw: RawTextResult, timings: Timings = Timings()): OcrDocument`. This is
  `:ocr-core`'s single public entry point and the only thing `:ocr-mlkit` calls.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.Alignment
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.Timings
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DocumentStructurerTest {
    private val structurer = DocumentStructurer()

    @Test
    fun `an empty page yields an empty document`() {
        val doc = structurer.structure(page())
        assertEquals(0, doc.blocks.size)
        assertEquals(1, doc.columnCount)
    }

    @Test
    fun `a numbered worksheet yields addressable questions with markers stripped`() {
        val doc = structurer.structure(
            page(
                line("LEMBAR KERJA IPA", 100f, 60f, 600f, 56f),
                line("1. Sebutkan tiga contoh sumber", 100f, 300f, 700f, 30f),
                line("daya alam yang dapat diperbarui", 100f, 336f, 700f, 30f),
                line("2. Jelaskan proses fotosintesis", 100f, 420f, 700f, 30f),
                line("3. Apa fungsi akar pada tumbuhan", 100f, 500f, 700f, 30f),
            )
        )

        assertEquals(BlockRole.TITLE, doc.blocks.first().role)

        val questions = doc.blocks.filter { it.role == BlockRole.QUESTION }
        assertEquals(3, questions.size)
        assertEquals(listOf("1.", "2.", "3."), questions.map { it.marker })
        assertEquals(
            "Sebutkan tiga contoh sumber daya alam yang dapat diperbarui",
            questions.first().text,
        )
        // Original line breaks survive reflow.
        assertEquals(2, questions.first().lines.size)
    }

    @Test
    fun `block ids are sequential in reading order`() {
        val doc = structurer.structure(
            page(
                line("kanan satu", 900f, 100f, 500f, 30f),
                line("kiri satu", 100f, 100f, 500f, 30f),
                line("kanan dua", 900f, 300f, 500f, 30f),
                line("kiri dua", 100f, 300f, 500f, 30f),
            )
        )
        assertEquals(listOf(0, 1, 2, 3), doc.blocks.map { it.id })
        assertEquals("kiri satu", doc.blocks.first().text)
        assertEquals(2, doc.columnCount)
    }

    @Test
    fun `centered text is reported as centered`() {
        val doc = structurer.structure(
            page(
                line("Judul Di Tengah", 600f, 60f, 400f, 30f),
                line("baris kiri yang panjang sekali", 100f, 300f, 1400f, 30f),
                line("baris kiri lain yang panjang", 100f, 340f, 1400f, 30f),
            )
        )
        assertEquals(Alignment.CENTER, doc.blocks.first().alignment)
    }

    @Test
    fun `timings pass through untouched and mean confidence is averaged`() {
        val doc = structurer.structure(
            page(
                line("satu dua tiga", 100f, 100f, 400f, 30f, confidence = 0.8f),
                line("empat lima enam", 100f, 300f, 400f, 30f, confidence = 0.6f),
            ),
            Timings(decodeMs = 5, preprocessMs = 6, recognizeMs = 100, structureMs = 2),
        )
        assertEquals(113L, doc.timings.totalMs)
        assertTrue(doc.meanConfidence!! in 0.69f..0.71f)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*DocumentStructurerTest*"
```

Expected: FAIL — `Unresolved reference: DocumentStructurer`.

- [ ] **Step 3: Write `DocumentStructurer.kt`**

```kotlin
package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.Alignment
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.TextBlock
import id.dotcode.braille.ocr.model.TextLine
import id.dotcode.braille.ocr.model.Timings
import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Runs the six structuring stages in order and assembles the output document.
 *
 * This is the only public entry point of :ocr-core. Everything above it is an
 * implementation detail, which is what lets the ML Kit adapter be replaced by a
 * fine-tuned recognizer in a later phase without any downstream change.
 */
class DocumentStructurer(private val config: StructuringConfig = StructuringConfig()) {

    private val skewEstimator = SkewEstimator(config)
    private val columnSegmenter = ColumnSegmenter(config)
    private val readingOrderSorter = ReadingOrderSorter(config)
    private val lineMerger = LineMerger(config)
    private val roleClassifier = RoleClassifier(config)

    fun structure(raw: RawTextResult, timings: Timings = Timings()): OcrDocument {
        val usable = raw.copy(lines = raw.lines.filter { it.text.isNotBlank() })
        if (usable.lines.isEmpty()) {
            return OcrDocument(
                pageWidth = raw.imageWidth,
                pageHeight = raw.imageHeight,
                skewDeg = 0f,
                columnCount = 1,
                blocks = emptyList(),
                meanConfidence = null,
                timings = timings,
            )
        }

        val deskewed = skewEstimator.deskew(usable)
        val lines = deskewed.result.lines
        val stats = PageStats.from(lines)
        val columns = columnSegmenter.segment(lines, stats)
        val ordered = readingOrderSorter.sort(lines, columns, stats)
        val groups = lineMerger.merge(ordered, stats)
        val roles = roleClassifier.classify(groups, stats, deskewed.result.imageHeight)

        val blocks = groups.mapIndexed { index, group ->
            val reflowed = LineMerger.reflow(group.lines)
            val marker = MarkerParser.parse(reflowed)
            val columnBounds = columns.bounds.getOrElse(group.columnIndex) { columns.bounds.first() }
            TextBlock(
                id = index,
                role = roles[index],
                columnIndex = group.columnIndex,
                marker = marker?.marker,
                indentLevel = indentLevel(group, columnBounds.start, stats),
                alignment = alignment(group, columnBounds, stats),
                relativeTextHeight = roleClassifier.relativeHeight(group, stats),
                text = marker?.remainder ?: reflowed,
                lines = group.lines.map {
                    TextLine(it.text, it.box, it.confidence, it.recognizedLanguage)
                },
                box = group.box,
                confidence = averageConfidence(group.lines.mapNotNull { it.confidence }),
            )
        }

        return OcrDocument(
            pageWidth = deskewed.result.imageWidth,
            pageHeight = deskewed.result.imageHeight,
            skewDeg = deskewed.skewDeg,
            columnCount = columns.columnCount,
            blocks = blocks,
            meanConfidence = averageConfidence(lines.mapNotNull { it.confidence }),
            timings = timings,
        )
    }

    private fun indentLevel(group: LineGroup, columnLeft: Float, stats: PageStats): Int {
        val quantum = (stats.medianCharWidth * config.indentQuantumFactor).coerceAtLeast(1f)
        val level = ((group.box.left - columnLeft) / quantum).roundToInt()
        return level.coerceIn(0, config.maxIndentLevel)
    }

    private fun alignment(
        group: LineGroup,
        columnBounds: ClosedFloatingPointRange<Float>,
        stats: PageStats,
    ): Alignment {
        val columnWidth = columnBounds.endInclusive - columnBounds.start
        if (columnWidth <= 0f) return Alignment.LEFT

        val leftMargin = group.box.left - columnBounds.start
        val rightMargin = columnBounds.endInclusive - group.box.right
        val minMargin = columnWidth * config.minSideMarginFraction
        if (leftMargin < minMargin) return Alignment.LEFT

        val columnCenter = (columnBounds.start + columnBounds.endInclusive) / 2f
        val centerTolerance = columnWidth * config.centerToleranceFraction
        return when {
            abs(group.box.centerX - columnCenter) <= centerTolerance -> Alignment.CENTER
            rightMargin < minMargin -> Alignment.RIGHT
            else -> Alignment.LEFT
        }
    }

    private fun averageConfidence(values: List<Float>): Float? =
        if (values.isEmpty()) null else values.sum() / values.size
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :ocr-core:test --tests "*DocumentStructurerTest*"
```

Expected: PASS, 5 tests. If the `centered text` test fails, check that
`minSideMarginFraction` is not rejecting the block before the center comparison runs.

- [ ] **Step 5: Add a JSON fixture regression test**

Create `ocr-core/src/test/resources/fixtures/worksheet-two-column.json` holding a
serialized `RawTextResult` for a two-column worksheet with a spanning title, six numbered
questions split across the columns, and a page number at the foot. Build it by writing the
lines with the `line()` helper in a scratch test, calling
`Json.encodeToString(RawTextResult.serializer(), page(...))`, and pasting the output.

Then add to `DocumentStructurerTest`:

```kotlin
    @Test
    fun `two column worksheet fixture keeps questions in order`() {
        val json = checkNotNull(javaClass.getResourceAsStream("/fixtures/worksheet-two-column.json"))
            .bufferedReader().readText()
        val raw = kotlinx.serialization.json.Json.decodeFromString(
            id.dotcode.braille.ocr.raw.RawTextResult.serializer(), json,
        )
        val doc = DocumentStructurer().structure(raw)
        val markers = doc.blocks
            .filter { it.role == id.dotcode.braille.ocr.model.BlockRole.QUESTION }
            .mapNotNull { it.marker }
        assertEquals(listOf("1.", "2.", "3.", "4.", "5.", "6."), markers)
    }
```

- [ ] **Step 6: Run the whole module test suite**

```bash
./gradlew :ocr-core:test
```

Expected: PASS, all tests green.

- [ ] **Step 7: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): assemble the full document structuring pipeline"
```

---

## Task 12: Accuracy harness

**Files:**
- Create: `ocr-core/src/main/kotlin/id/dotcode/braille/ocr/accuracy/ErrorRate.kt`
- Test: `ocr-core/src/test/kotlin/id/dotcode/braille/ocr/accuracy/ErrorRateTest.kt`

**Interfaces:**
- Consumes: nothing beyond stdlib
- Produces: `ErrorRate` (object) with `levenshtein(a: List<String>, b: List<String>): Int`,
  `characterErrorRate(expected: String, actual: String): Double`,
  `wordErrorRate(expected: String, actual: String): Double`, and
  `AccuracyReport(cer, wer, expectedChars, expectedWords)` via `ErrorRate.compare(expected, actual)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.dotcode.braille.ocr.accuracy

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ErrorRateTest {
    @Test
    fun `identical text has zero error`() {
        val report = ErrorRate.compare("sumber daya alam", "sumber daya alam")
        assertEquals(0.0, report.cer)
        assertEquals(0.0, report.wer)
    }

    @Test
    fun `one substituted character in sixteen`() {
        val report = ErrorRate.compare("sumber daya alam", "sumber daya alan")
        assertTrue(abs(report.cer - 1.0 / 16.0) < 1e-9)
    }

    @Test
    fun `one wrong word in three`() {
        val report = ErrorRate.compare("sumber daya alam", "sumber daya alan")
        assertTrue(abs(report.wer - 1.0 / 3.0) < 1e-9)
    }

    @Test
    fun `whitespace is normalized before comparison`() {
        val report = ErrorRate.compare("sumber  daya\nalam", "sumber daya alam")
        assertEquals(0.0, report.cer)
    }

    @Test
    fun `empty expected text with output is fully wrong`() {
        assertEquals(1.0, ErrorRate.compare("", "sesuatu").cer)
    }

    @Test
    fun `insertions and deletions both count`() {
        assertEquals(2, ErrorRate.levenshtein(listOf("a", "b", "c"), listOf("a", "x", "b", "c", "d")))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
./gradlew :ocr-core:test --tests "*ErrorRateTest*"
```

Expected: FAIL — `Unresolved reference: ErrorRate`.

- [ ] **Step 3: Write `ErrorRate.kt`**

```kotlin
package id.dotcode.braille.ocr.accuracy

/**
 * Character and word error rates against a reference transcription.
 *
 * This turns the concept paper's ">90% accuracy across 50 SLB-A worksheets" from an
 * aspiration into a number that can be produced on demand once the samples exist.
 */
data class AccuracyReport(
    val cer: Double,
    val wer: Double,
    val expectedChars: Int,
    val expectedWords: Int,
) {
    val characterAccuracy: Double get() = (1.0 - cer).coerceIn(0.0, 1.0)
    val wordAccuracy: Double get() = (1.0 - wer).coerceIn(0.0, 1.0)
}

object ErrorRate {

    fun compare(expected: String, actual: String): AccuracyReport {
        val expectedNorm = normalize(expected)
        val actualNorm = normalize(actual)
        val expectedChars = expectedNorm.map { it.toString() }
        val actualChars = actualNorm.map { it.toString() }
        val expectedWords = expectedNorm.split(' ').filter { it.isNotEmpty() }
        val actualWords = actualNorm.split(' ').filter { it.isNotEmpty() }

        return AccuracyReport(
            cer = rate(levenshtein(expectedChars, actualChars), expectedChars.size, actualChars.size),
            wer = rate(levenshtein(expectedWords, actualWords), expectedWords.size, actualWords.size),
            expectedChars = expectedChars.size,
            expectedWords = expectedWords.size,
        )
    }

    fun characterErrorRate(expected: String, actual: String): Double = compare(expected, actual).cer

    fun wordErrorRate(expected: String, actual: String): Double = compare(expected, actual).wer

    fun levenshtein(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size

        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.size]
    }

    private fun rate(distance: Int, expectedSize: Int, actualSize: Int): Double = when {
        expectedSize > 0 -> distance.toDouble() / expectedSize
        actualSize > 0 -> 1.0
        else -> 0.0
    }

    private fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :ocr-core:test --tests "*ErrorRateTest*"
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add ocr-core/src
git commit -m "feat(ocr-core): add character and word error rate measurement"
```

---

## Task 13: `:ocr-mlkit` — preprocessing, quality gate, adapter, engine

**Files:**
- Create: `ocr-mlkit/build.gradle.kts`
- Create: `ocr-mlkit/src/main/AndroidManifest.xml`
- Create: `ocr-mlkit/src/main/kotlin/id/dotcode/braille/ocr/mlkit/ImagePreprocessor.kt`
- Create: `ocr-mlkit/src/main/kotlin/id/dotcode/braille/ocr/mlkit/CaptureQualityGate.kt`
- Create: `ocr-mlkit/src/main/kotlin/id/dotcode/braille/ocr/mlkit/MlKitAdapter.kt`
- Create: `ocr-mlkit/src/main/kotlin/id/dotcode/braille/ocr/mlkit/OcrEngine.kt`
- Test: `ocr-mlkit/src/test/kotlin/id/dotcode/braille/ocr/mlkit/CaptureQualityGateTest.kt`

**Interfaces:**
- Consumes: `RawTextResult`, `RawLine`, `RawWord`, `BoxF`, `PointF`, `OcrResult`, `Timings`,
  `DocumentStructurer` from `:ocr-core`
- Produces: `OcrEngine(context, config = StructuringConfig())` with
  `suspend fun recognize(uri: Uri): OcrResult`, `suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): OcrResult`,
  and `close()`. Also `ImagePreprocessor.downscale(bitmap, targetLongEdge): Bitmap`,
  `CaptureQualityGate.evaluate(bitmap): FailureReason?`, `MlKitAdapter.toRawTextResult(text, width, height)`.

- [ ] **Step 1: Write `ocr-mlkit/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "id.dotcode.braille.ocr.mlkit"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { jvmToolchain(21) }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

dependencies {
    api(project(":ocr-core"))
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
```

Create an empty `ocr-mlkit/consumer-rules.pro` file so the reference resolves.

- [ ] **Step 2: Write `ocr-mlkit/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 3: Write `ImagePreprocessor.kt`**

```kotlin
package id.dotcode.braille.ocr.mlkit

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * Scales a capture down before recognition.
 *
 * Full-resolution captures cost time without improving accuracy, while dropping below
 * roughly 1000px on the long edge starts to lose small print. Orientation is deliberately
 * NOT baked into the bitmap — it is handed to ML Kit as a rotation degree, which avoids a
 * full-frame copy.
 */
object ImagePreprocessor {

    const val TARGET_LONG_EDGE = 1600

    fun downscale(bitmap: Bitmap, targetLongEdge: Int = TARGET_LONG_EDGE): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= targetLongEdge) return bitmap
        val scale = targetLongEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** Reads EXIF orientation as a rotation in degrees, for handing to InputImage. */
    fun rotationDegrees(stream: InputStream): Int = when (
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}
```

Add `implementation("androidx.exifinterface:exifinterface:1.4.1")` to `ocr-mlkit`
dependencies, and add the matching entry to `libs.versions.toml` as
`androidx-exifinterface = { module = "androidx.exifinterface:exifinterface", version = "1.4.1" }`,
referencing it as `libs.androidx.exifinterface`.

- [ ] **Step 4: Write the failing quality gate test**

```kotlin
package id.dotcode.braille.ocr.mlkit

import id.dotcode.braille.ocr.model.FailureReason
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class CaptureQualityGateTest {
    private val gate = CaptureQualityGate()

    /** A synthetic grayscale plane: sharp alternating stripes have high gradient energy. */
    private fun stripes(width: Int, height: Int, period: Int, low: Int, high: Int): IntArray =
        IntArray(width * height) { i -> if ((i % width) / period % 2 == 0) low else high }

    private fun flat(width: Int, height: Int, value: Int): IntArray = IntArray(width * height) { value }

    @Test
    fun `sharp well lit content passes`() {
        assertNull(gate.evaluateLuma(stripes(64, 64, 4, 20, 235), 64, 64))
    }

    @Test
    fun `a flat mid gray frame is too blurry`() {
        assertEquals(FailureReason.TooBlurry, gate.evaluateLuma(flat(64, 64, 128), 64, 64))
    }

    @Test
    fun `a near black frame is too dark`() {
        assertEquals(FailureReason.TooDark, gate.evaluateLuma(stripes(64, 64, 4, 2, 10), 64, 64))
    }

    @Test
    fun `darkness is reported before blurriness`() {
        assertEquals(FailureReason.TooDark, gate.evaluateLuma(flat(64, 64, 3), 64, 64))
    }
}
```

- [ ] **Step 5: Run it to confirm it fails**

```bash
./gradlew :ocr-mlkit:testDebugUnitTest --tests "*CaptureQualityGateTest*"
```

Expected: FAIL — `Unresolved reference: CaptureQualityGate`.

- [ ] **Step 6: Write `CaptureQualityGate.kt`**

```kotlin
package id.dotcode.braille.ocr.mlkit

import android.graphics.Bitmap
import id.dotcode.braille.ocr.model.FailureReason
import kotlin.math.abs

/**
 * Rejects captures that cannot produce trustworthy text.
 *
 * This matters more here than in a typical OCR app. A blind student reading garbage
 * braille under their fingers has no way to notice that recognition failed, so refusing a
 * bad capture with actionable feedback is safer than emitting confident nonsense.
 *
 * Sharpness uses mean absolute horizontal gradient rather than a true Laplacian variance:
 * it is a single pass, allocation-free, and discriminates blur just as well at this
 * threshold.
 */
class CaptureQualityGate(
    private val minMeanLuma: Int = 35,
    private val minSharpness: Double = 4.0,
) {

    fun evaluate(bitmap: Bitmap): FailureReason? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return evaluateLuma(luma, width, height)
    }

    /** Separated from [evaluate] so the thresholds are testable without an Android runtime. */
    fun evaluateLuma(luma: IntArray, width: Int, height: Int): FailureReason? {
        if (luma.isEmpty()) return FailureReason.NoTextFound

        val meanLuma = luma.sumOf { it.toLong() }.toDouble() / luma.size
        if (meanLuma < minMeanLuma) return FailureReason.TooDark

        var gradientSum = 0L
        var samples = 0L
        for (y in 0 until height) {
            val row = y * width
            for (x in 1 until width) {
                gradientSum += abs(luma[row + x] - luma[row + x - 1]).toLong()
                samples++
            }
        }
        val sharpness = if (samples == 0L) 0.0 else gradientSum.toDouble() / samples
        return if (sharpness < minSharpness) FailureReason.TooBlurry else null
    }
}
```

- [ ] **Step 7: Run the quality gate test**

```bash
./gradlew :ocr-mlkit:testDebugUnitTest --tests "*CaptureQualityGateTest*"
```

Expected: PASS, 4 tests.

- [ ] **Step 8: Write `MlKitAdapter.kt`**

```kotlin
package id.dotcode.braille.ocr.mlkit

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.geometry.PointF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.raw.RawWord

/**
 * Translates ML Kit output into the provider-agnostic contract.
 *
 * ML Kit's own block grouping is discarded here on purpose. Its blocks are geometric
 * clusters rather than semantic units, and LineMerger re-derives paragraph boundaries
 * under rules this project controls.
 */
object MlKitAdapter {

    fun toRawTextResult(text: Text, imageWidth: Int, imageHeight: Int): RawTextResult {
        val lines = text.textBlocks
            .flatMap { it.lines }
            .map { line ->
                RawLine(
                    text = line.text,
                    box = line.boundingBox.toBoxF(),
                    cornerPoints = line.cornerPoints.orEmpty().map { PointF(it.x.toFloat(), it.y.toFloat()) },
                    angleDeg = line.angle,
                    recognizedLanguage = line.recognizedLanguage,
                    confidence = line.confidence,
                    words = line.elements.map { element ->
                        RawWord(
                            text = element.text,
                            box = element.boundingBox.toBoxF(),
                            confidence = element.confidence,
                        )
                    },
                )
            }
        return RawTextResult(imageWidth = imageWidth, imageHeight = imageHeight, lines = lines)
    }

    private fun Rect?.toBoxF(): BoxF =
        if (this == null) BoxF(0f, 0f, 0f, 0f)
        else BoxF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
```

- [ ] **Step 9: Write `OcrEngine.kt`**

```kotlin
package id.dotcode.braille.ocr.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import id.dotcode.braille.ocr.model.FailureReason
import id.dotcode.braille.ocr.model.OcrResult
import id.dotcode.braille.ocr.model.Timings
import id.dotcode.braille.ocr.pipeline.DocumentStructurer
import id.dotcode.braille.ocr.pipeline.StructuringConfig
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.system.measureTimeMillis

/**
 * The public entry point of the OCR module.
 *
 * The recognizer instance is long-lived on purpose: construction dominates cost, while
 * recognition on a warm instance is cheap. Callers must [close] it when done.
 */
class OcrEngine(
    private val context: Context,
    config: StructuringConfig = StructuringConfig(),
    private val qualityGate: CaptureQualityGate = CaptureQualityGate(),
) : AutoCloseable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val structurer = DocumentStructurer(config)

    suspend fun recognize(uri: Uri): OcrResult {
        var bitmap: Bitmap? = null
        var rotation = 0
        val decodeMs = measureTimeMillis {
            rotation = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { ImagePreprocessor.rotationDegrees(it) }
            }.getOrDefault(0)
            bitmap = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        val decoded = bitmap ?: return OcrResult.Failure(FailureReason.NoTextFound, "could not decode image")
        return recognize(decoded, rotation, decodeMs)
    }

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): OcrResult =
        recognize(bitmap, rotationDegrees, decodeMs = 0)

    private suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int, decodeMs: Long): OcrResult {
        var scaled: Bitmap = bitmap
        var gateFailure: FailureReason? = null
        val preprocessMs = measureTimeMillis {
            scaled = ImagePreprocessor.downscale(bitmap)
            gateFailure = qualityGate.evaluate(scaled)
        }
        gateFailure?.let { return OcrResult.Failure(it) }

        var raw: com.google.mlkit.vision.text.Text? = null
        var error: Throwable? = null
        val recognizeMs = measureTimeMillis {
            val input = InputImage.fromBitmap(scaled, rotationDegrees)
            val outcome = suspendCoroutine { continuation ->
                recognizer.process(input)
                    .addOnSuccessListener { continuation.resume(Result.success(it)) }
                    .addOnFailureListener { continuation.resume(Result.failure(it)) }
            }
            raw = outcome.getOrNull()
            error = outcome.exceptionOrNull()
        }
        error?.let { return OcrResult.Failure(FailureReason.ModelUnavailable, it.message) }
        val text = raw ?: return OcrResult.Failure(FailureReason.ModelUnavailable)
        if (text.textBlocks.isEmpty()) return OcrResult.Failure(FailureReason.NoTextFound)

        // Recognition ran on the scaled bitmap, so the document's page space is the
        // scaled space. Coordinates and page dimensions therefore stay consistent.
        val adapted = MlKitAdapter.toRawTextResult(text, scaled.width, scaled.height)
        var structureMs = 0L
        lateinit var document: id.dotcode.braille.ocr.model.OcrDocument
        structureMs = measureTimeMillis {
            document = structurer.structure(adapted)
        }
        return OcrResult.Success(
            document.copy(
                timings = Timings(
                    decodeMs = decodeMs,
                    preprocessMs = preprocessMs,
                    recognizeMs = recognizeMs,
                    structureMs = structureMs,
                )
            )
        )
    }

    override fun close() {
        recognizer.close()
    }
}
```

Add `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")` to
`ocr-mlkit` and register it in `libs.versions.toml` as
`kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version = "1.10.2" }`.

- [ ] **Step 10: Build the module**

```bash
./gradlew :ocr-mlkit:assembleDebug :ocr-mlkit:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, quality gate tests pass.

- [ ] **Step 11: Commit**

```bash
git add ocr-mlkit gradle/libs.versions.toml
git commit -m "feat(ocr-mlkit): add preprocessing, quality gate, ML Kit adapter and engine"
```

---

## Task 14: `:app` — capture, structured result view, JSON export

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Create: `app/src/main/kotlin/id/dotcode/braille/ocr/app/MainActivity.kt`
- Create: `app/src/main/kotlin/id/dotcode/braille/ocr/app/OcrViewModel.kt`
- Create: `app/src/main/kotlin/id/dotcode/braille/ocr/app/CaptureScreen.kt`
- Create: `app/src/main/kotlin/id/dotcode/braille/ocr/app/ResultScreen.kt`

**Interfaces:**
- Consumes: `OcrEngine`, `OcrResult`, `OcrDocument`, `TextBlock`, `BlockRole`, `FailureReason`
- Produces: a runnable demo APK. Nothing depends on this module.

- [ ] **Step 1: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.dotcode.braille.ocr.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.dotcode.braille.ocr"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { jvmToolchain(21) }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":ocr-mlkit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
```

- [ ] **Step 2: Write `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" android:required="false" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.BrailleOcr">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">BRaiLLE OCR</string>
</resources>
```

`app/src/main/res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.BrailleOcr" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 3: Write `OcrViewModel.kt`**

```kotlin
package id.dotcode.braille.ocr.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.dotcode.braille.ocr.mlkit.OcrEngine
import id.dotcode.braille.ocr.model.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Idle : UiState
    data object Working : UiState
    data class Done(val result: OcrResult) : UiState
}

class OcrViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = OcrEngine(application)
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun recognize(uri: Uri) {
        _state.value = UiState.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { engine.recognize(uri) }
            _state.value = UiState.Done(result)
        }
    }

    fun reset() { _state.value = UiState.Idle }

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }
}
```

- [ ] **Step 4: Write `CaptureScreen.kt`**

```kotlin
package id.dotcode.braille.ocr.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

@Composable
fun CaptureScreen(onImage: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onImage) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = androidx.camera.core.Preview.Builder().build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(onClick = {
                val file = File.createTempFile("capture", ".jpg", context.cacheDir)
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            onImage(Uri.fromFile(file))
                        }
                        override fun onError(exception: ImageCaptureException) = Unit
                    },
                )
            }) { Text("Ambil Foto") }

            Button(onClick = { pickImage.launch("image/*") }) { Text("Dari Galeri") }
        }
    }
}
```

- [ ] **Step 5: Write `ResultScreen.kt`**

```kotlin
package id.dotcode.braille.ocr.app

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.dotcode.braille.ocr.model.FailureReason
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.OcrResult
import id.dotcode.braille.ocr.model.TextBlock

@Composable
fun ResultScreen(result: OcrResult, onRetake: () -> Unit) {
    when (result) {
        is OcrResult.Failure -> FailureView(result.reason, onRetake)
        is OcrResult.Success -> DocumentView(result.document, onRetake)
    }
}

@Composable
private fun FailureView(reason: FailureReason, onRetake: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(reason.toIndonesian(), style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onRetake, modifier = Modifier.padding(top = 16.dp)) { Text("Coba Lagi") }
    }
}

/** Actionable Indonesian feedback, because "TooBlurry" helps nobody in a classroom. */
private fun FailureReason.toIndonesian(): String = when (this) {
    FailureReason.TooBlurry -> "Foto kurang tajam. Tahan perangkat lebih stabil, lalu coba lagi."
    FailureReason.TooDark -> "Cahaya kurang. Dekatkan ke sumber cahaya, lalu coba lagi."
    FailureReason.NoTextFound -> "Tidak ada teks yang terbaca. Pastikan lembar kerja tampak penuh."
    FailureReason.ModelUnavailable -> "Mesin pengenalan teks tidak tersedia."
    FailureReason.Cancelled -> "Proses dibatalkan."
}

@Composable
private fun DocumentView(document: OcrDocument, onRetake: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "${document.timings.totalMs} ms  •  ${document.blocks.size} blok  •  " +
                "${document.columnCount} kolom  •  skew ${"%.1f".format(document.skewDeg)}°",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "decode ${document.timings.decodeMs} / pre ${document.timings.preprocessMs} / " +
                "ocr ${document.timings.recognizeMs} / struct ${document.timings.structureMs} ms",
            style = MaterialTheme.typography.labelSmall,
        )

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Button(onClick = onRetake) { Text("Foto Lagi") }
            Button(
                onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, document.toJson())
                    }
                    context.startActivity(Intent.createChooser(share, "Ekspor JSON"))
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Ekspor JSON") }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(document.blocks) { block -> BlockCard(block) }
        }
    }
}

@Composable
private fun BlockCard(block: TextBlock) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "#${block.id}  ${block.role}  kol ${block.columnIndex}  " +
                    "indent ${block.indentLevel}  ${block.alignment}  " +
                    "×${"%.2f".format(block.relativeTextHeight)}",
                style = MaterialTheme.typography.labelSmall,
            )
            block.marker?.let {
                Text("penanda: $it", style = MaterialTheme.typography.labelMedium)
            }
            Text(block.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
```

- [ ] **Step 6: Write `MainActivity.kt`**

```kotlin
package id.dotcode.braille.ocr.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val model: OcrViewModel = viewModel()
                val state by model.state.collectAsState()

                var hasCamera by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
                val request = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> hasCamera = granted }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (!hasCamera) request.launch(Manifest.permission.CAMERA)
                }

                when (val current = state) {
                    is UiState.Idle ->
                        if (hasCamera) CaptureScreen(onImage = model::recognize)
                        else Centered("Izin kamera diperlukan")
                    is UiState.Working -> Centered("Membaca teks…")
                    is UiState.Done -> ResultScreen(current.result, onRetake = model::reset)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Centered(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message) }
}
```

- [ ] **Step 7: Build and install on the emulator**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator" -avd Pixel_10_Pro -no-snapshot-load &
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" wait-for-device
./gradlew :app:installDebug
```

- [ ] **Step 8: Verify the end-to-end flow**

Push a test worksheet image to the emulator and import it through the gallery button:

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" push worksheet.jpg /sdcard/Download/
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" shell am start -n id.dotcode.braille.ocr/.app.MainActivity
```

Confirm on screen: blocks appear in reading order, numbered questions carry their markers
in a separate field, the timing HUD reports a total under 1000 ms, and "Ekspor JSON"
produces a document tree.

- [ ] **Step 9: Run the whole suite**

```bash
./gradlew test :ocr-mlkit:testDebugUnitTest :app:assembleDebug
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 10: Commit**

```bash
git add app
git commit -m "feat(app): add camera capture, structured result view and json export"
```

---

## Self-Review Notes

**Spec coverage.** Section 2 module architecture → Tasks 1, 13, 14. Section 2 provider
boundary → Task 2. Section 3 stages 1–6 → Tasks 5, 6, 7, 9, 8, 10 respectively. Section 4
output model → Task 3, assembled in Task 11. Section 5 performance decisions → Task 13
(`ImagePreprocessor` downscale, EXIF-as-rotation, long-lived recognizer) with `Timings`
surfaced in Task 14's HUD. Section 6 failure handling → Task 3 (`OcrResult`) and Task 13
(`CaptureQualityGate`). Section 7 testing → every task's TDD steps plus Task 12's error-rate
harness and Task 11's fixture test. Section 8 build environment → Task 1.

**Known gap, deliberately deferred.** The spec's Section 7 mentions an instrumented test in
`:ocr-mlkit` asserting a bundled sample image produces plausible geometry. It is not a
separate task because it requires a real sample worksheet image that does not exist yet;
Task 14 Step 8 covers the same ground manually. Add the instrumented test once real SLB-A
samples are collected, alongside the accuracy run.

**Type consistency.** `BoxF`, `RawLine`, `PageStats`, `ColumnAssignment`, `OrderedLine`,
`LineGroup`, `ParsedMarker`, `MarkerKind`, `BlockRole`, `Alignment`, `Timings`, `OcrDocument`,
`TextBlock`, `TextLine`, `OcrResult`, `FailureReason`, `DocumentStructurer.structure`,
`LineMerger.reflow`, `RoleClassifier.relativeHeight`, `ErrorRate.compare`,
`OcrEngine.recognize`, `ImagePreprocessor.downscale`, `CaptureQualityGate.evaluate` — each is
defined once and referenced under the same name everywhere else.
