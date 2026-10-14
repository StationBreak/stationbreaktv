// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

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
 * A raw loopback socket that plays a Jellyfin server for the instrumented smoke tests: it answers
 * `GET /System/Info/Public` with a public-info body and every other request with a sign-in answer. Nothing here is a
 * real credential or address. [close] stops it, and every connection it holds.
 */
internal class LoopbackJellyfin : AutoCloseable {
    private val sockets = mutableListOf<AutoCloseable>()
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1")).also { sockets += it }

    val port: Int get() = server.localPort

    init {
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(sockets) { sockets += client }
                Thread { answer(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() = synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }

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

    companion object {
        private const val BACKLOG = 5
        private const val TIMEOUT_SECONDS = 30L
        const val PUBLIC_INFO =
            "{\"ServerName\":\"Smoke\",\"Version\":\"12.2.1\",\"Id\":\"smoke-server\"," +
                "\"ProductName\":\"Jellyfin Server\",\"StartupWizardCompleted\":true,\"OperatingSystem\":\"\"}"
        const val AUTH_RESULT =
            "{\"User\":{\"Id\":\"smoke-user\",\"Policy\":{\"IsAdministrator\":false}}," +
                "\"AccessToken\":\"smoke-token\",\"ServerId\":\"smoke-server\"}"

        /**
         * Runs a suspending call to completion and returns its result. There is no kotlinx-coroutines on the tests'
         * compile classpath (it clashes with the app's pinned version in Lint's model), so this starts it by hand.
         */
        fun <T> blocking(block: suspend () -> T): T {
            val done = CountDownLatch(1)
            var outcome: Result<T>? = null
            block.startCoroutine(
                Continuation(EmptyCoroutineContext) {
                    outcome = it
                    done.countDown()
                },
            )
            check(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the call didn't finish" }
            return outcome!!.getOrThrow()
        }
    }
}
