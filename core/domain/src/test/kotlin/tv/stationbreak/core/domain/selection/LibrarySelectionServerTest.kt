// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.Missing
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Following the server's listing (spec 26, Part 4 §2 "followServer"): a library is taken out only when two counted
 * listings, three hours apart, both missed it; a failed or empty listing changes nothing.
 */
internal class LibrarySelectionServerTest : SelectionTestBase() {
    private val three = 3 * hour

    private fun Tv.follow(ids: Set<ItemId> = emptySet()) = runBlocking { selection.followServer(ACCOUNT, listed(ids)) }

    @Test
    fun aMissOnce_isKept_andCounted_withTheSeqAsItWas() {
        val tv = Tv(lineup = lineupOf(laughs)).also { it.resume() }

        tv.follow(setOf(libA))

        val stored = assertNotNull(tv.stored())
        assertEquals(setOf(libA, libB), stored.chosen)
        assertEquals(1L, stored.listings)
        assertEquals(mapOf(libB to Missing(atMs, 1L)), stored.missing)
        assertEquals(1L, stored.seq, "bookkeeping only: what plays is unchanged")
    }

    @Test
    fun aMissAgain_inALaterListing_threeHoursOn_removesTheLibrary_asAnUntick() {
        val tv = Tv(lineup = lineupOf(laughs)).also { it.resume() }
        tv.follow(setOf(libA))
        tv.nowMs = atMs + three

        tv.follow(setOf(libA))

        val stored = assertNotNull(tv.stored())
        assertEquals(setOf(libA), stored.chosen)
        assertTrue(stored.seq > 1L, "a change of what plays moves the seq")
        assertTrue(stored.missing.isEmpty())
        assertNull(tv.row(libB))
        assertEquals(stored.seq, tv.selection.selectionChanges.value)
    }

    @Test
    fun twoMissesWithinThreeHours_areKept() {
        val tv = Tv(lineup = lineupOf(laughs)).also { it.resume() }
        tv.follow(setOf(libA))
        tv.nowMs = atMs + three - 1

        tv.follow(setOf(libA))

        assertEquals(setOf(libA, libB), assertNotNull(tv.stored()).chosen)
    }

    @Test
    fun aMissFromTheFuture_isDropped() {
        val tv =
            Tv(
                lineup = lineupOf(laughs),
                stored =
                    StoredSelection(
                        setOf(libA, libB),
                        seq = 1L,
                        missing =
                            mapOf(libB to Missing(atMs + hour, 0L)),
                        listings = 1L,
                    ),
            )
        tv.resume()

        tv.follow(setOf(libA))

        assertEquals(mapOf(libB to Missing(atMs, 2L)), assertNotNull(tv.stored()).missing, "counted again from now")
        assertEquals(setOf(libA, libB), assertNotNull(tv.stored()).chosen)
    }

    @Test
    fun aListedLibrary_clearsItsMiss() {
        val tv = Tv(lineup = lineupOf(laughs)).also { it.resume() }
        tv.follow(setOf(libA))

        tv.follow(setOf(libA, libB))

        assertTrue(assertNotNull(tv.stored()).missing.isEmpty())
    }

    @Test
    fun aFailedListing_orAnEmptyOne_changesNothing() {
        val tv = Tv(lineup = lineupOf(laughs)).also { it.resume() }
        val stored = tv.stored()

        runBlocking { tv.selection.followServer(ACCOUNT, ServerResult.Failure(MediaServerError.Unknown())) }
        tv.follow()

        assertEquals(stored, tv.stored())
    }

    @Test
    fun anotherAccountsListing_isDropped() {
        val tv = Tv(lineup = lineupOf(laughs)).also { it.resume() }
        val stored = tv.stored()

        runBlocking { tv.selection.followServer(other, listed(setOf(libA))) }

        assertEquals(stored, tv.stored())
    }

    @Test
    fun anAddTheServerNoLongerLists_leavesTheAdds_andItsPassIsCancelled() {
        val tv = Tv(lineup = lineupOf(laughs)).ready(setOf(libA, libB, libC))
        tv.change(setOf(libA, libB, libC))
        val restarts = tv.restarts
        tv.follow(setOf(libA, libB))
        tv.nowMs = atMs + three

        tv.follow(setOf(libA, libB))

        val stored = assertNotNull(tv.stored())
        assertTrue(stored.adding.isEmpty())
        assertNull(tv.row(libC))
        assertEquals(restarts + 1, tv.restarts)
    }

    @Test
    fun everyChosenLibraryConfirmedMissing_isGone_keepingTheChoiceAndTheCache() {
        val tv = Tv(lineup = lineupOf(laughs)).also { it.resume() }
        tv.follow(setOf(libC))
        tv.nowMs = atMs + three

        tv.follow(setOf(libC))

        val stored = assertNotNull(tv.stored())
        assertTrue(stored.gone)
        assertEquals(setOf(libA, libB), stored.chosen)
        assertTrue(stored.seq > 1L, "nothing plays now: the seq moves")
        assertNotNull(tv.row(libA))
        assertNotNull(tv.row(libB))
        assertEquals(ChoiceView(setOf(libA, libB), emptySet(), gone = true), tv.selection.selection.value)

        // A chosen library listed again ends it.
        tv.follow(setOf(libA))
        val back = assertNotNull(tv.stored())
        assertTrue(!back.gone)
        assertTrue(back.seq > stored.seq)
    }

    @Test
    fun aListing_isReadThroughTheSignedInSession_andFollowed() {
        val tv = Tv(lineup = lineupOf(laughs)).also { it.resume() }

        val listing = tv.listing(setOf(libA, libB, libC))

        val listed = assertIs<LibraryListing.Listed>(listing)
        assertEquals(listOf(libA, libB, libC), listed.libraries.map { it.id })
        assertEquals(1L, assertNotNull(tv.stored()).listings)
        tv.signedIn = null
        val signedOut = runBlocking { tv.selection.listing() }
        assertEquals(LibraryListing.Unavailable(null, tv.selection.selection.value), signedOut)
    }
}
