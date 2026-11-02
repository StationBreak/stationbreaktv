// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RotationTest {
    /** A seeded pool: some series, some single films, and some films in collections. */
    private class Pool(
        val items: List<ScheduleItem>,
        val collections: Map<CanonicalId, CanonicalId>,
    ) {
        val films = FilmCollections.of(collections)
    }

    private fun film(
        id: CanonicalId,
        year: Int? = null,
    ) = ScheduleItem(
        id = id,
        kind = ScheduleItemKind.Movie,
        runtimeMs = 30 * MS_MINUTE,
        createdAtMs = 0L,
        deletedAtMs = null,
        premiereDate = null,
        productionYear = year,
        seriesId = null,
        seasonNumber = null,
        episodeNumber = null,
    )

    private fun episode(
        id: CanonicalId,
        series: CanonicalId,
        number: Int,
    ) = ScheduleItem(
        id = id,
        kind = ScheduleItemKind.Episode,
        runtimeMs = 30 * MS_MINUTE,
        createdAtMs = 0L,
        deletedAtMs = null,
        premiereDate = null,
        productionYear = null,
        seriesId = series,
        seasonNumber = 1,
        episodeNumber = number,
    )

    private fun SeededGen.id() = CanonicalId.parse(hexId())

    private fun SeededGen.pool(withCollections: Boolean): Pool {
        val items = ArrayList<ScheduleItem>()
        repeat(nextInt(0..6)) {
            val series = id()
            repeat(nextInt(1..8)) { number -> items += episode(id(), series, number + 1) }
        }
        repeat(nextInt(0..6)) { items += film(id()) }
        val map = HashMap<CanonicalId, CanonicalId>()
        if (withCollections) {
            repeat(nextInt(0..3)) {
                val collection = id()
                repeat(nextInt(1..5)) {
                    val member = film(id(), if (chance(70)) 1950 + nextInt(60) else null)
                    items += member
                    map[member.id] = collection
                }
            }
        }
        return Pool(items, map)
    }

    private fun List<ScheduleItem>.shuffledBy(salt: ULong) = Orderings.shuffle(this, salt)

    private fun GroupRules.groupOf(turn: List<ScheduleItem>) = groupId(turn.first())

    @Test
    fun rotationEqualsRandomInOrderWhenCapsEqualBlockLength() {
        forAllCases("rotation equals randomInOrder", 0x7001uL, cases = 50) { gen ->
            val pool = gen.pool(withCollections = false).items
            val seed = Seed(gen.nextULong())
            val block = gen.nextInt(1..6)
            assertEquals(
                Orderings.randomInOrderTurns(pool, seed, block),
                rotationTurnsOfADay(pool, seed, Caps(block, block), GroupRules(noFilms())),
            )
        }
    }

    @Test
    fun eachTurnIsOneGroupAtMostItsCapInTheGroupsOwnOrder() {
        forAllCases("turns are one group within the cap", 0x7002uL, cases = 300) { gen ->
            val built = gen.pool(withCollections = true)
            val rules = GroupRules(built.films)
            val caps = Caps(gen.nextInt(1..5), gen.nextInt(1..3))
            val turns = rotationTurnsOfADay(built.items, Seed(gen.nextULong()), caps, rules)

            assertEquals(built.items.sortedBy { it.id.value }, turns.flatten().sortedBy { it.id.value })
            val played = HashMap<CanonicalId, MutableList<ScheduleItem>>()
            for (turn in turns) {
                assertTrue(turn.isNotEmpty())
                val group = rules.groupOf(turn)
                assertTrue(turn.all { rules.groupId(it) == group }, "a turn is one group")
                assertTrue(turn.size <= rules.cap(turn.first(), caps), "a turn is within its cap")
                played.getOrPut(group) { ArrayList() } += turn
            }
            for ((group, order) in played) {
                val members = built.items.filter { rules.groupId(it) == group }
                val expected =
                    when (rules.kind(members.first())) {
                        GroupKind.Series -> members.sortedWith(Orderings.inGroupKey)
                        GroupKind.Collection -> members.sortedWith(Orderings.releaseKey)
                        GroupKind.Single -> members
                    }
                assertEquals(expected, order)
            }
        }
    }

    @Test
    fun aFilmCollectionIsOneGroupInReleaseOrder() {
        val collection = idOf('c')
        val undated = film(idOf('1'))
        val y1990 = film(idOf('2'), 1990)
        val y1985 = film(idOf('3'), 1985)
        val films = FilmCollections.of(listOf(undated, y1990, y1985).associate { it.id to collection })

        val turns = rotationTurnsOfADay(listOf(undated, y1990, y1985), Seed(5uL), Caps(3, 3), GroupRules(films))

        assertEquals(listOf(listOf(y1985, y1990, undated)), turns)
    }

    @Test
    fun aFilmInTwoNamedCollectionsPlaysUnderTheSmallerIdMap() {
        val smaller = idOf('a')
        val larger = idOf('b')
        val first = film(idOf('1'))
        val second = film(idOf('2'))
        // The caller resolved the film in both collections to the smaller ID; the rules use the map as given.
        val rules = GroupRules(FilmCollections.of(mapOf(first.id to smaller, second.id to larger)))

        assertEquals(smaller, rules.groupId(first))
        assertEquals(larger, rules.groupId(second))
        assertEquals(GroupKind.Collection, rules.kind(first))
        assertEquals(GroupKind.Single, rules.kind(film(idOf('3'))))
    }

    @Test
    fun capsFollowTheKindOfGroup() {
        val series = idOf('5')
        val member = film(idOf('1'))
        val rules = GroupRules(FilmCollections.of(mapOf(member.id to idOf('c'))))
        val caps = Caps(series = 4, film = 2)

        assertEquals(4, rules.cap(episode(idOf('2'), series, 1), caps))
        assertEquals(2, rules.cap(member, caps))
        assertEquals(1, rules.cap(film(idOf('3')), caps))
    }

    @Test
    fun onlyAFilmIsInACollection() {
        val episodeWithoutSeries = episode(idOf('1'), idOf('5'), 1).copy(seriesId = null)
        val rules = GroupRules(FilmCollections.of(mapOf(episodeWithoutSeries.id to idOf('c'))))

        assertEquals(episodeWithoutSeries.id, rules.groupId(episodeWithoutSeries))
        assertEquals(GroupKind.Single, rules.kind(episodeWithoutSeries))
    }

    @Test
    fun aGroupJoiningNeverReordersTheOthers() {
        forAllCases("a joiner leaves the others alone", 0x7003uL, cases = 100) { gen ->
            val built = gen.pool(withCollections = true)
            val rules = GroupRules(built.films)
            val seed = Seed(gen.nextULong())
            val caps = Caps(gen.nextInt(1..5), gen.nextInt(1..3))
            val series = gen.id()
            val collection = gen.id()
            val members = (1..gen.nextInt(1..4)).map { film(gen.id(), 1950 + gen.nextInt(60)) }
            val single = film(gen.id())
            val joiners =
                listOf(
                    (1..gen.nextInt(1..6)).map { episode(gen.id(), series, it) },
                    members,
                    listOf(single),
                )
            val joinedFilms = FilmCollections.of(built.collections + members.associate { it.id to collection })
            val joinedRules = GroupRules(joinedFilms)
            val before = rotationTurnsOfADay(built.items, seed, caps, rules)

            for (joiner in joiners) {
                val joined = rotationTurnsOfADay(built.items + joiner, seed, caps, joinedRules)
                val ids = joiner.map { it.id }.toSet()
                assertEquals(before, joined.filter { turn -> turn.none { it.id in ids } })
            }
        }
    }

    @Test
    fun aRotationTurnLongerThanTheEpochIsCutBeforeTheItemThatWouldOverflowIt() {
        // Four 10-hour films of one collection, film cap 3, a 24-hour epoch: two films fit (20 h), the third would
        // make 30 h. The next turn of the group continues from the third film.
        val collection = idOf('c')
        val films = (1..4).map { film(idOf(('0' + it)), 1990 + it).copy(runtimeMs = 10 * MS_HOUR) }
        val rules = GroupRules(FilmCollections.of(films.associate { it.id to collection }))

        val turns = Orderings.rotationTurns(films, Seed(5uL), Caps(3, 3), rules, MS_DAY)

        assertEquals(listOf(films.take(2), films.drop(2).take(2)), turns)
        // An item longer than the epoch still gets a turn of its own: the left-out rule handles it.
        val huge = films.first().copy(runtimeMs = 30 * MS_HOUR)
        assertEquals(listOf(listOf(huge)), Orderings.rotationTurns(listOf(huge), Seed(5uL), Caps(3, 3), rules, MS_DAY))
    }

    @Test
    fun randomInOrderTurnsAreNotCutByTheEpoch() {
        val series = idOf('5')
        val episodes =
            (1..3).map {
                episode(CanonicalId.parse(it.toString().repeat(32)), series, it).copy(runtimeMs = 10 * MS_HOUR)
            }

        assertEquals(listOf(episodes), Orderings.randomInOrderTurns(episodes, Seed(1uL), 3))
    }

    @Test
    fun aSingleGroupPlaysStraightThrough() {
        val series = idOf('5')
        val episodes = (1..20).map { episode(CanonicalId.parse(it.toString(16).padStart(32, '0')), series, it) }

        val turns = rotationTurnsOfADay(episodes.reversed(), Seed(3uL), Caps(3, 1), GroupRules(noFilms()))

        assertEquals(episodes, turns.flatten())
        assertEquals(List(6) { 3 } + 2, turns.map { it.size })
    }

    @Test
    fun adjacentTurnsOfOneGroupOccurOnlyWhenEveryOtherGroupIsUsedUp() {
        forAllCases("adjacent turns of a group", 0x7004uL, cases = 300) { gen ->
            val built = gen.pool(withCollections = true)
            val rules = GroupRules(built.films)
            val caps = Caps(gen.nextInt(1..5), gen.nextInt(1..3))
            val turns = rotationTurnsOfADay(built.items, Seed(gen.nextULong()), caps, rules)

            for (index in 0 until turns.size - 1) {
                val group = rules.groupOf(turns[index])
                if (rules.groupOf(turns[index + 1]) != group) continue
                val used =
                    turns
                        .take(index + 1)
                        .flatten()
                        .map { it.id }
                        .toSet()
                val others = built.items.filter { rules.groupId(it) != group }
                assertTrue(others.all { it.id in used }, "turn $index repeats its group while another still has items")
            }
        }
    }

    @Test
    fun blockLengthIsIgnoredByRotation() {
        forAllCases("rotation ignores blockLength", 0x7005uL, cases = 50) { gen ->
            val built = gen.pool(withCollections = true)
            val rules = GroupRules(built.films)
            val seed = Seed(gen.nextULong())
            val caps = Caps(gen.nextInt(1..5), gen.nextInt(1..3))

            fun runs(block: Int) =
                Runs
                    .cycle(testSettings(OrderingMode.Rotation, seed, block, caps = caps), built.items, 7uL, rules)
                    .map { run -> run.items.map { it.id } }

            assertEquals(runs(1), runs(9))
            val turns = rotationTurnsOfADay(built.items, seed, caps, rules)
            assertEquals(turns.map { turn -> turn.map { it.id } }, runs(1))
        }
    }

    @Test
    fun rotationIsAPureFunctionOfThePoolNotItsOrder() {
        forAllCases("rotation ignores pool order", 0x7006uL, cases = 100) { gen ->
            val built = gen.pool(withCollections = true)
            val rules = GroupRules(built.films)
            val seed = Seed(gen.nextULong())
            val caps = Caps(gen.nextInt(1..5), gen.nextInt(1..3))
            val expected = rotationTurnsOfADay(built.items, seed, caps, rules)

            assertEquals(expected, rotationTurnsOfADay(built.items.reversed(), seed, caps, rules))
            assertEquals(expected, rotationTurnsOfADay(built.items.shuffledBy(gen.nextULong()), seed, caps, rules))
        }
    }

    @Test
    fun theRotationWrapMayRepeatAGroupAtTheSeam() {
        // Ruling 2: Rotation has no seam rule. The group first in key order has more items than the other, so the last
        // turn of the cycle and the first turn of the next are the same group. That is the expected order, not a bug.
        val a = idOf('a')
        val b = idOf('b')
        val (early, late) = if (Orderings.groupKey(Seed(9uL), a) <= Orderings.groupKey(Seed(9uL), b)) a to b else b to a
        val pool =
            (1..3).map { episode(CanonicalId.parse(it.toString().repeat(32)), early, it) } +
                episode(idOf('9'), late, 1)

        val turns = rotationTurnsOfADay(pool, Seed(9uL), Caps(1, 1), GroupRules(noFilms()))

        assertEquals(listOf(early, late, early, early), turns.map { it.single().seriesId })
        assertEquals(early, turns.last().single().seriesId)
        assertEquals(turns.first().single().seriesId, turns.last().single().seriesId)
    }

    @Test
    fun plannedEndPassedOverUsesTheCollectionGroup() {
        val collection = idOf('c')
        val long = film(idOf('1'), 1990).copy(runtimeMs = 90 * MS_MINUTE)
        val short = film(idOf('2'), 1991)
        val pool = listOf(long, short)
        // A seed that puts the long film's group first, so its own-group case reaches the short film after it.
        val seed =
            (1uL..50uL).map { Seed(it) }.first {
                Orderings.groupKey(it, long.id) < Orderings.groupKey(it, short.id)
            }
        val settings = testSettings(OrderingMode.Rotation, seed, alignToGrid = false, caps = Caps(3, 1))
        val dayEnd = 60 * MS_MINUTE

        fun blocks(films: FilmCollections): List<ScheduleBlock> {
            val rules = GroupRules(films)
            val cycle = Timeline.cycle(settings, pool, 0uL, rules)
            return Timeline.layout(settings, cycle, 0, 0L, dayEnd, rules).blocks
        }

        // The long film does not fit and is passed over; its collection mate may not air before it.
        assertEquals(
            listOf(ScheduleBlock(0L, dayEnd, ScheduleContent.Slate)),
            blocks(FilmCollections.of(mapOf(long.id to collection, short.id to collection))),
        )
        // With no collection each film is its own group, so the short one fills the end.
        assertEquals(
            listOf(
                ScheduleBlock(0L, 30 * MS_MINUTE, ScheduleContent.Program(short.id)),
                ScheduleBlock(30 * MS_MINUTE, dayEnd, ScheduleContent.Slate),
            ),
            blocks(noFilms()),
        )
    }
}
