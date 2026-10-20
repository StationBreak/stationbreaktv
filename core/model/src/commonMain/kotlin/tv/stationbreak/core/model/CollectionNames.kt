// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * The current names of an account's collections that are not deleted, by ID (spec 12). A [RuleSnapshot] holds no
 * names, so a collection hint ("Kaiju") is matched against these. A name is text a person typed: [toString] shows only
 * how many there are, so a name never reaches a log.
 */
class CollectionNames(
    names: Map<CanonicalId, String>,
) {
    private val byId: Map<CanonicalId, String> = names.toMap()

    /** The name of the collection [id], or null when it is not one of these (unknown, or deleted). */
    operator fun get(id: CanonicalId): String? = byId[id]

    /** The IDs of the collections that have a name here. */
    val ids: Set<CanonicalId> get() = byId.keys

    override fun toString(): String = "CollectionNames(${byId.size})"
}
