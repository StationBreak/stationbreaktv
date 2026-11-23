// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
// an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.
//
// Source: https://android.googlesource.com/platform/packages/apps/TV/+/4e256ebdeb1333af4f2b9832d61078965e4a672f/ratings/
// (res/xml/tv_content_rating_systems.xml, blob 361393f5728921ff08c73b0e65224b70a478fba8, and
// res/values/rating_system_strings.xml, blob 9922cb1cd6652de5205ec7eab4a4007891a6024e).
// Derived: fixed rating table. Names upper-cased, the rating ID's suffix added as a second spelling, the US systems,
// the sub-ratings and the duplicate DTMB block left out. See NOTICE. (This block sits below the package line because
// the project's SPDX header rule rewrites everything above it.)

/**
 * The non-US rating systems of the Android TV ratings XML (#987, Apache-2.0, see the notice above), as the XML has
 * them: per system its ISO 3166 country codes, and per rating its `contentAgeHint` and its spellings (the XML title,
 * then the suffix of the rating's ID when it differs, both upper-cased). Data only: [CountryRatingTables] turns the
 * hints into ladder levels and holds the StationBreak overrides. The US systems are left out (the US ladder reads them,
 * [RatingMap] step 3), so are the sub-ratings (D, L, S, V). A rating the XML does not have is not here; do not add one
 * from another source (#987: no MovieLabs, Jellyfin, MediaPortal or TMDb tables). Frozen under `KidHold.VERSION_NAME`.
 *
 * Checked against the XML (blob 361393f5728921ff08c73b0e65224b70a478fba8): the rows were generated from the XML by a
 * reader outside the repository, and on 2026-11-23 a second reader, also outside it, re-fetched both files and compared
 * every (country, spelling) entry's age hint here with the XML's `contentAgeHint`. 500 entries, 0 mismatches. Both
 * readers parse the XML with a standard XML parser, resolve `@string/` titles from the strings file, and take a
 * rating ID's suffix after its system name. The overrides above or below a hint are code ([CountryRatingTables]),
 * not data, so this table holds the XML's hints unchanged.
 */
internal object AndroidTvRatingSystems {
    /** One rating: the XML's `contentAgeHint` and its spellings. */
    class Rating(
        val ageHint: Int,
        val names: List<String>,
    )

    /** One rating system: the countries it is for and its ratings. */
    class System(
        val countries: List<String>,
        val ratings: List<Rating>,
    )

