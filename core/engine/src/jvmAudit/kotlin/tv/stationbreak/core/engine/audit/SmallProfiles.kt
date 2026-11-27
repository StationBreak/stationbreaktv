// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The shape of a library is its data: counts, minutes and years are written as numbers.

package tv.stationbreak.core.engine.audit

private const val SITCOM_MINUTES = 22
private const val FILM_MINUTES = 100

/** A run of [count] series that share their facts; the year moves on by one for each. */
@Suppress("LongParameterList") // The facts a run of series shares, each with a default.
internal fun LibraryBuilder.shows(
    count: Int,
    genres: List<String>,
    rating: String,
    minutes: Int,
    tags: List<String> = emptyList(),
    seasons: Int = 3,
    episodes: Int = 10,
    firstYear: Int = 2000,
) {
    repeat(count) {
        series(TitleSpec(genres, tags, emptyList(), rating, minutes, firstYear + it), seasons, episodes)
    }
}

/** A run of [count] films that share their facts; the length and the year move on for each. */
@Suppress("LongParameterList") // The facts a run of films shares, each with a default.
private fun LibraryBuilder.films(
    count: Int,
    genres: List<String>,
    rating: String,
    minutes: Int = FILM_MINUTES,
    tags: List<String> = emptyList(),
    firstYear: Int = 1995,
) {
    repeat(count) {
        film(TitleSpec(genres, tags, emptyList(), rating, minutes + it % 4 * 7, firstYear + it))
    }
}

private fun LibraryBuilder.sitcoms(
    count: Int,
    genres: List<String>,
    rating: String,
    firstYear: Int,
) = shows(count, genres, rating, SITCOM_MINUTES, listOf("sitcom"), seasons = 5, episodes = 12, firstYear = firstYear)

private fun LibraryBuilder.kidsShows(
    count: Int,
    genres: List<String>,
    rating: String,
    minutes: Int,
) = shows(count, genres, rating, minutes, listOf("kids"), seasons = 3, episodes = 14, firstYear = 2008)

/** The cases the profile libraries carry (see [CaseTitles]); each adds the titles it needs to a library. */
internal fun LibraryBuilder.addAdultAnimatedSitcom(): ProfileCase {
    // Animation and Comedy, rated for adults, a studio no recipe names, and no adult-animation tag.
    val studios = listOf("Invented Pictures")
    val spec = TitleSpec(listOf("Animation", "Comedy"), emptyList(), studios, "TV-MA", SITCOM_MINUTES, 2012)
    series(spec, 6, 13, name = CaseTitles.ADULT_ANIMATED_SITCOM)
    val what = "An adult animated sitcom with no studio match and no adult-animation tag"
    return ProfileCase(listOf(CaseTitles.ADULT_ANIMATED_SITCOM), what)
}

internal fun LibraryBuilder.addSitcomWithAnExtraGenre(): ProfileCase {
    val spec = TitleSpec(listOf("Comedy", "Mystery"), listOf("sitcom"), emptyList(), "TV-14", SITCOM_MINUTES, 2009)
    series(spec, 4, 12, name = CaseTitles.SITCOM_WITH_AN_EXTRA_GENRE)
    val what = "A sitcom whose genres include one beyond Comedy, Family and Romance"
    return ProfileCase(listOf(CaseTitles.SITCOM_WITH_AN_EXTRA_GENRE), what)
}

internal fun LibraryBuilder.addSitcomWithAFreedGenre(): ProfileCase {
    // Comedy and Drama: an extra genre that does not mean "not a sitcom" (catalog 5 lets Laugh Track take it).
    val spec = TitleSpec(listOf("Comedy", "Drama"), listOf("sitcom"), emptyList(), "TV-14", SITCOM_MINUTES, 2011)
    series(spec, 4, 12, name = CaseTitles.SITCOM_WITH_A_FREED_GENRE)
    val what = "A sitcom whose extra genre (Drama) does not mean it is not a sitcom"
    return ProfileCase(listOf(CaseTitles.SITCOM_WITH_A_FREED_GENRE), what)
}

