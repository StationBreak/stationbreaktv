// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.rules.RatingLadder

/**
 * The rating map of the tier holds (ADR-0028, #987): a free-form rating string to a level of one shared age ladder,
 * or null for unknown or unrated, which the holds read as adult. The levels are [LEVELS], in the order of the rules
 * format's ladder tiers, so a level is the same number as `RatingLadder.rankOf` gives a US name. It is US-first: the
 * MPAA film and US TV Parental Guidelines names, a few owner-defined entries, bare minimum ages and, since
 * `kid-hold-10` (#987), the non-US systems of the Android TV ratings XML ([AndroidTvRatingSystems], Apache-2.0; see
 * NOTICE) map; any other rating is unknown. Frozen under `KidHold.VERSION_NAME`. Pure.
 * `kid-hold-8` (#1078) maps `X`, `AO` and `Adults Only` to level 7 and adds [isExplicit], the ratings of the Explicit
 * tier. Input noise (#1088) is tidied (`NoiseTidy`) in [clean], so for every rating: a doubled space or a dash
 * variant (en or em dash, minus sign, non-breaking hyphen) reads as the plain spelling does, and a rating with a space
 * inside (`R 18+`, `NC 17`) also matches with its spaces taken out (only a key with a letter: `0 7` stays unknown). A
 * tab or a no-break space in a rating stays present and off the ladder for [levelOf] (frozen vectors), but [isExplicit]
 * reads past them (fail up). The holds tidy genre and tag names fully. `kid-hold-9` (#1088) has all of this
 * (`kid-hold-9.json`). `kid-hold-10` (#987, `kid-hold-10.json`) fills the country tables from the XML
 * ([CountryRatingTables], with its overrides), reads a spelling of them that needs no prefix, reads the parts of a
 * list as a whole rating is read, and caps a bare age at Family.
 *
 * Parse order (#987): (1) null, and the unrated spellings once cleaned ([UNRATED]), are unknown. (2) Clean: trim
 * U+0020 from both ends, upper-case ASCII a-z only, turn an en dash (U+2013) into a hyphen, drop a leading `RATED `.
 * (3) The whole string against the US names ([ALIASES], then `RatingLadder.normalise`, descriptor suffixes included),
 * so `TV-14` never reads as a country code; then a spelling that needs no prefix (`SAM 13`, `PG-12`). (4) A country
 * prefix (`DE-12`, `de:12`, `NL/16`), looked up in [CountryRatingTables]; the table has no `TV`, `PG` or `US` code.
 * (5) A list split on `/` takes its highest level, each part read as steps 3, 4 and 6 read a rating, and is unknown if
 * any part is. (6) A bare minimum age (`6`, `12+`) rounds up to the next level, and is Family at least (#1006).
 * (7) Anything else is unknown.
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

    private const val LEVEL_X = 7
    private const val FAMILY_LEVEL = 2
    private const val RATED = "RATED "
    private const val ASCII_CASE_OFFSET = 'a' - 'A'

    /** The level (0 to 7) of [raw], or null when it is absent, unrated or unknown. */
    fun levelOf(raw: String?): Int? = levelOfClean(raw?.let(::clean))

    /**
     * Whether [raw] is a rating of the Explicit tier (`kid-hold-8`, #1078): level 7, or in [EXPLICIT] once cleaned as
     * [levelOf] cleans it, as it is or with a two-letter country prefix taken off (`GB-`, `AU:`, and since
     * `kid-hold-10` `DE/` and `GB - `: the prefix rule of [levelOf]). Since `kid-hold-9` (#1088) a tab, no-break space
     * or other white space counts as a space here, so `"\tNC-17"` is Explicit (fail up). A combined rating (split on
     * `/`, as [levelOf] splits a list) is Explicit when any part is: `R/NC-17`, `GB-R18/AU-X18+`.
     */
    fun isExplicit(raw: String?): Boolean {
        val key = raw?.let { clean(it, anySpace = true) } ?: return false
        return explicitPart(key) || ('/' in key && key.split('/').any { explicitPart(it.trim(' ')) })
    }

    // As written, or with its spaces taken out (`R 18+`, `NC 17`), with or without a country prefix. The whole key
    // packed is read too, so a name whose letters look like a prefix (`NC - 17`) stays Explicit (#987 review).
    private fun explicitPart(key: String): Boolean {
        val bare = withoutCountry(key)
        return key in EXPLICIT ||
            key.replace(" ", "") in EXPLICIT ||
            bare in EXPLICIT ||
            bare.replace(" ", "") in EXPLICIT ||
            levelOfClean(key) == LEVEL_X
    }

    private fun levelOfClean(key: String?): Int? =
        when {
            key.isNullOrEmpty() || key in UNRATED -> {
                null
            }

            else -> {
                namedLevel(key) ?: (if ('/' in key) listLevel(key) else ageLevel(key))
                    ?: if (' ' in key && key.any { it in 'A'..'Z' }) levelOfClean(key.replace(" ", "")) else null
            }
        }

    // [key] without a leading two-letter country code and its `-` or `:`, or [key] itself when it has none.
    // Since kid-hold-10 the prefix rule of the level ([CountryRatingTables.countryPrefix]): `-`, `:` or `/` (fail up).
    private fun withoutCountry(key: String): String = CountryRatingTables.countryPrefix(key)?.second ?: key

    private fun clean(
        raw: String,
        anySpace: Boolean = false,
    ): String {
        val upper =
            buildString(raw.length) {
                for (c in raw) append(if (c in 'a'..'z') c - ASCII_CASE_OFFSET else c)
            }
        val tidy = NoiseTidy.tidy(upper, anySpace)
        return if (tidy.startsWith(RATED)) tidy.substring(RATED.length).trim(' ') else tidy
    }

    private fun usLevel(key: String): Int? = ALIASES[key] ?: if (key == "APPROVED") null else RatingLadder.rankOf(key)

    // Steps 3 and 4: the US names whole, then a spelling that needs no prefix, then a country prefix.
    private fun namedLevel(key: String): Int? {
        val tables = CountryRatingTables
        return usLevel(key) ?: tables.unprefixed(key) ?: tables.countryLevel(key)
    }

    private fun listLevel(key: String): Int? {
        var highest = -1
        for (part in key.split('/')) {
            val trimmed = part.trim(' ')
            val level = namedLevel(trimmed) ?: ageLevel(trimmed) ?: return null
            if (level > highest) highest = level
        }
        return highest
    }

    // A bare minimum age has no system, so it is never a kid level (#1006, owner decision 2026-11-23): Family at least.
    private fun ageLevel(key: String): Int? =
        if (CountryRatingTables.isAge(key)) {
            maxOf(CountryRatingTables.ageRung(key.removeSuffix("+").toInt()), FAMILY_LEVEL)
        } else {
            null
        }
}
