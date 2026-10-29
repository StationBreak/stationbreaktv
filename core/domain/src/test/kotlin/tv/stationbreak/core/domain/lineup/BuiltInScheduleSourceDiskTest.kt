// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.ItemId
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A new process starts from the days the last one built (#480): the source writes each cold build to disk, and a
 * source in a fresh process (a new service and source over the same stores) takes the day from there, with no library
 * read and the very days a fresh build gives, until anything the days depend on moves.
 */
class BuiltInScheduleSourceDiskTest {
    private val library = CountingLibrary()
    private val lineups = RecordingStore()
    private val disk = MemoryDayStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        library.seed(fillingLibrary())
    }

    /** One process's service and source, and what it reported. */
    private inner class Process(
        app: String = "1:100",
    ) {
        val builds: MutableList<DayBuild> = Collections.synchronizedList(mutableListOf())
        val events: MutableList<DaySnapshotEvent> = Collections.synchronizedList(mutableListOf())
        val service = LineupService(lineups, library, clock, { 0 }, {}, TestCatalog)
        val source =
            BuiltInScheduleSource(
                service,
                library,
                built = { builds += it },
                persisted = PersistedDays(disk, app, { events += it }),
            ) { LIBRARIES }

        fun days(at: Instant = NOON): List<ChannelDay> =
            runBlocking {
                source.channelDays(ACCOUNT, LIBRARIES, at).also { source.awaitSaves() }
            }
    }

    private fun firstProcess(): List<ChannelDay> {
        val first = Process()
        runBlocking { first.service.refresh(ACCOUNT, LIBRARIES) }
        val days = first.days()
        assertEquals("miss", first.builds.single().snapshot)
        assertTrue(first.events.any { it is DaySnapshotEvent.Saved }, "the cold build was written")
        return days
    }

    @Test
    fun aNewProcessTakesTheDaysFromDiskAndTheyAreTheDaysAFreshBuildGives() {
        val cold = firstProcess()
        val readsBefore = library.snapshotCutoffs.size

        val next = Process()
        val warm = next.days()

        assertEquals(describe(cold), describe(warm))
        assertEquals("disk", next.builds.single().snapshot)
        assertEquals(readsBefore, library.snapshotCutoffs.size, "no library snapshot was read")
        assertEquals(0, next.source.snapshotReads())
        assertTrue(next.events.single() is DaySnapshotEvent.Hit, "and nothing was written again")

        // The same days as a build that never saw the file.
        val fresh = BuiltInScheduleSource(next.service, library) { LIBRARIES }
        assertEquals(describe(warm), describe(runBlocking { fresh.channelDays(ACCOUNT, LIBRARIES, NOON) }))
    }

    @Test
    fun aDiskHitIsKeptInMemoryLikeABuild() {
        firstProcess()
        val next = Process()
        val one = next.days()
        val two = next.days()
        assertTrue(one === two)
        assertEquals(1, next.builds.size)
    }

    @Test
    fun aLibraryChangeSinceTheFileWasWrittenBuildsAgain() {
        firstProcess()
        library.seed(movie(900, "Comedy", rating = "R"))

        val next = Process()
        val days = next.days()

        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.Generation), next.events.first())
        assertEquals("miss", next.builds.single().snapshot)
        val fresh = BuiltInScheduleSource(next.service, library) { LIBRARIES }
        assertEquals(describe(runBlocking { fresh.channelDays(ACCOUNT, LIBRARIES, NOON) }), describe(days))
        assertTrue(days.any { day -> day.pool.any { it.id == id(900) } }, "the new movie is in the days")
        assertTrue(next.events.last() is DaySnapshotEvent.Saved, "and the new days replace the file")
    }

    @Test
    fun anAppUpdateBuildsAgain() {
        firstProcess()
        val updated = Process(app = "2:100")
        updated.days()
        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.App), updated.events.first())
        assertEquals("miss", updated.builds.single().snapshot)
    }

    @Test
    fun aCorruptFileBuildsAgainAndIsReplaced() {
        val cold = firstProcess()
        val (where, bytes) = disk.only()
        disk.files[where] = bytes.copyOf(bytes.size / 2)

        val next = Process()
        val days = next.days()

        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.Unreadable), next.events.first())
        assertEquals(describe(cold), describe(days))
        assertEquals(bytes.toList(), disk.only().second.toList(), "rewritten whole")
        assertEquals(
            "disk",
            Process()
                .also { it.days() }
                .builds
                .single()
                .snapshot,
        )
    }

    @Test
    fun aFewDaysAheadAreKeptAndNoMore() {
        firstProcess()
        val process = Process()
        for (day in 1..4) process.days(Instant.fromEpochMilliseconds(NOON.toEpochMilliseconds() + day * DAY_MS))
        assertEquals(PersistedDays.KEEP, runBlocking { disk.names(ACCOUNT) }.size)
    }

    @Test
    fun anotherChoiceOfLibrariesIsNotServedTheFile() {
        firstProcess()
        val next = Process()
        val other = setOf(ItemId("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"))
        runBlocking { next.source.channelDays(ACCOUNT, other, NOON) }
        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.Libraries), next.events.first())
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
