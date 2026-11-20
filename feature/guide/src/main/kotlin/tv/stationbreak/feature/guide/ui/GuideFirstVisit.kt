// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.LocalCovered
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.guide.R

/**
 * The first visit's note (#94), in the info panel's place: the title in the panel's title slot and the body beneath.
 * Not a focus stop. One polite live region, so a screen reader says it once, when it appears; keyed on [LocalCovered],
 * so a note that appeared under a cover (setup's cheer, held over the first visit) is a new region, said once, when the
 * cover lifts.
 */
@Composable
internal fun FirstVisitNote(modifier: Modifier) {
    val colors = LocalGuideColors.current
    key(LocalCovered.current) {
        Column(
            modifier
                .clipToBounds()
                .testTag(GuideTags.FIRST_VISIT)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            PanelTitle(GuideTags.FIRST_VISIT, stringResource(R.string.first_visit_title), null, colors)
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.first_visit_body),
                modifier = Modifier.testTag(GuideTags.FIRST_VISIT_BODY),
                style = guideText(14.sp, FontWeight.Normal).copy(lineHeight = 19.sp),
                color = colors.textPrimary,
            )
        }
    }
}

/** A fresh press dismisses the note; a held key's repeats (a held OK from onboarding, say) do not. */
internal fun dismissesFirstVisitNote(
    type: KeyEventType,
    repeatCount: Int,
): Boolean = type == KeyEventType.KeyDown && repeatCount == 0