internal fun LibraryBuilder.addPgAnimatedFilms(): ProfileCase {
    // A studio no recipe names and no kid list holds, rated PG: the grown-up hold passes both films.
    val studios = listOf("Invented Pictures")
    val family = TitleSpec(listOf("Animation", "Family"), emptyList(), studios, "PG", FILM_MINUTES, 2014)
    film(family, CaseTitles.PG_ANIMATED_FAMILY_FILM)
    val plain = TitleSpec(listOf("Animation", "Comedy"), emptyList(), studios, "PG", FILM_MINUTES, 2016)
    film(plain, CaseTitles.PG_ANIMATED_FILM_NOT_FAMILY)
    val what =
        "A PG animated family film that must not be homed on Cartoons After Dark, " +
            "and a PG animated film with no Family genre"
    return ProfileCase(listOf(CaseTitles.PG_ANIMATED_FAMILY_FILM, CaseTitles.PG_ANIMATED_FILM_NOT_FAMILY), what)
}

internal fun LibraryBuilder.addAdultCartoonWithFamily(): ProfileCase {
    // Tagged as adult animation, rated for adults, and carrying Family: on Cartoons After Dark at catalog 4 (by its
    // tag), off it at catalog 5 (the Family exclusion; the trade-off is written in `StarterCatalog.VERSION`).
    val genres = listOf("Animation", "Family")
    val spec = TitleSpec(genres, listOf("adult animation"), emptyList(), "TV-MA", SITCOM_MINUTES, 2013)
    series(spec, 3, 13, name = CaseTitles.ADULT_CARTOON_WITH_FAMILY)
    val what = "An adult-tagged cartoon that also carries Family: the Family exclusion takes it off Cartoons After Dark"
    return ProfileCase(listOf(CaseTitles.ADULT_CARTOON_WITH_FAMILY), what)
}

internal fun LibraryBuilder.addSmallHorrorShelf(): ProfileCase {
    // Four horror films of about two hours: a shelf too small for the horror starter to qualify.
    for ((i, name) in CaseTitles.SMALL_HORROR_SHELF.withIndex()) {
        film(TitleSpec(listOf("Horror"), emptyList(), emptyList(), "R", FILM_MINUTES + i * 8, 1988 + i * 9), name)
    }
    val what = "A small horror shelf: too little for the horror starter to qualify"
    return ProfileCase(CaseTitles.SMALL_HORROR_SHELF, what)
}

internal object SitcomHousehold {
    fun build(): Profile {
        val b = LibraryBuilder(1)
        b.sitcoms(14, listOf("Comedy"), "TV-PG", 1994)
        b.sitcoms(3, listOf("Comedy", "Family"), "TV-G", 2001)
        b.sitcoms(2, listOf("Comedy", "Romance"), "TV-14", 2004)
        val cases = ArrayList(listOf(b.addSitcomWithAnExtraGenre(), b.addAdultAnimatedSitcom()))
        b.films(8, listOf("Comedy", "Romance"), "PG-13")
        b.films(6, listOf("Family", "Comedy"), "PG")
        b.kidsShows(8, listOf("Animation", "Kids"), "TV-Y", 11)
        b.shows(6, listOf("Drama"), "TV-14", 44)
        b.shows(4, listOf("Crime", "Drama"), "TV-MA", 44, seasons = 4)
        // Added last, so no title before them changes its name or ID.
        cases += b.addSitcomWithAFreedGenre()
        cases += b.addPgAnimatedFilms()
        cases += b.addAdultCartoonWithFamily()
        val description = "A household that watches sitcoms, with some family films and kids' shows"
        return Profile("sitcom-household", description, AuditInput(b.build(), null), cases)
    }
}

internal object HorrorFan {
    fun build(): Profile {
        val b = LibraryBuilder(2)
        val cases = listOf(b.addSmallHorrorShelf())
        b.films(26, listOf("Thriller"), "R", 105)
        b.films(4, listOf("Thriller", "Mystery"), "R", 110, listOf("ghost", "haunted"))
        b.films(3, listOf("Mystery"), "PG-13", 98, listOf("slasher", "summer camp"))
        b.films(22, listOf("Crime"), "R", 112)
        b.films(20, listOf("Science Fiction"), "PG-13", 118)
        b.films(20, listOf("Action"), "R", 108)
        b.shows(10, listOf("Crime", "Drama"), "TV-MA", 46, seasons = 4, firstYear = 2005)
        b.shows(6, listOf("Science Fiction", "Drama"), "TV-14", 45, listOf("mind-bender"), firstYear = 2008)
        val description = "A horror fan whose shelf is thin: mostly thrillers, crime and science fiction"
        return Profile("horror-fan", description, AuditInput(b.build(), null), cases)
    }
}

