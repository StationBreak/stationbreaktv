// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.playback.PlaybackTestData.audio
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import tv.stationbreak.core.playback.PlaybackTestData.subtitle
import tv.stationbreak.core.playback.PlaybackTestData.video
import kotlin.time.Duration

/** The B3 table of the design doc, cell by cell: what a change of track or version costs the picture. */
class SwitchCostTest {
    private val caps = insigniaLike()

    private fun playing(
        route: Route = Route.DIRECT_PLAY,
        chosenSubtitle: MediaStreamInfo? = null,
        subtitles: List<SeparateSubtitle> = emptyList(),
        streams: List<MediaStreamInfo> = listOf(video(), audio(1), audio(2, Codecs.AC3), audio(3, Codecs.DTS, 6)),
    ): ResolvedPlayback {
        val media = source(streams = streams)
        val hls = route != Route.DIRECT_PLAY
        return ResolvedPlayback(
            source = PlayerSource("u", hls, Duration.ZERO, subtitles, 1, null, directPlayTracks = !hls),
            route = route,
            playSession = null,
            media = media,
            chosenAudio = media.streams.first { it.index == 1 },
            chosenSubtitle = chosenSubtitle,
            requested = Duration.ZERO,
        )
    }

    private fun cost(
        playing: ResolvedPlayback,
        target: SwitchTarget,
    ) = switchCost(playing, target, caps)

    private fun embeddedText(index: Int) = subtitle(index).copy(positionInKind = 0)

    private fun externalText(index: Int) =
        subtitle(index, delivery = SubtitleDelivery.SEPARATE).copy(isExternal = true, positionInKind = null)

    private fun picture(index: Int) = subtitle(index, codec = "pgssub", delivery = SubtitleDelivery.BURNED_IN)

    @Test
    fun audioOnDirectPlayIsInstantWhenTheDeviceDecodesIt() {
        val p = playing()
        assertEquals(SwitchCost.Instant, cost(p, SwitchTarget.Audio(p.media.streams.first { it.index == 2 })))
    }

    @Test
    fun audioTheDeviceCannotDecodeRetunesBecauseTheRouteMovesToHls() {
        val p = playing()
        assertEquals(SwitchCost.Retune, cost(p, SwitchTarget.Audio(p.media.streams.first { it.index == 3 })))
    }

    @Test
    fun audioWhileTranscodingOrDirectStreamingRetunes() {
        for (route in listOf(Route.TRANSCODE, Route.DIRECT_STREAM)) {
            val p = playing(route)
            val target = SwitchTarget.Audio(p.media.streams.first { it.index == 2 })
            assertEquals(route.name, SwitchCost.Retune, cost(p, target))
        }
    }

    @Test
    fun anAudioTrackTheCannotBeFoundByPositionRetunes() {
        val p = playing()
        assertEquals(SwitchCost.Retune, cost(p, SwitchTarget.Audio(audio(2).copy(positionInKind = null))))
    }

    @Test
    fun subtitlesOffOnDirectPlayIsInstant() {
        assertEquals(SwitchCost.Instant, cost(playing(), SwitchTarget.Subtitle(null)))
    }

    @Test
    fun aTextSubtitleInTheContainerIsInstantOnDirectPlay() {
        assertEquals(SwitchCost.Instant, cost(playing(), SwitchTarget.Subtitle(embeddedText(4))))
    }

    @Test
    fun aTextSubtitleInTheContainerRetunesWhileTranscoding() {
        assertEquals(SwitchCost.Retune, cost(playing(Route.TRANSCODE), SwitchTarget.Subtitle(embeddedText(4))))
        assertEquals(SwitchCost.Retune, cost(playing(Route.DIRECT_STREAM), SwitchTarget.Subtitle(embeddedText(4))))
    }

    @Test
    fun subtitlesOffWhileTranscodingRetunes() {
        assertEquals(SwitchCost.Retune, cost(playing(Route.TRANSCODE), SwitchTarget.Subtitle(null)))
    }

    @Test
    fun aSeparateFileAlwaysRetunesEvenTheOneLoadedAtTheStart() {
        val loaded = externalText(5)
        val p = playing(chosenSubtitle = loaded, subtitles = listOf(SeparateSubtitle("u", "eng")))
        assertEquals("no in-place pick names it", SwitchCost.Retune, cost(p, SwitchTarget.Subtitle(loaded)))
        assertEquals(SwitchCost.Retune, cost(p, SwitchTarget.Subtitle(externalText(6))))
        assertEquals(SwitchCost.Retune, cost(playing(), SwitchTarget.Subtitle(externalText(5))))
    }

    @Test
    fun aPictureSubtitleAlwaysRetunes() {
        assertEquals(SwitchCost.Retune, cost(playing(), SwitchTarget.Subtitle(picture(4))))
        assertEquals(SwitchCost.Retune, cost(playing(Route.TRANSCODE), SwitchTarget.Subtitle(picture(4))))
    }

    @Test
    fun aVersionAlwaysRetunes() {
        assertEquals(SwitchCost.Retune, cost(playing(), SwitchTarget.Version(MediaSourceId("src2"))))
        assertEquals(SwitchCost.Retune, cost(playing(Route.TRANSCODE), SwitchTarget.Version(MediaSourceId("src2"))))
    }
}
