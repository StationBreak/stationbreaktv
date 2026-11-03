// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.DraftChoices
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.OnlyKind
import tv.stationbreak.core.domain.lineup.builder.PlayOrder

private const val FIRST_DECADE = 1920
private const val LAST_DECADE = 2020
private const val DECADE = 10
private const val SHORT = 30
private const val RUNTIME_STEP = 30
private const val LONG = 120

/** The choices a year row offers beside "any": a decade each. */
internal val YEAR_CHOICES: List<Pair<Int, Int>> =
    (FIRST_DECADE..LAST_DECADE step DECADE).map { it to it + DECADE - 1 }

/** The choices a runtime row offers beside "any": minimum and maximum minutes. */
internal val RUNTIME_CHOICES: List<Pair<Int?, Int?>> =
    listOf(null to SHORT) +
        (SHORT until LONG step RUNTIME_STEP).map { it to it + RUNTIME_STEP } +
        listOf(LONG to null)

/** The sheet's rows for [draft]: see [BuilderUi.sheetRows]. The actions are listed only once the channel is [ready]. */
internal fun sheetRowsOf(
    draft: ChannelDraft,
    editing: Boolean,
    dirty: Boolean,
    ready: Boolean,
): List<SheetRow> =
    buildList {
        add(SheetRow.Name)
        add(SheetRow.Sources)
        add(SheetRow.NarrowDown)
        add(SheetRow.PlayOrder)
        if (draft.showsCaps()) {
            add(SheetRow.SeriesCap)
            add(SheetRow.FilmCap)
        }
        add(SheetRow.Kids)
        add(SheetRow.Number)
        if (draft.keepsReplaceable()) add(SheetRow.Kept)
        add(SheetRow.Preview)
        if (ready) {
            add(SheetRow.Save)
            if (editing && !dirty && draft.canReshuffle()) add(SheetRow.Reshuffle)
            if (editing) add(SheetRow.Delete)
        }
    }

/** The rows of the Name page, top to bottom: an edit's page ends in Done, a new channel's in Save and watch. */
internal fun nameRowsOf(editPage: Boolean): List<NameRowKind> =
    listOf(NameRowKind.Name, NameRowKind.Number, if (editPage) NameRowKind.Done else NameRowKind.Save)

/** The rows of the Play page for [draft], top to bottom. */
internal fun playRowsOf(draft: ChannelDraft): List<PlayRowKind> =
    buildList {
        add(PlayRowKind.Order)
        if (draft.showsCaps()) {
            add(PlayRowKind.SeriesCap)
            add(PlayRowKind.FilmCap)
        }
        add(PlayRowKind.Kids)
        if (draft.keepsReplaceable()) add(PlayRowKind.Kept)
    }

/** The order after [this] one in the cycle Shuffle, Rotation, In order. */
internal fun PlayOrder.stepped(by: Int): PlayOrder {
    val orders = PlayOrder.entries
    return orders[(ordinal + by).coerceIn(0, orders.lastIndex)]
}

/** [filters] with the next (or previous) year choice; "any" comes before the first. */
internal fun DraftFilters.withYears(
    by: Int,
    wrap: Boolean,
): DraftFilters {
    val at = YEAR_CHOICES.indexOfFirst { it.first == yearFrom && it.second == yearTo }
    val to = steppedIndex(at, by, YEAR_CHOICES.size, wrap)
    val choice = YEAR_CHOICES.getOrNull(to)
    return copy(yearFrom = choice?.first, yearTo = choice?.second)
}

internal fun DraftFilters.withRuntime(
    by: Int,
    wrap: Boolean,
): DraftFilters {
    val at = RUNTIME_CHOICES.indexOfFirst { it.first == minMinutes && it.second == maxMinutes }
    val to = steppedIndex(at, by, RUNTIME_CHOICES.size, wrap)
    val choice = RUNTIME_CHOICES.getOrNull(to)
    return copy(minMinutes = choice?.first, maxMinutes = choice?.second)
}

// The ladder's tiers, lowest first, then "no ceiling" above them for a grown-up channel. A stored ceiling is placed
// by its tier, so a name the list does not spell (TV-Y7-FV) still steps to its neighbours. Left never reaches
// "no ceiling".
internal fun DraftFilters.withRating(
    by: Int,
    wrap: Boolean,
    kids: Boolean,
): DraftFilters {
    val tiers = DraftChoices.ratingCeilings(kids)
    val last = if (kids) tiers.lastIndex else tiers.size
    val at = DraftChoices.ratingTier(maxRating, kids)?.coerceAtMost(tiers.lastIndex) ?: tiers.size
    val to = (at + by).let { if (it > last) (if (wrap) 0 else last) else it.coerceAtLeast(0) }
    return copy(maxRating = tiers.getOrNull(to))
}

internal fun DraftFilters.withOnly(
    by: Int,
    wrap: Boolean,
): DraftFilters {
    val to = steppedIndex(OnlyKind.entries.indexOf(only), by, OnlyKind.entries.size, wrap)
    return copy(only = OnlyKind.entries.getOrNull(to))
}

// Index -1 is "any". Stepping left of the first is "any"; past the last either stops, or (a select) wraps to "any".
private fun steppedIndex(
    at: Int,
    by: Int,
    size: Int,
    wrap: Boolean,
): Int {
    val to = at + by
    return when {
        to >= size -> if (wrap) -1 else size - 1
        else -> to.coerceAtLeast(-1)
    }
}
