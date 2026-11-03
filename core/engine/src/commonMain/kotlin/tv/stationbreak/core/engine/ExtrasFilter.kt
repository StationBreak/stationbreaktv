// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.foldNameAscii
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/** Why [ExtrasFilter] dropped an item. The first reason that applies is the one counted. */
enum class ExtrasReason { NamedExtra, RuntimeUnderAMinute, RuntimeOverLimit, ShorterDuplicate }

/** What [ExtrasFilter.filter] gives back: the [snapshot] without the extras, and how many went for each reason. */
class FilteredSnapshot(
    val snapshot: RuleSnapshot,
    val dropped: Map<ExtrasReason, Int>,
)

/**
 * The extras filter (spec 23, Resolution 15): it takes out of a snapshot what a channel should never air, whatever its
 * rules say: a clip under a minute, an item past the sanity limit, a creditless opening or ending, trailer or
 * featurette that is short, and the shorter copies of one episode number. It is pure, keeps the item order and every
 * other field of the snapshot, and reads only runtimes, kinds, series and episode numbers and the item name. The lists
 * and limits below are frozen under [VERSION_NAME]; a change to either is a new version name.
 */
object ExtrasFilter {
    /** The name of this version of the patterns and limits. */
    const val VERSION_NAME = "extras-1"

    /** Under this an item is unairable. */
    const val MIN_RUNTIME_MS = 60_000L

    /** An episode longer than this (3 hours) is dropped. */
    const val EPISODE_LIMIT_MS = 10_800_000L

    /** A film longer than this (4.5 hours) is dropped. */
    const val FILM_LIMIT_MS = 16_200_000L

    /** The name patterns apply to items of this length (10 minutes) or less. */
    const val NAME_RULE_UP_TO_MS = 600_000L

    private val reasons = ExtrasReason.entries.toTypedArray()

    /** Drops the extras of [snapshot]. The same snapshot comes back when nothing is dropped. */
    fun filter(snapshot: RuleSnapshot): FilteredSnapshot {
        val items = snapshot.items
        val counts = IntArray(reasons.size)
        val alive = BooleanArray(items.size)
        val numbered = ArrayList<Int>()
        for (index in items.indices) {
            val reason = runtimeOrNameReason(items[index])
            if (reason != null) {
                counts[reason.ordinal]++
                continue
            }
            alive[index] = true
            if (isNumberedEpisode(items[index])) numbered.add(index)
        }
        dropShorterDuplicates(items, numbered, alive, counts)
        var droppedTotal = 0
        for (count in counts) droppedTotal += count
        if (droppedTotal == 0) return FilteredSnapshot(snapshot, emptyMap())
        val kept = ArrayList<RuleItem>(items.size - droppedTotal)
        for (index in items.indices) if (alive[index]) kept.add(items[index])
        val dropped = LinkedHashMap<ExtrasReason, Int>()
        for (reason in reasons) if (counts[reason.ordinal] > 0) dropped[reason] = counts[reason.ordinal]
        return FilteredSnapshot(snapshot.copy(items = kept), dropped)
    }

    private fun isNumberedEpisode(entry: RuleItem): Boolean {
        val item = entry.item
        val hasKey = item.seriesId != null && item.seasonNumber != null && item.episodeNumber != null
        return item.kind == ScheduleItemKind.Episode && hasKey
    }

    private fun runtimeOrNameReason(entry: RuleItem): ExtrasReason? {
        val runtime = entry.item.runtimeMs
        val limit = if (entry.item.kind == ScheduleItemKind.Movie) FILM_LIMIT_MS else EPISODE_LIMIT_MS
        return when {
            runtime < MIN_RUNTIME_MS -> ExtrasReason.RuntimeUnderAMinute
            runtime > limit -> ExtrasReason.RuntimeOverLimit
            runtime <= NAME_RULE_UP_TO_MS && isNamedExtra(entry.name) -> ExtrasReason.NamedExtra
            else -> null
        }
    }

