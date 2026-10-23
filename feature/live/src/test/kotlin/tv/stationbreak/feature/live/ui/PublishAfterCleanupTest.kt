// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PublishAfterCleanupTest {
    @Test
    fun theCleanupCompletesBeforeSignedOutIsPublished() =
        runTest {
            val events = mutableListOf<String>()
            publishAfterCleanup({ events += "cleanup" }, { events += "publish" })
            assertEquals(listOf("cleanup", "publish"), events)
        }

    @Test
    fun aCancelDuringTheCleanupDoesNotDropIt() =
        runTest {
            val events = mutableListOf<String>()
            val gate = CompletableDeferred<Unit>()
            val job =
                async {
                    publishAfterCleanup(
                        {
                            gate.await()
                            events += "cleanup"
                        },
                        { events += "publish" },
                    )
                }
            advanceUntilIdle()
            job.cancel()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("cleanup", "publish"), events)
        }
}
