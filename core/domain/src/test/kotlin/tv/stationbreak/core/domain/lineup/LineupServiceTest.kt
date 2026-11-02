// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class LineupServiceTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val events = mutableListOf<LineupEvent>()
    private var offset = 0
    private var now = NOON

    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private val service = LineupService(store, library, clock, { offset }, { events += it }, TestCatalog)

    private fun encodingWith(encode: (Lineup, RefreshMeta) -> ByteArray) =
        LineupService(store, library, clock, { offset }, { events += it }, LineupSeams(TestCatalog, encode))

    private fun stored(account: LibraryAccount = ACCOUNT): Lineup? =
        (store.inner.peek(account)?.let { LineupCodec.decode(it) } as? LineupCodec.Decoded.Loaded)?.lineup

    private fun current(account: LibraryAccount = ACCOUNT): Lineup? = service.lineup(account).value?.lineup

    @Test
    fun eachCompletedRefreshReportsOneNonNegativeDuration() =
        runBlocking<Unit> {
            val durations = mutableListOf<Long>()
            val seams = LineupSeams(TestCatalog, timed = { durations += it })
            val timedService = LineupService(store, library, clock, { offset }, { events += it }, seams)

            timedService.refresh(ACCOUNT, LIBRARIES)
            assertEquals(1, durations.size)
            timedService.refresh(ACCOUNT, LIBRARIES)

            assertEquals(2, durations.size)
            assertTrue(durations.all { it >= 0 })
        }

    private fun storedMeta(account: LibraryAccount = ACCOUNT): RefreshMeta? =
        (store.inner.peek(account)?.let { LineupCodec.decode(it) } as? LineupCodec.Decoded.Loaded)?.meta

    @Test
    fun aRefreshStoresTheGenerationTheLibrariesAndThePassItPlannedFrom() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            val pass = Instant.parse("2026-10-19T09:00:00Z")
            library.delegate.completeCollectionPass(ACCOUNT, pass)
            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.noteContentChange(ACCOUNT)
            val generation = library.delegate.contentGeneration(ACCOUNT)

            service.refresh(ACCOUNT, LIBRARIES)

            val expected = RefreshMeta(generation, pass.toEpochMilliseconds(), LIBRARIES.map { it.value }.sorted())
            assertEquals(expected, storedMeta())
            assertEquals(expected, service.lineup(ACCOUNT).value?.meta, "and held in memory")
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(expected, storedMeta(), "the same library gives the same meta")
        }

    @Test
    fun aPassIsNotRecordedWhenTheReadLacksItsCollections() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T09:00:00Z"))

            service.refresh(ACCOUNT, LIBRARIES)

            assertNull(storedMeta()?.collectionPassActedOn)
        }

    @Test
    fun aRefreshWithoutAPassKeepsTheOneRecordedBefore() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            val pass = Instant.parse("2026-10-19T09:00:00Z")
            library.delegate.completeCollectionPass(ACCOUNT, pass)
            service.refresh(ACCOUNT, LIBRARIES)

            library.delegate.setCollectionsSynced(ACCOUNT, false)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(pass.toEpochMilliseconds(), storedMeta()?.collectionPassActedOn)
        }

    @Test
    fun notingAPassChangesTheMetaOnlyAndKeepsTheRevision() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T09:00:00Z"))
            service.refresh(ACCOUNT, LIBRARIES)
            val before = checkNotNull(service.lineup(ACCOUNT).value)
            val later = Instant.parse("2026-10-19T12:00:00Z")

            service.notePass(ACCOUNT, later)

            assertEquals(later.toEpochMilliseconds(), storedMeta()?.collectionPassActedOn)
            val after = checkNotNull(service.lineup(ACCOUNT).value)
            assertEquals(before.revision, after.revision)
            assertEquals(before.lineup, after.lineup)
            assertEquals(before.meta.plannedGeneration, after.meta.plannedGeneration)
        }

    @Test
    fun oneInterruptedPassThenANewSyncStillWaits() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            library.delegate.setFailedCollectionPasses(ACCOUNT, 1)

            service.refresh(ACCOUNT, LIBRARIES)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(false, stored()?.finalised, "one pass that did not finish is not a reason to give up")
        }

    @Test
    fun twoFailedPassesFinaliseWithoutCollections() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            library.delegate.setFailedCollectionPasses(ACCOUNT, 2)

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(true, stored()?.finalised)
            assertTrue(checkNotNull(stored()).channels.any { it.state == ChannelState.On })
            assertNull(storedMeta()?.collectionPassActedOn, "and no pass was taken in")
        }

    @Test
    fun theFailureCountDoesNotMatterOnceTheLineupIsFinalised() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            service.refresh(ACCOUNT, LIBRARIES)
            val before = checkNotNull(stored())

            library.delegate.setCollectionsSynced(ACCOUNT, false)
            library.delegate.setFailedCollectionPasses(ACCOUNT, 5)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(before.channels, stored()?.channels)
        }

    private val newerCatalog =
        object : StarterCatalogView by TestCatalog {
            override val version: Int = 2
            override val recipes =
                TestCatalog.recipes.map {
                    if (it.key == "laughs") it.copy(exclusions = listOf(Source.Genre("Kids"))) else it
                }
        }

    private suspend fun finalisedAtCatalogOne() {
        library.delegate.setCollectionsSynced(ACCOUNT, true)
        service.refresh(ACCOUNT, LIBRARIES)
        assertEquals(1, stored()?.catalogVersion)
        library.delegate.setCollectionsSynced(ACCOUNT, false)
    }

    private fun laughsExclusions(lineup: Lineup?): List<Source>? {
        val laughs = checkNotNull(lineup).channels.first { it.id == LineupIds.starter("laughs") }
        return (laughs.source as ChannelSource.Starter).rules.exclusions
    }

    @Test
    fun aFinalisedLineupTakesTheNewerCatalogOnceCollectionPassesKeepFailing() =
        runBlocking<Unit> {
            finalisedAtCatalogOne()
            val newer = LineupService(store, library, clock, { offset }, { events += it }, newerCatalog)

            library.delegate.setFailedCollectionPasses(ACCOUNT, 2)
            newer.refresh(ACCOUNT, LIBRARIES)

            assertEquals(2, stored()?.catalogVersion)
            assertEquals(listOf(Source.Genre("Kids")), laughsExclusions(stored()))
        }

    @Test
    fun aFinalisedLineupWaitsForTheNewerCatalogWhileOnlyOnePassHasFailed() =
        runBlocking<Unit> {
            finalisedAtCatalogOne()
            val newer = LineupService(store, library, clock, { offset }, { events += it }, newerCatalog)

            library.delegate.setFailedCollectionPasses(ACCOUNT, 1)
            newer.refresh(ACCOUNT, LIBRARIES)

            assertEquals(1, stored()?.catalogVersion)
            assertNull(laughsExclusions(stored()), "still the version 1 rules")
        }

    @Test
    fun aFinalisedLineupAlreadyAtTheCurrentCatalogIsUnchangedWhenCollectionPassesKeepFailing() =
        runBlocking<Unit> {
            finalisedAtCatalogOne()
            val before = checkNotNull(stored())

            library.delegate.setFailedCollectionPasses(ACCOUNT, 2)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(before.channels, stored()?.channels)
            assertEquals(before.catalogVersion, stored()?.catalogVersion)
        }

    // The catalog with "laughs" reading the collection named "Synthetic Pack", at [version].
    private fun hintedCatalog(version: Int) =
        object : StarterCatalogView by TestCatalog {
            override val version: Int = version
            override val recipes =
                TestCatalog.recipes.map {
                    if (it.key == "laughs") it.copy(collectionHints = listOf("Synthetic Pack")) else it
                }
        }

    @Test
    fun aGiveUpBumpKeepsTheResolvedCollectionSourcesWhileTheirNamesAreUnchanged() =
        runBlocking<Unit> {
            val pack = id(0x900)
            library.delegate.seedCollectionName(ACCOUNT, pack, "Synthetic Pack")
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            LineupService(store, library, clock, { offset }, { events += it }, hintedCatalog(1))
                .refresh(ACCOUNT, LIBRARIES)
            assertTrue(Source.Collection(pack) in sourcesOfLaughs(stored()), "resolved at version 1")

            // The collection pass keeps failing, and a newer catalog arrives: the lineup gives up waiting and bumps.
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            library.delegate.setFailedCollectionPasses(ACCOUNT, 2)
            LineupService(store, library, clock, { offset }, { events += it }, hintedCatalog(2))
                .refresh(ACCOUNT, LIBRARIES)

            assertEquals(2, stored()?.catalogVersion)
            assertTrue(Source.Collection(pack) in sourcesOfLaughs(stored()), "the collection source survives the bump")
        }

    private fun sourcesOfLaughs(lineup: Lineup?): List<Source> {
        val laughs = checkNotNull(lineup).channels.first { it.id == LineupIds.starter("laughs") }
        return (laughs.source as ChannelSource.Starter).rules.sources
    }

    @Test
    fun aMetaOnlySaveNeverPutsAnOlderFileBackOverNewerChannelsInMemory() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T09:00:00Z"))
            service.refresh(ACCOUNT, LIBRARIES)
            val memory = checkNotNull(service.lineup(ACCOUNT).value).lineup
            // The file falls behind the session (another writer, or a save that was lost): older channels, same meta.
            val older = memory.copy(channels = memory.channels.drop(1))
            store.inner.save(ACCOUNT, LineupCodec.encode(older, checkNotNull(storedMeta())))

            service.notePass(ACCOUNT, Instant.parse("2026-10-19T12:00:00Z"))

            assertEquals(memory, current(), "the session's channels stay")
            assertEquals(memory, stored(), "and are what the file holds now, not the older ones")
        }

    @Test
    fun notingAPassTouchesNothingBeforeALineupHasTakenOneIn() =
        runBlocking<Unit> {
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            service.refresh(ACCOUNT, LIBRARIES)
            val saved = store.inner.peek(ACCOUNT)

            service.notePass(ACCOUNT, Instant.parse("2026-10-19T12:00:00Z"))

            assertContentEquals(saved, store.inner.peek(ACCOUNT))
        }

    @Test
    fun firstRefreshPlansAtNowAndSaves() =
        runBlocking<Unit> {
            offset = -300
            service.refresh(ACCOUNT, LIBRARIES)

            val lineup = checkNotNull(current())
            assertTrue(lineup.finalised)
            assertEquals(-300, lineup.utcOffsetMinutes)
            assertEquals(lineup, stored(), "what is in memory is what was saved")
            assertEquals(listOf(NOON), library.snapshotCutoffs, "one planning read, at now")
            assertEquals(listOf("load", "save"), store.calls)
            assertEquals(emptyList(), events)
        }

    @Test
    fun aStoredLineupIsPlannedFromNotReplaced() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val hidden =
                checkNotNull(current()).let { lineup ->
                    lineup.copy(
                        channels =
                            lineup.channels.map {
                                val laughs = it.id == LineupIds.starter("laughs")
                                if (laughs) it.copy(state = ChannelState.UserHidden) else it
                            },
                    )
                }
            store.inner.save(ACCOUNT, LineupCodec.encode(hidden))

            service.refresh(ACCOUNT, LIBRARIES)

            val laughs = checkNotNull(current()).channels.first { it.id == LineupIds.starter("laughs") }
            assertEquals(ChannelState.UserHidden, laughs.state)
            assertEquals(current(), stored())
        }

    @Test
    fun newerVersionIsNeverOverwritten() =
        runBlocking<Unit> {
            val newer = """{"version":2,"whatever":"a newer app wrote"}""".encodeToByteArray()
            store.inner.save(ACCOUNT, newer)
            store.calls.clear()

            service.refresh(ACCOUNT, LIBRARIES)
            service.refresh(ACCOUNT, LIBRARIES)

            assertContentEquals(newer, store.inner.peek(ACCOUNT))
            assertEquals(listOf("load", "load"), store.calls, "never saved, never set aside")
            assertEquals(List(2) { LineupEvent.NewerVersionLeftAlone }, events)
            assertTrue(checkNotNull(current()).finalised, "the session has a lineup of its own")
        }

    @Test
    fun aFileThatCannotBeReadIsLeftAlone() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val saved = checkNotNull(store.inner.peek(ACCOUNT))
            store.inner.failReads = true
            store.calls.clear()

            service.refresh(ACCOUNT, LIBRARIES)

            assertContentEquals(saved, store.inner.peek(ACCOUNT))
            assertEquals(listOf("load"), store.calls)
            assertEquals(listOf(LineupEvent.ReadFailedLeftAlone), events)
            assertNotNull(current(), "the session keeps a lineup")
        }

    @Test
    fun unreadableFileIsSetAsideBeforeRebuild() =
        runBlocking<Unit> {
            val garbage = "{not a lineup".encodeToByteArray()
            store.inner.save(ACCOUNT, garbage)
            store.calls.clear()

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf("load", "setAside", "save"), store.calls)
            assertContentEquals(garbage, store.inner.setAsideCopy(ACCOUNT))
            assertEquals(current(), stored())
            assertEquals(listOf(LineupEvent.SetAsideUnreadable), events)
        }

    @Test
    fun anUnreadableFileThatCannotBeMovedIsNotWrittenOver() =
        runBlocking<Unit> {
            val garbage = "{not a lineup".encodeToByteArray()
            store.inner.save(ACCOUNT, garbage)
            store.inner.failWrites = true
            store.calls.clear()

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf("load", "setAside"), store.calls)
            assertContentEquals(garbage, store.inner.peek(ACCOUNT))
            assertEquals(listOf(LineupEvent.UnreadableLeftAlone), events)
            assertNotNull(current())
        }

    @Test
    fun anotherAccountsLineupInTheFileIsSetAside() =
        runBlocking<Unit> {
            val other = Lineup(id(9), USER, 1, 0, finalised = false, channels = emptyList())
            store.inner.save(ACCOUNT, LineupCodec.encode(other))
            store.calls.clear()

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf("load", "setAside", "save"), store.calls)
            assertEquals(SERVER, checkNotNull(current()).serverId)
            assertEquals(listOf(LineupEvent.SetAsideUnreadable), events)
        }

    @Test
    fun aFailedSaveIsReportedAndTheLineupKept() =
        runBlocking<Unit> {
            store.inner.failWrites = true

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf(LineupEvent.SaveFailed), events)
            assertNotNull(current())
            assertNull(store.inner.peek(ACCOUNT))
        }

    @Test
    fun eachRefreshIsANewRevision() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val first = checkNotNull(service.lineup(ACCOUNT).value).revision
            service.refresh(ACCOUNT, LIBRARIES)
            assertTrue(checkNotNull(service.lineup(ACCOUNT).value).revision > first)
        }

    @Test
    fun refreshesUnderOneLock() =
        runBlocking<Unit> {
            val gated = GatedLibrary(library)
            val locked = LineupService(store, gated, clock, { offset }, { events += it }, TestCatalog)

            val first = launch { locked.refresh(ACCOUNT, LIBRARIES) }
            withTimeout(WAIT_MS) { gated.entered.await() }
            val second = launch { locked.refresh(ACCOUNT, LIBRARIES) }
            delay(200)
            assertEquals(listOf("load"), store.calls, "the second refresh waits for the first")
            gated.release.complete(Unit)
            withTimeout(WAIT_MS) { joinAll(first, second) }

            assertEquals(listOf("load", "save", "load", "save"), store.calls)
        }

    @Test
    fun traversalIdHasNoPath() =
        runBlocking<Unit> {
            val hostile = LibraryAccount(ServerId("../x"), UserId(USER.value))
            val alsoHostile = LibraryAccount(ServerId(SERVER.value), UserId("../../etc"))

            service.refresh(hostile, LIBRARIES)
            service.refresh(alsoHostile, LIBRARIES)

            assertEquals(emptyList(), store.calls, "the store is never asked")
            assertEquals(emptyList(), store.accounts)
            assertEquals(List(2) { LineupEvent.NonCanonicalAccount }, events)
            assertNull(service.lineup(hostile).value)
            assertEquals(emptyList(), service.channels(hostile).first())
            assertEquals(emptyList(), store.calls)
        }

    @Test
    fun upperCaseAndDashedIdsMapToTheCanonicalAccount() =
        runBlocking<Unit> {
            val upper = SERVER.value.uppercase()
            val dashed = USER.value.let { "${it.take(8)}-${it.substring(8, 12)}-${it.substring(12)}" }
            val spelled = LibraryAccount(ServerId(upper), UserId(dashed))
            library.delegate.seedRule(spelled, *fillingLibrary().toTypedArray())

            service.refresh(spelled, LIBRARIES)

            assertEquals(listOf(ACCOUNT, ACCOUNT), store.accounts, "the store sees the canonical account")
            assertNotNull(stored(ACCOUNT))
            assertEquals(service.lineup(ACCOUNT).value, service.lineup(spelled).value)
        }

    @Test
    fun channelsListsTheLineupForFeatures() =
        runBlocking<Unit> {
            now = Instant.fromEpochMilliseconds(1_800_000_000_000L)
            service.refresh(ACCOUNT, LIBRARIES)

            val summaries = service.channels(ACCOUNT).first()

            val lineup = checkNotNull(current())
            assertEquals(lineup.channels.map { it.id }, summaries.map { it.id })
            val toons = summaries.first { it.id == LineupIds.starter("toons") }
            val expected =
                ChannelSummary(
                    toons.id,
                    10,
                    "Toons",
                    ChannelStatus.On,
                    ChannelKind.Starter,
                    now,
                    defaultNumber = 10,
                    kid = true,
                )
            assertEquals(expected, toons)
            val odds = summaries.first { it.id == LineupIds.leftovers("odds-and-ends") }
            assertEquals(ChannelKind.Leftovers, odds.kind)
            assertEquals("Odds", odds.name)
            val afterHours = summaries.first { it.id == LineupIds.starter("after-hours") }
            assertEquals(ChannelStatus.Shelf, afterHours.state)
        }

    @Test
    fun aPlanFailureDoesNotHideTheStoredLineup() =
        runBlocking<Unit> {
            // Unfinalised, so planning takes the device's offset, and 7 minutes is not one the rules allow.
            val unfinalised = Lineup(SERVER, USER, 1, 0, finalised = false, channels = emptyList())
            store.inner.save(ACCOUNT, LineupCodec.encode(unfinalised))
            offset = 7

            assertFailsWith<IllegalArgumentException> { service.refresh(ACCOUNT, LIBRARIES) }
            assertNull(current(), "nothing was published")

            offset = 0
            assertEquals(unfinalised, service.loaded(ACCOUNT)?.lineup, "the stored lineup is still read")
            service.refresh(ACCOUNT, LIBRARIES)
            assertTrue(checkNotNull(current()).finalised)
        }

    @Test
    fun channelsReadsTheStoredLineupWithoutARefresh() =
        runBlocking<Unit> {
            val unusable = channel("custom-9", 601, ChannelState.On, ChannelSource.Unusable("{}"))
            val lineup = lineupOf(leftovers("odds-and-ends", 199, null).copy(nameOverride = "Mine"), unusable)
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))

            val summaries = service.channels(ACCOUNT).first { it.isNotEmpty() }

            assertEquals(listOf("Mine", "custom-9"), summaries.map { it.name })
            assertEquals(listOf(ChannelKind.Leftovers, ChannelKind.Unusable), summaries.map { it.kind })
            assertEquals(emptyList(), library.snapshotCutoffs, "nothing was planned")
        }

    @Test
    fun aLineupThatDoesNotRoundTripIsRejectedNotSaved() =
        runBlocking<Unit> {
            val garbling = encodingWith { _, _ -> "{nope".encodeToByteArray() }

            garbling.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf(LineupEvent.SaveRejected), events)
            assertEquals(listOf("load"), store.calls, "never saved")
            assertNull(store.inner.peek(ACCOUNT))
            assertNotNull(garbling.lineup(ACCOUNT).value, "the in-memory lineup is still published")
        }

    @Test
    fun aLineupEncodedForAnotherAccountIsRejectedNotSaved() =
        runBlocking<Unit> {
            val stolen = encodingWith { lineup, meta -> LineupCodec.encode(lineup.copy(userId = id(9)), meta) }

            stolen.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf(LineupEvent.SaveRejected), events)
            assertNull(store.inner.peek(ACCOUNT))
        }

    @Test
    fun aRejectedSaveLeavesTheStoredLineupAsItWas() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val saved = checkNotNull(store.inner.peek(ACCOUNT))
            events.clear()
            val garbling = encodingWith { _, _ -> "{nope".encodeToByteArray() }

            garbling.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf(LineupEvent.SaveRejected), events)
            assertContentEquals(saved, store.inner.peek(ACCOUNT))
        }

    @Test
    fun aLineupOverTheStoresSizeCapIsAFailedSaveAndStaysInMemory() =
        runBlocking<Unit> {
            val fat: (Lineup, RefreshMeta) -> ByteArray = { lineup, meta ->
                val first = lineup.channels.first().copy(nameOverride = "x".repeat(SIZE_CAP + 1))
                LineupCodec.encode(lineup.copy(channels = listOf(first) + lineup.channels.drop(1)), meta)
            }
            val capped = CappedStore(store.inner)
            val seams = LineupSeams(TestCatalog, fat)
            val cappedService = LineupService(capped, library, clock, { offset }, { events += it }, seams)

            cappedService.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf(LineupEvent.SaveFailed), events)
            assertNull(store.inner.peek(ACCOUNT))
            assertNotNull(cappedService.lineup(ACCOUNT).value)
        }

    @Test
    fun aSessionStartedFromANewerVersionNeverSaves() =
        runBlocking<Unit> {
            store.inner.save(ACCOUNT, """{"version":2}""".encodeToByteArray())
            store.calls.clear()

            service.refresh(ACCOUNT, LIBRARIES)
            service.refresh(ACCOUNT, LIBRARIES)

            assertTrue("save" !in store.calls && "setAside" !in store.calls, "calls were ${store.calls}")
        }

    @Test
    fun refreshAfterForgetDoesNotSaveTheOldLineupBack() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val hidden =
                checkNotNull(current()).let { lineup ->
                    lineup.copy(channels = lineup.channels.map { it.copy(state = ChannelState.UserHidden) })
                }
            store.inner.save(ACCOUNT, LineupCodec.encode(hidden))
            service.refresh(ACCOUNT, LIBRARIES)
            assertTrue(checkNotNull(current()).channels.all { it.state == ChannelState.UserHidden })

            assertTrue(service.forget(ACCOUNT))
            assertNull(store.inner.peek(ACCOUNT))
            assertNull(current(), "the in-memory lineup is gone")

            service.refresh(ACCOUNT, LIBRARIES)

            assertTrue(checkNotNull(current()).channels.any { it.state == ChannelState.On }, "planned from scratch")
            assertEquals(current(), stored())
        }

    @Test
    fun forgetIsTheOnlyCallerOfTheStoresForget() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            service.refresh(ACCOUNT, LIBRARIES)
            assertTrue("forget" !in store.calls)
            service.forget(ACCOUNT)
            assertEquals(1, store.calls.count { it == "forget" })
        }
}

