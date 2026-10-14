// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import tv.stationbreak.core.domain.MediaServerError
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.UUID
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** S0.3.4 and Angua (Security) R11: one case per mapped error, the whole cause chain, and nothing leaking. */
class ErrorMapperTest {
    private fun map(
        error: Throwable,
        tokenBearing: Boolean = false,
        secure: Boolean = false,
    ) = ErrorMapper.map(error, tokenBearing, secure)

    private fun handshake(cause: Throwable) = SSLHandshakeException("validity check failed").apply { initCause(cause) }

    // Network.

    @Test
    fun dnsFailure() {
        assertIs<MediaServerError.DnsFailure>(map(UnknownHostException("media.example.com")))
    }

    @Test
    fun connectionRefused() {
        assertIs<MediaServerError.ConnectionRefused>(map(ConnectException("Connection refused")))
        assertIs<MediaServerError.ConnectionRefused>(
            map(ConnectException("failed to connect to /192.168.1.10 (port 8096): ECONNREFUSED (Connection refused)")),
        )
    }

    @Test
    fun networkUnreachable() {
        assertIs<MediaServerError.NetworkUnreachable>(map(NoRouteToHostException("No route to host")))
        assertIs<MediaServerError.NetworkUnreachable>(map(SocketException("Network is unreachable")))
        assertIs<MediaServerError.NetworkUnreachable>(
            map(ConnectException("failed to connect to /10.0.0.9: ENETUNREACH (Network is unreachable)")),
        )
    }

    @Test
    fun timeouts() {
        assertIs<MediaServerError.Timeout>(map(SocketTimeoutException("timeout")))
        assertIs<MediaServerError.Timeout>(map(InterruptedIOException("timeout")))
        assertIs<MediaServerError.Timeout>(map(ConnectException("Connection timed out")))
        assertIs<MediaServerError.Timeout>(map(TimeoutException("Request timed out", null)))
    }

    // TLS.

    @Test
    fun untrustedCertificate() {
        val chain =
            SSLHandshakeException("PKIX path building failed").apply {
                initCause(CertPathValidatorException("Trust anchor for certification path not found."))
            }
        assertIs<MediaServerError.TlsUntrusted>(map(chain))
    }

    @Test
    fun hostnameMismatch() {
        val error = SSLPeerUnverifiedException("Hostname media.example.com not verified")
        assertIs<MediaServerError.TlsHostnameMismatch>(map(error))
    }

    @Test
    fun expiredCertificate() {
        assertIs<MediaServerError.TlsCertificateExpired>(map(handshake(CertificateExpiredException("NotAfter: Mon"))))
        assertIs<MediaServerError.TlsCertificateExpired>(map(handshake(CertificateNotYetValidException("NotBefore"))))
    }

    @Test
    fun expiredBeatsUntrustedWhenBothAreInTheChain() {
        val inner = CertPathValidatorException("validity check failed", CertificateExpiredException("NotAfter"))
        val outer = SSLHandshakeException("PKIX path validation failed").apply { initCause(inner) }
        assertIs<MediaServerError.TlsCertificateExpired>(map(outer))
    }

    @Test
    fun handshakeFailure() {
        val alert = SSLHandshakeException("Received fatal alert: handshake_failure")
        assertIs<MediaServerError.TlsHandshakeFailed>(map(alert))
        val closed = SSLHandshakeException("Remote host terminated the handshake")
        assertIs<MediaServerError.TlsHandshakeFailed>(map(closed))
        assertIs<MediaServerError.TlsHandshakeFailed>(map(SSLException("something nobody has seen before")))
    }

    @Test
    fun aPlaintextAnswerToATlsHelloIsNotTls() {
        assertIs<MediaServerError.NotTls>(map(SSLException("Unsupported or unrecognized SSL message")))
    }

    @Test
    fun aResetOrEofIsAHandshakeFailureOnTlsAndUnknownOtherwise() {
        assertIs<MediaServerError.TlsHandshakeFailed>(map(SocketException("Connection reset"), secure = true))
        assertIs<MediaServerError.TlsHandshakeFailed>(map(EOFException(), secure = true))
        // A dropped plain-http connection says nothing about the server's health: not ServerError.
        assertIs<MediaServerError.Unknown>(map(SocketException("Connection reset")))
        assertIs<MediaServerError.Unknown>(map(IOException("unexpected end of stream")))
    }

    // HTTP status.

    @Test
    fun httpStatuses() {
        assertIs<MediaServerError.HttpUnauthorized>(ErrorMapper.fromStatus(401))
        assertIs<MediaServerError.HttpForbidden>(ErrorMapper.fromStatus(403))
        assertIs<MediaServerError.NotFound>(ErrorMapper.fromStatus(404))
        assertIs<MediaServerError.ServerError>(ErrorMapper.fromStatus(500))
        assertIs<MediaServerError.ServerError>(ErrorMapper.fromStatus(503))
        assertIs<MediaServerError.Unknown>(ErrorMapper.fromStatus(418))
    }

    @Test
    fun aTokenRejectedOnATokenBearingCallIsSessionExpired() {
        assertIs<MediaServerError.SessionExpired>(ErrorMapper.fromStatus(401, tokenBearing = true))
        assertIs<MediaServerError.SessionExpired>(map(InvalidStatusException(401, null), tokenBearing = true))
        assertIs<MediaServerError.HttpUnauthorized>(map(InvalidStatusException(401, null), tokenBearing = false))
        assertIs<MediaServerError.HttpForbidden>(map(InvalidStatusException(403, null), tokenBearing = true))
    }

    @Test
    fun anSdkStatusExceptionMapsByItsStatus() {
        assertIs<MediaServerError.HttpForbidden>(map(InvalidStatusException(403, null)))
        assertIs<MediaServerError.ServerError>(map(InvalidStatusException(502, null)))
    }

