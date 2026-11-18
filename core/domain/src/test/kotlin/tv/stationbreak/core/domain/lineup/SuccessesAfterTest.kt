// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.lineup.LineupRefresher.Companion.successesAfter
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The refresher's success trigger (#891): only a success after the one the follow began with is news. */
class SuccessesAfterTest {
    private val before = Instant.parse("2026-10-19T09:00:00Z")
    private val after = Instant.parse("2026-10-19T10:00:00Z")
    private val later = Instant.parse("2026-10-19T11:00:00Z")

    @Test
    fun theSuccessTheFollowBeganWithIsNotNews() =
        runTest {
            val seen = AtomicReference<Instant?>(before)
            assertEquals(listOf(after), flowOf(before, after).successesAfter(seen).toList())
        }

    // The race of #891: the listener subscribed late, after a new success had landed, so its first value is already
    // news. `drop(1)` lost it.
    @Test
    fun aSuccessThatLandedBeforeTheListenerSubscribedIsStillNews() =
        runTest {
            val seen = AtomicReference<Instant?>(before)
            assertEquals(listOf(after), flowOf(after).successesAfter(seen).toList())
        }

    @Test
    fun aFirstSuccessAfterNoneIsNews() =
        runTest {
            val seen = AtomicReference<Instant?>(null)
            assertEquals(listOf(after), flowOf(null, after).successesAfter(seen).toList())
        }

    // Listening again after a failure starts from the last success passed on, so it is not sent twice.
    @Test
    fun listeningAgainDoesNotRepeatTheLastSuccess() =
        runTest {
            val seen = AtomicReference<Instant?>(before)
            assertEquals(listOf(after), flowOf(before, after).successesAfter(seen).toList())
            assertEquals(listOf(later), flowOf(after, later).successesAfter(seen).toList())
        }

    @Test
    fun aRepeatedValueAndANullAreNotNews() =
        runTest {
            val seen = AtomicReference<Instant?>(before)
            assertEquals(listOf(after), flowOf(before, before, after, after, null).successesAfter(seen).toList())
        }
}
