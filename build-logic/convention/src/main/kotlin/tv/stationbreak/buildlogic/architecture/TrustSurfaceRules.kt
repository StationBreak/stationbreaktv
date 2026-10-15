// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

/**
 * Where the TLS trust API may be named (spec 05, Unit 3, Angua (Security); #232 L5, #236 L4). A source-text tripwire,
 * not a parser: it reads one file with its comments removed.
 * - The trust API (`X509TrustManager`, `X509ExtendedTrustManager`, `TrustManager`, `TrustManagerFactory`,
 *   `HostnameVerifier`, `SSLContext`, `SSLCertificateSocketFactory`, and the `.hostnameVerifier(` and
 *   `.sslSocketFactory(` calls) only in the `trust` package of `:data:jellyfin`, the one place that decides what is
 *   trusted, matched by its full path. A few app files that need the platform's side of it are exempt by exact path.
 * - Trust-all patterns and `CertificatePinner` nowhere, the trust package included.
 * A name that merely contains one of these (`X509TrustManagerExtensions`) is not a match.
 */
object TrustSurfaceRules {
    private const val APP = ":app"
    private const val TRUST_MODULE = ":data:jellyfin"

    // The trust package, by its full path in every source set: not any directory that happens to be called `trust`.
    private val trustPackage = Regex("""^src/[^/]+/(?:kotlin|java)/tv/stationbreak/data/jellyfin/trust/[^/]+$""")

    // Files that may name the restricted API, by module and exact path. Nothing else in a module is exempt.
    private val restrictedExemptions =
        setOf(
            // The platform's own trust, asked through X509TrustManagerExtensions.
            APP to "src/main/kotlin/tv/stationbreak/app/net/AndroidSystemTrust.kt",
            // These tests exercise the real Conscrypt stack and the network security config.
            APP to "src/test/kotlin/tv/stationbreak/app/trust/NetworkSecurityConfigTest.kt",
            APP to "src/androidTest/kotlin/tv/stationbreak/app/NetworkSecurityConfigChainTest.kt",
            APP to "src/androidTest/kotlin/tv/stationbreak/app/PinnedTrustOnApi25Test.kt",
            // A test that hands the adapter's clients a socket factory of its own.
            TRUST_MODULE to "src/test/kotlin/tv/stationbreak/data/jellyfin/SchemeResolutionTest.kt",
        )

    // The app's trust guard test spells the banned patterns as strings to look for, so the ban skips it too.
    private val bannedExemptions =
        setOf(APP to "src/test/kotlin/tv/stationbreak/app/trust/NetworkSecurityConfigTest.kt")

    private val restricted =
        listOf(
            "X509TrustManager",
            "X509ExtendedTrustManager",
            "TrustManager",
            "TrustManagerFactory",
            "HostnameVerifier",
            "SSLContext",
            "SSLCertificateSocketFactory",
        ).map { it to Regex("""\b$it\b""") } +
            listOf(
                "hostnameVerifier()" to Regex("""\.hostnameVerifier\s*[({]"""),
                "sslSocketFactory()" to Regex("""\.sslSocketFactory\s*\("""),
            )

    private val banned =
        listOf(
            "CertificatePinner" to Regex("""\bCertificatePinner\b"""),
            "a trust-all manager" to Regex("""(?i)trust[_-]?all"""),
            "a trust-all hostname verifier" to Regex("""ALLOW_ALL_HOSTNAME_VERIFIER"""),
            "a hostname verifier that always says yes" to
                Regex("""(?:HostnameVerifier|hostnameVerifier)\s*[({]\s*(?:\{\s*)?(?:[\w,:?<>. ]*->\s*)?true\s*[)}]"""),
            "a process-wide verifier or socket factory" to
                Regex("""\bsetDefault(?:HostnameVerifier|SSLSocketFactory)\b"""),
            "a checkServerTrusted that checks nothing" to
                Regex("""checkServerTrusted\([^)]*\)\s*(?::\s*\w+\s*)?(?:\{\s*}|=\s*Unit\b)"""),
        )

    private val blockComment = Regex("""/\*[\s\S]*?\*/""")
    private val lineComment = Regex("""//[^\n]*""")

    /** One message per match: `path:line what`. [relativePath] is relative to the module, with `/` separators. */
    fun violations(
        modulePath: String,
        relativePath: String,
        text: String,
    ): List<String> {
        val file = modulePath to relativePath
        if (file in bannedExemptions) return emptyList()
        val code = withoutComments(text)
        val mayNameTrustApi =
            (modulePath == TRUST_MODULE && trustPackage.matches(relativePath)) || file in restrictedExemptions
        val findings = mutableListOf<Pair<Int, String>>()
        if (!mayNameTrustApi) {
            for ((name, pattern) in restricted) {
                findings +=
                    matches(code, pattern).map {
                        it to "$name is allowed only in the trust package of $TRUST_MODULE"
                    }
            }
        }
        for ((what, pattern) in banned) {
            findings += matches(code, pattern).map { it to "$what is not allowed anywhere" }
        }
        return findings.sortedBy { it.first }.map { (line, message) -> "$relativePath:$line $message" }
    }

    private fun matches(
        code: String,
        pattern: Regex,
    ): List<Int> = pattern.findAll(code).map { lineOf(code, it.range.first) }.toList()

    private fun lineOf(
        code: String,
        index: Int,
    ): Int = code.substring(0, index).count { it == '\n' } + 1

    // Replaced by the same number of line breaks, so a match keeps its line number.
    private fun withoutComments(text: String): String =
        text
            .replace(blockComment) { match -> "\n".repeat(match.value.count { it == '\n' }) }
            .replace(lineComment, "")
}
