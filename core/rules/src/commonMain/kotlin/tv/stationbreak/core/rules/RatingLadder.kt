// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * The rating ladder of the rules format (docs/rules-format.md, "Maximum rating and the rating ladder"): eight tiers
 * that follow Jellyfin 12.1's United States table, with TV Parental Guidelines and film ratings on one scale. Names
 * on the same tier are equal. [normalise] and [rankOf] turn the free-form rating string a server returns into a tier,
 * or into nothing.
 */
object RatingLadder {
    /** The tiers from the lowest (0) to the highest (7), each with the ladder spelling of its names. */
    val tiers: List<List<String>> =
        listOf(
            listOf("TV-Y", "TV-G", "G", "Approved"),
            listOf("TV-Y7", "TV-Y7-FV"),
            listOf("TV-PG", "PG"),
            listOf("PG-13"),
            listOf("TV-14"),
            listOf("R"),
            listOf("TV-MA", "NC-17"),
            listOf("TV-X", "TV-AO"),
        )

    /** Every name on the ladder, in its exact spelling. These are the only values `maxRating` may take. */
    val names: Set<String> = tiers.flatten().toSet()

    private val byUpperCase: Map<String, String> = names.associateBy { upperAscii(it) }
    private val rankByName: Map<String, Int> = tiers.flatMapIndexed { rank, tier -> tier.map { it to rank } }.toMap()

    /**
     * The one descriptor group Jellyfin lists after these tiers: a run of the letters in this order, each at most once.
     * The server table has them for TV-PG and TV-14 (D, L, S, V) and for TV-MA (L, S, V). TV-Y7-FV is a ladder name.
     */
    private val descriptorLetters: Map<String, String> = mapOf("TV-PG" to "DLSV", "TV-14" to "DLSV", "TV-MA" to "LSV")

    private const val ASCII_CASE_OFFSET = 'a' - 'A'

    /**
     * The ladder spelling of [raw], or null when it has none. Trim spaces (U+0020) from both ends, upper-case the
     * ASCII letters `a` to `z` only, and look the result up on the ladder; failing that, accept a TV-PG, TV-14 or
     * TV-MA rating followed by one descriptor group as that tier's name (`TV-14-V` is `TV-14`).
     */
    fun normalise(raw: String): String? {
        val key = upperAscii(raw.trim(' '))
        return byUpperCase[key] ?: withoutDescriptors(key)
    }

    /** The tier (0 to 7) of [raw] after [normalise], or null when it has none, or when [raw] is null. */
    fun rankOf(raw: String?): Int? = raw?.let { normalise(it) }?.let { rankByName.getValue(it) }

    /** The tier of a name that is exactly on the ladder. */
    internal fun rankOfName(name: String): Int? = rankByName[name]

    private fun withoutDescriptors(key: String): String? {
        val tier = descriptorLetters.keys.firstOrNull { key.startsWith("$it-") } ?: return null
        val group = key.substring(tier.length + 1)
        return if (isDescriptorGroup(group, descriptorLetters.getValue(tier))) byUpperCase.getValue(tier) else null
    }

    /** True for a non-empty run of [letters] in their order, each at most once. */
    private fun isDescriptorGroup(
        group: String,
        letters: String,
    ): Boolean {
        val positions = group.map { letters.indexOf(it) }
        return positions.isNotEmpty() && positions.first() >= 0 && positions.zipWithNext().all { (a, b) -> a < b }
    }

    private fun upperAscii(text: String): String =
        buildString(text.length) { for (c in text) append(if (c in 'a'..'z') c - ASCII_CASE_OFFSET else c) }
}
