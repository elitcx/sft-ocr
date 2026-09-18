@file:OptIn(ExperimentalTextApi::class)

package id.dotcode.braille.ocr.app

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The prototype's palette (`:root` in the BRaiLLE-UI index.html), one-to-one. */
object Brl {
    val Ink900 = Color(0xFF212121)
    val Ink800 = Color(0xFF2E2E2E)
    val Ink700 = Color(0xFF3F3F3F)
    val Ink600 = Color(0xFF575757)
    val Ink500 = Color(0xFF6E6E6E)
    val Ink400 = Color(0xFF8A8A8A)
    val Ink300 = Color(0xFFABABAB)
    val Ink200 = Color(0xFFCECECE)
    val Ink100 = Color(0xFFE5E5E5)
    val Paper0 = Color(0xFFFFFFFF)
    val Paper50 = Color(0xFFF6F5FA)
    val Paper100 = Color(0xFFEDECF3)
    val Paper200 = Color(0xFFE2E1EA)
    val Vanila100 = Color(0xFFF8F8DA)
    val Vanila200 = Color(0xFFEFF0A3)
    val Vanila300 = Color(0xFFDFE070)
    val Vanila500 = Color(0xFF9A9B2E)
    val Vanila700 = Color(0xFF62631A)
    val Honeydew100 = Color(0xFFE9F1E7)
    val Honeydew200 = Color(0xFFCFDECA)
    val Honeydew300 = Color(0xFFAEC6A7)
    val Honeydew500 = Color(0xFF6E8F66)
    val Honeydew700 = Color(0xFF3E5C39)
    val Alice100 = Color(0xFFEDF1F6)
    val Alice200 = Color(0xFFD8DFE9)
    val Alice300 = Color(0xFFB9C5D5)
    val Alice500 = Color(0xFF6F86A3)
    val Alice700 = Color(0xFF3F5570)
    val Alert100 = Color(0xFFF9DEDC)
    val Alert300 = Color(0xFFF2B8B5)
    val Alert600 = Color(0xFFB3261E)
    val Alert700 = Color(0xFF8C1D18)

    // CSS `linear-gradient(135deg, …)`: top-left to bottom-right.
    val GradAccent = Brush.linearGradient(listOf(Vanila200, Vanila300), Offset.Zero, Offset.Infinite)
    val GradDark = Brush.linearGradient(listOf(Ink700, Ink900), Offset.Zero, Offset.Infinite)

    val Pill = RoundedCornerShape(percent = 50)
    val CardShape = RoundedCornerShape(24.dp)

    /** Horizontal gutter of every screen in the prototype. */
    val Gutter = 24.dp
}

private fun urbanist(weight: Int) = Font(
    R.font.urbanist,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Urbanist = FontFamily(urbanist(400), urbanist(500), urbanist(600), urbanist(700), urbanist(800))

object BrlText {
    val H1 = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp)
    val H2 = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 31.sp)
    val H3 = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 24.sp)
    val Title = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 26.sp)
    val Body = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val BodySmall = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val Label = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp)
    val Overline = TextStyle(
        fontFamily = Urbanist, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
    )
}

