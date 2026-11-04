// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import kotlin.test.Test
import kotlin.test.assertEquals

class FitScoreTest {
    @Suppress("LongParameterList") // A test builder: every field has a default.
    private fun inputs(
        genreHits: Int = 0,
        tagHits: Int = 0,
        themeStudio: Boolean = false,
        guestOnly: Boolean = false,
        anchor: Boolean = false,
        kidOnKid: Boolean = false,
        formatFits: Boolean = false,
        toneMs: Long = 0,
        candidateMs: Long = 0,
    ) = FitInputs(genreHits, tagHits, themeStudio, guestOnly, anchor, kidOnKid, formatFits, toneMs, candidateMs)

    private fun plain(f: FitInputs): Long = fitScoreQ16(2, f) - specificityQ16(2)

    @Test
    fun eachTermHasThePrototypesValue() {
        assertEquals(0L, plain(inputs()))
        assertEquals(120L shl 16, plain(inputs(genreHits = 3)))
        assertEquals(35L shl 16, plain(inputs(tagHits = 1)))
        assertEquals(70L shl 16, plain(inputs(tagHits = 5)))
        assertEquals(120L shl 16, plain(inputs(themeStudio = true)))
        assertEquals(-150L shl 16, plain(inputs(guestOnly = true)))
        assertEquals(1000L shl 16, plain(inputs(anchor = true)))
        assertEquals(200L shl 16, plain(inputs(kidOnKid = true)))
        assertEquals(80L shl 16, plain(inputs(formatFits = true)))
        assertEquals(30L shl 16, plain(inputs(toneMs = 5, candidateMs = 10)))
    }

    @Test
    fun theScoreIsTheSpecificityPlusThePoints() {
        val f = inputs(genreHits = 2, tagHits = 1, anchor = true, guestOnly = true, toneMs = 3, candidateMs = 9)
        val points = fitPointsQ16(2, 1, false, true, true, false, false, 3, 9)
        assertEquals(specificityQ16(7) + points, fitScoreQ16(7, f))
    }

    @Test
    fun theToneTermFloors() {
        assertEquals(8L shl 16, plain(inputs(toneMs = 1, candidateMs = 7)))
    }

    @Test
    fun noCandidateMsMeansNoToneTerm() {
        assertEquals(0L, plain(inputs(toneMs = 1_000, candidateMs = 0)))
    }
}
