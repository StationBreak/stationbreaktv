// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * #305: the stored selection is one record per server, not account-keyed. For a moment after an account switch it can
 * still be the previous account's while the active account is already the new one; it says whose it is, and a
 * selection owned by another account is no selection.
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

    private fun sourceOwnedBy(owner: LibraryAccount?) =
        BuiltInScheduleSource(service, library) { SavedSelection(LIBRARIES, 0L, LIBRARIES, 0L, owner) }

    private suspend fun refreshedAndActive() {
        library.seed(fillingLibrary())
        service.refresh(ACCOUNT, LIBRARIES)
        library.delegate.useAccount(ACCOUNT)
    }

    @Test
    fun aSelectionOwnedByAnotherAccountGivesNoAirings() =
        runBlocking<Unit> {
            refreshedAndActive()

            assertTrue(sourceOwnedBy(other).airings(NOON).isEmpty())
        }

    @Test
    fun aSelectionOwnedByTheActiveAccountGivesAirings() =
        runBlocking<Unit> {
            refreshedAndActive()

            assertTrue(sourceOwnedBy(ACCOUNT).airings(NOON).isNotEmpty())
        }

    @Test
    fun aSelectionWithNoOwnerIsAcceptedAsTheActiveAccounts() =
        runBlocking<Unit> {
            refreshedAndActive()

            assertTrue(sourceOwnedBy(null).airings(NOON).isNotEmpty(), "saved by an older build")
        }
}
