// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Trimmed items stay claimed (plan Resolution 24): the trim window never hands an episode to the leftovers. */
class TrimmedItemsStayClaimedTest {
    private val series = id(900)
    private val episodes =
        (1..6).map {
            RuleItem(
                ScheduleItem(
                    id(910 + it),
                    ScheduleItemKind.Episode,
                    1_200_000L,
                    ADDED_MS,
                    null,
                    null,
                    null,
                    series,
                    1,
                    it,
                ),
                LIBRARY_ID,
                null,
                "TV-14",
                listOf("Comedy"),
                emptyList(),
            )
        }
    private val others = (0 until 4).map { movie(100 + it, "Crime", rating = "R") }

    // The same channel, plain and programmed: the curation caps the series at its share (an hour: the floor) and the
    // day keeps a window of it.
    private fun laughsWith(rules: tv.stationbreak.core.rules.ChannelRules) =
        lineupOf(starter("laughs", 110, ChannelState.On, rules), leftovers("odds-and-ends", 199, null))

    private val plain = laughsWith(rules("Laughs", Source.Genre("Comedy")))
    private val lineup = laughsWith(programmed("Laughs", Source.Genre("Comedy")))
    private val days = LineupDays(TestCatalog)

    private fun snapshots(scheduled: List<Scheduled>) =
        scheduled.map { it.cutoffMs }.distinct().associateWith {
            RuleSnapshot(it, setOf(LIBRARY_ID), episodes + others, emptyMap(), emptyMap(), collectionsSynced = true)
        }

    private fun ids(items: List<ScheduleItem>): Set<CanonicalId> = items.mapTo(HashSet()) { it.id }

    @Test
    fun theLeftoversNeverAirATrimmedEpisodeAndNothingAirsNowhere() {
        val scheduled = days.scheduled(lineup, NOON.toEpochMilliseconds(), null)
        val snaps = snapshots(scheduled)

        val built = days.days(lineup, scheduled, snaps)

        val laughs = built.first { it.channel.number == 110 }
        val rest = built.first { it.channel.number == 199 }
        assertEquals(3, laughs.pool.size, "the window keeps an hour, three of six")
        assertTrue(ids(rest.pool).none { it in ids(episodes.map { e -> e.item }) }, "no trimmed episode is left over")
        assertEquals(0, days.airingNowhere(lineup, scheduled, snaps))
        // The plain channel, untrimmed, gives the leftovers the same day.
        val plainScheduled = days.scheduled(plain, NOON.toEpochMilliseconds(), null)
        val untrimmed = days.days(plain, plainScheduled, snapshots(plainScheduled))
        assertEquals(untrimmed.first { it.channel.number == 199 }.pool, rest.pool)
        assertEquals(6, untrimmed.first { it.channel.number == 110 }.pool.size)
    }

    @Test
    fun aRestoredDayKeepsThePoolsItWasMadeWith() {
        val scheduled = days.scheduled(lineup, NOON.toEpochMilliseconds(), null)
        val built = days.days(lineup, scheduled, snapshots(scheduled))

        // A day from disk claims nothing: it is its stored pool, made with no keeps, and the stored leftovers pool
        // already has the trimmed episodes out of it.
        val restored =
            built.map { day ->
                val s = scheduled.first { it.channel.id == day.channel.id }
                days.restore(s, day.pool, day.films, day.hidden, day.weights, day.homed)
            }

        assertEquals(built.map { it.pool }, restored.map { it.pool })
        assertEquals(built.map { it.day.blocks }, restored.map { it.day.blocks })
        val rest = restored.first { it.channel.number == 199 }
        assertTrue(ids(rest.pool).none { it in ids(episodes.map { e -> e.item }) })
    }
}
