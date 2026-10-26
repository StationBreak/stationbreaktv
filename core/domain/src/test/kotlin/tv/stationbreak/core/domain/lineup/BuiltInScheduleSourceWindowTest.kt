// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** [BuiltInScheduleSource.schedule]: one window of airings for every visible channel (spec 17). */
class BuiltInScheduleSourceWindowTest {
    /** A library whose snapshot reads wait for [gate], to hold a build in flight. */
    private class GatedLibrary(
        val inner: CountingLibrary,
    ) : LibraryRepository by inner {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()

        override suspend fun ruleSnapshot(
            account: LibraryAccount,
            libraries: Set<ItemId>,
            cutoff: Instant,
        ): RuleSnapshot {
            entered.complete(Unit)
            gate.await()
            return inner.ruleSnapshot(account, libraries, cutoff)
        }
    }

    private val counting = CountingLibrary()
    private val library = GatedLibrary(counting).also { it.gate.complete(Unit) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private var chosen: Set<ItemId>? = LIBRARIES
    private val builds = mutableListOf<DayBuild>()
    private val source = BuiltInScheduleSource(service, library, built = { builds += it }) { chosen }

    private val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
    private val crimes = starter("crimes", 120, ChannelState.On, rules("Crimes", Source.Genre("Crime")))
    private val frights = starter("frights", 310, ChannelState.On, rules("Frights", Source.Genre("Nothing like it")))

    init {
        runBlocking { counting.delegate.useAccount(ACCOUNT) }
        counting.seed(fillingLibrary())
        holding(laughs, crimes)
    }

    private fun holding(vararg channels: LineupChannel) {
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(*channels))) }
    }

    private fun schedule(
        from: Instant = NOON,
        until: Instant = NOON + 6.hours,
        build: Build = Build.AsNeeded,
    ) = runBlocking { source.schedule(from, until, build) }

    @Test
    fun windowCoversSixHoursForEveryVisibleChannel() {
        val at = NOON
        val until = at + 6.hours
        val visible = runBlocking { source.airings(at) }.filter { it.hidden == null }

        val window = checkNotNull(schedule(at, until))

        assertEquals(visible.map { it.id }, window.rows.map { it.channel.id })
        assertEquals(listOf(110, 120), window.rows.map { it.channel.number })
        assertTrue(window.complete)
        for (row in window.rows) {
            val limit = minOf(until.toEpochMilliseconds(), row.builtUntilMs)
            assertTrue(row.blocks.last().endMs >= limit, "the row reaches the end of the window")
            assertTrue(row.blocks.first().startMs <= at.toEpochMilliseconds(), "the first block holds the start")
            assertTrue(row.blocks.first().endMs > at.toEpochMilliseconds())
            assertTrue(row.blocks.last().startMs < until.toEpochMilliseconds(), "no block starts after the window")
            row.blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs, "blocks run on") }
        }
        assertEquals(1, counting.programInfoCalls, "one read names every block of every row")
    }

    @Test
    fun builtUntilIsTheEndOfTheNextDay() {
        val row = checkNotNull(schedule()).rows.first()

        assertEquals(Instant.parse("2026-10-21T06:00:00Z").toEpochMilliseconds(), row.builtUntilMs)
    }

    @Test
    fun hiddenChannelsAreLeftOut() {
        holding(laughs, crimes, frights)
        val hiddenToday = runBlocking { source.airings(NOON) }.single { it.id == frights.id }
        assertNotNull(hiddenToday.hidden, "the fixture channel is hidden")

        val window = checkNotNull(schedule())

        assertEquals(listOf(laughs.id, crimes.id), window.rows.map { it.channel.id })
        assertTrue(window.rows.all { it.channel.hidden == null })
    }

    @Test
    fun cachedOnlyAcrossTheDayBoundaryDoesNotBuild() {
        val at = Instant.parse("2026-10-20T05:00:00Z")
        val until = Instant.parse("2026-10-20T11:00:00Z")
        val boundary = Instant.parse("2026-10-20T06:00:00Z").toEpochMilliseconds()
        runBlocking { source.channelDays(ACCOUNT, LIBRARIES, at) }
        val builtBefore = builds.size
        val readsBefore = counting.snapshotCutoffs.size

        val window = checkNotNull(schedule(at, until, Build.CachedOnly))

        assertFalse(window.complete)
        assertEquals(2, window.rows.size)
        assertTrue(window.rows.all { it.blocks.last().endMs == boundary }, "rows end where the kept day does")
        assertEquals(builtBefore, builds.size, "no day was built")
        assertEquals(readsBefore, counting.snapshotCutoffs.size, "no snapshot was read")
    }

    @Test
    fun cachedOnlyOfAKeptDayAcrossTheBoundaryIsCompleteOnceTheNextDayIsKept() {
        val at = Instant.parse("2026-10-20T05:00:00Z")
        val until = Instant.parse("2026-10-20T11:00:00Z")
        runBlocking { source.channelDays(ACCOUNT, LIBRARIES, at) }
        runBlocking { source.channelDays(ACCOUNT, LIBRARIES, until) }

        val window = checkNotNull(schedule(at, until, Build.CachedOnly))

        assertTrue(window.complete)
        assertTrue(window.rows.all { it.blocks.last().endMs >= until.toEpochMilliseconds() })
    }

    @Test
    fun asNeededAcrossTheDayBoundaryBuilds() {
        val at = Instant.parse("2026-10-20T05:00:00Z")
        val until = Instant.parse("2026-10-20T11:00:00Z")
        val boundary = Instant.parse("2026-10-20T06:00:00Z").toEpochMilliseconds()
        runBlocking { source.channelDays(ACCOUNT, LIBRARIES, at) }
        val builtBefore = builds.size

        val window = checkNotNull(schedule(at, until, Build.AsNeeded))

        assertTrue(window.complete)
        assertEquals(builtBefore + 1, builds.size, "day n + 1 was built")
        assertTrue(window.rows.all { row -> row.blocks.any { it.startMs >= boundary } }, "blocks run past 06:00")
        assertTrue(window.rows.all { it.blocks.last().endMs >= until.toEpochMilliseconds() })
    }

    @Test
    fun aWindowLongerThanTheBuiltDaysIsCutAtBuiltUntil() {
        val window = checkNotNull(schedule(NOON, NOON + 72.hours))

        assertTrue(window.complete, "cut at what is built, not truncated for lack of a built day")
        for (row in window.rows) {
            assertTrue(row.blocks.last().endMs >= row.builtUntilMs)
        }
    }

    @Test
    fun windowWithNothingCachedIsEmptyAndIncomplete() {
        val window = checkNotNull(schedule(build = Build.CachedOnly))

        assertEquals(emptyList(), window.rows)
        assertFalse(window.complete)
        assertEquals(0, builds.size, "nothing was built")
        assertEquals(0, counting.snapshotCutoffs.size)
    }

    @Test
    fun cachedOnlyDoesNotJoinAWarmerBuildInFlight() =
        runBlocking {
            val gated = GatedLibrary(counting)
            val gatedService = LineupService(store, gated, clock, { 0 }, {}, TestCatalog)
            val gatedSource = BuiltInScheduleSource(gatedService, gated) { LIBRARIES }
            val warmer =
                async(start = CoroutineStart.UNDISPATCHED) { gatedSource.channelDays(ACCOUNT, LIBRARIES, NOON) }
            withTimeout(WAIT_MS) { gated.entered.await() }
            assertEquals(1, gatedSource.inFlightCount())

            val window = withTimeout(WAIT_MS) { gatedSource.schedule(NOON, NOON + 6.hours, Build.CachedOnly) }

            assertEquals(ScheduleWindow(emptyList(), complete = false), window)
            assertEquals(0, gatedSource.joined.get(), "the build in flight was not joined")
            assertEquals(1, gatedSource.inFlightCount(), "and no second one was started")
            gated.gate.complete(Unit)
            assertEquals(2, warmer.await().size, "the warmer's build finished")
        }

    @Test
    fun cachedOnlyOfAKeptDayServesIt() {
        runBlocking { source.channelDays(ACCOUNT, LIBRARIES, NOON) }

        val window = checkNotNull(schedule(build = Build.CachedOnly))

        assertTrue(window.complete)
        assertEquals(2, window.rows.size)
    }

    @Test
    fun signedOutGivesNull() {
        chosen = null

        assertNull(schedule())
        assertNull(schedule(build = Build.CachedOnly))
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
