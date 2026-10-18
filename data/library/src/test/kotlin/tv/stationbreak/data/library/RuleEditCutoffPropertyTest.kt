// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ordering.splitmix64

/**
 * Spec 10 task 3.4.1 (the format's "Edits" rule, for the whole rule snapshot): an edit this TV saw at or after a day's
 * cut-off `c = S - G` leaves day n's `RuleSnapshot` unchanged, and day n + 1 uses it. A seeded generator makes 1,000
 * libraries ([RuleLibraryGen]: items, series and collections with a history before `c`). For each, the snapshot at `c`
 * is taken, then random edits are made with the clock at or after `c`:
 *
 * - items: rating, genre, tag, runtime and season edits, library moves, deletions and revivals;
 * - series: rating, genre and tag edits, deletions and revivals;
 * - collections: members added, removed and changed in kind, a collection deleted and written again (revived).
 *
 * Then (1) the snapshot at `c` is the one taken before, apart from the deletion time of an item deleted after `c`,
 * which the scheduler's type carries and the rule engine never reads; and (2) a snapshot taken after the edits shows
 * them: its items, series and collections equal the cache's current rows.
 *
 * Only things the TV already knew are edited: something first seen after `c` is, by the oldest-copy rule, in day n
 * (spec 09), which is not an edit. A failure prints the case number and the seed: rerun it with `SeededGen(<seed>)`.
 */
class RuleEditCutoffPropertyTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))

    @After
    fun close() = repository.close()

    private fun RuleSnapshot.withoutDeletionTimes() = copy(items = items.map { it.copy(item = it.item.copy(deletedAtMs = null)) })

    private suspend fun snapshot(
        libraries: List<ItemId>,
        cutoffMs: Long,
    ) = repository.ruleSnapshot(ALICE, libraries.toSet(), at(cutoffMs))

    private suspend fun editItems(gen: SeededGen) {
        val stored = repository.stored(ALICE)
        repeat(gen.nextInt(MAX_EDITS)) {
            val row = gen.pick(stored)
            val id = row.item.id
            when (gen.nextInt(ITEM_EDIT_KINDS)) {
                0 -> repository.put(ALICE, row.library, row.item.copy(officialRating = gen.pick(RuleLibraryGen.RATINGS)))
                1 -> repository.put(ALICE, row.library, row.item.copy(genres = row.item.genres + gen.pick(RuleLibraryGen.GENRES)))
                2 -> repository.put(ALICE, row.library, row.item.copy(tags = listOf(gen.pick(RuleLibraryGen.TAGS))))
                3 -> repository.put(ALICE, row.library, row.item.copy(seasonId = ItemId(hexId(0x800 + gen.nextInt(4)))))
                4 -> repository.put(ALICE, otherLibrary(row.library), row.item)
                5 -> repository.tombstone(ALICE, listOf(id))
                else -> repository.revive(ALICE, listOf(id))
            }
            clock.stepMs(1 + gen.nextLong(STEP_MS))
        }
    }

    private fun otherLibrary(library: ItemId) =
        if (library ==
            RuleLibraryGen.LIBRARY_A
        ) {
            RuleLibraryGen.LIBRARY_B
        } else {
            RuleLibraryGen.LIBRARY_A
        }

    private suspend fun editSeries(gen: SeededGen) {
        val rows = repository.currentSeries(ALICE)
        if (rows.isEmpty()) return
        repeat(gen.nextInt(MAX_EDITS)) {
            val row = gen.pick(rows)
            val id = ItemId(row.seriesId)
            when (gen.nextInt(SERIES_EDIT_KINDS)) {
                0 -> {
                    repository.putSeries(ALICE, ItemId(row.libraryId), series(row.seriesId, gen.pick(RuleLibraryGen.RATINGS)))
                }

                1 -> {
                    repository.putSeries(
                        ALICE,
                        ItemId(row.libraryId),
                        series(row.seriesId, row.officialRating, listOf(gen.pick(RuleLibraryGen.GENRES))),
                    )
                }

                2 -> {
                    repository.putSeries(
                        ALICE,
                        ItemId(row.libraryId),
                        series(row.seriesId, row.officialRating, tags = listOf(gen.pick(RuleLibraryGen.TAGS))),
                    )
                }

                3 -> {
                    repository.tombstoneSeries(ALICE, listOf(id))
                }

                else -> {
                    repository.reviveSeries(ALICE, listOf(id))
                }
            }
            clock.stepMs(1 + gen.nextLong(STEP_MS))
        }
    }

    private fun parse(entry: String): CollectionMember {
        val kind = CollectionMemberKind.entries.first { it.name.equals(entry.substringAfter(':'), ignoreCase = true) }
        return CollectionMember(ItemId(entry.substringBefore(':')), kind)
    }

    private suspend fun editCollections(gen: SeededGen) {
        val boxes = repository.collections(ALICE)
        if (boxes.isEmpty()) return
        repeat(gen.nextInt(MAX_EDITS)) {
            val box = gen.pick(boxes)
            val members = repository.currentMembers(ALICE, box.collectionId).map(::parse)
            val id = ItemId(box.collectionId)
            when (gen.nextInt(COLLECTION_EDIT_KINDS)) {
                0 -> {
                    repository.writeCollection(
                        ALICE,
                        id,
                        "Name",
                        members + CollectionMember(ItemId(hexId(0xE00 + gen.nextInt(MAX_EDITS))), gen.pick(KINDS)),
                    )
                }

                1 -> {
                    repository.writeCollection(ALICE, id, "Name", members.drop(1))
                }

                2 -> {
                    repository.writeCollection(
                        ALICE,
                        id,
                        "Name",
                        members.map { if (gen.chance(HALF)) it.copy(kind = gen.pick(KINDS)) else it },
                    )
                }

                3 -> {
                    repository.tombstoneCollections(ALICE, listOf(id))
                }

                else -> {
                    repository.writeCollection(ALICE, id, "Name", members)
                }
            }
            clock.stepMs(1 + gen.nextLong(STEP_MS))
        }
    }

    // The snapshot after the edits, against the cache's current rows: it shows what the edits made.
    private suspend fun assertShowsCurrentState(
        after: RuleSnapshot,
        cutoffMs: Long,
    ) {
        val stored = repository.stored(ALICE).groupBy { CanonicalId.parseOrNull(it.item.id.value) }
        for (item in after.items) {
            val own = stored[item.item.id].orEmpty().singleOrNull() ?: continue
            assertEquals("rating of ${item.item.id}", own.item.officialRating, item.officialRating)
            assertEquals(own.item.genres, item.genres)
            assertEquals(own.item.tags, item.tags)
            assertEquals(own.item.seasonId?.let { CanonicalId.parseOrNull(it.value) }, item.seasonId)
            assertEquals(CanonicalId.parseOrNull(own.library.value), item.libraryId)
            val deletedAt = own.deletedAt?.toEpochMilliseconds()
            assertTrue("deleted item ${item.item.id} kept", deletedAt == null || deletedAt >= cutoffMs)
        }
        val rows = repository.currentSeries(ALICE).associateBy { CanonicalId.parseOrNull(it.seriesId) }
        for ((id, series) in after.series) {
            val row = rows.getValue(id)
            assertEquals(row.officialRating, series.officialRating)
            assertEquals(VersionCodec.decode(row.genres), series.genres)
            assertEquals(VersionCodec.decode(row.tags), series.tags)
            assertTrue("deleted series $id kept", (row.deletedAtMs ?: Long.MAX_VALUE) >= cutoffMs)
        }
        assertCollectionsAreCurrent(after.collections)
    }

    private suspend fun assertCollectionsAreCurrent(held: Map<CanonicalId, RuleCollection>) {
        for ((id, box) in held) {
            val members = repository.currentMembers(ALICE, id.value).map(::parse)

            fun of(kind: CollectionMemberKind) =
                members.filter { it.kind == kind }.mapNotNull { CanonicalId.parseOrNull(it.id.value) }.toSet()
            assertEquals("movies of $id", of(CollectionMemberKind.Movie), box.movies)
            assertEquals(of(CollectionMemberKind.Episode), box.episodes)
            assertEquals(of(CollectionMemberKind.Series), box.series)
            assertEquals(of(CollectionMemberKind.Season), box.seasons)
        }
    }

    // Returns true when the edits changed what day n + 1 sees.
    private fun check(seed: ULong): Boolean =
        runBlocking {
            val gen = SeededGen(seed)
            val library = RuleLibraryGen.fill(repository, clock, gen)
            val cutoffMs = clock.nowMs + MARGIN_MS
            val before = snapshot(library.libraries, cutoffMs)

            clock.atMs(cutoffMs + gen.nextLong(STEP_MS))
            editItems(gen)
            editSeries(gen)
            editCollections(gen)

            val dayN = snapshot(library.libraries, cutoffMs)
            assertEquals("day n changed by an edit at or after its cut-off", before.withoutDeletionTimes(), dayN.withoutDeletionTimes())
            val next = snapshot(library.libraries, clock.nowMs + 1)
            assertShowsCurrentState(next, clock.nowMs + 1)
            next.withoutDeletionTimes().copy(cutoffMs = cutoffMs) != before.withoutDeletionTimes()
        }

    @Test
    fun anEditSeenAtOrAfterTheCutoffNeverChangesDayNAndIsUsedFromDayNPlusOne() {
        var differing = 0
        for (index in 0 until CASES) {
            val seed = splitmix64(BASE_SEED + index.toULong())
            try {
                if (check(seed)) differing++
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Throwable,
            ) {
                val message = "$NAME: case $index failed, seed $seed: ${failure.message}"
                println(message)
                throw AssertionError(message, failure)
            }
        }
        println("$NAME: $CASES cases, $differing where day n + 1 differs from day n")
        // Not vacuous: a good share of the histories really do change the snapshot between day n and day n + 1.
        assertTrue("only $differing cases changed between day n and n + 1", differing > CASES / 2)
    }

    private companion object {
        const val NAME = "RuleEditCutoffPropertyTest"
        const val CASES = 1000
        const val BASE_SEED = 0x5EED_1004uL
        const val MARGIN_MS = 10_000L
        const val STEP_MS = 3_600_000L
        const val MAX_EDITS = 6
        const val HALF = 50
        const val ITEM_EDIT_KINDS = 7
        const val SERIES_EDIT_KINDS = 5
        const val COLLECTION_EDIT_KINDS = 5
        val KINDS =
            listOf(CollectionMemberKind.Movie, CollectionMemberKind.Episode, CollectionMemberKind.Series, CollectionMemberKind.Season)
    }
}
