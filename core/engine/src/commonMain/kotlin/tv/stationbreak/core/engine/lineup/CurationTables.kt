// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/**
 * Name tables the curation reads, frozen under [VERSION_NAME]: a change to either is a new version. Studio and network
 * names are allowed in this file only.
 */
object CurationTables {
    /** The version name of both tables. Later code (day inputs, the curation digest) refers to this constant. */
    const val VERSION_NAME: String = "curation-1"

    /**
     * Studios and networks that mark an animated title as anime. A one-word name is a whole-word match, so a short
     * name does not match inside a longer one; a name of several words is a substring match.
     */
    val ANIME_STUDIOS: List<NameMatcher> =
        listOf(
            NameMatcher.Contains("Tokyo MX"),
            NameMatcher.Word("AT-X"),
            NameMatcher.Word("Nippon"),
            NameMatcher.Contains("Fuji TV"),
            NameMatcher.Contains("TV Tokyo"),
            NameMatcher.Word("Aniplex"),
            NameMatcher.Word("Sunrise"),
            NameMatcher.Contains("Toei Animation"),
            NameMatcher.Word("Madhouse"),
            NameMatcher.Contains("Production I.G"),
            NameMatcher.Word("Bones"),
            NameMatcher.Word("MAPPA"),
            NameMatcher.Contains("Kyoto Animation"),
            NameMatcher.Word("Tatsunoko"),
            NameMatcher.Word("KADOKAWA"),
            NameMatcher.Word("Crunchyroll"),
            NameMatcher.Contains("Studio Ghibli"),
            NameMatcher.Word("Tokuma"),
            NameMatcher.Word("TMS"),
            NameMatcher.Word("Pierrot"),
            NameMatcher.Contains("Kinema Citrus"),
            NameMatcher.Word("OLM"),
            NameMatcher.Word("Gonzo"),
            NameMatcher.Word("Shaft"),
            NameMatcher.Contains("Wit Studio"),
            NameMatcher.Contains("A-1 Pictures"),
            NameMatcher.Contains("J.C.Staff"),
            NameMatcher.Contains("Studio Bind"),
            NameMatcher.Word("BS11"),
        )

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
