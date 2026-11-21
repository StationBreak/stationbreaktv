// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.crash

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.acra.sender.ReportSenderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The sender posts a redacted report only while the viewer agrees, and keeps one that did not get through (#96). */
class CrashReportSenderTest {
    private val endpoint = checkNotNull(CrashReportEndpoint.parse("https://reports.example.invalid/crash"))
    private val sent = mutableListOf<Pair<Request, String>>()

    private val report: Map<String, Any?> =
        mapOf(
            "REPORT_ID" to "r-1",
            "APP_VERSION_NAME" to "1.0.0",
            "STACK_TRACE" to
                "java.net.UnknownHostException: Unable to resolve host \"media.example.org\"\n\tat a.B.c(B.kt:1)",
            // Not on the allowed list: never sent, whoever collected it.
            "LOGCAT" to "api_key=secret",
            "SHARED_PREFERENCES" to "server=https://media.example.org",
            "USER_EMAIL" to "me@example.org",
        )

    private fun client(code: Int = 200) =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                sent += chain.request() to buffer.readUtf8()
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("m")
                    .body("".toResponseBody())
                    .build()
            }.build()

    private fun sender(
        agreed: Boolean = true,
        main: Boolean = false,
        client: OkHttpClient = client(),
    ) = CrashReportSender({ endpoint.takeIf { agreed } }, client, { main })

    @Test
    fun postsOnlyTheAllowedFields_redacted_toTheEndpoint() {
        sender().deliver(report)
        val (request, body) = sent.single()
        assertEquals("POST", request.method)
        assertEquals("https://reports.example.invalid/crash", request.url.toString())
        assertTrue(body.contains("REPORT_ID"))
        assertTrue(body.contains("java.net.UnknownHostException"))
        listOf("media.example.org", "LOGCAT", "SHARED_PREFERENCES", "USER_EMAIL", "me@", "api_key", "secret").forEach {
            assertFalse("'$it' was sent: $body", body.contains(it))
        }
    }

    @Test
    fun withoutConsentNothingIsSent_andTheReportIsDropped() {
        sender(agreed = false).deliver(report)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aReportWithNoAllowedFieldSendsNothing() {
        sender().deliver(mapOf("LOGCAT" to "x"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aMainThreadReportWaitsForTheNextStart() {
        assertThrows(ReportSenderException::class.java) { sender(main = true).deliver(report) }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aRefusalOrAFailureKeepsTheReportForALaterTry() {
        assertThrows(ReportSenderException::class.java) { sender(client = client(500)).deliver(report) }
        val failing =
            OkHttpClient.Builder().addInterceptor { throw IOException("boom: media.example.org") }.build()
        assertThrows(ReportSenderException::class.java) { sender(client = failing).deliver(report) }
    }

    @Test
    fun theRealClientNeverSpeaksCleartextAndNeverFollowsARedirect() {
        val client = CrashReportSender.defaultClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.connectionSpecs.any { !it.isTls })
    }

    @Test
    fun acraFindsTheFactory_throughItsServiceFile() {
        val found =
            java.util.ServiceLoader
                .load(org.acra.sender.ReportSenderFactory::class.java)
                .toList()
        assertTrue(found.any { it is CrashReportSenderFactory })
        // ACRA's own senders are not in this app: this is the only one.
        assertEquals(1, found.size)
    }
}
