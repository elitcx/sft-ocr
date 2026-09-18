package id.dotcode.braille.ocr.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val CAPTURE_PREFIX = "capture"
private const val GUIDANCE_SPEECH_KEY = "guidance"

@Composable
fun CaptureScreen(model: OcrViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val assist by model.assist.collectAsState()

    fun isGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    var hasCamera by remember { mutableStateOf(isGranted()) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!hasCamera && !askedOnce) requestCamera.launch(Manifest.permission.CAMERA)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(model::recognize)
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importDocument(it, context.contentResolver.getType(it)) }
    }

    // Google's document scanner finds the page edges, flattens the page and drops the
    // background and any facing page - the source of most cut-off-word errors in plain
    // photos. It runs in Google Play services, so plain photos stay as the fallback.
    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                .setPageLimit(1)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build(),
        )
    }
    val scanDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            ?.pages?.firstOrNull()?.imageUri
            ?.let(model::recognize)
    }
    val startAutoScan = {
        val activity = context as? Activity
        if (activity != null) {
            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { sender -> scanDocument.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { scope.launch { snackbar.showSnackbar(strings.scannerUnavailable) } }
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val guided = assist.guidedCapture && hasCamera

    /** Takes a burst and hands the sharpest photo to the recognizer. */
    fun capture(fromGuidance: Boolean) {
        if (capturing) return
        capturing = true
        scope.launch {
            if (!fromGuidance) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            launch {
                flash.snapTo(0.7f)
                flash.animateTo(0f, tween(260))
            }
            // Only the newest capture is ever needed (it backs the current result).
            context.cacheDir.listFiles { f -> f.name.startsWith(CAPTURE_PREFIX) }?.forEach { it.delete() }
            val best = BurstCapture.capture(
                imageCapture = imageCapture,
                executor = ContextCompat.getMainExecutor(context),
                newFile = { File.createTempFile(CAPTURE_PREFIX, ".jpg", context.cacheDir) },
                shots = if (fromGuidance) BurstCapture.DEFAULT_SHOTS else 1,
            )
            capturing = false
            if (best == null) {
                model.haptics.failure()
                snackbar.showSnackbar(strings.captureFailed)
            } else {
                model.haptics.success()
                model.recognize(Uri.fromFile(best))
            }
        }
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        val guidance = if (guided) {
            rememberGuidance(model, onCapture = { capture(fromGuidance = true) }, active = !capturing)
        } else {
            null
        }

        if (hasCamera) {
            CameraPreview(
                imageCapture = imageCapture,
                analyzer = guidance?.analyzer,
                onCamera = { camera = it },
            )
            Viewfinder()
        } else {
            CameraPermissionPanel(
                permanentlyDenied = askedOnce && (context as? Activity)?.let {
                    !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
                } == true,
                onGrant = { requestCamera.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onResumeCheck = { hasCamera = isGranted() },
            )
        }

        // A dark page needs the light; guided capture turns it on rather than asking.
        val needsLight = guidance?.state?.instruction == Instruction.TOO_DARK
        LaunchedEffect(needsLight, camera) {
            if (needsLight && camera?.cameraInfo?.hasFlashUnit() == true && !torchOn) {
                torchOn = true
                camera?.cameraControl?.enableTorch(true)
            }
        }
        DisposableEffect(camera) {
            onDispose { runCatching { camera?.cameraControl?.enableTorch(false) } }
        }

        // Capture feedback: a brief white flash over the preview.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = flash.value }
                .background(Color.White),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Brl.Gutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BrlIconButton(R.drawable.ic_close, strings.close, onClose, onDark = true)
            val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
            AnimatedVisibility(hasCamera && hasFlash, enter = fadeIn(), exit = fadeOut()) {
                BrlIconButton(
                    if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off,
                    if (torchOn) strings.flashOff else strings.flashOn,
                    onClick = {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    },
                    onDark = true,
                    tint = if (torchOn) Brl.Vanila300 else Color.White,
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hasCamera) {
                GuidanceBanner(guidance?.state, capturing)
                Spacer(Modifier.height(16.dp))
            }
            AutoScanChip(onClick = startAutoScan)
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                LabeledIconButton(R.drawable.ic_photo_library, strings.gallery) {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                ShutterButton(
                    enabled = hasCamera && !capturing,
                    description = strings.takePhoto,
                    onClick = { capture(fromGuidance = false) },
                )
                LabeledIconButton(R.drawable.ic_upload_file, strings.document) {
                    pickDocument.launch(arrayOf(DocumentTextExtractor.PDF_MIME, DocumentTextExtractor.DOCX_MIME))
                }
            }
        }
    }
}

/** What the guided-capture loop exposes to the screen. */
private class GuidanceHolder(val analyzer: FrameAnalyzer, val stateProvider: () -> Guidance?) {
    val state: Guidance? get() = stateProvider()
}

/**
 * Runs the aiming loop: reads frames, keeps one instruction on screen at a time, speaks it,
 * pulses the vibration faster as the page lines up, and takes the photo when it is ready.
 */
@Composable
private fun rememberGuidance(
    model: OcrViewModel,
    onCapture: () -> Unit,
    active: Boolean,
): GuidanceHolder {
    val context = LocalContext.current
    val view = LocalView.current
    val strings = LocalStrings.current
    val assist by model.assist.collectAsState()
    val reading by model.reading.collectAsState()
    val readerOn = screenReaderOn()

    var guidance by remember { mutableStateOf<Guidance?>(null) }
    val engine = remember { GuidanceEngine() }
    val tilt = remember { TiltSensor(context) }
    val currentActive by rememberUpdatedState(active)
    val currentCapture by rememberUpdatedState(onCapture)
    val currentReading by rememberUpdatedState(reading)
    val currentReader by rememberUpdatedState(readerOn)
    val currentAssist by rememberUpdatedState(assist)

    val announcer = remember {
        Announcer(
            speakAloud = { text ->
                model.speech.speak(
                    GUIDANCE_SPEECH_KEY, text, voiceLocale(currentReading.voiceLanguage), currentReading.speechRate,
                )
            },
            announceToScreenReader = { text -> view.announceForAccessibility(text) },
            screenReaderOn = { currentReader },
            enabled = { currentAssist.spokenStatus },
        )
    }

    val analyzer = remember {
        FrameAnalyzer(tilt = { tilt.degrees }) { frame ->
            if (!currentActive) return@FrameAnalyzer
            val next = engine.update(frame, System.currentTimeMillis())
            guidance = next
            if (next.capture) currentCapture()
        }
    }

    DisposableEffect(Unit) {
        tilt.start()
        engine.reset(System.currentTimeMillis())
        onDispose {
            tilt.stop()
            analyzer.close()
            model.speech.stop(GUIDANCE_SPEECH_KEY)
        }
    }

    // Speaking: only when the instruction changes, and never more than every 1.5 seconds.
    LaunchedEffect(strings) {
        var spokenAt = 0L
        var spoken: Instruction? = null
        while (true) {
            val instruction = guidance?.instruction
            val now = System.currentTimeMillis()
            if (instruction != null && instruction != spoken && now - spokenAt >= SPEAK_GAP_MS) {
                spoken = instruction
                spokenAt = now
                announcer.say(strings.instruction(instruction))
            }
            delay(150)
        }
    }

    // Vibration: pulses get faster as the framing improves, like a metal detector.
    LaunchedEffect(Unit) {
        while (true) {
            val current = guidance
            if (current == null || current.instruction == Instruction.READY || !currentActive) {
                delay(250)
            } else {
                model.haptics.aim(current.closeness)
                delay((PULSE_SLOW_MS - (PULSE_SLOW_MS - PULSE_FAST_MS) * current.closeness).toLong())
            }
        }
    }

    return remember { GuidanceHolder(analyzer) { guidance } }
}

private const val SPEAK_GAP_MS = 1_500L
private const val PULSE_SLOW_MS = 700f
private const val PULSE_FAST_MS = 160f

/** The one instruction on screen, also announced to a screen reader as it changes. */
@Composable
private fun GuidanceBanner(guidance: Guidance?, capturing: Boolean) {
    val strings = LocalStrings.current
    val text = when {
        capturing -> strings.photoTaken
        guidance == null -> strings.scanHint
        else -> strings.instruction(guidance.instruction)
    }
    val ready = !capturing && guidance?.instruction == Instruction.READY
    val background by androidx.compose.animation.animateColorAsState(
        when {
            capturing || ready -> Brl.Honeydew500.copy(alpha = 0.92f)
            else -> Color.Black.copy(alpha = 0.55f)
        },
        tween(250), label = "guidanceBg",
    )
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "guidance",
    ) { shown ->
        Text(
            shown,
            color = Color.White,
            fontFamily = Urbanist,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = Brl.Gutter)
                .clip(Brl.Pill)
                .background(background)
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun CameraPreview(
    imageCapture: ImageCapture,
    analyzer: ImageAnalysis.Analyzer?,
    onCamera: (Camera?) -> Unit,
) {
    val context = LocalContext.current
    // Frame analysis must not run on the main thread: it decodes and measures every frame.
    val analysisExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    DisposableEffect(analysisExecutor) { onDispose { analysisExecutor.shutdown() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(lifecycleOwner, analyzer) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
            val useCases = mutableListOf(preview, imageCapture)
            if (analyzer != null) {
                // Aiming only needs a small frame; a big one would cost battery for nothing.
                analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }
                analysis?.let { useCases += it }
            }
            cameraProvider.unbindAll()
            onCamera(
                runCatching {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray(),
                    )
                }.getOrNull(),
            )
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            // Leaving the scan screen must release the camera, not keep it open behind results.
            disposed = true
            analysis?.clearAnalyzer()
            provider?.unbindAll()
            onCamera(null)
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** `.viewfinder`: a soft frame with vanilla corner accents and a sweeping scan line. */
@Composable
private fun Viewfinder() {
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    BoxWithConstraints(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val frameLeft = maxWidth * 0.05f
        val frameTop = maxHeight * 0.15f
        val frameWidth = maxWidth * 0.9f
        val frameHeight = maxHeight * 0.5f
        Canvas(
            Modifier
                .offset(frameLeft, frameTop)
                .size(frameWidth, frameHeight),
        ) {
            val radius = 24.dp.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.3f),
                cornerRadius = CornerRadius(radius),
                style = Stroke(2.dp.toPx()),
            )
            val stroke = 4.dp.toPx()
            val arm = 40.dp.toPx()
            val inset = stroke / 2 - 1.dp.toPx()
            val bounds = Rect(Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2))
            // One top-left accent, mirrored into the other three corners.
            val corner = cornerPath(bounds, radius, arm)
            listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f).forEach { (sx, sy) ->
                withTransform({ scale(sx, sy, pivot = bounds.center) }) {
                    drawPath(corner, Brl.Vanila300, style = Stroke(stroke))
                }
            }
            val y = sweep * size.height
            val glow = 10.dp.toPx()
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Brl.Vanila300.copy(alpha = 0.45f), Color.Transparent),
                    startY = y - glow, endY = y + glow,
                ),
                topLeft = Offset(0f, y - glow),
                size = Size(size.width, glow * 2),
            )
            drawRect(Brl.Vanila300, topLeft = Offset(0f, y - 1.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
        }
    }
}

