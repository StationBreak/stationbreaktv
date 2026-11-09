// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.stationbreak.core.domain.lineup.ChannelProperties
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.MinuteWindow
import tv.stationbreak.core.domain.lineup.builder.Narrowing
import tv.stationbreak.core.domain.lineup.builder.OnlyKind
import tv.stationbreak.core.domain.lineup.builder.ShownOrder
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.ui.joined
import tv.stationbreak.feature.channels.builder.ui.orderName

// The words of the properties sheet's two blocks, as label and value pairs. The domain holds structured values
// (RulesDescription, the one the builder's edit sheet words too); every line of text is made here.

/** A row with a label and its value; an empty one is never made. */
internal typealias PropertyRow = Pair<String, String>

/** "What it plays": sources by kind, the theme's anchors, what is left out, and what narrows the rest. */
@Composable
internal fun playRows(properties: ChannelProperties): List<PropertyRow> {
    val description = properties.description
    val sources = description?.sources.orEmpty()
    val kept = description?.kept.orEmpty()
    val years = kept.filterIsInstance<KeptItem.YearsSource>()
    val labelled =
        listOf(
            R.string.channel_props_row_genres to sources.filterIsInstance<DraftSource.Genre>().map { it.label },
            R.string.channel_props_row_tags to sources.filterIsInstance<DraftSource.Tag>().map { it.label },
            R.string.channel_props_row_collections to
                sources.filterIsInstance<DraftSource.Collection>().map { it.label },
            R.string.channel_props_row_shows to sources.filterIsInstance<DraftSource.Show>().map { it.label },
            R.string.channel_props_row_titles to sources.filterIsInstance<DraftSource.Pick>().map { it.label },
            R.string.channel_props_row_libraries to sources.filterIsInstance<DraftSource.Library>().map { it.label },
            R.string.channel_props_row_studios to kept.filterIsInstance<KeptItem.StudioSource>().map { it.name },
            R.string.channel_props_row_years to
                years.map { stringResource(R.string.channel_props_years_range, it.from, it.to) },
            R.string.channel_props_row_always to properties.anchors.map { it.name },
            R.string.channel_props_row_never to neverItems(properties),
            R.string.channel_props_row_narrowed to narrowedItems(description?.narrowing),
        )
    return labelled.mapNotNull { (label, items) ->
        items.takeIf { it.isNotEmpty() }?.let { stringResource(label) to joined(it, R.string.builder_join) }
    }
}

// What is left out, then the ceiling in words; a leftovers channel has only its ceiling.
@Composable
private fun neverItems(properties: ChannelProperties): List<String> {
    val description = properties.description
    val left = description?.leaveOut.orEmpty().map { it.label }
    val studios =
        description
            ?.kept
            .orEmpty()
            .filterIsInstance<KeptItem.StudioExclusion>()
            .map { it.name }
    val ceiling = description?.ceiling ?: properties.leftoversCeiling
    return left + studios + listOfNotNull(ceiling?.let { stringResource(R.string.channel_props_never_rating, it) })
}

@Composable
private fun narrowedItems(narrowing: Narrowing?): List<String> {
    if (narrowing == null) return emptyList()
    val only =
        when (narrowing.only) {
            OnlyKind.Films -> stringResource(R.string.channel_props_narrow_films)
            OnlyKind.Shows -> stringResource(R.string.channel_props_narrow_shows)
            null -> null
        }
    return listOfNotNull(
        only,
        narrowing.genres
            .takeIf {
                it.isNotEmpty()
            }?.let { stringResource(R.string.channel_props_narrow_genres, joined(it, R.string.builder_join)) },
        narrowing.notGenres
            .takeIf {
                it.isNotEmpty()
            }?.let { stringResource(R.string.channel_props_narrow_not_genres, joined(it, R.string.builder_join)) },
        narrowing.tags.takeIf { it.isNotEmpty() }?.let {
            stringResource(
                R.string.channel_props_narrow_tags,
                joined(it, R.string.builder_join),
            )
        },
        narrowing.studios
            .takeIf {
                it.isNotEmpty()
            }?.let { stringResource(R.string.channel_props_narrow_studios, joined(it, R.string.builder_join)) },
        yearsText(narrowing.yearFrom, narrowing.yearTo),
        narrowing.runtime?.let { windowText(it) },
        narrowing.episodeRuntime?.let { stringResource(R.string.channel_props_narrow_episodes, windowText(it)) },
        narrowing.movieRuntime?.let { stringResource(R.string.channel_props_narrow_movies, windowText(it)) },
    )
}

@Composable
private fun yearsText(
    from: Int?,
    to: Int?,
): String? = if (from == null || to == null) null else stringResource(R.string.channel_props_narrow_years, from, to)

@Composable
private fun windowText(window: MinuteWindow): String {
    val min = window.min
    val max = window.max
    return when {
        min != null && max != null -> stringResource(R.string.channel_props_minutes_between, min, max)
        max != null -> stringResource(R.string.channel_props_minutes_up_to, max)
        min != null -> stringResource(R.string.channel_props_minutes_at_least, min)
        else -> ""
    }
}

/** "How it runs": the order with the day's start, and the block length when there is one. The code is its own row. */
@Composable
internal fun runRows(properties: ChannelProperties): List<PropertyRow> {
    val timing = properties.timing ?: return emptyList()
    val order = properties.description?.order?.let { orderText(it) }
    val newDay = stringResource(R.string.channel_props_new_day, timing.dayStart)
    val orderValue = listOfNotNull(order, newDay).joinToString("  ·  ")
    return listOfNotNull(
        stringResource(R.string.channel_props_row_order) to orderValue,
        timing.blockLength?.let {
            stringResource(
                R.string.channel_props_row_block,
            ) to stringResource(R.string.builder_kept_block_length, it)
        },
    )
}

@Composable
private fun orderText(order: ShownOrder): String =
    when (order) {
        is ShownOrder.Play -> {
            orderName(order.order)
        }

        is ShownOrder.Kept -> {
            stringResource(
                when (order.mode) {
                    KeptOrder.Chronological -> R.string.channel_props_order_chronological
                    KeptOrder.RandomInOrder -> R.string.channel_props_order_random_in_order
                    KeptOrder.Curated -> R.string.channel_props_order_programmed
                },
            )
        }
    }
