// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.rules.RatingLadder

/**
 * The rating map of the tier holds (ADR-0028, #987): a free-form rating string to a level of one shared age ladder,
 * or null for unknown or unrated, which the holds read as adult. The levels are [LEVELS], in the order of the rules
 * format's ladder tiers, so a level is the same number as `RatingLadder.rankOf` gives a US name. It is US-first: only
 * the MPAA film and US TV Parental Guidelines names, a few owner-defined entries and bare minimum ages map; every other
 * country's names are unknown until a licensed table lands (#987). Frozen under `KidHold.VERSION_NAME`. Pure.
 * `kid-hold-8` (#1078) maps `X`, `AO` and `Adults Only` to level 7 and adds [isExplicit], the ratings of the Explicit
 * tier.
 *
 * Parse order (#987): (1) null, and the unrated spellings once cleaned ([UNRATED]), are unknown. (2) Clean: trim
 * U+0020 from both ends, upper-case ASCII a-z only, turn an en dash (U+2013) into a hyphen, drop a leading `RATED `.
 * (3) The whole string against the US names ([ALIASES], then `RatingLadder.normalise`, descriptor suffixes included),
 * so `TV-14` never reads as a country code. (4) A country prefix (`DE-12`, `GB:15`), looked up in [COUNTRY_TABLES].
 * (5) A list split on `/` takes its highest level, and is unknown if any part is. (6) A bare minimum age (`6`, `12+`)
 * rounds up to the next level. (7) Anything else is unknown.
 */
object RatingMap {
    /** The shared ladder, lowest first: index 0 is `ALL`, 7 is `X`. Kid levels are 0 and 1, family up to 2. */
    val LEVELS: List<String> = listOf("ALL", "7", "10", "13", "14", "16", "18", "X")

    /** The spellings that mean unrated, after cleaning. `Approved` is unrated here, unlike on the rules ladder. */
    val UNRATED: Set<String> = setOf("NR", "NOT RATED", "UNRATED", "PASSED", "APPROVED")

    /**
     * Owner-defined US entries the rules ladder lacks (#987): GP like PG, `PG13` as PG-13, XXX explicit, and the
     * ambiguous letters at their strictest reading (C and M are 16, A is adult). `kid-hold-8` (#1078): X, AO and
     * Adults Only are explicit too.
     */
    @Suppress("MagicNumber")
    val ALIASES: Map<String, Int> =
        mapOf(
            "GP" to 2,
            "PG13" to 3,
            "XXX" to 7,
            "X" to 7,
            "AO" to 7,
            "ADULTS ONLY" to 7,
            "C" to 5,
            "M" to 5,
            "A" to 6,
        )

    /**
     * The ratings of the Explicit tier (`kid-hold-8`, #1078), once cleaned, besides every level-7 rating: the US adult
     * ratings (NC-17 keeps its level 6 on the ladder), and the international adult ones as written with or without a
     * two-letter country prefix (GB and NZ `R18`, JP `R18+` and `R-18`, AU `X18+` and `R18+`, KR KMRB Restricted
     * Screening in English and Korean, and AniDB's `18 Restricted`). AU `R18+` and NZ `R18` also cover mainstream
     * films; under fail-up they count (#987 is the wider ladder).
     */
    val EXPLICIT: Set<String> =
        setOf(
            "X",
            "XXX",
            "AO",
            "ADULTS ONLY",
            "NC-17",
            "NC17",
            "R18",
            "R18+",
            "R-18",
            "X18+",
            "RESTRICTED SCREENING",
            "제한상영가",
            "18 RESTRICTED",
        )

    // #987: licensed per-country tables (code or name to rating to level) land here; empty until one is licensed.
    private val COUNTRY_TABLES: Map<String, Map<String, Int>> = emptyMap()

    // The highest age of each level up to 16; an age above the last is 18.
    @Suppress("MagicNumber")
    private val AGE_CEILINGS = intArrayOf(0, 7, 10, 13, 14, 17)
    private const val LEVEL_18 = 6
    private const val LEVEL_X = 7
    private const val COUNTRY_PREFIX = 3
    private const val MAX_AGE_DIGITS = 2
    private const val RATED = "RATED "
    private const val ASCII_CASE_OFFSET = 'a' - 'A'

    /** The level (0 to 7) of [raw], or null when it is absent, unrated or unknown. */
    fun levelOf(raw: String?): Int? = levelOfClean(raw?.let(::clean))

    /**
     * Whether [raw] is a rating of the Explicit tier (`kid-hold-8`, #1078): level 7, or in [EXPLICIT] once cleaned as
     * [levelOf] cleans it, as it is or with a two-letter country prefix (`GB-`, `AU:`) taken off. A combined rating
     * (split on `/`, as [levelOf] splits a list) is Explicit when any part is: `R/NC-17`, `GB-R18/AU-X18+`.
     */
    fun isExplicit(raw: String?): Boolean {
        val key = raw?.let(::clean) ?: return false
        return explicitPart(key) || ('/' in key && key.split('/').any { explicitPart(it.trim(' ')) })
    }

    private fun explicitPart(key: String): Boolean {
        val bare = withoutCountry(key)
        return key in EXPLICIT || bare in EXPLICIT || levelOfClean(key) == LEVEL_X
    }

    private fun levelOfClean(key: String?): Int? =
        when {
            key.isNullOrEmpty() || key in UNRATED -> null
            else -> usLevel(key) ?: countryLevel(key) ?: if ('/' in key) listLevel(key) else ageLevel(key)
        }

    // [key] without a leading two-letter country code and its `-` or `:`, or [key] itself when it has none.
    private fun withoutCountry(key: String): String {
        val coded = key.length > COUNTRY_PREFIX && key[0] in 'A'..'Z' && key[1] in 'A'..'Z'
        return if (coded && (key[2] == '-' || key[2] == ':')) key.substring(COUNTRY_PREFIX).trim(' ') else key
    }

    private fun clean(raw: String): String {
        val upper =
            buildString(raw.length) {
                for (c in raw.trim(' ')) {
                    append(
                        if (c in 'a'..'z') {
                            c - ASCII_CASE_OFFSET
                        } else if (c == '–') {
                            '-'
                        } else {
                            c
                        },
                    )
                }
            }
        return if (upper.startsWith(RATED)) upper.substring(RATED.length).trim(' ') else upper
    }

    private fun usLevel(key: String): Int? = ALIASES[key] ?: if (key == "APPROVED") null else RatingLadder.rankOf(key)

    private fun countryLevel(key: String): Int? {
        val cut = key.indexOfFirst { it == '-' || it == ':' || it == '/' }
        if (cut <= 0) return null
        return COUNTRY_TABLES[key.substring(0, cut).trim(' ')]?.get(key.substring(cut + 1).trim(' '))
    }

    private fun listLevel(key: String): Int? {
        var highest = -1
        for (part in key.split('/')) {
            val trimmed = part.trim(' ')
            val level = usLevel(trimmed) ?: ageLevel(trimmed) ?: return null
            if (level > highest) highest = level
        }
        return highest
    }

    private fun ageLevel(key: String): Int? {
        val digits = key.removeSuffix("+")
        if (digits.isEmpty() || digits.length > MAX_AGE_DIGITS || !digits.all { it in '0'..'9' }) return null
        val age = digits.toInt()
        val level = AGE_CEILINGS.indexOfFirst { age <= it }
        return if (level < 0) LEVEL_18 else level
    }
}