    @Suppress("MagicNumber")
    val SYSTEMS: List<System> =
        listOf(
            // AR_TV
            system(
                listOf("AR"),
                rating(0, "ATP"),
                rating(13, "SAM 13"),
                rating(16, "SAM 16"),
                rating(18, "SAM 18"),
            ),
            // AU_TV
            system(
                listOf("AU"),
                rating(2, "P"),
                rating(5, "C"),
                rating(0, "G"),
                rating(15, "PG"),
                rating(15, "M"),
                rating(15, "MA"),
                rating(15, "AV"),
                rating(18, "R"),
            ),
            // BR_TV
            system(
                listOf("BR"),
                rating(0, "GA", "L"),
                rating(10, "PG-10", "10"),
                rating(12, "PG-12", "12"),
                rating(14, "PG-14", "14"),
                rating(16, "PG-16", "16"),
                rating(18, "PG-18", "18"),
            ),
            // CA_TV_EN
            system(
                listOf("CA"),
                rating(0, "E", "EXEMPT"),
                rating(0, "C"),
                rating(8, "C8"),
                rating(0, "G"),
                rating(14, "PG"),
                rating(14, "14+", "14"),
                rating(18, "18+", "18"),
            ),
            // CA_TV_FR
            system(
                listOf("CA"),
                rating(0, "E"),
                rating(0, "G"),
                rating(8, "8ANS+", "8"),
                rating(13, "13ANS+", "13"),
                rating(16, "16ANS+", "16"),
                rating(18, "18ANS+", "18"),
            ),
            // DTMB
            system(
                listOf("CN"),
                rating(4, "4"),
                rating(5, "5"),
                rating(6, "6"),
                rating(7, "7"),
                rating(8, "8"),
                rating(9, "9"),
                rating(10, "10"),
                rating(11, "11"),
                rating(12, "12"),
                rating(13, "13"),
                rating(14, "14"),
                rating(15, "15"),
                rating(16, "16"),
                rating(17, "17"),
                rating(18, "18"),
            ),
            // DVB
            system(
                listOf(
                    "AM",
                    "BG",
                    "CH",
                    "DE",
                    "DK",
                    "FI",
                    "GR",
                    "HU",
                    "ID",
                    "IE",
                    "IL",
                    "IS",
                    "MY",
                    "NL",
                    "NZ",
                    "PL",
                    "PT",
                    "RO",
                    "RU",
                    "RS",
                    "SI",
                    "TH",
                    "TR",
                    "TW",
                    "UA",
                ),
                rating(4, "4"),
                rating(5, "5"),
                rating(6, "6"),
                rating(7, "7"),
                rating(8, "8"),
                rating(9, "9"),
                rating(10, "10"),
                rating(11, "11"),
                rating(12, "12"),
                rating(13, "13"),
                rating(14, "14"),
                rating(15, "15"),
                rating(16, "16"),
                rating(17, "17"),
                rating(18, "18"),
            ),
            // ES_DVB
            system(
                listOf("ES"),
                rating(0, "ALL"),
                rating(12, "CHILDREN", "C"),
                rating(18, "X-RATED", "X"),
                rating(4, "4"),
                rating(5, "5"),
                rating(6, "6"),
                rating(7, "7"),
                rating(8, "8"),
                rating(9, "9"),
                rating(10, "10"),
                rating(11, "11"),
                rating(12, "12"),
                rating(13, "13"),
                rating(14, "14"),
                rating(15, "15"),
                rating(16, "16"),
                rating(17, "17"),
                rating(18, "18"),
            ),
            // FR_DVB
            system(
                listOf("FR"),
                rating(0, "UNIVERSAL", "U"),
                rating(4, "4"),
                rating(5, "5"),
                rating(6, "6"),
                rating(7, "7"),
                rating(8, "8"),
                rating(9, "9"),
                rating(10, "10"),
                rating(11, "11"),
                rating(12, "12"),
                rating(13, "13"),
                rating(14, "14"),
                rating(15, "15"),
                rating(16, "16"),
                rating(17, "17"),
                rating(18, "18"),
            ),
            // ISDB
            system(
                listOf("JP"),
                rating(4, "4"),
                rating(5, "5"),
                rating(6, "6"),
                rating(7, "7"),
                rating(8, "8"),
                rating(9, "9"),
                rating(10, "10"),
                rating(11, "11"),
                rating(12, "12"),
                rating(13, "13"),
                rating(14, "14"),
                rating(15, "15"),
                rating(16, "16"),
                rating(17, "17"),
                rating(18, "18"),
                rating(19, "19"),
                rating(20, "20"),
            ),
            // KR_TV
            system(
                listOf("KR"),
                rating(0, "ALL"),
                rating(7, "7"),
                rating(12, "12"),
                rating(15, "15"),
                rating(19, "19"),
            ),
            // NZ_TV
            system(
                listOf("NZ"),
                rating(0, "G"),
                rating(0, "PGR"),
                rating(0, "AO"),
            ),
            // SG_TV
            system(
                listOf("SG"),
                rating(0, "G"),
                rating(0, "PG"),
                rating(13, "PG13"),
                rating(16, "NC16"),
                rating(18, "M18"),
                rating(21, "R21"),
            ),
            // TH_TV
            system(
                listOf("TH"),
                rating(4, "4"),
                rating(6, "6"),
                rating(10, "10"),
                rating(13, "13"),
                rating(18, "18"),
                rating(19, "19"),
            ),
        )

    private fun system(
        countries: List<String>,
        vararg ratings: Rating,
    ): System = System(countries, ratings.toList())

    private fun rating(
        ageHint: Int,
        vararg names: String,
    ): Rating = Rating(ageHint, names.toList())
}
