// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * The country tables of [RatingMap] (`kid-hold-10`, #987): the Android TV XML's systems ([AndroidTvRatingSystems]) by
 * country code, then spelling, to a ladder level, with StationBreak's [OVERRIDES], and the age rounding the bare ages
 * of [RatingMap] share. Built once from the data. Frozen under `KidHold.VERSION_NAME`. Pure.
 */
internal object CountryRatingTables {
    /**
     * StationBreak's decisions on top of the XML, by country and spelling. AU PG is 10, like US PG: owner decision,
     * the XML's age hint (15) is not used, nor any other source's. The rest fail up from a hint the rating's own name
     * contradicts: NZ PGR (parental guidance) and SG PG are 10, not the XML's 0; NZ AO (adults only) is explicit like
     * the `AO` alias, not 0; ES X (X-Rated) is explicit, not 18. `kid-hold-11` (#1124): the adult-only labels AU R, SG
     * R21, KR 19 and JP 19 and 20 are explicit (level 7), not 18 (owner call, reversible). `kid-hold-12` (#1128): TH 19
     * is explicit too. `kid-hold-13` (#987): KR 19 and JP 19 and 20 are mainstream adult ratings and read 18 again
     * (Adult, no override); AU R, SG R21 and TH 19 stay explicit. [BoardRatings] adds the boards the XML lacks.
     */
    @Suppress("MagicNumber")
    private val OVERRIDES: Map<String, Map<String, Int>> =
        mapOf(
            "AU" to mapOf("PG" to 2, "R" to 7),
            "TH" to mapOf("19" to 7),
            "NZ" to mapOf("PGR" to 2, "AO" to 7),
            "SG" to mapOf("PG" to 2, "R21" to 7),
            "ES" to mapOf("X" to 7, "X-RATED" to 7),
        )

    // Exempt (Canada's news, sports and talk) is not a classification: unknown, like NR, not the XML's age 0.
    private val EXEMPT: Set<String> = setOf("E", "EXEMPT")

    // The highest age of each level up to 16; an age above the last is 18, so 17 is level 6 (`kid-hold-14`, #1006).
    @Suppress("MagicNumber")
    private val AGE_CEILINGS = intArrayOf(0, 7, 10, 13, 14, 16)
    private const val LEVEL_18 = 6
    private const val MAX_AGE_DIGITS = 2
    private const val COUNTRY_CODE_LENGTH = 2

    /**
     * Country code to spelling to level: the age hint rounded up to the next level ([ageRung]), or the override. A
     * spelling with spaces also matches without them (`SAM13`), and an age also with a `+` (`DE-12+`). Two systems of
     * one country that share a spelling take the higher level.
     */
    private val TABLES: Map<String, Map<String, Int>> =
        buildMap<String, MutableMap<String, Int>> {
            for (system in AndroidTvRatingSystems.SYSTEMS) {
                for (country in system.countries) {
                    val table = getOrPut(country) { HashMap() }
                    for (rating in system.ratings) {
                        for (name in rating.names) {
                            if (name in EXEMPT) continue
                            val level = OVERRIDES[country]?.get(name) ?: ageRung(rating.ageHint)
                            for (key in spellingsOf(name)) table[key] = maxOf(table[key] ?: level, level)
                        }
                    }
                }
            }
            for ((system, ratings) in BoardRatings.BY_SYSTEM) {
                val table = getOrPut(system) { HashMap() }
                for ((name, level) in ratings) for (key in spellingsOf(name)) table[key] = level
            }
        }

    /**
     * The spellings of [TABLES] that need no country prefix, at the highest level of any country that has them: not a
     * bare number (an age, which [RatingMap] caps at Family), not a single letter (`P`, `C`, `L` mean different
     * things in each country), and not a name that is Explicit on its own ([RatingMap.EXPLICIT]: `R18`, `R18+`,
     * `X18+`), whose level stays unknown, as before `kid-hold-13`.
     */
    private val UNPREFIXED: Map<String, Int> =
        buildMap {
            for (table in TABLES.values) {
                for ((key, level) in table) {
                    if (key.length > 1 && !isAge(key) && key !in RatingMap.EXPLICIT) {
                        put(key, maxOf(get(key) ?: level, level))
                    }
                }
            }
        }

    /** The level of a cleaned [key] that needs no country prefix (`SAM 13`), or null. */
    fun unprefixed(key: String): Int? = UNPREFIXED[key]

    /**
     * The level of a cleaned [key] with a country prefix: a code, then `-`, `:` or `/`, then a spelling of that
     * country's table (`DE-12`, `KR: 15`, `NL/16`), or null.
     */
    fun countryLevel(key: String): Int? = countryPrefix(key)?.let { (code, rest) -> TABLES[code]?.get(rest) }

    /**
     * The system prefix of a cleaned [key] and the rest: a system, spaces allowed around the separator, then the first
     * `-`, `:` or `/` (`DE-12`, `GB: R18`, `AU/X18+`, `United Kingdom:R18`, `FSK-12`), or null. A system is two letters
     * `A` to `Z`, `UK` (GB), the name of a country of the XML, or `FSK` (`kid-hold-13`, #987; [BoardRatings.PREFIXES]).
     * The one prefix rule of [RatingMap]: both the level ([countryLevel]) and the explicit check read a prefix with it.
     */
    fun countryPrefix(key: String): Pair<String, String>? {
        val cut = key.indexOfFirst { it == '-' || it == ':' || it == '/' }
        if (cut <= 0) return null
        val prefix = key.substring(0, cut).trim(' ')
        val rest = key.substring(cut + 1).trim(' ')
        val isCode = prefix.length == COUNTRY_CODE_LENGTH && prefix.all { it in 'A'..'Z' }
        val system = BoardRatings.PREFIXES[prefix] ?: prefix.takeIf { isCode }
        return if (system != null && rest.isNotEmpty()) system to rest else null
    }

    /** Whether [key] is a minimum age: one or two digits, with or without a `+`. */
    fun isAge(key: String): Boolean {
        val digits = key.removeSuffix("+")
        return digits.isNotEmpty() && digits.length <= MAX_AGE_DIGITS && digits.all { it in '0'..'9' }
    }

    /** The level of [age], rounded up (fail up): 8 is 10, not 7; an age above 16 is 18. */
    fun ageRung(age: Int): Int {
        val level = AGE_CEILINGS.indexOfFirst { age <= it }
        return if (level < 0) LEVEL_18 else level
    }

    private fun spellingsOf(name: String): Set<String> =
        setOf(name, name.replace(" ", "")) + if (isAge(name) && !name.endsWith('+')) setOf("$name+") else emptySet()
}