private const val WAIT_MS = 10_000L
private const val SIZE_CAP = 1024 * 1024

/** A store that, like the file store, refuses to write more than the size cap. */
private class CappedStore(
    private val inner: PeekableStore,
) : LineupStore by inner {
    override suspend fun save(
        account: LibraryAccount,
        bytes: ByteArray,
    ): Boolean = bytes.size <= SIZE_CAP && inner.save(account, bytes)
}

/** A [LineupStore] that records each call and the account it was for. */
internal class RecordingStore(
    val inner: PeekableStore = PeekableStore(),
) : LineupStore {
    val calls = mutableListOf<String>()
    val accounts = mutableListOf<LibraryAccount>()

    private fun record(
        call: String,
        account: LibraryAccount,
    ) {
        calls += call
        accounts += account
    }

    override suspend fun load(account: LibraryAccount): StoredBytes {
        record("load", account)
        return inner.load(account)
    }

    override suspend fun save(
        account: LibraryAccount,
        bytes: ByteArray,
    ): Boolean = inner.save(account, bytes).also { record("save", account) }

    override suspend fun setAside(account: LibraryAccount): Boolean {
        record("setAside", account)
        return inner.setAside(account)
    }

    override suspend fun forget(account: LibraryAccount): Boolean {
        record("forget", account)
        return inner.forget(account)
    }
}

