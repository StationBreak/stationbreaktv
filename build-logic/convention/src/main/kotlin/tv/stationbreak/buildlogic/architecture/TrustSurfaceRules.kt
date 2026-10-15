// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

/**
 * Where the TLS trust API may be named (spec 05, Unit 3, Angua (Security); #232 L5). A source-text tripwire, not a
 * parser: it reads one file with its comments removed.
 * - `X509TrustManager`, `X509ExtendedTrustManager`, `HostnameVerifier` and `SSLContext` only in the `trust` package of
 *   `:data:jellyfin`, the one place that decides what is trusted. The app module is exempt: its own tests
 *   (`NetworkSecurityConfigTest`) already hold its trust delegate to `X509TrustManagerExtensions` alone.
 * - Trust-all patterns and `CertificatePinner` nowhere, the trust package and the app included.
 * A name that merely contains one of these (`X509TrustManagerExtensions`) is not a match.
 */
object TrustSurfaceRules {
    private const val APP = ":app"
    private const val TRUST_MODULE = ":data:jellyfin"

    // The one file that has to spell the banned patterns, as strings to look for: the app's own trust guard test.
    private const val APP_GUARD_TEST_NAME = "NetworkSecurityConfigTest.kt"

    private val restricted =
        listOf("X509TrustManager", "X509ExtendedTrustManager", "HostnameVerifier", "SSLContext")
            .map { it to Regex("""\b$it\b""") }

    private val banned =
        listOf(
            "CertificatePinner" to Regex("""\bCertificatePinner\b"""),
            "a trust-all manager" to Regex("""(?i)trust[_-]?all"""),
            "a trust-all hostname verifier" to Regex("""ALLOW_ALL_HOSTNAME_VERIFIER"""),
            "a hostname verifier that always says yes" to
                Regex("""(?:HostnameVerifier|hostnameVerifier)\s*[({]\s*(?:\{\s*)?(?:[\w, ]*->\s*)?true\s*[)}]"""),
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
        if (relativePath.substringAfterLast('/') == APP_GUARD_TEST_NAME && modulePath == APP) return emptyList()
        val code = withoutComments(text)
        val inTrustPackage = modulePath == TRUST_MODULE && "trust" in relativePath.split('/').dropLast(1)
        val findings = mutableListOf<Pair<Int, String>>()
        if (modulePath != APP && !inTrustPackage) {
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
