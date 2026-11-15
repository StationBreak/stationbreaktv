// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import kotlin.test.Test
import kotlin.test.assertEquals

/** The per-term sibling of [fitPointsQ16] (the lineup audit's): its terms are the same constants, and add up to it. */
class FitTermsTest {
    private val flags = listOf(false, true)
    private val counts = listOf(0, 1, 2, 5)
    private val toneShares = listOf(0L to 0L, 0L to 10L, 3L to 9L, 10L to 10L, 1L to 7L)

    private fun everyInput(): List<FitInputs> =
        counts.flatMap { genre ->
            counts.flatMap { tag ->
                flags.flatMap { studio ->
                    flags.flatMap { guest ->
                        everyFlagAndTone(genre, tag, studio, guest)
                    }
                }
            }
        }

    private fun everyFlagAndTone(
        genre: Int,
        tag: Int,
        studio: Boolean,
        guest: Boolean,
    ): List<FitInputs> =
        flags.flatMap { anchor ->
            flags.flatMap { kid ->
                flags.flatMap { format ->
                    toneShares.map { (tone, total) ->
                        FitInputs(genre, tag, studio, guest, anchor, kid, format, tone, total)
                    }
                }
            }
        }

    @Test
    fun theTermsAddUpToTheSharedPointsForEveryCombination() {
        for (f in everyInput()) {
            val points =
                fitPointsQ16(
                    f.genreHits,
                    f.tagHits,
                    f.themeStudio,
                    f.guestOnly,
                    f.anchor,
                    f.kidOnKid,
                    f.formatFits,
                    f.toneMs,
                    f.candidateMs,
                )
            val terms = fitTermsQ16(11, f)
            assertEquals(points, terms.points)
            assertEquals(specificityQ16(11) + points, terms.total)
            assertEquals(fitScoreQ16(11, f), terms.total)
        }
    }

    @Test
    fun eachTermIsItsOwnConstant() {
        val f = FitInputs(3, 5, true, true, true, true, true, 5, 10)
        val terms = fitTermsQ16(2, f)
        assertEquals(specificityQ16(2), terms.specificity)
        assertEquals(120L shl 16, terms.genre)
        assertEquals(70L shl 16, terms.tag)
        assertEquals(120L shl 16, terms.studio)
        // A guest-only channel is a decade channel: the "decade" term is its penalty.
        assertEquals(-150L shl 16, terms.decade)
        assertEquals(1000L shl 16, terms.anchor)
        assertEquals(200L shl 16, terms.kids)
        assertEquals(80L shl 16, terms.format)
        assertEquals(30L shl 16, terms.tone)
    }
}