    // One sort of the surviving numbered episodes by (series, season, episode); in each run of equal keys the longest
    // stays, and the smaller canonical ID among equals.
    private fun dropShorterDuplicates(
        items: List<RuleItem>,
        numbered: ArrayList<Int>,
        alive: BooleanArray,
        counts: IntArray,
    ) {
        if (numbered.size < 2) return
        numbered.sortWith(EpisodeKeyOrder(items))
        var runStart = 0
        for (position in 1..numbered.size) {
            if (position < numbered.size && sameEpisode(items[numbered[runStart]], items[numbered[position]])) continue
            if (position - runStart > 1) {
                var best = numbered[runStart]
                for (k in runStart + 1 until position) {
                    if (beats(items[numbered[k]], items[best])) best = numbered[k]
                }
                for (k in runStart until position) {
                    if (numbered[k] != best) {
                        alive[numbered[k]] = false
                        counts[ExtrasReason.ShorterDuplicate.ordinal]++
                    }
                }
            }
            runStart = position
        }
    }

    private fun sameEpisode(
        a: RuleItem,
        b: RuleItem,
    ): Boolean =
        a.item.seriesId == b.item.seriesId &&
            a.item.seasonNumber == b.item.seasonNumber &&
            a.item.episodeNumber == b.item.episodeNumber

    // Longer wins; as long, the smaller canonical ID.
    private fun beats(
        candidate: RuleItem,
        best: RuleItem,
    ): Boolean =
        candidate.item.runtimeMs > best.item.runtimeMs ||
            (candidate.item.runtimeMs == best.item.runtimeMs && candidate.item.id.value < best.item.id.value)

    // Only numbered episodes are sorted, so the fallbacks are never read.
    private class EpisodeKeyOrder(
        private val items: List<RuleItem>,
    ) : Comparator<Int> {
        override fun compare(
            a: Int,
            b: Int,
        ): Int {
            val x = items[a].item
            val y = items[b].item
            var order =
                x.seriesId
                    ?.value
                    .orEmpty()
                    .compareTo(y.seriesId?.value.orEmpty())
            if (order == 0) order = (x.seasonNumber ?: 0).compareTo(y.seasonNumber ?: 0)
            if (order == 0) order = (x.episodeNumber ?: 0).compareTo(y.episodeNumber ?: 0)
            return order
        }
    }

    /**
     * Whether [name] is a creditless opening or ending, trailer or featurette by its words: folded, split on every
     * character outside `a-z0-9`, and a word is `creditless`, `opening`, `ending`, `trailer`,
     * `featurette`, `ncop` or `nced` (with digits or without), or `op` or `ed` followed by digits. "nc op1" matches
     * through its `op1` word. `op` and `ed` alone never match. A null name matches nothing.
     */
    internal fun isNamedExtra(name: String?): Boolean {
        val text = if (name == null) "" else foldNameAscii(name)
        var found = false
        var start = -1
        var i = 0
        while (i <= text.length && !found) {
            if (i < text.length && isWordChar(text[i])) {
                if (start < 0) start = i
            } else if (start >= 0) {
                found = wordMatches(text, start, i)
                start = -1
            }
            i++
        }
        return found
    }

    private fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9'

    private fun wordMatches(
        text: String,
        start: Int,
        end: Int,
    ): Boolean =
        isWholeWord(text, start, end) ||
            isNumbered(text, start, end, "ncop", digitsOptional = true) ||
            isNumbered(text, start, end, "nced", digitsOptional = true) ||
            isNumbered(text, start, end, "op", digitsOptional = false) ||
            isNumbered(text, start, end, "ed", digitsOptional = false)

    private fun isWholeWord(
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        for (word in WORDS) {
            if (end - start == word.length && text.regionMatches(start, word, 0, word.length)) return true
        }
        return false
    }

    // [prefix] then only digits; at least one digit unless [digitsOptional].
    private fun isNumbered(
        text: String,
        start: Int,
        end: Int,
        prefix: String,
        digitsOptional: Boolean,
    ): Boolean {
        val digits = end - start - prefix.length
        var matches = digits >= 0 && text.regionMatches(start, prefix, 0, prefix.length)
        if (matches && digits == 0) matches = digitsOptional
        var i = start + prefix.length
        while (matches && i < end) {
            matches = text[i] in '0'..'9'
            i++
        }
        return matches
    }

    private val WORDS = listOf("creditless", "opening", "ending", "trailer", "featurette")
}
