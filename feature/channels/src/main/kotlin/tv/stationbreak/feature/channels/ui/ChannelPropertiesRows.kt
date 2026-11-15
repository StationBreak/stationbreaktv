// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.stationbreak.core.domain.lineup.ChannelProperties
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.MinuteWindow
import tv.stationbreak.core.domain.lineup.builder.Narrowing
import tv.stationbreak.core.domain.lineup.builder.OnlyKind
import tv.stationbreak.core.domain.lineup.builder.PlaysItem
import tv.stationbreak.core.domain.lineup.builder.PlaysKind
import tv.stationbreak.core.domain.lineup.builder.PlaysRow
import tv.stationbreak.core.domain.lineup.builder.ShownOrder
import tv.stationbreak.core.domain.lineup.builder.playsRowsOf
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.ui.joined
import tv.stationbreak.feature.channels.builder.ui.orderName
import tv.stationbreak.core.ui.R as UiR

// The words of the properties sheet's two blocks, as label and value pairs. The domain holds structured values
// (RulesDescription, the one the builder's edit sheet words too); every line of text is made here.

/** A row with a label and its value; an empty one is never made. */
internal typealias PropertyRow = Pair<String, String>

/**
 * "What it plays": the rows core:domain lists ([playsRowsOf], the ones the guide's channel box words too: sources by
 * kind, the theme's anchors, what it never plays), then what narrows the rest.
 */
@Composable
internal fun playRows(properties: ChannelProperties): List<PropertyRow> {
    val rows = playsRowsOf(properties.description, properties.anchors.map { it.name }, properties.leftoversCeiling)
    val words = rows.map { row -> playsLabel(row.kind) to playsValue(row) }
    val narrowed =
        narrowedItems(properties.description?.narrowing).takeIf { it.isNotEmpty() }?.let {
            stringResource(R.string.channel_props_row_narrowed) to listed(it)
        }
    return words + listOfNotNull(narrowed)
}

/** The sheet's label of a row of what a channel plays: core:ui's, the box's too. */
@Composable
internal fun playsLabel(kind: PlaysKind): String =
    stringResource(
        when (kind) {
            PlaysKind.Genres -> UiR.string.channel_plays_genres
            PlaysKind.Tags -> UiR.string.channel_plays_tags
            PlaysKind.Collections -> UiR.string.channel_plays_collections
            PlaysKind.Shows -> UiR.string.channel_plays_shows
            PlaysKind.Titles -> UiR.string.channel_plays_titles
            PlaysKind.Libraries -> UiR.string.channel_plays_libraries
            PlaysKind.Studios -> UiR.string.channel_plays_studios
            PlaysKind.Years -> UiR.string.channel_plays_years
            PlaysKind.Always -> UiR.string.channel_plays_always
            PlaysKind.Never -> UiR.string.channel_plays_never
        },
    )

// Items joined in the words the box joins them in.
@Composable
private fun listed(items: List<String>): String = joined(items, UiR.string.channel_plays_join)

@Composable
private fun playsValue(row: PlaysRow): String =
    joined(
        row.items.map {
            when (it) {
                is PlaysItem.Name -> it.text
                is PlaysItem.Years -> stringResource(UiR.string.channel_plays_years_range, it.from, it.to)
                is PlaysItem.RatedAbove -> stringResource(UiR.string.channel_plays_never_rating, it.rating)
            }
        },
        UiR.string.channel_plays_join,
    )

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
            }?.let { stringResource(R.string.channel_props_narrow_genres, listed(it)) },
        narrowing.notGenres
            .takeIf {
                it.isNotEmpty()
            }?.let { stringResource(R.string.channel_props_narrow_not_genres, listed(it)) },
        narrowing.tags.takeIf { it.isNotEmpty() }?.let {
            stringResource(
                R.string.channel_props_narrow_tags,
                listed(it),
            )
        },
        narrowing.studios
            .takeIf {
                it.isNotEmpty()
            }?.let { stringResource(R.string.channel_props_narrow_studios, listed(it)) },
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
