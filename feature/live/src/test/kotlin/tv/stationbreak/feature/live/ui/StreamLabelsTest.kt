// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.TrackFlags
import tv.stationbreak.core.playback.AudioFacts
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.SourceFacts
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.playback.SubtitleFacts
import tv.stationbreak.core.playback.SubtitleForm
import tv.stationbreak.core.playback.VideoFacts
import tv.stationbreak.feature.live.PickerRow
import java.util.Locale

/** The stream line and the picker rows (design doc B1, B2.8), read against the shipped `strings.xml`. */
class StreamLabelsTest {
    private val copy = StringsXml.streamCopy()

    private val names = mapOf("eng" to "English", "spa" to "Spanish", "jpn" to "Japanese")
    private val language: (String?) -> String? = { names[it] }
    private val commentary = TrackFlags(commentary = true)

    private fun audio(
        index: Int,
        language: String? = "eng",
        channels: Int? = 6,
        codec: String? = "ac3",
        flags: TrackFlags = TrackFlags(),
    ) = AudioFacts(index, language, codec, channels, null, false, flags)

    private fun subtitle(
        index: Int,
        language: String? = "eng",
        form: SubtitleForm = SubtitleForm.Text,
        flags: TrackFlags = TrackFlags(),
        title: String? = null,
    ) = SubtitleFacts(index, language, "subrip", form, title, false, false, flags)

    private fun version(
        id: String = "src1",
        name: String? = null,
        video: VideoFacts? = VideoFacts(1920, 1080, "hevc"),
        audio: List<AudioFacts> = listOf(audio(1)),
        subtitles: List<SubtitleFacts> = listOf(subtitle(3)),
    ) = SourceFacts(MediaSourceId(id), name, video, audio, subtitles)

    private fun facts(
        source: SourceFacts = version(),
        route: Route = Route.DIRECT_PLAY,
        audioIndex: Int? = 1,
        subtitleIndex: Int? = null,
    ) = StreamFacts(route, source.id, audioIndex, subtitleIndex, listOf(source))

    private fun line(facts: StreamFacts) = StreamLabels.streamLine(facts, copy, language)

    // --- the stream line ---

    @Test
    fun theStreamLineSaysThePictureTheAudioAndTheSubtitles() {
        assertEquals("1080p HEVC · English 5.1 · Subtitles off", line(facts()))
    }

    @Test
    fun aSubtitleInUseNamesItsLanguage() {
        assertEquals(
            "1080p HEVC · English 5.1 · Subtitles Spanish",
            line(facts(version(subtitles = listOf(subtitle(3, "spa"))), subtitleIndex = 3)),
        )
    }

    @Test
    fun aVersionWithNoSubtitleTracksSaysSo() {
        assertEquals("1080p HEVC · English 5.1 · No subtitles", line(facts(version(subtitles = emptyList()))))
    }

    @Test
    fun aTranscodeStartsWithTranscodingAndTheOtherRoutesSayNothing() {
        assertEquals("Transcoding · 1080p HEVC · English 5.1 · Subtitles off", line(facts(route = Route.TRANSCODE)))
        assertEquals(line(facts()), line(facts(route = Route.DIRECT_STREAM)))
    }

    @Test
    fun aPartTheFactsDoNotKnowIsLeftOut() {
        assertEquals("English 5.1 · Subtitles off", line(facts(version(video = null))))
        assertEquals("1080p HEVC · Subtitles off", line(facts(audioIndex = null)))
        assertEquals(
            "1080p HEVC · Unknown · Subtitles off",
            line(facts(version(audio = listOf(audio(1, language = null, channels = null))))),
        )
    }

    @Test
    fun anAudioTrackWithNoLayoutShowsOnlyItsLanguage() {
        val stereoless = version(audio = listOf(audio(1, channels = null)))
        assertEquals("1080p HEVC · English · Subtitles off", line(facts(stereoless)))
    }

    // --- the audio rows ---

    @Test
    fun anAudioRowIsLanguageThenFlagsThenLayoutAndCodec() {
        assertEquals("English · 5.1 · AC3", StreamLabels.audioRow(audio(1), copy, language))
        assertEquals(
            "English · Commentary · 2.0 · AAC",
            StreamLabels.audioRow(audio(2, channels = 2, codec = "aac", flags = commentary), copy, language),
        )
    }

    @Test
    fun anAudioRowWithNoLanguageSaysUnknown() {
        assertEquals("Unknown · 5.1 · AC3", StreamLabels.audioRow(audio(1, language = null), copy, language))
        assertEquals("Unknown · 5.1 · AC3", StreamLabels.audioRow(audio(1, language = "und"), copy, language))
    }

    @Test
    fun aCodecWeDoNotNameIsShownInCapitals() {
        assertEquals("English · 5.1 · XYZ", StreamLabels.audioRow(audio(1, codec = "xyz"), copy, language))
        assertEquals("English · 5.1", StreamLabels.audioRow(audio(1, codec = null), copy, language))
    }

    // --- the subtitle rows ---

    @Test
    fun aSubtitleRowIsLanguageThenFlagsThenTextOrPicture() {
        val forcedPicture = subtitle(4, "jpn", SubtitleForm.Picture, TrackFlags(forced = true))
        assertEquals("English · Text", StreamLabels.subtitleRow(subtitle(3), copy, language))
        val sdh = subtitle(3, flags = TrackFlags(sdh = true))
        assertEquals("English · SDH · Text", StreamLabels.subtitleRow(sdh, copy, language))
        assertEquals(
            "Japanese · Forced · Picture",
            StreamLabels.subtitleRow(forcedPicture, copy, language),
        )
    }

