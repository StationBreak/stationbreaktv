// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.playback.PlaybackTestData.audio
import tv.stationbreak.core.playback.PlaybackTestData.source
import tv.stationbreak.core.playback.PlaybackTestData.subtitle
import tv.stationbreak.core.playback.PlaybackTestData.video
import kotlin.time.Duration

class StreamFactsTest {
    private val commentary = audio(2, Codecs.AC3, 6).copy(title = "Director's Commentary", isDefault = false)
    private val text = subtitle(3).copy(displayTitle = "English SDH")
    private val picture = subtitle(4, codec = "pgssub")
    private val main = source(streams = listOf(video(), audio(1), commentary, text, picture))
    private val other =
        main.copy(
            id = MediaSourceId("src2"),
            name = "Director's Cut",
            streams = listOf(video(width = 3840, height = 2160), audio(1)),
        )

    private fun resolved(sources: List<tv.stationbreak.core.model.MediaSource>) =
        ResolvedPlayback(
            source = PlayerSource("u", false, Duration.ZERO, emptyList(), 1, 3, directPlayTracks = true),
            route = Route.DIRECT_PLAY,
            playSession = null,
            media = main,
            chosenAudio = main.streams.first { it.index == 1 },
            chosenSubtitle = text,
            requested = Duration.ZERO,
            sources = sources,
        )

    @Test
    fun theFactsNameTheRouteTheVersionAndTheTracksInUse() {
        val facts = resolved(listOf(main, other)).facts()
        assertEquals(Route.DIRECT_PLAY, facts.route)
        assertEquals(MediaSourceId("src1"), facts.sourceId)
        assertEquals(1, facts.audioIndex)
        assertEquals(3, facts.subtitleIndex)
        assertEquals(1, facts.audio?.index)
        assertEquals(3, facts.subtitle?.index)
    }

    @Test
    fun everyVersionTheServerOfferedIsSummarised() {
        val facts = resolved(listOf(main, other)).facts()
        assertEquals(listOf(MediaSourceId("src1"), MediaSourceId("src2")), facts.sources.map { it.id })
        val second = facts.sources[1]
        assertEquals("Director's Cut", second.name)
        assertEquals(VideoFacts(3840, 2160, Codecs.H264), second.video)
        assertEquals(facts.playing, facts.sources[0])
    }

    @Test
    fun aResultBuiltWithoutTheWholeAnswerStillListsTheChosenVersion() {
        assertEquals(listOf(MediaSourceId("src1")), resolved(emptyList()).facts().sources.map { it.id })
    }

    @Test
    fun trackSummariesCarryTheServersTitleAndTheFlags() {
        val playing = checkNotNull(resolved(listOf(main)).facts().playing)
        val extra = playing.audio.single { it.index == 2 }
        assertEquals("Director's Commentary", extra.title)
        assertTrue(extra.flags.commentary)
        assertTrue(
            playing.subtitles
                .single { it.index == 3 }
                .flags.sdh,
        )
    }

    @Test
    fun aSubtitleIsATextOrAPictureByItsCodec() {
        val playing = checkNotNull(resolved(listOf(main)).facts().playing)
        assertEquals(SubtitleForm.Text, playing.subtitles.single { it.index == 3 }.form)
        assertEquals(SubtitleForm.Picture, playing.subtitles.single { it.index == 4 }.form)
        val odd = main.copy(streams = listOf(subtitle(9, codec = "teletext")))
        assertEquals(SubtitleForm.Other, checkNotNull(resolved(listOf(odd)).facts().playing).subtitles.single().form)
    }

    @Test
    fun noSubtitleInUseIsNull() {
        val facts = resolved(listOf(main)).copy(chosenSubtitle = null).facts()
        assertNull(facts.subtitleIndex)
        assertNull(facts.subtitle)
    }
    // ---- the switch cost is decided here, so a screen never calls switchCost (concern 1) ----

    private val device = PlaybackTestData.insigniaLike()
    private val picture2 = subtitle(6, codec = "pgssub")
    private val dts = audio(7, Codecs.DTS, 6)
    private val tracks = source(streams = listOf(video(), audio(1), audio(2, Codecs.AC3), dts, text, picture2))

