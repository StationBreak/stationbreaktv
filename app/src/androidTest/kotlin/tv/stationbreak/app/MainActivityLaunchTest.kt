// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun javaTimeWorksThroughDesugaring() {
        // java.time doesn't exist on API 25; this passes there only because core library desugaring is on.
        assertEquals(DayOfWeek.THURSDAY, LocalDate.of(2026, 1, 1).dayOfWeek)
    }
}
