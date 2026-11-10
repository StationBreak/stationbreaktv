// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * Language codes as the server reports them, made comparable. Jellyfin sends ISO 639-2 codes, and files disagree on the
 * "bibliographic" and "terminologic" forms of the same language (`ger` and `deu`), and some carry the two-letter code.
 * [canonical] folds all of those to one form: the two-letter code where the language has one, else the lowercase
 * three-letter code. Two tracks are in the same language when their canonical codes are equal.
 */
object LanguageCodes {
    /** The languages worth folding: two-letter code, then every three-letter spelling of it. */
    private val FOLDED: Map<String, List<String>> =
        mapOf(
            "ar" to listOf("ara"),
            "bg" to listOf("bul"),
            "ca" to listOf("cat"),
            "cs" to listOf("cze", "ces"),
            "da" to listOf("dan"),
            "de" to listOf("ger", "deu"),
            "el" to listOf("gre", "ell"),
            "en" to listOf("eng"),
            "es" to listOf("spa"),
            "et" to listOf("est"),
            "fa" to listOf("per", "fas"),
            "fi" to listOf("fin"),
            "fr" to listOf("fre", "fra"),
            "he" to listOf("heb"),
            "hi" to listOf("hin"),
            "hr" to listOf("hrv"),
            "hu" to listOf("hun"),
            "id" to listOf("ind"),
            "is" to listOf("ice", "isl"),
            "it" to listOf("ita"),
            "ja" to listOf("jpn"),
            "ko" to listOf("kor"),
            "lt" to listOf("lit"),
            "lv" to listOf("lav"),
            "nl" to listOf("dut", "nld"),
            "no" to listOf("nor", "nob", "nb"),
            "pl" to listOf("pol"),
            "pt" to listOf("por"),
            "ro" to listOf("rum", "ron"),
            "ru" to listOf("rus"),
            "sk" to listOf("slo", "slk"),
            "sl" to listOf("slv"),
            "sr" to listOf("srp"),
            "sv" to listOf("swe"),
            "th" to listOf("tha"),
            "tr" to listOf("tur"),
            "uk" to listOf("ukr"),
            "vi" to listOf("vie"),
            "zh" to listOf("chi", "zho"),
        )

    private val BY_SPELLING: Map<String, String> =
        buildMap {
            for ((two, threes) in FOLDED) {
                put(two, two)
                threes.forEach { put(it, two) }
            }
        }

    /** Codes that say there is no language to speak of: undetermined, no linguistic content, multiple, uncoded. */
    private val NONE = setOf("und", "zxx", "mul", "mis", "unk", "")

    /** The comparable form of [code]; null when [code] is missing or says "no language". */
    fun canonical(code: String?): String? {
        val folded =
            code
                ?.trim()
                ?.lowercase()
                .orEmpty()
                .substringBefore('-')
                .substringBefore('_')
        return if (folded in NONE) null else BY_SPELLING[folded] ?: folded
    }

    /** True when both are known and name the same language. */
    fun same(
        a: String?,
        b: String?,
    ): Boolean {
        val left = canonical(a) ?: return false
        return left == canonical(b)
    }
}
