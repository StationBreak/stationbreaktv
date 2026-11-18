// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.tracks

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #725: the TV's caption setting seeds the subtitle choice and never overrides one the viewer made. */
class SystemCaptionSeededChoicesTest {
    private val account = LibraryAccount(ServerId("srv1"), UserId("u1"))
    private val store = InMemoryTrackChoiceStore()

    private class Captions(
        var on: Boolean,
        var lang: String?,
    ) : SystemCaptions {
        override fun enabled() = on

        override fun language() = lang
    }

    private fun seeded(captions: Captions) = SystemCaptionSeededChoices(store, captions)

    @Test
    fun captionsOnAndNoChoiceYetMeansSubtitlesOnInTheCaptionLanguage() =
        runBlocking {
            val prefs = seeded(Captions(on = true, lang = "en-US")).preferences(account)
            assertEquals(SubtitlePreference.On("en"), prefs.subtitles)
        }

    @Test
    fun captionsOffLeavesTheServersDefault() =
        runBlocking {
            assertNull(seeded(Captions(on = false, lang = "en")).preferences(account).subtitles)
        }

    @Test
    fun aRememberedOffWinsOverSystemCaptions() =
        runBlocking {
            store.saveSubtitles(account, SubtitlePreference.Off)
            val prefs = seeded(Captions(on = true, lang = "en")).preferences(account)
            assertEquals(SubtitlePreference.Off, prefs.subtitles)
        }

    @Test
    fun aRememberedLanguageWinsOverTheCaptionLanguage() =
        runBlocking {
            store.saveSubtitles(account, SubtitlePreference.On("fr", hearingImpaired = true))
            val prefs = seeded(Captions(on = true, lang = "en")).preferences(account)
            assertEquals(SubtitlePreference.On("fr", hearingImpaired = true), prefs.subtitles)
        }

    @Test
    fun noKnownLanguageSeedsNothing() =
        runBlocking {
            assertNull(seeded(Captions(on = true, lang = null)).preferences(account).subtitles)
            assertNull(seeded(Captions(on = true, lang = "und")).preferences(account).subtitles)
        }

    @Test
    fun theSeedIsNeverWrittenSoTurningCaptionsOffLeavesNoTrace() =
        runBlocking {
            val captions = Captions(on = true, lang = "en")
            seeded(captions).preferences(account)
            captions.on = false
            assertNull(seeded(captions).preferences(account).subtitles)
            assertNull(store.preferences(account).subtitles)
        }

    @Test
    fun theAudioLanguageCarriesThrough() =
        runBlocking {
            store.saveAudioLanguage(account, "de")
            assertEquals("de", seeded(Captions(on = true, lang = "en")).preferences(account).audioLanguage)
        }
}
