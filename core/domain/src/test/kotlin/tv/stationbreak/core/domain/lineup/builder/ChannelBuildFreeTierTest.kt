// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.FreeTier
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.LineupCodec.Decoded.Loaded
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.channel
import tv.stationbreak.core.domain.lineup.fillingLibrary
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.starter
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The free tier's cap on custom channels (#793): enforced in the lineup's lock, counted in one place. */
class ChannelBuildFreeTierTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, { }, TestCatalog)
    private val pro = FakeProEntitlement(initial = false)
    private var ids = 0
    private val builder =
        ChannelBuildManager(
            service,
            pro,
            { Seed(1uL) },
            { "id${++ids}" },
            { _, sources -> sources.associateWith { it.key } },
        ) { _, _ -> emptyMap() }

    private val laughs = LineupIds.starter("laughs")

    private fun custom(n: Int) =
        channel(
            "custom:c$n",
            null,
            ChannelState.On,
            ChannelSource.Custom(rules("C$n", Source.Genre("Comedy"))),
        ).copy(numberOverride = 600 + n)

    private suspend fun seedWith(
        customs: Int,
        vararg extra: LineupChannel,
    ) {
        val base = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        val channels = listOf(base) + (1..customs).map(::custom) + extra
        store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(*channels.toTypedArray())))
    }

    private fun stored(): Lineup {
        val bytes = checkNotNull(store.inner.peek(ACCOUNT))
        return checkNotNull((LineupCodec.decode(bytes) as? Loaded)?.lineup)
    }

    private suspend fun create(): BuildResult = builder.create(ACCOUNT, builder.blank(ACCOUNT, false).named())

    private fun ChannelDraft.named() = copy(name = "Mine", sources = listOf(DraftSource.Genre("Comedy", "Comedy")))

    @Test
    fun theFreeAllowanceIsFive() {
        assertEquals(5, FreeTier.CUSTOM_CHANNELS)
    }

    @Test
    fun aFreeLineupUnderTheCapCanCreate() =
        runBlocking<Unit> {
            seedWith(customs = 4)

            assertIs<BuildResult.Done>(create())
            assertTrue(builder.canAddCustom(ACCOUNT).first().not(), "now at the cap")
        }

    @Test
    fun aFreeLineupAtTheCapRefusesNewAndWritesNothing() =
        runBlocking<Unit> {
            seedWith(customs = 5)
            val before = store.inner.peek(ACCOUNT)

            assertEquals(BuildResult.Refused(BuildRefusal.CustomLimit), create())
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun aCopyOfAStarterAtTheCapIsRefusedToo() =
        runBlocking<Unit> {
            seedWith(customs = 5)
            val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, laughs, copy = true))

            assertEquals(BuildResult.Refused(BuildRefusal.CustomLimit), builder.create(ACCOUNT, opened.draft))
        }

    @Test
    fun proCreatesPastTheCap() =
        runBlocking<Unit> {
            pro.set(true)
            seedWith(customs = 5)

            assertIs<BuildResult.Done>(create())
            assertEquals(6, stored().channels.count { it.source is ChannelSource.Custom })
        }

    @Test
    fun aStoredLineupOverTheCapKeepsEverythingAndRefusesMore() =
        runBlocking<Unit> {
            seedWith(customs = 7)

            assertEquals(BuildResult.Refused(BuildRefusal.CustomLimit), create())
            assertEquals(7, stored().channels.count { it.source is ChannelSource.Custom })
            assertFalse(builder.canAddCustom(ACCOUNT).first())
        }

    @Test
    fun deletingFreesAPlace() =
        runBlocking<Unit> {
            seedWith(customs = 5)
            assertFalse(builder.canAddCustom(ACCOUNT).first())

            assertIs<BuildResult.Done>(builder.delete(ACCOUNT, ChannelId("custom:c3")))

            assertTrue(builder.canAddCustom(ACCOUNT).first())
            assertIs<BuildResult.Done>(create())
        }

    @Test
    fun editingInPlaceAtTheCapStaysFree() =
        runBlocking<Unit> {
            seedWith(customs = 5)
            val id = ChannelId("custom:c2")
            val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false))

            assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, opened.draft.copy(name = "Renamed")))
            assertEquals(BuildResult.Done(id), builder.reshuffle(ACCOUNT, id))
        }

    @Test
    fun unreadableCustomChannelsCountTowardTheCap() =
        runBlocking<Unit> {
            // Fail closed (#1063): a custom this app cannot read still holds its place, and is never deleted.
            val newer = channel("custom:newer", 650, ChannelState.On, ChannelSource.Unusable("""{"kind":"custom"}"""))
            val corrupt = channel("custom:bad", 651, ChannelState.On, ChannelSource.Unusable("not json"))
            seedWith(3, newer, corrupt)

            assertFalse(builder.canAddCustom(ACCOUNT).first())
            assertEquals(BuildResult.Refused(BuildRefusal.CustomLimit), create())
            assertEquals(2, stored().channels.count { it.source is ChannelSource.Unusable })
        }

    @Test
    fun canAddCustomFollowsTheEntitlement() =
        runBlocking<Unit> {
            seedWith(customs = 5)
            assertFalse(builder.canAddCustom(ACCOUNT).first())

            pro.set(true)

            assertTrue(builder.canAddCustom(ACCOUNT).first())
        }

    private fun retired(key: String) =
        channel(
            LineupIds.starter(key).value,
            310 + key.length,
            ChannelState.On,
            ChannelSource.Custom(rules(key, Source.Genre("Comedy"))),
        )

    @Test
    fun aRetiredStarterIsNotTheViewersAndDoesNotCount() =
        runBlocking<Unit> {
            seedWith(5, retired("old"))
            assertEquals(BuildResult.Refused(BuildRefusal.CustomLimit), create())
            assertEquals(false, builder.canAddCustom(ACCOUNT).first())
        }

    @Test
    fun fourViewerCustomsAndTwoRetiredStartersMayCreate() =
        runBlocking<Unit> {
            seedWith(4, retired("old"), retired("older"))

            assertTrue(builder.canAddCustom(ACCOUNT).first())
            assertIs<BuildResult.Done>(create())
        }

    @Test
    fun canAddCustomGoesFalseAfterACreateMadeElsewhere() =
        runBlocking<Unit> {
            seedWith(customs = 4)
            assertTrue(builder.canAddCustom(ACCOUNT).first())
            // A collector that is already watching moves to false when the fifth custom channel is made.
            val gone = async(start = CoroutineStart.UNDISPATCHED) { builder.canAddCustom(ACCOUNT).first { !it } }

            assertIs<BuildResult.Done>(create())

            assertFalse(withTimeout(TIMEOUT_MS) { gone.await() })
        }

    // Holds the lineup's lock while [during] runs: an edit asked for then waits on it, past every check made outside.
    private suspend fun <T> whileLocked(
        ask: suspend () -> T,
        during: () -> Unit,
    ): T =
        coroutineScope {
            service.loaded(ACCOUNT)
            val lock = checkNotNull(service.lockOf(ACCOUNT))
            lock.lock()
            val asked = async(start = CoroutineStart.UNDISPATCHED) { ask() }
            during()
            lock.unlock()
            asked.await()
        }

    @Test
    fun aRefundThatLandsWhileACreateWaitsForTheLockKeepsTheCap() =
        runBlocking<Unit> {
            pro.set(true)
            seedWith(customs = 5)
            val draft = builder.blank(ACCOUNT, false).named()
            val before = store.inner.peek(ACCOUNT)

            val result = whileLocked({ builder.create(ACCOUNT, draft) }) { pro.set(false) }

            assertEquals(BuildResult.Refused(BuildRefusal.NeedsPro), result)
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun aRefundThatLandsWhileAnEditWaitsForTheLockKeepsALockedCustomUnchanged() =
        runBlocking<Unit> {
            pro.set(true)
            seedWith(customs = 7)
            val locked = ChannelId("custom:c7")
            val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, locked, copy = false))
            val before = store.inner.peek(ACCOUNT)
            val refusal = BuildResult.Refused(BuildRefusal.NeedsPro)

            assertEquals(
                refusal,
                whileLocked({ builder.save(ACCOUNT, locked, opened.draft.copy(name = "Renamed")) }) { pro.set(false) },
            )
            pro.set(true)
            assertEquals(refusal, whileLocked({ builder.reshuffle(ACCOUNT, locked) }) { pro.set(false) })
            pro.set(true)
            assertEquals(refusal, whileLocked({ builder.delete(ACCOUNT, locked) }) { pro.set(false) })
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun withNoLineupCanAddCustomIsTheEntitlement() =
        runBlocking<Unit> {
            assertFalse(builder.canAddCustom(ACCOUNT).first())
            pro.set(true)
            assertTrue(builder.canAddCustom(ACCOUNT).first())
        }
}

private const val TIMEOUT_MS = 10_000L
