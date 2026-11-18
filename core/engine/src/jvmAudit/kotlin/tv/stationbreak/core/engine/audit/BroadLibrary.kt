// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

/** A run of titles that share their facts: [count] of them, films or series, spread over years and a few ratings. */
@Suppress("LongParameterList") // A plain carrier: one field per fact a shelf shares.
private class Shelf(
    val count: Int,
    val series: Boolean,
    val genres: List<String>,
    val tags: List<String> = emptyList(),
    val ratings: List<String>,
    val minutes: Int,
    val studios: List<String> = emptyList(),
    val firstYear: Int = 1950,
    val yearSpan: Int = 75,
)

@Suppress("LongParameterList") // A shelf of films: its facts, each with a default.
private fun film(
    count: Int,
    genres: List<String>,
    ratings: List<String>,
    minutes: Int = 105,
    tags: List<String> = emptyList(),
    span: Int = 75,
    studios: List<String> = emptyList(),
) = Shelf(count, false, genres, tags, ratings, minutes, studios, yearSpan = span)

@Suppress("LongParameterList") // A shelf of series: its facts, each with a default.
private fun show(
    count: Int,
    genres: List<String>,
    ratings: List<String>,
    minutes: Int = 44,
    tags: List<String> = emptyList(),
    studios: List<String> = emptyList(),
    first: Int = 1975,
    span: Int = 50,
) = Shelf(count, true, genres, tags, ratings, minutes, studios, first, span)

private val ADULT = listOf("R", "TV-MA")
private val TEEN = listOf("PG-13", "TV-14")
private val ALL = listOf("PG", "TV-PG", "G")
private val KIDS_RATINGS = listOf("TV-Y", "TV-Y7", "TV-G")
private val INVENTED_STUDIO = listOf("Invented Pictures")
private val ADULT_ANIMATION_TAGS = listOf("adult animation", "animated sitcom")

// The shelves of a broad library: the counts add up to a little under 1,900, and the rest is plain drama.
@Suppress("MagicNumber") // The shape of a library is its data: counts, minutes and years are written as numbers.
private val SHELVES: List<Shelf> =
    listOf(
        film(120, listOf("Drama"), ADULT + TEEN),
        film(110, listOf("Comedy"), TEEN + ALL),
        film(90, listOf("Action"), ADULT + TEEN),
        film(80, listOf("Thriller"), ADULT),
        film(60, listOf("Crime"), ADULT),
        film(60, listOf("Science Fiction"), TEEN),
        film(40, listOf("Horror"), ADULT, 98),
        film(18, listOf("Horror"), ADULT, 96, listOf("slasher", "summer camp")),
        film(12, listOf("Mystery", "Thriller"), TEEN, 102, listOf("ghost", "haunted")),
        film(50, listOf("Romance", "Comedy"), TEEN),
        film(60, listOf("Family"), ALL, 94),
        film(40, listOf("Animation", "Family"), ALL, 90),
        film(20, listOf("Western"), ALL + ADULT, 112, span = 50),
        film(25, listOf("War", "Drama"), ADULT, 128),
        film(20, listOf("History", "Drama"), TEEN, 135),
        film(60, listOf("Documentary"), ALL + TEEN, 92),
        film(20, listOf("Music"), TEEN, 100, listOf("concert", "music documentary")),
        film(50, listOf("Fantasy", "Adventure"), TEEN, 124),
        film(40, listOf("Mystery"), TEEN, 106),
        film(40, listOf("Adventure"), TEEN, 112),
        film(20, listOf("Family"), listOf("G"), 82, listOf("Christmas", "holiday")),
        film(15, listOf("Horror"), listOf("NR"), 80, listOf("b-movie", "drive-in"), span = 25),
        film(25, listOf("Comedy"), ADULT, 96, listOf("dark comedy")),
        film(30, listOf("Action", "Thriller"), ADULT, 118, listOf("heist")),
        show(60, listOf("Comedy"), ALL + TEEN, 22, listOf("sitcom")),
        show(100, listOf("Drama"), TEEN + ADULT),
        show(70, listOf("Crime", "Drama"), ADULT, 46),
        show(20, listOf("Animation", "Comedy"), ADULT, 22, ADULT_ANIMATION_TAGS, INVENTED_STUDIO),
        show(8, listOf("Animation", "Comedy"), ADULT, 22, emptyList(), INVENTED_STUDIO),
        show(70, listOf("Animation", "Kids"), KIDS_RATINGS, 12, listOf("kids"), first = 1995, span = 30),
        show(60, listOf("Animation", "Anime"), TEEN, 24, listOf("anime"), first = 1990, span = 35),
        show(50, listOf("Documentary"), ALL + TEEN, 48, listOf("nature")),
        show(30, listOf("Reality"), TEEN, 42, first = 2000, span = 25),
        show(15, listOf("Talk"), TEEN, 45, first = 1990, span = 35),
        show(50, listOf("Science Fiction", "Drama"), TEEN, 45, first = 1985, span = 40),
        show(30, listOf("Fantasy", "Drama"), TEEN, 50, first = 2000, span = 25),
        show(30, listOf("Comedy"), TEEN, 28, listOf("british", "british comedy"), first = 1970, span = 50),
        show(20, listOf("Drama"), TEEN, 52, listOf("miniseries"), first = 1985, span = 40),
        show(12, listOf("Soap"), TEEN, 30, first = 1980, span = 40),
        show(8, listOf("Western"), ALL, 50, first = 1955, span = 20),
        show(25, listOf("Comedy"), TEEN, 24, listOf("stand-up", "sketch")),
    )

