// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * F0.3 task 2.13 (ADR-0006, logging): the adapter makes a probe, a password sign-in and a failing call on the API 25
 * emulator, with the app's runtime classpath (which carries `slf4j-nop`). It covers what the JVM tests can't: the
 * adapter's OkHttp, kotlinx.serialization and error-mapping code (which loads the Jellyfin SDK's exception types) on
 * the old platform's class loader and TLS stack. A raw loopback socket plays the server; nothing here is a real
 * credential or address.
 */
@RunWith(AndroidJUnit4::class)
class AdapterSmokeOnApi25Test {
    private val sockets = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = sockets.forEach { runCatching { it.close() } }

    @Test
    fun probesSignsInAndMapsAFailureOnApi25() {
        val port = serve()
        val adapter =
            JellyfinMediaServer(
                OkHttpClient(),
                ClientIdentity("StationBreak", "Smoke test", "0.0.0"),
                DeviceIdProvider { "smoke-device" },
                AdapterLog.NONE,
            )
        blocking {
            val probed = adapter.discover("http://127.0.0.1:$port")
            assertTrue("probe: $probed", probed is ServerResult.Success)
            val address = (probed as ServerResult.Success).value.address

            val signedIn = adapter.signIn(SignInRequest(address, "smoke", "not-a-real-password".toCharArray(), null))
            assertTrue("sign-in: $signedIn", signedIn is ServerResult.Success)
            assertEquals("smoke-user", (signedIn as ServerResult.Success).value.userId.value)

            // Nothing listens on the closed port: the mapper (and the SDK exception types it reads) must load.
            sockets.forEach { runCatching { it.close() } }
            val refused = adapter.discover("http://127.0.0.1:$port")
            assertTrue("refused: $refused", refused is ServerResult.Failure)
            assertTrue((refused as ServerResult.Failure).error is MediaServerError)
        }
    }

    // No kotlinx-coroutines on the test's compile classpath (it clashes with the app's pinned version in Lint's model).
    private fun <T> blocking(block: suspend () -> T): T {
        val done = CountDownLatch(1)
        var outcome: Result<T>? = null
        block.startCoroutine(
            Continuation(EmptyCoroutineContext) {
                outcome = it
                done.countDown()
            },
        )
        check(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the adapter call didn't finish" }
        return outcome!!.getOrThrow()
    }

    private fun serve(): Int {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        sockets += server
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                sockets += client
                Thread { answer(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return server.localPort
    }

    private fun answer(client: Socket) {
        runCatching {
            val input = BufferedInputStream(client.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { check(it >= 0) }.toChar())
            val length =
                Regex("(?i)content-length: (\\d+)")
                    .find(head)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 0
            repeat(length) { input.read() }
            val body = if (head.startsWith("GET /System/Info/Public")) PUBLIC_INFO else AUTH_RESULT
            val bytes = body.toByteArray()
            client.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    ).toByteArray() + bytes,
                )
                flush()
            }
            client.close()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val PUBLIC_INFO =
            "{\"ServerName\":\"Smoke\",\"Version\":\"12.2.1\",\"Id\":\"smoke-server\"," +
                "\"ProductName\":\"Jellyfin Server\",\"StartupWizardCompleted\":true,\"OperatingSystem\":\"\"}"
        const val AUTH_RESULT =
            "{\"User\":{\"Id\":\"smoke-user\",\"Policy\":{\"IsAdministrator\":false}}," +
                "\"AccessToken\":\"smoke-token\",\"ServerId\":\"smoke-server\"}"
    }
}
