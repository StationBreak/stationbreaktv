// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.SpkiPin
import java.lang.reflect.ParameterizedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The error vocabulary of S0.3.4, and the shape Angua (Security) R11 requires of it. */
class PortErrorShapeTest {
    private val expectedVariants =
        setOf(
            "DnsFailure",
            "ConnectionRefused",
            "NetworkUnreachable",
            "Timeout",
            "TlsUntrusted",
            "TlsHostnameMismatch",
            "TlsCertificateExpired",
            "TlsCertificateNotYetValid",
            "TlsCertificateChanged",
            "TlsHandshakeFailed",
            "NotTls",
            "HttpUnauthorized",
            "HttpForbidden",
            "NotJellyfin",
            "UnsupportedVersion",
            "ServerNotSetUp",
            "Redirected",
            "CleartextNotConfirmed",
            "SessionExpired",
            "ServerMismatch",
            "QuickConnectDisabled",
            "ServerError",
            "InvalidAddress",
            "Unknown",
            "NotFound",
        )

    private val variants: List<Class<*>> = MediaServerError::class.java.permittedSubclasses.toList()

    private fun instanceOf(type: Class<*>): MediaServerError =
        if (type == MediaServerError.UnsupportedVersion::class.java) {
            MediaServerError.UnsupportedVersion(ServerVersion(11, 0, 0))
        } else {
            type.getDeclaredConstructor().newInstance() as MediaServerError
        }

    @Test
    fun everyVariantInTheSpecExistsAndNothingElse() {
        assertEquals(expectedVariants, variants.map { it.simpleName }.toSet())
    }

    @Test
    fun noVariantHoldsAThrowableInAnyField() {
        for (variant in variants) {
            for (field in variant.declaredFields) {
                assertFalse(Throwable::class.java.isAssignableFrom(field.type), "${variant.simpleName}.${field.name}")
            }
            for (constructor in variant.declaredConstructors) {
                for (type in constructor.parameterTypes) {
                    assertFalse(Throwable::class.java.isAssignableFrom(type), "${variant.simpleName} constructor")
                }
            }
        }
    }

    @Test
    fun theOnlyCauseDataIsAListOfClassNameStrings() {
        for (variant in variants) {
            val causes = variant.getMethod("getCauses")
            val listType = causes.genericReturnType as ParameterizedType
            assertEquals(List::class.java, listType.rawType, variant.simpleName)
            assertEquals(String::class.java, listType.actualTypeArguments.single(), variant.simpleName)
            for (field in variant.declaredFields) {
                val allowed =
                    field.type == List::class.java ||
                        field.type == ServerVersion::class.java ||
                        field.type == CertificateEvidence::class.java
                assertTrue(allowed, "${variant.simpleName}.${field.name} is a ${field.type.name}")
            }
        }
    }

    @Test
    fun toStringNamesTheVariantAndNoHostUrlOrHeader() {
        for (variant in variants) {
            val text = instanceOf(variant).toString()
            assertTrue(variant.simpleName in text, text)
            // The variant's own name (HttpForbidden) is the one place "http" may appear.
            val rest = text.replace(variant.simpleName, "")
            for (forbidden in listOf("http", "://", "Authorization", "Bearer", "MediaBrowser", "Token", "@")) {
                assertFalse(forbidden.lowercase() in rest.lowercase(), "$text contains $forbidden")
            }
        }
    }

    @Test
    fun aCauseChainIsKeptAsClassNamesOnly() {
        val error = MediaServerError.DnsFailure(listOf("java.net.UnknownHostException"))
        assertEquals(listOf("java.net.UnknownHostException"), error.causes)
        assertEquals("DnsFailure(causes=[java.net.UnknownHostException])", error.toString())
    }

    @Test
    fun anUnsupportedVersionReportsTheVersionFound() {
        val error = MediaServerError.UnsupportedVersion(ServerVersion(11, 0, 3))
        assertEquals(ServerVersion(11, 0, 3), error.found)
        assertTrue("11.0.3" in error.toString())
    }

    @Test
    fun resultsAreSuccessOrAnError() {
        val ok: ServerResult<Int> = ServerResult.Success(3)
        val failed: ServerResult<Int> = ServerResult.Failure(MediaServerError.Timeout())
        assertEquals(3, ok.getOrNull())
        assertEquals(null, failed.getOrNull())
        assertEquals(MediaServerError.Timeout(), failed.errorOrNull())
        assertEquals(null, ok.errorOrNull())
        assertEquals(6, ok.map { it * 2 }.getOrNull())
        assertEquals(failed, failed.map { it * 2 })
    }

    @Test
    fun anErrorThatCarriesACertificateShowsNoneOfItInToString() {
        val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 0x5A })
        val evidence =
            CertificateEvidence(pin, selfSigned = true, notBeforeMillis = 111_222_333L, notAfterMillis = 444_555_666L)
        val errors =
            listOf(
                MediaServerError.TlsUntrusted(listOf("javax.net.ssl.SSLHandshakeException"), evidence),
                MediaServerError.TlsHostnameMismatch(emptyList(), evidence),
                MediaServerError.TlsCertificateExpired(emptyList(), evidence),
                MediaServerError.TlsCertificateNotYetValid(emptyList(), evidence),
                MediaServerError.TlsCertificateChanged(emptyList(), evidence),
            )
        for (error in errors) {
            val text = error.toString()
            for (secret in listOf(pin.hex, pin.hex.lowercase(), "5A5A", "5a5a", "111222333", "444555666")) {
                assertFalse(secret in text, "$text shows $secret")
            }
        }
    }
}
