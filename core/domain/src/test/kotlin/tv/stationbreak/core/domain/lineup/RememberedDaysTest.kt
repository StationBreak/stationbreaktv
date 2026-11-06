// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #589: what is remembered for a later lineup to take over from is bounded to what can still match. */
class RememberedDaysTest {
    private val hour = 3_600_000L
    private val noon = NOON.toEpochMilliseconds()
    private val days = LineupDays(TestCatalog)
    private val lineup =
        lineupOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            leftovers("odds-and-ends", 199, null),
        )

    private class Made(
        val scheduled: List<Scheduled>,
        val inputs: Map<ChannelId, LineupDays.DayInput>,
        val days: List<ChannelDay>,
    )

    private fun madeAt(atMs: Long): Made {
        val scheduled = days.scheduled(lineup, atMs, null)
        val snapshots =
            scheduled.map { it.cutoffMs }.distinct().associateWith {
                RuleSnapshot(it, setOf(LIBRARY_ID), fillingLibrary(), emptyMap(), emptyMap(), collectionsSynced = true)
            }
        return Made(scheduled, days.dayInputsOf(lineup, scheduled, atMs), days.days(lineup, scheduled, snapshots))
    }

    private fun RememberedDays.take(
        made: Made,
        generation: Long,
    ) = reusable(ACCOUNT, LIBRARIES, generation, made.scheduled, made.inputs)

    private fun RememberedDays.file(
        made: Made,
        generation: Long,
    ) = remember(ACCOUNT, LIBRARIES, generation, made.inputs, made.days)

    @Test
    fun aDayIsTakenOverFromTheSameGenerationOnly() {
        val today = madeAt(noon)
        val remembered = RememberedDays()
        remembered.file(today, 1)
        assertEquals(today.days.map { it.channel.id }.toSet(), remembered.take(today, 1).keys)
        assertTrue(remembered.take(today, 2).isEmpty(), "another content generation takes nothing")
    }

    @Test
    fun anotherGenerationOfTheSameLibraryDropsTheDaysOfTheOldOne() {
        val today = madeAt(noon)
        val remembered = RememberedDays()
        remembered.file(today, 1)
        remembered.file(today, 2)
        assertEquals(today.days.size, remembered.size, "the days of generation 1 can never match again")
        assertTrue(remembered.take(today, 1).isEmpty())
    }

    @Test
    fun aDayThatEndedAWholeDayBeforeTheOldestOfTheBuildIsDropped() {
        val remembered = RememberedDays()
        val today = madeAt(noon)
        val tomorrow = madeAt(noon + 24 * hour)
        val later = madeAt(noon + 72 * hour)
        remembered.file(today, 1)
        remembered.file(tomorrow, 1)
        assertEquals(2 * today.days.size, remembered.size, "today stays while tomorrow is built")
        remembered.file(later, 1)
        assertEquals(later.days.size, remembered.size.coerceAtMost(later.days.size), "old days are gone")
        assertTrue(remembered.take(today, 1).isEmpty())
        assertTrue(remembered.take(tomorrow, 1).isEmpty())
        assertEquals(later.days.size, remembered.take(later, 1).size)
    }

    @Test
    fun noMoreThanThreeDaysOfEachChannelAreKept() {
        val remembered = RememberedDays()
        for (d in 0..5) remembered.file(madeAt(noon + d * 24 * hour), 1)
        assertTrue(remembered.size <= 3 * lineup.channels.size, "${remembered.size} days kept")
    }

    @Test
    fun theDaysFinishedBeforeABuildStoppedAreWhatABuildReports() {
        val scheduled = days.scheduled(lineup, noon, null)
        val snapshots =
            scheduled.map { it.cutoffMs }.distinct().associateWith {
                RuleSnapshot(it, setOf(LIBRARY_ID), fillingLibrary(), emptyMap(), emptyMap(), collectionsSynced = true)
            }
        val finished = ArrayList<ChannelDay>()
        var asked = 0
        runCatching {
            days.days(
                lineup,
                scheduled,
                snapshots,
                beforeChannel = { if (++asked == 3) error("stop") },
                finished = { finished += it },
            )
        }
        assertEquals(2, finished.size, "two days were finished before the third was asked for")
        val full = days.days(lineup, scheduled, snapshots)
        for (day in finished) assertEquals(full.dayOf(day.channel.id.value).aired(), day.aired())
    }
}
