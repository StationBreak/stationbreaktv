// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.tracks

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The carry-over rules of the design doc (B2.6) and the store's per-item version memory (B2.5). */
class TrackCarryTest {
    private fun audio(
        index: Int,
        language: String?,
        default: Boolean = false,
        title: String? = null,
    ) = MediaStreamInfo(
        index,
        StreamKind.AUDIO,
        "ac3",
        language,
        6,
        null,
        null,
        false,
        default,
        false,
        null,
        title = title,
    )

    private fun subtitle(
        index: Int,
        language: String?,
        codec: String = "subrip",
        default: Boolean = false,
        forced: Boolean = false,
        title: String? = null,
    ) = MediaStreamInfo(
        index,
        StreamKind.SUBTITLE,
        codec,
        language,
        null,
        null,
        null,
        false,
        default,
        forced,
        SubtitleDelivery.IN_STREAM,
        title = title,
    )

    private fun source(
        vararg streams: MediaStreamInfo,
        defaultSubtitle: Int? = null,
        id: String = "src1",
    ) = MediaSource(
        MediaSourceId(id),
        null,
        "mkv",
        null,
        true,
        true,
        streams = streams.toList(),
        defaultAudioIndex = 1,
        defaultSubtitleIndex = defaultSubtitle,
    )

    private val sdhPref = SubtitlePreference.On("eng", hearingImpaired = true)
    private val plainPref = SubtitlePreference.On("eng")

    private fun carry(
        choice: SubtitlePreference,
        source: MediaSource,
    ) = TrackCarry.tracksFor(prefs(subtitles = choice), source)

    private fun prefs(
        audio: String? = null,
        subtitles: SubtitlePreference? = null,
    ) = TrackPreferences(audio, subtitles)

    // --- audio ---

    @Test
    fun nothingStoredMeansTheServersDefaults() {
        val carried = TrackCarry.tracksFor(prefs(), source(audio(1, "eng"), audio(2, "spa")))
        assertTrue(carried.isServerDefault)
        assertNull(carried.audioIndex)
    }

    @Test
    fun theCarriedAudioLanguageChoosesItsTrack() {
        val carried = TrackCarry.tracksFor(prefs(audio = "es"), source(audio(1, "eng", true), audio(2, "spa")))
        assertEquals(2, carried.audioIndex)
    }

    @Test
    fun aLanguageIsMatchedWhateverSpellingTheFileUses() {
        val carried = TrackCarry.tracksFor(prefs(audio = "de"), source(audio(1, "eng"), audio(2, "ger")))
        assertEquals(2, carried.audioIndex)
    }

    @Test
    fun theDefaultFlaggedTrackOfTheLanguageWinsElseTheFirst() {
        val flagged = source(audio(1, "eng"), audio(3, "eng", default = true))
        assertEquals(3, TrackCarry.tracksFor(prefs(audio = "eng"), flagged).audioIndex)
        assertEquals(1, TrackCarry.tracksFor(prefs(audio = "eng"), source(audio(1, "eng"), audio(3, "eng"))).audioIndex)
    }

    @Test
    fun aCommentaryTrackNeverCarries() {
        val tracks = source(audio(1, "spa"), audio(2, "eng", default = true, title = "Director's Commentary"))
        assertNull(TrackCarry.tracksFor(prefs(audio = "eng"), tracks).audioIndex, "only the commentary is English")
        val both = source(audio(1, "eng"), audio(2, "eng", default = true, title = "Cast Commentary"))
        assertEquals(
            1,
            TrackCarry.tracksFor(prefs(audio = "eng"), both).audioIndex,
            "the ordinary track, not the default-flagged commentary",
        )
    }

    @Test
    fun noTrackInTheLanguageLeavesTheServersDefault() {
        val carried = TrackCarry.tracksFor(prefs(audio = "jpn"), source(audio(1, "eng"), audio(2, "spa")))
        assertNull(carried.audioIndex)
        assertTrue(carried.isServerDefault)
    }

    // --- subtitles ---

    @Test
    fun offCarriesAsOffWhateverTheServerDefaultIs() {
        val carried =
            TrackCarry.tracksFor(
                prefs(subtitles = SubtitlePreference.Off),
                source(subtitle(5, "eng", default = true), defaultSubtitle = 5),
            )
        assertEquals(SubtitleCarry.Off, carried.subtitles)
        assertFalse(carried.isServerDefault)
    }

