// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.guideText
import kotlin.math.min

// The title block is 46 dp tall, and the logo that stands for the title is at most that tall and 70% of the panel
// wide, left-aligned, scaled to fit. In the Light theme it sits on a dark plate (#0E0F11 at 92%, 2 dp corners, 6 dp
// padding), the box including the plate, since logos are usually light and would be lost on the grey page.
private val LogoMaxHeight = 46.dp
private const val LOGO_MAX_WIDTH_FRACTION = 0.7f
private val LogoPlate = Color(0xFF0E0F11).copy(alpha = 0.92f)
private val LogoPlateRadius = 2.dp
private val LogoPlatePadding = 6.dp

/** The title's letter-spacing, in em: -0.015. */
private const val TITLE_TRACKING = -0.015f

/** How long the text title and the logo cross-fade when the logo arrives for the cell already on show. */
private const val LOGO_FADE_MS = 120

/**
 * The title block: 46 dp tall whether it holds the text title (24 sp) or the logo, plate included, with its content
 * left-aligned and centred vertically, so nothing below it moves when one takes the place of the other. [cell] names
 * the cell it is for: a logo that is there when the cell is first shown is simply there, and one that arrives later
 * fades in over [LOGO_FADE_MS] as the text fades out. The title stays the logo's name.
 */
@Composable
internal fun PanelTitle(
    cell: Any,
    title: String,
    logo: ImageBitmap?,
    colors: GuideColors,
) {
    val reveal = remember(cell) { Animatable(if (logo != null) 1f else 0f) }
    LaunchedEffect(cell, logo != null) {
        reveal.animateTo(if (logo != null) 1f else 0f, tween(LOGO_FADE_MS, easing = LinearEasing))
    }
    Box(Modifier.fillMaxWidth().height(LogoMaxHeight), contentAlignment = Alignment.CenterStart) {
        if (logo == null || reveal.value < 1f) {
            val named = if (logo == null) Modifier.testTag(GuideTags.PANEL_TITLE) else Modifier.clearAndSetSemantics {}
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - reveal.value }.then(named),
                style = guideText(24.sp, FontWeight.SemiBold).copy(letterSpacing = TITLE_TRACKING.em),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (logo != null) {
            LogoTitle(title, logo, plated = colors.markTint != null, Modifier.graphicsLayer { alpha = reveal.value })
        }
    }
}

/**
 * [logo] at its own size, scaled down to fit the 46 dp tall, 70% wide box (the plate, in Light, inside it), never up,
 * and left-aligned. The title stays as the node's description, since the picture says it only to the eye.
 */
@Composable
private fun LogoTitle(
    title: String,
    logo: ImageBitmap,
    plated: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val padding = if (plated) LogoPlatePadding else 0.dp
        val room = maxWidth * LOGO_MAX_WIDTH_FRACTION - padding * 2
        val tall = LogoMaxHeight - padding * 2
        val natural = with(density) { logo.width.toDp() to logo.height.toDp() }
        val scale = min(1f, min(room / natural.first, tall / natural.second))
        val shape = RoundedCornerShape(LogoPlateRadius)
        val plate = if (plated) Modifier.background(LogoPlate, shape).padding(padding) else Modifier
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .testTag(GuideTags.PANEL_TITLE)
                .semantics { contentDescription = title }
                .then(plate),
        ) {
            Image(
                bitmap = logo,
                contentDescription = null,
                modifier = Modifier.size(natural.first * scale, natural.second * scale).testTag(GuideTags.PANEL_LOGO),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
