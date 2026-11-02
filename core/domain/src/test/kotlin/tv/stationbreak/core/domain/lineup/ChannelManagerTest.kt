// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
    private val manager = ChannelManager(service) { chosen }

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
    fun numberTakenCarriesTheHolder() =
        runBlocking<Unit> {
            seed(lineup())

            val result = manager.change(ACCOUNT, ChannelChange.SetNumber(LineupIds.starter("laughs"), 210))

            assertEquals(ChangeResult.Refused(ChangeRefusal.NumberTaken(LineupIds.starter("whodunit"))), result)
        }

    @Test
    fun aChangeIsDoneOrNotEditable() =
        runBlocking<Unit> {
            val laughs = ChannelChange.SwitchOff(LineupIds.starter("laughs"))
            val whodunit = ChannelChange.SwitchOff(LineupIds.starter("whodunit"))
            assertEquals(ChangeResult.NotEditable, manager.change(ACCOUNT, laughs))
            seed(lineup())
            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, laughs))
            assertEquals(ChangeResult.Refused(ChangeRefusal.LastOn), manager.change(ACCOUNT, whodunit))
            assertEquals(LineupAccess.Editable, manager.access(ACCOUNT).first())
        }

    @Test
    fun aFailedSaveIsReported() =
        runBlocking<Unit> {
            seed(lineup())
            store.inner.failWrites = true

            val off = ChannelChange.SwitchOff(LineupIds.starter("laughs"))
            assertEquals(ChangeResult.SaveFailed, manager.change(ACCOUNT, off))
            assertEquals(ResetResult.SaveFailed, manager.reset(ACCOUNT))
        }

    @Test
    fun resetReportsChosenWhenAChannelIsOn() =
        runBlocking<Unit> {
            seed(lineup(ChannelState.UserHidden))

            assertEquals(ResetResult.Chosen, manager.reset(ACCOUNT))
        }

    @Test
    fun resetReportsNotChosenYetWithoutAChannelOn() =
        runBlocking<Unit> {
            seed(lineup())
            val bare = LineupService(store, CountingLibrary(), clock, { 0 }, {}, TestCatalog)

            assertEquals(ResetResult.NotChosenYet, ChannelManager(bare) { LIBRARIES }.reset(ACCOUNT))
        }

    @Test
    fun resetWritesNothingWhenTheLibrariesAreUnavailable() =
        runBlocking<Unit> {
            seed(lineup())
            val before = store.inner.peek(ACCOUNT)
            store.calls.clear()

            chosen = null
            assertEquals(ResetResult.LibrariesUnavailable, manager.reset(ACCOUNT))
            chosen = emptySet()
            assertEquals(ResetResult.LibrariesUnavailable, manager.reset(ACCOUNT))
            val failing = ChannelManager(service) { error("settings unreadable") }
            assertEquals(ResetResult.LibrariesUnavailable, failing.reset(ACCOUNT))

            assertTrue("save" !in store.calls, "calls were ${store.calls}")
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun resetReportsLibrariesUnavailableWhenTheLibraryReadFails() =
        runBlocking<Unit> {
            seed(lineup())
            val broken =
                object : LibraryRepository by library {
                    override suspend fun ruleSnapshot(
                        account: LibraryAccount,
                        libraries: Set<ItemId>,
                        cutoff: Instant,
                    ): RuleSnapshot = error("library unreadable")
                }
            val failing = LineupService(store, broken, clock, { 0 }, {}, TestCatalog)

            assertEquals(ResetResult.LibrariesUnavailable, ChannelManager(failing) { LIBRARIES }.reset(ACCOUNT))
        }

    @Test
    fun resetReportsNotEditableForANewerAppsFile() =
        runBlocking<Unit> {
            val newer = """{"version":2}""".encodeToByteArray()
            store.inner.save(ACCOUNT, newer)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(ResetResult.NotEditable, manager.reset(ACCOUNT))
            assertEquals(LineupAccess.ReadOnly, manager.access(ACCOUNT).first())
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
