// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.stationbreak.core.domain.lineup.FamilyTier
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.ui.CapRow
import tv.stationbreak.feature.channels.R

/** The cap's name as the Cap row words it: Tots, Kids or Teens. */
@Composable
internal fun capName(tier: FamilyTier): String =
    stringResource(
        when (tier) {
            FamilyTier.Tots -> R.string.builder_cap_tots
            FamilyTier.Kids -> R.string.builder_cap_kids
            FamilyTier.Teens -> R.string.builder_cap_teens
        },
    )

/** A family channel's name in the summary: "Tots channel", "Kids channel" or "Teens channel". */
@Composable
internal fun tierChannelName(tier: FamilyTier): String =
    stringResource(
        when (tier) {
            FamilyTier.Tots -> R.string.builder_kids
            FamilyTier.Kids -> R.string.builder_tier_kids
            FamilyTier.Teens -> R.string.builder_tier_teens
        },
    )

/**
 * The Cap row of a draft: Tots, Kids and Teens side by side with the draft's tier filled. With no tier (Family channel
 * is off) it is dimmed, nothing is filled, and the focus order skips it, so it is never [focused].
 */
@Composable
internal fun CapLine(
    draft: ChannelDraft,
    focused: Boolean,
    tag: String,
) {
    val tier = draft.tier
    CapRow(
        label = stringResource(R.string.builder_cap),
        options = FamilyTier.entries.map { capName(it) },
        selected = tier?.ordinal,
        focused = focused,
        enabled = tier != null,
        tag = tag,
    )
}
