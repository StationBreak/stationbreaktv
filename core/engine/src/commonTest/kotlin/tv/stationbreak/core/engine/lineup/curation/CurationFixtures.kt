// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.FormatPromise
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.Theme
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.engine.collection as collectionOf
import tv.stationbreak.core.engine.episode as episodeOf
import tv.stationbreak.core.engine.idOf as engineIdOf
import tv.stationbreak.core.engine.movie as movieOf
import tv.stationbreak.core.engine.series as seriesOf
import tv.stationbreak.core.engine.snapshot as snapshotOf

internal const val HOUR = 3_600_000L
internal const val HALF_HOUR = 1_800_000L

internal fun idOf(n: Int): CanonicalId = engineIdOf(n)

internal fun collection(
    id: CanonicalId,
    movies: Set<CanonicalId>,
): RuleCollection = collectionOf(id, movies = movies)

private fun RuleItem.lasting(ms: Long): RuleItem = copy(item = item.copy(runtimeMs = ms))

/** A small library: neutral titles with increasing IDs, so the snapshot's canonical order is the order of creation. */
internal class Library {
    private var next = 0x1000
    private val items = ArrayList<RuleItem>()
    private val series = ArrayList<RuleSeries>()
    private val byTitle = HashMap<CanonicalId, MutableList<ScheduleItem>>()
    val collections = ArrayList<RuleCollection>()

    @Suppress("LongParameterList") // Test builder: one defaulted parameter per field a test varies.
    fun show(
        eps: Int,
        epMs: Long = HOUR,
        genres: List<String> = emptyList(),
        rating: String? = null,
        studios: List<String> = emptyList(),
    ): CanonicalId {
        val id = idOf(next++)
        series += seriesOf(id, rating = rating, genres = genres, studios = studios)
        val list = byTitle.getOrPut(id) { ArrayList() }
        repeat(eps) {
            val ep = episodeOf(idOf(next++), id, rating = rating, genres = genres, studios = studios).lasting(epMs)
            items += ep
            list += ep.item
        }
        return id
    }

    fun film(
        ms: Long = 2 * HOUR,
        genres: List<String> = emptyList(),
        rating: String? = null,
        studios: List<String> = emptyList(),
    ): CanonicalId {
        val id = idOf(next++)
        val film = movieOf(id, rating = rating, genres = genres, studios = studios).lasting(ms)
        items += film
        byTitle[id] = mutableListOf(film.item)
        return id
    }

    fun itemsOf(titles: List<CanonicalId>): List<ScheduleItem> = titles.flatMap { byTitle.getValue(it) }

    fun snapshot(): RuleSnapshot {
        val sorted = items.sortedBy { it.item.id.value }
        return snapshotOf(items = sorted, series = series, collections = collections)
    }
}

internal fun theme(format: FormatPromise?): Theme = Theme(emptyList(), emptyList(), format, false)

private val NO_THEME = Theme(emptyList(), emptyList(), null, false)

/** A curated channel whose recipe is about [genres]; [anchors] join its rules, as the resolver puts them there. */
@Suppress("LongParameterList") // Test builder: one defaulted parameter per field a test varies.
internal fun channel(
    id: String,
    number: Int,
    genres: List<String>,
    pool: List<ScheduleItem>,
    kid: Boolean = false,
    anchors: List<Source> = emptyList(),
    theme: Theme = NO_THEME,
    extraSources: List<Source> = emptyList(),
): CurationChannel {
    val band = if (kid) Band.Kids else checkNotNull(Band.of(number))
    val recipe =
        Recipe(
            key = id,
            name = id,
            personality = "Test.",
            band = band,
            number = number,
            optIn = false,
            sources = genres.map { Source.Genre(it) } + extraSources,
            exclusions = emptyList(),
            maxRating = if (kid) "G" else null,
            ordering = OrderingMode.Curated,
            collectionHints = emptyList(),
            theme = theme,
        )
    val rules = rules(sources = recipe.sources + anchors, formatVersion = 3)
    return CurationChannel(ChannelId(id), number, recipe, kid, rules, rules.toSchedulerSettings(), pool)
}

internal fun CurationChannel.withPool(pool: List<ScheduleItem>): CurationChannel =
    CurationChannel(id, number, recipe, kid, rules, settings, pool)
