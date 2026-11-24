// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shared country-prefix rule of `kid-hold-10` (#987 review) never makes an Explicit rating stop being Explicit:
 * over spacing, case, separator and prefix variants of every [RatingMap.EXPLICIT] entry, [RatingMap.isExplicit] is
 * true wherever the rule before it was. The earlier rule is copied here as the reference ([referenceIsExplicit]),
 * without its level-7 term (every level-7 spelling it reached is in [RatingMap.EXPLICIT] itself).
 */
class ExplicitPrefixInvariantTest {
    // The kid-hold-9 `withoutCountry`: two letters A to Z, then `-` or `:` at position 2, and something after it.
    private fun referenceWithoutCountry(key: String): String {
        val coded = key.length > 3 && key[0] in 'A'..'Z' && key[1] in 'A'..'Z'
        return if (coded && (key[2] == '-' || key[2] == ':')) key.substring(3).trim(' ') else key
    }

    private fun referencePart(key: String): Boolean {
        val bare = referenceWithoutCountry(key)
        return key in RatingMap.EXPLICIT || bare in RatingMap.EXPLICIT || bare.replace(" ", "") in RatingMap.EXPLICIT
    }

    // The kid-hold-9 clean of isExplicit: ASCII upper case, the any-space tidy, a leading `RATED ` dropped.
    private fun referenceClean(raw: String): String {
        val upper = buildString { for (c in raw) append(if (c in 'a'..'z') c - ('a' - 'A') else c) }
        val tidy = NoiseTidy.tidy(upper, anySpace = true)
        return if (tidy.startsWith("RATED ")) tidy.substring("RATED ".length).trim(' ') else tidy
    }

    private fun referenceIsExplicit(raw: String): Boolean {
        val key = referenceClean(raw)
        return referencePart(key) || ('/' in key && key.split('/').any { referencePart(it.trim(' ')) })
    }

    private fun variantsOf(entry: String): Set<String> {
        val spaced = mutableSetOf(entry, entry.lowercase())
        for (i in 1 until entry.length) spaced += entry.substring(0, i) + " " + entry.substring(i)
        val separators = listOf("-", " - ", " -", "- ", "  -  ", "\t-\t", "\u00A0-\u00A0", ":", " : ", "/", " / ")
        val withSeparators = spaced.toMutableSet()
        for (form in spaced) {
            if ('-' in form) for (sep in separators) withSeparators += form.replace("-", sep)
        }
        val prefixes = listOf("", "GB-", "gb:", "AU/", "DE - ", "NZ : ", "R/", "Rated ")
        val ends = listOf("" to "", " " to " ", "\t" to "", "\u00A0" to "\u00A0")
        return buildSet {
            for (form in withSeparators) {
                for (prefix in prefixes) for ((start, end) in ends) add(start + prefix + form + end)
            }
        }
    }

    // #1128: a country code and a space in front of an Explicit rating reads as the hyphen spelling does.
    @Test
    fun anExplicitRatingWithACountryCodeAndASpaceIsExplicit() {
        for (entry in RatingMap.EXPLICIT) {
            for (prefix in listOf("GB ", "au ", "NZ  ")) {
                assertTrue(RatingMap.isExplicit(prefix + entry), "[$prefix$entry]")
            }
        }
    }

    @Test
    fun anExplicitRatingStaysExplicitUnderTheSharedPrefixRule() {
        var reference = 0
        for (entry in RatingMap.EXPLICIT) {
            for (variant in variantsOf(entry)) {
                if (!referenceIsExplicit(variant)) continue
                reference++
                assertTrue(RatingMap.isExplicit(variant), "[$variant] was Explicit and is not")
            }
        }
        assertTrue(reference > 2_000, "variants the reference calls Explicit: $reference")
    }
}
