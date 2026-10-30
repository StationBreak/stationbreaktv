// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@Suppress("LargeClass") // One function per trigger and rule of the refresher, over one shared set of fixtures.
class LineupRefresherTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    private val store = RecordingStore()
    private val events = mutableListOf<LineupEvent>()
    private var offset = 0
    private var libraries: Set<ItemId>? = LIBRARIES
    private val job = Job()

    // Whether a sync run is writing the library (#434): off unless a test says otherwise.
    private val writing = MutableStateFlow(false)
    private val sync = SyncActivity { writing }
    private val scope = CoroutineScope(job)

    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    private fun serviceOver(lib: LibraryRepository): LineupService {
        val report: (LineupEvent) -> Unit = { events += it }
        return LineupService(store, lib, clock, { offset }, report, TestCatalog)
    }

    private val service = serviceOver(library)

    private fun refresher(
        lib: LibraryRepository = library,
        backoffMs: Long = LineupRefresher.INITIAL_BACKOFF_MS,
    ) = LineupRefresher(service, lib, { libraries }, sync, backoffMs) { events += it }

    private val refreshes get() = library.snapshotCutoffs.size

    @AfterTest
    fun stop() {
        job.cancel()
    }

    private suspend fun until(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(WAIT_MS) { while (!condition()) delay(10) }
        check(condition()) { what }
    }

    private suspend fun settle() = delay(SETTLE_MS)

    @Test
    fun refreshesAtStartWhenAbsent() =
        runBlocking<Unit> {
            refresher().start(scope)

            until("refreshed") { refreshes >= 1 }
            settle()
            assertEquals(1, refreshes)
            assertTrue(store.inner.peek(ACCOUNT) != null, "and saved")
        }

    @Test
    fun successThenPassFinalisesWithCollections() =
        runBlocking<Unit> {
            // The real order: the first sync's success lands before the collection pass does.
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            refresher().start(scope)
            until("start refresh") { refreshes >= 1 }
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:00Z")))
            until("success refresh") { refreshes >= 2 }
            settle()

            val waiting = checkNotNull(service.loaded(ACCOUNT)).lineup
            assertEquals(false, waiting.finalised)
            assertTrue(waiting.channels.none { it.state == ChannelState.On }, "nothing is on yet")
            assertEquals(false, saved().finalised, "saved as not final")

            library.delegate.setCollectionsSynced(ACCOUNT, true)
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:01:00Z"))
            until("finalised") { service.loaded(ACCOUNT)?.lineup?.finalised == true }

            val done = checkNotNull(service.loaded(ACCOUNT)).lineup
            assertTrue(done.channels.any { it.state == ChannelState.On })
            assertTrue(saved().finalised, "saved as final")
        }

    private fun saved(): Lineup = savedFile().lineup

    private fun savedMeta(): RefreshMeta = savedFile().meta

    private fun savedFile(): LineupCodec.Decoded.Loaded =
        LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded

    @Test
    fun refreshesAtStartWhenCatalogBehind() =
        runBlocking<Unit> {
            // A newer catalog is taken up only once the collections are in.
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            val behind = Lineup(SERVER, USER, 0, 0, finalised = true, channels = emptyList())
            store.inner.save(ACCOUNT, LineupCodec.encode(behind))

            refresher().start(scope)

            until("refreshed") { refreshes >= 1 }
            until("published") { service.loaded(ACCOUNT)?.lineup?.catalogVersion == 1 }
        }

    @Test
    fun refreshesAtStartWhenUnfinalised() =
        runBlocking<Unit> {
            val unfinalised = Lineup(SERVER, USER, 1, 0, finalised = false, channels = emptyList())
            store.inner.save(ACCOUNT, LineupCodec.encode(unfinalised))

            refresher().start(scope)

            until("refreshed") { refreshes >= 1 }
        }

    @Test
    fun doesNotRefreshAtStartWhenCurrent() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes

            refresher().start(scope)
            settle()

            assertEquals(before, refreshes)
        }

    private fun starterGrids(lineup: Lineup): List<Boolean?> =
        lineup.channels.mapNotNull { (it.source as? ChannelSource.Starter)?.rules?.timing?.alignToGrid }

    @Test
    fun aStableLibraryStillMigratesUneditedStartersWithoutAlignToGrid() =
        runBlocking<Unit> {
            lastRunSettled(Instant.parse("2026-10-19T09:00:00Z"))
            val current = savedFile()
            assertTrue(starterGrids(current.lineup).isNotEmpty(), "the fixture has starters")
            // The lineup as an older app stored it: starters with no alignToGrid. The library has not changed.
            val old =
                current.lineup.copy(
                    channels =
                        current.lineup.channels.map {
                            val source = it.source as? ChannelSource.Starter ?: return@map it
                            val timing = source.rules.timing.copy(alignToGrid = null)
                            it.copy(source = source.copy(rules = source.rules.copy(timing = timing)))
                        },
                )
            store.inner.save(ACCOUNT, LineupCodec.encode(old, current.meta))
            assertTrue(starterGrids(saved()).isEmpty(), "stored without alignToGrid")
            val before = refreshes

            relaunch().start(scope)
            until("the migration refresh") { refreshes > before }
            settle()
            assertEquals(before + 1, refreshes, "one refresh")
            assertTrue(starterGrids(saved()).all { it == false })
            assertEquals(old.channels.size, saved().channels.size)

            job.cancelChildren()
            relaunch().start(scope)
            settle()
            assertEquals(before + 1, refreshes, "the second start has nothing left to do")
        }

    // Root cause (#309), as the gate device showed it: after a force-stop the collection-pass signal replays the
    // pass the last run already acted on, and the launch sync then completes a new pass that changed nothing.
    private suspend fun lastRunSettled(pass: Instant) {
        library.delegate.setCollectionsSynced(ACCOUNT, true)
        library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T08:00:00Z")))
        library.delegate.completeCollectionPass(ACCOUNT, pass)
        refresher().start(scope)
        until("the last run planned and finalised") { service.loaded(ACCOUNT)?.lineup?.finalised == true }
        settle()
        job.cancelChildren()
    }

    private fun relaunch(): LineupRefresher {
        // A new process: a new service over the same store and library, and a new refresher.
        val fresh = serviceOver(library)
        return LineupRefresher(fresh, library, { libraries }, sync) { events += it }
    }

    @Test
    fun aWarmRelaunchWithNoChangeRunsNoRefresh() =
        runBlocking<Unit> {
            lastRunSettled(Instant.parse("2026-10-19T09:00:00Z"))
            val before = refreshes

            relaunch().start(scope)
            settle()

            assertEquals(before, refreshes, "the replayed pass was acted on by the last run")
        }

    @Test
    fun aWarmRelaunchRefreshesNothingWhenTheLaunchSyncChangedNothing() =
        runBlocking<Unit> {
            lastRunSettled(Instant.parse("2026-10-19T09:00:00Z"))
            val before = refreshes
            relaunch().start(scope)
            settle()

            // The launch sync: a reconcile that finds nothing, then a collection pass that writes nothing but still
            // records its start as the new pass.
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T12:00:00Z"))
            settle()

            assertEquals(before, refreshes, "a pass that changed nothing needs no refresh")
            val recorded = Instant.parse("2026-10-19T12:00:00Z").toEpochMilliseconds()
            assertEquals(recorded, savedMeta().collectionPassActedOn, "but the lineup says it holds that pass")

            // The next start replays that pass, not the one before it.
            job.cancelChildren()
            relaunch().start(scope)
            settle()
            assertEquals(before, refreshes, "and so the next start finds nothing new either")
        }

    @Test
    fun aStartRefreshAndTheReplayedPassAreOneRefresh() =
        runBlocking<Unit> {
            // First setup after the pass landed: nothing stored, so the start check refreshes, and the pass signal
            // then replays the pass that refresh already holds.
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T09:00:00Z"))

            refresher().start(scope)
            until("refreshed") { refreshes >= 1 }
            settle()

            assertEquals(1, refreshes)
            assertEquals(Instant.parse("2026-10-19T09:00:00Z").toEpochMilliseconds(), savedMeta().collectionPassActedOn)
        }

    @Test
    fun theActedOnPassIsKeptWithTheLineup() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(null, savedMeta().collectionPassActedOn, "no pass was in")
            refresher().start(scope)
            settle()

            val pass = Instant.parse("2026-10-19T10:00:00Z")
            library.delegate.completeCollectionPass(ACCOUNT, pass)
            until("recorded") { savedMeta().collectionPassActedOn == pass.toEpochMilliseconds() }
            assertEquals(pass.toEpochMilliseconds(), checkNotNull(service.loaded(ACCOUNT)).meta.collectionPassActedOn)
        }

    @Test
    fun aPassWhoseRefreshFailedIsNotRecordedAndTheNextStartTriesItAgain() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            val earlier = Instant.parse("2026-10-19T09:00:00Z")
            lastRunSettled(earlier)
            val pass = Instant.parse("2026-10-19T12:00:00Z")
            val flaky = FailingPlanningRead(library)
            LineupRefresher(serviceOver(flaky), flaky, { libraries }, sync) { events += it }.start(scope)
            settle()

            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.completeCollectionPass(ACCOUNT, pass)
            until("the failure was reported") { LineupEvent.RefreshFailed in events }
            settle()
            job.cancelChildren()

            val kept = savedMeta().collectionPassActedOn
            assertEquals(earlier.toEpochMilliseconds(), kept, "the failed pass is not recorded")
            val before = refreshes
            relaunch().start(scope)
            until("the next start refreshed") { refreshes == before + 1 }
            until("and recorded the pass") { savedMeta().collectionPassActedOn == pass.toEpochMilliseconds() }
        }

    @Test
    fun twoFailedPassesFinaliseTheFirstLineupWhicheverTriggerComesFirst() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            refresher().start(scope)
            until("start refresh") { refreshes >= 1 }
            // The refresh counts when it reads the snapshot; wait for its save before looking at the lineup.
            until("start refresh saved") { service.loaded(ACCOUNT) != null }
            assertEquals(false, service.loaded(ACCOUNT)?.lineup?.finalised)

            // The second pass fails after the start refresh: nothing announces it, and the next sync is the trigger.
            library.delegate.setFailedCollectionPasses(ACCOUNT, 2)
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:00Z")))
            until("finalised without the collections") { service.loaded(ACCOUNT)?.lineup?.finalised == true }

            // The collections arrive later and reach the lineup, which has taken in no pass yet.
            val before = refreshes
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            val pass = Instant.parse("2026-10-20T09:00:00Z")
            library.delegate.completeCollectionPass(ACCOUNT, pass)
            until("the pass was taken in") {
                service.loaded(ACCOUNT)?.meta?.collectionPassActedOn == pass.toEpochMilliseconds()
            }
            settle()
            assertEquals(before + 1, refreshes, "one refresh took the pass in")
        }

    @Test
    fun aSyncThatWroteRowsBeforeProcessDeathRefreshesOnTheNextStart() =
        runBlocking<Unit> {
            lastRunSettled(Instant.parse("2026-10-19T09:00:00Z"))
            val before = refreshes
            // The last process died after the sync wrote rows and before the refresh: the library's generation moved
            // on, the lineup's stored one did not.
            library.delegate.noteContentChange(ACCOUNT)
            val planned = savedMeta().plannedGeneration

            relaunch().start(scope)
            until("the next start refreshed") { refreshes == before + 1 }
            settle()

            assertEquals(before + 1, refreshes, "once")
            assertEquals(library.delegate.contentGeneration(ACCOUNT), savedMeta().plannedGeneration)
            assertTrue(savedMeta().plannedGeneration != planned)
        }

    @Test
    fun aChangedLibrarySelectionRefreshesWithoutASync() =
        runBlocking<Unit> {
            lastRunSettled(Instant.parse("2026-10-19T09:00:00Z"))
            val before = refreshes
            val other = setOf(ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0"))
            libraries = LIBRARIES + other

            relaunch().start(scope)
            until("the next start refreshed") { refreshes == before + 1 }
            settle()

            assertEquals(before + 1, refreshes, "once")
            assertEquals((LIBRARIES + other).map { it.value }.sorted(), savedMeta().plannedLibraries)
        }

    @Test
    fun aSelectionChangedWhileRunningIsSeenByTheNextTrigger() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher().start(scope)
            settle()
            assertEquals(before, refreshes)

            libraries = LIBRARIES + setOf(ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0"))
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:00Z")))

            until("refreshed for the new choice") { refreshes == before + 1 }
        }

    @Test
    fun refreshesAfterCollectionPass() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher().start(scope)
            settle()
            assertEquals(before, refreshes, "null is no trigger")

            val first = Instant.parse("2026-10-19T10:00:00Z")
            library.delegate.completeCollectionPass(ACCOUNT, first)
            until("first pass") { refreshes == before + 1 }

            library.delegate.completeCollectionPass(ACCOUNT, first)
            settle()
            assertEquals(before + 1, refreshes, "the same value again is no trigger")

            // A later pass that wrote nothing is no news: the lineup has these collections.
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:30:00Z"))
            settle()
            assertEquals(before + 1, refreshes, "a pass that changed nothing is no trigger")

            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T11:00:00Z"))
            until("second pass") { refreshes == before + 2 }
        }

    @Test
    fun refreshesWhenLastSuccessAdvances() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher().start(scope)
            settle()

            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:00Z")))
            until("first success") { refreshes == before + 1 }

            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T11:00:00Z")))
            until("second success") { refreshes == before + 2 }
        }

    // #434: one stay-current run fired a refresh at its success and another at its collection pass, and its studios
    // re-read wrote after both. The triggers its writes cause are held while it writes and checked once at its end.
    @Test
    fun aSyncRunIsTakenInByOneRefreshWhenItEnds() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T09:00:00Z"))
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher().start(scope)
            settle()
            assertEquals(before, refreshes, "current at start")

            // One run: the incremental pass, then the collection pass, then the studios re-read, each writing rows.
            writing.value = true
            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:00Z")))
            val pass = Instant.parse("2026-10-19T10:01:00Z")
            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.completeCollectionPass(ACCOUNT, pass)
            library.delegate.noteContentChange(ACCOUNT)
            settle()
            assertEquals(before, refreshes, "nothing is planned while the run writes")

            writing.value = false
            until("refreshed when the run ended") { refreshes == before + 1 }
            settle()

            assertEquals(before + 1, refreshes, "one refresh for the whole run")
            val generation = library.delegate.contentGeneration(ACCOUNT)
            assertEquals(generation, savedMeta().plannedGeneration, "its last write too")
            assertEquals(pass.toEpochMilliseconds(), savedMeta().collectionPassActedOn)
        }

    // The first sync and the stay-current runs are two runners behind one signal (#480): the refresher cannot tell
    // which of them is writing.
    @Test
    fun aFirstSyncIsHeldAndTakenInByOneRefreshWhenItStops() =
        runBlocking<Unit> {
            val firstSync = MutableStateFlow(false)
            val before = startCurrentWith(firstSync, MutableStateFlow(false))

            firstSync.value = true
            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:00Z")))
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:01:00Z"))
            library.delegate.noteContentChange(ACCOUNT)
            settle()
            assertEquals(before, refreshes, "nothing is planned while the first sync writes")

            firstSync.value = false
            until("refreshed when the first sync stopped") { refreshes == before + 1 }
            settle()
            assertEquals(before + 1, refreshes, "one refresh for the whole first sync")
        }

    @Test
    fun twoRunnersWritingOneAfterTheOtherAreOneRefresh() =
        runBlocking<Unit> {
            val firstSync = MutableStateFlow(false)
            val stayCurrent = MutableStateFlow(false)
            val before = startCurrentWith(firstSync, stayCurrent)

            // The second starts before the first has stopped: the writing never stops in between.
            firstSync.value = true
            library.delegate.noteContentChange(ACCOUNT)
            stayCurrent.value = true
            firstSync.value = false
            library.delegate.noteContentChange(ACCOUNT)
            settle()
            assertEquals(before, refreshes, "held while either writes")

            stayCurrent.value = false
            until("refreshed when both had stopped") { refreshes == before + 1 }
            settle()
            assertEquals(before + 1, refreshes, "one refresh for both")
        }

    @Test
    fun theCombinedSignalGivesItsCurrentValueFirstAndStaysOnWhileEitherWrites() =
        runBlocking<Unit> {
            val firstSync = MutableStateFlow(true)
            val stayCurrent = MutableStateFlow(false)
            val seen = mutableListOf<Boolean>()

            @Suppress("InjectDispatcher") // A test: Unconfined sees each value as it is set.
            val watching =
                launch(Dispatchers.Unconfined) {
                    eitherWriting(firstSync, stayCurrent).writing().collect { seen += it }
                }
            stayCurrent.value = true
            firstSync.value = false
            stayCurrent.value = false
            watching.cancel()

            // Repeats collapse (each change emits again); a drop to false between the runners would show.
            val changes = seen.filterIndexed { i, on -> i == 0 || on != seen[i - 1] }
            assertEquals(listOf(true, false), changes, "on from the start, off only when both stopped")
        }

    // A current lineup and a refresher listening to [firstSync] and [stayCurrent]; returns the refreshes so far.
    private suspend fun startCurrentWith(
        firstSync: MutableStateFlow<Boolean>,
        stayCurrent: MutableStateFlow<Boolean>,
    ): Int {
        library.delegate.setCollectionsSynced(ACCOUNT, true)
        library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T09:00:00Z"))
        service.refresh(ACCOUNT, LIBRARIES)
        val before = refreshes
        LineupRefresher(service, library, { libraries }, eitherWriting(firstSync, stayCurrent)) { events += it }
            .start(scope)
        settle()
        assertEquals(before, refreshes, "current at start")
        return before
    }

    @Test
    fun aSyncRunThatChangedNothingRefreshesNothingAndRecordsItsPass() =
        runBlocking<Unit> {
            lastRunSettled(Instant.parse("2026-10-19T09:00:00Z"))
            val before = refreshes
            relaunch().start(scope)
            settle()

            writing.value = true
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T11:00:00Z")))
            val pass = Instant.parse("2026-10-19T11:01:00Z")
            library.delegate.completeCollectionPass(ACCOUNT, pass)
            settle()
            writing.value = false
            until("the pass recorded") { savedMeta().collectionPassActedOn == pass.toEpochMilliseconds() }
            settle()

            assertEquals(before, refreshes, "a run that wrote nothing needs no refresh")
        }

    @Test
    fun aStartThatFindsTheLineupBehindRefreshesAtOnceEvenWhileASyncWrites() =
        runBlocking<Unit> {
            // An earlier process died before its end-of-run refresh: the stored lineup is behind the library.
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T09:00:00Z"))
            service.refresh(ACCOUNT, LIBRARIES)
            library.delegate.noteContentChange(ACCOUNT)
            val before = refreshes

            // This process starts while its own run is already writing.
            writing.value = true
            relaunch().start(scope)
            until("refreshed at start") { refreshes == before + 1 }

            writing.value = false
            settle()
            assertEquals(before + 1, refreshes, "the run wrote nothing more: the end check finds the lineup current")

            writing.value = true
            library.delegate.noteContentChange(ACCOUNT)
            settle()
            writing.value = false
            until("a run that wrote more refreshes at its end") { refreshes == before + 2 }
        }

    @Test
    fun aSuccessfulSyncThatChangedNothingRefreshesNothing() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T09:00:00Z"))
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher().start(scope)
            settle()

            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:00Z")))
            settle()
            assertEquals(before, refreshes, "the sync wrote nothing")

            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T11:00:00Z")))
            until("a sync that changed items refreshed") { refreshes == before + 1 }
            settle()

            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T12:00:00Z")))
            settle()
            assertEquals(before + 1, refreshes, "the change was taken in by that refresh")
        }

    @Test
    fun doesNotRefreshOnFailureOrRunning() =
        runBlocking<Unit> {
            val success = Instant.parse("2026-10-19T10:00:00Z")
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, success))
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher().start(scope)
            settle()

            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), success))
            settle()
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.FirstSyncRunning(3, 10), success))
            settle()

            assertEquals(before, refreshes)
        }

    @Test
    fun refreshesAreSerialised() =
        runBlocking<Unit> {
            val slow = SlowLibrary(library)
            service.refresh(ACCOUNT, LIBRARIES)
            LineupRefresher(serviceOver(slow), slow, { libraries }, sync) { events += it }.start(scope)
            settle()

            // Both signals at once: the two refreshes they ask for must not overlap.
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:00:00Z"))
            delay(SLOW_MS / 4)
            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:01Z")))
            until("both ran") { slow.completed.get() >= 2 }
            settle()

            assertEquals(1, slow.maxInFlight.get(), "never two at once")
        }

    @Test
    fun aFailingRefreshIsReportedAndTheRefresherKeepsListening() =
        runBlocking<Unit> {
            // Unfinalised, so planning takes the device offset, and 7 minutes is not one the rules allow.
            val unfinalised = Lineup(SERVER, USER, 1, 0, finalised = false, channels = emptyList())
            store.inner.save(ACCOUNT, LineupCodec.encode(unfinalised))
            offset = 7

            refresher().start(scope)
            until("failure reported") { LineupEvent.RefreshFailed in events }

            offset = 0
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:00:00Z"))
            until("a later trigger refreshed") { refreshes >= 1 }
            until("finalised") { service.loaded(ACCOUNT)?.lineup?.finalised == true }
        }

    @Test
    fun aTriggerFlowThatThrowsOnceIsRetriedAndStillRefreshes() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher(ThrowingOncePasses(library), backoffMs = 10).start(scope)
            until("the failure was reported") { LineupEvent.RefreshFailed in events }

            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:00:00Z"))

            until("a trigger after the failure refreshed") { refreshes == before + 1 }
            assertEquals(1, events.count { it == LineupEvent.RefreshFailed }, "reported once, for the one failure")
        }

    @Test
    fun aPersistentlyFailingSourceWithAStaleLineupBacksOffInsteadOfSpinning() =
        runTest {
            libraries = null
            val failedAt = mutableListOf<Long>()
            val report: (LineupEvent) -> Unit = {
                if (it == LineupEvent.RefreshFailed) failedAt += testScheduler.currentTime
            }
            // No libraries are chosen, so nothing is ever planned and the lineup stays stale: every resubscription
            // emits Start again; the collection-pass source always throws.
            val refresher = LineupRefresher(service, AlwaysThrowingPasses(library), { libraries }, sync, 1_000L, report)

            refresher.start(backgroundScope)
            testScheduler.advanceTimeBy(15_010L)
            testScheduler.runCurrent()

            val gaps = failedAt.zipWithNext { a, b -> b - a }
            // Each gap is the wait (1 s, 2 s, 4 s, 8 s) plus the 1 ms the source takes to fail.
            assertEquals(listOf(1_001L, 2_001L, 4_001L, 8_001L), gaps)
        }

    @Test
    fun theBackoffStartsOverAfterATriggerGotThrough() {
        val backoff = LineupRefresher.Backoff(LineupRefresher.INITIAL_BACKOFF_MS)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), List(3) { backoff.next() })
        backoff.reset()
        assertEquals(1_000L, backoff.next(), "fail, succeed, fail again: the second failure waits the initial time")
        assertEquals(2_000L, backoff.next())
    }

    @Test
    fun theBackoffDoublesFromOneSecondToFiveMinutes() {
        val waits = (0L..12L).map { LineupRefresher.backoffMs(it, LineupRefresher.INITIAL_BACKOFF_MS) }
        val doubling = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L)
        assertEquals(doubling, waits.take(9))
        assertEquals(List(4) { 300_000L }, waits.drop(9), "capped at five minutes")
        assertEquals(300_000L, LineupRefresher.backoffMs(500, LineupRefresher.INITIAL_BACKOFF_MS), "no overflow")
    }

    @Test
    fun nothingRefreshesWithoutAnAccountOrLibraries() =
        runBlocking<Unit> {
            library.delegate.forget(ACCOUNT)
            refresher().start(scope)
            settle()
            assertEquals(0, refreshes)

            libraries = null
            library.delegate.useAccount(ACCOUNT)
            settle()
            assertEquals(0, refreshes, "no libraries chosen yet")

            libraries = emptySet()
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:00:00Z"))
            settle()
            assertEquals(0, refreshes, "an empty choice is no libraries")
        }

    @Test
    fun librariesThrowingOnceDoesNotStopTheRefresher() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            var throwOnce = false
            LineupRefresher(
                service,
                library,
                {
                    if (throwOnce) {
                        throwOnce = false
                        error("the settings could not be read")
                    }
                    libraries
                },
                sync,
            ) { events += it }.start(scope)
            settle()
            throwOnce = true

            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:00:00Z"))
            until("failure reported") { LineupEvent.RefreshFailed in events }
            assertEquals(before, refreshes)

            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T11:00:00Z"))
            until("a later trigger refreshed") { refreshes == before + 1 }
        }

    @Test
    fun anAccountSwitchNeverRefreshesTheOldAccountWithTheNewOnesLibraries() =
        runBlocking<Unit> {
            val other = LibraryAccount(ServerId(SERVER.value), UserId("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"))
            val otherLibraries = setOf(ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0"))
            val seen = RecordingLibrary(library)
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            LineupRefresher(serviceOver(seen), seen, {
                // The read is pending for the first account; the app switches account meanwhile, and the
                // read then answers with the new account's choice.
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                otherLibraries
            }, sync) { events += it }.start(scope)
            withTimeout(WAIT_MS) { entered.await() }

            library.delegate.useAccount(other)
            delay(SETTLE_MS)
            gate.complete(Unit)
            until("the new account was refreshed") { seen.reads.any { it.first == other } }
            settle()

            assertEquals(emptyList(), seen.reads.filter { it.first == ACCOUNT }, "the old account is never read")
        }

    @Test
    fun startingTwiceStartsOnce() =
        runBlocking<Unit> {
            val slow = SlowLibrary(library)
            val refresher = LineupRefresher(serviceOver(slow), slow, { libraries }, sync) { events += it }
            refresher.start(scope)
            refresher.start(scope)
            until("refreshed") { slow.completed.get() >= 1 }
            settle()

            assertEquals(1, slow.completed.get())
        }

    @Test
    fun aSelectionAlreadyForTheNewAccountIsNeverUsedForTheOldOne() =
        runBlocking<Unit> {
            val other = LibraryAccount(ServerId(SERVER.value), UserId("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"))
            val otherLibraries = setOf(ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0"))
            val seen = RecordingLibrary(library)
            // The account flow the refresher listens to has not delivered the new account yet, but the port already
            // answers it when asked again, and the selection is already the new account's.
            val lagging = LaggingActiveAccount(seen, stale = ACCOUNT, now = other)
            LineupRefresher(serviceOver(seen), lagging, { otherLibraries }, sync) { events += it }.start(scope)
            settle()

            assertEquals(emptyList(), seen.reads, "no refresh with the other account's libraries")
        }

    // Ruling 2: the lineup is not part of the session's saved library. Signing out, or a rejected session, forgets the
    // saved library (`AccountCache.forget`) and leaves the lineup file alone.
    @Test
    fun sessionExpiryKeepsTheLineup() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val saved = checkNotNull(store.inner.peek(ACCOUNT))
            val info = ServerInfo("Home", ServerId(SERVER.value), ServerVersion(12, 2, 0))
            val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
            val session = Session(UserId(USER.value), AccessToken("fake-token"), info, address, null, false)
            val server = FakeMediaServer()
            val sessions = SessionRepository(InMemorySecretStore(), server, library.delegate)

            sessions.save(session)
            server.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())
            sessions.restore(address)
            sessions.signOut(session)

            assertTrue(library.delegate.forgotten.isNotEmpty(), "the saved library was forgotten")
            assertTrue(saved.contentEquals(store.inner.peek(ACCOUNT)), "the lineup file is untouched")
            assertTrue(store.calls.none { it == "forget" || it == "setAside" }, "the store was never told")
            assertTrue(store.inner.load(ACCOUNT) is StoredBytes.Present)
        }
}

