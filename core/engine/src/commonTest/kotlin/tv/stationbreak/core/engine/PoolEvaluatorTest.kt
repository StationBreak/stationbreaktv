// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/rules-format.md, "Items and the pool", rules 3 and 4, and the counts. */
class PoolEvaluatorTest {
    private val show = idOf(0x10)
    private val box = idOf(0x30)
    private val otherLib = idOf(0xB0)

    private fun eval(
        rules: ChannelRules,
        snap: RuleSnapshot,
    ) = PoolEvaluator.evaluate(rules, snap)

    private fun one(
        entry: RuleItem,
        vararg known: RuleSeries,
    ) = snapshot(items = listOf(entry), series = known.toList())

    private fun poolIds(
        rules: ChannelRules,
        snap: RuleSnapshot,
    ) = eval(rules, snap).items.map { it.id }

    private fun libraryRules(
        exclusions: List<Source>? = null,
        maxRating: String? = null,
    ) = rules(listOf(Source.Library(LIB)), exclusions, maxRating)

    // Rule 3: union, then exclusions.

    @Test
    fun anItemMatchingSeveralSourcesIsInThePoolOnceAndCountedForEach() {
        val a = movie(idOf(1), genres = listOf("Comedy"), year = 1995)
        val b = movie(idOf(2), genres = listOf("Drama"), year = 1995)
        val c = movie(idOf(3), library = otherLib, genres = listOf("Comedy"))
        val d = movie(idOf(4), library = otherLib, genres = listOf("Action"))
        val sources =
            listOf(Source.Library(LIB), Source.Genre("comedy"), Source.YearRange(1990, 1999), Source.Tag("none"))
        val pool = eval(rules(sources), snapshot(items = listOf(a, b, c, d), libraries = setOf(LIB, otherLib)))
        assertEquals(listOf(a.item, b.item, c.item), pool.items)
        assertEquals(listOf(2, 2, 2, 0), pool.counts.matchedBySource)
        assertEquals(0, pool.counts.excluded)
    }

    @Test
    fun theSamePoolFromTwoSourcesThatSelectTheSameThing() {
        val a = movie(idOf(1))
        val pool = eval(rules(listOf(Source.Library(LIB), Source.Library(LIB))), snapshot(items = listOf(a)))
        assertEquals(listOf(a.item), pool.items)
        assertEquals(listOf(1, 1), pool.counts.matchedBySource)
    }

    @Test
    fun anExclusionOfEachKindRemovesItsItems() {
        val lib2 = otherLib
        val items =
            listOf(
                movie(idOf(1)),
                movie(idOf(2), library = lib2),
                movie(idOf(3), genres = listOf("Horror")),
                movie(idOf(4), tags = listOf("Cut")),
                movie(idOf(5), year = 1985),
                movie(idOf(6)),
                episode(idOf(7), show),
                episode(idOf(8), idOf(0x11)),
            )
        val snap =
            snapshot(
                items = items,
                series = listOf(series(show), series(idOf(0x11))),
                collections = listOf(collection(box, movies = setOf(idOf(6)))),
                libraries = setOf(LIB, lib2),
            )
        val sources = listOf(Source.Library(LIB), Source.Library(lib2))
        val cases =
            listOf<Pair<Source, List<Int>>>(
                Source.Library(lib2) to listOf(2),
                Source.Genre("HORROR") to listOf(3),
                Source.Tag("cut") to listOf(4),
                Source.YearRange(1980, 1989) to listOf(5),
                Source.Collection(box) to listOf(6),
                Source.Series(show) to listOf(7),
            )
        for ((exclusion, removed) in cases) {
            val pool = eval(rules(sources, listOf(exclusion)), snap)
            val expected = items.filter { entry -> entry.item.id !in removed.map { idOf(it) } }.map { it.item }
            assertEquals(expected, pool.items, "exclusion $exclusion")
            assertEquals(removed.size, pool.counts.excluded, "excluded count for $exclusion")
        }
    }

