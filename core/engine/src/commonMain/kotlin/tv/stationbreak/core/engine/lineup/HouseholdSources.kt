// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source

/**
 * "Household" collections are the ones a person made, as against the ones a server makes: a server's end in
 * " Collection". With "Use my collections" off, a starter's household-only collection sources are left out.
 */
object HouseholdSources {
    private const val SERVER_SUFFIX = " collection"

    /** True when [collectionName] (null: this TV holds none) does not end in " Collection" once folded. */
    fun isHouseholdOnly(collectionName: String?): Boolean =
        collectionName == null || !NameMatcher.fold(collectionName).endsWith(SERVER_SUFFIX)

    /**
     * [rules] without the collection sources whose collection, named in [snapshot], is household-only; [rules] itself
     * when none is dropped, and null when no source is left. A collection the snapshot lacks (before a full collection
     * pass) has no name and counts as household-only; it resolves to nothing then anyway.
     */
    fun without(
        rules: ChannelRules,
        snapshot: RuleSnapshot,
    ): ChannelRules? {
        val kept = ArrayList<Source>(rules.sources.size)
        for (source in rules.sources) {
            if (source is Source.Collection && isHouseholdOnly(snapshot.collections[source.id]?.name)) continue
            kept.add(source)
        }
        return when {
            kept.size == rules.sources.size -> rules
            kept.isEmpty() -> null
            else -> rules.copy(sources = kept)
        }
    }
}
