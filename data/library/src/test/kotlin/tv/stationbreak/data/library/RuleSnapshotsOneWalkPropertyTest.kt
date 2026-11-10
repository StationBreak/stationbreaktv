// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.time.Instant

/**
 * #684 PR 4: `ruleSnapshots` (one walk, several selections) gives, for each selection, exactly what `ruleSnapshot`
 * gives for it alone, and what the frozen [ReferenceRuleSnapshot] (the assembly as it was before the walk) reads,
 * folded names included (`RuleSnapshot ==` ignores them, and a rule match reads them). The walks are
 * `[S, S - R, R]`, as the selection repair asks them, and the cases that make it hard:
 * - the repository's item chunk is 2, 3 and 500 (the internal seam), so a chunk boundary that splits an item's
 *   as-of choice from its neighbours shows, and the union's read is cut at other places than each selection's own;
 * - an empty new selection (`S` empty, or `S == R`), an empty removed set, the same set twice, and the libraries' IDs
 *   spelled as a server may (dashed, upper case), which the filter reads as other libraries (it is on the raw string);
 * - cut-offs at, one millisecond before and one after every `seenFromMs`, `supersededAtMs` and `deletedAtMs`; two
 *   or more cut-offs per library, each its own walk;
 * - ownership moves through `ItemHistoryDao.planMove` (not `item_also_listed` rows), items tombstoned and revived
 *   before and after the cut-off, series shared across libraries, collections, `collectionsSynced` true and false;
 * - per walk, one read of the items, one read of the series and one `RuleItemMapper` per distinct selection.
 *
 * A failure prints the seed: rerun it with `SeededGen(<seed>)`.
 */
class RuleSnapshotsOneWalkPropertyTest {
    private val clock = FakeClock()
    private val pair = TestLibraries.inMemoryWithDatabase(clock, signedIn = listOf(ALICE))
    private val repository = pair.first
    private val db = pair.second

    @After
    fun close() = repository.close()

    private val a = RuleLibraryGen.LIBRARY_A
    private val b = RuleLibraryGen.LIBRARY_B

    // The pool the selections are drawn from: both libraries, and each spelled another way (another library to the
    // database, which filters on the raw string).
    private val pool = listOf(a, b, ItemId(dashedUpper(a.value)), ItemId(dashedUpper(b.value)))

    // What a case read, to prove the corpus is not empty and the walks are not trivial.
    private class Counts {
        var walks = 0
        var elements = 0
        var items = 0
        var series = 0
        var collections = 0
        var synced = 0
        var unsynced = 0
        var emptyAfter = 0
        var multiChunk = 0
        var edges = 0
        var removedNotEmpty = 0
    }

    // Moves some items to the other library through `planMove`, as the owner change of a reconcile does.
    private suspend fun moveSome(gen: SeededGen) {
        val rows = repository.currentRows(ALICE).filter { it.libraryId == a.value || it.libraryId == b.value }
        val targets = HashMap<String, String>()
        for (row in rows) {
            if (gen.chance(MOVE_PERCENT)) targets[row.itemId] = if (row.libraryId == a.value) b.value else a.value
        }
        if (targets.isEmpty()) return
        clock.stepMs(1 + gen.nextLong(MOVE_STEP_MS))
        moveItems(repository.dao, repository.history, ALICE.server.value, ALICE.user.value, targets, clock.nowMs, null)
    }

    // The moments the as-of rule turns on, each with a millisecond either side: a sample of them.
    private suspend fun edgeCutoffs(gen: SeededGen): List<Instant> {
        val moments = HashSet<Long>()
        for (row in repository.currentRows(ALICE)) {
            moments += row.seenFromMs
            row.deletedAtMs?.let { moments += it }
        }
        for (version in repository.versions(ALICE)) {
            moments += version.seenFromMs
            moments += version.supersededAtMs
            version.deletedAtMs?.let { moments += it }
        }
        val edges = moments.sorted().flatMap { listOf(it - 1, it, it + 1) }
        return edges.sortedBy { gen.nextULong() }.take(EDGE_CUTOFFS).map(Instant::fromEpochMilliseconds)
    }

    private fun foldsOf(snapshot: RuleSnapshot): List<List<String>> =
        snapshot.items.flatMap { listOf(it.foldedGenres, it.foldedTags, it.foldedStudios) } +
            snapshot.series.values.flatMap { listOf(it.foldedGenres, it.foldedTags, it.foldedStudios) }

    private fun subsetOf(gen: SeededGen): Set<ItemId> = pool.filterTo(LinkedHashSet()) { gen.chance(SUBSET_PERCENT) }

