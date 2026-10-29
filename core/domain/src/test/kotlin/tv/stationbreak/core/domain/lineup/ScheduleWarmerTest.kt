// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Warming ahead (#392): the days are built before a tune asks, driven by a fake clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleWarmerTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val builds = mutableListOf<DayBuild>()
    private val layouts = mutableListOf<Pair<Long, Long>>()

    /** The end of the broadcast day that holds [NOON] (24 hours from 06:00 at offset 0). */
    private val dayEnd = Instant.parse("2026-10-20T06:00:00Z")

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        library.seed(fillingLibrary())
        val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs))) }
    }

    /** A source whose builds run on the test's virtual time, so the tests are deterministic. */
    private fun TestScope.sourceOn(libraries: Set<tv.stationbreak.core.model.ItemId>? = LIBRARIES) =
        BuiltInScheduleSource(
            service,
            library,
            built = { builds += it },
            laidOut = { n, ms -> layouts += n to ms },
            dispatcher = StandardTestDispatcher(testScheduler),
        ) { libraries }

    @Test
    fun warmBuildsTodayAndLeavesALaterTuneNothingToRead() =
        runTest {
            val source = sourceOn()
            val wake = source.warm(NOON)
            assertNotNull(wake)
            val reads = library.snapshotCutoffs.size

            source.onAir(source.airings(NOON).single().id, NOON, 2)

            assertEquals(reads, library.snapshotCutoffs.size, "the tune read nothing")
            assertEquals(1, builds.size)
            assertEquals(1, builds.single().channels)
        }

    @Test
    fun warmWaitsUntilAnHourBeforeTheDayEndsToBuildTheNextDay() =
        runTest {
            val source = sourceOn()
            val first = source.warm(NOON)
            assertEquals(dayEnd - 1.hours, first)
            assertEquals(1, builds.size, "only today's day so far")

            val now = dayEnd - 30.minutes
            val second = source.warm(now)
            assertEquals(2, builds.size, "the following day is built within the hour")
            assertEquals(dayEnd, second, "then it looks again when the day turns over")

            val reads = library.snapshotCutoffs.size
            val id = source.airings(now).single().id
            source.onAir(id, now, 12)
            assertEquals(reads, library.snapshotCutoffs.size, "a look across the day edge reads nothing")
        }

    @Test
    fun warmDoesNothingWithoutLibrariesChosen() =
        runTest {
            assertNull(sourceOn(null).warm(NOON))
        }

    @Test
    fun warmLaysOutEachChannelsDayAheadAndTimesIt() =
        runTest {
            val source = sourceOn()
            source.warm(NOON)
            assertEquals(1, layouts.size, "the one channel's layout was worked out by the warm")

            source.onAir(source.airings(NOON).single().id, NOON, 2)
            assertEquals(1, layouts.size, "a tune lays nothing out again")
        }

    @Test
    fun theWarmerBuildsAtStartAndAgainBeforeTheDayEnds() =
        runTest {
            val noonMs = NOON.toEpochMilliseconds()
            val timeline =
                object : Clock {
                    override fun now(): Instant = Instant.fromEpochMilliseconds(noonMs + testScheduler.currentTime)
                }
            val warmer = ScheduleWarmer(sourceOn(), service, library, timeline)
            warmer.start(backgroundScope)
            runCurrent()
            assertEquals(1, builds.size, "today's days are built at start")

            advanceTimeBy(16.hours)
            runCurrent()
            assertEquals(1, builds.size, "an hour out from the end is not yet reached")

            advanceTimeBy(2.hours)
            runCurrent()
            assertEquals(2, builds.size, "the following day is built within the hour of the end")
        }

    // #434: WorkManager starts the process for the periodic sync with no screen; nothing in it needs a day built.
    @Test
    fun aWarmerBuildsNothingUntilTheAppFirstComesToTheForeground() =
        runTest {
            val warmer = ScheduleWarmer(sourceOn(), service, library, clock)
            advanceTimeBy(6.hours)
            runCurrent()
            assertEquals(0, builds.size, "a process with no screen builds no day")

            warmer.foreground(backgroundScope)
            runCurrent()
            assertEquals(1, builds.size, "the first foreground warms today")

            warmer.foreground(backgroundScope)
            runCurrent()
            assertEquals(1, builds.size, "a later foreground looks again and finds today built")
        }

    @Test
    fun aNudgeThatArrivesWhileAWarmRunsIsDropped() =
        runTest {
            val skew = longArrayOf(0)
            val noonMs = NOON.toEpochMilliseconds()
            val timeline =
                object : Clock {
                    override fun now(): Instant = Instant.fromEpochMilliseconds(noonMs + skew[0])
                }
            lateinit var warmer: ScheduleWarmer
            var nudged = false
            val source =
                BuiltInScheduleSource(
                    service,
                    library,
                    built = { builds += it },
                    laidOut = { _, _ ->
                        // In the middle of the first warm: the TV comes to the foreground, and the day is nearly over.
                        if (!nudged) {
                            nudged = true
                            skew[0] = (dayEnd - 30.minutes - NOON).inWholeMilliseconds
                            warmer.nudge()
                        }
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                ) { LIBRARIES }
            warmer = ScheduleWarmer(source, service, library, timeline)
            warmer.start(backgroundScope)
            runCurrent()
            assertEquals(1, builds.size, "the nudge during the warm did not start another")
        }

    @Test
    fun aNudgeDuringAFailedWarmRetriesAtOnce() =
        runTest {
            lateinit var warmer: ScheduleWarmer
            var asked = 0
            val source =
                BuiltInScheduleSource(
                    service,
                    library,
                    built = { builds += it },
                    dispatcher = StandardTestDispatcher(testScheduler),
                ) {
                    // The first warm fails, with the TV coming to the foreground while it ran.
                    if (asked++ == 0) {
                        warmer.nudge()
                        error("settings unreadable")
                    }
                    LIBRARIES
                }
            warmer = ScheduleWarmer(source, service, library, clock)
            warmer.start(backgroundScope)
            runCurrent()
            assertEquals(1, builds.size, "the nudge made the warm try again at once, not after the idle wait")
        }

    @Test
    fun theWarmerLooksAgainAtOnceWhenNudgedAndNeverWaitsPastTheCap() =
        runTest {
            val skew = longArrayOf(0)
            val timeline =
                object : Clock {
                    override fun now(): Instant =
                        Instant.fromEpochMilliseconds(NOON.toEpochMilliseconds() + testScheduler.currentTime + skew[0])
                }
            val warmer = ScheduleWarmer(sourceOn(), service, library, timeline)
            warmer.start(backgroundScope)
            runCurrent()
            assertEquals(1, builds.size)

            // The TV sleeps through to 30 minutes before the day ends: the virtual wait did not run, the clock did.
            skew[0] = (dayEnd - 30.minutes - NOON).inWholeMilliseconds
            warmer.nudge()
            runCurrent()
            assertEquals(2, builds.size, "coming to the foreground warms the following day")

            // Without a nudge, a wait is capped, so a standby that stops the wait cannot hold the warm back long.
            skew[0] += 24.hours.inWholeMilliseconds
            advanceTimeBy(6.minutes)
            runCurrent()
            assertEquals(3, builds.size, "the capped wait looked again")
        }
}
