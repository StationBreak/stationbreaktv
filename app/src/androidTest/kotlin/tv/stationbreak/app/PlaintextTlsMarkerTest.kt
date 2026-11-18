// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.data.jellyfin.PlaintextTlsMarkers
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * F0.3 R5, the two-stack rule: only a positively identified plaintext answer to a TLS hello may make `discover` fall
 * back from https to http, and the two TLS stacks word it differently. The JDK's wording is checked by JVM tests;
 * this runs the same classifier on the API 25 emulator's Conscrypt stack. It uses no SDK and no Jellyfin server: a
 * raw loopback socket plays the part of a LAN server that speaks plain HTTP only.
 */
@RunWith(AndroidJUnit4::class)
class PlaintextTlsMarkerTest {
    // Added to from the server threads while tearDown reads it.
    private val sockets = CopyOnWriteArrayList<AutoCloseable>()

    @After
    fun tearDown() = sockets.forEach { runCatching { it.close() } }

    /**
     * A loopback server that answers every connection with [reply] and then closes it. With [reset], it reads only the
     * start of the hello and resets the connection instead (an abortive close, as a firewall or a refusing server can).
     */
    private fun serverReplying(
        reply: ByteArray,
        reset: Boolean = false,
    ): Int {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        sockets += server
        Thread {
            while (!server.isClosed) {
                val client: Socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += client
                runCatching { if (reset) resetMidHello(client) else answer(client, reply) }
                runCatching { client.close() }
            }
        }.apply { isDaemon = true }.start()
        return server.localPort
    }

    // Reads the whole TLS hello record first, as a real server would, then answers and closes cleanly. (#850:
    // reading at most 512 bytes of a padded hello could leave some unread, so the close reset the connection under
    // the answer.)
    private fun answer(
        client: Socket,
        reply: ByteArray,
    ) {
        readHelloRecord(client)
        client.getOutputStream().write(reply)
        client.getOutputStream().flush()
        client.shutdownOutput()
        client.soTimeout = DRAIN_TIMEOUT_MS
        while (client.getInputStream().read() >= 0) Unit
    }

    private fun resetMidHello(client: Socket) {
        DataInputStream(client.getInputStream()).readFully(ByteArray(RECORD_HEADER_BYTES))
        client.setSoLinger(true, 0)
    }

    /**
     * Reads one TLS record, the client's hello: a 5-byte header (type 22, handshake) whose last two bytes are the
     * body's length. BoringSSL sends its hello in one record today; a client whose hello spans two would need a second
     * read.
     */
    private fun readHelloRecord(client: Socket) {
        val input = DataInputStream(client.getInputStream())
        val header = ByteArray(RECORD_HEADER_BYTES)
        input.readFully(header)
        check(header[0].toInt() == HANDSHAKE_RECORD) { "not a TLS handshake record: ${header[0]}" }
        val length = (header[3].toInt() and BYTE_MASK) shl Byte.SIZE_BITS or (header[4].toInt() and BYTE_MASK)
        check(length <= MAX_RECORD_BYTES) { "a record longer than TLS allows: $length" }
        input.readFully(ByteArray(length))
    }

    /** The error of an https call to a server that answers [reply] instead of a TLS handshake. */
    private fun httpsErrorFor(
        reply: ByteArray,
        reset: Boolean = false,
    ): IOException {
        val port = serverReplying(reply, reset)
        val client = OkHttpClient.Builder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("https://127.0.0.1:$port/").build()
        return try {
            client.newCall(request).execute().close()
            throw AssertionError("the https call to a non-TLS server succeeded")
        } catch (error: IOException) {
            error
        }
    }

    // The whole cause chain: Conscrypt wraps the stack's own wording inside a generic handshake exception. The text
    // is the TLS stack's (the server is a loopback socket), and it is logged so a run records what the stack says.
    private fun describe(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(MAX_CHAIN)
        val chain = causes.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
        Log.i("PlaintextTlsMarkerTest", chain)
        return chain
    }

    // The TLS stack's own messages in the chain: what the client parsed, not a timeout or a failed connect.
    private fun sslMessages(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(MAX_CHAIN)
            .filterIsInstance<SSLException>()
            .joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun anHttpAnswerToATlsHelloIsPositivelyPlaintext() {
        val error = httpsErrorFor(KESTREL_400.toByteArray())

        // The message is the TLS stack's own wording (no host, no secret); it is in the failure text so a red run
        // records what Conscrypt says.
        val chain = describe(error)
        assertTrue("not recognised as plaintext: $chain", PlaintextTlsMarkers.indicatesPlaintext(error))
    }

    // Repeated, so a run that words an alert as plaintext even once in a while (#850) fails here, not on another PR.
    @Test
    fun aTlsAlertIsNotPlaintext() {
        // A TLS 1.2 alert record: handshake_failure. A server that speaks TLS but refuses is not a plaintext server.
        val alert = byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28)
        repeat(REPEATS) { attempt ->
            val error = httpsErrorFor(alert)
            val chain = describe(error)
            // The client really received and parsed the alert: a pass for any other reason would prove nothing.
            assertTrue("attempt $attempt: no alert in the chain: $chain", "_ALERT_" in sslMessages(error).uppercase())
            assertFalse("attempt $attempt taken for plaintext: $chain", PlaintextTlsMarkers.indicatesPlaintext(error))
        }
    }

    // #850's likely cause: a TLS server that resets mid-hello, on a thread that has just seen a plaintext answer (a
    // version error left on the TLS library's per-thread error queue could be reported for the reset). Interleaved and
    // repeated so a stale error shows here; a reset must never read as plaintext, or https would fall back to http.
    @Test
    fun aResetAfterAPlaintextAnswerIsNotPlaintext() {
        repeat(REPEATS) { attempt ->
            val plaintext = httpsErrorFor(KESTREL_400.toByteArray())
            assertTrue("attempt $attempt: ${describe(plaintext)}", PlaintextTlsMarkers.indicatesPlaintext(plaintext))
            val reset = httpsErrorFor(ByteArray(0), reset = true)
            val misread = PlaintextTlsMarkers.indicatesPlaintext(reset)
            assertFalse("attempt $attempt reset taken for plaintext: ${describe(reset)}", misread)
        }
    }

    @Test
    fun aConnectionClosedMidHandshakeIsNotPlaintext() {
        val error = httpsErrorFor(ByteArray(0))

        assertFalse("taken for plaintext: ${describe(error)}", PlaintextTlsMarkers.indicatesPlaintext(error))
    }

    private companion object {
        // What Kestrel, behind a Jellyfin on a LAN, sends to a TLS hello.
        const val KESTREL_400 = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        const val RECORD_HEADER_BYTES = 5
        const val HANDSHAKE_RECORD = 22
        const val MAX_RECORD_BYTES = 16_384 + 2_048
        const val BYTE_MASK = 0xff
        const val DRAIN_TIMEOUT_MS = 2_000
        const val REPEATS = 10
        const val MAX_CHAIN = 6
        const val CALL_TIMEOUT_SECONDS = 10L
    }
}
