// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelFloors
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1097, #1108: the floor rule of the gate, read by [FloorOracle] and not by `ChannelFloors`. Each band's floor is
 * broken on purpose in the real everything-on view ([Leaks]), and in the view of the custom case, whose channels the
 * real planner has moved up.
 */
class GateFloorMutationTest {
    private val leaks = Leaks()
    private val view = leaks.view
    private val customCase = GateTestSupport.case("edge-cases-custom")
    private val customPlan = GateTestSupport.everythingOnPlan(customCase)
    private val customView = GateTestSupport.everythingOn(customCase)

    private fun GateChannel.numbered(number: Int) = copy(number = number)

    private fun swapped(
        from: HouseholdView,
        key: String,
        change: (GateChannel) -> GateChannel,
    ) = HouseholdView(from.snapshot, from.channels.map { if (it.key == key) change(it) else it }, from.curated)

    private fun floorBroken(view: HouseholdView) = Rule.BelowFloor in leaks.broken(view, customCase)

    @Test
    fun aChannelBelowItsFloorFails() {
        assertTrue(Rule.BelowFloor !in leaks.broken(view), "the real view keeps every channel at or above its floor")
        for (floor in listOf(34, 67, 100)) {
            val key = view.channels.first { FloorOracle.of(it) == floor }.key
            val moved = view.channels.map { if (it.key == key) it.numbered(floor - 1) else it }
            assertTrue(Rule.BelowFloor in leaks.broken(leaks.withChannels(moved)), "$floor, $key")
        }
    }

    @Test
    fun theRealPlannerMovesTheCustomChannelsAboveTheirFloorsAndTheGateAcceptsIt() {
        assertTrue(!floorBroken(customView), "no channel below its floor")
        val own = customView.channels.filter { it.key.startsWith("custom:") }
        assertEquals(4, own.size)
        val byFloor = own.associate { FloorOracle.of(it) to it.number!! }
        assertTrue(byFloor.getValue(100) >= 100 && byFloor.getValue(67) >= 67 && byFloor.getValue(34) >= 34, "$byFloor")
        assertTrue(own.any { it.policy == PoolPolicy.KidsOnly && it.number == 20 }, "a PG channel below 34 is a Tots one")
    }

    // A weak `ChannelFloors` is one the planner's migration and edits trust: a custom channel is left below its floor,
    // and the floor a gate reading `ChannelFloors` compares it with is as low as the channel. The oracle does not read
    // `ChannelFloors`, so it fails the channel; the agreement test below holds the two to one number.
    @Test
    fun aCustomChannelLeftBelowItsFloorFailsWhateverChannelFloorsSays() {
        val own = customView.channels.filter { it.key.startsWith("custom:") && it.policy == PoolPolicy.Custom }
        for (channel in own) {
            val floor = FloorOracle.of(channel)
            val left = swapped(customView, channel.key) { it.numbered(floor - 1) }
            assertTrue(floorBroken(left), "${channel.key} left at ${floor - 1}, floor $floor")
        }
    }

    @Test
    fun theOracleReadsTheCeilingAndTheKindOfChannel() {
        fun channel(
            ceiling: String?,
            policy: PoolPolicy,
        ) = GateChannel("k", ChannelId("custom:1"), 1, false, ceiling, policy, emptyList(), emptySet())
        assertEquals(1, FloorOracle.of(channel("PG", PoolPolicy.KidsOnly)))
        assertEquals(34, FloorOracle.of(channel("PG", PoolPolicy.Custom)))
        assertEquals(34, FloorOracle.of(channel("TV-PG", PoolPolicy.GrownUp)))
        assertEquals(34, FloorOracle.of(channel("G", PoolPolicy.Custom)))
        assertEquals(67, FloorOracle.of(channel("PG-13", PoolPolicy.Custom)))
        assertEquals(67, FloorOracle.of(channel("TV-14", PoolPolicy.Custom)))
        assertEquals(100, FloorOracle.of(channel("R", PoolPolicy.Custom)))
        assertEquals(100, FloorOracle.of(channel(null, PoolPolicy.Custom)))
        assertEquals(100, FloorOracle.of(channel(null, PoolPolicy.ExplicitOnly)))
    }

    // Every channel of every case: the oracle never asks more than `ChannelFloors` (it reads the ceiling, which a
    // built-in's recipe band may exceed), and for a viewer's own channel the two are the same number.
    @Test
    fun theOracleAgreesWithChannelFloorsOnTheViewersOwnChannels() {
        for (planned in listOf(customPlan)) {
            for (channel in customView.channels.filter { it.key.startsWith("custom:") }) {
                val source: LineupChannel = planned.lineup.channels.first { it.id == channel.id }
                assertTrue(source.source is ChannelSource.Custom)
                assertEquals(ChannelFloors.of(source, planned.catalog), FloorOracle.of(channel), channel.key)
            }
        }
    }
}
