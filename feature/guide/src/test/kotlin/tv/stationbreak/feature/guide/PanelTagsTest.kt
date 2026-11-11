// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSummary
import java.util.Locale

/** The tags under the details panel's meta line (#723): which facts become tags, in what order, in what words. */
class PanelTagsTest {
    private val copy = PanelTagCopy("Mono", "Stereo", "CC", "Critics", "Audience")

    private fun item(
        rating: String? = null,
        critics: Int? = null,
        audience: Double? = null,
        media: MediaSummary? = null,
    ) = ItemDetails(
        ItemId("i1"),
        null,
        null,
        emptyList(),
        null,
        officialRating = rating,
        criticRating = critics,
        communityRating = audience,
        media = media,
    )

    private fun size(
        width: Int?,
        height: Int?,
    ) = MediaSummary(width, height, null, hasSubtitles = false)

    @Test
    fun everyKnownFactIsATagInTheSetOrder() {
        val tags = panelTagsOf(item("PG-13", 68, 6.6, MediaSummary(1920, 1080, 6, hasSubtitles = true)), copy)

        assertEquals(
            listOf(
                PanelTag.Plain("PG-13"),
                PanelTag.Plain("HD"),
                PanelTag.Plain("5.1"),
                PanelTag.Plain("CC"),
                PanelTag.Score("Critics", "68%"),
                PanelTag.Score("Audience", "6.6"),
            ),
            tags,
        )
    }

    @Test
    fun anItemWithNoFactsHasNoTags() {
        assertEquals(emptyList<PanelTag>(), panelTagsOf(item(), copy))
    }

    @Test
    fun onlyTheKnownFactsShow() {
        assertEquals(listOf(PanelTag.Plain("NR")), panelTagsOf(item(rating = "NR"), copy))
        assertEquals(listOf(PanelTag.Score("Audience", "7.1")), panelTagsOf(item(audience = 7.1), copy))
        assertEquals(
            listOf(PanelTag.Plain("Stereo")),
            panelTagsOf(item(media = MediaSummary(null, null, 2, hasSubtitles = false)), copy),
        )
    }

    @Test
    fun ccIsOnlyForAnItemWithASubtitleTrack() {
        assertEquals(
            listOf(PanelTag.Plain("CC")),
            panelTagsOf(item(media = MediaSummary(null, null, null, hasSubtitles = true)), copy),
        )
        assertEquals(
            emptyList<PanelTag>(),
            panelTagsOf(item(media = MediaSummary(null, null, null, hasSubtitles = false)), copy),
        )
    }

    @Test
    fun theResolutionClassFollowsEitherSideOfThePicture() {
        // A wide film is letterboxed into a short frame, so the width counts as well as the height.
        val classes =
            listOf(
                Triple(640, 360, ResolutionClass.Sd),
                Triple(720, 480, ResolutionClass.Sd),
                Triple(1280, 720, ResolutionClass.Hd),
                Triple(1920, 1080, ResolutionClass.Hd),
                Triple(1920, 800, ResolutionClass.Hd),
                Triple(1280, 536, ResolutionClass.Hd),
                Triple(3840, 2160, ResolutionClass.Uhd),
                Triple(3840, 1600, ResolutionClass.Uhd),
                Triple(4096, 1716, ResolutionClass.Uhd),
            )
        for ((width, height, expected) in classes) {
            assertEquals("${width}x$height", expected, resolutionOf(size(width, height)))
        }
        assertEquals("4K", ResolutionClass.Uhd.label)
    }

    @Test
    fun aPictureWithOneSideKnownIsClassedByIt() {
        assertEquals(ResolutionClass.Hd, resolutionOf(size(1920, null)))
        assertEquals(ResolutionClass.Hd, resolutionOf(size(null, 1080)))
        assertEquals(ResolutionClass.Sd, resolutionOf(size(null, 480)))
        assertEquals(ResolutionClass.Uhd, resolutionOf(size(null, 2160)))
    }

    @Test
    fun noSizeIsNoClass() {
        assertNull(resolutionOf(size(null, null)))
        assertNull(resolutionOf(size(0, 0)))
        assertEquals(emptyList<PanelTag>(), panelTagsOf(item(media = size(null, null)), copy))
    }

    @Test
    fun onlyTheLayoutsWithANameAreTagged() {
        assertEquals("Mono", audioLayoutOf(1, copy))
        assertEquals("Stereo", audioLayoutOf(2, copy))
        assertEquals("5.1", audioLayoutOf(6, copy))
        assertEquals("7.1", audioLayoutOf(8, copy))
        for (odd in listOf(0, 3, 4, 5, 7, 10, -2)) assertNull("$odd channels", audioLayoutOf(odd, copy))
    }

    @Test
    fun theScoresAreWrittenTheSameWhateverTheLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                listOf(PanelTag.Score("Critics", "0%"), PanelTag.Score("Audience", "10.0")),
                panelTagsOf(item(critics = 0, audience = 10.0), copy),
            )
            assertEquals(listOf(PanelTag.Score("Audience", "6.6")), panelTagsOf(item(audience = 6.6), copy))
        } finally {
            Locale.setDefault(before)
        }
    }
}
