// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * Spec 10 task 2.4 (ADR-0015): deleting a member from Jellyfin does not bump its collections, so when the reconcile
 * tombstones an item or a series, the same transaction ends that ID's current member rows in EVERY collection of the
 * account, at the tombstone's stamp. Reviving the item re-adds nothing. A fault after the tombstone rolls both back.
 */
class CollectionTombstoneCascadeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE, BOB))
    private val start = clock.nowMs
    private val film = ItemId(hexId(1))
    private val other = ItemId(hexId(2))
    private val show = ItemId(hexId(500))
    private val boxA = ItemId("box01")
    private val boxB = ItemId("box02")

    @After
    fun close() = repository.close()

    // Two items and a series cached, and two collections that hold them (the film in both).
    private suspend fun world() {
        repository.select(ALICE, setOf(MOVIES, SHOWS))
        repository.put(ALICE, MOVIES, sitem(1), sitem(2))
        repository.putSeries(ALICE, SHOWS, series(show.value))
        val aMembers = listOf(cm(film.value), cm(other.value), cm(show.value, CollectionMemberKind.Series))
        repository.writeCollection(ALICE, boxA, "A", aMembers)
        repository.writeCollection(ALICE, boxB, "B", listOf(cm(film.value), cm("x")))
        clock.stepMs(10_000)
    }

    private suspend fun current(collection: ItemId): List<String> {
        val members = repository.currentMembers(ALICE, collection.value)
        return members.map { it.substringBefore(':') }
    }

    // The account's member rows of [collection] for [member], oldest first.
    private suspend fun rows(
        collection: ItemId,
        member: ItemId,
    ): List<CollectionMemberEntity> {
        val all = repository.collectionMembers(ALICE)
        return all.filter { it.collectionId == collection.value && it.memberId == member.value }
    }

    private suspend fun heldBefore(cutoffMs: Long) =
        repository
            .heldCollections(ALICE, Instant.fromEpochMilliseconds(cutoffMs))
            .associate { c -> c.collectionId to c.members.map { it.memberId } }

    @Test
    fun tombstoningAnItemEndsItsMemberRowsInEveryCollectionAtTheTombstonesStamp() =
        runBlocking {
            world()
            val stamp = clock.nowMs

            repository.tombstone(ALICE, listOf(film))

            assertEquals(listOf(other.value, show.value), current(boxA))
            assertEquals(listOf("x"), current(boxB))
            val ended = repository.collectionMembers(ALICE).filter { it.memberId == film.value }
            assertEquals(listOf(stamp, stamp), ended.map { it.supersededAtMs })
            repository.assertMemberIntervalsSound(ALICE)
        }

    @Test
    fun tombstoningASeriesEndsItsMemberRowsToo() =
        runBlocking {
            world()
            val stamp = clock.nowMs

            repository.tombstoneSeries(ALICE, listOf(show))

            assertEquals(listOf(film.value, other.value), current(boxA))
            assertEquals(stamp, repository.collectionMembers(ALICE).single { it.memberId == show.value }.supersededAtMs)
        }

    @Test
    fun theReconcilesTombstonesDoTheSame() =
        runBlocking {
            world()
            val stamp = clock.nowMs

            repository.applyReconcile(ALICE, listOf(film), emptyList(), listOf(show), emptyList())

            assertEquals(listOf(other.value), current(boxA))
            assertEquals(listOf("x"), current(boxB))
            assertEquals(setOf(stamp), repository.collectionMembers(ALICE).mapNotNull { it.supersededAtMs }.toSet())
        }

    @Test
    fun theCollectionRowsAndTheirLifecycleAreNotTouched() =
        runBlocking {
            world()
            val before = repository.collections(ALICE)

            repository.tombstone(ALICE, listOf(film))

            assertEquals(before, repository.collections(ALICE))
            assertEquals(emptyList<CollectionVersionEntity>(), repository.collectionVersions(ALICE))
        }

    @Test
    fun aRevivalReAddsNothing() =
        runBlocking {
            world()
            repository.tombstone(ALICE, listOf(film))
            repository.tombstoneSeries(ALICE, listOf(show))
            clock.stepMs(10_000)
            val members = repository.collectionMembers(ALICE)

            repository.revive(ALICE, listOf(film))
            repository.reviveSeries(ALICE, listOf(show))

            assertEquals(members, repository.collectionMembers(ALICE))
            assertEquals(listOf(other.value), current(boxA))
        }

    @Test
    fun theNextCollectionReadDecidesAfterARevival() =
        runBlocking {
            world()
            repository.tombstone(ALICE, listOf(film))
            clock.stepMs(10_000)
            repository.revive(ALICE, listOf(film))
            clock.stepMs(10_000)

            // Jellyfin lists the film again, so this read adds it back as a new interval.
            repository.writeCollection(ALICE, boxB, "B", listOf(cm(film.value), cm("x")))

            assertEquals(listOf(film.value, "x"), current(boxB))
            assertEquals(2, rows(boxB, film).size)
            repository.assertMemberIntervalsSound(ALICE)
        }

    @Test
    fun aSnapshotAfterTheTombstoneDoesNotListTheMemberAndOneBeforeStillDoes() =
        runBlocking {
            world()
            val before = clock.nowMs
            repository.tombstone(ALICE, listOf(film))

            val both = mapOf("box01" to listOf(film.value, other.value, show.value), "box02" to listOf(film.value, "x"))
            assertEquals(both, heldBefore(before))
            val after = mapOf("box01" to listOf(other.value, show.value), "box02" to listOf("x"))
            assertEquals(after, heldBefore(before + 1))
        }

    @Test
    fun anIdInNoCollectionChangesNothing() =
        runBlocking {
            world()
            val members = repository.collectionMembers(ALICE)

            repository.tombstone(ALICE, listOf(ItemId(hexId(99))))

            assertEquals(members, repository.collectionMembers(ALICE))
        }

    @Test
    fun anAlreadySupersededRowKeepsItsEnd() =
        runBlocking {
            world()
            clock.stepMs(5_000)
            repository.writeCollection(ALICE, boxB, "B", listOf(cm("x")))
            val ended = rows(boxB, film).single()
            clock.stepMs(5_000)

            repository.tombstone(ALICE, listOf(film))

            val after = rows(boxB, film).single()
            assertEquals(ended.supersededAtMs, after.supersededAtMs)
        }

    @Test
    fun anotherAccountsCollectionsAreNotTouched() =
        runBlocking {
            world()
            repository.writeCollection(BOB, boxA, "A", listOf(cm(film.value)))

            repository.tombstone(ALICE, listOf(film))

            assertEquals(listOf(film.value), repository.currentMembers(BOB, boxA.value).map { it.substringBefore(':') })
        }

    @Test
    fun aClockSetBackNeverLeavesAnEmptyInterval() =
        runBlocking {
            world()
            clock.rewindMs(1_000_000)

            repository.tombstone(ALICE, listOf(film))

            val ended = repository.collectionMembers(ALICE).filter { it.memberId == film.value }
            assertEquals(listOf(start + 1, start + 1), ended.map { it.supersededAtMs })
            repository.assertMemberIntervalsSound(ALICE)
        }

    @Test
    fun aManyIdTombstoneIsChunked() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            val ids = (0 until 1_000).map { ItemId(hexId(it + 10)) }
            repository.put(ALICE, MOVIES, *ids.map { sitem(it.value.toInt(16)) }.toTypedArray())
            repository.writeCollection(ALICE, boxA, "A", ids.map { cm(it.value) })
            clock.stepMs(1_000)

            repository.tombstone(ALICE, ids)

            assertEquals(emptyList<String>(), current(boxA))
            repository.assertMemberIntervalsSound(ALICE)
        }

    @Test
    fun aFaultAfterTheTombstoneRollsBackTheTombstoneAndTheCascade() =
        runBlocking {
            world()
            val items = repository.currentRows(ALICE)
            val seriesRows = repository.currentSeries(ALICE)
            val members = repository.collectionMembers(ALICE)
            repository.faultBetweenReconcileTombstonesAndRevives = { error("the process died here") }

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.applyReconcile(ALICE, listOf(film), emptyList(), listOf(show), emptyList()) }
            }

            assertEquals(items, repository.currentRows(ALICE))
            assertEquals(seriesRows, repository.currentSeries(ALICE))
            assertEquals(members, repository.collectionMembers(ALICE))
            assertNull(repository.currentRows(ALICE).first { it.itemId == film.value }.deletedAtMs)
        }

    @Test
    fun theSameReconcileWritesWholeOnceTheFaultIsGone() =
        runBlocking {
            world()
            repository.faultBetweenReconcileTombstonesAndRevives = { error("the process died here") }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.applyReconcile(ALICE, listOf(film), emptyList(), emptyList(), emptyList()) }
            }
            repository.faultBetweenReconcileTombstonesAndRevives = null

            repository.applyReconcile(ALICE, listOf(film), emptyList(), emptyList(), emptyList())

            assertEquals(clock.nowMs, repository.currentRows(ALICE).first { it.itemId == film.value }.deletedAtMs)
            assertEquals(listOf(other.value, show.value), current(boxA))
        }

    // Items and series are stored as the server sent their IDs; members are stored in canonical form (32 lower-case
    // hex). A tombstone must still end the member row (Vimes M1).
    private val rawFilm = "ABCDEF01-2345-6789-ABCD-EF0123456789"
    private val canonicalFilm = "abcdef0123456789abcdef0123456789"

    private suspend fun rawWorld() {
        repository.select(ALICE, setOf(MOVIES, SHOWS))
        repository.put(ALICE, MOVIES, sitem(1).copy(id = ItemId(rawFilm)))
        repository.putSeries(ALICE, SHOWS, series(rawFilm.replace('A', 'B')))
        repository.writeCollection(
            ALICE,
            boxA,
            "A",
            listOf(cm(canonicalFilm), cm(canonicalFilm.replace('a', 'b'), CollectionMemberKind.Series)),
        )
        clock.stepMs(10_000)
    }

    @Test
    fun anItemTombstonedUnderItsRawIdEndsTheCanonicalMemberRow() =
        runBlocking {
            rawWorld()

            repository.tombstone(ALICE, listOf(ItemId(rawFilm)))

            assertEquals(listOf(canonicalFilm.replace('a', 'b')), current(boxA))
        }

    @Test
    fun aSeriesTombstonedUnderItsRawIdEndsTheCanonicalMemberRow() =
        runBlocking {
            rawWorld()

            repository.tombstoneSeries(ALICE, listOf(ItemId(rawFilm.replace('A', 'B'))))

            assertEquals(listOf(canonicalFilm), current(boxA))
        }

    @Test
    fun theReconcileEndsCanonicalMemberRowsOfRawIds() =
        runBlocking {
            rawWorld()

            val rawSeries = ItemId(rawFilm.replace('A', 'B'))
            repository.applyReconcile(ALICE, listOf(ItemId(rawFilm)), emptyList(), listOf(rawSeries), emptyList())

            assertEquals(emptyList<String>(), current(boxA))
        }
}
