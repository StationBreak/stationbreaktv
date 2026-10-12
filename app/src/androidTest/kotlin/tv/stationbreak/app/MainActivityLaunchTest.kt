// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test
    fun launchReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun debugBuildUsesTheDebugApplicationId() {
        // ADR-0010 + ADR-0011: debug builds install as tv.stationbreak.app.debug, beside a release build.
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        assertEquals("tv.stationbreak.app.debug", packageName)
    }

    @Test
    fun javaTimeWorksThroughDesugaring() {
        // Only meaningful below API 26: java.time doesn't exist on API 25, so this passes there only because
        // core library desugaring is on. On API 26+ java.time is native. Proven on the API 25 emulator (task 2.0).
        assertEquals(DayOfWeek.THURSDAY, LocalDate.of(2026, 1, 1).dayOfWeek)
    }
}
