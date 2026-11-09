// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.feature.guide.ui.SettingsPanel
import tv.stationbreak.feature.guide.ui.SettingsRow
import tv.stationbreak.feature.guide.ui.SettingsStep
import tv.stationbreak.feature.guide.ui.SyncNowLine
import tv.stationbreak.feature.guide.ui.settingsStep
import tv.stationbreak.feature.guide.ui.syncNowLine

/** Spec 26, Task 5.2: the Settings panel's Sync now row starts one sync per press and says how it stands. */
class GuideSyncNowTest : GuideRig() {
    private val onSyncNow = SettingsPanel(SettingsRow.SyncNow)

    private fun press(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        repeat: Int = 0,
    ): SettingsStep = checkNotNull(settingsStep(onSyncNow, key, type, repeat))

    private val noTime: (Long) -> String = { error("no time to format") }

    @Test
    fun select_startsOnce_rowShowsSyncing() =
        guideTest { vm ->
            // Select asks for one sync, once per press: its auto-repeat and its release ask for none, and the panel
            // stays open on the row.
            assertEquals(SettingsStep(onSyncNow, syncNow = true), press(Key.DirectionCenter))
            assertEquals(SettingsStep(onSyncNow), press(Key.Enter, repeat = 1))
            assertEquals(SettingsStep(onSyncNow), press(Key.Enter, KeyEventType.KeyUp))

            // The guide starts it, and reads the state the sync says.
            assertNull("nothing to say before a press", syncNowLine(vm.syncNowState.value, noTime))
            assertTrue(vm.syncNow())
            assertEquals(1, syncNow.starts)
            assertEquals(SyncNowState.Running, vm.syncNowState.value)
            assertEquals(SyncNowLine(R.string.guide_sync_running), syncNowLine(vm.syncNowState.value, noTime))

            // A press while it runs starts nothing.
            assertFalse(vm.syncNow())
            assertEquals(SyncNowState.Running, vm.syncNowState.value)
        }

    @Test
    fun failed_showsReason() =
        guideTest { vm ->
            val expected =
                mapOf(
                    SyncFailure.Network to R.string.guide_sync_failed_network,
                    SyncFailure.SessionExpired to R.string.guide_sync_failed_session,
                    SyncFailure.Server to R.string.guide_sync_failed_server,
                    SyncFailure.Suspect to R.string.guide_sync_failed_suspect,
                )
            assertEquals(SyncFailure.entries.toSet(), expected.keys)
            for ((failure, line) in expected) {
                syncNow.state.value = SyncNowState.Failed(failure)
                assertEquals(SyncNowLine(line), syncNowLine(vm.syncNowState.value, noTime))
            }
        }

    @Test
    fun done_showsTime() =
        guideTest { vm ->
            val at = min(102)
            syncNow.state.value = SyncNowState.Done(at)
            val asked = mutableListOf<Long>()

            val line = syncNowLine(vm.syncNowState.value) { ms -> "8:42 PM".also { asked += ms } }

            assertEquals(SyncNowLine(R.string.guide_sync_done, "8:42 PM"), line)
            assertEquals(listOf(at), asked)
        }
}
