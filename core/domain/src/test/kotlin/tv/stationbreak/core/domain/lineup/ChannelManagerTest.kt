// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The port features use to change a lineup (spec 21). */
class ChannelManagerTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private var chosen: Set<ItemId>? = LIBRARIES
    private val manager = ChannelManager(service, { chosen }, clock)

    private fun lineup(
        state: ChannelState = ChannelState.On,
        vararg extra: LineupChannel,
    ): Lineup =
        lineupOf(
            starter("laughs", 110, state, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, state, rules("Whodunit", Source.Genre("Crime"))),
            *extra,
        )

    private suspend fun seed(lineup: Lineup) {
        store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
    }

    @Test
    fun numberTakenNamesTheHolder() =
        runBlocking<Unit> {
            seed(lineup())

            val result = manager.change(ACCOUNT, ChannelChange.SetNumber(LineupIds.starter("laughs"), 210))

            assertEquals(ChangeResult.NumberTaken(LineupIds.starter("whodunit"), "Whodunit"), result)
        }

    @Test
    fun aChangeIsDoneOrNotEditable() =
        runBlocking<Unit> {
            val laughs = ChannelChange.SwitchOff(LineupIds.starter("laughs"))
            val whodunit = ChannelChange.SwitchOff(LineupIds.starter("whodunit"))
            assertEquals(ChangeResult.NotEditable, manager.change(ACCOUNT, laughs))
            seed(lineup())
            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, laughs))
            assertEquals(ChangeResult.LastOn, manager.change(ACCOUNT, whodunit))
            assertTrue(manager.editable(ACCOUNT).first())
        }

    @Test
    fun resetReportsChosenWhenAChannelIsOn() =
        runBlocking<Unit> {
            seed(lineup(ChannelState.UserHidden))

            assertEquals(ResetResult.Chosen, manager.reset(ACCOUNT))
        }

    @Test
    fun resetReportsNotChosenYetWithoutOne() =
        runBlocking<Unit> {
            seed(lineup())
            chosen = null

            assertEquals(ResetResult.NotChosenYet, manager.reset(ACCOUNT))
            assertFalse(checkNotNull(service.lineup(ACCOUNT).value).lineup.finalised)
        }

    @Test
    fun resetReportsNotEditableForANewerAppsFile() =
        runBlocking<Unit> {
            val newer = """{"version":2}""".encodeToByteArray()
            store.inner.save(ACCOUNT, newer)

            assertEquals(ResetResult.NotEditable, manager.reset(ACCOUNT))
            assertFalse(manager.editable(ACCOUNT).first())
        }

    @Test
    fun summariesMarkKidAndOptInChannels() =
        runBlocking<Unit> {
            seed(
                lineup(
                    ChannelState.On,
                    starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"))),
                    starter("after-hours", 590, ChannelState.Shelf, rules("After Hours", Source.Tag("late"))),
                ),
            )
            val byId = service.channels(ACCOUNT).first().associateBy { it.id }

            val toons = checkNotNull(byId[LineupIds.starter("toons")])
            val after = checkNotNull(byId[LineupIds.starter("after-hours")])
            val laughs = checkNotNull(byId[LineupIds.starter("laughs")])
            assertTrue(toons.kid && !toons.optIn)
            assertTrue(after.optIn && !after.kid)
            assertTrue(!laughs.kid && !laughs.optIn)
            assertEquals(110, laughs.defaultNumber)
        }
}
