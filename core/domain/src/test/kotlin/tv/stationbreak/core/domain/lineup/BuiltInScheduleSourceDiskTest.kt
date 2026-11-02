// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupIds
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
        val service = LineupService(lineups, library, clock, { 0 }, {}, LineupSeams(TestCatalog, derived = disk))
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

    // #480: every open runs the reconcile and the collection pass, and a pass that completes records its own start
    // even when it changed nothing. Only a change to the library may make the next process build again.
    @Test
    fun aSyncThatChangedNothingBetweenProcessesStillFindsTheDaysOnDisk() {
        val cold = firstProcess()
        val generation = runBlocking { library.contentGeneration(ACCOUNT) }
        val aMinuteLater = Instant.fromEpochMilliseconds(NOON.toEpochMilliseconds() + 60_000)
        library.delegate.completeCollectionPass(ACCOUNT, aMinuteLater)
        assertEquals(generation, runBlocking { library.contentGeneration(ACCOUNT) }, "the pass wrote nothing")

        val next = Process()
        val warm = next.days()

        assertEquals("disk", next.builds.single().snapshot)
        assertTrue(next.events.single() is DaySnapshotEvent.Hit)
        assertEquals(describe(cold), describe(warm))
    }

    @Test
    fun theFirstFullCollectionPassBetweenProcessesBuildsAgain() {
        val first = Process()
        runBlocking { first.service.refresh(ACCOUNT, LIBRARIES) }
        // The days are built before any full collection pass has succeeded, and saved.
        library.delegate.setCollectionsSynced(ACCOUNT, false)
        first.days()
        assertTrue(first.events.any { it is DaySnapshotEvent.Saved })
        library.delegate.setCollectionsSynced(ACCOUNT, true)

        val next = Process()
        next.days()

        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.Generation), next.events.first())
        assertEquals("miss", next.builds.single().snapshot)
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

    private fun fresh(process: Process): List<ChannelDay> {
        val fresh = BuiltInScheduleSource(process.service, library) { LIBRARIES }
        return runBlocking { fresh.channelDays(ACCOUNT, LIBRARIES, NOON) }
    }

    @Test
    fun aFileWithAFlippedDigitBuildsAgainAndGivesTheFreshDays() {
        firstProcess()
        val (where, bytes) = disk.only()
        val text = bytes.decodeToString()
        val at =
            Regex("\"r\":([0-9])")
                .find(text.substringAfter('\n'))!!
                .groups[1]!!
                .range.first
        val flippedAt = text.indexOf('\n') + 1 + at
        disk.files[where] =
            text
                .toCharArray()
                .also { it[flippedAt] = if (it[flippedAt] == '1') '2' else '1' }
                .concatToString()
                .encodeToByteArray()

        val next = Process()
        val days = next.days()

        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.Unreadable), next.events.first())
        assertEquals("miss", next.builds.single().snapshot)
        assertEquals(describe(fresh(next)), describe(days))
    }

    @Test
    fun aBuildOfTheSameCutoffsInTheSameProcessDoesNotReadTheDiskAgainButIsWritten() {
        val first = Process()
        runBlocking { first.service.refresh(ACCOUNT, LIBRARIES) }
        first.days()
        val reads = disk.reads
        // A lineup edit that changes a day: the days are built again from the snapshots this process holds.
        runBlocking { first.service.edit(ACCOUNT, LineupEdit.SwitchOff(LineupIds.starter("laughs"))) }
        first.days()

        assertEquals(reads, disk.reads, "the disk was not read again")
        assertEquals(listOf(true, false), first.builds.map { it.diskMs >= 0 }, "only the first build looked")
        assertEquals(2, first.events.count { it is DaySnapshotEvent.Saved }, "both builds were written")
    }

    @Test
    fun aDiskHitReportsItsTimeAsDiskMs() {
        firstProcess()
        val next = Process()
        next.days()
        val hit = next.builds.single()
        assertEquals(hit.ms, hit.diskMs)
        assertEquals(0, hit.snapshotsMs)
        assertEquals(0, hit.poolsMs)
    }

    @Test
    fun forgettingTheLineupForgetsItsDays() {
        firstProcess()
        val next = Process()
        runBlocking { next.service.forget(ACCOUNT) }
        assertTrue(disk.files.isEmpty())
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
