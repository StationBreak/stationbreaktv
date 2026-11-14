// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * The Kotlin copy of `docs/rules-format/vectors/kid-hold-5.json`, so the commonTest check (`KidHold5VectorTest`) can run
 * on linuxX64Test, which cannot read files. `KidHold5VectorInSyncTest` (jvmTest) fails if it differs from the published
 * file, and also runs the published file itself. The `\u` escapes are JSON escapes, decoded by the parser.
 */
internal object KidHold5VectorFile {
    val json: String =
        """
{
  "description": "The series-rating rule of kid-hold-5 / grown-up-hold-4 (docs/adr/0019-kid-hold.md, addendum 2026-11-13). Written by hand from the ADR text, not computed with an independent reference. For an episode of a known series the decisive rating is the series' rating when it is present, otherwise the episode's own rating. A series rating is absent when it is null or empty after trimming U+0020 spaces only (the trim of rating.json normalise); a tab, U+00A0 or U+3000 is not trimmed, so such a rating is present, off the ladder, and fails the kid table. 'passesKidCeiling' cases: the decisive rating must be within the kid ceiling (rating.json normalise and the kid table: TV-Y, TV-G, G rank 0; TV-Y7, TV-Y7-FV rank 1; PG rank 2; the ceiling choices are G, TV-Y7 and PG; unrated, Approved and every rating above PG fail every ceiling). 'animatedFamily' cases: the genres are the union of episodeGenres and seriesGenres, which must include Animation and Family, and the signal's rating test (G, TV-G, TV-Y, TV-Y7, TV-Y7-FV, Approved or unrated spellings, or an absent rating, pass) is applied to the decisive rating. 'madeForKids' cases: whether the episode is made for kids (kids genres, kid studios, children's ratings or the animated-family signal; no studios in these rows). 'dropsFromGrownUp' cases: the grown-up hold drops the episode when it is made for kids and passes the kid table with no ceiling (PG). 'unchanged' cases: shapes the rule does not touch (a movie or an episode with no series ID is judged on its own rating; an episode whose series is unknown fails closed). A blank series rating counts as no rating here; rules-format rule 4 (open and grown-up channels) still treats a blank rating as blocking its episodes. Every port ships this rule under the name kid-hold-5. kid-hold-5 is kid-hold-4 (ADR-0026, studio fold 2) plus this rule, so a kid-hold-5 port must also pass ADR-0026's kid-hold-4.json once it exists.",
  "passesKidCeiling": [
    {"name": "TV-Y series, TV-PG episode", "episode": "TV-PG", "series": "TV-Y", "ceiling": "PG", "passes": true},
    {"name": "TV-Y series, NR episode", "episode": "NR", "series": "TV-Y", "ceiling": "PG", "passes": true},
    {"name": "TV-Y7 series, TV-14 episode", "episode": "TV-14", "series": "TV-Y7", "ceiling": "TV-Y7", "passes": true},
    {"name": "TV-PG series, TV-Y episode", "episode": "TV-Y", "series": "TV-PG", "ceiling": "PG", "passes": false},
    {"name": "NR series, TV-Y episode", "episode": "TV-Y", "series": "NR", "ceiling": "PG", "passes": false},
    {"name": "Not Rated series, TV-Y episode", "episode": "TV-Y", "series": "Not Rated", "ceiling": "PG", "passes": false},
    {"name": "TV-Y7 series, G episode, G ceiling", "episode": "G", "series": "TV-Y7", "ceiling": "G", "passes": false},
    {"name": "empty series rating, TV-PG episode", "episode": "TV-PG", "series": "", "ceiling": "PG", "passes": false},
    {"name": "null series rating, TV-PG episode", "episode": "TV-PG", "series": null, "ceiling": "PG", "passes": false},
    {"name": "spaces-only series rating, TV-PG episode", "episode": "TV-PG", "series": "  ", "ceiling": "PG", "passes": false},
    {"name": "spaces-only series rating, TV-Y episode (episode decides)", "episode": "TV-Y", "series": "  ", "ceiling": "PG", "passes": true},
    {"name": "tab series rating is present, so TV-Y episode fails", "episode": "TV-Y", "series": "\t", "ceiling": "PG", "passes": false},
    {"name": "no-break space series rating is present, so TV-Y episode fails", "episode": "TV-Y", "series": "\u00a0", "ceiling": "PG", "passes": false},
    {"name": "ideographic space series rating is present, so TV-Y episode fails", "episode": "TV-Y", "series": "\u3000", "ceiling": "PG", "passes": false},
    {"name": "null series rating, TV-Y episode", "episode": "TV-Y", "series": null, "ceiling": "PG", "passes": true}
  ],
  "animatedFamily": [
    {"name": "TV-Y series, PG episode", "episode": "PG", "series": "TV-Y", "episodeGenres": ["Animation", "Family"], "seriesGenres": [], "holds": true},
    {"name": "PG series, TV-Y episode", "episode": "TV-Y", "series": "PG", "episodeGenres": ["Animation", "Family"], "seriesGenres": [], "holds": false},
    {"name": "spaces-only series rating, PG episode (episode decides)", "episode": "PG", "series": " ", "episodeGenres": ["Animation", "Family"], "seriesGenres": [], "holds": false},
    {"name": "spaces-only series rating, TV-Y episode (episode decides)", "episode": "TV-Y", "series": " ", "episodeGenres": ["Animation", "Family"], "seriesGenres": [], "holds": true},
    {"name": "tab series rating is present and off the ladder", "episode": "TV-Y", "series": "\t", "episodeGenres": ["Animation", "Family"], "seriesGenres": [], "holds": false},
    {"name": "no-break space series rating is present and off the ladder", "episode": "TV-Y", "series": "\u00a0", "episodeGenres": ["Animation", "Family"], "seriesGenres": [], "holds": false},
    {"name": "series genres alone supply Animation and Family, TV-G series, PG episode", "episode": "PG", "series": "TV-G", "episodeGenres": [], "seriesGenres": ["Animation", "Family"], "holds": true},
    {"name": "only Animation across episode and series", "episode": "TV-Y", "series": "TV-Y", "episodeGenres": ["Animation"], "seriesGenres": ["Drama"], "holds": false}
  ],
  "madeForKids": [
    {"name": "widening: TV-14 episode of a TV-G Animation+Family series is made for kids", "episode": "TV-14", "series": "TV-G", "episodeGenres": [], "seriesGenres": ["Animation", "Family"], "madeForKids": true},
    {"name": "NR series, PG episode, Animation+Family: made for kids (still fails every ceiling)", "episode": "PG", "series": "NR", "episodeGenres": ["Animation", "Family"], "seriesGenres": [], "madeForKids": true},
    {"name": "PG series, G episode, Animation+Family is not made for kids on the signal", "episode": "G", "series": "PG", "episodeGenres": ["Animation", "Family"], "seriesGenres": [], "madeForKids": false}
  ],
  "dropsFromGrownUp": [
    {"name": "kids-genre TV-Y series, TV-PG episode: dropped", "episode": "TV-PG", "series": "TV-Y", "episodeGenres": [], "seriesGenres": ["Children"], "drops": true},
    {"name": "kids-genre TV-Y series, unrated episode: dropped", "episode": "Unrated", "series": "TV-Y", "episodeGenres": [], "seriesGenres": ["Children"], "drops": true},
    {"name": "kids-genre unrated series, TV-PG episode: kept", "episode": "TV-PG", "series": null, "episodeGenres": [], "seriesGenres": ["Children"], "drops": false}
  ],
  "unchanged": [
    {"name": "movie rated TV-Y passes on its own rating", "shape": "movie", "rating": "TV-Y", "ceiling": "PG", "passes": true},
    {"name": "movie rated TV-PG fails on its own rating", "shape": "movie", "rating": "TV-PG", "ceiling": "PG", "passes": false},
    {"name": "unrated movie fails", "shape": "movie", "rating": null, "ceiling": "PG", "passes": false},
    {"name": "episode with no series ID, TV-Y, judged on its own rating", "shape": "episodeNoSeriesId", "rating": "TV-Y", "ceiling": "PG", "passes": true},
    {"name": "episode with no series ID, TV-PG, judged on its own rating", "shape": "episodeNoSeriesId", "rating": "TV-PG", "ceiling": "PG", "passes": false},
    {"name": "episode whose series is unknown fails closed, even rated TV-Y", "shape": "episodeSeriesUnknown", "rating": "TV-Y", "ceiling": "PG", "passes": false}
  ]
}
        """.trimIndent()
}
