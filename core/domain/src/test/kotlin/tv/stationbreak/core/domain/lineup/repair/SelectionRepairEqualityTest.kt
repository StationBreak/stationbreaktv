// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The oracle for #684 (a faster untick repair, with the same bytes): the repair of a selection change that main records
 * must equal, step for step, what the frozen [ReferenceSelectionRepair] records, over a matrix of libraries, lineups
 * and changes. "Equal" is the recorded `ChannelRepair`s (the whole record and the change's pins) and the SHA-256 of the
 * stored lineup file, so a repair that differs in one block, one fix or one pinned library fails. Later #684 PRs
 * (the planning dispatcher, one library walk, parallel lanes) leave this test as it is and must keep it green.
 *
 * Three runs: the real `TodayRepairer` against the reference (the one that gates), the production planner driven by the
 * test's own copy of the turn against the reference (proves that copy is faithful, so the mutants mean something),
 * and deliberately broken variants of the production planner, injected through the planner seam of [RepairWorld], which
 * must each fail the matrix (as `DayInputReuseTest` shows a field no scenario pins would go unnoticed).
 *
 * The matrix reaches an empty selection (`after` with nothing in it, what `S - R = {}` would be) although the
 * product cannot get there: `LibrarySelection` answers `LastLibrary` before any repair. The later PRs read `removed`
 * and `after` by position, and an empty `after` is the edge that breaks such code first.
 */
internal class SelectionRepairEqualityTest {
    private class Scenario(
        val name: String,
        val libs: List<SyntheticLib>,
        val shape: LineupShape,
        val body: suspend RepairWorld.() -> Unit,
    )

    private fun lib(
        n: Int,
        size: Int,
        onlyGenre: String? = null,
    ) = SyntheticLib(ItemId("d$n".repeat(16)), ('0' + n), size, 10L + n, onlyGenre)

    private val tiny = listOf(lib(1, 90), lib(2, 70), lib(3, 50), lib(4, 40, "Documentary"))
    private val small = listOf(lib(1, 700), lib(2, 500), lib(3, 250), lib(4, 120, "Documentary"))
    private val catalog = LineupShape.Catalog(startersOn = 14, customs = 8)

    private val boundary = NOON.toEpochMilliseconds() - 7 * HOUR_MS

    private fun scenarios(): List<Scenario> = catalogScenarios() + handScenarios()

    private fun catalogScenarios(): List<Scenario> =
        listOf(
            Scenario("catalog: untick the 250-item library, days laid out", small, catalog) {
                warm()
                untick(setOf(libs[2].id))
            },
            Scenario("catalog: untick the 250-item library, days not laid out", small, catalog) {
                untick(setOf(libs[2].id))
            },
            Scenario("catalog: second untick on repaired days", small, catalog) {
                warm()
                untick(setOf(libs[2].id))
                warm()
                untick(setOf(libs[1].id))
            },
            Scenario("catalog: untick right after an add", small, catalog) {
                chosen = allIds - libs[3].id
                warm()
                add(libs[3].id)
                untick(setOf(libs[2].id))
            },
            Scenario("catalog: untick the one-genre library", small, catalog) {
                warm()
                untick(setOf(libs[3].id))
            },
            Scenario("catalog: untick the biggest library", small, catalog) {
                warm()
                untick(setOf(libs[0].id))
            },
            Scenario("catalog: an add that lands with a remove", small, catalog) {
                chosen = allIds - libs[3].id
                warm()
                change(chosen - libs[2].id + libs[3].id)
            },
            Scenario("catalog: tiny libraries, untick two at once", tiny, catalog) {
                warm()
                untick(setOf(libs[1].id, libs[2].id))
            },
        )

    private fun handScenarios(): List<Scenario> =
        listOf(
            Scenario("hand: no extras filter, untick the 50-item library", tiny, LineupShape.Hand(null)) {
                warm()
                untick(setOf(libs[2].id))
            },
            Scenario("hand: extras filter from the start of time", tiny, LineupShape.Hand(Long.MIN_VALUE)) {
                warm()
                untick(setOf(libs[2].id))
            },
            Scenario("hand: extras cut inside the day, two cut-offs", small, LineupShape.Hand(boundary)) {
                warm()
                untick(setOf(libs[2].id))
            },
            Scenario("hand: items deleted after the cut-off, then an untick", small, LineupShape.Hand(null)) {
                warm()
                deleteAiring(3)
                untick(setOf(libs[2].id))
            },
            Scenario("hand: items deleted, a second untick on the repaired days", small, LineupShape.Hand(boundary)) {
                warm()
                untick(setOf(libs[2].id))
                warm()
                deleteAiring(4)
                untick(setOf(libs[0].id))
            },
            Scenario("hand: untick down to one library", tiny, LineupShape.Hand(null)) {
                warm()
                untick(setOf(libs[1].id, libs[2].id, libs[3].id))
            },
            Scenario("hand: an empty selection after (S - R = {})", tiny, LineupShape.Hand(null)) {
                warm()
                change(emptySet())
            },
            Scenario("hand: days not laid out, untick the biggest", small, LineupShape.Hand(null)) {
                untick(setOf(libs[0].id))
            },
        )

    private fun worldFor(
        scenario: Scenario,
        planner: Planner?,
    ): RepairWorld = RepairWorld(scenario.libs, scenario.shape, planner = planner)

    private fun run(
        scenario: Scenario,
        planner: Planner?,
    ): List<Step> =
        runBlocking {
            val world = worldFor(scenario, planner)
            world.(scenario.body)()
            world.steps
        }

    /** The scenarios whose steps under [candidate] differ from the reference's, with what differs. */
    private fun mismatches(candidate: Planner?): List<String> =
        scenarios().mapNotNull { scenario ->
            val expected = expectedOf(scenario.name)
            val actual = runCatching { run(scenario, candidate) }
            when {
                actual.isFailure -> "${scenario.name}: threw ${actual.exceptionOrNull()}"
                else -> differences(expected, actual.getOrThrow())?.let { "${scenario.name}: $it" }
            }
        }

    private fun differences(
        expected: List<Step>,
        actual: List<Step>,
    ): String? =
        if (expected.size != actual.size) {
            "${expected.size} steps expected, ${actual.size} made"
        } else {
            expected.zip(actual).withIndex().firstNotNullOfOrNull { (index, pair) -> stepDifference(index, pair) }
        }

    private fun stepDifference(
        index: Int,
        pair: Pair<Step, Step>,
    ): String? {
        val (want, got) = pair
        return when {
            want.pins != got.pins -> "step $index pins ${want.pins} vs ${got.pins}"
            want.record != got.record -> "step $index record ${firstRecordDifference(want.record, got.record)}"
            want.digest != got.digest -> "step $index stored bytes ${want.digest} vs ${got.digest}"
            else -> null
        }
    }

    private fun firstRecordDifference(
        want: TodayRepairs,
        got: TodayRepairs,
    ): String {
        val repairs = want.selection.zip(got.selection).indexOfFirst { (a, b) -> a != b }
        return when {
            want.selection.size != got.selection.size -> "${want.selection.size} vs ${got.selection.size} repairs"
            repairs >= 0 -> "repair $repairs (${want.selection[repairs].channel.value}) differs"
            want.fixes != got.fixes -> "fixes differ"
            else -> "other fields differ"
        }
    }

    @Test
    fun theRepairerRecordsWhatTheReferenceRecords() {
        val failures = mismatches(null)
        assertTrue(failures.isEmpty(), "the repairer differs from the reference:\n${failures.joinToString("\n")}")
    }

    @Test
    fun theProductionPlannerInTheTestsOwnTurnRecordsWhatTheReferenceRecords() {
        val failures = mismatches(Planners.production)
        assertTrue(failures.isEmpty(), "the planner differs from the reference:\n${failures.joinToString("\n")}")
    }

    @Test
    fun theReferenceInTheTestsOwnTurnRecordsWhatItsOwnRunRecords() {
        // The same planner twice gives the same bytes: nothing in the matrix (a clock, an order) varies between runs.
        val failures = mismatches(Planners.reference)
        assertTrue(failures.isEmpty(), "the reference differs from itself:\n${failures.joinToString("\n")}")
    }

    @Test
    fun theMatrixReachesWhatItClaimsToPin() {
        val all = scenarios().flatMap { expectedOf(it.name) }
        val repairs = all.flatMap { it.record.selection }
        assertTrue(all.any { it.pins.isNotEmpty() }, "some change pins a programme of a removed library")
        assertTrue(repairs.any { it.tailHidden != null }, "some day has nothing to air after the change")
        assertTrue(repairs.any { it.pinLibrary == null }, "some day has no pin (hidden at the change, or none on air)")
        assertTrue(repairs.any { r -> r.blocks.any { it.itemId != null } }, "some day airs programmes after it")
        val seqs = scenarios().map { s -> expectedOf(s.name).map { it.record.lastSeq } }
        assertTrue(seqs.any { it.size > 1 && it.distinct().size > 1 }, "some scenario changes twice, on repaired days")
        assertTrue(all.map { it.digest }.toSet().size > all.size / 2, "the stored files differ between scenarios")
    }

    // --- The mutation check: each broken variant must fail the matrix ---------------------------------------------

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

    @Test
    fun beforeAndAfterSelectionsSwappedFailTheMatrix() {
        val swapped =
            Planners.mutated { inputs ->
                changeOf(inputs) { SelectionChange(it.after, it.before, it.seq, it.atMs) }
            }

        val failures = mismatches(swapped)

        assertTrue(failures.isNotEmpty(), "the swapped selections went unnoticed")
        println("MUTANT swapped before/after fails ${failures.size} of ${scenarios().size}: ${failures.first()}")
    }

    @Test
    fun theRemovedSnapshotReadFromTheNewSelectionFailsTheMatrix() {
        val mutant =
            Planners.mutated { inputs ->
                val removed = inputs.change.before - inputs.change.after
                val wrapped =
                    object : LibraryRepository by inputs.library {
                        override suspend fun ruleSnapshot(
                            account: LibraryAccount,
                            libraries: Set<ItemId>,
                            cutoff: Instant,
                        ): RuleSnapshot =
                            inputs.library.ruleSnapshot(
                                account,
                                if (libraries == removed) inputs.change.after else libraries,
                                cutoff,
                            )
                    }
                libraryOf(inputs, wrapped)
            }

        val failures = mismatches(mutant)

        assertTrue(failures.isNotEmpty(), "a removed snapshot that holds the new selection went unnoticed")
        println("MUTANT removed read as after fails ${failures.size} of ${scenarios().size}: ${failures.first()}")
    }

    /**
     * The brief's "removed snapshot replaced by `before`" is an equivalent mutant of today's code: the removed
     * libraries' snapshot is read only to name the library of a pinned programme, `before` holds the same items with
     * the same library, so the repair is the same. It is reported, not asserted to fail; the later PR that reads
     * `removed` from one shared walk is the one where it would matter (and its own data test covers it).
     */
    @Test
    fun theRemovedSnapshotReadFromTheOldSelectionIsEquivalentToday() {
        val mutant =
            Planners.mutated { inputs ->
                val removed = inputs.change.before - inputs.change.after
                val wrapped =
                    object : LibraryRepository by inputs.library {
                        override suspend fun ruleSnapshot(
                            account: LibraryAccount,
                            libraries: Set<ItemId>,
                            cutoff: Instant,
                        ): RuleSnapshot =
                            inputs.library.ruleSnapshot(
                                account,
                                if (libraries == removed) inputs.change.before else libraries,
                                cutoff,
                            )
                    }
                libraryOf(inputs, wrapped)
            }

        val failures = mismatches(mutant)

        println("MUTANT removed read as before fails ${failures.size} of ${scenarios().size} (equivalent if 0)")
    }

    @Test
    fun aLivenessAnswerForcedTrueFailsTheMatrix() {
        val mutant =
            Planners.mutated { inputs ->
                val wrapped =
                    object : LibraryRepository by inputs.library {
                        // Every id asked about has a current row, whatever the library holds: a deleted one included.
                        override suspend fun currentIds(
                            account: LibraryAccount,
                            ids: Set<CanonicalId>,
                        ): Set<CanonicalId> = ids
                    }
                libraryOf(inputs, wrapped)
            }

        val failures = mismatches(mutant)

        assertTrue(failures.isNotEmpty(), "a liveness answer forced true went unnoticed")
        println("MUTANT liveness forced true fails ${failures.size} of ${scenarios().size}: ${failures.first()}")
    }

    private fun expectedOf(name: String): List<Step> =
        cached.getOrPut(name) { run(scenarios().first { it.name == name }, Planners.reference) }

    private companion object {
        const val HOUR_MS = 3_600_000L

        // The reference's steps are the same for every test of the class: worked out once per scenario.
        val cached = java.util.concurrent.ConcurrentHashMap<String, List<Step>>()
    }
}