/** The in-memory store, plus a look at what it holds without counting as a load. */
internal class PeekableStore : LineupStore {
    private val delegate = InMemoryLineupStore()

    var failReads: Boolean
        get() = delegate.failReads
        set(value) {
            delegate.failReads = value
        }

    var failWrites: Boolean
        get() = delegate.failWrites
        set(value) {
            delegate.failWrites = value
        }

    fun setAsideCopy(account: LibraryAccount): ByteArray? = delegate.setAsideCopy(account)

    fun peek(account: LibraryAccount): ByteArray? {
        val reads = delegate.failReads
        delegate.failReads = false
        val held = runBlocking { delegate.load(account) }
        delegate.failReads = reads
        return (held as? StoredBytes.Present)?.bytes
    }

    override suspend fun load(account: LibraryAccount): StoredBytes = delegate.load(account)

    override suspend fun save(
        account: LibraryAccount,
        bytes: ByteArray,
    ): Boolean = delegate.save(account, bytes)

    override suspend fun setAside(account: LibraryAccount): Boolean = delegate.setAside(account)

    override suspend fun forget(account: LibraryAccount): Boolean = delegate.forget(account)
}

/** Holds the first `ruleSnapshot` until [release] completes, after saying it was [entered]. */
internal class GatedLibrary(
    private val inner: CountingLibrary,
) : LibraryRepository by inner {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        if (entered.complete(Unit)) release.await()
        return inner.ruleSnapshot(account, libraries, cutoff)
    }
}
