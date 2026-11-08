// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import kotlin.random.Random

/**
 * Spec 26, whole-part fix (data LOW-1): the match indexes the live rows once instead of filtering them all per gone
 * item, and gives the same answer as before on every input. Random inputs over small value sets, so ids, names, years,
 * series and episode numbers collide often; each is compared with [ReferenceReIdMatch]. Invented values only.
 */
class ReIdMatchEquivalenceTest {
    private fun id(n: Int) = CanonicalId.parse(hexId(n))

    private fun Random.ids(): Map<String, String> {
        val out = HashMap<String, String>()
        for (key in KEYS) if (nextInt(3) == 0) out[key] = "v${nextInt(3)}"
        return out
    }

    private fun <T> Random.pick(vararg values: T): T = values[nextInt(values.size)]

    private fun Random.signals(n: Int): Signals =
        Signals(
            id = id(n),
            kind = pick("Movie", "Movie", "Episode", "Episode", "Video"),
            providerIds = ids(),
            seriesId = if (nextInt(4) == 0) null else id(SERIES + nextInt(SERIES_RANGE)),
            season = pick(null, 1, 2),
            episode = pick(null, 1, 2),
            foldedName = pick("", "a", "b"),
            year = pick(null, 2000, 2001),
            runtimeMs = pick(null, 30 * MINUTE, 31 * MINUTE, 40 * MINUTE),
        )

    private fun Random.series(n: Int): SeriesSignals = SeriesSignals(id(n), pick("", "s", "t"), ids())

    private fun Random.seriesOf(range: IntRange): List<SeriesSignals> =
        range.shuffled(this).take(nextInt(0, range.count() + 1)).map { series(SERIES + it) }

    // Distinct ids within each list, as the reads give them; gone and live ids may overlap (never their own candidate).
    private fun Random.items(
        from: Int,
        count: Int,
    ): List<Signals> = (from until from + ID_RANGE).shuffled(this).take(count).map { signals(it) }

    @Test
    fun theIndexedMatchAgreesWithTheReferenceOnRandomInputs() {
        var matched = 0
        repeat(RUNS) { seed ->
            val random = Random(seed)
            val gone = random.items(1, random.nextInt(1, 8))
            val live = random.items(4, random.nextInt(0, 14))
            // Tombstoned series mostly in the lower half and live ones in the upper, so a series that came back under a
            // new id (and its episodes) is common; an overlap is still possible.
            val goneSeries = random.seriesOf(0 until SERIES_RANGE / 2 + 1)
            val liveSeries = random.seriesOf(SERIES_RANGE / 2 - 1 until SERIES_RANGE)

            val expected = ReferenceReIdMatch.match(gone, live, liveSeries, goneSeries)
            val actual = ReIdMatch.match(gone, live, liveSeries, goneSeries)

            assertEquals("seed $seed", expected, actual)
            assertEquals(
                "seed $seed",
                ReferenceReIdMatch.movedSeries(goneSeries, liveSeries),
                ReIdMatch.movedSeries(goneSeries, liveSeries),
            )
            if (expected.isNotEmpty()) matched++
        }
        // The inputs reach the matches too, not only the empty answers.
        assert(matched > RUNS / 10) { "only $matched of $RUNS runs matched anything" }
    }

    private companion object {
        const val RUNS = 4_000
        const val ID_RANGE = 12
        const val SERIES = 900
        const val SERIES_RANGE = 6
        const val MINUTE = 60_000L
        val KEYS = listOf("imdb", "tmdb", "tvdb", "tmdbcollection")
    }
}