    @Test
    fun anSdkContentExceptionIsNotJellyfin() {
        assertIs<MediaServerError.NotJellyfin>(map(InvalidContentException("bad json", null)))
    }

    @Test
    fun anythingElseIsUnknown() {
        assertIs<MediaServerError.Unknown>(map(IllegalStateException("boom")))
        assertIs<MediaServerError.Unknown>(map(ApiClientException("wrapper", null)))
    }

    // The whole cause chain.

    @Test
    fun theSdkWrapperIsMappedByItsCauseNoMatterHowDeep() {
        val inner = ApiClientException("again", ConnectException("Connection refused"))
        val refused = ApiClientException("Failed request", inner)
        assertIs<MediaServerError.ConnectionRefused>(map(refused))
        val lookup = ApiClientException("x", IOException("io", UnknownHostException("h")))
        assertIs<MediaServerError.DnsFailure>(map(lookup))
        assertIs<MediaServerError.Timeout>(map(ApiClientException("x", SocketTimeoutException("timeout"))))
    }

    @Test
    fun secureConnectionExceptionIsMappedByTheCertificateCauseUnderneath() {
        fun secure(cause: Throwable) = SecureConnectionException("TLS", cause)
        val untrusted =
            SSLHandshakeException("PKIX").apply {
                initCause(CertPathValidatorException("Trust anchor for certification path not found."))
            }
        assertIs<MediaServerError.TlsUntrusted>(map(secure(untrusted)))
        val mismatch = SSLPeerUnverifiedException("Hostname h not verified")
        assertIs<MediaServerError.TlsHostnameMismatch>(map(secure(mismatch)))
        val expired = SSLHandshakeException("v").apply { initCause(CertificateExpiredException("NotAfter")) }
        assertIs<MediaServerError.TlsCertificateExpired>(map(secure(expired)))
        val alert = SSLHandshakeException("Received fatal alert: handshake_failure")
        assertIs<MediaServerError.TlsHandshakeFailed>(map(secure(alert)))
        assertIs<MediaServerError.TlsHandshakeFailed>(map(SecureConnectionException("TLS", null)))
    }

    @Test
    fun aFailureSuppressedByAnotherIsReadToo() {
        val unreachable =
            ConnectException(
                "Network is unreachable",
            ).apply { addSuppressed(SSLHandshakeException("PKIX path building failed")) }
        val error = map(unreachable)
        assertIs<MediaServerError.TlsUntrusted>(error)
        assertTrue(
            "java.net.ConnectException" in error.causes && "javax.net.ssl.SSLHandshakeException" in error.causes,
            error.causes.toString(),
        )
    }

    @Test
    fun aPlaintextMarkerDoesNotHideAnotherTlsFailureInTheChain() {
        val plaintext = SSLException("Unsupported or unrecognized SSL message")
        val other = SSLHandshakeException("Received fatal alert: handshake_failure")
        assertIs<MediaServerError.NotTls>(map(plaintext))
        val twoAddresses = IOException("two addresses", plaintext).apply { addSuppressed(other) }
        assertIs<MediaServerError.TlsHandshakeFailed>(map(twoAddresses))
    }

    @Test
    fun aChainTooLongToReadFailsClosedInsteadOfAllowingTheFallback() {
        // The TLS failure is past the cap, so it is never read: a refusal must not be reported as one.
        val refused = ConnectException("Connection refused")
        repeat(MAX_CAUSE_CHAIN) { refused.addSuppressed(IOException("filler $it")) }
        refused.addSuppressed(SSLHandshakeException("PKIX path building failed"))
        assertIs<MediaServerError.Unknown>(map(refused))
        assertIs<MediaServerError.ConnectionRefused>(map(ConnectException("Connection refused")))
    }

    @Test
    fun cancellationIsRethrownNeverMapped() {
        assertFailsWith<CancellationException> { map(CancellationException("cancelled")) }
    }

    // Nothing leaks.

    @Test
    fun aCauseChainWithAUrlAHostAndATokenYieldsClassNamesOnly() {
        // Generated per run: a fake token, and no literal for a secret scanner to find.
        val secret = "tok-" + UUID.randomUUID()
        val url = "https://media.example.com:8920/Items?ApiKey=$secret"
        val deepest = ConnectException("failed to connect to media.example.com/203.0.113.7 (port 8920): $url refused")
        val chain =
            ApiClientException(
                "GET $url failed with Authorization: MediaBrowser Token=\"$secret\"",
                IOException("Unable to resolve host \"media.example.com\" for $url", deepest),
            )

        val error = map(chain)

        assertIs<MediaServerError.ConnectionRefused>(error)
        val text = error.toString()
        val leaks = listOf(secret, "media.example.com", "203.0.113.7", "ApiKey", "Authorization", "MediaBrowser")
        for (leak in leaks + listOf("https://", "8920")) {
            assertFalse(leak in text, "the mapped error leaks '$leak'")
        }
        assertEquals(
            listOf(
                "org.jellyfin.sdk.api.client.exception.ApiClientException",
                "java.io.IOException",
                "java.net.ConnectException",
            ),
            error.causes,
        )
        val classNameCharacters = Regex("""[A-Za-z0-9_.$]+""")
        assertTrue(error.causes.all(classNameCharacters::matches))
    }

    @Test
    fun aCauseCycleAndAVeryDeepChainAreHandled() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertIs<MediaServerError.Unknown>(map(a))
        var deep: Throwable = ConnectException("Connection refused")
        repeat(100) { deep = IOException("wrap", deep) }
        // Past the depth limit the refused cause isn't reached: an answer, never a hang or a stack overflow.
        assertTrue(map(deep).causes.size <= MAX_CAUSE_CHAIN)
    }
}
