// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tv.stationbreak.app.di.ProModule
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.domain.lineup.ChannelEditor
import tv.stationbreak.core.domain.lineup.ResetResult
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import javax.inject.Inject

/**
 * #811: the app's own graph, with the production [ProModule] swapped for a closed entitlement, as a free release
 * build would have it. The channel editor the screens get from Hilt reads that one entitlement, and reads it live:
 * flipping it to Pro opens the gate with no restart, which is the app's half of "buying Pro opens the gate".
 * The screen half is ChannelsScreenTest's free-tier cases.
 */
@HiltAndroidTest
@UninstallModules(ProModule::class)
class ProGateWiringTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    // The selection's resume, as the app's start runs it, so nothing that waits on it waits for ever (spec 26).
    @get:Rule(order = 1)
    val selection = SelectionResumeRule()

    private val closed = FakeProEntitlement(initial = false)

    @BindValue
    @JvmField
    val pro: ProEntitlement = closed

    @Inject lateinit var entitlement: ProEntitlement

    @Inject lateinit var editor: ChannelEditor

    private val account = LibraryAccount(ServerId("srv-gate"), UserId("u-gate"))

    @Before
    fun inject() = hilt.inject()

    @Test
    fun theGraphHasOneEntitlement_theTestsClosedOne() {
        assertSame(closed, entitlement)
    }

    @Test
    fun theGraphsChannelEditorRefusesAResetWhileFree_andAllowsItOnceProIsSetLive() =
        runBlocking {
            assertEquals(ResetResult.NeedsPro, editor.reset(account))

            closed.set(true)

            // Past the gate: whatever the lineup says next (here, no library chosen), it is no longer "needs Pro".
            assertNotEquals(ResetResult.NeedsPro, editor.reset(account))
        }
}
