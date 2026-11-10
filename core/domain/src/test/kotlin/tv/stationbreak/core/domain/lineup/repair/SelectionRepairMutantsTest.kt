// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The mutation check of #684's equality oracle (as `DayInputReuseTest` shows a field no scenario pins would go
 * unnoticed): each deliberately broken variant of the production planner, injected from outside through the planner
 * seam of `RepairWorld` ([Planners.mutated]), must fail the matrix of [RepairMatrix].
 *
 * Unlike the reference, the matrix and the golden digests, THIS file may be updated by later #684 PRs: when a PR
 * changes what the planner reads (a lane, a primed liveness answer, one walk for all selections), it adds the
 * variants that break its new seams (`Fills.prime` put against putIfAbsent, a walk position swapped...) and keeps
 * these failing.
 */
internal class SelectionRepairMutantsTest {
    private fun changeOf(
        inputs: PlanInputs,
        with: (SelectionChange) -> SelectionChange,
    ) = PlanInputs(
        inputs.days,
        inputs.library,
        inputs.account,
        inputs.held,
        with(inputs.change),
        inputs.shared,
        inputs.today,
    )

    private fun libraryOf(
        inputs: PlanInputs,
        library: LibraryRepository,
    ) = PlanInputs(inputs.days, library, inputs.account, inputs.held, inputs.change, inputs.shared, inputs.today)

    private fun sharedOf(
        inputs: PlanInputs,
        shared: SharedRead,
    ) = PlanInputs(inputs.days, inputs.library, inputs.account, inputs.held, inputs.change, shared, inputs.today)

    // The library, with a snapshot of the removed libraries read as [asked] says (the selection to read instead). The
    // repair reads all its selections in one walk now (#684), so it is the walk's removed element that is swapped.
    private fun readingRemovedAs(
        inputs: PlanInputs,
        asked: (SelectionChange) -> Set<ItemId>,
    ): LibraryRepository {
        val removed = inputs.change.before - inputs.change.after
        return object : LibraryRepository by inputs.library {
            override suspend fun ruleSnapshot(
                account: LibraryAccount,
                libraries: Set<ItemId>,
                cutoff: Instant,
            ): RuleSnapshot {
                val read = if (libraries == removed) asked(inputs.change) else libraries
                return inputs.library.ruleSnapshot(account, read, cutoff)
            }

            override suspend fun ruleSnapshots(
                account: LibraryAccount,
                selections: List<Set<ItemId>>,
                cutoff: Instant,
            ): List<RuleSnapshot> =
                inputs.library.ruleSnapshots(
                    account,
                    selections.map { if (it == removed) asked(inputs.change) else it },
                    cutoff,
                )
        }
    }

    // The library, with the walk's list of snapshots put through [reorder]: a position of the walk (the new selection,
    // the current one, the removed libraries) read for another.
    private fun walkReordered(
        inputs: PlanInputs,
        reorder: (List<RuleSnapshot>) -> List<RuleSnapshot>,
    ): LibraryRepository =
        object : LibraryRepository by inputs.library {
            override suspend fun ruleSnapshots(
                account: LibraryAccount,
                selections: List<Set<ItemId>>,
                cutoff: Instant,
            ): List<RuleSnapshot> = reorder(inputs.library.ruleSnapshots(account, selections, cutoff))
        }

    private fun assertFails(
        what: String,
        mutant: Planner,
    ) {
        val failures = RepairMatrix.mismatches(mutant)
        assertTrue(failures.isNotEmpty(), "$what went unnoticed")
        val of = RepairMatrix.scenarios().size
        println("MUTANT $what fails ${failures.size} of $of: ${failures.first().take(MAX_SHOWN)}")
    }

    @Test
    fun beforeAndAfterSelectionsSwappedFailTheMatrix() {
        assertFails(
            "swapped before/after",
            Planners.mutated { inputs -> changeOf(inputs) { SelectionChange(it.after, it.before, it.seq, it.atMs) } },
        )
    }

