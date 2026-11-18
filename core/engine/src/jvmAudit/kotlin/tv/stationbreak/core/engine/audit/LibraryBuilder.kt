// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.NameFolds
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/** What a title is, for the builder: the facts a rule reads. Names are invented ([InventedNames]). */
internal class TitleSpec(
    val genres: List<String>,
    val tags: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val rating: String? = null,
    val minutes: Int = 0,
    val year: Int = DEFAULT_YEAR,
) {
    private companion object {
        const val DEFAULT_YEAR = 2005
    }
}

/**
 * Builds a library by code, with invented titles: IDs, names and times are worked out from a counter, so the same
 * calls give the same snapshot. A film has one item; a series has episodes in seasons, each with the series' rating,
 * and carries its genres, tags and studios on the series row, as a server sends them. Nothing here is, or is meant to
 * resemble, anyone's library.
 */
internal class LibraryBuilder(
    libraryNumber: Int,
    private val names: InventedNames = InventedNames(),
) {
    private val items = ArrayList<RuleItem>()
    private val series = LinkedHashMap<CanonicalId, RuleSeries>()
    private val collections = ArrayList<RuleCollection>()
    private val folds = NameFolds()
    private val usedNames = HashSet<String>()
    private var nextItem = 0
    private var nextSeries = 0
    private var nextSeason = 0
    private var nextCollection = 0
    private val library = id(NS_LIBRARY, libraryNumber)

    /** The names of the titles added, by the ID of the film or series, for collections and test cases. */
    val titleIds = LinkedHashMap<String, CanonicalId>()

    private fun id(
        space: Int,
        n: Int,
    ): CanonicalId {
        val value = (space.toLong() shl SPACE_SHIFT) or n.toLong()
        return CanonicalId.parse(value.toString(HEX).padStart(ID_LENGTH, '0'))
    }

    private fun nameOf(wanted: String?): String {
        var name = wanted ?: names.next()
        while (!usedNames.add(name)) name = names.next()
        return name
    }

    /** Adds a film and returns its name. */
    fun film(
        spec: TitleSpec,
        name: String? = null,
    ): String {
        val title = nameOf(name)
        val itemId = id(NS_ITEM, nextItem++)
        val item = scheduleItem(itemId, ScheduleItemKind.Movie, spec, null, null, null)
        add(RuleItem(item, library, null, spec.rating, spec.genres, spec.tags, spec.studios, title))
        titleIds[title] = itemId
        return title
    }

    /** Adds a series of [seasons] seasons of [episodes] episodes each, and returns its name. */
    fun series(
        spec: TitleSpec,
        seasons: Int,
        episodes: Int,
        name: String? = null,
    ): String {
        val title = nameOf(name)
        val seriesId = id(NS_SERIES, nextSeries++)
        val row = RuleSeries(seriesId, spec.rating, spec.genres, spec.tags, spec.studios, title)
        row.primeFolds(folds)
        series[seriesId] = row
        for (season in 1..seasons) {
            val seasonId = id(NS_SEASON, nextSeason++)
            for (episode in 1..episodes) {
                val itemId = id(NS_ITEM, nextItem++)
                val item = scheduleItem(itemId, ScheduleItemKind.Episode, spec, seriesId, season, episode)
                val episodeTitle = episodeName(episode)
                add(RuleItem(item, library, seasonId, spec.rating, emptyList(), emptyList(), emptyList(), episodeTitle))
            }
        }
        titleIds[title] = seriesId
        return title
    }

    // An episode's own name, as a server holds one: not the series'.
    private fun episodeName(episode: Int): String = "Episode $episode"

    /** Adds a collection of the titles named [members] (films or series), and returns it. */
    fun collection(
        name: String,
        members: List<String>,
    ) {
        val ids = members.map { titleIds.getValue(it) }
        val films = filmIds()
        val movies = ids.filter { it in films }.toSet()
        val seriesIds = ids.filter { it in series.keys }.toSet()
        val id = id(NS_COLLECTION, nextCollection++)
        collections += RuleCollection(id, movies, emptySet(), seriesIds, emptySet(), name)
    }

    private fun filmIds(): Set<CanonicalId> {
        val films = items.filter { it.item.kind == ScheduleItemKind.Movie }
        return films.mapTo(HashSet()) { it.item.id }
    }

    private fun add(item: RuleItem) {
        item.primeFolds(folds)
        items += item
    }

    @Suppress("LongParameterList") // One for each field a title sets on its item.
    private fun scheduleItem(
        id: CanonicalId,
        kind: ScheduleItemKind,
        spec: TitleSpec,
        seriesId: CanonicalId?,
        season: Int?,
        episode: Int?,
    ): ScheduleItem =
        ScheduleItem(
            id = id,
            kind = kind,
            runtimeMs = spec.minutes * MS_PER_MINUTE,
            createdAtMs = CREATED_AT_MS + id.value.takeLast(CREATED_DIGITS).toLong(HEX),
            deletedAtMs = null,
            premiereDate = if (kind == ScheduleItemKind.Movie) "${spec.year}-06-15" else null,
            productionYear = spec.year,
            seriesId = seriesId,
            seasonNumber = season,
            episodeNumber = episode,
        )

    fun build(collectionsSynced: Boolean = true): RuleSnapshot =
        RuleSnapshot(
            cutoffMs = CUTOFF_MS,
            libraries = setOf(library),
            items = items.sortedBy { it.item.id.value },
            series = series.values.sortedBy { it.id.value }.associateBy { it.id },
            collections = collections.sortedBy { it.id.value }.associateBy { it.id },
            collectionsSynced = collectionsSynced,
        )

    private companion object {
        const val NS_ITEM = 1
        const val NS_SERIES = 2
        const val NS_SEASON = 3
        const val NS_COLLECTION = 4
        const val NS_LIBRARY = 0xA
        const val SPACE_SHIFT = 32
        const val HEX = 16
        const val ID_LENGTH = 32
        const val MS_PER_MINUTE = 60_000L
        const val CREATED_DIGITS = 6
        const val CREATED_AT_MS = 1_600_000_000_000L

        /** The cut-off of every profile library: a fixed moment, so a snapshot is the same every run. */
        const val CUTOFF_MS = 1_800_000_000_000L
    }
}