    @Test
    fun aCarriedSubtitleLanguagePicksItsTextTrack() {
        val tracks = source(subtitle(5, "spa"), subtitle(6, "eng"))
        val carried = TrackCarry.tracksFor(prefs(subtitles = plainPref), tracks)
        assertEquals(SubtitleCarry.Track(6), carried.subtitles)
    }

    @Test
    fun aForcedOrCommentarySubtitleNeverCarries() {
        val tracks =
            source(
                subtitle(5, "eng", forced = true),
                subtitle(7, "eng", title = "Commentary"),
            )
        val carried = carry(sdhPref, tracks)
        assertEquals(SubtitleCarry.ServerDefault, carried.subtitles)
    }

    // --- SDH carries (owner decision, B2.6) ---

    private fun sdhTrack(
        index: Int,
        hearingImpairedFlag: Boolean = false,
        title: String? = null,
    ) = subtitle(index, "eng", title = title).copy(isHearingImpaired = hearingImpairedFlag)

    @Test
    fun anSdhPreferenceTakesAnSdhTrackInTheLanguageOverAPlainOne() {
        val tracks = source(subtitle(5, "eng"), sdhTrack(6, title = "English SDH"))
        val carried = carry(sdhPref, tracks)
        assertEquals(SubtitleCarry.Track(6), carried.subtitles)
    }

    @Test
    fun theServersHearingImpairedFlagCountsAsSdhWhateverTheTitle() {
        val tracks = source(subtitle(5, "eng"), sdhTrack(6, hearingImpairedFlag = true))
        val carried = carry(sdhPref, tracks)
        assertEquals(SubtitleCarry.Track(6), carried.subtitles)
    }

    @Test
    fun anSdhPreferenceFallsBackToAPlainTrackInTheLanguage() {
        val carried =
            TrackCarry.tracksFor(
                prefs(subtitles = sdhPref),
                source(subtitle(5, "spa"), subtitle(6, "eng")),
            )
        assertEquals(SubtitleCarry.Track(6), carried.subtitles)
    }

    @Test
    fun aPlainPreferenceTakesAPlainTrackBeforeAnSdhOne() {
        val tracks = source(sdhTrack(5, hearingImpairedFlag = true), subtitle(6, "eng"))
        assertEquals(SubtitleCarry.Track(6), carry(plainPref, tracks).subtitles)
    }

    @Test
    fun aPlainPreferenceFallsBackToAnSdhTrackInTheLanguage() {
        val sdhOnly = source(sdhTrack(5, hearingImpairedFlag = true))
        assertEquals(SubtitleCarry.Track(5), carry(plainPref, sdhOnly).subtitles)
    }

    @Test
    fun aPlainPreferenceNeverFallsBackToACommentaryOrForcedTrack() {
        val tracks = source(subtitle(5, "eng", forced = true), subtitle(6, "eng", title = "Commentary"))
        assertEquals(SubtitleCarry.ServerDefault, carry(plainPref, tracks).subtitles)
    }

    // --- Off does not remove a forced track (owner decision) ---

    @Test
    fun offLeavesTheServerDefaultWhenThatDefaultIsForced() {
        val forcedDefault = source(subtitle(5, "eng", default = true, forced = true), defaultSubtitle = 5)
        val carried = carry(SubtitlePreference.Off, forcedDefault)
        assertEquals(SubtitleCarry.ServerDefault, carried.subtitles)
        assertTrue(carried.isServerDefault)
    }

    @Test
    fun aDefaultThatIsForcedByItsTitleAlsoSurvivesOff() {
        val titled = source(subtitle(5, "eng", default = true, title = "English [Forced]"), defaultSubtitle = 5)
        assertEquals(SubtitleCarry.ServerDefault, carry(SubtitlePreference.Off, titled).subtitles)
    }

    @Test
    fun offStillTurnsOffAnOrdinaryDefaultOrNone() {
        val ordinary = source(subtitle(5, "eng", default = true), defaultSubtitle = 5)
        assertEquals(SubtitleCarry.Off, carry(SubtitlePreference.Off, ordinary).subtitles)
        val forcedButNotDefault =
            source(subtitle(5, "eng", forced = true), subtitle(6, "eng", default = true), defaultSubtitle = 6)
        assertEquals(SubtitleCarry.Off, carry(SubtitlePreference.Off, forcedButNotDefault).subtitles)
        assertEquals(SubtitleCarry.Off, carry(SubtitlePreference.Off, source(subtitle(5, "eng"))).subtitles)
    }

