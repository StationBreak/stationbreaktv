// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

/** A row of what a channel plays, as the properties sheet lists them, in that order. */
enum class PlaysKind { Genres, Tags, Collections, Shows, Titles, Libraries, Studios, Years, Always, Never }

/** One thing a [PlaysRow] lists: a name, a range of years, or "anything rated above" a rating-ladder name. */
sealed interface PlaysItem {
    data class Name(
        val text: String,
    ) : PlaysItem

    data class Years(
        val from: Int,
        val to: Int,
    ) : PlaysItem

    data class RatedAbove(
        val rating: String,
    ) : PlaysItem
}

/** A row of what a channel plays: its [kind] and the [items] it lists, never empty. */
data class PlaysRow(
    val kind: PlaysKind,
    val items: List<PlaysItem>,
)

/**
 * What a channel plays as rows, in the properties sheet's order, each with anything in it: its sources by kind (genres,
 * tags, collections, shows, titles, libraries, then studios and years), the theme's [anchors] ("Always"), and what it
 * never plays: what is left out, the studios excluded, and anything rated above its ceiling (the [description]'s, else
 * [leftoversCeiling]). The one list the sheet and the guide's channel box both word (#777), so they never disagree.
 * What narrows the pool (genres, runtimes and the like) is not here: the sheet adds it as a row of its own.
 */
fun playsRowsOf(
    description: RulesDescription?,
    anchors: List<String> = emptyList(),
    leftoversCeiling: String? = null,
): List<PlaysRow> {
    val sources = description?.sources.orEmpty()
    val kept = description?.kept.orEmpty()
    val ceiling = description?.ceiling ?: leftoversCeiling
    val never =
        description?.leaveOut.orEmpty().map { it.label } +
            kept.filterIsInstance<KeptItem.StudioExclusion>().map { it.name }
    val rows =
        listOf(
            PlaysKind.Genres to names(sources.filterIsInstance<DraftSource.Genre>().map { it.label }),
            PlaysKind.Tags to names(sources.filterIsInstance<DraftSource.Tag>().map { it.label }),
            PlaysKind.Collections to names(sources.filterIsInstance<DraftSource.Collection>().map { it.label }),
            PlaysKind.Shows to names(sources.filterIsInstance<DraftSource.Show>().map { it.label }),
            PlaysKind.Titles to names(sources.filterIsInstance<DraftSource.Pick>().map { it.label }),
            PlaysKind.Libraries to names(sources.filterIsInstance<DraftSource.Library>().map { it.label }),
            PlaysKind.Studios to names(kept.filterIsInstance<KeptItem.StudioSource>().map { it.name }),
            PlaysKind.Years to kept.filterIsInstance<KeptItem.YearsSource>().map { PlaysItem.Years(it.from, it.to) },
            PlaysKind.Always to names(anchors),
            PlaysKind.Never to names(never) + listOfNotNull(ceiling?.let { PlaysItem.RatedAbove(it) }),
        )
    return rows.filter { (_, items) -> items.isNotEmpty() }.map { (kind, items) -> PlaysRow(kind, items) }
}

private fun names(texts: List<String>): List<PlaysItem> = texts.map { PlaysItem.Name(it) }