internal object KidsHousehold {
    fun build(): Profile {
        val b = LibraryBuilder(3)
        b.kidsShows(26, listOf("Animation", "Kids"), "TV-Y", 11)
        b.kidsShows(10, listOf("Family", "Kids"), "TV-Y7", 22)
        b.kidsShows(4, listOf("Animation", "Kids", "Adventure"), "TV-G", 22)
        b.films(14, listOf("Animation", "Family"), "G", 88)
        b.films(12, listOf("Family", "Adventure"), "PG", 96)
        b.films(5, listOf("Family"), "G", 80, listOf("Christmas", "holiday"))
        b.shows(4, listOf("Documentary"), "TV-G", 24, listOf("kids", "science"), seasons = 2, firstYear = 2015)
        // Added last, so no title before them changes its name or ID.
        // Kids' shows under the stock genres a server gives them (#1010): a typical kids' library has Adventure,
        // Comedy and Science Fiction next to Kids, so Backyard Fort, Giggle Garage and Robot Factory clear their bars.
        // The shows are made for kids (the kids tag next to a kid rating) and inside the Tots ceiling.
        b.kidsShows(4, listOf("Kids", "Adventure"), "TV-Y7", 22)
        b.kidsShows(6, listOf("Kids", "Comedy"), "TV-Y7", 22)
        b.kidsShows(6, listOf("Kids", "Science Fiction"), "TV-Y7", 22)
        // Little Spirits is a Family-band channel: its anime are PG films, not Kids titles. A tag and a studio the
        // recipe names win them from Level Up, as in the anime viewer's library.
        repeat(9) {
            val genres = listOf("Animation", "Anime", "Fantasy")
            val tags = listOf("anime", "family anime")
            b.film(TitleSpec(genres, tags, listOf("Studio Ghibli"), "PG", 95 + it % 4 * 7, 2004 + it))
        }
        val description = "A kids' household: cartoons, family films and a few holiday titles"
        return Profile("kids-household", description, AuditInput(b.build(), null), emptyList())
    }
}

internal object AnimeViewer {
    fun build(): Profile {
        val b = LibraryBuilder(4)
        val anime = listOf("Animation", "Anime")
        b.shows(18, anime, "TV-14", 24, listOf("anime"), seasons = 2, episodes = 24, firstYear = 2002)
        val mecha = listOf("anime", "mecha", "giant robot")
        b.shows(6, anime, "TV-14", 24, mecha, seasons = 1, episodes = 26, firstYear = 1995)
        b.shows(6, anime, "TV-14", 23, listOf("anime", "shonen"), episodes = 24, firstYear = 2010)
        b.films(14, anime + "Fantasy", "PG", 105, listOf("anime"), 1998)
        // The family-anime shelf (catalog 18, #1031): films a studio or a `family anime` tag marks, as a real anime
        // viewer's library has.
        repeat(14) {
            val spec =
                TitleSpec(
                    anime + "Fantasy",
                    listOf("anime", "family anime"),
                    listOf("Studio Ghibli"),
                    "PG",
                    95 + it % 4 * 7,
                    1999 + it,
                )
            b.film(spec)
        }
        b.shows(6, listOf("Animation"), "TV-PG", 22, episodes = 13, firstYear = 2005)
        b.shows(8, listOf("Drama"), "TV-14", 44, firstYear = 2010)
        // Network dramas (catalog 20, #1033): Prestige takes drama by network, not by the bare Drama genre, so a
        // library with a little of it keeps the channel, as a real anime viewer's does.
        repeat(8) {
            val network = listOf("HBO", "Apple TV", "AMC", "FX")[it % 4]
            b.series(TitleSpec(listOf("Drama"), emptyList(), listOf(network), "TV-14", 44, 2012 + it), 3, 10)
        }
        val description = "An anime viewer: series, mecha, shonen and films, with a little else"
        return Profile("anime-viewer", description, AuditInput(b.build(), null), emptyList())
    }
}
