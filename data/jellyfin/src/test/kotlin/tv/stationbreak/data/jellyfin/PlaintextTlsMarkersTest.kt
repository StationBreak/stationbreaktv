// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import java.io.IOException
import java.net.SocketException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Angua (Security) R5: only a positively identified plaintext answer to a TLS hello counts, on either TLS stack. */
class PlaintextTlsMarkersTest {
    @Test
    fun everyKnownMarkerIsRecognisedOnAnSslException() {
        val markers = PlaintextTlsMarkers.JVM + PlaintextTlsMarkers.CONSCRYPT
        assertTrue(markers.isNotEmpty())
        for (marker in markers) {
            assertTrue(PlaintextTlsMarkers.indicatesPlaintext(SSLException("prefix $marker suffix")), marker)
            assertTrue(PlaintextTlsMarkers.indicatesPlaintext(SSLHandshakeException(marker)), marker)
        }
    }

    @Test
    fun theMarkerMayBeAnywhereInTheCauseChain() {
        val marker = SSLException("Unsupported or unrecognized SSL message")
        val wrapped = IOException("outer", IOException("middle", marker))
        assertTrue(PlaintextTlsMarkers.indicatesPlaintext(wrapped))
    }

    @Test
    fun handshakeAlertsResetsAndEofAreNotPlaintext() {
        val notPlaintext =
            listOf(
                SSLHandshakeException("Received fatal alert: handshake_failure"),
                SSLHandshakeException("Received fatal alert: protocol_version"),
                SSLHandshakeException("Remote host terminated the handshake"),
                SSLException("Connection reset"),
                SSLException("Read error: ssl=0x1: Failure in SSL library, usually a protocol error"),
                SSLException("Unexpected end of stream"),
                SocketException("Connection reset"),
                IOException("something"),
            )
        for (error in notPlaintext) assertFalse(PlaintextTlsMarkers.indicatesPlaintext(error), error.toString())
    }

    // A server that sent a TLS alert speaks TLS, so a plaintext marker in the same chain is not believed.
    @Test
    fun aMarkerBesideAReceivedAlertIsNotPlaintext() {
        val withAlert =
            listOf(
                SSLHandshakeException("Received fatal alert: handshake_failure").apply {
                    initCause(SSLException("WRONG_VERSION_NUMBER"))
                },
                IOException(
                    "outer",
                    SSLException("TLSV1_ALERT_PROTOCOL_VERSION", SSLException("wrong version number")),
                ),
            )
        for (error in withAlert) assertFalse(PlaintextTlsMarkers.indicatesPlaintext(error), error.toString())
    }

    // Only the TLS stack's own words count: "alert" in a non-TLS message does not hide a real plaintext answer.
    @Test
    fun theWordAlertOutsideAnSslExceptionDoesNotHideAMarker() {
        val error = IOException("alert from somewhere else", SSLException("Unsupported or unrecognized SSL message"))
        assertTrue(PlaintextTlsMarkers.indicatesPlaintext(error))
    }

    @Test
    fun aMarkerInANonSslExceptionDoesNotCount() {
        assertFalse(PlaintextTlsMarkers.indicatesPlaintext(IOException("Unsupported or unrecognized SSL message")))
    }
}