    private fun playingOn(
        route: Route,
        media: tv.stationbreak.core.model.MediaSource = tracks,
        sources: List<tv.stationbreak.core.model.MediaSource> = listOf(media),
        limits: tv.stationbreak.core.model.DeviceCapabilities? = device,
    ): ResolvedPlayback {
        val hls = route != Route.DIRECT_PLAY
        return ResolvedPlayback(
            source = PlayerSource("u", hls, Duration.ZERO, emptyList(), 1, null, directPlayTracks = !hls),
            route = route,
            playSession = null,
            media = media,
            chosenAudio = media.streams.first { it.index == 1 },
            chosenSubtitle = null,
            requested = Duration.ZERO,
            sources = sources,
            limits = limits,
        )
    }

    private fun StreamFacts.audioCost(index: Int) = checkNotNull(playing).audio.single { it.index == index }.cost

    private fun StreamFacts.subtitleCost(index: Int) = checkNotNull(playing).subtitles.single { it.index == index }.cost

    @Test
    fun aTrackIsInstantOnlyWhenThePlayerCanBeToldWhichOneToShow() {
        val facts = playingOn(Route.DIRECT_PLAY).facts()
        val subtitles = checkNotNull(facts.playing).subtitles
        assertTrue(
            "an instant subtitle row must carry the pick its in-place switch needs",
            subtitles.filter { it.cost == SwitchCost.Instant }.all { it.pick != null },
        )
        val audio = checkNotNull(facts.playing).audio
        assertTrue(audio.filter { it.cost == SwitchCost.Instant }.all { it.pick != null })
    }

    @Test
    fun theTitleIsTheFilesOwnNotTheServersGeneratedDisplayTitle() {
        val first = tracks.streams.first { it.kind == StreamKind.SUBTITLE }
        val stream = first.copy(title = null, displayTitle = "English - SUBRIP")
        val media = tracks.copy(streams = tracks.streams.map { if (it.index == stream.index) stream else it })
        val shown = checkNotNull(playingOn(Route.DIRECT_PLAY, media).facts().playing)
        assertNull(shown.subtitles.single { it.index == stream.index }.title)
    }

    @Test
    fun onDirectPlayEachTrackCarriesItsOwnCost() {
        val facts = playingOn(Route.DIRECT_PLAY).facts()
        assertEquals(SwitchCost.Instant, facts.audioCost(2))
        assertEquals("DTS cannot be decoded here: the route would move to HLS", SwitchCost.Retune, facts.audioCost(7))
        assertEquals(SwitchCost.Instant, facts.subtitleCost(3))
        assertEquals("a picture subtitle is burned in", SwitchCost.Retune, facts.subtitleCost(6))
        assertEquals(SwitchCost.Instant, facts.offCost)
    }

    @Test
    fun whileTranscodingEverythingRetunes() {
        val facts = playingOn(Route.TRANSCODE).facts()
        assertEquals(SwitchCost.Retune, facts.audioCost(2))
        assertEquals(SwitchCost.Retune, facts.subtitleCost(3))
        assertEquals(SwitchCost.Retune, facts.offCost)
    }

    @Test
    fun everyVersionRetunesAndSoDoTheTracksOfAnotherVersion() {
        val facts = playingOn(Route.DIRECT_PLAY, sources = listOf(tracks, other)).facts()
        assertTrue(facts.sources.all { it.cost == SwitchCost.Retune })
        val elsewhere = facts.sources.single { it.id == other.id }
        assertTrue(elsewhere.audio.all { it.cost == SwitchCost.Retune })
    }

    @Test
    fun aResultBuiltWithoutTheDeviceSaysRetuneForEverythingTheSafeAnswer() {
        val facts = playingOn(Route.DIRECT_PLAY, limits = null).facts()
        assertEquals(SwitchCost.Retune, facts.audioCost(2))
        assertEquals(SwitchCost.Retune, facts.subtitleCost(3))
        assertEquals(SwitchCost.Retune, facts.offCost)
    }
}