/**
 * A broad library of 1,900 titles, and the thin server of 200 (the same shelves, a tenth the size), both with invented
 * titles. They carry the adult animated sitcom and the sitcom with an extra genre; the thin one also carries the small
 * horror shelf, because at a tenth the size its horror is too little for the horror starter.
 */
@Suppress("MagicNumber") // The shape of a library is its data: counts, minutes and years are written as numbers.
internal object BroadLibrary {
    const val TITLES = 1_900
    private const val THIN_TITLES = 200
    private const val FILLER_MINUTES = 108
    private const val FILLER_YEAR = 1960
    private const val YEARS = 60
    private const val SHORT_EPISODES = 13

    fun build(total: Int): Profile {
        val description = "A broad library of $total titles across every shelf of the catalog"
        return of("broad-$total", description, total, false)
    }

    fun buildThin(): Profile {
        val description = "A thin server of $THIN_TITLES titles: one or two of most shelves"
        return of("thin-200", description, THIN_TITLES, true)
    }

    private fun of(
        key: String,
        description: String,
        total: Int,
        thin: Boolean,
    ): Profile {
        val cases = ArrayList<ProfileCase>()
        val b = shelved(total, thin, cases)
        collect(b)
        return Profile(key, description, AuditInput(b.build(), null), cases)
    }

    /** The broad library's titles, with no collections yet: for a stress library that adds its own (#790). */
    fun builder(total: Int): LibraryBuilder = shelved(total, false, ArrayList())

    private fun shelved(
        total: Int,
        thin: Boolean,
        cases: MutableList<ProfileCase>,
    ): LibraryBuilder {
        val b = LibraryBuilder(if (thin) THIN_LIBRARY else BROAD_LIBRARY)
        val scale = total.toDouble() / TITLES
        cases += b.addSitcomWithAnExtraGenre()
        cases += b.addAdultAnimatedSitcom()
        if (thin) cases += b.addSmallHorrorShelf()
        for (shelf in SHELVES) {
            val count = if (thin) maxOf(1, Math.round(shelf.count * scale).toInt()) else shelf.count
            // The thin server has no horror to speak of beyond its small shelf.
            if (thin && "Horror" in shelf.genres) continue
            repeat(count) { i -> add(b, shelf, i) }
        }
        var filler = 0
        while (b.titleIds.size < total) {
            val rating = TEEN[filler % TEEN.size]
            val year = FILLER_YEAR + filler % YEARS
            b.film(TitleSpec(listOf("Drama"), emptyList(), emptyList(), rating, FILLER_MINUTES, year))
            filler++
        }
        check(b.titleIds.size == total) { "The shelves make exactly the library's titles" }
        return b
    }

    private fun add(
        b: LibraryBuilder,
        shelf: Shelf,
        i: Int,
    ) {
        val year = shelf.firstYear + i * YEAR_STEP % shelf.yearSpan
        val rating = shelf.ratings[i % shelf.ratings.size]
        val spec = TitleSpec(shelf.genres, shelf.tags, shelf.studios, rating, shelf.minutes + i % MINUTE_SPREAD, year)
        if (shelf.series) {
            val seasons = 1 + i % MAX_SEASONS
            b.series(spec, seasons, if (shelf.minutes < SHORT_EPISODE) SHORT_EPISODES else EPISODES)
        } else {
            b.film(spec)
        }
    }

    // A few collections: some a person made, some a server made (named "... Collection"), each of a handful of titles.
    private fun collect(b: LibraryBuilder) {
        val names = b.titleIds.keys.toList()
        for (c in 0 until COLLECTIONS) {
            val members = names.filterIndexed { i, _ -> i % COLLECTIONS == c }.take(COLLECTION_SIZE)
            val label = names[c * COLLECTION_NAME_STEP]
            b.collection(if (c % 2 == 0) "$label Collection" else "$label picks", members)
        }
    }

    private const val BROAD_LIBRARY = 5
    private const val THIN_LIBRARY = 6
    private const val YEAR_STEP = 7
    private const val MINUTE_SPREAD = 9
    private const val MAX_SEASONS = 4
    private const val SHORT_EPISODE = 30
    private const val EPISODES = 10
    private const val COLLECTIONS = 12
    private const val COLLECTION_SIZE = 6
    private const val COLLECTION_NAME_STEP = 3
}
