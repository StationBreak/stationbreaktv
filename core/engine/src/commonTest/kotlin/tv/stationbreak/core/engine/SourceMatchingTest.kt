// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/rules-format.md, "Items and the pool", rule 2: one table per kind of source. */
class SourceMatchingTest {
    private fun hit(
        source: Source,
        item: RuleItem,
        snapshot: RuleSnapshot,
    ): Boolean {
        val seriesId = item.item.seriesId
        val series = if (seriesId == null) null else snapshot.series[seriesId]
        return SourceMatcher(listOf(source), snapshot).matches(item, series)
    }

    private fun check(
        source: Source,
        snapshot: RuleSnapshot,
        vararg cases: Pair<RuleItem, Boolean>,
    ) {
        for ((index, case) in cases.withIndex()) {
            assertEquals(case.second, hit(source, case.first, snapshot), "case $index of $source")
        }
    }

    private val show = idOf(0x10)
    private val otherShow = idOf(0x11)
    private val season = idOf(0x20)
    private val otherLib = idOf(0xB0)

    @Test
    fun library() {
        val snap = snapshot()
        check(
            Source.Library(LIB),
            snap,
            movie(idOf(1)) to true,
            movie(idOf(2), library = otherLib) to false,
            episode(idOf(3), show) to true,
            episode(idOf(4), show, library = otherLib) to false,
        )
    }

    @Test
    fun series() {
        val snap = snapshot(series = listOf(series(show), series(otherShow)))
        check(
            Source.Series(show),
            snap,
            episode(idOf(1), show) to true,
            episode(idOf(2), otherShow) to false,
            episode(idOf(3), null) to false,
            movie(show) to false,
            movie(idOf(4)) to false,
        )
    }

    @Test
    fun collectionMemberKinds() {
        val box = idOf(0x30)
        val snap =
            snapshot(
                collections =
                    listOf(
                        collection(
                            box,
                            movies = setOf(idOf(1)),
                            episodes = setOf(idOf(2), idOf(9)),
                            series = setOf(show),
                            seasons = setOf(season),
                        ),
                    ),
            )
        check(
            Source.Collection(box),
            snap,
            movie(idOf(1)) to true,
            episode(idOf(2), otherShow) to true,
            episode(idOf(3), show) to true,
            episode(idOf(4), otherShow, seasonId = season) to true,
            episode(idOf(5), otherShow, seasonId = idOf(0x21)) to false,
            episode(idOf(6), otherShow) to false,
            episode(idOf(7), null, seasonId = season) to true,
            episode(idOf(8), null) to false,
            // A movie whose ID is listed under episodes does not match: the kinds differ.
            movie(idOf(9)) to false,
            movie(idOf(10)) to false,
        )
    }

    @Test
    fun anEpisodeIdListedAsAMovieMemberDoesNotMatch() {
        val box = idOf(0x30)
        val snap = snapshot(collections = listOf(collection(box, movies = setOf(idOf(1)))))
        check(Source.Collection(box), snap, episode(idOf(1), show) to false)
    }

    @Test
    fun aCollectionAbsentFromTheSnapshotMatchesNothing() {
        val snap = snapshot()
        check(
            Source.Collection(idOf(0x30)),
            snap,
            movie(idOf(1)) to false,
            episode(idOf(2), show, seasonId = season) to false,
        )
    }

    @Test
    fun anEpisodeWithNoSeasonDoesNotMatchASeasonMember() {
        val box = idOf(0x30)
        val snap = snapshot(collections = listOf(collection(box, seasons = setOf(season))))
        check(Source.Collection(box), snap, episode(idOf(1), show, seasonId = null) to false)
    }

    @Test
    fun aSeasonsOwnGenresAndTagsAreNeverUsed() {
        // The snapshot holds no season record, so an episode's genres are only its own and its series'.
        val snap = snapshot(series = listOf(series(show, genres = listOf("Drama"))))
        check(
            Source.Genre("Comedy"),
            snap,
            episode(idOf(1), show, seasonId = season) to false,
        )
    }

