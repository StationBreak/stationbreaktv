// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.RatingMap
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.curation.LaneBuilder
import tv.stationbreak.core.engine.lineup.curation.Title
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Catalog 17 (#1154): a title that comes into Cartoons After Dark by the Animation genre alone needs a decisive rating
 * of PG-13 / TV-14 or higher. The audit counts, per profile library, the titles on the channel before and after, and
 * the ones that left, in the manner of [FamilyExclusionTradeOffTest].
 */
class CartoonsAdultSignalTest {
    private class Counts(
        val before: List<Title>,
        val after: List<Title>,
    ) {
        val left: List<Title> get() = before.filter { b -> after.none { it.id == b.id } }
    }

    private fun countsOf(key: String): Counts {
        val profile = ProfileLibraries.build(key)
        val snapshot = ExtrasFilter.filter(profile.input.snapshot).snapshot
        val names = HashMap<CanonicalId, String>()
        for ((id, c) in snapshot.collections) c.name?.let { names[id] = it }
        val lanes = AuditLanes(PlannedLineup.of(snapshot, CollectionNames(names), profile.input.viewer))
        val lane = lanes.lanes.firstOrNull { it.channel.recipe.key == KEY } ?: return Counts(emptyList(), emptyList())
        val ch = lane.channel
        val theme = checkNotNull(ch.recipe.theme)
        assertTrue(theme.adultSignalOnGenreEntry, "the catalog asks for the adult signal")
        // The same lane as catalog 16 built it: the recipe without the flag.
        val off = ch.recipe.copy(theme = theme.copy(adultSignalOnGenreEntry = false))
        val oldChannel = CurationChannel(ch.id, ch.number, off, ch.kid, ch.rules, ch.settings, ch.pool)
        val old = LaneBuilder(snapshot, listOf(oldChannel))
        return Counts(
            before =
                old.lanes
                    .single()
                    .cand
                    .map { old.titles[it].title },
            after = lane.cand.indices.map { lanes.titleOf(lane, it) },
        )
    }

    private val counts = ProfileLibraries.keys.associateWith(::countsOf)

    // The titles that left Cartoons After Dark, per profile library: on it at catalog 16, off it now. A title that
    // leaves falls to another lane or to Odds & Ends (it keeps airing, as Family-tier animation did elsewhere).
    @Test
    fun theTitlesThatLeaveCartoonsAfterDarkPerProfile() {
        val left = counts.mapValues { it.value.left.size }
        val gone = mapOf("sitcom-household" to 1, "anime-viewer" to 6, "seat-household" to 8)
        val expected = ProfileLibraries.keys.associateWith { gone[it] ?: 0 }
        assertEquals(expected, left)
        val remaining = counts.mapValues { it.value.after.size }
        val kept = mapOf("sitcom-household" to 1, "broad-1900" to 29, "thin-200" to 4, "seat-household" to 8)
        assertEquals(ProfileLibraries.keys.associateWith { kept[it] ?: 0 }, remaining)
    }

    @Test
    fun noKidRatedOrFamilyTierTitleIsOnTheChannelAnyMore() {
        for ((key, c) in counts) {
            for (title in c.after) {
                val level = RatingMap.levelOf(title.rating)
                assertTrue(level != null && level >= PG_13_LEVEL, "$key: ${title.rating} is on the channel")
                assertTrue(!title.madeForKids, key)
            }
            for (title in c.left) {
                assertTrue((RatingMap.levelOf(title.rating) ?: 0) < PG_13_LEVEL, "$key: ${title.rating} left")
            }
        }
    }

    @Test
    fun theAdultCartoonsStayAndTheChannelStillClearsItsBar() {
        for (key in listOf("broad-1900", "thin-200")) {
            val report = LineupAudit.run(ProfileLibraries.build(key).input)
            val sitcom = report.titles.single { it.name == CaseTitles.ADULT_ANIMATED_SITCOM }
            assertEquals(KEY, sitcom.home?.channel, key)
        }
        for (key in listOf("broad-1900", "thin-200", "seat-household")) {
            val row = LineupAudit.run(ProfileLibraries.build(key).input).starters.single { it.key == KEY }
            // On, or off only for want of a seat (BelowCap): it qualifies.
            assertTrue(row.shelfReason != "TooThin" && row.shelfReason != "Empty", "$key: ${row.shelfReason}")
            assertNotEquals(0, counts.getValue(key).after.size, key)
        }
    }

    private companion object {
        const val KEY = "cartoons-after-dark"
        const val PG_13_LEVEL = 3
    }
}
