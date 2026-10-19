// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The library of the rule-snapshot tests: its ID is canonical, which a `RuleItem` needs. */
internal val RULE_LIBRARY: ItemId = RuleLibraryGen.LIBRARY_A

/** The canonical ID for the number [n] (see [hexId]). */
internal fun canon(n: Int): CanonicalId = CanonicalId.parse(hexId(n))

/** An instant [ms] milliseconds since 1970, for a cut-off. */
internal fun at(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

/** An episode of the series numbered [series] (null for none), in [RULE_LIBRARY], created a day before [EPOCH]. */
internal fun episodeOf(
    n: Int,
    series: Int?,
    library: ItemId = RULE_LIBRARY,
    rating: String? = null,
) = MediaItem(
    id = ItemId(hexId(n)),
    name = "Name of $n",
    kind = ItemKind.Episode,
    runtime = 30.minutes,
    parentId = library,
    dateCreated = EPOCH - 1440.minutes,
    officialRating = rating,
    seriesId = series?.let { ItemId(hexId(it)) },
    seasonNumber = 1,
    episodeNumber = n,
)
