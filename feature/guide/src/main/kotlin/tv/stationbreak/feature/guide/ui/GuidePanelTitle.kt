// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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

// The logo that stands for the title: at most 46 dp tall and 70% of the panel wide, left-aligned, scaled to fit. In
// the Light theme it sits on a dark plate (#0E0F11 at 92%, 2 dp corners, 6 dp padding), the box including the plate,
// since logos are usually light and would be lost on the grey page.
private val LogoMaxHeight = 46.dp
private const val LOGO_MAX_WIDTH_FRACTION = 0.7f
private val LogoPlate = Color(0xFF0E0F11).copy(alpha = 0.92f)
private val LogoPlateRadius = 2.dp
private val LogoPlatePadding = 6.dp

/** The title's letter-spacing, in em: -0.015. */
private const val TITLE_TRACKING = -0.015f

/** The title in 24 sp, or, when the logo is in hand in time, the logo in its place, with the title as its name. */
@Composable
internal fun PanelTitle(
    title: String,
    logo: ImageBitmap?,
    colors: GuideColors,
) {
    if (logo == null) {
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth().testTag(GuideTags.PANEL_TITLE),
            style = guideText(24.sp, FontWeight.SemiBold).copy(letterSpacing = TITLE_TRACKING.em),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        LogoTitle(title, logo, plated = colors.markTint != null)
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
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val padding = if (plated) LogoPlatePadding else 0.dp
        val room = maxWidth * LOGO_MAX_WIDTH_FRACTION - padding * 2
        val tall = LogoMaxHeight - padding * 2
        val natural = with(density) { logo.width.toDp() to logo.height.toDp() }
        val scale = min(1f, min(room / natural.first, tall / natural.second))
        val shape = RoundedCornerShape(LogoPlateRadius)
        val plate = if (plated) Modifier.background(LogoPlate, shape).padding(padding) else Modifier
        Box(Modifier.testTag(GuideTags.PANEL_TITLE).semantics { contentDescription = title }.then(plate)) {
            Image(
                bitmap = logo,
                contentDescription = null,
                modifier = Modifier.size(natural.first * scale, natural.second * scale).testTag(GuideTags.PANEL_LOGO),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
