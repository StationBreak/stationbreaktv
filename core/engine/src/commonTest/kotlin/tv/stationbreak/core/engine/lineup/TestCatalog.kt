// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source

/** A small catalog for planner tests. */
internal class TestCatalog(
    override val recipes: List<Recipe>,
    override val version: Int = 1,
    override val leftovers: List<LeftoversRecipe> = TEST_LEFTOVERS,
    override val retiredNumbers: Set<Int> = emptySet(),
    override val retiredKeys: Set<String> = emptySet(),
) : StarterCatalogView

internal val TEST_LEFTOVERS: List<LeftoversRecipe> =
    listOf(
        LeftoversRecipe("kids-odds", "Kids Odds", "Synthetic.", 99, "PG"),
        LeftoversRecipe("odds", "Odds", "Synthetic.", 199, null),
    )

internal val SERVER: CanonicalId = idOf(0x5E)
internal val USER: CanonicalId = idOf(0x5F)
internal val NO_NAMES: CollectionNames = CollectionNames(emptyMap())

/** Each fixture item runs 30 minutes, so this many items make exactly the qualifying 12 hours. */
internal const val QUALIFYING_ITEMS = 24

/** A recipe whose one source is the genre named [key], in the band its [number] is in. Kids recipes get a G ceiling. */
internal fun recipeOf(
    key: String,
    number: Int,
    optIn: Boolean = false,
    hints: List<String> = emptyList(),
    sources: List<Source> = listOf(Source.Genre(key)),
): Recipe {
    val band = Band.entries.first { number in it.numbers }
    return Recipe(
        key = key,
        name = "Channel $key",
        personality = "Synthetic.",
        band = band,
        number = number,
        optIn = optIn,
        sources = sources,
        exclusions = emptyList(),
        maxRating = if (band == Band.Kids) "G" else null,
        ordering = null,
        collectionHints = hints,
    )
}

/**
 * A library holding, for each genre in [fills], that many 30-minute G-rated movies of that genre, in canonical-ID
 * order. [collections] are added as they are.
 */
internal fun libraryOf(
    fills: Map<String, Int>,
    collectionsSynced: Boolean = true,
    collections: List<RuleCollection> = emptyList(),
    alsoKids: Set<String> = setOf("tots"),
): RuleSnapshot {
    var next = 0x10000
    val items =
        fills.entries.sortedBy { it.key }.flatMap { (genre, count) ->
            List(count) { movie(idOf(next++), rating = "G", genres = if (genre in alsoKids) listOf(genre, "Kids") else listOf(genre)) }
        }
    return snapshot(items = items, collections = collections, collectionsSynced = collectionsSynced)
}

/** Runs the planner with the fixture server and user. */
@Suppress("LongParameterList")
internal fun planOf(
    current: Lineup?,
    snapshot: RuleSnapshot,
    catalog: StarterCatalogView,
    nowMs: Long = 1_000L,
    offset: Int = 0,
    names: CollectionNames = NO_NAMES,
): Lineup = LineupPlanner.plan(current, SERVER, USER, offset, snapshot, names, nowMs, catalog)

internal fun Lineup.channel(key: String): LineupChannel =
    channels.first { it.id == LineupIds.starter(key) || it.id == LineupIds.leftovers(key) }

/** The recipe keys of the switched-on channels. */
internal fun Lineup.onKeys(): Set<String> {
    val on = channels.filter { it.state == ChannelState.On }
    return on.map { it.id.value.substringAfter(':') }.toSet()
}

internal fun Lineup.withChannel(
    key: String,
    change: (LineupChannel) -> LineupChannel,
): Lineup = copy(channels = channels.map { if (it == channel(key)) change(it) else it })

internal val LineupChannel.starter: ChannelSource.Starter get() = source as ChannelSource.Starter
