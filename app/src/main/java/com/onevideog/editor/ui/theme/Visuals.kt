package com.onevideog.editor.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * v0.9.2 shared visual vocabulary — the "glazed orange on graphite metal"
 * identity. Everything here is cheap by design for weak GPUs:
 * gradients, hairline edges, tinted shadows and graphicsLayer animations
 * only. No RenderEffects, no blurs, no shaders.
 */

/** Glazed metallic orange gradient (light source at the top). */
val OvgOrangeBrush: Brush = Brush.verticalGradient(
    0.0f to OrangeHi,
    0.55f to OrangeMid,
    1.0f to OrangeLo,
)

/** Graphite metal surface gradient (dark theme). */
fun ovgGraphiteBrush(base: Color, raised: Color): Brush =
    Brush.verticalGradient(0.0f to raised, 1.0f to base)

/**
 * Thin lit edge: a hairline light stroke along the top of the shape fading
 * toward the bottom — the "light catching the metal edge" 2.5D cue.
 * Applied as a gradient border (foundation border API); place it after
 * clip/background so the outer half of the stroke crops into a crisp line.
 */
fun Modifier.litEdge(
    shape: Shape,
    color: Color = Color.White,
    topAlpha: Float = 0.30f,
    bottomAlpha: Float = 0.04f,
    width: Dp = 1.dp,
): Modifier = composed {
    border(
        width = width,
        brush = Brush.verticalGradient(
            0.0f to color.copy(alpha = topAlpha),
            0.45f to color.copy(alpha = bottomAlpha),
            1.0f to color.copy(alpha = bottomAlpha),
        ),
        shape = shape,
    )
}

/**
 * Soft colored glow — a tinted shadow (spot + ambient) as the only depth
 * cue. Below API 28 the platform ignores the tint and draws a plain shadow.
 */
fun Modifier.softGlow(
    elevation: Dp = 10.dp,
    shape: Shape,
    tint: Color = OrangeMid,
): Modifier = composed {
    shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = tint,
        spotColor = tint,
    )
}

/**
 * Touch feedback: scales down slightly while pressed (spring settle back).
 * One tiny recomposition per press event; motion runs in the draw phase.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Ripple-free click that still feeds the given interaction source. */
fun Modifier.clickableNoRipple(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = composed {
    clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

/** Convenience text color on glazed orange surfaces. */
val OnOrange: Color = Color(0xFF1A0E02)

/**
 * Primary action button — glazed metallic orange, lit edge, soft orange
 * glow, springy press feedback. The content is an icon/text row.
 * v0.9.3: `enabled` support — a disabled button dims to half alpha and
 * swallows clicks (the export screen gates the start action on it).
 */
@Composable
fun GlazedOrangeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    elevation: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .pressScale(interactionSource)
            .softGlow(elevation, shape, OrangeLo)
            .clip(shape)
            .background(OvgOrangeBrush)
            .litEdge(shape, topAlpha = 0.45f, bottomAlpha = 0.06f)
            .clickableNoRipple(interactionSource) { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(contentPadding),
        ) { content() }
    }
}

/** Quiet secondary button — graphite metal with a light lit edge. */
@Composable
fun GraphiteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val raised = MaterialTheme.colorScheme.surfaceVariant
    val base = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .pressScale(interactionSource)
            .softGlow(6.dp, shape, Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(0.0f to raised, 1.0f to base))
            .litEdge(shape, topAlpha = 0.22f, bottomAlpha = 0.04f)
            .clickableNoRipple(interactionSource) { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(contentPadding),
        ) { content() }
    }
}

/** Metallic graphite surface card (dark 2.5D raised panel). */
@Composable
fun GraphiteCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    raised: Boolean = true,
    content: @Composable () -> Unit,
) {
    val top = if (raised) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val bottom = if (raised) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceDim
    Box(
        modifier = modifier
            .softGlow(8.dp, shape, Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(0.0f to top, 1.0f to bottom))
            .litEdge(shape),
    ) {
        content()
    }
}

/**
 * Brand wordmark animation — the letters "O" and "G" enter low and launch up
 * with a soft spring, one after the other, glazed-orange gradient on the
 * glyphs themselves. v0.9.3: the background is TRANSPARENT — the old radial
 * halo behind the row was clipped by the row bounds and read as a brown
 * rectangle behind the letters (device report). All motion lives in
 * graphicsLayer blocks; after the entrance the letters never recompose.
 */
@Composable
fun OgWordmarkAnimation(
    fontSize: TextUnit = 64.sp,
    modifier: Modifier = Modifier,
    entranceDelayMs: Long = 60,
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }

    var oStarted by remember { mutableStateOf(false) }
    var gStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(entranceDelayMs)
        oStarted = true
        delay(130)
        gStarted = true
    }
    val oProgress by animateFloatAsState(
        targetValue = if (oStarted) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "ogO",
    )
    val gProgress by animateFloatAsState(
        targetValue = if (gStarted) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "ogG",
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WordmarkLetter("O", oProgress, fontSize, fontSizePx)
        WordmarkLetter("G", gProgress, fontSize, fontSizePx)
    }
}

@Composable
private fun WordmarkLetter(letter: String, progress: Float, fontSize: TextUnit, fontSizePx: Float) {
    Text(
        text = letter,
        style = MaterialTheme.typography.displaySmall.copy(
            fontWeight = FontWeight.Black,
            fontSize = fontSize,
            brush = OvgOrangeBrush,
        ),
        modifier = Modifier.graphicsLayer {
            // Enter low, launch up, settle — all in the draw phase.
            translationY = (1f - progress) * fontSizePx * 0.55f
            alpha = progress.coerceIn(0f, 1f)
            val s = 0.82f + 0.18f * progress
            scaleX = s
            scaleY = s
        },
    )
}