    @Test
    fun aSubtitleOfNoKnownFormSaysNothingOfIt() {
        assertEquals("English", StreamLabels.subtitleRow(subtitle(3, form = SubtitleForm.Other), copy, language))
    }

    @Test
    fun theFirstSubtitleRowIsOffNeverNone() {
        assertEquals("Off", StreamLabels.offRow(copy))
    }

    // --- the version rows ---

    @Test
    fun aVersionRowIsResolutionCodecAndEditionName() {
        assertEquals("1080p · HEVC · Director's Cut", StreamLabels.versionRow(version(name = "Director's Cut"), copy))
        assertEquals("2160p · H.264", StreamLabels.versionRow(version(video = VideoFacts(3840, 2160, "h264")), copy))
    }

    @Test
    fun aVersionWithNothingToSayIsUnknown() {
        assertEquals("Unknown", StreamLabels.versionRow(version(video = null), copy))
        assertEquals("Theatrical", StreamLabels.versionRow(version(video = null, name = "Theatrical"), copy))
    }

    // --- the figures ---

    @Test
    fun aCroppedFilmIsLabelledByItsWidthTier() {
        assertEquals("1080p", StreamFigures.resolution(1920, 800))
        assertEquals("1080p", StreamFigures.resolution(1920, 1080))
        assertEquals("1080p", StreamFigures.resolution(1440, 1080))
        assertEquals("720p", StreamFigures.resolution(1280, 544))
        assertEquals("2160p", StreamFigures.resolution(3840, 1600))
        assertEquals("1440p", StreamFigures.resolution(2560, 1440))
    }

    @Test
    fun aSmallPictureSaysItsOwnHeight() {
        assertEquals("480p", StreamFigures.resolution(720, 480))
        assertEquals("576p", StreamFigures.resolution(720, 576))
        assertEquals("1080p", StreamFigures.resolution(null, 1080))
        assertEquals("720p", StreamFigures.resolution(1280, null))
        assertNull(StreamFigures.resolution(null, null))
        assertNull(StreamFigures.resolution(0, 0))
    }

    @Test
    fun layoutsReadAsTheViewerKnowsThem() {
        assertEquals(
            listOf("1.0", "2.0", "2.1", "5.1", "6.1", "7.1", "12ch"),
            listOf(1, 2, 3, 6, 7, 8, 12).map { StreamFigures.layout(it) },
        )
        assertNull(StreamFigures.layout(null))
        assertNull(StreamFigures.layout(0))
    }

    @Test
    fun languageNamesComeFromTheLocaleAndFallBackToTheCode() {
        assertEquals("English", languageName("eng", Locale.ENGLISH))
        assertEquals("German", languageName("ger", Locale.ENGLISH))
        assertEquals("XYZ", languageName("xyz", Locale.ENGLISH))
        assertNull(languageName("und", Locale.ENGLISH))
        assertNull(languageName(null, Locale.ENGLISH))
    }

    // --- twin rows ---

    private fun subtitleTexts(vararg tracks: SubtitleFacts): List<String> =
        StreamLabels.pickerTexts(tracks.map { PickerRow.Subtitle(it, playing = false) }, copy, language)

    @Test
    fun rowsThatReadTheSameTakeTheirTitlesAndRowsThatDoNotAreUnchanged() {
        val texts =
            subtitleTexts(
                subtitle(1, title = "Full"),
                subtitle(2, form = SubtitleForm.Picture),
                subtitle(3, title = "Signs"),
                subtitle(4, language = "spa"),
            )
        assertEquals("English · Text · Full", texts[0])
        assertEquals("English · Picture", texts[1])
        assertEquals("English · Text · Signs", texts[2])
        assertEquals("Spanish · Text", texts[3])
    }

    @Test
    fun untitledTwinsAreNumberedFromTheSecond() {
        val texts = subtitleTexts(subtitle(1), subtitle(2), subtitle(3))
        assertEquals(listOf("English · Text", "English · Text · 2", "English · Text · 3"), texts)
    }

    @Test
    fun twinsWithTheSameTitleFallBackToNumbers() {
        val texts = subtitleTexts(subtitle(1, title = "Full"), subtitle(2, title = "Full"))
        assertEquals(listOf("English · Text · Full", "English · Text · Full · 2"), texts)
    }

    @Test
    fun aTitleThatIsANumberOrSaysWhatTheRowAlreadySaysIsNoTitle() {
        val texts =
            subtitleTexts(
                subtitle(1, flags = TrackFlags(forced = true), title = "Forced"),
                subtitle(2, flags = TrackFlags(forced = true), title = "Forced"),
                subtitle(3, title = "0"),
                subtitle(4, title = "0"),
                subtitle(5, title = "English"),
            )
        assertEquals("English · Forced · Text", texts[0])
        assertEquals("English · Forced · Text · 2", texts[1])
        assertEquals("English · Text", texts[2])
        assertEquals("English · Text · 2", texts[3])
        assertEquals("English · Text · 3", texts[4])
    }

    @Test
    fun aTitledTwinNextToAnUntitledOneIsToldApartByTheTitle() {
        val texts = subtitleTexts(subtitle(1), subtitle(2, title = "Full"))
        assertEquals(listOf("English · Text", "English · Text · Full"), texts)
    }
}
