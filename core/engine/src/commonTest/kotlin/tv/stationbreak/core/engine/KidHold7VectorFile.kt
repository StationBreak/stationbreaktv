// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * The Kotlin copy of `docs/rules-format/vectors/kid-hold-7.json`, so the commonTest check (`KidHold7VectorTest`) can
 * run on linuxX64Test, which cannot read files. `KidHold7VectorInSyncTest` (jvmTest) fails if it differs from the
 * published file, and also runs the published file itself.
 */
internal object KidHold7VectorFile {
    val json: String =
        """
{
  "description": "The moves up of kid-hold-7 / grown-up-hold-6 (docs/adr/0029-tier-moves-up.md): a delta on kid-hold-6.json, which every kid-hold-7 port must still pass unchanged. Written by hand from the ADR text, not computed with an independent reference. Same 'tier' encoding as kid-hold-6.json, plus an optional 'providerIds' object on a 'series' (provider key to id, keys lower case). Rules, all moves up only: (1) a title whose tier would be family and that has a RatingTier.FAMILY_VETO_TAGS tag (mass murder, decapitation, child-death) or a RatingTier.FAMILY_VETO_GENRES genre (horror), on the item or its known series (folded, exact), is teen; a title that is kids stays kids; plain murder or killer tags do nothing; (2) TV Tokyo is not a kid studio; (3) a known series with a provider id equal to an entry of the hand-kept bump list (KidBumpList: the Ren & Stimpy Show, tvdb 76088, tmdb 504, imdb tt0101178) is family instead of kids, and the list never moves a title down (a TV-MA series with those ids is still adult). KidHold.admits and GrownUpHold.dropsFromGrownUp are true exactly when the tier is kids.",
  "tier": [
    {"name": "PG film tagged 'mass murder'", "shape": "movie", "rating": "PG", "tags": ["mass murder"], "tier": "teen"},
    {"name": "PG film tagged 'decapitation'", "shape": "movie", "rating": "PG", "tags": ["decapitation"], "tier": "teen"},
    {"name": "PG film tagged 'child-death'", "shape": "movie", "rating": "PG", "tags": ["child-death"], "tier": "teen"},
    {"name": "GP film tagged 'child-death'", "shape": "movie", "rating": "GP", "tags": ["child-death"], "tier": "teen"},
    {"name": "PG film tagged 'Mass Murder' (folded)", "shape": "movie", "rating": "PG", "tags": ["Mass Murder"], "tier": "teen"},
    {"name": "PG film tagged 'murder' only", "shape": "movie", "rating": "PG", "tags": ["murder"], "tier": "family"},
    {"name": "GP film tagged 'killer' only", "shape": "movie", "rating": "GP", "tags": ["killer"], "tier": "family"},
    {"name": "PG film tagged 'mass murder' and 'nudity'", "shape": "movie", "rating": "PG", "tags": ["mass murder", "nudity"], "tier": "adult"},
    {"name": "PG-13 film tagged 'mass murder'", "shape": "movie", "rating": "PG-13", "tags": ["mass murder"], "tier": "teen"},
    {"name": "TV-PG episode of a series tagged 'child-death'", "shape": "episode", "rating": "TV-PG", "series": {"rating": "TV-PG", "tags": ["child-death"]}, "tier": "teen"},
    {"name": "G film with a kids genre tagged 'child-death' (kids rule unchanged)", "shape": "movie", "rating": "G", "genres": ["Kids"], "tags": ["child-death"], "tier": "kids"},
    {"name": "TV-Y7 film from TV Tokyo alone", "shape": "movie", "rating": "TV-Y7", "studios": ["TV Tokyo"], "tier": "family"},
    {"name": "TV-Y7 film from TV Tokyo and 4Kids Entertainment", "shape": "movie", "rating": "TV-Y7", "studios": ["TV Tokyo", "4Kids Entertainment"], "tier": "kids"},
    {"name": "TV-Y7 film from TV Tokyo with a kids genre", "shape": "movie", "rating": "TV-Y7", "genres": ["Kids"], "studios": ["TV Tokyo"], "tier": "kids"},
    {"name": "episode of a listed series (tvdb 76088)", "shape": "episode", "rating": "TV-Y7", "series": {"rating": "TV-Y7", "genres": ["Animation", "Family"], "studios": ["Nickelodeon"], "providerIds": {"tvdb": "76088"}}, "tier": "family"},
    {"name": "episode of a listed series (tmdb 504)", "shape": "episode", "rating": "TV-Y7", "series": {"rating": "TV-Y7", "genres": ["Animation", "Family"], "studios": ["Nickelodeon"], "providerIds": {"tmdb": "504"}}, "tier": "family"},
    {"name": "episode of a listed series (imdb tt0101178)", "shape": "episode", "rating": "TV-Y7", "series": {"rating": "TV-Y7", "genres": ["Animation", "Family"], "studios": ["Nickelodeon"], "providerIds": {"imdb": "tt0101178"}}, "tier": "family"},
    {"name": "same series with no provider ids", "shape": "episode", "rating": "TV-Y7", "series": {"rating": "TV-Y7", "genres": ["Animation", "Family"], "studios": ["Nickelodeon"]}, "tier": "kids"},
    {"name": "same series with other ids", "shape": "episode", "rating": "TV-Y7", "series": {"rating": "TV-Y7", "genres": ["Animation", "Family"], "studios": ["Nickelodeon"], "providerIds": {"tvdb": "73287", "imdb": "tt0371475"}}, "tier": "kids"},
    {"name": "listed id under another key does not match", "shape": "episode", "rating": "TV-Y7", "series": {"rating": "TV-Y7", "genres": ["Animation", "Family"], "studios": ["Nickelodeon"], "providerIds": {"tvdb": "504"}}, "tier": "kids"},
    {"name": "TV-MA series carrying the listed ids (mis-scrape) stays adult", "shape": "episode", "rating": "TV-MA", "series": {"rating": "TV-MA", "genres": ["Animation", "Family"], "studios": ["Nickelodeon"], "providerIds": {"tvdb": "76088"}}, "tier": "adult"},
    {"name": "TV-14 series carrying the listed ids stays teen", "shape": "episode", "rating": "TV-14", "series": {"rating": "TV-14", "genres": ["Animation", "Family"], "studios": ["Nickelodeon"], "providerIds": {"tvdb": "76088"}}, "tier": "teen"},
    {"name": "unrated series carrying the listed ids stays adult", "shape": "episode", "rating": null, "series": {"rating": null, "genres": ["Animation", "Family"], "studios": ["Nickelodeon"], "providerIds": {"tvdb": "76088"}}, "tier": "adult"},
    {"name": "listed series that is also horror", "shape": "episode", "rating": "TV-Y7", "series": {"rating": "TV-Y7", "genres": ["Kids", "Horror"], "studios": ["Nickelodeon"], "providerIds": {"tvdb": "76088"}}, "tier": "teen"},
    {"name": "PG film, Horror", "shape": "movie", "rating": "PG", "genres": ["Horror"], "tier": "teen"},
    {"name": "GP film, Horror", "shape": "movie", "rating": "GP", "genres": ["Horror"], "tier": "teen"},
    {"name": "TV-PG film, Horror (folded)", "shape": "movie", "rating": "TV-PG", "genres": ["HORROR"], "tier": "teen"},
    {"name": "TV-Y film, Horror, no kids evidence", "shape": "movie", "rating": "TV-Y", "genres": ["Horror"], "tier": "teen"},
    {"name": "TV-PG episode of a Horror series", "shape": "episode", "rating": "TV-PG", "series": {"rating": "TV-PG", "genres": ["Horror"]}, "tier": "teen"},
    {"name": "PG film, Thriller and Mystery", "shape": "movie", "rating": "PG", "genres": ["Thriller", "Mystery"], "tier": "family"},
    {"name": "PG adventure film", "shape": "movie", "rating": "PG", "genres": ["Adventure"], "tier": "family"},
    {"name": "PG film, 'Horror comedy' is not Horror", "shape": "movie", "rating": "PG", "genres": ["Horror comedy"], "tier": "family"},
    {"name": "PG film, Horror and nudity", "shape": "movie", "rating": "PG", "genres": ["Horror"], "tags": ["nudity"], "tier": "adult"},
    {"name": "R film, Horror", "shape": "movie", "rating": "R", "genres": ["Horror"], "tier": "adult"},
    {"name": "PG-13 film, Horror", "shape": "movie", "rating": "PG-13", "genres": ["Horror"], "tier": "teen"},
    {"name": "G film, Kids and Horror genres (kids rule unchanged)", "shape": "movie", "rating": "G", "genres": ["Kids", "Horror"], "tier": "kids"}
  ]
}
        """.trimIndent()
}
