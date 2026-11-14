// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.rules.ChannelRules

/**
 * How many rules files a loaded lineup holds at each rules `formatVersion` (a channel's rules and a starter's previous
 * rules; a channel whose rules need a newer app holds none this app can count), and how many of them its load
 * [upgraded] to 4 (docs/adr/0026-studio-fold-2.md, Versioning). Counts only: no name, ID or content. The versions are
 * of the lineup as loaded, after the upgrade: `v3` counts what is still 3 in memory. [upgraded] above none on a second
 * start means the earlier rewrite did not land. The diagnostics screen (F3.6) should show the same.
 */
data class RulesFormats(
    val v1: Int = 0,
    val v2: Int = 0,
    val v3: Int = 0,
    val v4: Int = 0,
    val upgraded: Int = 0,
) {
    /** The log line, logged under the `Lineup` tag. */
    fun line(): String = "rules formats v1=$v1 v2=$v2 v3=$v3 v4=$v4 upgraded=$upgraded"

    internal companion object {
        /** The rules files of [lineup] counted by version, and [upgraded] as its load said. */
        fun of(
            lineup: Lineup,
            upgraded: Int,
        ): RulesFormats {
            // A reader only gives 1 to 4.
            val counts =
                lineup.channels
                    .flatMap { rulesOf(it.source) }
                    .groupingBy { it.formatVersion }
                    .eachCount()
            return RulesFormats(counts.of(V1), counts.of(V2), counts.of(V3), counts.of(V4), upgraded)
        }

        private fun Map<Int, Int>.of(version: Int): Int = get(version) ?: 0

        private fun rulesOf(source: ChannelSource): List<ChannelRules> =
            when (source) {
                is ChannelSource.Starter -> listOfNotNull(source.rules, source.previousRules)
                is ChannelSource.Custom -> listOf(source.rules)
                is ChannelSource.Leftovers, is ChannelSource.Unusable -> emptyList()
            }

        private const val V1 = 1
        private const val V2 = 2
        private const val V3 = 3
        private const val V4 = 4
    }
}