private const val WAIT_MS = 10_000L
private const val SETTLE_MS = 250L

/** A library whose first planning read fails, and later ones do not. */
private class FailingPlanningRead(
    private val inner: CountingLibrary,
) : LibraryRepository by inner {
    private val failed = AtomicInteger()

    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        check(failed.getAndIncrement() > 0) { "the planning read failed" }
        return inner.ruleSnapshot(account, libraries, cutoff)
    }
}

/** A library whose planning read takes a moment, and notes how many run at once. */
private class SlowLibrary(
    private val inner: CountingLibrary,
) : LibraryRepository by inner {
    val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val completed = AtomicInteger()

    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            delay(SLOW_MS)
            return inner.ruleSnapshot(account, libraries, cutoff)
        } finally {
            inFlight.decrementAndGet()
            completed.incrementAndGet()
        }
    }
}

private const val SLOW_MS = 80L

/** A library whose collection-pass flow always throws when collected. */
private class AlwaysThrowingPasses(
    inner: CountingLibrary,
) : LibraryRepository by inner {
    override fun collectionPassCompleted(account: LibraryAccount): Flow<Instant?> =
        flow {
            // Long enough for the start check to emit its Start trigger first, as it does on every resubscription.
            delay(1)
            error("the trigger flow failed")
        }
}

