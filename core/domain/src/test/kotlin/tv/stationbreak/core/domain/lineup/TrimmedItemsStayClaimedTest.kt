// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.TitleKeeps
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
    private val lineup =
        lineupOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            leftovers("odds-and-ends", 199, null),
        )
    private val keeps = TitleKeeps.of(mapOf(series to 2_400_000L))
    private val windowed = LineupDays(TestCatalog) { _, _ -> }
    private val trimming = LineupDays(TestCatalog, { s -> if (s.channel.number == 110) keeps else TitleKeeps.NONE })

    private fun snapshots(scheduled: List<Scheduled>) =
        scheduled.map { it.cutoffMs }.distinct().associateWith {
            RuleSnapshot(it, setOf(LIBRARY_ID), episodes + others, emptyMap(), emptyMap(), collectionsSynced = true)
        }

    private fun ids(items: List<ScheduleItem>): Set<CanonicalId> = items.mapTo(HashSet()) { it.id }

    @Test
    fun theLeftoversNeverAirATrimmedEpisodeAndNothingAirsNowhere() {
        val scheduled = trimming.scheduled(lineup, NOON.toEpochMilliseconds(), null)
        val snaps = snapshots(scheduled)

        val days = trimming.days(lineup, scheduled, snaps)

        val laughs = days.first { it.channel.number == 110 }
        val rest = days.first { it.channel.number == 199 }
        assertEquals(2, laughs.pool.size, "the window keeps two of six")
        assertTrue(ids(rest.pool).none { it in ids(episodes.map { e -> e.item }) }, "no trimmed episode is left over")
        assertEquals(0, trimming.airingNowhere(lineup, scheduled, snaps))
        // The same build with no keeps gives the leftovers the same day.
        val untrimmed = windowed.days(lineup, scheduled, snaps)
        assertEquals(untrimmed.first { it.channel.number == 199 }.pool, rest.pool)
    }

    @Test
    fun aRestoredDayKeepsThePoolsItWasMadeWith() {
        val scheduled = trimming.scheduled(lineup, NOON.toEpochMilliseconds(), null)
        val days = trimming.days(lineup, scheduled, snapshots(scheduled))

        // A day from disk claims nothing: it is its stored pool, made with no keeps, and the stored leftovers pool
        // already has the trimmed episodes out of it.
        val restored =
            days.map { day ->
                trimming.restore(scheduled.first { it.channel.id == day.channel.id }, day.pool, day.films, day.hidden)
            }

        assertEquals(days.map { it.pool }, restored.map { it.pool })
        val rest = restored.first { it.channel.number == 199 }
        assertTrue(ids(rest.pool).none { it in ids(episodes.map { e -> e.item }) })
    }
}
