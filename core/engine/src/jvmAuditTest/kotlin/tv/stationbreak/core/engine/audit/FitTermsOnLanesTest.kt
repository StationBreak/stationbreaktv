// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-term scores of the audit are the curation's own: for every lane and every title of every profile library,
 * the terms (specificity, genre, tag, studio, decade, anchor, kids, format, tone) add up to the score the lane holds,
 * which `fitPointsQ16` and the lane's specificity made.
 */
class FitTermsOnLanesTest {
    private fun lanesOf(profile: Profile): AuditLanes {
        val snapshot = ExtrasFilter.filter(profile.input.snapshot).snapshot
        val names = HashMap<CanonicalId, String>()
        for ((id, c) in snapshot.collections) c.name?.let { names[id] = it }
        return AuditLanes(PlannedLineup.of(snapshot, CollectionNames(names), profile.input.viewer))
    }

    @Test
    fun theTermsAddUpToTheLaneScoreForEveryLaneAndTitleOfEveryProfile() {
        var checked = 0
        for (profile in ProfileLibraries.all()) {
            val lanes = lanesOf(profile)
            for (lane in lanes.lanes) {
                for (p in lane.cand.indices) {
                    val where = "${profile.key} ${lane.channel.recipe.key} #$p"
                    assertEquals(lane.score[p], lanes.termsOf(lane, p).total, where)
                    checked++
                }
            }
        }
        assertTrue(checked > MIN_CHECKED, "The profiles score many titles ($checked)")
    }

    private companion object {
        const val MIN_CHECKED = 5_000
    }
}
