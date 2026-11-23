// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.MovedNotice
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The port features use to change a lineup (spec 21). */
class ChannelManagerTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, { events += it }, TestCatalog)
    private val events = mutableListOf<LineupEvent>()
    private var chosen: Set<ItemId>? = LIBRARIES
    private val manager = ChannelManager(service, FakeProEntitlement(), IGNORED_SWITCH_ONS) { chosen }

    private fun lineup(
        state: ChannelState = ChannelState.On,
        vararg extra: LineupChannel,
    ): Lineup =
        lineupOf(
            starter("laughs", 110, state, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, state, rules("Whodunit", Source.Genre("Crime"))),
            *extra,
        )

    private suspend fun seed(lineup: Lineup) {
        store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
    }

    @Test
    fun numberTakenCarriesTheHolder() =
        runBlocking<Unit> {
            seed(lineup())

            val result = manager.change(ACCOUNT, ChannelChange.SetNumber(LineupIds.starter("laughs"), 210))

            assertEquals(ChangeResult.Refused(ChangeRefusal.NumberTaken(LineupIds.starter("whodunit"))), result)
        }

    @Test
    fun aNumberBelowTheChannelsFloorIsRefusedWithTheBandItNeeds() =
        runBlocking<Unit> {
            seed(lineup())

            val result = manager.change(ACCOUNT, ChannelChange.SetNumber(LineupIds.starter("laughs"), 50))

            assertEquals(ChangeResult.Refused(ChangeRefusal.BelowBand(BandNeeded.General)), result)
        }

    private fun withNotices(): Lineup {
        val base = lineup()
        val laughs = LineupIds.starter("laughs")
        val renamed = base.channels.map { if (it.id == laughs) it.copy(nameOverride = "Mine") else it }
        val notices =
            listOf(
                MovedNotice(LineupIds.starter("laughs"), 50, 110),
                MovedNotice(LineupIds.starter("whodunit"), 60, 210),
            )
        return base.copy(channels = renamed, movedNotices = notices)
    }

    private suspend fun stored(): ByteArray = assertIs<StoredBytes.Present>(store.inner.load(ACCOUNT)).bytes

    @Test
    fun movedNoticesAreTakenOnceAndClearedFromTheFile() =
        runBlocking<Unit> {
            seed(withNotices())
            assertTrue("\"moved\"" in stored().decodeToString())

            val first = manager.takeMovedNotices(ACCOUNT)

            val told = listOf(MovedChannel("Mine", 50, 110), MovedChannel("Whodunit", 60, 210))
            assertEquals(told, first.sortedBy { it.from })
            assertEquals(emptyList(), manager.takeMovedNotices(ACCOUNT), "never shown twice")
            val after = stored()
            assertTrue("\"moved\"" !in after.decodeToString(), "the file is written without the key")
            val read = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(after))
            assertEquals(LineupCodec.BASE_VERSION, read.fileVersion)
        }

    @Test
    fun takingWithNoNoticesWritesNothing() =
        runBlocking<Unit> {
            seed(lineup())
            val before = stored()
            val saves = store.calls.count { it == "save" }

            assertEquals(emptyList(), manager.takeMovedNotices(ACCOUNT))

            assertContentEquals(before, stored())
            assertEquals(saves, store.calls.count { it == "save" })
        }

    @Test
    fun aNoticeForAChannelThatIsGoneIsNotTold() =
        runBlocking<Unit> {
            val base = withNotices()
            val gone = base.copy(channels = base.channels.filter { it.id != LineupIds.starter("whodunit") })
            seed(gone)
            assertEquals(listOf("Mine"), manager.takeMovedNotices(ACCOUNT).map { it.name })
            assertEquals(emptyList(), manager.takeMovedNotices(ACCOUNT))
        }

    @Test
    fun aLineupThatMayNotBeWrittenKeepsItsNotices() =
        runBlocking<Unit> {
            assertEquals(emptyList(), manager.takeMovedNotices(ACCOUNT), "no lineup")
            seed(withNotices())
            store.inner.failWrites = true
            assertEquals(emptyList(), manager.takeMovedNotices(ACCOUNT), "the save failed, so it is not told")
            store.inner.failWrites = false
            assertEquals(2, manager.takeMovedNotices(ACCOUNT).size, "the next visit tells it")
        }

    @Test
    fun aChangeIsDoneOrNotEditable() =
        runBlocking<Unit> {
            val laughs = ChannelChange.SwitchOff(LineupIds.starter("laughs"))
            val whodunit = ChannelChange.SwitchOff(LineupIds.starter("whodunit"))
            assertEquals(ChangeResult.NotEditable, manager.change(ACCOUNT, laughs))
            seed(lineup())
            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, laughs))
            assertEquals(ChangeResult.Refused(ChangeRefusal.LastOn), manager.change(ACCOUNT, whodunit))
            assertEquals(LineupAccess.Editable, manager.access(ACCOUNT).first())
        }

    @Test
    fun theHouseholdSwitchIsAChangeOfTheLineupNotOfAChannel() =
        runBlocking<Unit> {
            seed(lineup())

            assertEquals(ChangeResult.Done, manager.change(ACCOUNT, ChannelChange.UseMyCollections(false)))

            val after = checkNotNull(service.lineup(ACCOUNT).value).lineup
            assertEquals(false, after.useMyCollections)
            assertTrue(after.channels.none { it.chosenByViewer })
            assertEquals(false, service.useMyCollections(ACCOUNT).first())
        }

    @Test
    fun aFailedSaveIsReported() =
        runBlocking<Unit> {
            seed(lineup())
            store.inner.failWrites = true

            val off = ChannelChange.SwitchOff(LineupIds.starter("laughs"))
            assertEquals(ChangeResult.SaveFailed, manager.change(ACCOUNT, off))
            assertEquals(ResetResult.SaveFailed, manager.reset(ACCOUNT))
        }

    @Test
    fun resetReportsChosenWhenAChannelIsOn() =
        runBlocking<Unit> {
            seed(lineup(ChannelState.UserHidden))

            assertEquals(ResetResult.Chosen, manager.reset(ACCOUNT))
        }

    @Test
    fun resetReportsNotChosenYetWithoutAChannelOn() =
        runBlocking<Unit> {
            seed(lineup())
            val bare = LineupService(store, CountingLibrary(), clock, { 0 }, {}, TestCatalog)

            val bareManager = ChannelManager(bare, FakeProEntitlement(), IGNORED_SWITCH_ONS) { LIBRARIES }
            assertEquals(ResetResult.NotChosenYet, bareManager.reset(ACCOUNT))
        }

    @Test
    fun resetWritesNothingWhenTheLibrariesAreUnavailable() =
        runBlocking<Unit> {
            seed(lineup())
            val before = store.inner.peek(ACCOUNT)
            store.calls.clear()

            chosen = null
            assertEquals(ResetResult.LibrariesUnavailable, manager.reset(ACCOUNT))
            chosen = emptySet()
            assertEquals(ResetResult.LibrariesUnavailable, manager.reset(ACCOUNT))
            assertTrue(events.isEmpty(), "none chosen is not a fault")
            val failing =
                ChannelManager(service, FakeProEntitlement(), IGNORED_SWITCH_ONS) { error("settings unreadable") }
            assertEquals(ResetResult.LibrariesUnavailable, failing.reset(ACCOUNT))
            assertEquals(listOf(LineupEvent.ResetLibrariesUnavailable), events)

            assertTrue("save" !in store.calls, "calls were ${store.calls}")
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun resetReportsLibrariesUnavailableWhenTheLibraryReadFails() =
        runBlocking<Unit> {
            seed(lineup())
            val broken =
                object : LibraryRepository by library {
                    override suspend fun ruleSnapshot(
                        account: LibraryAccount,
                        libraries: Set<ItemId>,
                        cutoff: Instant,
                    ): RuleSnapshot = error("library unreadable")

                    override suspend fun ruleSnapshots(
                        account: LibraryAccount,
                        selections: List<Set<ItemId>>,
                        cutoff: Instant,
                    ): List<RuleSnapshot> = error("library unreadable")
                }
            val failing = LineupService(store, broken, clock, { 0 }, { events += it }, TestCatalog)

            val failingManager = ChannelManager(failing, FakeProEntitlement(), IGNORED_SWITCH_ONS) { LIBRARIES }
            assertEquals(ResetResult.LibrariesUnavailable, failingManager.reset(ACCOUNT))
            assertEquals(listOf(LineupEvent.ResetLibrariesUnavailable), events)
        }

    // The port never throws: a failure of the plan (here the device offset it reads) is a result, reported.
    @Test
    fun aPlannerFailureOnResetIsFailedNotThrown() =
        runBlocking<Unit> {
            seed(lineup())
            val failing = LineupService(store, library, clock, { error("no offset") }, { events += it }, TestCatalog)
            failing.loaded(ACCOUNT)
            val before = store.inner.peek(ACCOUNT)

            val manager = ChannelManager(failing, FakeProEntitlement(), IGNORED_SWITCH_ONS) { LIBRARIES }
            assertEquals(ResetResult.Failed, manager.reset(ACCOUNT))
            assertEquals(listOf(LineupEvent.ResetFailed), events)
            assertContentEquals(before, store.inner.peek(ACCOUNT), "nothing was saved")
        }

    // A store whose read throws (it never should): the port still never throws.
    private val throwing =
        object : LineupStore by store {
            override suspend fun load(account: LibraryAccount): StoredBytes = error("store broke")
        }

    @Test
    fun aChangeThatThrowsIsFailedNotThrown() =
        runBlocking<Unit> {
            val broken =
                ChannelManager(
                    LineupService(throwing, library, clock, { 0 }, { events += it }, TestCatalog),
                    FakeProEntitlement(),
                    IGNORED_SWITCH_ONS,
                ) {
                    LIBRARIES
                }

            val result = broken.change(ACCOUNT, ChannelChange.SwitchOff(LineupIds.starter("laughs")))

            assertEquals(ChangeResult.Failed, result)
            assertEquals(listOf(LineupEvent.EditFailed), events)
        }

    @Test
    fun anAccessThatThrowsIsReadOnlyNotThrown() =
        runBlocking<Unit> {
            val broken =
                ChannelManager(
                    LineupService(throwing, library, clock, { 0 }, { events += it }, TestCatalog),
                    FakeProEntitlement(),
                    IGNORED_SWITCH_ONS,
                ) {
                    LIBRARIES
                }

            assertEquals(LineupAccess.ReadOnly, broken.access(ACCOUNT).first())
            assertEquals(listOf(LineupEvent.AccessFailed), events)
        }

    @Test
    fun aFailedReadThenAFailedSaveLeavesMemoryAsTheStoredLineup() =
        runBlocking<Unit> {
            seed(lineup())
            store.inner.failReads = true
            service.refresh(ACCOUNT, LIBRARIES)
            store.inner.failReads = false
            store.inner.failWrites = true
            val before = store.inner.peek(ACCOUNT)

            val off = ChannelChange.SwitchOff(LineupIds.starter("laughs"))
            assertEquals(ChangeResult.SaveFailed, manager.change(ACCOUNT, off))

            assertContentEquals(before, store.inner.peek(ACCOUNT), "the stored file is unchanged")
            val held = checkNotNull(service.lineup(ACCOUNT).value)
            assertEquals(lineup(), held.lineup, "the reconcile published the stored lineup: memory matches disk")
        }

    @Test
    fun resetReportsNotEditableForANewerAppsFile() =
        runBlocking<Unit> {
            val newer = """{"version":${LineupCodec.VERSION + 1}}""".encodeToByteArray()
            store.inner.save(ACCOUNT, newer)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(ResetResult.NotEditable, manager.reset(ACCOUNT))
            assertEquals(LineupAccess.ReadOnly, manager.access(ACCOUNT).first())
        }

    @Test
    fun summariesMarkKidAndOptInChannels() =
        runBlocking<Unit> {
            seed(
                lineup(
                    ChannelState.On,
                    starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"))),
                    starter("after-hours", 590, ChannelState.Shelf, rules("After Hours", Source.Tag("late"))),
                ),
            )
            val byId = service.channels(ACCOUNT).first().associateBy { it.id }

            val toons = checkNotNull(byId[LineupIds.starter("toons")])
            val after = checkNotNull(byId[LineupIds.starter("after-hours")])
            val laughs = checkNotNull(byId[LineupIds.starter("laughs")])
            assertTrue(toons.kid && !toons.optIn)
            assertTrue(after.optIn && !after.kid)
            assertTrue(!laughs.kid && !laughs.optIn)
            assertEquals(110, laughs.defaultNumber)
        }

    @Test
    fun aSwitchOnOfAChannelThatWasOffIsToldOnce() =
        runBlocking<Unit> {
            seed(lineup(ChannelState.On, starter("noir", 310, ChannelState.Shelf, rules("Noir", Source.Tag("noir")))))
            service.loaded(ACCOUNT)
            val told = mutableListOf<ChannelId>()
            val watched = ChannelManager(service, FakeProEntitlement(), { _, id -> told += id }) { chosen }
            val noir = LineupIds.starter("noir")

            assertEquals(ChangeResult.Done, watched.change(ACCOUNT, ChannelChange.SwitchOn(noir)))
            assertEquals(ChangeResult.Done, watched.change(ACCOUNT, ChannelChange.SwitchOn(noir)))

            assertEquals(listOf(noir), told)
        }

    @Test
    fun aSwitchOnOfAChannelAlreadyOnIsNotTold() =
        runBlocking<Unit> {
            seed(lineup())
            service.loaded(ACCOUNT)
            val told = mutableListOf<ChannelId>()
            val watched = ChannelManager(service, FakeProEntitlement(), { _, id -> told += id }) { chosen }

            val laughs = LineupIds.starter("laughs")

            assertEquals(ChangeResult.Done, watched.change(ACCOUNT, ChannelChange.SwitchOn(laughs)))
            assertEquals(ChangeResult.Done, watched.change(ACCOUNT, ChannelChange.SwitchOff(laughs)))

            assertEquals(emptyList(), told)
        }
}
