// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/** The grown-up hold of `grown-up-hold-2` (ADR-0020 addendum): a grown-up pool drops what is made for kids. */
class PoolEvaluatorGrownUpHoldTest {
    private fun libraryRules(): ChannelRules = rules(listOf(Source.Library(LIB)), null, null)

    private fun idsOf(pool: Pool) = pool.items.map { it.id }

    @Test
    fun grownUpDropsAKidStudioItemWithAGrownUpRating() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = "R", studios = listOf("Pixar")),
                        movie(idOf(2), rating = "R"),
                    ),
            )
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(listOf(idOf(2)), idsOf(pool))
        assertEquals(1, pool.counts.madeForKidsOut)
    }

    @Test
    fun grownUpDropsAKidsGenreItem() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = "PG-13", genres = listOf("Kids")),
                        movie(idOf(2), rating = "PG-13", genres = listOf("Comedy")),
                    ),
            )
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(listOf(idOf(2)), idsOf(pool))
        assertEquals(1, pool.counts.madeForKidsOut)
    }

    @Test
    fun grownUpDropsAnAnimatedFamilyGFilm() {
        val snap = snapshot(items = listOf(movie(idOf(1), rating = "G", genres = listOf("Animation", "Family"))))
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(emptyList(), idsOf(pool))
        assertEquals(1, pool.counts.madeForKidsOut)
    }

    @Test
    fun grownUpKeepsALiveActionFamilyPgFilm() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = "PG", genres = listOf("Family", "Comedy")),
                        movie(idOf(2), rating = "PG", genres = listOf("Animation", "Family")),
                    ),
            )
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(listOf(idOf(1), idOf(2)), idsOf(pool))
        assertEquals(0, pool.counts.madeForKidsOut)
    }

    @Test
    fun madeForKidsOutIsCounted() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = "TV-Y"),
                        movie(idOf(2), genres = listOf("Kids")),
                        movie(idOf(3), studios = listOf("Pixar")),
                        movie(idOf(4), rating = "G", genres = listOf("Animation", "Family")),
                        movie(idOf(5), rating = "R"),
                    ),
            )
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(4, pool.counts.madeForKidsOut)
        assertEquals(0, pool.counts.heldOut)
        assertEquals(listOf(idOf(5)), idsOf(pool))
    }
}
