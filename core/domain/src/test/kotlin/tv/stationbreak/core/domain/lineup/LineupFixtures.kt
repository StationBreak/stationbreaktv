// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LeftoversRecipe
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import kotlin.time.Instant

// Synthetic fixtures only: made-up IDs, genres and names, nothing from a real library.

internal val SERVER: CanonicalId = CanonicalId.parse("a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0")
internal val USER: CanonicalId = CanonicalId.parse("b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0")
internal val ACCOUNT = LibraryAccount(ServerId(SERVER.value), UserId(USER.value))
internal val LIBRARY_ID: CanonicalId = CanonicalId.parse("c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0")
internal val LIBRARIES: Set<ItemId> = setOf(ItemId(LIBRARY_ID.value))

/** 2026-01-01T00:00:00Z: every fixture item was added then, long before any cut-off the tests use. */
internal const val ADDED_MS = 1_767_225_600_000L

/** 2026-10-19T12:00:00Z. */
internal val NOON: Instant = Instant.parse("2026-10-19T12:00:00Z")

internal const val TWO_HOURS_MS = 7_200_000L

internal fun timing(
    offset: Int = 0,
    start: String? = "06:00",
) = Timing(null, null, null, null, null, null, BroadcastDay(offset, start))

internal fun rules(
    name: String,
    vararg sources: Source,
    maxRating: String? = null,
    exclusions: List<Source>? = null,
    timing: Timing = timing(),
) = ChannelRules(1, name, Seed(name.hashCode().toULong()), sources.toList(), exclusions, maxRating, null, timing, null)

internal fun channel(
    id: String,
    number: Int?,
    state: ChannelState,
    source: ChannelSource,
    onSinceMs: Long = ADDED_MS,
) = LineupChannel(ChannelId(id), number, null, null, state, source, onSinceMs)

internal fun starter(
    key: String,
    number: Int?,
    state: ChannelState,
    rules: ChannelRules,
) = channel(LineupIds.starter(key).value, number, state, ChannelSource.Starter(key, edited = false, rules = rules))

internal fun leftovers(
    key: String,
    number: Int,
    maxRating: String?,
    state: ChannelState = ChannelState.On,
) = channel(LineupIds.leftovers(key).value, number, state, ChannelSource.Leftovers(key, maxRating))

internal fun lineupOf(
    vararg channels: LineupChannel,
    offset: Int = 0,
) = Lineup(SERVER, USER, catalogVersion = 1, utcOffsetMinutes = offset, finalised = true, channels = channels.toList())

/** A hex ID made from [n], so fixtures are readable: `id(7)` is `0000…0007`. */
internal fun id(n: Int): CanonicalId = CanonicalId.parse(n.toString(16).padStart(32, '0'))

/** A movie of [runtimeMs] with [genres] and [rating], added at [ADDED_MS]. */
@Suppress("LongParameterList")
internal fun movie(
    n: Int,
    vararg genres: String,
    rating: String? = "PG",
    tags: List<String> = emptyList(),
    runtimeMs: Long = TWO_HOURS_MS,
    createdAtMs: Long = ADDED_MS,
) = RuleItem(
    item = ScheduleItem(id(n), ScheduleItemKind.Movie, runtimeMs, createdAtMs, null, null, 2000, null, null, null),
    libraryId = LIBRARY_ID,
    seasonId = null,
    officialRating = rating,
    genres = genres.toList(),
    tags = tags,
)

/** A small catalog: five grown-up recipes, one kids' recipe, the opt-in one and both leftovers. */
internal object TestCatalog : StarterCatalogView {
    override val version: Int = 1

    override val recipes: List<Recipe> =
        listOf(
            recipe("toons", Band.Kids, 10, "Animation", maxRating = "G"),
            recipe("laughs", Band.General, 110, "Comedy"),
            recipe("whodunit", Band.Drama, 210, "Crime"),
            recipe("westerns", Band.Drama, 220, "Western"),
            recipe("frights", Band.Genre, 310, "Horror"),
            recipe("rockets", Band.Genre, 320, "Science Fiction"),
            Recipe(
                "after-hours",
                "After Hours",
                "Grown-up.",
                Band.Premium,
                590,
                optIn = true,
                sources = listOf(Source.Tag("late")),
                exclusions = emptyList(),
                maxRating = null,
                ordering = null,
                collectionHints = emptyList(),
            ),
        )

    override val leftovers: List<LeftoversRecipe> =
        listOf(
            LeftoversRecipe("kids-odds-and-ends", "Kids' Odds", "Kids' rest.", 99, "PG"),
            LeftoversRecipe("odds-and-ends", "Odds", "The rest.", 199, null),
        )

    override val retiredNumbers: Set<Int> = emptySet()
    override val retiredKeys: Set<String> = emptySet()

    private fun recipe(
        key: String,
        band: Band,
        number: Int,
        genre: String,
        maxRating: String? = null,
    ) = Recipe(
        key,
        key.replaceFirstChar { it.uppercase() },
        "Synthetic.",
        band,
        number,
        optIn = false,
        sources = listOf(Source.Genre(genre)),
        exclusions = emptyList(),
        maxRating = maxRating,
        ordering = null,
        collectionHints = emptyList(),
    )
}

/** Seven two-hour movies per grown-up genre (14 h each, so every recipe fills), the kids' ones rated G. */
internal fun fillingLibrary(): List<RuleItem> {
    val genres = listOf("Comedy", "Crime", "Western", "Horror", "Science Fiction")
    val grownUp =
        genres.flatMapIndexed { g, genre -> (0 until 7).map { movie(100 + g * 10 + it, genre, rating = "R") } }
    val kids = (0 until 7).map { movie(200 + it, "Animation", rating = "G") }
    return grownUp + kids
}

/**
 * A library repository that counts the reads the lineup code makes. [ACCOUNT]'s collection pass has succeeded, as the
 * first lineup waits for it; a test of the wait turns it off with `delegate.setCollectionsSynced`.
 */
internal class CountingLibrary(
    val delegate: InMemoryLibraryRepository = InMemoryLibraryRepository(),
) : LibraryRepository by delegate {
    init {
        delegate.setCollectionsSynced(ACCOUNT, true)
    }

    val snapshotCutoffs = mutableListOf<Instant>()
    var scheduleItemsCalls = 0
    var programInfoCalls = 0

    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        snapshotCutoffs += cutoff
        return delegate.ruleSnapshot(account, libraries, cutoff)
    }

    override suspend fun scheduleItems(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): List<ScheduleItem> {
        scheduleItemsCalls++
        return delegate.scheduleItems(account, libraries, cutoff)
    }

    override suspend fun programInfo(
        account: LibraryAccount,
        ids: Set<CanonicalId>,
    ): Map<CanonicalId, ProgramInfo> {
        programInfoCalls++
        return delegate.programInfo(account, ids)
    }

    fun seed(vararg items: RuleItem) = delegate.seedRule(ACCOUNT, *items)

    fun seed(items: List<RuleItem>) = delegate.seedRule(ACCOUNT, *items.toTypedArray())
}