@Composable
fun BrailleTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.u() = copy(fontFamily = Urbanist)
    val typography = Typography(
        displayLarge = base.displayLarge.u(), displayMedium = base.displayMedium.u(),
        displaySmall = base.displaySmall.u(), headlineLarge = base.headlineLarge.u(),
        headlineMedium = base.headlineMedium.u(), headlineSmall = base.headlineSmall.u(),
        titleLarge = base.titleLarge.u().copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.u().copy(fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.u(), bodyLarge = base.bodyLarge.u(),
        bodyMedium = base.bodyMedium.u(), bodySmall = base.bodySmall.u(),
        labelLarge = base.labelLarge.u().copy(fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.u(), labelSmall = base.labelSmall.u(),
    )
    val colors = lightColorScheme(
        primary = Brl.Ink900, onPrimary = Brl.Paper0,
        secondary = Brl.Vanila300, onSecondary = Brl.Ink900,
        background = Brl.Paper50, onBackground = Brl.Ink900,
        surface = Brl.Paper0, onSurface = Brl.Ink900,
        surfaceVariant = Brl.Paper100, onSurfaceVariant = Brl.Ink500,
        surfaceContainerLow = Brl.Paper0, surfaceContainer = Brl.Paper0,
        surfaceContainerHigh = Brl.Paper0, surfaceContainerHighest = Brl.Paper100,
        outline = Brl.Ink200, outlineVariant = Brl.Paper200,
        error = Brl.Alert600, onError = Brl.Paper0,
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

@Composable
fun BrlIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
        modifier = modifier.size(size),
    )
}

/** The prototype's `:active { transform: scale(…) }` press feedback. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, tween(200), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

enum class BrlButtonStyle { Primary, Dark, Outline, OutlineOnDark, Danger, Success }

@Composable
fun BrlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BrlButtonStyle = BrlButtonStyle.Primary,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    mini: Boolean = false,
    contentPadding: PaddingValues? = null,
    fontSize: TextUnit? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val (background, content, border) = when (style) {
        BrlButtonStyle.Primary -> Triple(Brl.GradAccent, Brl.Ink900, null)
        BrlButtonStyle.Dark -> Triple(Brl.GradDark, Brl.Paper0, null)
        BrlButtonStyle.Outline -> Triple(null, Brl.Ink900, BorderStroke(2.dp, Brl.Ink200))
        BrlButtonStyle.OutlineOnDark -> Triple(null, Brl.Paper0, BorderStroke(2.dp, Brl.Ink700))
        BrlButtonStyle.Danger -> Triple(
            androidx.compose.ui.graphics.SolidColor(Brl.Alert100), Brl.Alert700, BorderStroke(1.dp, Brl.Alert300),
        )
        BrlButtonStyle.Success -> Triple(androidx.compose.ui.graphics.SolidColor(Brl.Honeydew100), Brl.Honeydew700, null)
    }
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, tween(200), label = "enabled")
    Box(
        modifier = modifier
            .pressScale(interaction, 0.96f)
            .graphicsLayer { this.alpha = alpha }
            .heightIn(min = if (mini) 32.dp else 56.dp)
            .clip(Brl.Pill)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .then(if (border != null) Modifier.border(border, Brl.Pill) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = content),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                contentPadding ?: if (mini) PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                else PaddingValues(horizontal = 28.dp, vertical = 8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            val iconSize = if (mini) 18.dp else 24.dp
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize - 4.dp),
                    color = content,
                    strokeWidth = 2.5.dp,
                )
            } else if (icon != null) {
                BrlIcon(icon, tint = content, size = iconSize)
            }
            Text(
                text,
                color = content,
                fontFamily = Urbanist,
                fontWeight = if (mini) FontWeight.SemiBold else FontWeight.Bold,
                fontSize = fontSize ?: if (mini) 14.sp else 17.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The prototype's `.card`: white, 24 dp corners, a soft 24 dp shadow. */
@Composable
fun BrlCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    background: Brush? = null,
    color: Color = Brl.Paper0,
    border: BorderStroke? = null,
    elevated: Boolean = true,
    shape: Shape = Brl.CardShape,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    /** CSS `border-left: 4px solid …`: a strip down the leading edge. */
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        // Block-level like the prototype's cards, which stretch across their column.
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressScale(interaction, 0.98f) else Modifier)
            .then(
                if (elevated) {
                    Modifier.shadow(
                        elevation = 14.dp, shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.22f),
                    )
                } else Modifier,
            )
            .clip(shape)
            .then(if (background != null) Modifier.background(background) else Modifier.background(color))
            .then(
                if (accent != null) {
                    Modifier.drawBehind {
                        drawRect(accent, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
                    }
                } else Modifier,
            )
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        onClickLabel = onClickLabel,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else Modifier,
            )
            .padding(contentPadding),
        content = content,
    )
}

