// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.rules.AdultMark

/**
 * One fixture run of the gate: the snapshot file [file] (in the fixtures folder), whether the fixture opts After Hours
 * in, the most leftover hours it may hold before the warnings say so ([leftoverCapHours], null for no cap) and whether
 * it is the kids fixture ([kidsHomes]: a kids-band themed channel must have homes), and the [customs] the viewer has
 * built, with the numbers they hold. Several cases may share a file.
 */
internal class GateCase(
    val key: String,
    val file: String,
    val afterHours: Boolean = false,
    val leftoverCapHours: Int? = null,
    val kidsHomes: Boolean = false,
    val customs: List<CustomChannel> = emptyList(),
)

/** What one case produced: the deterministic [report] (the golden), the hard-fail [violations] and the [warnings]. */
internal class GateResult(
    val case: GateCase,
    val report: String,
    val violations: List<Violation>,
    val warnings: List<String>,
)

/**
 * The household-view lineup gate (#1035): the real planner on a frozen fixture, twice (the default picks, then every
 * non-opt-in channel switched on, with After Hours on only when the fixture opts in), each pass seen the way a day
 * build airs it ([HouseholdViews]), checked by [GateInvariants] and summarised for the goldens.
 */
internal object LineupGate {
    const val MAX_CHANNEL_HOURS = 300.0
    const val MIN_CHANNEL_HOURS = 12.0
    private const val HEAVY_OVERLAP = 0.75
    private const val HEAVY_OVERLAP_HOURS = 12.0
    private const val MS_PER_HOUR = 3_600_000.0
    private const val TENTHS = 10.0

    fun run(
        case: GateCase,
        input: AuditInput,
        epoch: EpochCut,
    ): GateResult {
        val snapshot = ExtrasFilter.filter(input.snapshot).snapshot
        val names = LineupAudit.collectionNamesOf(snapshot)
        val base = PlannedLineup.of(snapshot, names, null)
        val picks =
            base.lineup.channels
                .filter { it.isOnStarter() }
                .map { (it.source as ChannelSource.Starter).recipeKey }
                .sorted()
        val optIn = if (case.afterHours) listOf(AdultMark.AFTER_HOURS_KEY) else emptyList()
        val ownChoice = optIn.isEmpty() && case.customs.isEmpty()
        val standard = if (ownChoice) base else PlannedLineup.of(snapshot, names, Viewer(optIn, case.customs))
        val everyKey = StarterCatalog.recipes.filter { !it.optIn && it.key !in picks }.map { it.key }
        val everything = PlannedLineup.of(snapshot, names, Viewer(everyKey + optIn, case.customs))

        val top = LinkedHashMap<String, JsonElement>()
        top["case"] = JsonPrimitive(case.key)
        top["picks"] = JsonArray(picks.map(::JsonPrimitive))
        val violations = ArrayList(FailUpward.check(snapshot))
        val warnings = ArrayList<String>()
        for ((name, planned) in listOf("default" to standard, "everything" to everything)) {
            val view = HouseholdViews.of(planned, epoch)
            violations += GateInvariants.check(name, view, case, picks)
            val pass = GatePass(name, planned, view, case)
            warnings += pass.warnings()
            pass.addTo(top)
        }
        return GateResult(case, JsonLayout.layout(top), violations, warnings)
    }

    private fun LineupChannel.isOnStarter() = source is ChannelSource.Starter && state == ChannelState.On

