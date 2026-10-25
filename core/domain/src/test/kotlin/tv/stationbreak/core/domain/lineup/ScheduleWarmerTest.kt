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
    private var now = NOON
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val builds = mutableListOf<DayBuild>()
    private val source = BuiltInScheduleSource(service, library, built = { builds += it }) { LIBRARIES }

    /** The end of the broadcast day that holds [NOON] (24 hours from 06:00 at offset 0). */
    private val dayEnd = Instant.parse("2026-10-20T06:00:00Z")

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        library.seed(fillingLibrary())
        val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs))) }
    }

    private fun TestScope.dispatcher() = StandardTestDispatcher(testScheduler)

    @Test
    fun warmBuildsTodayAndLeavesALaterTuneNothingToRead() =
        runTest {
            val wake = source.warm(NOON, dispatcher())
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
            val first = source.warm(NOON, dispatcher())
            assertEquals(dayEnd - 1.hours, first)
            assertEquals(1, builds.size, "only today's day so far")

            now = dayEnd - 30.minutes
            val second = source.warm(now, dispatcher())
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
            val none = BuiltInScheduleSource(service, library) { null }
            assertNull(none.warm(NOON, dispatcher()))
        }

    @Test
    fun theWarmerBuildsAtStartAndAgainBeforeTheDayEnds() =
        runTest {
            val elapsed = { Instant.fromEpochMilliseconds(NOON.toEpochMilliseconds() + testScheduler.currentTime) }
            val timeline =
                object : Clock {
                    override fun now(): Instant = elapsed()
                }
            val warmer = ScheduleWarmer(source, service, library, timeline, dispatcher())
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
}
