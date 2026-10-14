// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.data.jellyfin.PlaintextTlsMarkers
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * F0.3 R5, the two-stack rule: only a positively identified plaintext answer to a TLS hello may make `discover` fall
 * back from https to http, and the two TLS stacks word it differently. The JDK's wording is checked by JVM tests;
 * this runs the same classifier on the API 25 emulator's Conscrypt stack. It uses no SDK and no Jellyfin server: a
 * raw loopback socket plays the part of a LAN server that speaks plain HTTP only.
 */
@RunWith(AndroidJUnit4::class)
class PlaintextTlsMarkerTest {
    private val sockets = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = sockets.forEach { runCatching { it.close() } }

    /** A loopback server that answers every connection with [reply] and then closes it. */
    private fun serverReplying(reply: ByteArray): Int {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        sockets += server
        Thread {
            while (!server.isClosed) {
                val client: Socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += client
                runCatching {
                    // Read the TLS hello first, as a real server would see it, then answer.
                    client.getInputStream().read(ByteArray(HELLO_BYTES))
                    client.getOutputStream().write(reply)
                    client.getOutputStream().flush()
                    client.close()
                }
            }
        }.apply { isDaemon = true }.start()
        return server.localPort
    }

    /** The error of an https call to a server that answers [reply] instead of a TLS handshake. */
    private fun httpsErrorFor(reply: ByteArray): IOException {
        val port = serverReplying(reply)
        val client = OkHttpClient.Builder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("https://127.0.0.1:$port/").build()
        return try {
            client.newCall(request).execute().close()
            throw AssertionError("the https call to a non-TLS server succeeded")
        } catch (error: IOException) {
            error
        }
    }

    private fun describe(error: Throwable) = "${error.javaClass.name}: ${error.message}"

    @Test
    fun anHttpAnswerToATlsHelloIsPositivelyPlaintext() {
        // What Kestrel, behind a Jellyfin on a LAN, sends to a TLS hello.
        val answer = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        val error = httpsErrorFor(answer.toByteArray())

        // The message is the TLS stack's own wording (no host, no secret); it is in the failure text so a red run
        // records what Conscrypt says.
        assertTrue("not recognised as plaintext: ${describe(error)}", PlaintextTlsMarkers.indicatesPlaintext(error))
    }

    @Test
    fun aTlsAlertIsNotPlaintext() {
        // A TLS 1.2 alert record: handshake_failure. A server that speaks TLS but refuses is not a plaintext server.
        val alert = byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28)
        val error = httpsErrorFor(alert)

        assertFalse("taken for plaintext: ${describe(error)}", PlaintextTlsMarkers.indicatesPlaintext(error))
    }

    @Test
    fun aConnectionClosedMidHandshakeIsNotPlaintext() {
        val error = httpsErrorFor(ByteArray(0))

        assertFalse("taken for plaintext: ${describe(error)}", PlaintextTlsMarkers.indicatesPlaintext(error))
    }

    private companion object {
        const val HELLO_BYTES = 512
        const val CALL_TIMEOUT_SECONDS = 10L
    }
}
