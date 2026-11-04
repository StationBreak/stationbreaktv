// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals

class InOrderTest {
    private fun film(
        digit: Char,
        date: String? = null,
    ) = ScheduleItem(
        id = idOf(digit),
        kind = ScheduleItemKind.Movie,
        runtimeMs = 30 * MS_MINUTE,
        createdAtMs = 0L,
        deletedAtMs = null,
        premiereDate = date,
        productionYear = null,
        seriesId = null,
        seasonNumber = null,
        episodeNumber = null,
    )

    private fun episode(
        id: CanonicalId,
        series: Char,
        season: Int,
        number: Int,
        date: String?,
    ) = ScheduleItem(
        id = id,
        kind = ScheduleItemKind.Episode,
        runtimeMs = 30 * MS_MINUTE,
        createdAtMs = 0L,
        deletedAtMs = null,
        premiereDate = date,
        productionYear = null,
        seriesId = idOf(series),
        seasonNumber = season,
        episodeNumber = number,
    )

    private fun ep(
        series: Char,
        number: Int,
        date: String?,
        season: Int = 1,
    ) = episode(CanonicalId.parse(series.toString().repeat(30) + "0" + number), series, season, number, date)

    private fun ids(items: List<ScheduleItem>) = items.map { it.id }

    @Test
    fun groupsPlayStartToFinishOldestFirst() {
        val a1 = ep('a', 1, "1980-01-01")
        val a2 = ep('a', 2, "1980-02-01")
        val movie = film('5', "1985-01-01")
        val b1 = ep('b', 1, "1990-01-01")
        val b2 = ep('b', 2, "1990-02-01")

        assertEquals(listOf(a1, a2, movie, b1, b2), Orderings.inOrder(listOf(b2, movie, a2, b1, a1)))
    }

    @Test
    fun anUndatedGroupPlaysLast() {
        val undatedFilm = film('1')
        val dated = film('9', "2001-01-01")
        val undatedEpisode = ep('c', 1, null)

        val order = Orderings.inOrder(listOf(undatedEpisode, undatedFilm, dated))

        assertEquals(listOf(dated, undatedFilm, undatedEpisode), order)
    }

    @Test
    fun aGroupIsPlacedByItsFirstDatedItemNotItsEarliestDate() {
        // The special premiered in 1970 but plays after the regular seasons, so the group sits at 1990, not at 1970.
        val special = ep('a', 9, "1970-01-01", season = 0)
        val first = ep('a', 1, "1990-01-01")
        val undatedSecond = ep('a', 2, null)
        val between = film('5', "1980-01-01")

        val order = Orderings.inOrder(listOf(special, undatedSecond, first, between))

        assertEquals(listOf(between, first, undatedSecond, special), order)
    }

    @Test
    fun aGroupWhoseFirstItemIsUndatedIsPlacedByItsFirstDatedOne() {
        val undatedFirst = ep('a', 1, null)
        val dated = ep('a', 2, "1975-01-01")
        val between = film('5', "1980-01-01")

        assertEquals(listOf(undatedFirst, dated, between), Orderings.inOrder(listOf(between, dated, undatedFirst)))
    }

    @Test
    fun twoGroupsOfTheSameYearTieOnGroupId() {
        val smaller = film('1', "1999-05-05")
        val larger = film('2', "1999-05-05")

        assertEquals(listOf(smaller, larger), Orderings.inOrder(listOf(larger, smaller)))
    }

    @Test
    fun everyFilmIsItsOwnGroupEvenInACollection() {
        val films =
            listOf(
                film('1', "1990-01-01"),
                film('2', "1985-01-01"),
                film('3', "1995-01-01"),
                film('4', "1987-01-01"),
            )
        val collected = GroupRules(FilmCollections.of(films.take(3).associate { it.id to idOf('c') }), noWeights())
        val settings = testSettings(OrderingMode.InOrder, blockLength = 2)

        val withCollection = Runs.cycle(settings, films, 0uL, collected)

        assertEquals(Runs.cycle(settings, films, 0uL, noGroups()), withCollection)
        assertEquals(listOf('2', '4', '1', '3').map { idOf(it) }, withCollection.flatMap { ids(it.items) })
    }

    @Test
    fun inOrderRunsAreBlockLengthChunks() {
        val pool = (1..7).map { ep('a', it, "1980-01-0$it") }

        for (block in listOf(1, 3)) {
            val runs = Runs.cycle(testSettings(OrderingMode.InOrder, blockLength = block), pool, 0uL, noGroups())

            assertEquals(pool.chunked(block), runs.map { it.items })
        }
    }

    @Test
    fun inOrderIsAPureFunctionOfTheSet() {
        val pool =
            listOf(
                ep('a', 1, "1980-01-01"),
                ep('a', 2, null),
                film('5', "1985-01-01"),
                film('6'),
                ep('b', 1, "1985-01-01"),
            )
        val expected = Orderings.inOrder(pool)

        assertEquals(expected, Orderings.inOrder(pool.reversed()))
        assertEquals(expected, Orderings.inOrder(Orderings.shuffle(pool, 42uL)))
    }

    @Test
    fun aGroupJoiningNeverReordersTheOthers() {
        val pool = listOf(ep('a', 1, "1980-01-01"), film('5', "1985-01-01"), ep('b', 1, "1990-01-01"), film('6'))
        val before = Orderings.inOrder(pool)

        for (year in listOf("1970", "1982", "1985", "1995")) {
            val joiner = film('f', "$year-06-01")
            val after = Orderings.inOrder(pool + joiner).filter { it != joiner }

            assertEquals(before, after, "joiner of $year")
        }
    }

    @Test
    fun capsAreIgnored() {
        val pool = (1..6).map { ep('a', it, "1980-01-0$it") } + film('5', "1985-01-01")
        val plain = Runs.cycle(testSettings(OrderingMode.InOrder, blockLength = 2), pool, 0uL, noGroups())
        val capped =
            Runs.cycle(testSettings(OrderingMode.InOrder, blockLength = 2, caps = Caps(1, 1)), pool, 0uL, noGroups())

        assertEquals(plain, capped)
    }
}
