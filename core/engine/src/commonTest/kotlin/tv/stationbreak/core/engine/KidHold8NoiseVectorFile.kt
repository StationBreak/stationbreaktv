// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * The Kotlin copy of `docs/rules-format/vectors/kid-hold-8-noise.json` (#1088), so the commonTest check
 * (`KidHold8NoiseVectorTest`) can run on linuxX64Test, which cannot read files. `KidHold8NoiseVectorInSyncTest`
 * (jvmTest) fails if it differs from the published file, and also runs the published file itself.
 */
internal object KidHold8NoiseVectorFile {
    val json: String =
        """
{
  "description": "Input-noise tolerance of kid-hold-8 / grown-up-hold-7 (#1088): a delta on kid-hold-8.json that changes no meaning, only what noise around a rating, genre or tag no longer hides. Written by hand, not computed with an independent reference. Same encodings as kid-hold-8.json. Tidying: runs of U+0020 are one, the ends lose U+0020, and U+2010 to U+2015, U+2212, U+FE58, U+FE63 and U+FF0D are a hyphen. A rating is tidied so, after upper-casing ASCII a-z and before the rest of the cleaning of kid-hold-6 (so before the 'RATED ' prefix is dropped); a tab or a no-break space in a rating is NOT tidied (kid-hold-5.json pins it as present and off the ladder). A genre or tag is also tidied of every Unicode space separator (U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F, U+3000), tab, CR and LF, each read as U+0020 first; a rating that is still unknown and has a space inside is read once more with its spaces taken out; an explicit rating also matches with its spaces taken out (R 18+ is R18+). The tidied genre or tag is matched against the tier lists. 'noMetadata': a rating, genre or tag that is empty or only space-like characters (the tidied text is empty) counts as none.",
  "ratingMap": [
    {"rating": "NC\u201117", "level": 6},
    {"rating": "NC\u201317", "level": 6},
    {"rating": "NC\u201417", "level": 6},
    {"rating": "NC\u221217", "level": 6},
    {"rating": "NC  17", "level": null},
    {"rating": "PG\u201113", "level": 3},
    {"rating": "TV\u201114", "level": 4},
    {"rating": "TV\u2011MA", "level": 6},
    {"rating": "PG  -  13", "level": 3}
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
    {"rating": "R  15+", "explicit": false}
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
