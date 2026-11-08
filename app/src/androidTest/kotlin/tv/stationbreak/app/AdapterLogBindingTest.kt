// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.RedactingAdapterLog
import javax.inject.Inject

/**
 * F0.4 task 3.2: the graph's [AdapterLog] is the redacting decorator, so no sink a later task plugs in can be handed an
 * unmasked line or exception message.
 */
@HiltAndroidTest
class AdapterLogBindingTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    // The selection's resume, as the app's start runs it, so nothing that waits on it waits for ever (spec 26).
    @get:Rule(order = 1)
    val selection = SelectionResumeRule()

    @Inject lateinit var adapterLog: AdapterLog

    @Before
    fun inject() = hilt.inject()

    @Test
    fun theGraphProvidesTheRedactingDecorator() {
        assertTrue("AdapterLog is ${adapterLog::class.java.name}", adapterLog is RedactingAdapterLog)
    }
}
