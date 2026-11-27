// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * The Kotlin copy of `docs/rules-format/vectors/kid-hold-14.json` (#1006), so the commonTest check
 * (`KidHold14VectorTest`) can run on linuxX64Test, which cannot read files. `KidHold14VectorInSyncTest`
 * (jvmTest) fails if it differs from the published file, and also runs the published file itself.
 */
internal object KidHold14VectorFile {
    val json: String =
        """
{
  "description": "A minimum age of 17 is level 6 (Adult), a bare minimum age stays Family at least, and a dash variant reads plain in the animated-family signal (kid-hold-14 / grown-up-hold-14, #1006; docs/adr/0028-rating-tiers.md, addendum 2026-11-27): a delta on kid-hold-13.json, which a kid-hold-14 port must still pass except the superseded rows named below, and on every earlier file as kid-hold-13.json says. Written by hand from the ADR text, not computed with an independent reference. (1) Levels run 0 to 7 as in kid-hold-10.json. A minimum age of 17 or 17+ is level 6, not 5, bare (17, 17+) or with a system prefix (DE-17, DE-17+, and the 17 and 17+ of every country table of kid-hold-10.json); 15 and 16 stay level 5, and 18 stays 6. The age rungs are now 0, 1 to 7, 8 to 10, 11 to 13, 14, 15 to 16, and 17 and up. SUPERSEDED rows of kid-hold-6.json and kid-hold-10.json: ratingMap 17 and every XX-17 and XX-17+ row are level 6 (were 5). (2) A bare minimum age is Family at least until licensed country tables land (#987): 0, 6 and 7+ with kid evidence (a kids genre) are Family, never Kids; a system-qualified age or rating (DE-6, GB-U) keeps its level and can be Kids. Restated from kid-hold-10.json and kid-hold-13.json so the rule has its own rows. (3) A dash variant (en dash U+2013, em dash U+2014, minus sign U+2212) in a TV rating reads as the plain hyphen in the animated-family signal, as the rating map has always read it: an Animation and Family title rated TV\u2013Y is Kids, as TV-Y is. A title rated with a plain hyphen is unchanged.",
  "ratingMap": [
    {"rating": "17", "level": 6},
    {"rating": "17+", "level": 6},
    {"rating": "DE-17", "level": 6},
    {"rating": "DE-17+", "level": 6},
    {"rating": "DE:17", "level": 6},
    {"rating": "fr/17", "level": 6},
    {"rating": "15", "level": 5},
    {"rating": "16", "level": 5},
    {"rating": "16+", "level": 5},
    {"rating": "DE-16", "level": 5},
    {"rating": "18", "level": 6},
    {"rating": "0", "level": 2},
    {"rating": "6", "level": 2},
    {"rating": "7+", "level": 2},
    {"rating": "DE-6", "level": 1},
    {"rating": "DE-0", "level": 0},
    {"rating": "TV\u2013Y", "level": 0},
    {"rating": "TV\u2014Y7", "level": 1},
    {"rating": "NC-17", "level": 6}
  ],
  "explicitRating": [
    {"rating": "17", "explicit": false},
    {"rating": "DE-17", "explicit": false}
  ],
  "tier": [
    {"name": "bare 0 with a kids genre is family", "shape": "movie", "rating": "0", "genres": ["Kids"], "tier": "family"},
    {"name": "bare 6 with a kids genre is family", "shape": "movie", "rating": "6", "genres": ["Animation", "Kids"], "tier": "family"},
    {"name": "bare 7+ with a kids genre is family", "shape": "movie", "rating": "7+", "genres": ["Kids"], "tier": "family"},
    {"name": "DE-6 with a kids genre is kids", "shape": "movie", "rating": "DE-6", "genres": ["Kids"], "tier": "kids"},
    {"name": "bare 17 movie is adult", "shape": "movie", "rating": "17", "tier": "adult"},
    {"name": "DE-17+ movie is adult", "shape": "movie", "rating": "DE-17+", "tier": "adult"},
    {"name": "TV\u2013Y with Animation and Family is kids", "shape": "movie", "rating": "TV\u2013Y", "genres": ["Animation", "Family"], "tier": "kids"},
    {"name": "TV-Y with Animation and Family is kids", "shape": "movie", "rating": "TV-Y", "genres": ["Animation", "Family"], "tier": "kids"},
    {"name": "TV\u2013Y7 with Animation and Family is kids", "shape": "movie", "rating": "TV\u2013Y7", "genres": ["Animation", "Family"], "tier": "kids"},
    {"name": "TV\u2013G with Animation and Family is kids", "shape": "movie", "rating": "TV\u2013G", "genres": ["Animation", "Family"], "tier": "kids"},
    {"name": "TV\u2013PG with Animation and Family is not kids", "shape": "movie", "rating": "TV\u2013PG", "genres": ["Animation", "Family"], "tier": "family"}
  ],
  "passesKidCeiling": [
    {"name": "bare 7+ under TV-Y7", "rating": "7+", "ceiling": "TV-Y7", "passes": false},
    {"name": "bare 0 under TV-Y7", "rating": "0", "ceiling": "TV-Y7", "passes": false},
    {"name": "DE-6 under TV-Y7", "rating": "DE-6", "ceiling": "TV-Y7", "passes": true}
  ],
  "customCeiling": [
    {"name": "bare 17 movie, R ceiling, grown-up", "policy": "grownUp", "shape": "movie", "rating": "17", "ceiling": "R", "passes": false},
    {"name": "DE-17 movie, R ceiling, custom", "policy": "custom", "shape": "movie", "rating": "DE-17", "ceiling": "R", "passes": false},
    {"name": "DE-17 movie, TV-MA ceiling, custom", "policy": "custom", "shape": "movie", "rating": "DE-17", "ceiling": "TV-MA", "passes": true},
    {"name": "bare 17+ movie, TV-MA ceiling, grown-up", "policy": "grownUp", "shape": "movie", "rating": "17+", "ceiling": "TV-MA", "passes": true},
    {"name": "DE-16 movie, R ceiling, custom", "policy": "custom", "shape": "movie", "rating": "DE-16", "ceiling": "R", "passes": true}
  ]
}
        """.trimIndent()
}
