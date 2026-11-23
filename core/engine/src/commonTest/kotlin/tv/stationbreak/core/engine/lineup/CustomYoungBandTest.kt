// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.LIB
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1108, `grown-up-hold-9`: a custom channel under a PG or TV-14 ceiling is numbered in the Kids (34-66) or Teens
 * (67-99) band by `ChannelFloors`, so its pool follows that band's tier limit; with no ceiling, or a higher one, it is
 * unchanged. The rules follow the ceiling, never the number it happens to hold.
 */
class CustomYoungBandTest {
    private val plain = movie(idOf(1), rating = "PG")
    private val pgHorror = movie(idOf(2), rating = "PG", genres = listOf("Horror"))
    private val pgGore = movie(idOf(3), rating = "PG", tags = listOf("gore"))
    private val approved = movie(idOf(4), rating = "Approved")
    private val unrated = movie(idOf(5), rating = null)
    private val teenHorror = movie(idOf(6), rating = "TV-14", genres = listOf("Horror"))
    private val teenAdultTag = movie(idOf(7), rating = "PG-13", tags = listOf("nudity"))
    private val teenExplicitTag = movie(idOf(8), rating = "TV-14", tags = listOf("softcore"))
    private val kidsFilm = movie(idOf(9), rating = "G", genres = listOf("Animation", "Family"))
    private val rated = movie(idOf(10), rating = "R")
    private val everything =
        listOf(plain, pgHorror, pgGore, approved, unrated, teenHorror, teenAdultTag, teenExplicitTag)

    private fun custom(
        number: Int,
        ceiling: String?,
    ) = LineupChannel(
        ChannelId("custom:$number"),
        null,
        number,
        null,
        ChannelState.On,
        ChannelSource.Custom(rules(listOf(Source.Library(LIB)), maxRating = ceiling)),
        5L,
    )

    // The pool a custom channel airs: its own ceiling and the policy the day build gives it.
    private fun airs(
        channel: LineupChannel,
        items: List<RuleItem> = everything + kidsFilm + rated,
    ): Set<Long> {
        val own = (channel.source as ChannelSource.Custom).rules
        val ruled: ChannelRules = own.copy(maxRating = ChannelCeilings.of(channel, StarterCatalog))
        val pool = PoolEvaluator.evaluate(ruled, snapshot(items = items), KidStatus.basePolicyOf(channel))
        return pool.items.mapTo(HashSet()) { item -> items.indexOfFirst { it.item.id == item.id } + 1L }
    }

    @Test
    fun aCustomPgChannelAirsNeitherHorrorGoreNorUnratedTitles() {
        val aired = airs(custom(40, "PG"))
        assertEquals(setOf(1L, 9L), aired, "the plain PG title and the kids-tier one, nothing else")
    }

    @Test
    fun aCustomTv14ChannelKeepsTeenTitlesAndDropsAdultAndExplicitOnes() {
        val aired = airs(custom(70, "TV-14"))
        assertEquals(setOf(1L, 2L, 3L, 6L, 9L), aired, "family and teen tiers; not Approved, unrated or adult")
    }

    @Test
    fun anUnratedOrApprovedTitleIsOutUnderAnyCeilingAndAnAdultCustomIsUnchanged() {
        for (ceiling in listOf("PG", "TV-14", "R")) {
            val aired = airs(custom(700, ceiling))
            assertTrue(4L !in aired && 5L !in aired, "no Approved or unrated title under $ceiling")
        }
        val adult = airs(custom(700, "R"))
        assertEquals(setOf(1L, 2L, 3L, 6L, 7L, 8L, 9L, 10L), adult, "an R ceiling has no tier limit")
        val open = airs(custom(700, null))
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), open)
    }

    // A custom TV-14 channel at 40 is below its floor (67) until the migration moves it; it airs the same either way.
    @Test
    fun theRulesFollowTheCeilingNotTheNumber() {
        for (ceiling in listOf("PG", "TV-14")) {
            val expected = airs(custom(700, ceiling))
            for (number in listOf(34, 40, 66, 67, 99, 150)) {
                assertEquals(expected, airs(custom(number, ceiling)), "$ceiling at $number")
            }
        }
    }

    @Test
    fun aKidChannelByNumberStaysKidsOnly() {
        assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(custom(20, "PG")))
        assertEquals(PoolPolicy.Custom, KidStatus.basePolicyOf(custom(40, "PG")))
    }
}
