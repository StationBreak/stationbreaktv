// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The test card's geometry is the icon art's own numbers, in its 1024 x 576 units.

package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.BroadcastType
import kotlin.math.min

/** The card is drawn in the SVG's own 1024 x 576 units and scaled to the screen. */
private const val CARD_WIDTH = 1024f
private const val CARD_HEIGHT = 576f
private val CENTRE = Offset(512f, 288f)

private const val GRID_ALPHA = 0.18f
private const val RAY_ALPHA = 0.55f

private val RAY_ENDS =
    listOf(
        512f to 8f,
        512f to 568f,
        8f to 288f,
        1016f to 288f,
        232f to 8f,
        792f to 8f,
        232f to 568f,
        792f to 568f,
        394.58f to 8f,
        629.42f to 8f,
        394.58f to 568f,
        629.42f to 568f,
        8f to 76.65f,
        8f to 499.35f,
        1016f to 76.65f,
        1016f to 499.35f,
    )

/** The four wedges at the middle of each edge, as the icon draws them. */
private val WEDGES =
    listOf(
        listOf(500f to 14f, 524f to 14f, 516f to 70f, 508f to 70f),
        listOf(500f to 562f, 524f to 562f, 516f to 506f, 508f to 506f),
        listOf(14f to 276f, 14f to 300f, 70f to 292f, 70f to 284f),
        listOf(1010f to 276f, 1010f to 300f, 954f to 292f, 954f to 284f),
    )

private val TARGET_CENTRES = listOf(56f to 56f, 56f to 520f, 968f to 56f, 968f to 520f)

/** The ring labels: text, x, y in the rings' own frame (the icon's 512-unit square, set 256 across and 32 down). */
private val RING_LABELS =
    listOf(Triple("20", 300f, 92f), Triple("30", 300f, 124f), Triple("40", 212f, 430f), Triple("50", 212f, 398f))
private const val RING_FRAME_X = 256f
private const val RING_FRAME_Y = 32f
private const val RING_LABEL_SIZE = 13f
private const val DISC_RADIUS = 150f

/** How big one card unit is on this screen, for the text laid over the drawing. */
internal class CardScale(
    private val unit: Dp,
    private val density: Density,
) {
    fun dp(units: Float): Dp = unit * units

    fun sp(units: Float): TextUnit = with(density) { (unit * units).toSp() }
}

/**
 * The StationBreak test card (design: board E2), drawn from the app icon's art in `art/placeholder/tv-banner.svg`:
 * the grid, rays, rings, wedges, corner targets, the ring numbers, the centre disc and the orange TV. It is drawn with
 * the canvas, not from a bitmap, so it is sharp at any size and costs nothing to keep. [tvY] is where the TV sits, in
 * card units. [content] is laid over it for the card's words, with [CardScale] to place them.
 */
@Composable
internal fun TestCard(
    tvY: Float,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(CardScale) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().testTag(LiveTags.TEST_CARD)) {
        val fit = min(maxWidth.value / CARD_WIDTH, maxHeight.value / CARD_HEIGHT)
        Canvas(Modifier.fillMaxSize()) {
            drawRect(BroadcastColors.Ground)
            val scale = min(size.width / CARD_WIDTH, size.height / CARD_HEIGHT)
            translate((size.width - CARD_WIDTH * scale) / 2, (size.height - CARD_HEIGHT * scale) / 2) {
                scale(scale, scale, pivot = Offset.Zero) {
                    drawGrid()
                    drawRays()
                    drawRings()
                    drawWedgesAndTargets()
                    drawRingLabels(measurer)
                    drawDisc()
                    drawTv(tvY)
                }
            }
        }
        content(CardScale(1.dp * fit, density))
    }
}

private fun DrawScope.drawGrid() {
    val ink = BroadcastColors.Ink.copy(alpha = GRID_ALPHA)
    // The ray centre's own column and row are left out, as in the art.
    for (x in (64..960 step 64).filter { it != 512 }.map { it.toFloat() }) {
        drawLine(ink, Offset(x, 0f), Offset(x, CARD_HEIGHT), 1.5f)
    }
    for (y in (32..544 step 64).filter { it != 288 }.map { it.toFloat() }) {
        drawLine(ink, Offset(0f, y), Offset(CARD_WIDTH, y), 1.5f)
    }
}

private fun DrawScope.drawRays() {
    val ink = BroadcastColors.Ink.copy(alpha = RAY_ALPHA)
    RAY_ENDS.forEach { (x, y) -> drawLine(ink, CENTRE, Offset(x, y), 2f) }
}

private fun DrawScope.drawRings() {
    drawCircle(BroadcastColors.Ink, 236f, CENTRE, style = Stroke(4f))
    drawCircle(BroadcastColors.Ink, 210f, CENTRE, style = Stroke(2f))
    drawCircle(BroadcastColors.Ink, 186f, CENTRE, style = Stroke(6f))
}

private fun DrawScope.drawWedgesAndTargets() {
    WEDGES.forEach { points ->
        val path = Path()
        points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        drawPath(path, BroadcastColors.Ink)
    }
    TARGET_CENTRES.forEach { (x, y) ->
        drawCircle(BroadcastColors.Ink, 36f, Offset(x, y), style = Stroke(3f))
        drawCircle(BroadcastColors.Ink, 20f, Offset(x, y), style = Stroke(3f))
    }
}

private fun DrawScope.drawRingLabels(measurer: TextMeasurer) {
    // Drawn inside the card's scale, so the size is in card units: 13 of them, as pixels, made into sp for the style.
    val style =
        TextStyle(
            fontFamily = BroadcastType.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = RING_LABEL_SIZE.toSp(),
            color = BroadcastColors.Ink,
        )
    RING_LABELS.forEach { (text, x, y) ->
        val laid = measurer.measure(text, style)
        drawText(laid, topLeft = Offset(RING_FRAME_X + x - laid.size.width / 2f, RING_FRAME_Y + y - laid.firstBaseline))
    }
}

private fun DrawScope.drawDisc() {
    drawCircle(BroadcastColors.Ground, DISC_RADIUS, CENTRE)
    drawCircle(BroadcastColors.Ink, DISC_RADIUS, CENTRE, style = Stroke(5f))
}

/** The orange TV with its rabbit ears, centred on the card at height [y]. */
private fun DrawScope.drawTv(y: Float) {
    translate(CENTRE.x, y) {
        val ink = BroadcastColors.Ink
        drawLine(ink, Offset(0f, -6f), Offset(-18f, -24f), 4f, StrokeCap.Round)
        drawLine(ink, Offset(0f, -6f), Offset(18f, -24f), 4f, StrokeCap.Round)
        drawRoundRect(ink, Offset(-34f, -6f), Size(68f, 50f), CornerRadius(12f), Stroke(5f))
        drawRoundRect(BroadcastColors.Accent, Offset(-24f, 4f), Size(48f, 30f), CornerRadius(7f))
        drawRoundRect(ink, Offset(-9f, 11f), Size(6f, 16f), CornerRadius(2f))
        drawRoundRect(ink, Offset(3f, 11f), Size(6f, 16f), CornerRadius(2f))
    }
}