    @Test
    fun anSdhPreferenceStillNeverPicksAPictureTrack() {
        val picture = sdhTrack(5, hearingImpairedFlag = true).copy(codec = "pgssub")
        val carried = carry(sdhPref, source(picture))
        assertEquals(SubtitleCarry.ServerDefault, carried.subtitles)
    }

    @Test
    fun aPreferenceNeverPicksAPictureSubtitle() {
        val tracks = source(subtitle(5, "eng", codec = "pgssub"), subtitle(6, "eng", codec = "ass"))
        val carried = TrackCarry.tracksFor(prefs(subtitles = plainPref), tracks)
        assertEquals(SubtitleCarry.Track(6), carried.subtitles)
        val onlyPicture = source(subtitle(5, "eng", codec = "pgssub"))
        assertEquals(
            SubtitleCarry.ServerDefault,
            TrackCarry.tracksFor(prefs(subtitles = plainPref), onlyPicture).subtitles,
        )
    }

    @Test
    fun aPictureSubtitleThatIsTheServersOwnDefaultStandsUntouched() {
        val tracks = source(subtitle(5, "eng", codec = "pgssub", default = true), defaultSubtitle = 5)
        val carried = TrackCarry.tracksFor(prefs(subtitles = plainPref), tracks)
        assertEquals(SubtitleCarry.ServerDefault, carried.subtitles, "no override: the server's choice plays")
    }

    @Test
    fun noSubtitleInTheLanguageLeavesTheServersDefault() {
        val carried = TrackCarry.tracksFor(prefs(subtitles = SubtitlePreference.On("jpn")), source(subtitle(5, "eng")))
        assertEquals(SubtitleCarry.ServerDefault, carried.subtitles)
    }

    @Test
    fun audioAndSubtitlesCarryIndependently() {
        val carried =
            TrackCarry.tracksFor(
                prefs(audio = "spa", subtitles = SubtitlePreference.Off),
                source(audio(1, "eng"), audio(2, "spa"), subtitle(5, "eng")),
            )
        assertEquals(CarriedTracks(2, SubtitleCarry.Off), carried)
    }

    // --- version ---

    @Test
    fun aRememberedVersionIsUsedOnlyWhileTheItemStillOffersIt() {
        val sources = listOf(source(id = "a"), source(id = "b"))
        assertEquals(MediaSourceId("b"), TrackCarry.version(MediaSourceId("b"), sources))
        assertNull(TrackCarry.version(MediaSourceId("gone"), sources))
        assertNull(TrackCarry.version(null, sources))
    }

    // --- the store ---

    private val account = LibraryAccount(ServerId("s"), UserId("u"))

    @Test
    fun theInMemoryStoreKeepsPreferencesPerAccount() =
        runBlocking {
            val store = InMemoryTrackChoiceStore()
            store.saveAudioLanguage(account, "es")
            store.saveSubtitles(account, SubtitlePreference.On("en"))
            assertEquals(prefs("es", SubtitlePreference.On("en")), store.preferences(account))
            assertEquals(prefs(), store.preferences(LibraryAccount(ServerId("s"), UserId("other"))))
            store.saveAudioLanguage(account, null)
            assertNull(store.preferences(account).audioLanguage)
        }

    @Test
    fun versionMemoryReplacesAnItemsEntryAndKeepsTheMostRecent() {
        var memory = VersionMemory()
        repeat(TrackChoiceStore.MAX_VERSIONS + 5) { memory = memory.with(ItemId("item$it"), MediaSourceId("s$it")) }
        assertEquals(TrackChoiceStore.MAX_VERSIONS, memory.entries.size)
        assertNull(memory[ItemId("item0")], "the oldest fell off")
        assertEquals(MediaSourceId("s204"), memory[ItemId("item204")])
        memory = memory.with(ItemId("item100"), MediaSourceId("new"))
        assertEquals(MediaSourceId("new"), memory[ItemId("item100")])
        assertEquals(ItemId("item100"), memory.entries.last().first, "one entry per item, the latest at the end")
        assertEquals(TrackChoiceStore.MAX_VERSIONS, memory.entries.size)
    }
}
