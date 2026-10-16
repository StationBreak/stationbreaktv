// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Spec 06 task 4.6: the background job. One unique periodic piece of work, about every 6 hours, that needs a network
 * connection, is kept if it already exists (so it is enqueued once however often the app opens), and runs both passes.
 * Neither request carries anything but two yes/no flags: no token ever enters WorkManager's own database.
 */
class PeriodicWorkSchedulingTest {
    private val rig = StayCurrentRig()
    private val enqueuer = RecordingEnqueuer()
    private val stayCurrent = StayCurrent(enqueuer, enqueuer, rig.repository, SyncTriggerPolicy(rig.clock), rig.runner)

    @After
    fun close() = rig.close()

    @Test
    fun theJobIsPeriodicEverySixHoursAndNeedsTheNetwork() {
        val spec = StayCurrentWork.periodicRequest().workSpec

        assertTrue(spec.isPeriodic)
        assertEquals(TimeUnit.HOURS.toMillis(6), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun theJobRunsBoth() {
        val data = StayCurrentWork.periodicRequest().workSpec.input

        assertTrue(data.getBoolean(StayCurrentWork.KEY_INCREMENTAL, false))
        assertTrue(data.getBoolean(StayCurrentWork.KEY_RECONCILE, false))
    }

    @Test
    fun schedulingAsksForOneUniqueJobAndKeepsAnExistingOne() {
        stayCurrent.schedulePeriodic()
        stayCurrent.schedulePeriodic()

        assertEquals(List(2) { StayCurrentWork.PERIODIC_NAME }, enqueuer.periodic.map { it.first })
        assertEquals(List(2) { ExistingPeriodicWorkPolicy.KEEP }, enqueuer.periodic.map { it.second })
    }

    @Test
    fun theOnOpenWorkNeedsTheNetworkAndCarriesOnlyTheTwoFlags() {
        val spec = StayCurrentWork.openRequest(SyncPlan(incremental = false, reconcile = true)).workSpec

        assertFalse(spec.isPeriodic)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(setOf(StayCurrentWork.KEY_INCREMENTAL, StayCurrentWork.KEY_RECONCILE), spec.input.keyValueMap.keys)
        assertTrue(
            spec.input.keyValueMap.values
                .all { it is Boolean },
        )
        assertFalse(spec.input.getBoolean(StayCurrentWork.KEY_INCREMENTAL, true))
        assertTrue(spec.input.getBoolean(StayCurrentWork.KEY_RECONCILE, false))
    }

    @Test
    fun thePeriodicJobCarriesOnlyTheTwoFlagsToo() {
        val data = StayCurrentWork.periodicRequest().workSpec.input

        assertTrue(data.keyValueMap.values.all { it is Boolean })
        assertEquals(2, data.keyValueMap.size)
    }

    @Test
    fun theNamesAreDistinctFromTheFirstSyncsSoOneNeverReplacesTheOther() {
        val names = setOf(FirstSyncWork.UNIQUE_NAME, StayCurrentWork.OPEN_NAME, StayCurrentWork.PERIODIC_NAME)

        assertEquals(3, names.size)
    }

    @Test
    fun aChangeOfAccountCancelsTheFirstSyncAndTheOnOpenWorkButNotThePeriodicJob() {
        AccountChangeSyncControl(enqueuer).cancelFirstSync()

        assertEquals(setOf(FirstSyncWork.UNIQUE_NAME, StayCurrentWork.OPEN_NAME), enqueuer.cancelled.toSet())
    }
}
