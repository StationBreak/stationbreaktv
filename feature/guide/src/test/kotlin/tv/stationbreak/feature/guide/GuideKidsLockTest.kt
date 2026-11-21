// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLock
import tv.stationbreak.core.domain.kids.KidsLockState
import tv.stationbreak.core.domain.kids.PinResult
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7

/**
 * What the guide's ViewModel does with Kids Mode (#789): it follows the lock, keeps the screens that edit the lineup
 * shut while the lock is on, and passes the PIN pad's requests on to the lock.
 */
class GuideKidsLockTest : GuideRig() {
    private fun GuideViewModel.press(direction: GuideDirection) = move(direction, repeat = false, atMs = 0)

    private fun TestScope.eventsOf(vm: GuideViewModel): MutableList<GuideEvent> =
        mutableListOf<GuideEvent>().also { got -> backgroundScope.launch { vm.events.collect { got += it } } }

    @Test
    fun theGuideFollowsTheLock() =
        guideTest { vm ->
            runCurrent()
            assertFalse(vm.kidsOn)
            assertEquals(false, vm.kidsState.value?.pinSet)
            kidsLock.lock()
            runCurrent()
            assertTrue(vm.kidsOn)
            assertEquals(true, vm.kidsState.value?.pinSet)
            kidsLock.unlock()
            runCurrent()
            assertFalse(vm.kidsOn)
        }

    @Test
    fun selectOnTheChannelColumnOpensPropertiesOnlyWhileTheLockIsOff() =
        guideTest { vm ->
            val got = eventsOf(vm)
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            assertTrue(vm.snapshot.value.onChannel)
            kidsLock.lock()
            runCurrent()
            vm.select()
            runCurrent()
            assertTrue(got.isEmpty())
            kidsLock.unlock()
            runCurrent()
            vm.select()
            runCurrent()
            assertEquals(listOf<GuideEvent>(GuideEvent.OpenChannelProperties(CH7)), got)
        }

    @Test
    fun aboutAndChannelsStayShutOnTheMenuWhileTheLockIsOn_SettingsAndExitDoNot() =
        guideTest { vm ->
            val got = eventsOf(vm)
            vm.open(CH7)
            runCurrent()
            vm.toggleMenu()
            kidsLock.lock()
            runCurrent()
            // Channels, then About, then Settings: all stops, so the sheet's keys are as they were.
            vm.select()
            vm.press(GuideDirection.Up)
            assertEquals(GuideMenuEntry.About, vm.snapshot.value.menu)
            vm.select()
            runCurrent()
            assertTrue(got.isEmpty())
            vm.press(GuideDirection.Down)
            vm.press(GuideDirection.Down)
            assertEquals(GuideMenuEntry.Settings, vm.snapshot.value.menu)
            vm.select()
            runCurrent()
            assertEquals(listOf<GuideEvent>(GuideEvent.OpenSettings), got)
        }

    @Test
    fun channelsOpensAgainOnceTheLockIsOff() =
        guideTest { vm ->
            val got = eventsOf(vm)
            vm.open(CH7)
            runCurrent()
            vm.toggleMenu()
            kidsLock.lock()
            runCurrent()
            kidsLock.unlock()
            runCurrent()
            vm.select()
            runCurrent()
            assertEquals(listOf<GuideEvent>(GuideEvent.OpenChannels), got)
        }

    @Test
    fun updateLibraryDoesNotStartWhileTheLockIsOn() =
        guideTest { vm ->
            kidsLock.lock()
            runCurrent()
            assertFalse(vm.syncNow())
            kidsLock.unlock()
            runCurrent()
            assertTrue(vm.syncNow())
        }

    /** A lock whose stored state has not been read: it never emits, as the real one before its file is read. */
    private fun unread(): KidsLock =
        object : KidsLock by kidsLock {
            override val state: Flow<KidsLockState> = flow { awaitCancellation() }
        }

    @Test
    fun beforeTheLockIsFirstReadEveryGuardThatBlocksEditingIsClosed() {
        kidsForGuide = unread()
        guideTest { vm ->
            val got = eventsOf(vm)
            vm.open(CH7)
            runCurrent()
            assertEquals(null, vm.kidsState.value)
            assertTrue(vm.kidsOn)
            // Update library stays shut.
            assertFalse(vm.syncNow())
            // About on the grid's channel column stays shut.
            vm.press(GuideDirection.Left)
            assertTrue(vm.snapshot.value.onChannel)
            vm.select()
            runCurrent()
            assertTrue(got.isEmpty())
            // Channels and About on the menu stay shut.
            vm.toggleMenu()
            runCurrent()
            vm.select()
            vm.press(GuideDirection.Up)
            assertEquals(GuideMenuEntry.About, vm.snapshot.value.menu)
            vm.select()
            runCurrent()
            assertTrue(got.isEmpty())
        }
    }

