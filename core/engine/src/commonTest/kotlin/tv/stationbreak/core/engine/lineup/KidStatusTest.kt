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

    private fun rulesOf(key: String) = RecipeResolver.resolve(recipeOf(key, 110), SERVER, 0, NO_NAMES)

    @Test
    fun aKidRecipeIsKidsOnlyAndAnyOtherRecipeIsGrownUp() {
        assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(recipeOf("tots", 10)))
        assertEquals(PoolPolicy.GrownUp, KidStatus.policyOf(recipeOf("alpha", 110)))
    }

    @Test
    fun aKidStarterIsKidsOnly() {
        assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(starter("tots", 10)))
        assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(starter(kidKey, 150)))
    }

    @Test
    fun aGrownUpStarterIsGrownUpEditedOrNot() {
        assertEquals(PoolPolicy.GrownUp, KidStatus.policyOf(starter("alpha", 110)))
        assertEquals(PoolPolicy.GrownUp, KidStatus.policyOf(starter("alpha", 110, edited = true)))
    }

    @Test
    fun aCustomChannelIsOpenOutsideTheKidsBandAndKidsOnlyInsideIt() {
        val rules = rulesOf("alpha")
        assertEquals(PoolPolicy.Open, KidStatus.policyOf(channel(150, ChannelSource.Custom(rules))))
        assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(channel(20, ChannelSource.Custom(rules))))
    }

    @Test
    fun theKidsLeftoversIsKidsOnlyAndTheGrownUpLeftoversIsOpen() {
        val kids = channel(99, ChannelSource.Leftovers("kids-odds-and-ends", "PG"))
        val odds = channel(199, ChannelSource.Leftovers("odds-and-ends", null))
        assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(kids))
        assertEquals(PoolPolicy.Open, KidStatus.policyOf(odds))
    }
}
