// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.time.Instant

/**
 * #684, the data-layer oracle: over generated libraries, the repository's `ruleSnapshot` equals the frozen
 * [ReferenceRuleSnapshot] at every cut-off and selection tried, and so do the folded-name caches of the items and the
 * series (`RuleSnapshot ==` ignores them, and a rule match reads them). The one-walk PR changes what `ruleSnapshot`
 * reads; this stays as it is and is the gate: the snapshot of each selection of one walk must equal this reference's.
 * (That PR adds its own cases for the walk's list of selections, `[S, S - R, R]`.)
 *
 * What the generated libraries and the reads cover:
 * - the reference reads the items in chunks of 2, 3 and 500 (the repository always reads 500), so a chunk boundary
 *   that splits the as-of choice of an item from its neighbours would show;
 * - selections of both libraries, either, none (empty), the library IDs spelled as a server may spell them (dashed,
 *   upper case: the filter is on the raw string), and one library of each spelling;
 * - cut-offs exactly at, one millisecond before and one after every `seenFromMs`, `supersededAtMs` and
 *   `deletedAtMs` the history holds (the as-of rule's edges), beside the generator's own cut-offs; two or more
 *   cut-offs per library;
 * - ownership moves through [moveItems] (`ItemHistoryDao.planMove`), which versions an item under its old library
 *   when it goes to another, beside the generator's own puts to another library;
 * - items edited, tombstoned and revived; the same ID in two spellings; series and collections as the generator
 *   makes them.
 *
 * A failure prints the seed: rerun it with `SeededGen(<seed>)`.
 */
class RuleSnapshotReferenceEqualityTest {
    private val clock = FakeClock()
    private val pair = TestLibraries.inMemoryWithDatabase(clock, signedIn = listOf(ALICE))
    private val repository = pair.first
    private val db = pair.second

    @After
    fun close() = repository.close()

    // What one case read, to prove the corpus is not empty.
    private class Counts {
        var reads = 0
        var items = 0
        var series = 0
        var collections = 0
        var multiChunk = 0
        var edges = 0
    }

    private val a = RuleLibraryGen.LIBRARY_A
    private val b = RuleLibraryGen.LIBRARY_B

    private val selections: List<Set<ItemId>> =
        listOf(
            setOf(a, b),
            setOf(a),
            setOf(b),
            emptySet(),
            // The raw-string filter: a library spelled another way is another library to the database.
            setOf(ItemId(dashedUpper(a.value))),
            setOf(a, ItemId(dashedUpper(b.value))),
        )

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
        return edges.shuffledBy(gen).take(EDGE_CUTOFFS).map(Instant::fromEpochMilliseconds)
    }

    private fun <T> List<T>.shuffledBy(gen: SeededGen): List<T> = sortedBy { gen.nextULong() }

    private fun foldsOf(snapshot: RuleSnapshot): List<List<String>> =
        snapshot.items.flatMap { listOf(it.foldedGenres, it.foldedTags, it.foldedStudios) } +
            snapshot.series.values.flatMap { listOf(it.foldedGenres, it.foldedTags, it.foldedStudios) }

    private fun check(
        seed: ULong,
        counts: Counts,
    ) = runBlocking {
        val gen = SeededGen(seed)
        val generated = RuleLibraryGen.fill(repository, clock, gen)
        moveSome(gen)
        val edges = edgeCutoffs(gen)
        counts.edges += edges.size
        for (cutoff in generated.cutoffs + edges) {
            for (selection in selections) {
                val label = "seed $seed, cut-off $cutoff, libraries $selection"
                val actual = repository.ruleSnapshot(ALICE, selection, cutoff)
                for (chunk in CHUNKS) {
                    val reference = ReferenceRuleSnapshot.read(db, ALICE, selection, cutoff, chunk)
                    assertEquals("$label, chunk $chunk: the snapshot", reference, actual)
                    assertEquals("$label, chunk $chunk: the folded names", foldsOf(reference), foldsOf(actual))
                }
                counts.reads++
                counts.items += actual.items.size
                counts.series += actual.series.size
                counts.collections += actual.collections.size
                if (actual.items.size > CHUNKS.min()) counts.multiChunk++
                assertEquals(selection.mapNotNull { CanonicalId.parseOrNull(it.value) }.toSet(), actual.libraries)
            }
        }
    }

    @Test
    fun theSnapshotsEqualTheReferenceOverGeneratedLibraries() {
        val counts = Counts()
        for (case in 0 until CASES) check(SEED_BASE + case.toULong(), counts)
        assertTrue("the corpus has items to compare (${counts.items})", counts.items > 0)
        assertTrue("the corpus has series to compare (${counts.series})", counts.series > 0)
        assertTrue("the corpus has collections to compare (${counts.collections})", counts.collections > 0)
        assertTrue("some reads span more than one chunk of ${CHUNKS.min()}", counts.multiChunk > 0)
        assertTrue("edge cut-offs were read (${counts.edges})", counts.edges > 0)
        println("RuleSnapshotReferenceEqualityTest: ${counts.reads} reads, ${counts.items} items")
    }

    private companion object {
        const val CASES = 60
        const val SEED_BASE = 0x684UL
        const val MOVE_PERCENT = 30
        const val MOVE_STEP_MS = 3_600_000L
        const val EDGE_CUTOFFS = 12

        // The item chunks the reference reads in (the repository's own is 500).
        val CHUNKS = listOf(2, 3, 500)
    }
}
