// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.foldNameAscii

/** The ASCII fold of docs/rules-format.md, "String matching"; one implementation, in the model beside the snapshot. */
internal fun foldAscii(text: String): String = foldNameAscii(text)

/**
 * The names of a list of rules, folded once, each remembering the rule it came from: `labels[i]` is the label (a rule's
 * index in its list) of `names[i]`. [contains] folds the probe the same way, so the match is ASCII case-insensitive and
 * exact otherwise.
 */
internal class NameSet(
    names: List<String>,
    labels: IntArray,
) {
    private val byName: Map<String, IntArray>

    init {
        val building = HashMap<String, MutableList<Int>>()
        for (index in names.indices) {
            building.getOrPut(foldAscii(names[index])) { ArrayList(1) }.add(labels[index])
        }
        val frozen = HashMap<String, IntArray>(building.size * 2)
        for ((name, indices) in building) frozen[name] = indices.toIntArray()
        byName = frozen
    }

    val isEmpty: Boolean get() = byName.isEmpty()

    /** True if some name equals [name] after folding both. */
    fun contains(name: String): Boolean = byName.containsKey(foldAscii(name))

    /** The labels (ascending) of the names equal to [name], or null. */
    fun indicesOf(name: String): IntArray? = byName[foldAscii(name)]

    /** As [indicesOf] for a [folded] name (already through `foldAscii`): no fold, so no allocation. */
    fun indicesOfFolded(folded: String): IntArray? = byName[folded]
}
