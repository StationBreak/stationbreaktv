// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import kotlin.time.Instant
import tv.stationbreak.core.engine.lineup.ShelfReason as EngineReason

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

internal fun LineupChannel.summary(
    catalog: StarterCatalogView,
    playKey: PlayKey,
    nowMs: Long,
) = ChannelSummary(
    id = id,
    number = number,
    name = displayName(catalog),
    state = shownState(nowMs).domain,
    kind = source.kind,
    onSince = Instant.fromEpochMilliseconds(onSinceMs),
    defaultNumber = defaultNumber,
    kid = KidStatus.isKidChannel(this),
    optIn = isOptIn(catalog),
    playKey = playKey,
    shelfReason = shownShelfReason(nowMs),
)

// The catalog recipe key of a starter or leftovers channel; a custom or unusable one has none. No else, so a new source
// decides.
internal fun ChannelSource.recipeKey(): String? =
    when (this) {
        is ChannelSource.Starter -> recipeKey

        is ChannelSource.Leftovers -> recipeKey

        is ChannelSource.Custom -> null

        // An unreadable channel is not shown as a healthy built-in, so it has no key even when its id belongs to a
        // catalog channel (its name still comes from the catalog).
        is ChannelSource.Unusable -> null
    }

// A shelved channel's reason, or the one the planner stored when it set a leave that is yet to come: a channel on the
// air today that goes at the next day start. A channel that starts at the next day start has none.
private fun LineupChannel.shownShelfReason(nowMs: Long): ShelfReason? {
    // On with a leave set: it leaves at the next day start, or it has left already (and is shown on the shelf).
    val leaving = state == ChannelState.On && leavesAtMs != null
    return if (shownState(nowMs) == ChannelState.Shelf || leaving) {
        shelfReason?.let {
            when (it) {
                EngineReason.Empty -> ShelfReason.Empty
                EngineReason.TooThin -> ShelfReason.TooThin
                EngineReason.BelowCap -> ShelfReason.BelowCap
                EngineReason.OptIn -> ShelfReason.OptIn
            }
        }
    } else {
        null
    }
}

/** The state a viewer sees at [nowMs]: a channel whose leave time has come is on the shelf, as `rolled` makes it. */
private fun LineupChannel.shownState(nowMs: Long): ChannelState =
    if (state == ChannelState.On && leavesAtMs.let { it != null && it <= nowMs }) ChannelState.Shelf else state

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
            is ChannelSource.Unusable -> unusableKind(rawJson)
        }

private fun unusableKind(rawJson: String): ChannelKind =
    if (kindOf(rawJson) == CUSTOM_KIND) ChannelKind.UnusableCustom else ChannelKind.Unusable

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
