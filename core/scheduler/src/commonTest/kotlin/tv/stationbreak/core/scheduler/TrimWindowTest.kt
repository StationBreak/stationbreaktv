// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.TitleKeeps
import kotlin.test.Test
import kotlin.test.assertEquals

class TrimWindowTest {
    private val show = seriesIdOf(1)

    private fun keeps(
        capMinutes: Long,
        title: CanonicalId = show,
    ) = TitleKeeps.of(mapOf(title to capMinutes * MS_MINUTE))

    // Episodes whose IDs run against their numbers, so the in-group order is not the ID order.
    private fun episodes(vararg minutes: Int): List<ScheduleItem> =
        minutes.mapIndexed { index, m ->
            episodeOf(1, 100 - index, m).copy(id = hexIdOf(1, 100 - index), episodeNumber = index + 1)
        }

    private fun kept(
        pool: List<ScheduleItem>,
        n: Long,
        keeps: TitleKeeps,
    ): List<Int> = TrimWindow.apply(pool, n, keeps).map { checkNotNull(it.episodeNumber) }.sorted()

    @Test
    fun theTrimWindowMovesOnEachDayAndWraps() {
        val pool = episodes(20, 20, 20, 20, 20, 20, 20, 20, 20, 20)
        val keeps = keeps(60)
        assertEquals(listOf(1, 2, 3), kept(pool, 0, keeps))
        assertEquals(listOf(4, 5, 6), kept(pool, 1, keeps))
        assertEquals(listOf(7, 8, 9), kept(pool, 2, keeps))
        assertEquals(listOf(1, 2, 10), kept(pool, 3, keeps))
        val seen = HashSet<Int>()
        for (n in 0L until 4L) seen += kept(pool, n, keeps)
        assertEquals((1..10).toSet(), seen)
    }

    @Test
    fun theCountIsTheInGroupPrefixUnderTheCap() {
        val pool = episodes(22, 22, 44, 22)
        val keeps = keeps(60)
        assertEquals(listOf(1, 2), kept(pool, 0, keeps))
        assertEquals(listOf(3, 4), kept(pool, 1, keeps))
        assertEquals(listOf(1, 2), kept(pool, 2, keeps))
        assertEquals(listOf(3, 4), kept(pool, -1, keeps))
        // The input order plays no part.
        assertEquals(kept(pool, 1, keeps), kept(pool.reversed(), 1, keeps))
    }

    @Test
    fun aFirstItemOverTheCapStillKeepsOne() {
        val pool = episodes(22, 22, 44, 22)
        val keeps = keeps(20)
        for (n in -5L..5L) assertEquals(listOf(n.mod(4L).toInt() + 1), kept(pool, n, keeps), "day $n")
    }

    @Test
    fun noKeepsKeepsEverything() {
        val pool = episodes(22, 22, 44, 22)
        val all = pool.map { it.id }.sortedBy { it.value }
        assertEquals(pool, TrimWindow.apply(pool, 3, TitleKeeps.NONE))
        // A cap that holds the whole series, and a cap for a series that is not in the pool (the output is sorted).
        assertEquals(all, TrimWindow.apply(pool, 3, keeps(110)).map { it.id })
        assertEquals(all, TrimWindow.apply(pool, 3, keeps(10, seriesIdOf(9))).map { it.id })
    }

    @Test
    fun filmsAreNeverWindowed() {
        val film = filmOf(1, 90)
        val orphan = episodeOf(2, 1, 20).copy(seriesId = null)
        val pool = (episodes(22, 22, 44, 22) + film + orphan).sortedBy { it.id.value }
        val keeps = TitleKeeps.of(mapOf(show to 20 * MS_MINUTE, film.id to 1L, orphan.id to 1L))
        val result = TrimWindow.apply(pool, 0, keeps).map { it.id }
        assertEquals(true, film.id in result)
        assertEquals(true, orphan.id in result)
        assertEquals(3, result.size)
    }

    @Test
    fun negativeDaysUseFloorMod() {
        val pool = episodes(20, 20, 20, 20, 20, 20, 20)
        val keeps = keeps(40)
        for (n in listOf(-1L, -7L, -8L, -100L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val start = (n.mod(7L) * 2L).mod(7L).toInt()
            val expected = listOf(start, (start + 1) % 7).map { it + 1 }.sorted()
            assertEquals(expected, kept(pool, n, keeps), "day $n")
        }
    }

    @Test
    fun theWindowIsTheLastStepOfThePool() {
        val settings = testSettings()
        val inPool = episodes(20, 20, 20, 20)
        // A fifth episode added after the cut-off is not in the pool: it counts toward neither the prefix nor the size.
        val late = episodeOf(1, 5, 20).copy(id = hexIdOf(1, 5), createdAtMs = Long.MAX_VALUE / 4)
        val pool = EpochPool.build(settings, inPool + late, 0L, keeps(40))
        assertEquals(listOf(1, 2), pool.map { checkNotNull(it.episodeNumber) }.sorted())
    }

    @Test
    fun aRestoredDayEqualsAFreshBuildWithKeeps() {
        val settings = testSettings()
        val pool = EpochPool.build(settings, episodes(20, 20, 20, 20, 20, 20), 1L)
        val fresh = buildDay(settings, pool, 1L, noFilms(), noWeights(), keeps(40))
        // The day pool is the pool after the window; a day restored from it is built with NONE.
        assertEquals(listOf(3, 4), fresh.pool.map { checkNotNull(it.episodeNumber) }.sorted())
        val restored = buildDay(settings, fresh.pool, 1L, noFilms(), noWeights(), noKeeps())
        assertEquals(fresh.blocks, restored.blocks)
        assertEquals(fresh.pool, restored.pool)
        val aired = restored.blocks.mapNotNull { (it.content as? ScheduleContent.Program)?.itemId }
        assertEquals(fresh.pool.map { it.id }.toSet(), aired.toSet())
    }

    @Test
    fun thePublicPoolBuilderNeverWindows() {
        val settings = testSettings()
        val pool = EpochPool.build(settings, episodes(20, 20, 20, 20, 20, 20), 1L)
        assertEquals(6, pool.size)
    }
}
