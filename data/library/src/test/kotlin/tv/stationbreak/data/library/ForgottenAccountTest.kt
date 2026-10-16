// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState

/**
 * Spec 06 task 3.4 (Angua M1): a sync that is still running when its account signs out, or another account signs in,
 * must not write anything back. Every transaction the sync uses checks inside the transaction that the account is
 * still the signed-in one, and sign-in and sign-out cancel the unique sync work before they touch the rows.
 */
class ForgottenAccountTest {
    private val control = RecordingSyncControl()
    private val repository = TestLibraries.inMemory(sync = control, signedIn = emptyList())

    @After
    fun close() = repository.close()

    private suspend fun signedInAndSelected() {
        repository.useAccount(ALICE)
        repository.select(ALICE, setOf(MOVIES))
    }

    // The note's own test: the sync calls select after the account was forgotten.

    @Test
    fun aSyncCallingSelectAfterForgetLeavesNothingBehind() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.forget(ALICE)

            repository.select(ALICE, setOf(MOVIES))

            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun selectForAnAccountThatNeverSignedInStoresNothing() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))

            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun beginningTheFirstSyncAfterForgetStoresNothingAndSaysSo() =
        runBlocking {
            signedInAndSelected()
            repository.forget(ALICE)

            assertThrows(AccountNotActiveException::class.java) {
                runBlocking { repository.beginFirstSync(ALICE) }
            }
            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun theStateTheSyncSavesAfterForgetStoresNothing() =
        runBlocking {
            signedInAndSelected()
            repository.forget(ALICE)

            assertThrows(AccountNotActiveException::class.java) { runBlocking { repository.finishSync(ALICE) } }
            assertThrows(AccountNotActiveException::class.java) {
                runBlocking { repository.failSync(ALICE, SyncFailure.Network) }
            }
            assertThrows(AccountNotActiveException::class.java) { runBlocking { repository.setTotal(ALICE, 5) } }
            assertThrows(AccountNotActiveException::class.java) {
                runBlocking { repository.markFetched(ALICE, MOVIES) }
            }
            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun aPageWrittenAfterAnotherAccountSignedInStoresNothing() =
        runBlocking {
            signedInAndSelected()
            repository.useAccount(BOB)

            assertThrows(Exception::class.java) {
                runBlocking { repository.writePage(ALICE, MOVIES, listOf(item("late")), 1, 1, run = 1) }
            }
            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun aPageWriteChecksTheAccountInsideItsTransaction() =
        runBlocking {
            signedInAndSelected()
            // Only the marker goes, as if the sign-out landed between the sync's last look and its write: the library
            // row (which a foreign key would also catch) is still there, so only the check can stop the write.
            repository.dao.deleteActive(ALICE.server.value, ALICE.user.value)

            assertThrows(AccountNotActiveException::class.java) {
                runBlocking { repository.writePage(ALICE, MOVIES, listOf(item("late")), 1, 1, run = 1) }
            }
            assertEquals(0, repository.rowCounts().items)
            assertEquals(0, repository.rowCounts().checkpoints)
        }

    @Test
    fun tombstonesAndRevivalsAlsoNeedTheAccountToBeActive() =
        runBlocking {
            signedInAndSelected()
            repository.writePage(ALICE, MOVIES, listOf(item("a")), 1, 1, run = 1)
            repository.dao.deleteActive(ALICE.server.value, ALICE.user.value)

            assertThrows(AccountNotActiveException::class.java) {
                runBlocking { repository.tombstone(ALICE, listOf(item("a").id)) }
            }
            assertThrows(AccountNotActiveException::class.java) {
                runBlocking { repository.revive(ALICE, listOf(item("a").id)) }
            }
            Unit
        }

    // Who is signed in.

    @Test
    fun signingInMakesTheAccountActiveAndSigningOutEndsIt() =
        runBlocking {
            assertNull(repository.activeStatus().first())

            repository.useAccount(ALICE)
            assertEquals(LibraryStatus(SyncState.NeverSynced, null), repository.activeStatus().first())

            repository.forget(ALICE)
            assertNull(repository.activeStatus().first())
        }

    @Test
    fun anotherAccountSigningInReplacesTheActiveOne() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.useAccount(BOB)

            repository.select(ALICE, setOf(MOVIES))
            repository.select(BOB, setOf(MOVIES))

            assertEquals(emptySet<tv.stationbreak.core.model.ItemId>(), repository.pendingLibraries(ALICE))
            assertEquals(setOf(MOVIES), repository.pendingLibraries(BOB))
        }

    @Test
    fun theActiveStatusFollowsTheSyncOfTheActiveAccount() =
        runBlocking {
            signedInAndSelected()
            repository.beginFirstSync(ALICE)
            repository.setTotal(ALICE, 4)

            assertEquals(
                LibraryStatus(SyncState.FirstSyncRunning(copied = 0, total = 4), null),
                repository.activeStatus().first(),
            )
        }

    // The unique sync work is cancelled before the rows change.

    @Test
    fun signingInAsAnotherAccountCancelsTheSyncBeforeTheRowsGo() =
        runBlocking {
            signedInAndSelected()
            control.cancels = 0
            control.rowsWhenCancelled.clear()
            val before = repository.rowCounts()
            control.watch { runBlocking { repository.rowCounts() } }

            repository.useAccount(BOB)

            assertEquals(1, control.cancels)
            assertEquals(listOf(before), control.rowsWhenCancelled)
            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun signingOutCancelsTheSyncBeforeTheRowsGo() =
        runBlocking {
            signedInAndSelected()
            control.cancels = 0
            control.rowsWhenCancelled.clear()
            val before = repository.rowCounts()
            control.watch { runBlocking { repository.rowCounts() } }

            repository.forget(ALICE)

            assertEquals(1, control.cancels)
            assertEquals(listOf(before), control.rowsWhenCancelled)
        }

    @Test
    fun usingTheSameAccountAgainLeavesTheRunningSyncAlone() =
        runBlocking {
            // The worker restores the session itself, which names the account it is already syncing.
            signedInAndSelected()
            control.cancels = 0

            repository.useAccount(ALICE)

            assertEquals(0, control.cancels)
        }
}