/** A library whose first collection-pass flow throws when collected; later collections are the real one. */
private class ThrowingOncePasses(
    private val inner: CountingLibrary,
) : LibraryRepository by inner {
    private val collected = AtomicInteger()

    override fun collectionPassCompleted(account: LibraryAccount): Flow<Instant?> =
        flow {
            check(collected.getAndIncrement() > 0) { "the trigger flow failed" }
            inner.collectionPassCompleted(account).collect { emit(it) }
        }
}

/** A library that notes which account and libraries each planning read was for. */
private class RecordingLibrary(
    private val inner: CountingLibrary,
) : LibraryRepository by inner {
    val reads: MutableList<Pair<LibraryAccount, Set<ItemId>>> = CopyOnWriteArrayList()

    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        reads += account to libraries
        return inner.ruleSnapshot(account, libraries, cutoff)
    }
}

/** A library whose first [activeAccount] flow is stale (it never moves on), while any later ask gives the true one. */
private class LaggingActiveAccount(
    private val inner: LibraryRepository,
    private val stale: LibraryAccount,
    private val now: LibraryAccount,
) : LibraryRepository by inner {
    private val asked = AtomicInteger()

    override fun activeAccount(): Flow<LibraryAccount?> =
        if (asked.getAndIncrement() == 0) {
            flow {
                emit(stale)
                awaitCancellation()
            }
        } else {
            flowOf(now)
        }
}