    // [S, S - R, R] for a random S and a random R inside it; some are the shapes that go wrong (see the class note).
    private fun walksOf(gen: SeededGen): List<List<Set<ItemId>>> {
        val random =
            List(RANDOM_WALKS) {
                val s = subsetOf(gen)
                val r = s.filterTo(LinkedHashSet()) { gen.chance(SUBSET_PERCENT) }
                listOf(s, s - r, r)
            }
        val fixed =
            listOf(
                listOf(setOf(a, b), setOf(a), setOf(b)),
                // The new selection empty, and the removed set the whole selection: `S - R` is empty.
                listOf(setOf(a), emptySet(), setOf(a)),
                // Nothing at all, and the same set three times.
                listOf(emptySet(), emptySet(), emptySet()),
                listOf(setOf(a, b), setOf(a, b), setOf(a, b)),
                // The raw-string filter: a spelling of a library is another library.
                listOf(setOf(a, ItemId(dashedUpper(b.value))), setOf(a), setOf(ItemId(dashedUpper(b.value)))),
                // Two selections only (a first untick that reads no current selection).
                listOf(setOf(b), setOf(b)),
                listOf(setOf(a, b)),
            )
        return random + fixed
    }

    private fun check(
        seed: ULong,
        counts: Counts,
    ) = runBlocking {
        val gen = SeededGen(seed)
        val generated = RuleLibraryGen.fill(repository, clock, gen, spellings = true)
        moveSome(gen)
        val edges = edgeCutoffs(gen)
        counts.edges += edges.size
        val walks = walksOf(gen)
        for (cutoff in generated.cutoffs + edges) {
            for (selections in walks) {
                for (chunk in CHUNKS) checkWalk(seed, cutoff, selections, chunk, counts)
            }
        }
    }

    private suspend fun checkWalk(
        seed: ULong,
        cutoff: Instant,
        selections: List<Set<ItemId>>,
        chunk: Int,
        counts: Counts,
    ) {
        val label = "seed $seed, cut-off $cutoff, chunk $chunk, selections $selections"
        val heard = ArrayList<String>()
        repository.traceWalk = { heard += it }
        val walked =
            try {
                repository.ruleSnapshots(ALICE, selections, cutoff, chunk)
            } finally {
                repository.traceWalk = null
            }
        val distinct = selections.distinct().size
        assertEquals("$label: one snapshot per selection", selections.size, walked.size)
        assertEquals("$label: one read of the items", 1, heard.count { it == "items" })
        assertEquals("$label: one series read per selection", distinct, heard.count { it == "series" })
        assertEquals("$label: one mapper per selection", distinct, heard.count { it == "mapper" })
        for ((i, selection) in selections.withIndex()) {
            val alone = repository.ruleSnapshot(ALICE, selection, cutoff)
            val reference = ReferenceRuleSnapshot.read(db, ALICE, selection, cutoff, chunk)
            assertEquals("$label, element $i: equals ruleSnapshot", alone, walked[i])
            assertEquals("$label, element $i: equals the reference", reference, walked[i])
            assertEquals("$label, element $i: the folded names", foldsOf(reference), foldsOf(walked[i]))
            counts.elements++
            counts.items += walked[i].items.size
            counts.series += walked[i].series.size
            counts.collections += walked[i].collections.size
            if (walked[i].collectionsSynced) counts.synced++ else counts.unsynced++
            if (walked[i].items.size > CHUNKS.min()) counts.multiChunk++
        }
        counts.walks++
        if (selections.isNotEmpty() && walked[0].items.isEmpty()) counts.emptyAfter++
        if (selections.size == 3 && walked[2].items.isNotEmpty()) counts.removedNotEmpty++
    }

    @Test
    fun theWalkGivesEachSelectionWhatItsOwnReadGivesOverGeneratedLibraries() {
        val counts = Counts()
        for (case in 0 until CASES) check(SEED_BASE + case.toULong(), counts)
        assertTrue("the corpus has items to compare (${counts.items})", counts.items > 0)
        assertTrue("the corpus has series to compare (${counts.series})", counts.series > 0)
        assertTrue("the corpus has collections to compare (${counts.collections})", counts.collections > 0)
        assertTrue("some reads have collectionsSynced true (${counts.synced})", counts.synced > 0)
        assertTrue("some reads have collectionsSynced false (${counts.unsynced})", counts.unsynced > 0)
        assertTrue("some walks have an empty new selection (${counts.emptyAfter})", counts.emptyAfter > 0)
        assertTrue("some walks have items in the removed set (${counts.removedNotEmpty})", counts.removedNotEmpty > 0)
        assertTrue("some reads span more than one chunk of ${CHUNKS.min()}", counts.multiChunk > 0)
        assertTrue("edge cut-offs were read (${counts.edges})", counts.edges > 0)
        println(
            "RuleSnapshotsOneWalkPropertyTest: ${counts.walks} walks, ${counts.elements} snapshots, " +
                "${counts.items} items",
        )
    }

    private companion object {
        const val CASES = 40
        const val SEED_BASE = 0x6840UL
        const val MOVE_PERCENT = 30
        const val MOVE_STEP_MS = 3_600_000L
        const val EDGE_CUTOFFS = 6
        const val RANDOM_WALKS = 4
        const val SUBSET_PERCENT = 55

        // The item chunks the walk reads in: 2 and 3 cross chunk boundaries, 500 is the app's own.
        val CHUNKS = listOf(2, 3, 500)
    }
}