enum class TagTone { Neutral, Success, Alert, Info, Vanila, Dark }

@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.Neutral,
    @DrawableRes icon: Int? = null,
) {
    val (bg, fg) = tagColors(tone)
    val animatedBg by animateColorAsState(bg, tween(250), label = "tagBg")
    val animatedFg by animateColorAsState(fg, tween(250), label = "tagFg")
    Row(
        modifier = modifier
            .clip(Brl.Pill)
            .background(animatedBg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) BrlIcon(icon, tint = animatedFg, size = 16.dp)
        Text(text, style = BrlText.Caption, color = animatedFg, maxLines = 1)
    }
}

private fun tagColors(tone: TagTone): Pair<Color, Color> = when (tone) {
    TagTone.Neutral -> Brl.Paper100 to Brl.Ink700
    TagTone.Success -> Brl.Honeydew100 to Brl.Honeydew700
    TagTone.Alert -> Brl.Alert100 to Brl.Alert700
    TagTone.Info -> Brl.Alice100 to Brl.Alice700
    TagTone.Vanila -> Brl.Vanila100 to Brl.Vanila700
    TagTone.Dark -> Brl.Ink700 to Brl.Paper0
}

/** Round 48 dp icon button; `onDark` is the camera screen's translucent variant. */
@Composable
fun BrlIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
    tint: Color = if (onDark) Color.White else Brl.Ink900,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(interaction, 0.92f)
            .size(size)
            .clip(CircleShape)
            .then(if (onDark) Modifier.background(Color.White.copy(alpha = 0.2f)) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = tint),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        contentAlignment = Alignment.Center,
    ) {
        BrlIcon(icon, tint = tint, size = iconSize, contentDescription = contentDescription)
    }
}

/** A light screen's header row: back arrow, title, optional trailing content. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    backDescription: String,
    modifier: Modifier = Modifier,
    @DrawableRes backIcon: Int = R.drawable.ic_arrow_back,
    titleColor: Color = Brl.Ink900,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            BrlIconButton(
                backIcon, backDescription, onBack,
                modifier = Modifier.offset(x = (-12).dp),
                tint = titleColor,
            )
        }
        Text(
            title,
            style = BrlText.Title,
            color = titleColor,
            modifier = Modifier
                .weight(1f)
                // The back button is pulled into the gutter; keep the prototype's 16 dp gap.
                .offset(x = if (onBack != null) (-8).dp else 0.dp)
                .semantics { heading() },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/** The prototype's 40×24 toggle, animated. Purely visual: the row around it is the control. */
@Composable
fun BrlSwitchVisual(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val track by animateColorAsState(
        when {
            !enabled -> Brl.Ink100
            checked -> Brl.Honeydew300
            else -> Brl.Ink200
        },
        tween(200), label = "track",
    )
    val thumbOffset by animateDpAsState(if (checked) 18.dp else 2.dp, tween(200), label = "thumb")
    Box(
        modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(track),
    ) {
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(thumbOffset.roundToPx(), 2.dp.roundToPx()) }
                .size(20.dp)
                .shadow(1.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    dark: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                style = BrlText.Body.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    !enabled -> Brl.Ink300
                    dark -> Brl.Paper0
                    else -> Brl.Ink900
                },
            )
            if (description != null) {
                Text(description, style = BrlText.BodySmall, color = if (dark) Brl.Ink300 else Brl.Ink500)
            }
        }
        BrlSwitchVisual(checked, Modifier.clearAndSetSemantics { }, enabled)
    }
}

/**
 * One braille cell drawn as the prototype does: a 2×3 grid read row by row (dots 1-4, 2-5,
 * 3-6). [dots] is a bitmask with dot n at bit n-1.
 */
