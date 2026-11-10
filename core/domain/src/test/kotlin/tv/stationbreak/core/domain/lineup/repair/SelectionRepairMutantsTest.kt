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

    // The library, with a snapshot of the removed libraries read as [asked] says (the selection to read instead).
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
        }
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
    fun theSharedReadOfTheOldSelectionAnsweredWithTheRemovedFailsTheMatrix() {
        // On a repaired day `SelectionRepair` reads what the channel admits of the selection before the change, through
        // the source's shared reader: answered here for the removed libraries alone.
        val mutant =
            Planners.mutated { inputs ->
                val removed = inputs.change.before - inputs.change.after
                sharedOf(inputs) { _, cuts -> inputs.shared(removed, cuts) }
            }

        assertFails("shared read of before answered with removed", mutant)
    }

    /**
     * The removed snapshot read as `before` is an equivalent mutant of today's code: the removed libraries' snapshot is
     * read only to name the library of a pinned programme, and `libraryOf` looks in the new selection's snapshot first.
     * The fallback to the removed snapshot reaches only items of the removed libraries, which `before` holds too, with
     * the same library each. So the repair is the same. It is printed, not asserted; a later PR that reads `removed`
     * from one shared walk is the one where this variant could matter (add it to this class as a failing one then).
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
    }
}
