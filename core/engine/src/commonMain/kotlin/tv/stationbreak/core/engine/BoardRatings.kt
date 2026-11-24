// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * The ratings of the boards that the Android TV XML does not carry (`kid-hold-13`, #987): StationBreak's own table
 * on top of [AndroidTvRatingSystems], by system code, then spelling, to a ladder level. Each tier is the board's own
 * published definition, not a copy of any other table: the BBFC age ratings (bbfc.co.uk/our-age-ratings), the FSK
 * ages of JuSchG section 14 (gesetze-im-internet.de/juschg/__14.html, fsk.de/altersstufen), Eirin's categories
 * (eirin.jp/english/008.html) and the Australian Classification ratings (classification.gov.au). Fail up where two
 * tiers meet: 12A, FSK 12 and PG12 are 13, not 10; AU M is 16, not 14; BBFC 15 is 16. An adult-only label (BBFC R18,
 * AU X18+, R18+ and R, JP R18+) is level 7, Explicit, as the `AU-R` of `kid-hold-11` is. A label a board does not
 * define is not here, so it is unknown. [CountryRatingTables] reads this over the XML, so these win. Frozen under
 * `KidHold.VERSION_NAME`. Pure.
 */
@Suppress("MagicNumber")
internal object BoardRatings {
    /** The key of the FSK-only table: the prefix `FSK` reads only FSK's five official values, so `FSK-7` is unknown. */
    const val FSK = "FSK"

    /** FSK's five official ages and their levels (FSK 6 is 7, FSK 12 is 13, FSK 16 is 16, FSK 18 is 18 and up). */
    private val FSK_AGES: Map<String, Int> = mapOf("0" to 0, "6" to 1, "12" to 3, "16" to 5, "18" to 6)

    // The FSK spellings with a system word in them: `FSK 12`, `FSK12`, `FSK-12`, `ab 12`, `ab12`.
    private val FSK_WORDED: Map<String, Int> =
        buildMap {
            for ((age, level) in FSK_AGES) {
                for (word in listOf("FSK ", "FSK-", "AB ")) put(word + age, level)
            }
            put("KEINE JUGENDFREIGABE", 6)
        }

    /** System code to spelling to level, over the XML's table of that country. */
    val BY_SYSTEM: Map<String, Map<String, Int>> =
        mapOf(
            "GB" to mapOf("U" to 0, "PG" to 2, "12A" to 3, "12" to 3, "15" to 5, "18" to 6, "R18" to 7),
            FSK to FSK_AGES,
            "DE" to FSK_WORDED + ("0" to 0),
            "JP" to
                mapOf(
                    "G" to 0,
                    "PG12" to 3,
                    "PG 12" to 3,
                    "R15+" to 5,
                    "R 15+" to 5,
                    "R15" to 5,
                    "R-15" to 5,
                    "R18+" to 7,
                    "R 18+" to 7,
                ),
            "AU" to
                mapOf(
                    "MA15+" to 5,
                    "MA 15+" to 5,
                    "MA15" to 5,
                    "R18+" to 7,
                    "R 18+" to 7,
                    "X18+" to 7,
                    "X 18+" to 7,
                    "X18" to 7,
                ),
        )

    /**
     * The code or name before a `-`, `:` or `/` to the system it reads: `UK` is GB, the countries of the XML by name,
     * and `FSK` for FSK's own table. A two-letter code is its own system ([CountryRatingTables.countryPrefix]).
     */
    val PREFIXES: Map<String, String> =
        mapOf(
            "UK" to "GB",
            "UNITED KINGDOM" to "GB",
            "GREAT BRITAIN" to "GB",
            "UNITED STATES" to "US",
            "GERMANY" to "DE",
            "DEUTSCHLAND" to "DE",
            "FSK" to FSK,
            "ARGENTINA" to "AR",
            "ARMENIA" to "AM",
            "AUSTRALIA" to "AU",
            "BRAZIL" to "BR",
            "BULGARIA" to "BG",
            "CANADA" to "CA",
            "CHINA" to "CN",
            "DENMARK" to "DK",
            "FINLAND" to "FI",
            "FRANCE" to "FR",
            "GREECE" to "GR",
            "HUNGARY" to "HU",
            "ICELAND" to "IS",
            "INDONESIA" to "ID",
            "IRELAND" to "IE",
            "ISRAEL" to "IL",
            "JAPAN" to "JP",
            "KOREA" to "KR",
            "SOUTH KOREA" to "KR",
            "MALAYSIA" to "MY",
            "NETHERLANDS" to "NL",
            "NEW ZEALAND" to "NZ",
            "POLAND" to "PL",
            "PORTUGAL" to "PT",
            "ROMANIA" to "RO",
            "RUSSIA" to "RU",
            "SERBIA" to "RS",
            "SINGAPORE" to "SG",
            "SLOVENIA" to "SI",
            "SPAIN" to "ES",
            "SWITZERLAND" to "CH",
            "TAIWAN" to "TW",
            "THAILAND" to "TH",
            "TURKEY" to "TR",
            "UKRAINE" to "UA",
        )
}
