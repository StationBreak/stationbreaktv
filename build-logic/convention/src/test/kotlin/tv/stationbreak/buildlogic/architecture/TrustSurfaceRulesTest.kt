// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrustSurfaceRulesTest {
    private val trustFile = "src/main/kotlin/tv/stationbreak/data/jellyfin/trust/PinningTrustManager.kt"
    private val otherFile = "src/main/kotlin/tv/stationbreak/feature/onboarding/Thing.kt"

    private fun violations(
        text: String,
        module: String = ":feature:onboarding",
        path: String = otherFile,
    ) = TrustSurfaceRules.violations(module, path, text)

    @Test
    fun theTrustApiIsAllowedInTheTrustPackageOfDataJellyfin() {
        val text = "class A : X509ExtendedTrustManager()\nval c = SSLContext.getInstance(\"TLS\")\nval v: HostnameVerifier"
        assertEquals(emptyList(), violations(text, ":data:jellyfin", trustFile))
    }

    @Test
    fun theTrustTestsOfDataJellyfinMayUseItToo() {
        val path = "src/test/kotlin/tv/stationbreak/data/jellyfin/trust/PinnedTlsSupport.kt"
        assertEquals(emptyList(), violations("val c = SSLContext.getInstance(\"TLS\")", ":data:jellyfin", path))
    }

    @Test
    fun theSameCodeInAnotherPackageOfDataJellyfinIsRefused() {
        val path = "src/main/kotlin/tv/stationbreak/data/jellyfin/ClientFactory.kt"
        assertEquals(1, violations("val c = SSLContext.getInstance(\"TLS\")", ":data:jellyfin", path).size)
    }

    @Test
    fun aTrustPackageInAnotherModuleIsNotTheTrustPackage() {
        assertEquals(1, violations("val v = HostnameVerifier { _, _ -> false }", ":feature:onboarding", trustFile).size)
    }

    @Test
    fun x509TrustManagerHostnameVerifierAndSslContextAreRefusedElsewhere() {
        for (name in listOf("X509TrustManager", "X509ExtendedTrustManager", "HostnameVerifier", "SSLContext")) {
            val found = violations("import javax.net.ssl.$name\nval x: $name? = null")
            assertTrue(found.any { name in it && ":1" in it }, "$name: $found")
        }
    }

    @Test
    fun theViolationNamesTheFileAndLine() {
        val found = violations("package a\n\nval c = SSLContext.getInstance(\"TLS\")")
        assertEquals(1, found.size)
        assertTrue(found.single().startsWith("$otherFile:3 "), found.single())
    }

    @Test
    fun anotherNameThatMerelyContainsOneOfThemPasses() {
        val text = "val e = X509TrustManagerExtensions(m)\nval p = PinnedHostnameVerifierFactory()\nval s: SSLSocketFactory"
        assertEquals(emptyList(), violations(text))
    }

    @Test
    fun commentsAreNotCode() {
        val text = "// uses SSLContext\n/* HostnameVerifier\n   CertificatePinner */\n/** X509TrustManager, trustAll */\nval a = 1"
        assertEquals(emptyList(), violations(text))
    }

    @Test
    fun certificatePinnerIsRefusedEverywhereEvenInTheTrustPackage() {
        assertEquals(1, violations("val p = CertificatePinner.Builder().build()", ":data:jellyfin", trustFile).size)
        assertEquals(1, violations("val p = CertificatePinner.Builder().build()").size)
    }

    @Test
    fun trustAllPatternsAreRefusedEverywhereEvenInTheTrustPackage() {
        val patterns =
            listOf(
                "val v = HostnameVerifier { _, _ -> true }",
                "val v = HostnameVerifier { true }",
                "builder.hostnameVerifier { _, _ -> true }",
                "builder.hostnameVerifier(HostnameVerifier { hostname, session -> true })",
                "val m = object : TrustAllCerts()",
                "val trustAll = true",
                "val v = ALLOW_ALL_HOSTNAME_VERIFIER",
                "HttpsURLConnection.setDefaultHostnameVerifier(v)",
                "HttpsURLConnection.setDefaultSSLSocketFactory(f)",
                "override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}",
                "override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) = Unit",
                "public void checkServerTrusted(X509Certificate[] c, String a) { }",
            )
        for (pattern in patterns) {
            assertTrue(violations(pattern, ":data:jellyfin", trustFile).isNotEmpty(), "in trust: $pattern")
            assertTrue(violations(pattern).isNotEmpty(), "elsewhere: $pattern")
        }
    }

    @Test
    fun aVerifierThatReallyChecksIsNotATrustAllPattern() {
        val text = "val v = HostnameVerifier { host, session -> host == session.peerHost }"
        assertEquals(emptyList(), violations(text, ":data:jellyfin", trustFile))
    }

    @Test
    fun aCheckServerTrustedThatThrowsIsNotATrustAllPattern() {
        val text = "override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?): Unit = throw CertificateException()"
        assertEquals(emptyList(), violations(text, ":data:jellyfin", trustFile))
    }

    @Test
    fun theOtherWaysIntoTrustAreRefusedOutsideTheTrustPackage() {
        val snippets =
            listOf(
                "builder.hostnameVerifier(v)",
                "builder.hostnameVerifier { host, session -> host == session.peerHost }",
                "builder.sslSocketFactory(factory, manager)",
                "val f = TrustManagerFactory.getInstance(\"X.509\")",
                "val f = SSLCertificateSocketFactory.getInsecure(0, null)",
                "val f: SSLCertificateSocketFactory? = null",
                "val m: Array<TrustManager> = emptyArray()",
            )
        for (snippet in snippets) {
            assertTrue(violations(snippet).isNotEmpty(), "elsewhere: $snippet")
            assertEquals(emptyList(), violations(snippet, ":data:jellyfin", trustFile), "in trust: $snippet")
        }
    }

    @Test
    fun aTypedLambdaThatAlwaysSaysYesIsATrustAllPattern() {
        val patterns =
            listOf(
                "builder.hostnameVerifier { _: String?, _: SSLSession? -> true }",
                "val v = HostnameVerifier { host: String, session: SSLSession -> true }",
            )
        for (pattern in patterns) {
            assertTrue(violations(pattern, ":data:jellyfin", trustFile).isNotEmpty(), pattern)
        }
    }

    @Test
    fun theTrustPackageIsMatchedByItsFullPathNotByAnyDirectoryNamedTrust() {
        val text = "val c = SSLContext.getInstance(\"TLS\")"
        for (
        path in
        listOf(
            "src/main/kotlin/x/trust/Sneaky.kt",
            "src/main/kotlin/tv/stationbreak/data/jellyfin/other/trust/Sneaky.kt",
            "src/main/kotlin/tv/stationbreak/data/jellyfin/trust2/Sneaky.kt",
            "trust/Sneaky.kt",
        )
        ) {
            assertEquals(1, violations(text, ":data:jellyfin", path).size, path)
        }
        for (set in listOf("main", "test", "androidTest", "testFixtures")) {
            val path = "src/$set/kotlin/tv/stationbreak/data/jellyfin/trust/Ok.kt"
            assertEquals(emptyList(), violations(text, ":data:jellyfin", path), path)
        }
    }

    @Test
    fun theAppModuleIsNoLongerExemptAsAWhole() {
        val text = "val c = SSLContext.getInstance(\"TLS\")"
        assertEquals(1, violations(text, ":app", "src/main/kotlin/tv/stationbreak/app/net/Other.kt").size)
    }

    @Test
    fun onlyTheAppFilesNamedByExactPathAreExempt() {
        val spelled = "val forbidden = Regex(\"X509TrustManager|HostnameVerifier|trustAll|TrustAllCerts\")"
        val guardTest = "src/test/kotlin/tv/stationbreak/app/trust/NetworkSecurityConfigTest.kt"
        assertEquals(emptyList(), violations(spelled, ":app", guardTest))
        // The same file name anywhere else is not the exemption.
        assertTrue(violations(spelled, ":app", "src/test/kotlin/other/NetworkSecurityConfigTest.kt").isNotEmpty())
        assertTrue(violations(spelled, ":feature:x", guardTest).isNotEmpty())
    }
}