    @Test
    fun aLibraryThatIsBothSourceAndExclusionLeavesAnEmptyPool() {
        val items = listOf(movie(idOf(1)), movie(idOf(2)), episode(idOf(3), show))
        val snap = snapshot(items = items, series = listOf(series(show)))
        val pool = eval(libraryRules(exclusions = listOf(Source.Library(LIB))), snap)
        assertEquals(emptyList(), pool.items)
        assertEquals(listOf(3), pool.counts.matchedBySource)
        assertEquals(3, pool.counts.excluded)
        assertEquals(0, pool.counts.blockedByCeiling)
    }

    @Test
    fun anItemThatMatchesNoSourceIsNotCountedAsExcluded() {
        val horror = movie(idOf(1), library = otherLib, genres = listOf("Horror"))
        val snap = snapshot(items = listOf(horror), libraries = setOf(LIB, otherLib))
        val pool = eval(libraryRules(exclusions = listOf(Source.Genre("Horror"))), snap)
        assertEquals(emptyList(), pool.items)
        assertEquals(0, pool.counts.excluded)
    }

    @Test
    fun noExclusionsMeansNothingIsExcluded() {
        val snap = snapshot(items = listOf(movie(idOf(1))))
        assertEquals(1, eval(libraryRules(exclusions = null), snap).items.size)
        assertEquals(1, eval(libraryRules(exclusions = emptyList()), snap).items.size)
    }

    // Rule 4: the ceiling.

