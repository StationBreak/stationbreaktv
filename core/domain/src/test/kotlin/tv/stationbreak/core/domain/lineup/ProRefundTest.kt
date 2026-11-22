// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.FreeTier
import tv.stationbreak.core.domain.kids.FakeKidsLock
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLockedChannelRepository
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A refund after Pro (#99, ADR-0023): the starters reset, the customs past the free five are kept and locked. */
class ProRefundTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, { }, TestCatalog)
    private val pro = FakeProEntitlement(initial = true)
    private val manager = ChannelManager(service, pro, IGNORED_SWITCH_ONS) { LIBRARIES }
    private val locked = ProLockedChannelRepository(service, pro)
    private val total = FreeTier.CUSTOM_CHANNELS + 2

    // The oldest first: c1 is the oldest, c7 the newest.
    private fun custom(n: Int) =
        channel(
            "custom:c$n",
            null,
            ChannelState.On,
            ChannelSource.Custom(rules("C$n", Source.Genre("Comedy"))),
        ).copy(numberOverride = 600 + n)

    private suspend fun seed(vararg extra: LineupChannel) {
        val base = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        val channels = listOf(base) + (1..total).map(::custom) + extra
        store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(*channels.toTypedArray())))
    }

    private fun id(n: Int) = ChannelId("custom:c$n")

    private suspend fun shown() =
        locked
            .channels(ACCOUNT)
            .first()
            .filter { it.viewerMade }
            .map { it.id }

    @Test
    fun aRefundLocksTheCustomsBeyondTheOldestFive_andDeletesNone() =
        runBlocking<Unit> {
            seed()
            val before = store.inner.peek(ACCOUNT)

            pro.set(false)

            assertEquals((1..FreeTier.CUSTOM_CHANNELS).map(::id), shown())
            assertEquals(total, service.channels(ACCOUNT).first().count { it.viewerMade }, "all are still held")
            assertContentEquals(before, store.inner.peek(ACCOUNT), "locking writes nothing")
        }

    @Test
    fun aLockedCustomCannotBeChanged_butTheFirstFiveCan() =
        runBlocking<Unit> {
            seed()
            pro.set(false)
            val before = store.inner.peek(ACCOUNT)

            val needsPro = ChangeResult.Refused(ChangeRefusal.NeedsPro)
            assertEquals(needsPro, manager.change(ACCOUNT, ChannelChange.SwitchOff(id(6))))
            assertEquals(needsPro, manager.change(ACCOUNT, ChannelChange.SetNumber(id(7), 650)))
            assertContentEquals(before, store.inner.peek(ACCOUNT))
            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, ChannelChange.SwitchOff(id(5))))
        }

    @Test
    fun buyingProAgainUnlocksThemUntouched() =
        runBlocking<Unit> {
            seed()
            val before = store.inner.peek(ACCOUNT)
            pro.set(false)
            assertEquals(FreeTier.CUSTOM_CHANNELS, shown().size)

            pro.set(true)

            assertEquals((1..total).map(::id), shown())
            assertContentEquals(before, store.inner.peek(ACCOUNT))
            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, ChannelChange.SwitchOff(id(6))))
        }

    @Test
    fun theLockFollowsTheIdAlone_notTheNumberNorWhenAChannelWasSwitchedOn() {
        val six =
            (1..FreeTier.CUSTOM_CHANNELS + 1).map {
                custom(it).copy(numberOverride = 700 - it, onSinceMs = 9_000L - it)
            }
        val expected = setOf(id(FreeTier.CUSTOM_CHANNELS + 1))
        assertEquals(expected, six.lockedCustomIds())

        // Switching the oldest on again moves its onSince, and the order of the list is not a rank: nothing moves.
        val switched = six.map { if (it.id == id(1)) it.copy(onSinceMs = 99_999L) else it }.reversed()
        assertEquals(expected, switched.lockedCustomIds())
    }

    @Test
    fun kidsModeDoesNotUncoverALockedCustom_sinceTheLockIsRankedOverTheWholeLineup() =
        runBlocking<Unit> {
            // c6 and c7 are the locked ones; both are kids channels. Kids Mode on shows only numbers up to 33.
            val customs = (1..total).map { custom(it).copy(numberOverride = if (it >= 6) 20 + it else 600 + it) }
            val base = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
            store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(*(listOf(base) + customs).toTypedArray())))
            val lock = FakeKidsLock()
            lock.lock(KidsCap.Kids)
            pro.set(false)

            val shownInKids = KidsLockedChannelRepository(locked, lock).channels(ACCOUNT).first()

            assertEquals(emptyList(), shownInKids.map { it.id })
        }

    @Test
    fun aRefundResetsTheStartersTheViewerEditedAndKeepsEveryCustom() =
        runBlocking<Unit> {
            val edited = starter("whodunit", 210, ChannelState.Shelf, rules("Whodunit", Source.Genre("Crime")))
            seed(edited.copy(viewerChoice = ViewerChoice.Touched))
            val revocation = ProRevocation(service) { LIBRARIES }

            assertTrue(revocation.onRevoked(ACCOUNT))

            val lineup = decoded()
            assertTrue(lineup.channels.none { it.chosenByViewer && it.source is ChannelSource.Starter })
            assertEquals(total, lineup.channels.count { it.id.value.startsWith("custom:") })
        }

    @Test
    fun withNoViewerMarkOnAStarterARefundResetsNothing() =
        runBlocking<Unit> {
            seed()
            val before = store.inner.peek(ACCOUNT)

            assertFalse(ProRevocation(service) { LIBRARIES }.onRevoked(ACCOUNT))
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun withNoLibrariesKnownARefundWaitsAndWritesNothing() =
        runBlocking<Unit> {
            val touched = starter("whodunit", 210, ChannelState.Shelf, rules("W", Source.Genre("Crime")))
            seed(touched.copy(viewerChoice = ViewerChoice.Touched))
            val before = store.inner.peek(ACCOUNT)

            assertFalse(ProRevocation(service) { null }.onRevoked(ACCOUNT))
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun theScheduleLeavesLockedCustomsOutWhileFree_andAirsThemAgainWithPro() =
        runBlocking<Unit> {
            seed()
            library.useAccount(ACCOUNT)
            val airings = (1..total).map { ChannelAiring(id(it), 600 + it, "C$it", NOON, null) }
            val source = ProLockedScheduleSource(AiringsOnly(airings), ProLockedIds(pro, library, service))

            assertEquals(total, source.airings(NOON).size)

            pro.set(false)
            assertEquals((1..FreeTier.CUSTOM_CHANNELS).map(::id), source.airings(NOON).map { it.id })
            assertEquals(null, source.onAir(id(6), NOON, 1))
        }

    private class AiringsOnly(
        private val all: List<ChannelAiring>,
    ) : ScheduleSource {
        override suspend fun onAir(
            channel: ChannelId,
            at: Instant,
            next: Int,
        ): ChannelOnAir? = null

        override suspend fun standIns(
            channel: ChannelId,
            block: AiringBlock,
            exclude: Set<CanonicalId>,
        ): List<AiringBlock> = emptyList()

        override suspend fun airings(at: Instant): List<ChannelAiring> = all

        override suspend fun schedule(
            from: Instant,
            until: Instant,
            build: ScheduleBuild,
        ): ScheduleWindow? = null
    }

    private fun decoded(): Lineup {
        val bytes = checkNotNull(store.inner.peek(ACCOUNT))
        return checkNotNull((LineupCodec.decode(bytes) as? LineupCodec.Decoded.Loaded)?.lineup)
    }
}
