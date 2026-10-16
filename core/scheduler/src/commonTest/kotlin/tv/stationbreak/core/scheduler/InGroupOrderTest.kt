// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order of episodes inside a `randomInOrder` group (owner decision Q3): the numbering decides, not the air date.
 * The key is season class (regular season 0, specials 1, missing season 2), season, episode (missing last), undated
 * flag, date, ID bytes.
 */
class InGroupOrderTest {
    private val series = idOf('7')

    private fun episode(
        digit: Char,
        season: Int?,
        episode: Int?,
        date: String?,
    ): ScheduleItem =
        testItem(idOf(digit), kind = ScheduleItemKind.Episode)
            .copy(seriesId = series, seasonNumber = season, episodeNumber = episode, premiereDate = date)

    private fun inGroup(vararg items: ScheduleItem): List<Char> {
        val groups = Orderings.groups(items.toList(), Seed(1uL))
        assertEquals(1, groups.size)
        return groups.single().items.map { it.id.value.first() }
    }

    @Test
    fun theNumberingDecidesWhenDatesDisagree() {
        // E2 aired before E1: it still plays after it.
        val e1 = episode('a', 1, 1, "2020-03-01")
        val e2 = episode('b', 1, 2, "2020-01-01")
        assertEquals(listOf('a', 'b'), inGroup(e2, e1))
    }

    @Test
    fun anUndatedFirstEpisodePlaysFirst() {
        val e1 = episode('a', 1, 1, null)
        val e2 = episode('b', 1, 2, "2020-01-01")
        assertEquals(listOf('a', 'b'), inGroup(e2, e1))
    }

    @Test
    fun specialsPlayAfterTheLastSeason() {
        val special = episode('a', 0, 1, "2019-01-01")
        val s1 = episode('b', 1, 1, "2020-01-01")
        val s2 = episode('c', 2, 1, "2021-01-01")
        assertEquals(listOf('b', 'c', 'a'), inGroup(special, s2, s1))
    }

    @Test
    fun aMissingSeasonPlaysAfterTheSpecials() {
        val special = episode('a', 0, 1, "2019-01-01")
        val s1 = episode('b', 1, 1, "2020-01-01")
        val loose = episode('c', null, 1, "2018-01-01")
        assertEquals(listOf('b', 'a', 'c'), inGroup(loose, special, s1))
    }

    @Test
    fun aMissingEpisodeIsLastInItsSeasonAndTwoMissingOnesTieOnDateThenId() {
        val e1 = episode('a', 1, 1, "2020-05-01")
        val none1 = episode('b', 1, null, "2020-02-01")
        val none2 = episode('c', 1, null, null)
        val none3 = episode('d', 1, null, "2020-01-01")
        // Ties on season and episode: dated before undated, then date, then ID.
        assertEquals(listOf('a', 'd', 'b', 'c'), inGroup(none3, none2, none1, e1))
    }

    @Test
    fun theChronologicalKeyIsStillDateFirst() {
        val e1 = episode('a', 1, 1, "2020-03-01")
        val e2 = episode('b', 1, 2, "2020-01-01")
        assertEquals(listOf('b', 'a'), Orderings.chronological(listOf(e1, e2)).map { it.id.value.first() })
        assertEquals(1, Orderings.chronologicalKey.compare(e2, e1))
        assertEquals(-1, Orderings.inGroupKey.compare(e1, e2))
    }

    @Test
    fun aMovieIsAGroupOfOne() {
        val movie = testItem(CanonicalId.parse("9".repeat(32)))
        assertEquals(listOf('9'), Orderings.groups(listOf(movie), Seed(1uL)).single().items.map { it.id.value.first() })
    }
}
