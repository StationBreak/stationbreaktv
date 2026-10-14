// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuickConnectTest {
    private val jellyfin = FakeJellyfin()
    private val adapter = testAdapter()
    private val secret = fake("qc-secret")
    private val token = fake("token")
    private var polls = 0

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun enabled(value: Boolean = true) = jellyfin.on("GET", "/QuickConnect/Enabled") { jellyfin.json("$value") }

    private fun initiate() =
        jellyfin.on("POST", "/QuickConnect/Initiate") {
            jellyfin.json("{\"Authenticated\":false,\"Secret\":\"$secret\",\"Code\":\"123456\"}")
        }

    private fun connect(approvedOnPoll: Int? = null) =
        jellyfin.on("GET", "/QuickConnect/Connect") {
            polls++
            val approved = approvedOnPoll != null && polls >= approvedOnPoll
            jellyfin.json("{\"Authenticated\":$approved,\"Secret\":\"$secret\",\"Code\":\"123456\"}")
        }

    private fun finish() {
        jellyfin.on("POST", "/Users/AuthenticateWithQuickConnect") { jellyfin.json(authResult(token)) }
    }

    @Test
    fun checksEnabledFirstThenIssuesAWaitsAndSignsIn() =
        runTest {
            enabled()
            initiate()
            connect(approvedOnPoll = 3)
            finish()
            val states = adapter.quickConnect(jellyfin.address, null).toList()
            assertEquals(QuickConnectState.CodeIssued("123456"), states[0])
            assertEquals(QuickConnectState.Waiting, states[1])
            assertIs<QuickConnectState.SignedIn>(states[2])
            assertEquals(3, states.size)
            assertEquals(
                listOf("/QuickConnect/Enabled", "/QuickConnect/Initiate"),
                jellyfin.log
                    .map { it.url.encodedPath }
                    .filter { it.startsWith("/QuickConnect") }
                    .take(2),
            )
            assertEquals(3, polls)
            // The finish carries the secret in the body, not the URL.
            val finishRequest = jellyfin.requests("/Users/AuthenticateWithQuickConnect").single()
            assertEquals("{\"Secret\":\"$secret\"}", finishRequest.body!!.utf8())
        }

    @Test
    fun aServerWithQuickConnectOffIsQuickConnectDisabledAndNothingIsInitiated() =
        runTest {
            enabled(false)
            initiate()
            val states = adapter.quickConnect(jellyfin.address, null).toList()
            assertEquals(listOf(QuickConnectState.Failed(MediaServerError.QuickConnectDisabled())), states)
            assertTrue(jellyfin.requests("/QuickConnect/Initiate").isEmpty())
        }

    @Test
    fun anInitiateTheServerRefusesIsQuickConnectDisabledToo() =
        runTest {
            enabled()
            jellyfin.on("POST", "/QuickConnect/Initiate") { jellyfin.status(401) }
            val states = adapter.quickConnect(jellyfin.address, null).toList()
            assertEquals(listOf(QuickConnectState.Failed(MediaServerError.QuickConnectDisabled())), states)
        }

    @Test
    fun theServerForgettingTheCodeIsExpired() =
        runTest {
            enabled()
            initiate()
            jellyfin.on("GET", "/QuickConnect/Connect") { jellyfin.status(404) }
            val states = adapter.quickConnect(jellyfin.address, null).toList()
            assertEquals(listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Expired), states)
        }

    @Test
    fun aRefusedPollIsDenied() =
        runTest {
            enabled()
            initiate()
            jellyfin.on("GET", "/QuickConnect/Connect") { jellyfin.status(403) }
            val states = adapter.quickConnect(jellyfin.address, null).toList()
            assertEquals(QuickConnectState.Denied, states.last())
        }

    @Test
    fun stopsAtTheClientLimitEvenIfTheServerNeverExpiresTheCode() =
        runTest {
            enabled()
            initiate()
            connect(approvedOnPoll = null)
            val states = adapter.quickConnect(jellyfin.address, null).toList()
            assertEquals(QuickConnectState.Expired, states.last())
            // 5 minutes at one poll every 5 seconds.
            assertEquals(60, polls)
            assertEquals(5 * 60 * 1000L, currentTime)
            assertEquals(1, states.count { it == QuickConnectState.Waiting })
        }

    @Test
    fun cancellingTheCollectorStopsThePollingAndSendsNothingMore() =
        runTest {
            enabled()
            initiate()
            connect(approvedOnPoll = null)
            val job = launch { adapter.quickConnect(jellyfin.address, null).collect { } }
            advanceTimeBy(12_000)
            job.cancel()
            job.join()
            val before = jellyfin.log.size
            advanceTimeBy(10 * 60 * 1000L)
            assertEquals(before, jellyfin.log.size)
            assertFalse(job.isActive)
        }

    @Test
    fun theSecretIsInNoEmittedStateAndNoStateString() =
        runTest {
            enabled()
            initiate()
            connect(approvedOnPoll = 2)
            finish()
            val states = adapter.quickConnect(jellyfin.address, null).toList()
            for (state in states) assertFalse(state.toString().contains(secret), state::class.simpleName)
        }

    @Test
    fun aCredentialedRedirectOnTheFinishIsRedirected() =
        runTest {
            enabled()
            initiate()
            connect(approvedOnPoll = 1)
            jellyfin.on("POST", "/Users/AuthenticateWithQuickConnect") { jellyfin.redirect("http://127.0.0.1:1/") }
            val states = adapter.quickConnect(jellyfin.address, null).toList()
            assertEquals(QuickConnectState.Failed(MediaServerError.Redirected()), states.last())
        }
}
