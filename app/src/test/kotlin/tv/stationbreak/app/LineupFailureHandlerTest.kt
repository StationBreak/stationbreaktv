// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LineupFailureHandlerTest {
    @Test
    fun aJobThatThrowsIsLoggedByEventNameOnlyAndDoesNotCrash() {
        val logged = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + lineupFailureHandler { logged += it })

        runBlocking { scope.launch { error("a secret library name") }.join() }

        assertEquals(listOf("BackgroundJobFailed"), logged)
    }

    @Test
    fun theScopeStaysUsableAfterAFailure() {
        val logged = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + lineupFailureHandler { logged += it })
        var ran = false

        runBlocking {
            scope.launch { error("boom") }.join()
            scope.launch { ran = true }.join()
        }

        assertEquals(true, ran)
    }
}
