// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [BuiltInLineupReadiness]: what setup's first-sync screen and live TV are told about the lineup. Ready is a channel on
 * the air now, whatever else is so; Building is a lineup not there, being planned, or waiting for its first choice;
 * Empty is a lineup planned with everything it waits for and still nothing on the air.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuiltInLineupReadinessTest {
    private val library = CountingLibrary()

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    private val noon =
        object : Clock {
            override fun now(): Instant = NOON
        }

    // The schedule is read at half past the test day's start, where its blocks are.
    private val scheduleClock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(T0 + 30 * MIN)
        }
    private val store = InMemoryLineupStore()
    private val service = LineupService(store, library, noon, { 0 }, {}, TestCatalog)
    private val schedule = FakeSchedule()
    private val working = MutableStateFlow(false)
    private val readiness = BuiltInLineupReadiness(service, library, schedule, scheduleClock, working)

    private fun onTheAir() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
    }

    private suspend fun now(account: LibraryAccount = ACCOUNT): LineupState = readiness.readiness(account).first()

    // A thin library: two filling recipes of the five the first choice needs, so it is never finalised.
    private fun seedThin() = library.seed(fillingLibrary().take(14))

    @Test
    fun noLineupYetIsBuilding() =
        runBlocking<Unit> {
            assertEquals(LineupState.Building, now())
        }

    @Test
    fun aFinalisedLineupWithAChannelOnTheAirIsReady() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            library.delegate.completeCollectionPass(ACCOUNT, NOON)
            service.refresh(ACCOUNT, LIBRARIES)
            onTheAir()

            assertEquals(true, service.loaded(ACCOUNT)?.lineup?.finalised)
            assertEquals(LineupState.Ready, now())
        }

    @Test
    fun aThinLibraryThatNeverFinalisesButHasAChannelOnTheAirIsReady() =
        runBlocking<Unit> {
            seedThin()
            library.delegate.completeCollectionPass(ACCOUNT, NOON)
            service.refresh(ACCOUNT, LIBRARIES)
            onTheAir()

            assertEquals(false, service.loaded(ACCOUNT)?.lineup?.finalised, "the fixture is thin")
            assertEquals(LineupState.Ready, now())
        }

    @Test
    fun aThinLibraryPlannedWithItsCollectionsAndNothingOnTheAirIsEmpty() =
        runBlocking<Unit> {
            seedThin()
            library.delegate.completeCollectionPass(ACCOUNT, NOON)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(false, service.loaded(ACCOUNT)?.lineup?.finalised, "the fixture is thin")
            assertEquals(LineupState.Empty, now())
        }

    @Test
    fun aFirstLineupWaitingForItsCollectionsIsBuildingUntilTheyAreGivenUp() =
        runBlocking<Unit> {
            // Planned at setup, before the first sync: no collection pass yet, so no first choice.
            library.seed(fillingLibrary())
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(LineupState.Building, now())

            // The passes keep failing: the next plan goes without them, so nothing more is coming.
            library.delegate.setFailedCollectionPasses(ACCOUNT, GIVE_UP_AFTER)
            assertEquals(LineupState.Empty, now())
        }

    @Test
    fun whileTheRefresherWorksANothingOnTheAirIsBuilding() =
        runBlocking<Unit> {
            val none = Lineup(SERVER, USER, 1, 0, finalised = true, channels = emptyList())
            store.save(ACCOUNT, LineupCodec.encode(none))
            working.value = true
            assertEquals(LineupState.Building, now())

            working.value = false
            assertEquals(LineupState.Empty, now())
        }

    @Test
    fun aChannelOnTheAirIsReadyWhileTheRefresherWorks() =
        runBlocking<Unit> {
            working.value = true
            onTheAir()

            assertEquals(LineupState.Ready, now())
        }

    @Test
    fun aScheduleThatCannotBeReadIsNotReady() =
        runBlocking<Unit> {
            onTheAir()
            schedule.airingsFailOn = setOf(1)

            assertEquals(LineupState.Building, now())
        }

    @Test
    fun anAccountWithNoLineupEverIsEmpty() =
        runBlocking<Unit> {
            // Its IDs are not IDs: it never has a lineup, so there is nothing to wait for.
            assertEquals(LineupState.Empty, now(LibraryAccount(ServerId("not-an-id"), UserId("nor-this"))))
        }

    @Test
    fun itFollowsTheLineupFromBuildingToReady() =
        runTest(UnconfinedTestDispatcher()) {
            library.seed(fillingLibrary())
            val seen = mutableListOf<LineupState>()
            backgroundScope.launch { readiness.readiness(ACCOUNT).collect { seen += it } }

            onTheAir()
            library.delegate.completeCollectionPass(ACCOUNT, NOON)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(listOf(LineupState.Building, LineupState.Ready), seen)
        }
}
