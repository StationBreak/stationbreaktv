// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.ServerVersion

/** Decision 5: every error gets a plain sentence, a details line and one next step, from one table. */
class ErrorCopyTest {
    private val variants: List<Class<*>> = MediaServerError::class.java.permittedSubclasses!!.toList()

    @Test
    fun everyMediaServerErrorHasAnEntry() {
        // Fails the day a subtype is added without copy: the table is keyed by class, so a missing entry is a gap.
        val missing = variants.filterNot { ErrorCopyTable.entries.containsKey(it) }.map { it.simpleName }
        assertEquals("No copy for: $missing", emptyList<String>(), missing)
    }

    @Test
    fun theTableHoldsNoEntryForAnErrorThatDoesNotExist() {
        val extra =
            ErrorCopyTable.entries.keys
                .filterNot { it in variants }
                .map { it.simpleName }
        assertEquals("Entries for removed errors: $extra", emptyList<String>(), extra)
    }

    @Test
    fun everyEntryHasASentenceADetailsLineAndANextStep() {
        for ((type, copy) in ErrorCopyTable.entries) {
            assertNotEquals("${type.simpleName} message", 0, copy.message)
            assertNotEquals("${type.simpleName} details", 0, copy.details)
            assertNotEquals("${type.simpleName} has a primary action", copy.secondary, copy.primary)
        }
    }

    @Test
    fun networkErrorsOfferRetryAndEditAddress() {
        val network =
            listOf(
                MediaServerError.DnsFailure(),
                MediaServerError.ConnectionRefused(),
                MediaServerError.NetworkUnreachable(),
                MediaServerError.Timeout(),
            )
        for (error in network) {
            val copy = ErrorCopyTable.copyFor(error)
            assertEquals(error.toString(), ErrorGroup.Network, copy.group)
            assertEquals(error.toString(), ErrorAction.Retry, copy.primary)
            assertEquals(error.toString(), ErrorAction.EditAddress, copy.secondary)
        }
    }

    @Test
    fun notUsableErrorsHaveTheirOwnGroupAndOneAction() {
        val notUsable =
            listOf(
                MediaServerError.NotJellyfin(),
                MediaServerError.UnsupportedVersion(ServerVersion(11, 0, 0)),
                MediaServerError.ServerNotSetUp(),
                MediaServerError.Redirected(),
            )
        for (error in notUsable) {
            val copy = ErrorCopyTable.copyFor(error)
            assertEquals(error.toString(), ErrorGroup.NotUsable, copy.group)
            assertNull(error.toString(), copy.secondary)
        }
    }

    @Test
    fun unsupportedVersionNamesTheVersionFound() {
        val args = ErrorCopyTable.messageArgs(MediaServerError.UnsupportedVersion(ServerVersion(10, 11, 8)))
        assertEquals(listOf("10.11.8"), args)
        assertEquals(emptyList<Any>(), ErrorCopyTable.messageArgs(MediaServerError.DnsFailure()))
    }

    @Test
    fun addressAndTlsGroups() {
        assertEquals(ErrorGroup.Address, ErrorCopyTable.copyFor(MediaServerError.InvalidAddress()).group)
        for (
        error in
        listOf(
            MediaServerError.TlsUntrusted(),
            MediaServerError.TlsHostnameMismatch(),
            MediaServerError.TlsCertificateExpired(),
            MediaServerError.TlsHandshakeFailed(),
            MediaServerError.NotTls(),
        )
        ) {
            assertEquals(error.toString(), ErrorGroup.Tls, ErrorCopyTable.copyFor(error).group)
        }
    }

    @Test
    fun noTlsErrorOffersATrustAction() {
        // Trusting a certificate is Unit 3 (and never on TlsHandshakeFailed). Until then the only actions are these.
        val actions = ErrorAction.entries.map { it.name }.toSet()
        assertEquals(setOf("Retry", "EditAddress"), actions)
    }

    @Test
    fun detailsLineNamesTheCauseClassesAndNothingElse() {
        val error = MediaServerError.DnsFailure(listOf("UnknownHostException", "IOException"))
        assertEquals("UnknownHostException, IOException", ErrorCopyTable.causeText(error))
        assertTrue(ErrorCopyTable.causeText(MediaServerError.Timeout()).isEmpty())
    }

    @Test
    fun causeTextKeepsOnlyClassNameShapedText() {
        // The port promises class names; the screen still refuses anything that looks like a URL or a header.
        val error = MediaServerError.Unknown(listOf("java.net.UnknownHostException", "https://jellyfin.example.test/x"))
        assertEquals("UnknownHostException", ErrorCopyTable.causeText(error))
    }
}
