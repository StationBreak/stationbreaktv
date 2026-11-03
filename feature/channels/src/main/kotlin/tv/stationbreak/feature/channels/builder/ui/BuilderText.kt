// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.domain.lineup.builder.PreviewItem
import tv.stationbreak.core.domain.lineup.builder.SkipReason
import tv.stationbreak.core.domain.lineup.builder.SkippedPick
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderStep
import tv.stationbreak.feature.channels.builder.SourceKindTab

// The words of the builder. The ViewModel holds structured values; every line of text is made here.

@Composable
internal fun stepName(step: BuilderStep): String =
    stringResource(
        when (step) {
            BuilderStep.Sources -> R.string.builder_step_sources
            BuilderStep.Play -> R.string.builder_step_play
            BuilderStep.Name -> R.string.builder_step_name
        },
    )

@Composable
internal fun tabName(tab: SourceKindTab): String =
    stringResource(
        when (tab) {
            SourceKindTab.Libraries -> R.string.builder_source_libraries
            SourceKindTab.Collections -> R.string.builder_source_collections
            SourceKindTab.Tags -> R.string.builder_source_tags
            SourceKindTab.Genres -> R.string.builder_source_genres
            SourceKindTab.Pick -> R.string.builder_source_pick
        },
    )

@Composable
internal fun orderName(order: PlayOrder): String =
    stringResource(
        when (order) {
            PlayOrder.Shuffle -> R.string.builder_order_shuffle
            PlayOrder.Rotation -> R.string.builder_order_rotation
            PlayOrder.InOrder -> R.string.builder_order_in_order
        },
    )

@Composable
internal fun orderHint(order: PlayOrder): String =
    stringResource(
        when (order) {
            PlayOrder.Shuffle -> R.string.builder_order_shuffle_hint
            PlayOrder.Rotation -> R.string.builder_order_rotation_hint
            PlayOrder.InOrder -> R.string.builder_order_in_order_hint
        },
    )

@Composable
internal fun onOff(on: Boolean): String = stringResource(if (on) R.string.builder_on else R.string.builder_off)

/** A cap as the rows show it: the number, or "No limit" while a kept uncapped Shuffle is in force. */
@Composable
internal fun capText(cap: Int?): String = cap?.toString() ?: stringResource(R.string.builder_cap_no_limit)

/** "Kept from Name", or a general line when the built-in channel the settings came from is not known. */
@Composable
internal fun keptFromText(from: String): String =
    if (from.isBlank()) {
        stringResource(R.string.builder_kept_from_copied)
    } else {
        stringResource(R.string.builder_kept_from, from)
    }

@Composable
internal fun keptText(item: KeptItem): String =
    when (item) {
        is KeptItem.Order -> {
            stringResource(
                if (item.mode == KeptOrder.Chronological) {
                    R.string.builder_kept_order_chronological
                } else {
                    R.string.builder_kept_order_random_in_order
                },
            )
        }

        is KeptItem.YearsSource -> {
            stringResource(R.string.builder_kept_years, item.from, item.to)
        }

        is KeptItem.BlockLength -> {
            stringResource(R.string.builder_kept_block_length, item.n)
        }

        KeptItem.NoCaps -> {
            stringResource(R.string.builder_kept_no_caps)
        }

        is KeptItem.EpochHours -> {
            stringResource(R.string.builder_kept_epoch_hours, item.hours)
        }

        KeptItem.BackToBackOff -> {
            stringResource(R.string.builder_kept_back_to_back_off)
        }

        KeptItem.Filler -> {
            stringResource(R.string.builder_kept_filler)
        }

        KeptItem.OtherSettings -> {
            stringResource(R.string.builder_kept_other)
        }
    }

/** "Show A S1E3 (22 min)" for an episode, "Film (95 min)" for anything else: built here from the structured item. */
@Composable
internal fun previewItemText(item: PreviewItem): String {
    val series = item.seriesName
    val season = item.season
    val episode = item.episode
    return if (series != null && season != null && episode != null) {
        stringResource(R.string.builder_preview_item_episode, series, season, episode, item.minutes)
    } else {
        stringResource(R.string.builder_preview_item, item.name, item.minutes)
    }
}

@Composable
internal fun skippedText(skipped: List<SkippedPick>): String {
    val picks =
        skipped.map {
            val own = it.reason == SkipReason.NotMadeForKids
            val why = stringResource(if (own) R.string.builder_skip_not_kids else R.string.builder_skip_rating)
            stringResource(R.string.builder_skipped_pick, it.label, why)
        }
    val shown = joined(picks.take(SKIPPED_SHOWN), R.string.builder_join)
    val more = picks.size - SKIPPED_SHOWN
    val list = if (more > 0) stringResource(R.string.builder_skipped_more, shown, more) else shown
    return stringResource(R.string.builder_kids_skipped, list)
}

private const val SKIPPED_SHOWN = 2

/** "label: value", worded by one resource so the design partner can reorder it. */
@Composable
internal fun labelled(
    label: String,
    value: String,
): String = stringResource(R.string.builder_labelled, label, value)

/** [items] joined by the separator resource [join] (which takes the text so far, then the next). */
@Composable
internal fun joined(
    items: List<String>,
    join: Int,
): String = items.reduceOrNull { so, next -> stringResource(join, so, next) }.orEmpty()
