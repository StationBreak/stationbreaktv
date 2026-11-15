// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.curation.Lane
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.ChannelRules

/** How far a title got on the furthest recipe, and which recipes got it that far. */
internal class Reach(
    val stage: Int,
    val closest: List<String>,
) {
    /** The cause a title with this reach has when no switched-on channel homes it. */
    val cause: Cause get() = CAUSES[stage]

    companion object {
        // The stages a recipe's rules put an item through, in order: matched a source, survived the exclusions,
        // survived the hold, survived the filters and ceiling, can air, is admitted by the anime rule (a candidate).
        private val CAUSES =
            listOf(
                Cause.NoSourceMatch,
                Cause.ExcludedByRecipe,
                Cause.HeldByPolicy,
                Cause.FilteredOrRated,
                Cause.NotAirable,
                Cause.AnimeRule,
                Cause.OnlyOnShelvedChannels,
            )
        const val CANDIDATE = 6
        val NONE = Reach(0, emptyList())
    }
}

/**
 * Where each title stops on the recipes' way to a pool, for a title with no home. For each starter, its rules are
 * evaluated again with parts taken away, one at a time, in the evaluator's own order (sources, exclusions, the hold,
 * the filters and ceiling), and the airable pool and the anime rule come after: the furthest stage any of a title's
 * items reaches on any recipe is the title's. The evaluations are the real [PoolEvaluator]'s: nothing is rewritten.
 */
internal class TitleCauses(
    private val snapshot: RuleSnapshot,
    lanes: AuditLanes,
) {
    private val best = HashMap<CanonicalId, Int>()
    private val closest = HashMap<CanonicalId, MutableList<String>>()

    init {
        for (lane in lanes.lanes) addLane(lane, lanes)
    }

    /** The reach of [title] (the group ID of a series, or a film's own ID). */
    fun reachOf(title: CanonicalId): Reach {
        val stage = best[title] ?: return Reach.NONE
        return Reach(stage, closest[title].orEmpty().take(MAX_CLOSEST))
    }

    private fun addLane(
        lane: Lane,
        lanes: AuditLanes,
    ) {
        val rules = lane.channel.rules
        val policy = lanes.policyOf(lane)
        val sourced = ids(rules.copy(exclusions = null, filters = null, maxRating = null), PoolPolicy.Open)
        val unexcluded = ids(rules.copy(filters = null, maxRating = null), PoolPolicy.Open)
        val unheld = ids(rules.copy(filters = null, maxRating = null), policy)
        val full = lane.channel.pool.mapTo(HashSet()) { it.id }
        val airable = lane.pool.mapTo(HashSet()) { it.id }
        val admitted = HashSet<CanonicalId>()
        for (i in lane.pool.indices) if (lane.itemPos[i] >= 0) admitted += lane.pool[i].id
        val key = lane.channel.recipe.key
        val here = HashMap<CanonicalId, Int>()
        for (entry in snapshot.items) {
            val id = entry.item.id
            val stage =
                when {
                    id in admitted -> Reach.CANDIDATE
                    id in airable -> PASSED_AIRABLE
                    id in full -> PASSED_FILTERS
                    id in unheld -> PASSED_HOLD
                    id in unexcluded -> PASSED_EXCLUSIONS
                    id in sourced -> PASSED_SOURCES
                    else -> 0
                }
            here.merge(entry.item.groupId, stage, ::maxOf)
        }
        for ((title, stage) in here) note(title, stage, key)
    }

    private fun ids(
        rules: ChannelRules,
        policy: PoolPolicy,
    ): Set<CanonicalId> = PoolEvaluator.evaluate(rules, snapshot, policy).items.mapTo(HashSet()) { it.id }

    private fun note(
        title: CanonicalId,
        stage: Int,
        key: String,
    ) {
        val before = best[title] ?: -1
        when {
            stage > before -> {
                best[title] = stage
                closest[title] = arrayListOf(key)
            }

            stage == before -> {
                closest.getValue(title).add(key)
            }
        }
    }

    private companion object {
        // A stage is the number of steps an item passed: 0 none, then a source, the exclusions, the hold, the filters
        // and ceiling, and the airable pool (6 is the anime rule too: a candidate).
        const val PASSED_SOURCES = 1
        const val PASSED_EXCLUSIONS = 2
        const val PASSED_HOLD = 3
        const val PASSED_FILTERS = 4
        const val PASSED_AIRABLE = 5
        const val MAX_CLOSEST = 3
    }
}
