// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grown-up hold of `grown-up-hold-4` (ADR-0020 addendum): a grown-up pool drops only what a kid channel could take
 * (made for kids and within the widest kid ceiling); the rest stays (option C).
 */
class PoolEvaluatorGrownUpHoldTest {
    private fun libraryRules(): ChannelRules = rules(listOf(Source.Library(LIB)), null, null)

    private fun idsOf(pool: Pool) = pool.items.map { it.id }

    private val show = idOf(0x60)

    private fun kidsRules(): ChannelRules = rules(listOf(Source.Library(LIB)), null, "PG")

    private val animatedFamily = listOf("Animation", "Family")

    @Test
    fun aGRatedAnimatedFamilyFilmIsDroppedFromGrownUpAndKeptByAKidPool() {
        val snap = snapshot(items = listOf(movie(idOf(1), rating = "G", genres = animatedFamily)))
        val grownUp = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(emptyList(), idsOf(grownUp))
        assertEquals(1, grownUp.counts.madeForKidsOut)
        assertEquals(listOf(idOf(1)), idsOf(PoolEvaluator.evaluate(kidsRules(), snap, PoolPolicy.KidsOnly)))
    }

    @Test
    fun anUnratedAnimatedFamilyFilmIsKeptByGrownUpAndHeldOutOfAKidPool() {
        for (rating in listOf(null, "NR", "Not Rated")) {
            val snap = snapshot(items = listOf(movie(idOf(1), rating = rating, genres = animatedFamily)))
            val grownUp = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
            assertEquals(listOf(idOf(1)), idsOf(grownUp), "grown-up keeps rating $rating")
            assertEquals(0, grownUp.counts.madeForKidsOut)
            val kids = PoolEvaluator.evaluate(kidsRules(), snap, PoolPolicy.KidsOnly)
            assertEquals(emptyList(), idsOf(kids), "kid pool holds out rating $rating")
        }
    }

    @Test
    fun aKidStudioFilmRatedTvPgIsKeptByGrownUp() {
        val snap = snapshot(items = listOf(movie(idOf(1), rating = "TV-PG", studios = listOf("Pixar"))))
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(listOf(idOf(1)), idsOf(pool))
        assertEquals(0, pool.counts.madeForKidsOut)
        assertTrue(PoolEvaluator.evaluate(kidsRules(), snap, PoolPolicy.KidsOnly).items.isEmpty())
    }

    @Test
    fun aKidStudioFilmRatedPgIsDroppedFromGrownUp() {
        val snap = snapshot(items = listOf(movie(idOf(1), rating = "PG", studios = listOf("Pixar"))))
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(emptyList(), idsOf(pool))
        assertEquals(1, pool.counts.madeForKidsOut)
    }

    // grown-up-hold-3 (ADR-0026): a kid studio spelled with a blank-width letter between its words still counts.
    @Test
    fun aKidStudioSpelledWithAHangulFillerIsDroppedFromGrownUpAtPg() {
        val lookAlike = movie(idOf(1), rating = "PG", studios = listOf("Cartoon\u3164Network"))
        val snap = snapshot(items = listOf(lookAlike, movie(idOf(2), rating = "PG", studios = listOf("Other Co"))))
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(listOf(idOf(2)), idsOf(pool))
        assertEquals(1, pool.counts.madeForKidsOut)
        assertEquals(listOf(idOf(1)), idsOf(PoolEvaluator.evaluate(kidsRules(), snap, PoolPolicy.KidsOnly)))
    }

    @Test
    fun aKidStudioSeriesRatedTv14WithTv14EpisodesIsKeptByGrownUp() {
        val snap =
            snapshot(
                items = listOf(episode(idOf(1), show, rating = "TV-14"), episode(idOf(2), show, rating = "TV-14")),
                series = listOf(series(show, rating = "TV-14", studios = listOf("Pixar"))),
            )
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(listOf(idOf(1), idOf(2)), idsOf(pool))
        assertEquals(0, pool.counts.madeForKidsOut)
    }

    @Test
    fun aKidsGenreFilmRatedAboveTheKidCeilingIsKeptByGrownUp() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = "PG-13", genres = listOf("Kids")),
                        movie(idOf(2), rating = "R", studios = listOf("Pixar")),
                        movie(idOf(3), rating = "PG", genres = listOf("Kids")),
                    ),
            )
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(listOf(idOf(1), idOf(2)), idsOf(pool))
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
    fun anEpisodeWhoseSeriesIsMissingFromTheSnapshotIsKept() {
        val snap = snapshot(items = listOf(episode(idOf(1), show, rating = "TV-Y")))
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(listOf(idOf(1)), idsOf(pool))
        assertEquals(0, pool.counts.madeForKidsOut)
    }

    @Test
    fun madeForKidsOutCountsOnlyWhatIsDropped() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = "TV-Y"),
                        movie(idOf(2), genres = listOf("Kids")),
                        movie(idOf(3), studios = listOf("Pixar")),
                        movie(idOf(4), rating = "G", genres = animatedFamily),
                        movie(idOf(5), rating = "R"),
                    ),
            )
        val pool = PoolEvaluator.evaluate(libraryRules(), snap, PoolPolicy.GrownUp)
        assertEquals(2, pool.counts.madeForKidsOut)
        assertEquals(0, pool.counts.heldOut)
        assertEquals(listOf(idOf(2), idOf(3), idOf(5)), idsOf(pool))
    }

    @Test
    fun dropsFromGrownUpIsMadeForKidsAndWithinTheWidestKidCeiling() {
        val kept = movie(idOf(1), rating = "TV-PG", studios = listOf("Pixar"))
        val dropped = movie(idOf(2), rating = "G", genres = animatedFamily)
        assertEquals(false, GrownUpHold.dropsFromGrownUp(kept, null))
        assertEquals(true, GrownUpHold.dropsFromGrownUp(dropped, null))
    }
}
