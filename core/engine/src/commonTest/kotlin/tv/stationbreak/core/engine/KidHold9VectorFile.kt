// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * The Kotlin copy of `docs/rules-format/vectors/kid-hold-9.json` (#1088), so the commonTest check
 * (`KidHold9VectorTest`) can run on linuxX64Test, which cannot read files. `KidHold9VectorInSyncTest`
 * (jvmTest) fails if it differs from the published file, and also runs the published file itself.
 */
internal object KidHold9VectorFile {
    val json: String =
        """
{
  "description": "Input-noise tidy of kid-hold-9 / grown-up-hold-8 (#1088): a delta on kid-hold-8.json, which a kid-hold-9 port must still pass, and on kid-hold-6.json and kid-hold-7.json as kid-hold-8.json says, except the superseded rows named below. Written by hand, not computed with an independent reference. Same encodings as kid-hold-8.json. Rating tidy (RatingMap.levelOf and the clean step of isExplicit): after upper-casing ASCII a-z, runs of U+0020 are one, the ends lose U+0020, and U+2010 to U+2015, U+2212, U+FE58, U+FE63 and U+FF0D are a hyphen; then the rest of the cleaning of kid-hold-6 (so before the 'RATED ' prefix is dropped). A rating that is still unknown and has a space inside AND a letter is read once more with its spaces taken out (PG - 13 is PG-13); a key with no letter (0 7, 1 0) is not. An explicit rating also matches with its spaces taken out (R 18+ is R18+, NC 17 is NC17). ratingMap (levelOf) does NOT treat a tab or a no-break space as a space (kid-hold-5.json pins them as present and off the ladder), but 'explicitRating' (isExplicit) does: any Unicode space separator (U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F, U+3000), tab, CR and LF is first read as U+0020, so a tab or no-break space around or inside an Explicit rating does not hide it (fail up). Genres and tags: before they are matched against the tier lists, the folded name is tidied of every such space (as U+0020), runs of U+0020 and the ends, and the dash variants. 'noMetadata': a rating, genre or tag that is empty or only white space of the kind above counts as none (the tidied text is empty); this reads a tab- or no-break-space-only rating as absent, which the episode rule (EpisodeRating.isAbsent) and ratingMap do not, and that direction only drops more from a leftovers pool. SUPERSEDED rows of earlier files (kid-hold-8.json, explicitRating): {\"rating\": \"\\tX\", \"explicit\": false} is now true.",
  "ratingMap": [
    {"rating": "NC\u201117", "level": 6},
    {"rating": "NC\u201317", "level": 6},
    {"rating": "NC\u201417", "level": 6},
    {"rating": "NC\u221217", "level": 6},
    {"rating": "NC  17", "level": null},
    {"rating": "PG\u201113", "level": 3},
    {"rating": "TV\u201114", "level": 4},
    {"rating": "TV\u2011MA", "level": 6},
    {"rating": "PG  -  13", "level": 3},
    {"rating": "0 7", "level": null},
    {"rating": "1 0", "level": null},
    {"rating": "\tPG", "level": null},
    {"rating": "P G", "level": 2}
  ],
  "explicitRating": [
    {"rating": "NC\u201117", "explicit": true},
    {"rating": "NC\u201317", "explicit": true},
    {"rating": "NC\u221217", "explicit": true},
    {"rating": "NC 17", "explicit": true},
    {"rating": "R  18+", "explicit": true},
    {"rating": "R 18+", "explicit": true},
    {"rating": "R\u201118", "explicit": true},
    {"rating": "GB\u2011R18", "explicit": true},
    {"rating": "AU-X 18+", "explicit": true},
    {"rating": "Adults  Only", "explicit": true},
    {"rating": "R/NC\u201117", "explicit": true},
    {"rating": "R/TV\u2011MA", "explicit": false},
    {"rating": "R 17", "explicit": false},
    {"rating": "PG\u201113", "explicit": false},
    {"rating": "R  15+", "explicit": false},
    {"rating": "\tX", "explicit": true},
    {"rating": "\tNC-17", "explicit": true},
    {"rating": "NC-17\u00a0", "explicit": true},
    {"rating": "R18\u00a0", "explicit": true},
    {"rating": "R\u00a018+", "explicit": true},
    {"rating": "\u3000XXX\u3000", "explicit": true},
    {"rating": "R/NC\u201117", "explicit": true},
    {"rating": "0 7", "explicit": false},
    {"rating": "1 8", "explicit": false}
  ],
  "tier": [
    {"name": "NC-17 with a non-breaking hyphen", "shape": "movie", "rating": "NC\u201117", "tier": "explicit"},
    {"name": "R 18+ with two spaces", "shape": "movie", "rating": "R  18+", "tier": "explicit"},
    {"name": "explicit tag with a non-breaking hyphen", "shape": "movie", "rating": "TV-14", "tags": ["x\u2011rated"], "tier": "explicit"},
    {"name": "explicit tag with a doubled space", "shape": "movie", "rating": "TV-14", "tags": ["adult  film"], "tier": "explicit"},
    {"name": "explicit tag with a no-break space", "shape": "movie", "rating": "TV-14", "tags": ["erotic\u00a0movie"], "tier": "explicit"},
    {"name": "explicit tag with a tab at the end", "shape": "movie", "rating": "TV-14", "tags": ["softcore\t"], "tier": "explicit"},
    {"name": "explicit genre with a no-break space at the end", "shape": "movie", "rating": "TV-14", "genres": ["Hentai\u00a0"], "tier": "explicit"},
    {"name": "explicit genre with an en dash is not a genre", "shape": "movie", "rating": "TV-14", "genres": ["Hen\u2013tai"], "tier": "teen"},
    {"name": "plot tag with a doubled space stays teen", "shape": "movie", "rating": "TV-14", "tags": ["porn  industry"], "tier": "teen"},
    {"name": "adult tag with a no-break space", "shape": "movie", "rating": "PG", "tags": ["sex\u00a0comedy"], "tier": "adult"},
    {"name": "PG-13 with a non-breaking hyphen is teen", "shape": "movie", "rating": "PG\u201113", "tier": "teen"}
  ],
  "noMetadata": [
    {"name": "a tab for a rating", "shape": "movie", "rating": "\t", "dropped": true},
    {"name": "a no-break space for a rating", "shape": "movie", "rating": "\u00a0", "dropped": true},
    {"name": "an empty genre", "shape": "movie", "genres": [""], "dropped": true},
    {"name": "a blank genre", "shape": "movie", "genres": ["  ", "\u3000"], "dropped": true},
    {"name": "a blank tag", "shape": "movie", "tags": ["\t"], "dropped": true},
    {"name": "a blank rating, genre and tag", "shape": "movie", "rating": "  ", "genres": [" "], "tags": ["\u00a0"], "dropped": true},
    {"name": "a real genre next to a blank one", "shape": "movie", "genres": ["", "Drama"], "dropped": false},
    {"name": "a rating of NR is metadata", "shape": "movie", "rating": "NR", "dropped": false}
  ]
}
        """.trimIndent()
}
