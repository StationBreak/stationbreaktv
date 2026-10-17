// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Spec 09 task 2.9: when this TV holds no version older than the cut-off (a new install, or an item first seen later),
 * the read falls back to the **oldest** version it holds, so the item is still scheduled and the TVs differ only for
 * the one day the format documents.
 */
class NewInstallOldestCopyTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val t0 = clock.nowMs

    @After
    fun close() = repository.close()

    private fun at(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

    private suspend fun read(cutoffMs: Long): List<ScheduleItem> = repository.scheduleItems(ALICE, setOf(MOVIES), at(cutoffMs))

    private suspend fun runtimes(cutoffMs: Long) = read(cutoffMs).associate { it.id.value to it.runtimeMs }

    @Test
    fun anItemEditedTwiceAndAskedForBeforeAnythingIsOlderGivesItsOldestVersion() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 100.minutes))
            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 110.minutes))

            val held = runtimes(t0 - 1.hours.inWholeMilliseconds)

            assertEquals(mapOf(hexId(1) to 90.minutes.inWholeMilliseconds), held)
        }

    @Test
    fun anItemWithNoVersionAndFirstSeenAfterTheCutoffGivesItsCurrentRow() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))

            val held = runtimes(t0 - 1.hours.inWholeMilliseconds)

            assertEquals(mapOf(hexId(1) to 90.minutes.inWholeMilliseconds), held)
        }

    @Test
    fun theCutoffExactlyAtTheFirstSeenTimeIsStillNotOlder() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 100.minutes))

            // seenFrom < cutoff is strict: at t0 nothing is older, so the oldest copy; one ms later the first version is.
            assertEquals(90.minutes.inWholeMilliseconds, runtimes(t0)[hexId(1)])
            assertEquals(90.minutes.inWholeMilliseconds, runtimes(t0 + 1)[hexId(1)])
            assertEquals(90.minutes.inWholeMilliseconds, runtimes(t0 + 10_000)[hexId(1)])
            assertEquals(100.minutes.inWholeMilliseconds, runtimes(t0 + 10_001)[hexId(1)])
        }

    @Test
    fun theRuleIsPerItemSoAnOldItemAndANewOneAreEachJudgedOnTheirOwnHistory() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(1.hours.inWholeMilliseconds)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 100.minutes), sitem(2, runtime = 60.minutes))
            clock.stepMs(1.hours.inWholeMilliseconds)
            repository.put(ALICE, MOVIES, sitem(2, runtime = 70.minutes))
            val cutoff = t0 + 90.minutes.inWholeMilliseconds

            val held = runtimes(cutoff)

            // Item 1 was seen before the cutoff (its second version, 100 min, since t0 + 1 h). Item 2 first appeared at
            // t0 + 1 h, also before the cutoff, and was edited after it: its 60 min version.
            assertEquals(
                mapOf(hexId(1) to 100.minutes.inWholeMilliseconds, hexId(2) to 60.minutes.inWholeMilliseconds),
                held,
            )
        }

    @Test
    fun anItemFirstSeenOnlyAfterTheCutoffWithEditsGivesItsOldestVersionNotItsLatest() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            clock.stepMs(1.hours.inWholeMilliseconds)
            repository.put(ALICE, MOVIES, sitem(5, runtime = 30.minutes))
            clock.stepMs(1_000)
            repository.put(ALICE, MOVIES, sitem(5, runtime = 40.minutes))

            val held = runtimes(t0 + 1_000)

            assertEquals(mapOf(hexId(5) to 30.minutes.inWholeMilliseconds), held)
        }

    @Test
    fun anAccountWithNothingReadsAsEmpty() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))

            assertEquals(emptyList<ScheduleItem>(), read(t0))
            assertEquals(emptyList<ScheduleItem>(), repository.scheduleItems(ALICE, emptySet(), at(t0)))
        }
}
