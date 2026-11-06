// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which pool policy a recipe or channel gets (ADR-0019, ADR-0020). */
class KidStatusTest {
    private val kidKey = StarterCatalog.EVER_KID_SAFE_KEYS.first()

    private fun channel(
        number: Int?,
        source: ChannelSource,
    ) = LineupChannel(ChannelId("c-$number"), number, null, null, ChannelState.On, source, 0L)

    private fun starter(
        key: String,
        number: Int,
        edited: Boolean = false,
    ) = channel(number, ChannelSource.Starter(key, edited, rulesOf(key)))

    private fun rulesOf(key: String) = RecipeResolver.resolve(recipeOf(key, 110), SERVER, 0, NO_NAMES, NO_LIBRARY)

    @Test
    fun aKidRecipeIsKidsOnlyAndAnyOtherRecipeIsGrownUp() {
        assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(recipeOf("tots", 10)))
        assertEquals(PoolPolicy.GrownUp, KidStatus.policyOf(recipeOf("alpha", 110)))
    }

    @Test
    fun aKidStarterIsKidsOnly() {
        assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(starter("tots", 10)))
        assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(starter(kidKey, 150)))
    }

    @Test
    fun aGrownUpStarterIsGrownUpEditedOrNot() {
        assertEquals(PoolPolicy.GrownUp, KidStatus.basePolicyOf(starter("alpha", 110)))
        assertEquals(PoolPolicy.GrownUp, KidStatus.basePolicyOf(starter("alpha", 110, edited = true)))
    }

    @Test
    fun aCustomChannelIsOpenOutsideTheKidsBandAndKidsOnlyInsideIt() {
        val rules = rulesOf("alpha")
        assertEquals(PoolPolicy.Open, KidStatus.basePolicyOf(channel(150, ChannelSource.Custom(rules))))
        assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(channel(20, ChannelSource.Custom(rules))))
    }

    @Test
    fun theKidsLeftoversIsKidsOnlyAndTheGrownUpLeftoversIsGrownUp() {
        val kids = channel(99, ChannelSource.Leftovers("kids-odds-and-ends", "PG"))
        val odds = channel(199, ChannelSource.Leftovers("odds-and-ends", null))
        assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(kids))
        assertEquals(PoolPolicy.GrownUp, KidStatus.basePolicyOf(odds))
    }

    @Test
    fun theGrownUpLeftoversIsOpenUnlessAKidLeftoversIsOnInTheLineup() {
        fun lineupOf(vararg channels: LineupChannel) = Lineup(SERVER, USER, 1, 0, true, channels.toList())
        val kids = channel(99, ChannelSource.Leftovers("kids-odds-and-ends", "PG"))
        val odds = channel(199, ChannelSource.Leftovers("odds-and-ends", null))
        val alpha = starter("alpha", 110)
        assertEquals(PoolPolicy.GrownUp, KidStatus.policyOf(odds, lineupOf(kids, odds)))
        assertEquals(PoolPolicy.Open, KidStatus.policyOf(odds, lineupOf(odds)))
        for (state in listOf(ChannelState.Shelf, ChannelState.UserHidden)) {
            assertEquals(PoolPolicy.Open, KidStatus.policyOf(odds, lineupOf(kids.copy(state = state), odds)))
        }
        // Everything else is as without a lineup.
        assertEquals(PoolPolicy.GrownUp, KidStatus.policyOf(alpha, lineupOf(alpha, odds)))
        assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(kids, lineupOf(kids, odds)))
    }

    @Test
    fun aKidLeftoversThatStartsAfterTheDayIsOffForThatDaysPolicy() {
        fun lineupOf(vararg channels: LineupChannel) = Lineup(SERVER, USER, 1, 0, true, channels.toList())
        val today = LineupDay(0L, 1_000L)
        val kids = channel(99, ChannelSource.Leftovers("kids-odds-and-ends", "PG"))
        val odds = channel(199, ChannelSource.Leftovers("odds-and-ends", null))
        val starting = kids.copy(onSinceMs = today.endMs)
        // Starting later: off today (open), on from its boundary; started: on.
        assertEquals(PoolPolicy.Open, KidStatus.policyOf(odds, lineupOf(starting, odds), today))
        val tomorrow = LineupDay.startingAt(today.endMs)
        assertEquals(PoolPolicy.GrownUp, KidStatus.policyOf(odds, lineupOf(starting, odds), tomorrow))
        assertEquals(PoolPolicy.GrownUp, KidStatus.policyOf(odds, lineupOf(kids, odds), today))
        // The channel's own and a starter's policy do not depend on the day; the day-less one still counts it as on.
        assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(starting, lineupOf(starting, odds), today))
        assertEquals(PoolPolicy.GrownUp, KidStatus.policyOf(odds, lineupOf(starting, odds)))
    }

    @Test
    fun aCustomChannelStaysOpen() {
        assertEquals(PoolPolicy.Open, KidStatus.basePolicyOf(channel(150, ChannelSource.Custom(rulesOf("alpha")))))
    }
}
