// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.FilterKind

/**
 * How a channel plays its pool, as the screens word it: one of the builder's [PlayOrder]s, or an order the builder has
 * no control for (a built-in's `chronological`, `randomInOrder` or `curated`, which a screen calls "Programmed").
 */
sealed interface ShownOrder {
    data class Play(
        val order: PlayOrder,
    ) : ShownOrder

    data class Kept(
        val mode: KeptOrder,
    ) : ShownOrder
}

/** A runtime window in minutes; a side that is null is open. */
data class MinuteWindow(
    val min: Int?,
    val max: Int?,
)

/**
 * What narrows a pool beyond its sources, in the viewer's terms. A part that is empty or null narrows nothing. It holds
 * every filter the rules format has; the builder's own [DraftFilters] is the part of it the builder can set.
 */
data class Narrowing(
    val genres: List<String> = emptyList(),
    val notGenres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val runtime: MinuteWindow? = null,
    val episodeRuntime: MinuteWindow? = null,
    val movieRuntime: MinuteWindow? = null,
    val only: OnlyKind? = null,
) {
    /** True when nothing is narrowed. */
    val isEmpty: Boolean
        get() =
            genres.isEmpty() && notGenres.isEmpty() && tags.isEmpty() && studios.isEmpty() &&
                yearFrom == null && yearTo == null && runtime == null && episodeRuntime == null &&
                movieRuntime == null && only == null
}

/**
 * What a channel's rules say about what it plays and in what order, as structured values: the one description that
 * the builder's edit sheet and a channel's properties both word, so the two never disagree. Nothing here is text; a
 * screen words each part (`ChannelDraft.describe` and [of] are how the app makes one).
 *
 * [sources] and [leaveOut] carry the words the viewer sees (their labels). [kept] is what the builder has no picker for
 * and that is still in force: year ranges, studios, a block length and the like. [ceiling] is a rating-ladder name or
 * null. The caps are null for no limit, and only meaningful when [showsCaps].
 */
data class RulesDescription(
    val sources: List<DraftSource>,
    val leaveOut: List<DraftSource>,
    val kept: List<KeptItem>,
    val narrowing: Narrowing,
    val ceiling: String?,
    val order: ShownOrder,
    val showsCaps: Boolean,
    val seriesCap: Int?,
    val filmCap: Int?,
) {
    companion object {
        /**
         * The description of [rules], with [draft] their draft (`DraftRules.fromRules`, with the sources' words and
         * the studios' spelling filled in). The narrowing is read from the rules, which hold filters (tags, studios,
         * not-genres, a runtime for each kind) that a draft cannot.
         */
        internal fun of(
            rules: ChannelRules,
            draft: ChannelDraft,
        ): RulesDescription {
            val described = draft.describe()
            // The block length is the timing's to show (ChannelTiming), so it is not listed twice.
            val kept = described.kept.filterNot { it is KeptItem.BlockLength }
            return described.copy(kept = kept, narrowing = narrowingOf(rules))
        }

        private fun narrowingOf(rules: ChannelRules): Narrowing {
            val filters = rules.filters ?: return Narrowing()
            return Narrowing(
                genres = filters.genres.orEmpty(),
                notGenres = filters.notGenres.orEmpty(),
                tags = filters.tags.orEmpty(),
                studios = filters.studios.orEmpty(),
                yearFrom = filters.yearFrom,
                yearTo = filters.yearTo,
                runtime = window(filters.minRuntimeMinutes, filters.maxRuntimeMinutes),
                episodeRuntime = window(filters.episodeMinRuntimeMinutes, filters.episodeMaxRuntimeMinutes),
                movieRuntime = window(filters.movieMinRuntimeMinutes, filters.movieMaxRuntimeMinutes),
                only =
                    when (filters.kind) {
                        FilterKind.Movie -> OnlyKind.Films
                        FilterKind.Episode -> OnlyKind.Shows
                        null -> null
                    },
            )
        }

        private fun window(
            min: Int?,
            max: Int?,
        ): MinuteWindow? = if (min == null && max == null) null else MinuteWindow(min, max)
    }
}

/** The play order the screen shows: the draft's own, else a kept order, else Shuffle (as the edit sheet's row does). */
fun ChannelDraft.shownOrder(): ShownOrder {
    val kept = keptInForce.filterIsInstance<KeptItem.Order>().firstOrNull()
    return order?.let { ShownOrder.Play(it) }
        ?: kept?.let { ShownOrder.Kept(it.mode) }
        ?: ShownOrder.Play(PlayOrder.Shuffle)
}

/** This draft's [RulesDescription]: what the builder's edit sheet words. */
fun ChannelDraft.describe(): RulesDescription =
    RulesDescription(
        sources = sources,
        leaveOut = leaveOut,
        kept = keptInForce,
        narrowing =
            Narrowing(
                genres = filters.genres,
                yearFrom = filters.yearFrom,
                yearTo = filters.yearTo,
                runtime =
                    if (filters.minMinutes == null && filters.maxMinutes == null) {
                        null
                    } else {
                        MinuteWindow(filters.minMinutes, filters.maxMinutes)
                    },
                only = filters.only,
            ),
        ceiling = filters.maxRating,
        order = shownOrder(),
        showsCaps = showsCaps(),
        seriesCap = shownSeriesCap(),
        filmCap = shownFilmCap(),
    )
