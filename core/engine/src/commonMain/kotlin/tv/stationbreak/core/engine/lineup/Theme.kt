// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.cleanStudioName
import tv.stationbreak.core.model.foldNameAscii

/**
 * A way to match a name, worked out on folded text with no regex. A matcher's [folded] name goes through the studio
 * clean and the ASCII fold of docs/rules-format.md, "String matching" (the same fold the studio source uses);
 * [matches] takes a name already folded the same way (see [fold]), so a loop over many names folds each once.
 */
sealed interface NameMatcher {
    /** The matcher's name, cleaned and folded. */
    val folded: String

    /** True when [foldedName] (already through [fold]) matches. */
    fun matches(foldedName: String): Boolean

    /** The whole name equals [name]. */
    data class Exact(
        val name: String,
    ) : NameMatcher {
        override val folded: String = foldedNonEmpty(name)

        override fun matches(foldedName: String): Boolean = foldedName == folded
    }

    /** The name starts with [name]. */
    data class Prefix(
        val name: String,
    ) : NameMatcher {
        override val folded: String = foldedNonEmpty(name)

        override fun matches(foldedName: String): Boolean = foldedName.startsWith(folded)
    }

    /** The name has [name] anywhere in it. */
    data class Contains(
        val name: String,
    ) : NameMatcher {
        override val folded: String = foldedNonEmpty(name)

        override fun matches(foldedName: String): Boolean = foldedName.contains(folded)
    }

    /**
     * The name has [name] as whole words: the characters on either side of it are not letters, digits or "_". The
     * folded name must start and end with a letter or digit, because a boundary next to punctuation is not a word
     * boundary and the match would mean something different.
     */
    data class Word(
        val name: String,
    ) : NameMatcher {
        override val folded: String = foldedNonEmpty(name)

        init {
            val edges = isAlnum(folded.first()) && isAlnum(folded.last())
            require(edges) { "A word starts and ends with a letter or digit" }
        }

        override fun matches(foldedName: String): Boolean {
            var from = foldedName.indexOf(folded)
            while (from >= 0) {
                val end = from + folded.length
                val startsWord = from == 0 || !isWordChar(foldedName[from - 1])
                val endsWord = end == foldedName.length || !isWordChar(foldedName[end])
                if (startsWord && endsWord) return true
                from = foldedName.indexOf(folded, from + 1)
            }
            return false
        }
    }

    companion object {
        /** [name] through the studio clean and the ASCII fold: the form [matches] reads. */
        fun fold(name: String): String = foldNameAscii(cleanStudioName(name))
    }
}

private fun foldedNonEmpty(name: String): String {
    val folded = NameMatcher.fold(name)
    require(folded.isNotEmpty()) { "A matcher needs a name" }
    return folded
}

private fun isAlnum(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9'

private fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '_'

/** What a recipe's anchor names. */
enum class AnchorKind { Collection, Title, Studio }

/** One anchor of a theme: a collection, a title (a series or a film) or a studio that [match] picks out by name. */
data class Anchor(
    val kind: AnchorKind,
    val match: NameMatcher,
)

/** What a themed channel promises about its shape. */
enum class FormatPromise { Films, ShortSeries }

/**
 * What makes a recipe themed: [anchors] (the sources it is built round), [themeStudios] (studios whose items fit it),
 * a [format] promise, and [animeOnly] for a channel that airs anime alone.
 */
data class Theme(
    val anchors: List<Anchor>,
    val themeStudios: List<NameMatcher>,
    val format: FormatPromise?,
    val animeOnly: Boolean,
)
