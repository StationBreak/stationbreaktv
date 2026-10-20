// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Spec 12 task 2: the current names of the collections that are not deleted, and the signal that a whole collection
 * pass has completed. `RuleSnapshot` holds no names, so a name comes from this read.
 */
class CollectionNamesTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE, BOB))

    @After
    fun close() = repository.close()

    private suspend fun write(
        account: LibraryAccount,
        n: Int,
        name: String,
    ) = repository.writeCollection(
        account,
        ItemId(hexId(n)),
        name,
        listOf(CollectionMember(ItemId(hexId(1)), CollectionMemberKind.Movie)),
    )

    // Room reports a change on its own thread, so wait for the value instead of assuming when it arrives.
    private suspend fun Flow<Instant?>.until(wanted: Instant?) = withTimeout(10.seconds) { first { it == wanted } }

    @Test
    fun collectionNamesAreCurrentAndNotDeleted() =
        runBlocking {
            write(ALICE, 1000, "Synthetic Old Name")
            write(ALICE, 1001, "Synthetic Kept")
            write(ALICE, 1002, "Synthetic Gone")
            write(BOB, 1003, "Synthetic Bob's")
            clock.stepMs(1_000)
            write(ALICE, 1000, "Synthetic New Name")
            repository.tombstoneCollections(ALICE, listOf(ItemId(hexId(1002))))

            val names = repository.collectionNames(ALICE)

            assertEquals(setOf(canon(1000), canon(1001)), names.ids)
            assertEquals("Synthetic New Name", names[canon(1000)])
            assertEquals("Synthetic Kept", names[canon(1001)])
            assertNull(names[canon(1002)])
            assertNull(names[canon(1003)])
        }

    @Test
    fun aRevivedCollectionIsBackWithItsNewName() =
        runBlocking {
            write(ALICE, 1000, "Synthetic Before")
            clock.stepMs(1_000)
            repository.tombstoneCollections(ALICE, listOf(ItemId(hexId(1000))))
            clock.stepMs(1_000)
            write(ALICE, 1000, "Synthetic After")

            assertEquals("Synthetic After", repository.collectionNames(ALICE)[canon(1000)])
        }

    @Test
    fun anAccountWithNoCollectionsHasNoNames() =
        runBlocking {
            assertTrue(repository.collectionNames(ALICE).ids.isEmpty())
        }

    @Test
    fun collectionPassSignalAdvancesAfterPass() =
        runBlocking {
            val signal = repository.collectionsSyncedAt(ALICE)
            assertNull(signal.first())

            // The sync engine finishes the items first, and the pass has not completed: no signal yet.
            repository.select(ALICE, setOf(MOVIES))
            repository.beginFirstSync(ALICE)
            repository.finishSync(ALICE, clock.now())
            write(ALICE, 1000, "Synthetic Name")
            assertNull(signal.first())

            val firstStart = clock.now()
            repository.recordCollectionPass(ALICE, firstStart, relisted = true)
            assertEquals(firstStart, signal.until(firstStart))

            clock.stepMs(60_000)
            val secondStart = clock.now()
            repository.recordCollectionPass(ALICE, secondStart, relisted = false)
            assertEquals(secondStart, signal.until(secondStart))

            // Another account's pass is not this account's signal.
            assertNull(repository.collectionsSyncedAt(BOB).first())
        }

    @Test
    fun aPassThatNeverCompletedLeavesTheSignalWhereItWas() =
        runBlocking {
            val started = clock.now()
            repository.recordCollectionPass(ALICE, started, relisted = true)
            clock.stepMs(60_000)

            // A pass that stopped never reaches recordCollectionPass; writing collections alone does not move it.
            write(ALICE, 1000, "Synthetic Name")

            assertEquals(started, repository.collectionsSyncedAt(ALICE).first())
        }
}
