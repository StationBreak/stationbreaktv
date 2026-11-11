// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.TrackFlags
import tv.stationbreak.core.playback.AudioFacts
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.SourceFacts
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.playback.SubtitleFacts
import tv.stationbreak.core.playback.SubtitleForm
import tv.stationbreak.core.playback.SwitchCost
import tv.stationbreak.core.playback.TrackPick
import tv.stationbreak.core.playback.VideoFacts

/** The facts of a play with three audio tracks and three subtitle tracks, for the switch tests. */
internal object SwitchFixtures {
    val SOURCE = MediaSourceId("src")
    val ENGLISH =
        AudioFacts(1, "eng", "ac3", 6, null, true, TrackFlags(), SwitchCost.Instant, TrackPick(0, "eng", "ac3"))
    val SPANISH =
        AudioFacts(2, "spa", "ac3", 6, null, false, TrackFlags(), SwitchCost.Instant, TrackPick(1, "spa", "ac3"))

    /** A track the player cannot decode: it re-tunes. */
    val FRENCH = AudioFacts(3, "fra", "dts", 6, null, false, TrackFlags(), SwitchCost.Retune)
    val COMMENTARY =
        AudioFacts(4, "eng", "ac3", 2, "Commentary", false, TrackFlags(commentary = true), SwitchCost.Retune)
    val ENGLISH_SUBS =
        SubtitleFacts(
            5,
            "eng",
            "subrip",
            SubtitleForm.Text,
            null,
            false,
            false,
            TrackFlags(),
            SwitchCost.Instant,
            TrackPick(0, "eng", "subrip"),
        )
    val ENGLISH_SDH =
        SubtitleFacts(
            6,
            "eng",
            "subrip",
            SubtitleForm.Text,
            "SDH",
            false,
            false,
            TrackFlags(sdh = true),
            SwitchCost.Instant,
            TrackPick(1, "eng", "subrip"),
        )

    /** A picture subtitle: burned in, so it re-tunes. */
    val SPANISH_PICTURE =
        SubtitleFacts(
            7,
            "spa",
            "pgssub",
            SubtitleForm.Picture,
            null,
            false,
            false,
            TrackFlags(),
            SwitchCost.Retune,
        )

    /** A second version of the item, as the server lists it: another picture, another name. */
    val ALT = MediaSourceId("alt")

    /** The playing version [SOURCE] and the item's other version [ALT], each with its own tracks. */
    fun twoVersions(): StreamFacts {
        val playing = facts()
        val other = SourceFacts(ALT, "Theatrical", VideoFacts(1280, 720, "h264"), listOf(ENGLISH), emptyList())
        return playing.copy(sources = playing.sources + other)
    }

    fun facts(
        audioIndex: Int? = 1,
        subtitleIndex: Int? = null,
        audio: List<AudioFacts> = listOf(ENGLISH, SPANISH, FRENCH, COMMENTARY),
        subtitles: List<SubtitleFacts> = listOf(ENGLISH_SUBS, ENGLISH_SDH, SPANISH_PICTURE),
        route: Route = Route.DIRECT_PLAY,
    ) = StreamFacts(
        route = route,
        sourceId = SOURCE,
        audioIndex = audioIndex,
        subtitleIndex = subtitleIndex,
        sources = listOf(SourceFacts(SOURCE, null, VideoFacts(1920, 1080, "hevc"), audio, subtitles)),
        offCost = SwitchCost.Instant,
    )
}
