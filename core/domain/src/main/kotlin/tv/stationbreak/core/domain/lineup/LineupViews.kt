// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import kotlin.time.Instant

// How engine lineup types are shown to features, which never see them (spec 12).

/**
 * The name a channel is shown with: the user's, else its rules' name, else the catalog's, else its key or ID. An
 * unusable channel takes the catalog's name when its ID is a catalog channel's.
 */
internal fun LineupChannel.displayName(catalog: StarterCatalogView): String =
    nameOverride ?: when (val source = source) {
        is ChannelSource.Starter -> source.rules.name ?: recipeName(catalog, source.recipeKey) ?: source.recipeKey
        is ChannelSource.Custom -> source.rules.name ?: id.value
        is ChannelSource.Leftovers -> leftoversName(catalog, source.recipeKey) ?: source.recipeKey
        is ChannelSource.Unusable -> catalogName(catalog, this) ?: id.value
    }

internal fun LineupChannel.summary(catalog: StarterCatalogView) =
    ChannelSummary(
        id = id,
        number = number,
        name = displayName(catalog),
        state = state.domain,
        kind = source.kind,
        onSince = Instant.fromEpochMilliseconds(onSinceMs),
        defaultNumber = defaultNumber,
        kid = KidStatus.isKidChannel(this),
        optIn = isOptIn(catalog),
    )

private fun LineupChannel.isOptIn(catalog: StarterCatalogView): Boolean {
    val starter = source as? ChannelSource.Starter ?: return false
    return catalog.recipes.any { it.key == starter.recipeKey && it.optIn }
}

/** Number order, channels without a number last, then ID: the order features list channels in. */
internal val CHANNEL_ORDER: Comparator<LineupChannel> =
    compareBy<LineupChannel, Int?>(nullsLast()) { it.number }.thenBy { it.id.value }

private val ChannelState.domain: ChannelStatus
    get() =
        when (this) {
            ChannelState.On -> ChannelStatus.On
            ChannelState.Shelf -> ChannelStatus.Shelf
            ChannelState.UserHidden -> ChannelStatus.UserHidden
        }

private val ChannelSource.kind: ChannelKind
    get() =
        when (this) {
            is ChannelSource.Starter -> ChannelKind.Starter
            is ChannelSource.Leftovers -> ChannelKind.Leftovers
            is ChannelSource.Custom -> ChannelKind.Custom
            is ChannelSource.Unusable -> ChannelKind.Unusable
        }

private fun recipeName(
    catalog: StarterCatalogView,
    key: String,
): String? = catalog.recipes.firstOrNull { it.key == key }?.name

private fun leftoversName(
    catalog: StarterCatalogView,
    key: String,
): String? = catalog.leftovers.firstOrNull { it.key == key }?.name

private fun catalogName(
    catalog: StarterCatalogView,
    channel: LineupChannel,
): String? =
    catalog.recipes.firstOrNull { LineupIds.starter(it.key) == channel.id }?.name
        ?: catalog.leftovers.firstOrNull { LineupIds.leftovers(it.key) == channel.id }?.name
