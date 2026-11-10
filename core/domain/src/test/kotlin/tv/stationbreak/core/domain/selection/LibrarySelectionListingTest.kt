// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fix round 1 of the Libraries screen fix: a listing answers as soon as the server does, never waiting on the
 * selection's lock (which a change on a slow TV holds for tens of seconds). It is followed afterwards, under the lock,
 * off the caller's path, with the same bookkeeping as before.
 */
internal class LibrarySelectionListingTest : SelectionTestBase() {
    @Test
    @Suppress("InjectDispatcher") // A test: the change needs a thread of its own while the listing is asked.
    fun aListing_returnsWhileAChangeHoldsTheLock_andIsFollowedAfterIt() {
        val tv = Tv().ready(setOf(libA, libB))
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        tv.settings.beforeSave = {
            inside.complete(Unit)
            release.await()
        }
        runBlocking {
            val change = async(Dispatchers.Default) { tv.selection.change(setOf(libA)) }
            try {
                withTimeout(WAIT_MS) { inside.await() }
                tv.server.librariesResult = listed(setOf(libA, libB, libC))

                val listing = withTimeout(WAIT_MS) { tv.selection.listing() }

                val listed = assertIs<LibraryListing.Listed>(listing)
                assertEquals(listOf(libA, libB, libC), listed.libraries.map { it.id })
                assertEquals(LibraryAccount(session.server.id, session.userId), listed.account, "who it was listed for")
                assertEquals(tv.selection.selection.value, listed.selection, "the stored choice, read without the lock")
                assertEquals(1L, assertNotNull(tv.stored()).listings, "not followed while the change holds the lock")
            } finally {
                // However the test ends, the change goes on, so nothing is left waiting.
                tv.settings.beforeSave = {}
                release.complete(Unit)
            }
            assertEquals(ChangeResult.Saved, withTimeout(WAIT_MS) { change.await() })
            withTimeout(WAIT_MS) { tv.selection.followed() }
        }

        assertEquals(2L, assertNotNull(tv.stored()).listings, "followed once the change let the lock go")
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libC)), "C is listed now, so it may be ticked")
    }

    // Fix round 2: a later listing that failed or listed nothing has no follow, and never hides an earlier one still
    // queued behind a change: followed() waits for every follow that may still run.
    @Test
    @Suppress("InjectDispatcher") // A test: the change and the wait each need a thread of their own.
    fun followed_waitsForAnEarlierFollow_pastALaterFailedOrEmptyListing() {
        val tv = Tv().ready(setOf(libA, libB))
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        tv.settings.beforeSave = {
            inside.complete(Unit)
            release.await()
        }
        runBlocking {
            val change = async(Dispatchers.Default) { tv.selection.change(setOf(libA)) }
            try {
                withTimeout(WAIT_MS) { inside.await() }
                tv.server.librariesResult = listed(setOf(libA, libB, libC))
                withTimeout(WAIT_MS) { tv.selection.listing() }
                tv.server.librariesResult = ServerResult.Failure(MediaServerError.Timeout())
                withTimeout(WAIT_MS) { tv.selection.listing() }
                tv.server.librariesResult = ServerResult.Success(emptyList())
                withTimeout(WAIT_MS) { tv.selection.listing() }

                assertNull(
                    withTimeoutOrNull(SHORT_MS) { tv.selection.followed() },
                    "the first follow is still queued behind the change",
                )
            } finally {
                tv.settings.beforeSave = {}
                release.complete(Unit)
            }
            assertEquals(ChangeResult.Saved, withTimeout(WAIT_MS) { change.await() })
            withTimeout(WAIT_MS) { tv.selection.followed() }
        }

        assertEquals(2L, assertNotNull(tv.stored()).listings, "the first listing was followed; the others need none")
    }

    @Test
    fun aFollowThatFails_isReported_andTheListingStillAnswers() {
        val tv = Tv().ready(setOf(libA, libB))
        tv.settings.failLoads = 1

        val listing = tv.listing(setOf(libA, libB))

        assertIs<LibraryListing.Listed>(listing)
        assertTrue(SelectionEvent.ListingFollowFailed in tv.events)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val SHORT_MS = 300L
    }
}
