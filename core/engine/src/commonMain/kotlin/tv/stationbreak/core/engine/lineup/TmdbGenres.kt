// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/** The genre names TMDB uses, in the movie and TV lists. Any other genre spelling becomes a tag source. */
internal val TMDB_GENRES: Set<String> =
    linkedSetOf(
        "Action",
        "Adventure",
        "Animation",
        "Comedy",
        "Crime",
        "Documentary",
        "Drama",
        "Family",
        "Fantasy",
        "History",
        "Horror",
        "Music",
        "Mystery",
        "Romance",
        "Science Fiction",
        "TV Movie",
        "Thriller",
        "War",
        "Western",
        "Action & Adventure",
        "Kids",
        "News",
        "Reality",
        "Sci-Fi & Fantasy",
        "Soap",
        "Talk",
        "War & Politics",
    )