    @Test
    fun theRemovedSnapshotReadFromTheNewSelectionFailsTheMatrix() {
        assertFails(
            "removed read as after",
            Planners.mutated { inputs -> libraryOf(inputs, readingRemovedAs(inputs) { it.after }) },
        )
    }

    @Test
    fun theSharedReadOfTheOldSelectionAnsweredWithTheRemovedIsNotReadAnyMore() {
        // Before #684's one walk, a repaired day read what the channel admits of the selection before the change
        // through the source's shared reader. The walk reads it now, so this variant changes nothing: it is printed,
        // and the walk's own variants below are what fail the matrix instead.
        val mutant =
            Planners.mutated { inputs ->
                val removed = inputs.change.before - inputs.change.after
                sharedOf(inputs) { _, cuts -> inputs.shared(removed, cuts) }
            }

        val failures = RepairMatrix.mismatches(mutant)

        println("MUTANT shared read of before answered with removed fails ${failures.size} (0: the reader is unused)")
    }

    @Test
    fun theWalksNewAndCurrentSnapshotsSwappedFailTheMatrix() {
        assertFails(
            "walk position after<->before swapped",
            Planners.mutated { inputs ->
                libraryOf(inputs, walkReordered(inputs) { if (it.size >= 2) listOf(it[1], it[0]) + it.drop(2) else it })
            },
        )
    }

    @Test
    fun theWalksCurrentAndRemovedSnapshotsSwappedFailTheMatrix() {
        // The walk's list is [after, before, removed] when a day is repaired and a programme is on air.
        assertFails(
            "walk position before<->removed swapped",
            Planners.mutated { inputs ->
                libraryOf(inputs, walkReordered(inputs) { if (it.size == THREE) listOf(it[0], it[2], it[1]) else it })
            },
        )
    }

    @Test
    fun theWalksNewSnapshotReadAsEmptyFailsTheMatrix() {
        assertFails(
            "walk after read as empty",
            Planners.mutated { inputs ->
                libraryOf(
                    inputs,
                    walkReordered(inputs) { listOf(it[0].copy(items = emptyList())) + it.drop(1) },
                )
            },
        )
    }

    /**
     * The removed snapshot read as `before` is an equivalent mutant of today's code: the removed libraries' snapshot is
     * read only to name the library of a pinned programme, and `libraryOf` looks in the new selection's snapshot first.
     * The fallback to the removed snapshot reaches only items of the removed libraries, which `before` holds too, with
     * the same library each. So the repair is the same, and it stays so now that `removed` is read in the one walk
     * (#684 PR 4): the walk's variants that do fail the matrix are the position swaps above. It is printed, not
     * asserted.
     */
    @Test
    fun theRemovedSnapshotReadFromTheOldSelectionIsEquivalentToday() {
        val mutant = Planners.mutated { inputs -> libraryOf(inputs, readingRemovedAs(inputs) { it.before }) }

        val failures = RepairMatrix.mismatches(mutant)

        val of = RepairMatrix.scenarios().size
        println("MUTANT removed read as before fails ${failures.size} of $of (equivalent if 0)")
    }

    @Test
    fun aLivenessAnswerForcedTrueFailsTheMatrix() {
        // The nearest injection without a production seam to "a liveness answer forced true for an unasked id": every
        // id asked about has a current row, whatever the library holds (a deleted one included).
        val mutant =
            Planners.mutated { inputs ->
                libraryOf(
                    inputs,
                    object : LibraryRepository by inputs.library {
                        override suspend fun currentIds(
                            account: LibraryAccount,
                            ids: Set<CanonicalId>,
                        ): Set<CanonicalId> = ids
                    },
                )
            }

        assertFails("liveness forced true", mutant)
    }

    private companion object {
        const val MAX_SHOWN = 160
        const val THREE = 3
    }
}
