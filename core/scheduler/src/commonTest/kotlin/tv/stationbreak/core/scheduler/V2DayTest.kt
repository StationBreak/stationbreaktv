// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.RuntimeFilter
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class V2DayTest {
    private val collection: CanonicalId = idOf('c')
    private val films: List<ScheduleItem> = (1..3).map { item(idOf(('0' + it)), null, 1990 + it) }
    private val showA: List<ScheduleItem> = (1..3).map { item(episodeId('a', it), idOf('a'), null, it) }
    private val showB: List<ScheduleItem> = (1..3).map { item(episodeId('b', it), idOf('b'), null, it) }
    private val pool: List<ScheduleItem> = films + showA + showB
    private val map: FilmCollections = FilmCollections.of(films.associate { it.id to collection })

    private fun episodeId(
        series: Char,
        number: Int,
    ) = CanonicalId.parse(series.toString().repeat(30) + "0$number")

    private fun item(
        id: CanonicalId,
        series: CanonicalId?,
        year: Int?,
        episode: Int? = null,
    ): ScheduleItem =
        ScheduleItem(
            id = id,
            kind = if (series == null) ScheduleItemKind.Movie else ScheduleItemKind.Episode,
            runtimeMs = 30 * MS_MINUTE,
            createdAtMs = -MS_DAY,
            deletedAtMs = null,
            premiereDate = null,
            productionYear = year,
            seriesId = series,
            seasonNumber = episode?.let { 1 },
            episodeNumber = episode,
        )

    private fun rotation(film: Int): SchedulerSettings =
        testSettings(OrderingMode.Rotation, Seed(7uL), alignToGrid = false, caps = Caps(3, film))

    private fun programs(
        settings: SchedulerSettings,
        films: FilmCollections,
    ) = buildDay(
        settings,
        pool,
        1L,
        films,
        noWeights(),
        noKeeps(),
    ).blocks.map { (it.content as ScheduleContent.Program).itemId }

    @Test
    fun aMapGroupsFilmsInRotation() {
        for (filmCap in listOf(1, 3)) {
            val settings = rotation(filmCap)
            val turns = rotationTurnsOfADay(pool, settings.seed, settings.caps!!, GroupRules(map, noWeights()))
            val cycle = turns.flatten().map { it.id }
            val aired = programs(settings, map)

            // A day of a continuous playout is the cycle, repeated, from some run: a rotation of it.
            val matches = cycle.indices.any { k -> aired.indices.all { aired[it] == cycle[(k + it) % cycle.size] } }
            if (filmCap == 3) assertNotEquals(programs(settings, noFilms()), aired, "the map changes the day")
            assertTrue(matches, "film cap $filmCap: the day follows the rotation with the collection as one group")
        }
        val capThree = rotationTurnsOfADay(pool, Seed(7uL), Caps(3, 3), GroupRules(map, noWeights()))
        assertTrue(capThree.any { turn -> turn.map { it.id } == films.map { it.id } }, "three films in one turn")
        val unmapped = rotationTurnsOfADay(pool, Seed(7uL), Caps(3, 3), GroupRules(noFilms(), noWeights()))
        assertTrue(unmapped.none { turn -> turn.size == films.size && turn.all { it in films } }, "no map, no group")
        val capOne = rotationTurnsOfADay(pool, Seed(7uL), Caps(3, 1), GroupRules(map, noWeights()))
        assertTrue(capOne.none { turn -> turn.size > 1 && turn.all { it in films } }, "one film per turn")
    }

    @Test
    fun anInputErrorStillThrowsBeforeBuilding() {
        val settings = rotation(1)

        assertFailsWith<IllegalArgumentException> {
            buildDay(
                settings,
                pool + films.first(),
                1L,
                map,
                noWeights(),
                noKeeps(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildDay(
                settings,
                listOf(films.first().copy(createdAtMs = Long.MAX_VALUE)),
                1L,
                map,
                noWeights(),
                noKeeps(),
            )
        }
    }

    @Test
    fun epochPoolInputsDifferByRuntimeFilter() {
        val plain = testSettings()
        val filtered = testSettings(runtimeFilter = RuntimeFilter(minMs = 10 * MS_MINUTE, maxMs = null))

        assertNotEquals(EpochPool.inputsOf(plain), EpochPool.inputsOf(filtered))
        assertNotEquals(EpochPool.inputsOf(plain), EpochPool.inputsOf(testSettings(graceHours = 2)))
        assertEquals(EpochPool.inputsOf(plain), EpochPool.inputsOf(testSettings(seed = Seed(99uL), blockLength = 4)))
    }
}