/**
 * Invented titles: two made-up words, from a fixed list of syllables, in a fixed scrambled order. They are not words
 * of any language and not names of anything, so no title of a profile library is a real one by design. The pairs
 * repeat after [WORDS] names, so from the second round on a name carries its round's number (`Bravelmi Torsenda 2`):
 * a stress library has more titles than one round (#790), and the first round, which every golden uses, is unchanged.
 */
internal class InventedNames {
    private var n = 1

    fun next(): String {
        val index = n++
        val pair = "${word(index * FIRST_STEP)} ${word(index * SECOND_STEP + OFFSET)}"
        val round = (index - 1) / WORDS + 1
        return if (round == 1) pair else "$pair $round"
    }

    private fun word(seed: Int): String {
        var v = Math.floorMod(seed, WORDS)
        val sb = StringBuilder()
        repeat(SYLLABLES_PER_WORD) {
            sb.append(SYLLABLES[v % SYLLABLES.size])
            v /= SYLLABLES.size
        }
        return sb.toString().replaceFirstChar { it.uppercase() }
    }

    companion object {
        /**
         * Whether [name] is two words of three syllables each, from the list, with a round number of 2 or more after
         * the first round: what [next] makes, and nothing else.
         */
        fun isInvented(name: String): Boolean {
            val parts = name.split(' ')
            val round = parts.getOrNull(WORDS_PER_NAME)
            if (parts.size > WORDS_PER_NAME + 1 || (round != null && !ROUND.matches(round))) return false
            val words = parts.take(WORDS_PER_NAME)
            return words.size == WORDS_PER_NAME &&
                words.all { word ->
                    var rest = word.lowercase()
                    var syllables = 0
                    while (rest.isNotEmpty()) {
                        val syllable = SYLLABLES.firstOrNull { rest.startsWith(it) } ?: break
                        rest = rest.removePrefix(syllable)
                        syllables++
                    }
                    rest.isEmpty() && syllables == SYLLABLES_PER_WORD && word.first().isUpperCase()
                }
        }

        private val SYLLABLES =
            listOf(
                "bra",
                "vel",
                "tor",
                "mi",
                "sen",
                "dar",
                "quin",
                "lo",
                "fa",
                "zu",
                "ren",
                "ky",
                "ol",
                "ma",
                "pe",
                "xi",
            )
        private const val SYLLABLES_PER_WORD = 3
        private const val WORDS_PER_NAME = 2

        // A round after the first: 2 or more, with no leading zero.
        private val ROUND = Regex("[2-9]|[1-9][0-9]+")
        private const val WORDS = 16 * 16 * 16
        private const val FIRST_STEP = 977
        private const val SECOND_STEP = 613
        private const val OFFSET = 41
    }
}