    @Test
    fun aMovieUnderACeilingNeedsItsOwnRating() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = "G"),
                        movie(idOf(2), rating = "PG"),
                        movie(idOf(3), rating = "PG-13"),
                        movie(idOf(4), rating = null),
                        movie(idOf(5), rating = ""),
                        movie(idOf(6), rating = "NR"),
                    ),
            )
        val pool = eval(libraryRules(maxRating = "PG"), snap)
        assertEquals(listOf(idOf(1), idOf(2)), pool.items.map { it.id })
        assertEquals(4, pool.counts.blockedByCeiling)
    }

    @Test
    fun withNoCeilingEveryRatingPasses() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = null),
                        movie(idOf(2), rating = "NC-17"),
                        movie(idOf(3), rating = "junk"),
                    ),
            )
        val pool = eval(libraryRules(), snap)
        assertEquals(3, pool.items.size)
        assertEquals(0, pool.counts.blockedByCeiling)
    }

    @Test
    fun anEpisodeWithNoOwnRatingTakesItsSeriesRating() {
        val snap = one(episode(idOf(1), show, rating = null), series(show, rating = "PG"))
        assertEquals(listOf(idOf(1)), poolIds(libraryRules(maxRating = "PG"), snap))
        val snapEmpty = one(episode(idOf(1), show, rating = ""), series(show, rating = "PG"))
        assertEquals(listOf(idOf(1)), poolIds(libraryRules(maxRating = "PG"), snapEmpty))
    }

    @Test
    fun theStaleCaseIsBlockedWhenTheSeriesWasReRatedAboveTheCeiling() {
        // Own TV-Y7, series now TV-MA, ceiling TV-PG: the series fails (b).
        val snap = one(episode(idOf(1), show, rating = "TV-Y7"), series(show, rating = "TV-MA"))
        val pool = eval(libraryRules(maxRating = "TV-PG"), snap)
        assertEquals(emptyList(), pool.items)
        assertEquals(1, pool.counts.blockedByCeiling)
    }

    @Test
    fun anEpisodeRatedGWhoseSeriesIsRatedRIsOutUnderPG() {
        // The architecture guardian's case: the movie check on the episode's own rating would let this through.
        val snap = one(episode(idOf(1), show, rating = "G"), series(show, rating = "R"))
        val pool = eval(libraryRules(maxRating = "PG"), snap)
        assertEquals(emptyList(), pool.items)
        assertEquals(1, pool.counts.blockedByCeiling)
    }

    @Test
    fun theReverseStaleCaseIsAlsoBlocked() {
        // Series re-rated down to G, the episode keeps a higher copy: (a) uses the own rating and blocks it.
        val snap = one(episode(idOf(1), show, rating = "R"), series(show, rating = "G"))
        assertEquals(emptyList(), poolIds(libraryRules(maxRating = "PG"), snap))
    }

    @Test
    fun aSeriesRatedNotRatedBlocksItsEpisodes() {
        val snap =
            snapshot(
                items = listOf(episode(idOf(1), show, rating = "G"), episode(idOf(2), idOf(0x11), rating = "G")),
                series = listOf(series(show, rating = "NR"), series(idOf(0x11), rating = " ")),
            )
        assertEquals(emptyList(), poolIds(libraryRules(maxRating = "PG"), snap))
    }

    @Test
    fun aSeriesWithNoRatingLeavesTheEpisodesOwnRatingToDecide() {
        val snap =
            snapshot(
                items = listOf(episode(idOf(1), show, rating = "G"), episode(idOf(2), show, rating = null)),
                series = listOf(series(show, rating = null)),
            )
        assertEquals(listOf(idOf(1)), poolIds(libraryRules(maxRating = "PG"), snap))
    }

    @Test
    fun aLooseEpisodeIsCheckedOnItsOwnRatingAlone() {
        val snap =
            snapshot(
                items =
                    listOf(
                        episode(idOf(1), null, rating = "G"),
                        episode(idOf(2), null, rating = null),
                        episode(idOf(3), null, rating = "R"),
                    ),
            )
        val pool = eval(libraryRules(maxRating = "PG"), snap)
        assertEquals(listOf(idOf(1)), pool.items.map { it.id })
        assertEquals(2, pool.counts.blockedByCeiling)
        assertEquals(0, pool.counts.episodesWithUnknownSeries)
    }

    @Test
    fun anEpisodeWhoseSeriesIsNotInTheSnapshotIsBlockedUnderACeilingButAllowedWithNone() {
        val snap = snapshot(items = listOf(episode(idOf(1), show, rating = "G")))
        val blocked = eval(libraryRules(maxRating = "PG"), snap)
        assertEquals(emptyList(), blocked.items)
        assertEquals(1, blocked.counts.blockedByCeiling)
        assertEquals(1, blocked.counts.episodesWithUnknownSeries)
        val open = eval(libraryRules(), snap)
        assertEquals(listOf(idOf(1)), open.items.map { it.id })
        assertEquals(0, open.counts.blockedByCeiling)
        assertEquals(1, open.counts.episodesWithUnknownSeries)
    }

    @Test
    fun blockedByCeilingCountsOnlyItemsThatSurvivedTheExclusions() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = "R"),
                        movie(idOf(2), rating = "R", genres = listOf("Horror")),
                        movie(idOf(3), rating = "G", genres = listOf("Horror")),
                        movie(idOf(4), rating = "G"),
                    ),
            )
        val pool = eval(libraryRules(exclusions = listOf(Source.Genre("horror")), maxRating = "PG"), snap)
        assertEquals(listOf(idOf(4)), pool.items.map { it.id })
        assertEquals(2, pool.counts.excluded)
        assertEquals(1, pool.counts.blockedByCeiling)
    }

    // Tombstones, order and the other counts.

    @Test
    fun anItemWithADeletionTimeIsKept() {
        val gone = movie(idOf(1), deletedAtMs = 5_000L)
        val pool = eval(libraryRules(), snapshot(items = listOf(gone, movie(idOf(2)))))
        assertEquals(listOf(idOf(1), idOf(2)), pool.items.map { it.id })
        assertEquals(5_000L, pool.items.first().deletedAtMs)
    }

    @Test
    fun episodesWithUnknownSeriesCountsSurvivorsBeforeTheCeiling() {
        val ghost = idOf(0x99)
        val items =
            listOf<RuleItem>(
                episode(idOf(1), ghost, rating = "G"),
                // Matched and then excluded: not counted.
                episode(idOf(2), ghost, year = 1970),
                // Not matched by any source: not counted.
                episode(idOf(3), ghost, library = otherLib),
                // Series present: not counted.
                episode(idOf(4), show),
                // A loose episode has no series ID: not counted.
                episode(idOf(5), null),
                // Blocked by the ceiling but still counted.
                episode(idOf(6), ghost, rating = "R"),
                movie(idOf(7)),
            )
        val known = listOf(series(show, rating = "G"))
        val snap = snapshot(items = items, series = known, libraries = setOf(LIB, otherLib))
        val pool = eval(libraryRules(exclusions = listOf(Source.YearRange(1970, 1970)), maxRating = "PG"), snap)
        assertEquals(2, pool.counts.episodesWithUnknownSeries)
    }

    @Test
    fun unresolvedSourcesListsMissingCollectionsByIndexAndNeverLibraries() {
        val sources =
            listOf(
                Source.Library(LIB),
                Source.Library(otherLib),
                Source.Collection(box),
                Source.Collection(idOf(0x31)),
                Source.Genre("Comedy"),
                Source.Series(idOf(0x77)),
                Source.YearRange(1990, 1999),
            )
        val snap = snapshot(collections = listOf(collection(box)))
        val pool = eval(rules(sources), snap)
        // A library missing from RuleSnapshot.libraries may only not have been asked for, so it is not listed.
        assertEquals(listOf(3), pool.counts.unresolvedSources)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0), pool.counts.matchedBySource)
        assertEquals(emptyList(), pool.counts.unresolvedExclusions)
    }

    @Test
    fun unresolvedExclusionsListsMissingCollectionExclusionsByIndexIntoTheExclusions() {
        val exclusions =
            listOf(
                Source.Library(otherLib),
                Source.Collection(box),
                Source.Collection(idOf(0x31)),
                Source.Genre("x"),
                Source.Collection(idOf(0x32)),
            )
        val snap = snapshot(collections = listOf(collection(box)))
        val pool = eval(libraryRules(exclusions = exclusions), snap)
        assertEquals(listOf(2, 4), pool.counts.unresolvedExclusions)
        assertEquals(emptyList(), pool.counts.unresolvedSources)
    }

    // Fail-closed exclusions (rule 3 as amended): an exclusion that cannot be checked removes everything it might remove.

    @Test
    fun aCollectionExclusionMissingFromTheSnapshotEmptiesThePool() {
        val items =
            listOf(
                movie(idOf(1), rating = "G"),
                movie(idOf(2), rating = "R"),
                episode(idOf(3), idOf(0x99), rating = "G"),
                movie(idOf(4), library = otherLib),
            )
        val snap = snapshot(items = items, libraries = setOf(LIB, otherLib))
        val pool = eval(libraryRules(exclusions = listOf(Source.Collection(box)), maxRating = "PG"), snap)
        assertEquals(emptyList(), pool.items)
        // Every source-matched item is excluded; the unmatched one is not counted.
        assertEquals(3, pool.counts.excluded)
        assertEquals(0, pool.counts.blockedByCeiling)
        assertEquals(0, pool.counts.episodesWithUnknownSeries)
        assertEquals(listOf(3), pool.counts.matchedBySource)
        assertEquals(listOf(0), pool.counts.unresolvedExclusions)
    }

    @Test
    fun aMissingCollectionExclusionEmptiesThePoolEvenBesideAPresentOne() {
        val snap = snapshot(items = listOf(movie(idOf(1))), collections = listOf(collection(box)))
        val pool = eval(libraryRules(exclusions = listOf(Source.Collection(box), Source.Collection(idOf(0x31)))), snap)
        assertEquals(emptyList(), pool.items)
        assertEquals(1, pool.counts.excluded)
        assertEquals(listOf(1), pool.counts.unresolvedExclusions)
    }

    @Test
    fun aPresentCollectionExclusionStillOnlyRemovesItsMembers() {
        val snap =
            snapshot(
                items = listOf(movie(idOf(1)), movie(idOf(2))),
                collections = listOf(collection(box, movies = setOf(idOf(1)))),
            )
        val pool = eval(libraryRules(exclusions = listOf(Source.Collection(box))), snap)
        assertEquals(listOf(idOf(2)), pool.items.map { it.id })
        assertEquals(emptyList(), pool.counts.unresolvedExclusions)
    }

    @Test
    fun aGenreOrTagExclusionRemovesAnEpisodeWhoseSeriesIsMissingWhateverItsOwnNames() {
        val ghost = idOf(0x99)
        val items =
            listOf(
                episode(idOf(1), ghost),
                episode(idOf(2), ghost, genres = listOf("Drama"), tags = listOf("Other")),
                // A loose episode and a movie are checked on their own names only.
                episode(idOf(3), null),
                movie(idOf(4)),
                episode(idOf(5), show),
            )
        val snap = snapshot(items = items, series = listOf(series(show)))
        for (exclusion in listOf(Source.Genre("Comedy"), Source.Tag("Cut"))) {
            val pool = eval(libraryRules(exclusions = listOf(exclusion)), snap)
            assertEquals(listOf(idOf(3), idOf(4), idOf(5)), pool.items.map { it.id }, "$exclusion")
            assertEquals(2, pool.counts.excluded, "$exclusion")
            assertEquals(emptyList(), pool.counts.unresolvedExclusions)
        }
    }

    @Test
    fun aGenreSourceStillUsesOwnNamesWhenTheSeriesIsMissing() {
        val ghost = idOf(0x99)
        val snap = snapshot(items = listOf(episode(idOf(1), ghost), episode(idOf(2), ghost, genres = listOf("Comedy"))))
        val pool = PoolEvaluator.evaluate(rules(listOf(Source.Genre("comedy"))), snap)
        assertEquals(listOf(idOf(2)), pool.items.map { it.id })
    }

    @Test
    fun anEpisodeRatedTvY7WhoseSeriesIsTvMaIsOutUnderTvY7() {
        val snap = one(episode(idOf(1), show, rating = "TV-Y7"), series(show, rating = "TV-MA"))
        val pool = eval(libraryRules(maxRating = "TV-Y7"), snap)
        assertEquals(emptyList(), pool.items)
        assertEquals(1, pool.counts.blockedByCeiling)
    }

    @Test
    fun anEmptySnapshotWithAnyCeilingStillWorks() {
        // ChannelRules rejects a ceiling that is not on the ladder, so evaluate's own eager check cannot be reached
        // with a constructed value; the check is there for a rules value built some other way.
        assertEquals(emptyList(), eval(libraryRules(maxRating = "TV-MA"), snapshot()).items)
    }

    @Test
    fun collectionsSyncedIsPassedThrough() {
        assertTrue(eval(libraryRules(), snapshot(collectionsSynced = true)).counts.collectionsSynced)
        assertFalse(eval(libraryRules(), snapshot(collectionsSynced = false)).counts.collectionsSynced)
    }

    @Test
    fun anEmptySnapshotGivesAnEmptyPool() {
        val pool = eval(rules(listOf(Source.Library(LIB), Source.Genre("x"))), snapshot())
        assertEquals(emptyList(), pool.items)
        assertEquals(listOf(0, 0), pool.counts.matchedBySource)
        assertEquals(0, pool.counts.excluded)
    }

    @Test
    fun theOutputKeepsTheOrderOfTheSnapshot() {
        // The builder does not sort: this order is not the ID order, and the pool must not change it.
        val items = listOf(movie(idOf(9)), movie(idOf(3)), movie(idOf(7)), movie(idOf(1)))
        assertEquals(items.map { it.item }, eval(libraryRules(), snapshot(items = items)).items)
    }

    @Test
    fun aDuplicateIdFailsEvenWhenNeitherCopyWouldBeKept() {
        val unmatched = movie(idOf(5), library = otherLib)
        val failure =
            assertFailsWith<IllegalArgumentException> {
                eval(libraryRules(), snapshot(items = listOf(movie(idOf(1)), unmatched, unmatched.copy())))
            }
        assertTrue(failure.message.orEmpty().contains(idOf(5).value), failure.message)
    }

    @Test
    fun theSameInputsGiveTheSamePoolEveryTime() {
        val snap = snapshot(items = listOf(movie(idOf(2), genres = listOf("A")), movie(idOf(1))))
        val r = rules(listOf(Source.Genre("a"), Source.Library(LIB)))
        assertEquals(eval(r, snap), eval(r, snap))
    }
}
