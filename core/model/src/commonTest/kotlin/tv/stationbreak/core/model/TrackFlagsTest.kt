// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackFlagsTest {
    private fun track(
        title: String? = null,
        displayTitle: String? = null,
        forced: Boolean = false,
        hearingImpaired: Boolean = false,
    ) = MediaStreamInfo(
        index = 1,
        kind = StreamKind.AUDIO,
        codec = "ac3",
        language = "eng",
        channels = 6,
        width = null,
        height = null,
        isExternal = false,
        isDefault = false,
        isForced = forced,
        delivery = null,
        title = title,
        displayTitle = displayTitle,
        isHearingImpaired = hearingImpaired,
    )

    @Test
    fun aPlainTrackHasNoFlags() {
        assertFalse(track("English", "English - AC3 - 5.1").flags().neverCarries)
        assertFalse(track().flags().neverCarries)
    }

    @Test
    fun commentaryIsReadFromEitherTitle() {
        assertTrue(track(title = "Director's Commentary").flags().commentary)
        assertTrue(track(displayTitle = "English - Commentary - AC3").flags().commentary)
    }

    @Test
    fun sdhIsReadFromItsAbbreviationOrItsWords() {
        assertTrue(track(title = "English SDH").flags().sdh)
        assertTrue(track(title = "English (Hearing Impaired)").flags().sdh)
        assertFalse(track(title = "Sdhuvan").flags().sdh, "a word that merely contains the letters")
    }

    @Test
    fun theServersHearingImpairedFlagMakesATrackSdhWhateverItsTitle() {
        assertTrue(track(title = "English", hearingImpaired = true).flags().sdh)
    }

    @Test
    fun forcedComesFromTheServersFlagOrTheTitle() {
        assertTrue(track(forced = true).flags().forced)
        assertTrue(track(title = "English [Forced]").flags().forced)
    }

    @Test
    fun audioDescriptionIsAFlagToo() {
        assertTrue(track(title = "English Audio Description").flags().description)
        assertTrue(track(displayTitle = "Descriptive Video Service").flags().description)
    }

    @Test
    fun neverCarriesIsTrueForCommentaryForcedAndDescriptionButNotSdh() {
        assertTrue(TrackFlags(commentary = true).neverCarries)
        assertTrue(TrackFlags(forced = true).neverCarries)
        assertTrue(TrackFlags(description = true).neverCarries)
        assertFalse(TrackFlags(sdh = true).neverCarries)
        assertFalse(TrackFlags().neverCarries)
    }
}

class LanguageCodesTest {
    @Test
    fun bibliographicAndTerminologicSpellingsAreTheSameLanguage() {
        assertTrue(LanguageCodes.same("ger", "deu"))
        assertTrue(LanguageCodes.same("fre", "fra"))
        assertTrue(LanguageCodes.same("chi", "zho"))
    }

    @Test
    fun twoLetterAndThreeLetterCodesAreTheSameLanguage() {
        assertTrue(LanguageCodes.same("en", "eng"))
        assertTrue(LanguageCodes.same("EN-GB", "eng"))
        assertEquals("en", LanguageCodes.canonical(" ENG "))
    }

    @Test
    fun differentLanguagesAreNotTheSame() {
        assertFalse(LanguageCodes.same("eng", "spa"))
    }

    @Test
    fun noLanguageIsNeverTheSameAsAnything() {
        assertNull(LanguageCodes.canonical(null))
        assertNull(LanguageCodes.canonical("und"))
        assertNull(LanguageCodes.canonical(""))
        assertFalse(LanguageCodes.same("und", "und"))
        assertFalse(LanguageCodes.same(null, "eng"))
    }

    @Test
    fun aCodeWeDoNotKnowKeepsItsOwnLowercaseForm() {
        assertEquals("tlh", LanguageCodes.canonical("TLH"))
        assertTrue(LanguageCodes.same("tlh", "TLH"))
    }
}