@Composable
fun BrailleCellView(
    dots: Int,
    dotSize: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    activeColor: Color = Brl.Vanila200,
    inactiveColor: Color = Brl.Ink600,
    glow: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (column in 0 until 2) {
                    val dot = if (column == 0) row + 1 else row + 4
                    BrailleDot(
                        active = dots and (1 shl (dot - 1)) != 0,
                        size = dotSize,
                        activeColor = activeColor,
                        inactiveColor = inactiveColor,
                        glow = glow,
                    )
                }
            }
        }
    }
}

@Composable
fun BrailleDot(active: Boolean, size: Dp, activeColor: Color, inactiveColor: Color, glow: Boolean) {
    val color by animateColorAsState(if (active) activeColor else inactiveColor, tween(200), label = "dot")
    val glowAlpha by animateFloatAsState(if (active && glow) 0.55f else 0f, tween(200), label = "glow")
    Box(
        Modifier
            .size(size)
            .drawBehind {
                if (glowAlpha > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(activeColor.copy(alpha = glowAlpha), Color.Transparent),
                            center = center,
                            radius = this.size.minDimension * 0.95f,
                        ),
                        radius = this.size.minDimension * 0.95f,
                    )
                }
            }
            .clip(CircleShape)
            .background(color),
    )
}

/** `.loading-braille-grid`: one pin lit at a time, travelling clockwise round the cell. */
@Composable
fun BrailleLoadingGrid(modifier: Modifier = Modifier, finished: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "loading")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(1800, easing = { it }), RepeatMode.Restart),
        label = "step",
    )
    // Grid positions in reading order are dots 1,4,2,5,3,6; clockwise is 1 → 4 → 5 → 6 → 3 → 2.
    val clockwiseDots = intArrayOf(1, 4, 5, 6, 3, 2)
    val lit = if (finished) 0b111111 else 1 shl (clockwiseDots[step.toInt().coerceIn(0, 5)] - 1)
    BrailleCellView(
        dots = lit,
        dotSize = 28.dp,
        gap = 12.dp,
        activeColor = if (finished) Brl.Honeydew300 else Brl.Vanila200,
        inactiveColor = Brl.Ink700,
        glow = true,
        modifier = modifier.clearAndSetSemantics { },
    )
}

@Composable
fun BrlSnackbar(data: SnackbarData) {
    Snackbar(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        containerColor = Brl.Ink900,
        contentColor = Brl.Paper0,
        action = data.visuals.actionLabel?.let { label ->
            {
                TextButton(onClick = data::performAction) {
                    Text(label, color = Brl.Vanila200, fontFamily = Urbanist, fontWeight = FontWeight.Bold)
                }
            }
        },
    ) {
        Text(data.visuals.message, fontFamily = Urbanist, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun brlTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Brl.Ink900,
    unfocusedBorderColor = Brl.Ink200,
    focusedLabelColor = Brl.Ink900,
    unfocusedLabelColor = Brl.Ink500,
    cursorColor = Brl.Ink900,
    focusedContainerColor = Brl.Paper0,
    unfocusedContainerColor = Brl.Paper0,
)

val TextFieldShape = RoundedCornerShape(16.dp)

/** The app mark: a braille "b" in a dark rounded square (the prototype's `.home-logo`). */
@Composable
fun AppLogo(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Brl.GradDark),
        contentAlignment = Alignment.Center,
    ) {
        BrailleCellView(
            dots = 0b000011,
            dotSize = size / 8,
            gap = size / 14,
            activeColor = Brl.Vanila200,
            inactiveColor = Brl.Ink600,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = BrlText.H3.copy(fontSize = 16.sp), color = Brl.Ink900, modifier = modifier.semantics { heading() })
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = BrlText.BodySmall, color = Brl.Ink500, modifier = Modifier.padding(end = 16.dp))
        Text(
            value,
            style = BrlText.BodySmall.copy(fontWeight = FontWeight.Bold),
            color = Brl.Ink900,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
