// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.rules.Source
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The debug command that rebuilds today's schedule now (#812): it plans even when the lineup is current, drops every
 * built day (memory and disk) so they are built again, keeps the viewer's own edits, and does nothing when nobody is
 * signed in or no library is chosen. A plain refresh is unchanged: it leaves current days alone.
 */
class LineupRebuildTodayTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val lineups = RecordingStore()
    private val disk = MemoryDayStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val builds: MutableList<DayBuild> = Collections.synchronizedList(mutableListOf())
    private val service = LineupService(lineups, library, clock, { 0 }, {}, LineupSeams(TestCatalog, derived = disk))
    private val source =
        BuiltInScheduleSource(
            service,
            library,
            built = { builds += it },
            persisted = PersistedDays(disk, "1:100", {}),
        ) { selectionOf(LIBRARIES) }
    private val idle = SyncActivity { MutableStateFlow(false) }
    private var libraries: Set<ItemId>? = LIBRARIES
    private val refresher = LineupRefresher(service, library, { libraries }, idle, NO_CHANGES, report = {})

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    private fun days() = runBlocking { source.channelDays(ACCOUNT, LIBRARIES, NOON).also { source.awaitSaves() } }

    private fun stored(): Lineup {
        val bytes = checkNotNull(lineups.inner.peek(ACCOUNT))
        return checkNotNull((LineupCodec.decode(bytes) as? LineupCodec.Decoded.Loaded)?.lineup)
    }

    @Test
    fun aCurrentLineupIsPlannedAgainAndItsDaysAreBuiltAgain() =
        runBlocking<Unit> {
            refresher.rebuildToday()
            days()
            assertEquals(1, builds.size)
            val revision = service.loaded(ACCOUNT)!!.revision
            val reads = library.snapshotCutoffs.size

            // A plain refresh of a current lineup leaves the kept days alone: they are what a tune finds.
            service.refresh(ACCOUNT, LIBRARIES)
            days()
            assertEquals(1, builds.size, "a refresh keeps the days")
            val afterRefresh = service.loaded(ACCOUNT)!!.revision

            assertEquals(RebuildTodayResult.Done, refresher.rebuildToday())
            days()

            assertEquals(2, builds.size, "the days were built again")
            assertNotEquals("disk", builds.last().snapshot, "not taken from the days on disk")
            assertTrue(library.snapshotCutoffs.size > reads, "the plan read the library")
            assertTrue(service.loaded(ACCOUNT)!!.revision > afterRefresh, "a new revision, so the channel retunes")
            assertTrue(afterRefresh > revision)
        }

    @Test
    fun theDaysOnDiskAreDroppedSoAStaleFileIsNeverTakenBack() =
        runBlocking<Unit> {
            refresher.rebuildToday()
            days()
            assertTrue(disk.files.isNotEmpty(), "the cold build was written")
            val names = disk.files.keys.toSet()

            refresher.rebuildToday()
            assertTrue(disk.files.isEmpty(), "forgotten with the lineup's other derived days")
            days()

            assertEquals(names, disk.files.keys.toSet(), "and written again by the new build")
            assertNotEquals("disk", builds.last().snapshot)
        }

    @Test
    fun aRebuildDeletesTheDaysWithoutRefusingTheNextSave() =
        runBlocking<Unit> {
            refresher.rebuildToday()
            days()
            val names =
                disk.files.keys
                    .map { it.second }
                    .toSet()
            assertTrue(names.isNotEmpty())

            refresher.rebuildToday()

            assertEquals(0, disk.forgets.get(), "a rebuild is not a sign-out: the account is not tombstoned")
            assertTrue(disk.files.isEmpty(), "the old days are deleted")
            assertTrue(disk.write(ACCOUNT, "probe", byteArrayOf(1)), "a later write is accepted (#815)")
            days()
            assertEquals(
                names,
                disk.files.keys
                    .map { it.second }
                    .toSet() - "probe",
                "the rebuilt day is saved",
            )
        }

    @Test
    fun aResetDeletesTheDaysWithoutRefusingTheNextSave() =
        runBlocking<Unit> {
            refresher.rebuildToday()
            days()
            assertTrue(disk.files.isNotEmpty())

            assertIs<Saved.Ok<Lineup>>(service.reset(ACCOUNT, LIBRARIES))

            assertEquals(0, disk.forgets.get())
            assertTrue(disk.files.isEmpty(), "the old days are deleted")
            assertTrue(disk.write(ACCOUNT, "probe", byteArrayOf(1)), "a later write is accepted (#815)")
        }

    @Test
    fun forgettingTheLineupStillForgetsTheDaysStore() =
        runBlocking<Unit> {
            refresher.rebuildToday()
            days()

            service.forget(ACCOUNT)

            assertEquals(1, disk.forgets.get())
            assertTrue(disk.files.isEmpty())
        }

    // A lineup planned under an older catalog whose Whodunit still has rules that match nothing: the newer catalog's
    // rules for it (Crime) are a change the plan makes, which a refresh starts at the next boundary.
    private fun seedStale() =
        runBlocking {
            val old =
                lineupOf(
                    starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
                    starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Mystery"))),
                ).copy(catalogVersion = 0)
            lineups.inner.save(ACCOUNT, LineupCodec.encode(old))
        }

    private fun whodunit(): ChannelSource.Starter =
        checkNotNull(
            stored().channels.first { it.id.value == "starter:whodunit" }.source as? ChannelSource.Starter,
        )

    private fun whodunitToday() = days().first { it.channel.id.value == "starter:whodunit" }

    @Test
    fun aRefreshStartsThePlansChangeAtTheNextBoundary() =
        runBlocking<Unit> {
            seedStale()

            service.refresh(ACCOUNT, LIBRARIES)

            assertTrue(whodunit().rulesFromMs!! > NOON.toEpochMilliseconds(), "the new rules wait for tomorrow")
            assertTrue(whodunitToday().pool.isEmpty(), "so today still airs the old ones")
        }

    @Test
    fun aRebuildAppliesThePlansChangeFromTheStartOfToday() =
        runBlocking<Unit> {
            seedStale()

            assertEquals(RebuildTodayResult.Done, refresher.rebuildToday())

            assertNull(whodunit().rulesFromMs, "the new rules began with today, so no boundary is waiting")
            assertTrue(whodunitToday().pool.isNotEmpty(), "and today's days are built from them")
            assertTrue(
                stored().channels.count { it.state == ChannelState.On } > 2,
                "the starters the plan picked are on now",
            )
        }

    @Test
    fun theViewersOwnEditsAreKept() =
        runBlocking<Unit> {
            val mine =
                channel(
                    "custom:Mine",
                    600,
                    ChannelState.On,
                    ChannelSource.Custom(rules("Mine", Source.Genre("Comedy"))),
                    onSinceMs = 4_321L,
                )
            val moved = starter("laughs", 150, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
            val off = starter("whodunit", 210, ChannelState.UserHidden, rules("Whodunit", Source.Genre("Crime")))
            lineups.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(moved, off, mine)))

            assertEquals(RebuildTodayResult.Done, refresher.rebuildToday())

            val byId = stored().channels.associateBy { it.id.value }
            assertEquals(mine, byId["custom:Mine"], "the viewer's channel as it was")
            assertEquals(ChannelState.UserHidden, byId.getValue(off.id.value).state, "the viewer's switch-off")
            assertEquals(150, byId.getValue(moved.id.value).number, "the viewer's renumber")
        }

    @Test
    fun nobodySignedInIsAReportedNoOp() =
        runBlocking<Unit> {
            val nobody = CountingLibrary().apply { seed(fillingLibrary()) }
            val bare = LineupService(lineups, nobody, clock, { 0 }, {}, TestCatalog)
            val idle = LineupRefresher(bare, nobody, { LIBRARIES }, idle, NO_CHANGES, report = {})

            assertEquals(RebuildTodayResult.NoAccount, idle.rebuildToday())

            assertNull(lineups.inner.peek(ACCOUNT), "nothing planned or stored")
            assertEquals(0, nobody.snapshotCutoffs.size, "and the library was not read")
        }

    @Test
    fun anAccountWithNoCanonicalIdsHasNoLineupToRebuild() =
        runBlocking<Unit> {
            val odd = LibraryAccount(ServerId("not an id"), UserId("nor this"))

            assertEquals(false, service.rebuildToday(odd, LIBRARIES))
            assertEquals(true, service.rebuildToday(ACCOUNT, LIBRARIES))
        }

    @Test
    fun noLibrariesIsAReportedNoOp() =
        runBlocking<Unit> {
            libraries = null
            assertEquals(RebuildTodayResult.NoLibraries, refresher.rebuildToday())
            libraries = emptySet()
            assertEquals(RebuildTodayResult.NoLibraries, refresher.rebuildToday())

            assertNull(lineups.inner.peek(ACCOUNT))
            assertEquals(0, library.snapshotCutoffs.size)
        }
}
