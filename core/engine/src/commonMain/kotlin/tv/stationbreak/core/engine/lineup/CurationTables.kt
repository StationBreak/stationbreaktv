// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/**
 * Name tables the curation reads, frozen under [VERSION_NAME]: a change to either is a new version. Studio and network
 * names are allowed in this file only.
 */
object CurationTables {
    const val VERSION_NAME: String = "curation-1"

    /** Studios and networks that mark an animated title as anime. */
    val ANIME_STUDIOS: List<NameMatcher> =
        listOf(
            "Tokyo MX",
            "AT-X",
            "Nippon",
            "Fuji TV",
            "TV Tokyo",
            "Aniplex",
            "Sunrise",
            "Toei Animation",
            "Madhouse",
            "Production I.G",
            "Bones",
            "MAPPA",
            "Kyoto Animation",
            "Tatsunoko",
            "KADOKAWA",
            "Crunchyroll",
            "Studio Ghibli",
            "Tokuma",
            "TMS",
            "Pierrot",
            "Kinema Citrus",
            "OLM",
            "Gonzo",
            "Shaft",
            "Wit Studio",
            "A-1 Pictures",
            "J.C.Staff",
            "Studio Bind",
            "BS11",
        ).map { NameMatcher.Contains(it) }

    /** Networks whose animation is for grown-ups. */
    val ADULT_ANIMATION_NETWORKS: List<NameMatcher> =
        listOf(
            NameMatcher.Contains("Adult Swim"),
            NameMatcher.Contains("Comedy Central"),
            NameMatcher.Word("FX"),
            NameMatcher.Word("FXX"),
            NameMatcher.Contains("MTV"),
            NameMatcher.Contains("Netflix"),
        )
}
