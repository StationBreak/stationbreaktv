// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.live.PictureLabel
import tv.stationbreak.feature.live.PictureLead
import tv.stationbreak.feature.live.R
import java.util.Locale

/** The label's height: 32 dp across the top of the picture (#722). */
internal val PictureLabelHeight = 32.dp

private const val SCRIM_TOP_ALPHA = 0.88f
private val SidePadding = 10.dp
private val MarkWidth = 36.dp
private val MarkHeight = 20.dp
private val LabelSize = 11.sp

/**
 * What the picture in the guide's corner is (#722), along its top edge: the channel's mark and number, then the lead
 * and title ("Now playing: ..."), or the channel's name alone. A scrim, `page` fading from 88% to nothing downwards,
 * with no hard edge; one line, the title ellipsized. Always the Dark tokens, as it sits over video. The top edge is
 * clear of the film's own subtitles, which sit along the bottom.
 */
@Composable
internal fun PictureLabelBar(
    label: PictureLabel,
    modifier: Modifier = Modifier,
) {
    ChromeTheme {
        val colors = LocalGuideColors.current
        val top = colors.page.copy(alpha = SCRIM_TOP_ALPHA)
        val scrim = Brush.verticalGradient(listOf(top, top.copy(alpha = 0f)))
        Row(
            modifier
                .fillMaxWidth()
                .height(PictureLabelHeight)
                .semantics(mergeDescendants = true) {}
                .testTag(LiveTags.PICTURE_LABEL)
                .background(scrim)
                .padding(horizontal = SidePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelMarkBox(label.recipeKey, label.channelName, Modifier.size(MarkWidth, MarkHeight))
            Spacer(Modifier.width(SidePadding))
            Text(
                text = String.format(Locale.ROOT, "%02d", label.number),
                style = guideFigures(LabelSize, FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.width(SidePadding))
            val lead = label.lead?.let { stringResource(leadText(it)) }
            Text(
                text =
                    buildAnnotatedString {
                        if (lead != null && label.title != null) {
                            withStyle(SpanStyle(color = colors.textSecondary, fontWeight = FontWeight.Normal)) {
                                append(lead)
                                append(' ')
                            }
                            withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold)) {
                                append(label.title)
                            }
                        } else {
                            withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold)) {
                                append(label.channelName)
                            }
                        }
                    },
                modifier = Modifier.weight(1f),
                style = guideText(LabelSize, FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun leadText(lead: PictureLead) =
    when (lead) {
        PictureLead.NowPlaying -> R.string.live_picture_now_playing
        PictureLead.TuningIn -> R.string.live_picture_tuning_in
        PictureLead.UpNext -> R.string.live_picture_up_next
        PictureLead.FillingIn -> R.string.live_picture_filling_in
        PictureLead.SwitchingAudio -> R.string.live_picture_switching_audio
        PictureLead.SwitchingSubtitles -> R.string.live_picture_switching_subtitles
        PictureLead.SwitchingVersion -> R.string.live_picture_switching_version
    }