    @Test
    fun theFirstPinIsSetAndTheLockTurnsOnWithIt() =
        guideTest { vm ->
            assertEquals(PinResult.Ok, vm.submitPin(PinSubmit.SetFirst("1234")))
            runCurrent()
            assertTrue(vm.kidsOn)
            assertEquals("1234", kidsLock.pin)
        }

    @Test
    fun turnOffNeedsTheRightPin_andWrongOnesAreAnsweredWithTriesLeft() =
        guideTest { vm ->
            kidsLock.pin = "1234"
            kidsLock.lock()
            runCurrent()
            assertEquals(PinResult.Wrong(triesLeft = 1), vm.submitPin(PinSubmit.TurnOff("0000")))
            runCurrent()
            assertTrue(vm.kidsOn)
            assertEquals(PinResult.Ok, vm.submitPin(PinSubmit.TurnOff("1234")))
            runCurrent()
            assertFalse(vm.kidsOn)
        }

    @Test
    fun turnOnNeedsNoPinOnceOneIsSet() =
        guideTest { vm ->
            assertFalse(vm.enableKids())
            kidsLock.pin = "1234"
            assertTrue(vm.enableKids())
            runCurrent()
            assertTrue(vm.kidsOn)
        }

    @Test
    fun theOldPinIsCheckedThenChanged() =
        guideTest { vm ->
            kidsLock.pin = "1234"
            assertEquals(PinResult.Wrong(triesLeft = 1), vm.submitPin(PinSubmit.CheckOld("9999")))
            assertEquals(PinResult.Ok, vm.submitPin(PinSubmit.CheckOld("1234")))
            assertEquals(PinResult.Ok, vm.submitPin(PinSubmit.ChangeTo("1234", "4321")))
            assertEquals("4321", kidsLock.pin)
        }

    @Test
    fun theCapShowsThroughTheState() =
        guideTest { vm ->
            runCurrent()
            assertEquals(KidsCap.Kids, vm.kidsState.value?.cap)
            kidsLock.lock(KidsCap.Family)
            runCurrent()
            assertEquals(KidsCap.Family, vm.kidsState.value?.cap)
            assertTrue(vm.kidsState.value?.allows(40) == true)
            assertFalse(vm.kidsState.value?.allows(70) == true)
        }

    @Test
    fun whileOffTheCapChangesAtOnceWithNoPin() =
        guideTest { vm ->
            runCurrent()
            assertEquals(PinResult.Ok, vm.setKidsCap(KidsCap.Teen))
            runCurrent()
            assertEquals(KidsCap.Teen, vm.kidsState.value?.cap)
            assertFalse(vm.kidsOn)
        }

    @Test
    fun whileOnACapChangeNeedsThePin_andAWrongPinKeepsTheCap() =
        guideTest { vm ->
            kidsLock.pin = "1234"
            kidsLock.lock(KidsCap.Kids)
            runCurrent()
            // The no-PIN path is shut while the lock is on: it asks nothing of the lock.
            assertEquals(PinResult.Unavailable, vm.setKidsCap(KidsCap.Teen))
            runCurrent()
            assertEquals(KidsCap.Kids, vm.kidsState.value?.cap)
            assertEquals(PinResult.Wrong(triesLeft = 1), vm.submitPin(PinSubmit.SetCap(KidsCap.Teen, "0000")))
            runCurrent()
            assertEquals(KidsCap.Kids, vm.kidsState.value?.cap)
            assertEquals(PinResult.Ok, vm.submitPin(PinSubmit.SetCap(KidsCap.Family, "1234")))
            runCurrent()
            assertEquals(KidsCap.Family, vm.kidsState.value?.cap)
            assertTrue(vm.kidsOn)
        }

    @Test
    fun beforeTheLockIsReadTheCapDoesNotChange() {
        kidsForGuide = unread()
        guideTest { vm ->
            runCurrent()
            assertEquals(PinResult.Unavailable, vm.setKidsCap(KidsCap.Teen))
            assertEquals(KidsCap.Kids, kidsLock.current.value.cap)
        }
    }

    @Test
    fun aPinNeverShowsInAText() {
        val said =
            listOf(
                PinSubmit.SetFirst("1234"),
                PinSubmit.TurnOff("1234"),
                PinSubmit.CheckOld("1234"),
                PinSubmit.ChangeTo("1234", "4321"),
                PinSubmit.SetCap(KidsCap.Teen, "1234"),
            ).joinToString { it.toString() }
        assertFalse(said.contains("1234") || said.contains("4321"))
    }
}