    @Test
    fun genre() {
        val snap =
            snapshot(
                series =
                    listOf(
                        series(show, genres = listOf("Comedy")),
                        series(otherShow, genres = listOf("Drama")),
                    ),
            )
        check(
            Source.Genre("Comedy"),
            snap,
            movie(idOf(1), genres = listOf("Comedy")) to true,
            movie(idOf(2), genres = listOf("Drama", "COMEDY")) to true,
            movie(idOf(3), genres = listOf("Comedy ")) to false,
            movie(idOf(4)) to false,
            episode(idOf(5), otherShow, genres = listOf("Comedy")) to true,
            // Inherited from the series.
            episode(idOf(6), show) to true,
            episode(idOf(7), otherShow) to false,
            // A series name is never used for a movie, and a movie has no series.
            movie(idOf(8)) to false,
            // A series absent from the snapshot: own names only.
            episode(idOf(9), idOf(0x99)) to false,
            episode(idOf(10), idOf(0x99), genres = listOf("comedy")) to true,
            episode(idOf(11), null) to false,
        )
    }

    @Test
    fun tag() {
        val snap =
            snapshot(
                series =
                    listOf(
                        series(show, tags = listOf("Classic")),
                        series(otherShow, genres = listOf("Classic")),
                    ),
            )
        check(
            Source.Tag("classic"),
            snap,
            movie(idOf(1), tags = listOf("CLASSIC")) to true,
            movie(idOf(2), genres = listOf("Classic")) to false,
            episode(idOf(3), show) to true,
            episode(idOf(4), otherShow) to false,
            episode(idOf(5), idOf(0x99)) to false,
            episode(idOf(6), idOf(0x99), tags = listOf("Classic")) to true,
            episode(idOf(7), show, tags = listOf("Other")) to true,
        )
    }

    @Test
    fun yearRange() {
        val snap = snapshot(series = listOf(series(show)))
        check(
            Source.YearRange(1990, 1999),
            snap,
            movie(idOf(1), year = 1990) to true,
            movie(idOf(2), year = 1999) to true,
            movie(idOf(3), year = 1989) to false,
            movie(idOf(4), year = 2000) to false,
            movie(idOf(5), year = 1995) to true,
            movie(idOf(6), year = null) to false,
            episode(idOf(7), show, year = 1995) to true,
            episode(idOf(8), show, year = null) to false,
        )
    }

    @Test
    fun aSingleYearRangeIncludesThatYear() {
        check(
            Source.YearRange(2001, 2001),
            snapshot(),
            movie(idOf(1), year = 2001) to true,
            movie(idOf(2), year = 2000) to false,
            movie(idOf(3), year = 2002) to false,
        )
    }

    @Test
    fun tallyCountsEachSourceOncePerItemAndReturnsWhetherAnyMatched() {
        val snap = snapshot(series = listOf(series(show, genres = listOf("Comedy"))))
        val sources =
            listOf(
                Source.Genre("comedy"),
                Source.Library(LIB),
                Source.Series(otherShow),
                Source.Genre("COMEDY"),
            )
        val matcher = SourceMatcher(sources, snap)
        val hits = IntArray(sources.size)
        // Own genre listed twice and the series' genre too: still one hit for each genre source.
        val both = episode(idOf(1), show, genres = listOf("Comedy", "comedy"))
        assertEquals(true, matcher.tally(both, snap.series[show], hits))
        assertEquals(listOf(1, 1, 0, 1), hits.toList())
        val none = movie(idOf(2), library = otherLib)
        assertEquals(false, matcher.tally(none, null, hits))
        assertEquals(listOf(1, 1, 0, 1), hits.toList())
        assertEquals(true, matcher.tally(episode(idOf(3), otherShow, library = otherLib), null, hits))
        assertEquals(listOf(1, 1, 1, 1), hits.toList())
    }

    @Test
    fun twoSourcesOnTheSameCollectionAreCountedSeparately() {
        val box = idOf(0x30)
        val snap = snapshot(collections = listOf(collection(box, movies = setOf(idOf(1)))))
        val matcher = SourceMatcher(listOf(Source.Collection(box), Source.Collection(box)), snap)
        val hits = IntArray(2)
        assertEquals(true, matcher.tally(movie(idOf(1)), null, hits))
        assertEquals(listOf(1, 1), hits.toList())
    }
}
