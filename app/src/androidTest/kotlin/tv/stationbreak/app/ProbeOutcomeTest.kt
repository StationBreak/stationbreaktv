// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Spec 03, Unit 4 (R17): the on-device probe's output is only PASS or FAIL, a taxonomy type name and the server's
 * major version. Every exception here carries a host, a URL and a token in its message, and the test fails if any of
 * them reaches the output. It runs on the emulator because `androidTest` code only runs there.
 */
class ProbeOutcomeTest {
    private val host = "secret-host.example.invalid"
    private val token = "TOKEN-d41d8cd98f00b204"
    private val hostile = "https://$host:8920/System/Info/Public?api_key=$token failed for $host"

    private val taxonomy =
        setOf(
            "TlsUntrusted",
            "TlsHostnameMismatch",
            "TlsCertificateExpired",
            "TlsHandshakeFailed",
            "NotTls",
            "ConnectionRefused",
            "NetworkUnreachable",
            "DnsFailure",
            "Timeout",
            "Unknown",
        )
    private val shape = Regex("""ProbeOutcome result=(PASS|FAIL) type=([A-Za-z]+) major=(\d{1,3}|none)""")

    private fun typeOf(failure: Throwable): String {
        val line = ProbeOutcome.failure(failure)
        assertNoLeak(line)
        val match = shape.matchEntire(line)
        assertTrue("unexpected shape: $line", match != null)
        assertEquals("FAIL", match!!.groupValues[1])
        assertEquals("none", match.groupValues[3])
        assertTrue("not a taxonomy name: ${match.groupValues[2]}", match.groupValues[2] in taxonomy)
        return match.groupValues[2]
    }

    private fun assertNoLeak(line: String) {
        assertFalse(line, line.contains(host))
        assertFalse(line, line.contains(token))
        assertFalse(line, line.contains("https://"))
        assertFalse(line, line.contains("api_key"))
    }

    @Test
    fun untrustedChain() {
        val cause = CertPathValidatorException(hostile)
        assertEquals("TlsUntrusted", typeOf(SSLHandshakeException(hostile).initCause(cause)))
        assertEquals("TlsUntrusted", typeOf(SSLHandshakeException(hostile).initCause(CertificateException(hostile))))
    }

    @Test
    fun expiredCertificateWinsOverTheValidatorWrapper() {
        val expired = CertificateExpiredException(hostile)
        val wrapped = CertPathValidatorException(hostile, expired)
        assertEquals("TlsCertificateExpired", typeOf(SSLHandshakeException(hostile).initCause(wrapped)))
    }

    @Test
    fun hostnameMismatch() {
        assertEquals("TlsHostnameMismatch", typeOf(SSLPeerUnverifiedException("Hostname $host not verified: $token")))
    }

    @Test
    fun tls13OnlyServerReadsAsAHandshakeFailure() {
        assertEquals("TlsHandshakeFailed", typeOf(SSLHandshakeException("Handshake failed for $hostile")))
    }

    @Test
    fun plaintextAnswerToATlsAttemptIsNotTls() {
        val notTls = SSLHandshakeException("Unable to parse TLS packet header from $hostile")
        assertEquals("NotTls", typeOf(notTls))
    }

    @Test
    fun networkFailures() {
        assertEquals("ConnectionRefused", typeOf(ConnectException("Connection refused: $hostile")))
        assertEquals("NetworkUnreachable", typeOf(ConnectException("Network is unreachable: $hostile")))
        assertEquals("NetworkUnreachable", typeOf(NoRouteToHostException(hostile)))
        assertEquals("DnsFailure", typeOf(UnknownHostException(hostile)))
        assertEquals("Timeout", typeOf(SocketTimeoutException(hostile)))
    }

    @Test
    fun anythingElseIsUnknownAndKeepsNoMessage() {
        assertEquals("Unknown", typeOf(IllegalStateException(hostile)))
        assertEquals("Unknown", typeOf(StackOverflowError()))
        assertEquals("Unknown", typeOf(RuntimeException(hostile, RuntimeException(hostile))))
    }

    @Test
    fun aCauseLoopCannotHangTheClassifier() {
        val a = RuntimeException(hostile)
        val b = RuntimeException(hostile, a)
        a.initCause(b)
        assertEquals("Unknown", typeOf(a))
    }

    @Test
    fun passReportsOnlyTheMajorVersion() {
        val body =
            """{"LocalAddress":"http://$host:8096","ServerName":"$host","Version":"12.1.0","Id":"$token",""" +
                """"ProductName":"Jellyfin Server"}"""
        val line = ProbeOutcome.success(body)
        assertNoLeak(line)
        assertEquals("ProbeOutcome result=PASS type=None major=12", line)
    }

    @Test
    fun aBodyWithoutAUsableVersionIsAFailureNotAPass() {
        listOf("""{"Version":"abc $token"}""", """{"Id":"$token"}""", "<html>$host</html>", "", """{"Version":"12"}""")
            .forEach { body ->
                val line = ProbeOutcome.success(body)
                assertNoLeak(line)
                assertEquals("ProbeOutcome result=FAIL type=Unknown major=none", line)
            }
    }

    @Test
    fun anUnexpectedStatusIsAFailureWithoutTheBody() {
        val line = ProbeOutcome.status()
        assertEquals("ProbeOutcome result=FAIL type=Unknown major=none", line)
    }

    @Test
    fun anAddressThatIsNotHttpsFailsAsNotTlsBeforeAnyConnection() {
        listOf("http://$host", "HTTP://$host:8096", "ftp://$host", host, "", "  http://$host").forEach { address ->
            val line = ProbeOutcome.requireHttps(address)
            assertEquals("ProbeOutcome result=FAIL type=NotTls major=none", line)
            assertNoLeak(line.orEmpty())
        }
        listOf("https://$host", "HTTPS://$host:8920", " https://$host").forEach { address ->
            assertEquals(null, ProbeOutcome.requireHttps(address))
        }
    }
}
