// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** What a free viewer may change (#793): the planner's lineup is Pro, the viewer's own custom channels are not. */
class ChannelManagerFreeTierTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, { }, TestCatalog)
    private val pro = FakeProEntitlement(initial = false)
    private val manager = ChannelManager(service, pro, IGNORED_SWITCH_ONS) { LIBRARIES }

    private val laughs = LineupIds.starter("laughs")
    private val whodunit = LineupIds.starter("whodunit")
    private val shelved = LineupIds.starter("shelved")
    private val odds = LineupIds.leftovers("odds")
    private val mine = ChannelId("custom:mine")
    private val other = ChannelId("custom:other")
    private val needsPro = ChangeResult.Refused(ChangeRefusal.NeedsPro)

    private fun customOf(
        id: String,
        number: Int,
    ) = channel(id, number, ChannelState.On, ChannelSource.Custom(rules(id, Source.Genre("Comedy"))))

    private suspend fun seed() {
        val lineup: Lineup =
            lineupOf(
                starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
                starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
                starter("shelved", 310, ChannelState.Shelf, rules("Shelved", Source.Genre("Western"))),
                leftovers("odds", 500, null),
                customOf("custom:mine", 600),
                customOf("custom:other", 601),
            )
        store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
    }

    private suspend fun free(change: ChannelChange) = manager.change(ACCOUNT, change)

    @Test
    fun freeCannotSwitchAStarterOnOrOff() =
        runBlocking<Unit> {
            seed()
            val before = store.inner.peek(ACCOUNT)

            assertEquals(needsPro, free(ChannelChange.SwitchOff(laughs)))
            assertEquals(needsPro, free(ChannelChange.SwitchOn(shelved)))
            assertContentEquals(before, store.inner.peek(ACCOUNT), "a refused change writes nothing")
        }

    @Test
    fun freeCannotSwitchOffOddsAndEnds() =
        runBlocking<Unit> {
            seed()

            assertEquals(needsPro, free(ChannelChange.SwitchOff(odds)))
        }

    @Test
    fun freeCannotRenumberAStarter() =
        runBlocking<Unit> {
            seed()

            assertEquals(needsPro, free(ChannelChange.SetNumber(laughs, 150)))
            assertEquals(needsPro, free(ChannelChange.ClearNumber(laughs)))
        }

    @Test
    fun freeCanSwitchOffAndRenumberItsOwnCustomChannels() =
        runBlocking<Unit> {
            seed()

            assertEquals(ChangeResult.Done, free(ChannelChange.SetNumber(mine, 650)))
            assertEquals(ChangeResult.Done, free(ChannelChange.ClearNumber(mine)))
            assertEquals(ChangeResult.Done, free(ChannelChange.SwitchOff(mine)))
            assertEquals(ChangeResult.Done, free(ChannelChange.SwitchOn(mine)))
        }

    @Test
    fun freeCanSwapTwoOfItsOwnCustomChannels() =
        runBlocking<Unit> {
            seed()

            assertEquals(ChangeResult.Done, free(ChannelChange.SetNumber(mine, 601, swapWith = other)))
        }

    @Test
    fun aSwapThatTouchesAStarterIsProEvenFromACustomChannel() =
        runBlocking<Unit> {
            seed()
            val before = store.inner.peek(ACCOUNT)

            assertEquals(needsPro, free(ChannelChange.SetNumber(mine, 210, swapWith = whodunit)))
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun useMyCollectionsIsFree() =
        runBlocking<Unit> {
            seed()

            assertEquals(ChangeResult.Done, free(ChannelChange.UseMyCollections(false)))
        }

    @Test
    fun anUnknownChannelIsStillUnknown() =
        runBlocking<Unit> {
            seed()

            val result = free(ChannelChange.SwitchOff(ChannelId("starter:nope")))

            assertEquals(ChangeResult.Refused(ChangeRefusal.UnknownChannel), result)
        }

    @Test
    fun freeCannotReset() =
        runBlocking<Unit> {
            seed()
            val before = store.inner.peek(ACCOUNT)

            assertEquals(ResetResult.NeedsPro, manager.reset(ACCOUNT))
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun proMayDoEverythingAndFollowsTheEntitlementLive() =
        runBlocking<Unit> {
            seed()
            assertEquals(needsPro, free(ChannelChange.SwitchOff(laughs)))

            pro.set(true)

            assertEquals(ChangeResult.Done, free(ChannelChange.SwitchOff(laughs)))
            assertEquals(ChangeResult.Done, free(ChannelChange.SwitchOn(shelved)))
            assertEquals(ChangeResult.Done, free(ChannelChange.SetNumber(mine, 210, swapWith = whodunit)))
            assertEquals(ResetResult.Chosen, manager.reset(ACCOUNT))
        }

    @Test
    fun anUnreadableCustomChannelIsGatedLikeAStarter() =
        runBlocking<Unit> {
            val newer = channel("custom:newer", 650, ChannelState.On, ChannelSource.Unusable("""{"kind":"custom"}"""))
            val base = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
            store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(base, customOf("custom:mine", 600), newer)))

            assertEquals(needsPro, free(ChannelChange.SwitchOff(ChannelId("custom:newer"))))
        }

    @Test
    fun aRetiredStarterIsTheplannersAndGated() =
        runBlocking<Unit> {
            val oldRules = rules("Old", Source.Genre("Comedy"))
            val retired = channel("starter:old", 311, ChannelState.On, ChannelSource.Custom(oldRules))
            val base = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
            store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(base, retired)))

            assertEquals(needsPro, free(ChannelChange.SwitchOff(ChannelId("starter:old"))))
        }

    @Test
    fun aRetiredStarterIsKindCustomButNotMadeByTheViewer() =
        runBlocking<Unit> {
            val oldRules = rules("Old", Source.Genre("Comedy"))
            val retired = channel("starter:old", 311, ChannelState.On, ChannelSource.Custom(oldRules))
            val base = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
            store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(base, retired, customOf("custom:mine", 600))))

            val byId = service.channels(ACCOUNT).first().associateBy { it.id }

            assertEquals(ChannelKind.Custom, byId.getValue(ChannelId("starter:old")).kind)
            assertFalse(byId.getValue(ChannelId("starter:old")).viewerMade)
            assertTrue(byId.getValue(mine).viewerMade)
            assertFalse(byId.getValue(laughs).viewerMade)
        }

    @Test
    fun clearingANumberWithASwapAgainstAStarterIsPro() =
        runBlocking<Unit> {
            seed()

            assertEquals(needsPro, free(ChannelChange.ClearNumber(mine, swapWith = laughs)))
        }

    @Test
    fun switchingOddsAndEndsOnIsPro() =
        runBlocking<Unit> {
            seed()

            assertEquals(needsPro, free(ChannelChange.SwitchOn(odds)))
        }

    @Test
    fun withNoLineupAFreeChangeFailsClosed() =
        runBlocking<Unit> {
            assertEquals(needsPro, free(ChannelChange.SwitchOff(laughs)))
        }
}
