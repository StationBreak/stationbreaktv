// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.OrderingCaps
import tv.stationbreak.core.rules.Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The days' key is complete (spec 21): any two lineups that every edit can reach and whose [LineupDays.inputsOf] are
 * equal give the same day for every channel: the same pool, empty reason, settings and ceiling. The key is a list kept
 * by hand; this is what catches a lineup read the build gained and the key did not.
 */
class DayInputsDifferentialTest {
    private val days = LineupDays(TestCatalog)

    // Beside the filling library, items the holds and ceilings tell apart: animation not made for kids, a kids'
    // title on a children's rating, and grown-up ratings the kids' ceiling blocks.
    private val items =
        fillingLibrary() +
            listOf(
                movie(800, "Animation", rating = "PG"),
                movie(801, "Comedy", rating = "G"),
                movie(802, "Animation", rating = "G"),
                movie(803, "Animation", "Kids", rating = "TV-Y"),
                movie(804, "Animation", "Kids", rating = "PG-13"),
            )

    private fun custom(
        id: String,
        number: Int?,
        state: ChannelState,
        vararg sources: Source,
    ) = channel(id, number, state, ChannelSource.Custom(rules(id, *sources)))

    // Rules of version 2: a rotation with caps and a collection source, and a channel with filters.
    private fun v2(
        id: String,
        ordering: Ordering?,
        filters: Filters?,
        vararg sources: Source,
    ) = ChannelRules(
        2,
        id,
        Seed(id.hashCode().toULong()),
        sources.toList(),
        null,
        null,
        ordering,
        timing(),
        null,
        filters,
    )

    private val boxSet = id(900)
    private val years = Filters(null, 1990, 2010, null, null, null)

    private val v2Fixture =
        lineupOf(
            channel(
                "custom-1",
                600,
                ChannelState.On,
                ChannelSource.Custom(v2("one", Ordering(OrderingMode.Rotation), null, Source.Collection(boxSet))),
            ),
            channel(
                "custom-2",
                610,
                ChannelState.UserHidden,
                ChannelSource.Custom(v2("two", null, years, Source.Genre("Comedy"))),
            ),
            leftovers("odds-and-ends", 199, null),
        )

    private val fixtures: List<Lineup> =
        listOf(
            lineupOf(
                starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
                starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
                starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
                starter("frights", 310, ChannelState.UserHidden, rules("Frights", Source.Genre("Horror"))),
                starter("westerns", 220, ChannelState.Shelf, rules("Westerns", Source.Genre("Western"))),
                starter("after-hours", 590, ChannelState.Shelf, rules("After Hours", Source.Tag("late"))),
                leftovers("kids-odds-and-ends", 99, "PG"),
                leftovers("odds-and-ends", 199, null),
                // A retired leftovers key: a second open leftovers channel, so the claim order turns on the numbers.
                leftovers("retired-odds", 299, null),
            ),
            lineupOf(
                starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
                custom("custom-1", 50, ChannelState.On, Source.Genre("Animation")),
                custom("custom-2", 600, ChannelState.On, Source.Genre("Comedy"), Source.Genre("Animation")),
                custom("custom-3", 610, ChannelState.UserHidden, Source.Genre("Science Fiction")),
                custom("custom-4", null, ChannelState.Shelf, Source.Genre("Crime")),
                leftovers("kids-odds-and-ends", 99, "PG"),
                leftovers("odds-and-ends", 199, null, ChannelState.UserHidden),
            ),
            v2Fixture,
        )

    private val numbers = listOf(5, 50, 98, 99, 100, 120, 150, 199, 210, 250, 600, 999)

    /** Every edit of every kind, for every channel, that the lineup lets through. */
    private fun editsOf(lineup: Lineup): List<LineupEdit> =
        lineup.channels.flatMap { channel ->
            val id = channel.id
            val holders = lineup.channels.mapNotNull { it.number?.let { n -> n to it.id } }
            listOf(
                LineupEdit.SwitchOn(id, confirmedOptIn = true),
                LineupEdit.SwitchOff(id),
                LineupEdit.ClearNumber(id),
            ) + numbers.map { LineupEdit.SetNumber(id, it) } +
                holders.filter { it.second != id }.flatMap { (n, holder) ->
                    listOf(
                        LineupEdit.SetNumber(id, n, swapWith = holder),
                        LineupEdit.ClearNumber(id, swapWith = holder),
                    )
                }
        }

    private fun applied(
        lineup: Lineup,
        edit: LineupEdit,
    ): Lineup? {
        val outcome = LineupEdits.apply(lineup, edit, NOON.toEpochMilliseconds(), TestCatalog)
        return (outcome as? EditOutcome.Applied)?.lineup
    }

    private fun reachable(): List<Lineup> {
        val out = ArrayList<Lineup>()
        for (fixture in fixtures) {
            out += fixture
            // Each edit once.
            for (edit in editsOf(fixture)) {
                val once = applied(fixture, edit) ?: continue
                out += once
            }
            // A few generated sequences.
            val random = Random(fixture.channels.size)
            repeat(SEQUENCES) {
                var lineup = fixture
                repeat(STEPS) {
                    val edits = editsOf(lineup)
                    lineup = applied(lineup, edits[random.nextInt(edits.size)]) ?: lineup
                    out += lineup
                }
            }
        }
        return out
    }

    private fun describe(lineup: Lineup): Map<String, String> {
        val at = NOON.toEpochMilliseconds()
        val scheduled = days.scheduled(lineup, at)
        val snapshots =
            scheduled.map { it.cutoffMs }.distinct().associateWith {
                RuleSnapshot(it, setOf(LIBRARY_ID), items, emptyMap(), emptyMap(), collectionsSynced = true)
            }
        return days.days(lineup, scheduled, snapshots).associate { day ->
            day.channel.id.value to
                "pool=${day.pool.map { it.id.value }} hidden=${day.hidden} settings=${day.settings} " +
                "n=${day.n} ceiling=${day.ceilingRank}"
        }
    }

    @Test
    fun lineupsWithTheSameInputsHaveTheSameDays() {
        val groups = reachable().groupBy { days.inputsOf(it) }
        var compared = 0
        for ((_, lineups) in groups) {
            val distinct = lineups.distinct()
            if (distinct.size < 2) continue
            val first = describe(distinct.first())
            for (other in distinct.drop(1)) {
                assertEquals(first, describe(other), "same inputs, other days:\n${distinct.first()}\n$other")
                compared++
            }
        }
        assertTrue(compared >= MIN_COMPARED, "only $compared pairs shared inputs: the test would prove little")
        assertTrue(groups.size >= MIN_GROUPS, "only ${groups.size} distinct inputs: the edits change too little")
    }

    @Test
    fun aCapsEditOrAFilterEditChangesTheDigest() {
        fun digestOf(edit: (ChannelRules) -> ChannelRules): String {
            val custom = v2Fixture.channels.first { it.id.value == "custom-1" }
            val rules = (custom.source as ChannelSource.Custom).rules
            val edited = custom.copy(source = ChannelSource.Custom(edit(rules)))
            return days.inputsOf(lineupOf(edited))
        }

        val digests =
            setOf(
                digestOf { it },
                digestOf { it.copy(ordering = Ordering(OrderingMode.Rotation, OrderingCaps(series = 2, film = 2))) },
                digestOf { it.copy(filters = Filters(null, 1990, 2010, null, null, null)) },
            )

        assertEquals(3, digests.size)
    }

    private companion object {
        const val SEQUENCES = 8
        const val STEPS = 6
        const val MIN_COMPARED = 20
        const val MIN_GROUPS = 10
    }
}
