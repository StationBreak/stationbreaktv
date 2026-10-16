// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The data layer is main-safe: a caller on a UI thread never reads a socket. A server that sends the headers, then
 * the body a byte at a time, makes the body read block for seconds; the thread the caller runs on (standing in for
 * Android's main thread) must not be inside socket or Okio code while that happens. On the old code the call resumed
 * on the caller's thread after the headers and read the body there (`NetworkOnMainThreadException` on Android).
 */
class MainSafeNetworkTest {
    private val server = MockWebServer().apply { start() }
    private val callerThread = Executors.newSingleThreadExecutor { Thread(it, CALLER) }
    private val caller = callerThread.asCoroutineDispatcher()

    @AfterTest
    fun tearDown() {
        callerThread.shutdownNow()
        server.close()
    }

    private fun slowBody() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(INFO)
                .bodyDelay(1500, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    @Test
    fun theProbeReadsABodyThatArrivesAfterTheHeadersOffTheCallersThread() {
        slowBody()
        val probe = ServerProbe(OkHttpClient())
        assertNeverBlockedOnTheNetwork { probe.probe(ServerAddress(Scheme.Http, "127.0.0.1", server.port)) }
    }

    @Test
    fun theTransportReadsABodyThatArrivesAfterTheHeadersOffTheCallersThread() {
        slowBody()
        val header = AuthHeaderBuilder(ClientIdentity("StationBreak", "Test", "0"))
        val transport = Transport(ClientFactory(OkHttpClient(), header, ProbeTimeouts()), DeviceIdProvider { "device" })
        assertNeverBlockedOnTheNetwork {
            transport.send(ServerAddress(Scheme.Http, "127.0.0.1", server.port), Endpoint.get("System/Info/Public"))
        }
    }

    private fun assertNeverBlockedOnTheNetwork(call: suspend () -> Any) =
        runBlocking {
            val running: Deferred<Any> = CoroutineScope(caller).async { call() }
            val seen = mutableListOf<String>()
            while (!running.isCompleted) {
                blockedFrame()?.let(seen::add)
                delay(SAMPLE_MILLIS)
            }
            running.await()
            assertTrue(seen.isEmpty(), "the caller's thread was reading the network: ${seen.firstOrNull()}")
            assertEquals(1, server.requestCount)
        }

    private fun blockedFrame(): String? =
        Thread
            .getAllStackTraces()
            .entries
            // The coroutine debug agent appends " @coroutine#n" to the name of a thread that is running a coroutine.
            .firstOrNull { it.key.name.startsWith(CALLER) }
            ?.value
            ?.firstOrNull { frame -> NETWORK_PREFIXES.any(frame.className::startsWith) }
            ?.toString()

    private companion object {
        const val INFO =
            "{\"ServerName\":\"x\",\"Id\":\"y\",\"Version\":\"12.2.1\"," +
                "\"ProductName\":\"Jellyfin Server\",\"StartupWizardCompleted\":true}"

        const val CALLER = "fake-main"
        const val SAMPLE_MILLIS = 10L
        val NETWORK_PREFIXES = listOf("okio.", "java.net.Socket", "sun.nio.ch.", "sun.security.ssl.")
    }
}
