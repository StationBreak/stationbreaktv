// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

// Part of the oracle for #684 (with `ReferenceSelectionRepair`, `RepairWorld` and `SelectionRepairEqualityTest`):
// DO NOT EDIT in a later #684 PR. The mutants that may be updated are in `SelectionRepairMutantsTest`.

/** One scenario of the matrix: a set of libraries, a lineup, and what happens to the TV. */
internal class Scenario(
    val name: String,
    val libs: List<SyntheticLib>,
    val shape: LineupShape,
    val body: suspend RepairWorld.() -> Unit,
)

/**
 * The fixture matrix of #684's equality oracle, and the way to run it with a planner ([RepairWorld]) and compare what
 * each planner records with what the frozen reference records.
 */
internal object RepairMatrix {
    private const val HOUR_MS = 3_600_000L

    @Suppress("LongParameterList") // A library's setup, one for one; all but the first two defaulted.
    private fun lib(
        n: Int,
        size: Int,
        onlyGenre: String? = null,
        between: Boolean = false,
        relisting: SyntheticLib? = null,
        extras: Boolean = false,
    ) = SyntheticLib(ItemId("d$n".repeat(16)), '0' + n, size, 10L + n, onlyGenre, between, relisting, extras)

    // The first two libraries have items created and deleted between the cut-offs of the day (noon the day before and
    // 00:00 on the day): a snapshot read at one holds other items than one read at the other.
    private val tiny =
        listOf(
            lib(1, 90, between = true, extras = true),
            lib(2, 70, between = true),
            lib(3, 50),
            lib(4, 40, "Documentary"),
        )
    private val first = lib(1, 700, between = true, extras = true)

    // The fifth library's episodes name the series of the first (a series shared across libraries).
    private val small =
        listOf(
            first,
            lib(2, 500, between = true, extras = true),
            lib(3, 250),
            lib(4, 120, "Documentary"),
            lib(5, 150, relisting = first),
        )
    private val catalog = LineupShape.Catalog(startersOn = 14, customs = 8)
    private val boundary = NOON.toEpochMilliseconds() - 7 * HOUR_MS

    /** Every scenario, in a fixed order. */
    fun scenarios(): List<Scenario> = catalogScenarios() + handScenarios()

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
            Scenario("catalog: untick the biggest library, whose series another shares", small, catalog) {
                warm()
                untick(setOf(libs[0].id))
            },
            Scenario("catalog: untick the library that shares a series", small, catalog) {
                warm()
                untick(setOf(libs[4].id))
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
            Scenario("hand: two cut-offs, items change between them", small, LineupShape.Hand(null)) {
                warm()
                untick(setOf(libs[1].id))
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

    /** Runs [scenario] on a fresh TV, with [planner] (null: the real repairer), and gives its steps. */
    fun run(
        scenario: Scenario,
        planner: Planner?,
    ): List<Step> =
        runBlocking {
            val world = RepairWorld(scenario.libs, scenario.shape, planner = planner, firstSeenFilter = true)
            world.(scenario.body)()
            world.steps
        }

    /**
     * The ids of the items each distinct cut-off of the hand lineup's days reads (a snapshot of every library at it):
     * the cut-offs read other items, since some were created and some deleted between them.
     */
    fun itemIdsByCutoff(): Map<Long, Set<CanonicalId>> =
        runBlocking {
            val world = RepairWorld(small, LineupShape.Hand(null), firstSeenFilter = true)
            val held = checkNotNull(world.service.loaded(ACCOUNT))
            val scheduled = LineupDays(world.service.catalog).scheduled(held.lineup, world.atMs, held.meta.extrasFromMs)
            scheduled.map { it.cutoffMs }.distinct().associateWith { cutoffMs ->
                val cutoff = Instant.fromEpochMilliseconds(cutoffMs)
                world.library
                    .ruleSnapshot(ACCOUNT, world.allIds, cutoff)
                    .items
                    .mapTo(HashSet()) { it.item.id }
            }
        }

    /**
     * How many items the extras filter takes out of the snapshot of every library at each of the hand lineup's
     * cut-offs: some out of each (the libraries with extras hold trailers), so a day the filter applies to reads a
     * smaller pool than one it does not.
     */
    fun extrasDropped(): List<Int> =
        runBlocking {
            val world = RepairWorld(small, LineupShape.Hand(null), firstSeenFilter = true)
            val held = checkNotNull(world.service.loaded(ACCOUNT))
            val scheduled = LineupDays(world.service.catalog).scheduled(held.lineup, world.atMs, held.meta.extrasFromMs)
            scheduled.map { it.cutoffMs }.distinct().map { cutoffMs ->
                val plain = world.library.ruleSnapshot(ACCOUNT, world.allIds, Instant.fromEpochMilliseconds(cutoffMs))
                plain.items.size -
                    ExtrasFilter
                        .filter(plain)
                        .snapshot.items.size
            }
        }

    private val references = ConcurrentHashMap<String, List<Step>>()

    /** The reference's steps of the scenario named [name], worked out once. */
    fun referenceOf(name: String): List<Step> {
        val scenario = scenarios().first { it.name == name }
        return references.getOrPut(name) { run(scenario, Planners.reference) }
    }

    /** The scenarios whose steps under [candidate] differ from the reference's, with what differs. */
    fun mismatches(candidate: Planner?): List<String> =
        scenarios().mapNotNull { scenario ->
            val expected = referenceOf(scenario.name)
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
}
