// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.model.ChannelId

/** The first channel when nothing is saved (#375): PG-tier ceiling, then any ceiling, then the lowest. */
class LiveChannelFirstChannelTest {
    // Ladder tiers: TV-Y is 0; PG and TV-PG share ChannelAiring.PG_TIER (that spelling is tested in the domain).
    private val tvY = 0
    private val pg = ChannelAiring.PG_TIER
    private val tv14 = ChannelAiring.PG_TIER + 2

    /** A channel with one program (item [item]) on now, under the ceiling tier [ceiling]. */
    private fun Rig.channelWith(
        id: ChannelId,
        number: Int,
        item: Int,
        ceiling: Int?,
    ) {
        schedule.channel(id, number, listOf(program(0, 60, item))).ceilingRank = ceiling
    }

    @Test
    fun aPgCeilingChannelBeatsALowerNumberedTvYOne() =
        runTest {
            val r = Rig(this)
            r.channelWith(CH5, 5, 1, tvY)
            r.channelWith(CH7, 7, 2, pg)
            r.channelWith(CH9, 9, 3, null)
            r.start()
            assertEquals(itemOf(2), r.tunes.first().item)
        }

    @Test
    fun theLowestPgCeilingChannelWins() =
        runTest {
            val r = Rig(this)
            r.channelWith(CH5, 5, 1, tvY)
            r.channelWith(CH7, 7, 2, pg)
            r.channelWith(CH9, 9, 3, pg)
            r.start()
            assertEquals(itemOf(2), r.tunes.first().item)
        }

    @Test
    fun withNoPgCeilingTheLowestChannelBelowPgIsNext() =
        runTest {
            val r = Rig(this)
            r.channelWith(CH5, 5, 1, null)
            r.channelWith(CH7, 7, 2, tv14)
            r.channelWith(CH9, 9, 3, tvY)
            r.start()
            assertEquals(itemOf(3), r.tunes.first().item)
        }

    @Test
    fun aCeilingAbovePgDoesNotJumpAheadOfALowerNumberedChannelWithNone() =
        runTest {
            val r = Rig(this)
            r.channelWith(CH5, 5, 1, null)
            r.channelWith(CH7, 7, 2, tv14)
            r.start()
            assertEquals(itemOf(1), r.tunes.first().item)
        }

    @Test
    fun aCeilingAbovePgAndNoneFallInNumberOrder() =
        runTest {
            val r = Rig(this)
            r.channelWith(CH5, 5, 1, tv14)
            r.channelWith(CH7, 7, 2, null)
            r.start()
            assertEquals(itemOf(1), r.tunes.first().item)
        }

    @Test
    fun withNoCeilingAtAllTheLowestChannelIsUsed() =
        runTest {
            val r = Rig(this)
            r.channelWith(CH5, 5, 1, null)
            r.channelWith(CH7, 7, 2, null)
            r.start()
            assertEquals(itemOf(1), r.tunes.first().item)
        }

    @Test
    fun aGroupWithNothingOnTheAirIsSkipped() =
        runTest {
            val r = Rig(this)
            // PG tier, but nothing on now; PG tier, but hidden; then the any-ceiling group answers.
            r.schedule.channel(CH5, 5, emptyList()).ceilingRank = pg
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 2)), hidden = HiddenReason.EmptyToday).ceilingRank = pg
            r.channelWith(CH9, 9, 3, tvY)
            r.start()
            assertEquals(itemOf(3), r.tunes.first().item)
        }

    @Test
    fun aSavedChannelStillBeatsAPgCeilingChannel() =
        runTest {
            val r = Rig(this)
            r.channelWith(CH5, 5, 1, tvY)
            r.channelWith(CH7, 7, 2, pg)
            r.lastChannel.save(r.account, CH5)
            r.start()
            assertEquals(itemOf(1), r.tunes.first().item)
        }

    @Test
    fun aSavedChannelThatIsNotOnTheAirFallsBackToThePgRule() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH5, 5, emptyList()).ceilingRank = tvY
            r.channelWith(CH7, 7, 2, tvY)
            r.channelWith(CH9, 9, 3, pg)
            r.lastChannel.save(r.account, CH5)
            r.start()
            assertEquals(itemOf(3), r.tunes.first().item)
        }
}