    private class GatePass(
        val name: String,
        val planned: PlannedLineup,
        val view: HouseholdView,
        val case: GateCase,
    ) {
        private val runtime = view.snapshot.items.associate { it.item.id to it.item.runtimeMs }
        private val titles = view.snapshot.items.associate { it.item.id to it.item.groupId }
        private val onStarters = view.channels.filter { !it.leftovers && !LineupIds.isCustom(it.id) }

        fun hours(c: GateChannel): Double = tenths(c.items.sumOf { runtime.getValue(it.id) } / MS_PER_HOUR)

        private fun titlesOf(c: GateChannel): Int = c.items.mapTo(HashSet()) { it.groupId }.size

        private fun tenths(value: Double): Double = Math.round(value * TENTHS) / TENTHS

        fun addTo(top: MutableMap<String, JsonElement>) {
            top["$name.channels"] = JsonArray(view.channels.map(::channelRow))
            val on = onStarters.mapTo(HashSet()) { it.key }
            val off =
                StarterCatalog.recipes
                    .map { it.key }
                    .filter { it !in on }
                    .sorted()
            top["$name.off"] = JsonArray(off.map(::JsonPrimitive))
            top["$name.noHome"] = noHome()
            top["$name.overlap"] = overlap()
        }

        private fun channelRow(c: GateChannel): JsonElement =
            JsonObject(
                linkedMapOf(
                    "key" to JsonPrimitive(c.key),
                    "number" to JsonPrimitive(c.number),
                    "ceiling" to JsonPrimitive(c.ceiling),
                    "policy" to JsonPrimitive(c.policy.toString()),
                    "titles" to JsonPrimitive(titlesOf(c)),
                    "homes" to JsonPrimitive(c.homed.size),
                    "hours" to JsonPrimitive(hours(c)),
                ),
            )

        // The titles that air nowhere in the view, by why, as the audit's causes say (the planner's lanes).
        private fun noHome(): JsonElement {
            val aired = view.channels.flatMapTo(HashSet()) { c -> c.items.map { titles.getValue(it.id) } }
            val causes = TitleCauses(view.snapshot, AuditLanes(planned))
            val counts = HashMap<Cause, Int>()
            for (title in titles.values.toSet() - aired) counts.merge(causes.reachOf(title).cause, 1, Int::plus)
            val ordered = Cause.entries.filter { it in counts }
            return JsonObject(ordered.associateTo(LinkedHashMap()) { it.label to JsonPrimitive(counts.getValue(it)) })
        }

        // Every pair of on starters that air a common item, with the runtime they share.
        private class Shared(
            val pair: String,
            val ms: Long,
            val smallerHours: Double,
        )

        private val shared: List<Shared> by lazy {
            val out = ArrayList<Shared>()
            for (i in onStarters.indices) {
                val a = onStarters[i].items.mapTo(HashSet()) { it.id }
                for (j in i + 1 until onStarters.size) {
                    val ms = onStarters[j].items.filter { it.id in a }.sumOf { runtime.getValue(it.id) }
                    if (ms == 0L) continue
                    val smaller = minOf(hours(onStarters[i]), hours(onStarters[j]))
                    out += Shared("${onStarters[i].key} + ${onStarters[j].key}", ms, smaller)
                }
            }
            out
        }

        // How much the on starters share: pairs that air a common item, the hours shared in all, the widest pair.
        private fun overlap(): JsonElement {
            val widest = shared.maxByOrNull { it.ms }
            return JsonObject(
                linkedMapOf(
                    "pairs" to JsonPrimitive(shared.size),
                    "sharedHours" to JsonPrimitive(tenths(shared.sumOf { it.ms } / MS_PER_HOUR)),
                    "widest" to JsonPrimitive(widest?.pair.orEmpty()),
                    "widestHours" to JsonPrimitive(tenths((widest?.ms ?: 0L) / MS_PER_HOUR)),
                ),
            )
        }

        /** The soft warnings: report-only, they never fail the gate. */
        fun warnings(): List<String> {
            val out = ArrayList<String>()
            for (c in onStarters) {
                val h = hours(c)
                if (h > MAX_CHANNEL_HOURS) out += "[${case.key} $name] ${c.key} holds $h h, over $MAX_CHANNEL_HOURS"
                if (h < MIN_CHANNEL_HOURS) out += "[${case.key} $name] ${c.key} holds $h h, under $MIN_CHANNEL_HOURS"
                if (c.homed.isEmpty()) out += "[${case.key} $name] ${c.key} has 0 homes"
            }
            val cap = case.leftoverCapHours
            val leftoverHours = view.channels.filter { it.leftovers }.sumOf { hours(it) }
            if (cap != null && leftoverHours > cap) {
                out += "[${case.key} $name] the leftovers hold ${tenths(leftoverHours)} h, over the pinned $cap h"
            }
            return out + heavyOverlaps()
        }

        // Two on starters sharing 12 hours or more, over three quarters of the smaller one: the overlap drifting up.
        private fun heavyOverlaps(): List<String> =
            shared.filter { heavy(it) }.map {
                "[${case.key} $name] ${it.pair} share ${tenths(it.ms / MS_PER_HOUR)} h, over 3/4 of the smaller"
            }

        private fun heavy(s: Shared): Boolean {
            val hours = s.ms / MS_PER_HOUR
            return hours >= HEAVY_OVERLAP_HOURS && hours > s.smallerHours * HEAVY_OVERLAP
        }
    }
}
