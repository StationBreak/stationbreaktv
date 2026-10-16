// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlin.test.Test
import kotlin.test.assertTrue

// Nothing in an example may come from a real library, server or account (spec 07, Notes: proof hygiene).
class SanitisedExamplesTest {
    private val all: Map<String, String> by lazy {
        val files = RulesFormatFiles.valid + RulesFormatFiles.invalid
        assertTrue(files.isNotEmpty(), "no example files")
        files
    }

    private fun strings(v: Any?): List<String> =
        when (v) {
            is String -> listOf(v)
            is Map<*, *> -> v.keys.map { it.toString() } + v.values.flatMap { strings(it) }
            is List<*> -> v.flatMap { strings(it) }
            else -> emptyList()
        }

    // 32 hex characters that are one repeated digit, or 31 zeros then one digit.
    private val placeholder = Regex("^(([0-9])\\2{31}|0{31}[0-9])$")
    private val idShaped = Regex("^[0-9a-fA-F-]{32,36}$")
    private val tokenLike = Regex("[A-Za-z0-9_-]{24,}")

    @Test
    fun everyIdIsAPlaceholder() {
        for ((file, text) in all) {
            for (s in strings(RulesFormatFiles.parse(text)).filter { idShaped.matches(it) }) {
                val bare = s.replace("-", "").lowercase()
                assertTrue(placeholder.matches(bare), "$file: $s is not a placeholder ID")
            }
        }
    }

    @Test
    fun noUrlAddressOrTokenLikeString() {
        for ((file, text) in all) {
            for (s in strings(RulesFormatFiles.parse(text))) {
                assertTrue(!s.contains("http", ignoreCase = true), "$file: URL-like string")
                assertTrue('@' !in s, "$file: address-like string")
                if (!idShaped.matches(s)) assertTrue(!tokenLike.containsMatchIn(s), "$file: token-like string $s")
            }
        }
    }
}
