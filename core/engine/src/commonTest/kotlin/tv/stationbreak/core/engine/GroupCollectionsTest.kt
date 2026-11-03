// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupCollectionsTest {
    private val small = CanonicalId.parse("0".repeat(31) + "1")
    private val large = CanonicalId.parse("f".repeat(32))
    private val film1 = movie(idOf(1))
    private val film2 = movie(idOf(2))

    private fun rotation(named: List<CanonicalId>): ChannelRules =
        rules(
            named.map { Source.Collection(it) },
            formatVersion = 2,
            ordering = Ordering(OrderingMode.Rotation),
        )

    private fun mapOf(
        rules: ChannelRules,
        snap: RuleSnapshot,
        vararg pool: RuleItem,
    ): Map<CanonicalId, CanonicalId?> {
        val items: List<ScheduleItem> = pool.map { it.item }
        val films = GroupCollections.of(rules, snap, items)
        return items.associate { it.id to films[it.id] }
    }

    @Test
    fun aFilmInTwoNamedCollectionsTakesTheSmallerIdFromTheTwoIdsGiven() {
        val snap =
            snapshot(
                items = listOf(film1),
                collections =
                    listOf(
                        collection(large, movies = setOf(film1.item.id)),
                        collection(small, movies = setOf(film1.item.id)),
                    ),
            )

        assertEquals(small, mapOf(rotation(listOf(large, small)), snap, film1)[film1.item.id])
        assertEquals(small, mapOf(rotation(listOf(small, large)), snap, film1)[film1.item.id])
    }

    @Test
    fun onlyNamedCollectionsGroupFilms() {
        val unnamed = idOf(0x31)
        val snap =
            snapshot(
                items = listOf(film1, film2),
                collections =
                    listOf(
                        collection(large, movies = setOf(film1.item.id)),
                        collection(unnamed, movies = setOf(film1.item.id, film2.item.id)),
                    ),
            )

        val map = mapOf(rotation(listOf(large)), snap, film1, film2)

        assertEquals(large, map[film1.item.id])
        assertNull(map[film2.item.id])
    }

    @Test
    fun aCollectionOfShowsMapsNoFilms() {
        val ep = episode(idOf(3), idOf(0x10))
        val snap =
            snapshot(
                items = listOf(film1, ep),
                collections = listOf(collection(small, series = setOf(idOf(0x10)), episodes = setOf(ep.item.id))),
            )

        val map = mapOf(rotation(listOf(small)), snap, film1, ep)

        assertNull(map[film1.item.id])
        assertNull(map[ep.item.id])
    }

    @Test
    fun anUnresolvedCollectionIsSkipped() {
        val members = setOf(film1.item.id)
        val snap = snapshot(items = listOf(film1), collections = listOf(collection(large, movies = members)))
        val names = rotation(listOf(small))

        assertNull(mapOf(names, snap, film1)[film1.item.id])
    }

    @Test
    fun filmsOutsideThePoolAreNotInTheMap() {
        val snap =
            snapshot(
                items = listOf(film1, film2),
                collections = listOf(collection(small, movies = setOf(film1.item.id, film2.item.id))),
            )

        val films = GroupCollections.of(rotation(listOf(small)), snap, listOf(film1.item))

        assertEquals(small, films[film1.item.id])
        assertNull(films[film2.item.id])
    }

    @Test
    fun aModeWithoutCapsHasAnEmptyMap() {
        val members = setOf(film1.item.id)
        val snap = snapshot(items = listOf(film1), collections = listOf(collection(small, movies = members)))
        val chronological = rules(listOf(Source.Collection(small)), ordering = Ordering(OrderingMode.Chronological))

        assertNull(GroupCollections.of(chronological, snap, listOf(film1.item))[film1.item.id])
    }

    private val curated = Ordering(OrderingMode.Curated)

    private fun curatedRules(named: List<CanonicalId> = emptyList()): ChannelRules =
        rules(listOf(Source.Library(LIB)) + named.map { Source.Collection(it) }, formatVersion = 3, ordering = curated)

    @Test
    fun curatedMapsEveryCollectionNotOnlyNamedOnes() {
        val ep = episode(idOf(3), idOf(0x10))
        val snap =
            snapshot(
                items = listOf(film1, film2, ep),
                collections =
                    listOf(
                        collection(large, movies = setOf(film1.item.id)),
                        collection(small, movies = setOf(film2.item.id), episodes = setOf(ep.item.id)),
                    ),
            )

        val map = mapOf(curatedRules(), snap, film1, film2, ep)

        assertEquals(large, map[film1.item.id])
        assertEquals(small, map[film2.item.id])
        assertNull(map[ep.item.id])
    }

    @Test
    fun curatedTakesTheSmallestCollectionId() {
        val snap =
            snapshot(
                items = listOf(film1),
                collections =
                    listOf(
                        collection(large, movies = setOf(film1.item.id)),
                        collection(small, movies = setOf(film1.item.id)),
                    ),
            )

        // Named or not, the smallest ID of every snapshot collection wins.
        assertEquals(small, mapOf(curatedRules(listOf(large)), snap, film1)[film1.item.id])
        assertEquals(small, mapOf(curatedRules(), snap, film1)[film1.item.id])
    }

    @Test
    fun curatedMapsOnlyFilmsOfThePool() {
        val snap =
            snapshot(
                items = listOf(film1, film2),
                collections = listOf(collection(small, movies = setOf(film1.item.id, film2.item.id))),
            )

        val films = GroupCollections.of(curatedRules(), snap, listOf(film1.item))

        assertEquals(small, films[film1.item.id])
        assertNull(films[film2.item.id])
    }

    @Test
    fun otherModesAreUnchanged() {
        val snap =
            snapshot(
                items = listOf(film1, film2),
                collections =
                    listOf(
                        collection(large, movies = setOf(film1.item.id)),
                        collection(small, movies = setOf(film2.item.id)),
                    ),
            )

        // Rotation maps the named collections only; a mode without caps maps nothing.
        val rotation = mapOf(rotation(listOf(large)), snap, film1, film2)
        assertEquals(large, rotation[film1.item.id])
        assertNull(rotation[film2.item.id])
        val shuffle = rules(listOf(Source.Collection(small)), ordering = Ordering(OrderingMode.Shuffle))
        assertNull(mapOf(shuffle, snap, film1, film2)[film2.item.id])
    }
}