private fun cornerPath(bounds: Rect, radius: Float, arm: Float) = Path().apply {
    moveTo(bounds.left, bounds.top + arm)
    lineTo(bounds.left, bounds.top + radius)
    arcTo(Rect(bounds.left, bounds.top, bounds.left + radius * 2, bounds.top + radius * 2), 180f, 90f, false)
    lineTo(bounds.left + arm, bounds.top)
}

@Composable
private fun ShutterButton(enabled: Boolean, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val inner by animateFloatAsState(if (pressed) 0.9f else 1f, tween(100), label = "shutter")
    Box(
        Modifier
            .size(72.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .graphicsLayer { scaleX = inner; scaleY = inner }
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun LabeledIconButton(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrlIconButton(icon, label, onClick, onDark = true, size = 56.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = Color.White.copy(alpha = 0.85f),
            fontFamily = Urbanist,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun AutoScanChip(onClick: () -> Unit) {
    val strings = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction, 0.96f)
            .clip(Brl.Pill)
            .background(Brl.GradAccent)
            .clickable(interaction, ripple(color = Brl.Ink900), role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrlIcon(R.drawable.ic_document_scanner, tint = Brl.Ink900, size = 20.dp)
        Text(strings.autoScan, color = Brl.Ink900, fontFamily = Urbanist, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            strings.recommended,
            color = Brl.Vanila700,
            fontFamily = Urbanist,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(Brl.Pill)
                .background(Color.White.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CameraPermissionPanel(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onResumeCheck: () -> Unit,
) {
    val strings = LocalStrings.current
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        onResumeCheck()
        onPauseOrDispose { }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(bottom = 180.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Brl.Ink700),
            contentAlignment = Alignment.Center,
        ) { BrlIcon(R.drawable.ic_photo_camera, tint = Brl.Vanila200, size = 36.dp) }
        Spacer(Modifier.height(20.dp))
        Text(
            strings.cameraPermTitle,
            style = BrlText.Title,
            color = Brl.Paper0,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(strings.cameraPermDesc, style = BrlText.Body, color = Brl.Ink200, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (permanentlyDenied) {
            BrlButton(strings.openAppSettings, onOpenSettings, icon = R.drawable.ic_open_in_new)
        } else {
            BrlButton(strings.cameraPermGrant, onGrant, icon = R.drawable.ic_photo_camera)
        }
    }
}
