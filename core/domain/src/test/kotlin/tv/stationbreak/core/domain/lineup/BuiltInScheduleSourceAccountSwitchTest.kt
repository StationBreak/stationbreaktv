// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * #305: the signed-in account and its library selection are one read. The selection is a per-server record, not
 * account-keyed, so an account that changes between the two reads could pair one account with another's selection.
 */
class BuiltInScheduleSourceAccountSwitchTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val other = LibraryAccount(ServerId("other-server"), UserId("other-user"))

    // The active account for each read, in order; the last one stands once they run out.
    private fun flipping(vararg reads: LibraryAccount): LibraryRepository {
        val queue = reads.toMutableList()
        return object : LibraryRepository by library {
            override fun activeAccount(): Flow<LibraryAccount?> = flowOf(next(queue))
        }
    }

    private fun next(queue: MutableList<LibraryAccount>): LibraryAccount {
        val last = queue.size == 1
        return if (last) queue[0] else queue.removeAt(0)
    }

    private var selectionReads = 0

    private fun sourceOver(active: LibraryRepository) =
        BuiltInScheduleSource(service, active) {
            selectionReads++
            selectionOf(LIBRARIES)
        }

    @Test
    fun anAccountThatChangesBetweenTheReadsIsReadAgainAndNeverMixedWithTheOldSelection() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            service.refresh(ACCOUNT, LIBRARIES)
            val source = sourceOver(flipping(other, ACCOUNT, ACCOUNT))

            val airings = source.airings(NOON)

            assertEquals(2, selectionReads, "the selection was read again for the account that stood")
            assertTrue(airings.isNotEmpty(), "and the lineup is the account's that stood")
        }

    @Test
    fun anAccountThatKeepsChangingGivesNoSelectionAndNoAirings() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            service.refresh(ACCOUNT, LIBRARIES)
            val source = sourceOver(flipping(ACCOUNT, other, ACCOUNT, other))

            assertTrue(source.airings(NOON).isEmpty())
            assertEquals(2, selectionReads, "one retry, then it gives up")
        }

    @Test
    fun anUnchangedAccountIsReadOnceWithItsSelection() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            service.refresh(ACCOUNT, LIBRARIES)
            val source = sourceOver(flipping(ACCOUNT))

            assertTrue(source.airings(NOON).isNotEmpty())
            assertEquals(1, selectionReads)
        }
}
