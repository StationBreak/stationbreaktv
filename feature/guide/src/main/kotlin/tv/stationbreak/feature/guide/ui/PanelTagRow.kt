// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.feature.guide.PanelTag
import tv.stationbreak.feature.guide.PanelTagCopy
import tv.stationbreak.feature.guide.R

// A tag: 9 sp SemiBold text in a 1 dp outline with 2 dp corners and 4 dp either side, 13 dp tall, 5 dp apart.
private val TagHeight = 13.dp
private val TagRadius = 2.dp
private val TagPadding = 4.dp
private val TagGap = 5.dp
private val TagStroke = 1.dp

@Composable
internal fun tagCopy(): PanelTagCopy =
    PanelTagCopy(
        mono = stringResource(R.string.guide_tag_mono),
        stereo = stringResource(R.string.guide_tag_stereo),
        closedCaptions = stringResource(R.string.guide_tag_cc),
        critics = stringResource(R.string.guide_tag_critics),
        audience = stringResource(R.string.guide_tag_audience),
    )

/**
 * One row of outlined tags (#723): as many as fit the width, in order, the rest left out whole. Flat: no fills, no
 * icons. Each is its own node, so a screen reader reads the facts one by one.
 */
@Composable
internal fun TagRow(tags: List<PanelTag>) {
    Layout(
        content = {
            tags.forEachIndexed { index, tag -> Tag(tag, Modifier.testTag("${GuideTags.PANEL_TAG}$index")) }
        },
        modifier = Modifier.fillMaxWidth().testTag(GuideTags.PANEL_TAGS),
    ) { measurables, constraints ->
        val gap = TagGap.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        var x = 0
        val placed = mutableListOf<Pair<Placeable, Int>>()
        for (measurable in measurables) {
            val placeable = measurable.measure(loose)
            if (x + placeable.width > constraints.maxWidth) break
            placed += placeable to x
            x += placeable.width + gap
        }
        val height = placed.maxOfOrNull { it.first.height } ?: 0
        layout(constraints.constrainWidth(x - gap), height) {
            placed.forEach { (placeable, at) -> placeable.placeRelative(at, 0) }
        }
    }
}

@Composable
private fun Tag(
    tag: PanelTag,
    modifier: Modifier,
) {
    val colors = LocalGuideColors.current
    val label =
        buildAnnotatedString {
            when (tag) {
                is PanelTag.Plain -> {
                    append(tag.text)
                }

                is PanelTag.Score -> {
                    withStyle(SpanStyle(color = colors.textSecondary, fontWeight = FontWeight.Normal)) {
                        append(tag.label)
                        append(' ')
                    }
                    append(tag.value)
                }
            }
        }
    Box(
        modifier
            .height(TagHeight)
            .border(TagStroke, colors.keyCapStroke, RoundedCornerShape(TagRadius))
            .padding(horizontal = TagPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = guideFigures(TAG_SP.sp, FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
        )
    }
}

private const val TAG_SP = 9
