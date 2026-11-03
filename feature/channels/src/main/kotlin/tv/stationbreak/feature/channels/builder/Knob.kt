// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.PlayOrder

/** The value a Left or Right on [focus] turns, or null where those keys only move. */
internal enum class Knob { Order, SeriesCap, FilmCap, Number, Years, Runtime, Only, Rating }

internal fun knobOf(focus: BuilderFocus?): Knob? =
    when (focus) {
        BuilderFocus.PlayRow(PlayRowKind.Order), BuilderFocus.Sheet(SheetRow.PlayOrder) -> Knob.Order
        BuilderFocus.PlayRow(PlayRowKind.SeriesCap), BuilderFocus.Sheet(SheetRow.SeriesCap) -> Knob.SeriesCap
        BuilderFocus.PlayRow(PlayRowKind.FilmCap), BuilderFocus.Sheet(SheetRow.FilmCap) -> Knob.FilmCap
        BuilderFocus.NameRow(NameRowKind.Number), BuilderFocus.Sheet(SheetRow.Number) -> Knob.Number
        BuilderFocus.NarrowRow(NarrowRowKind.Years) -> Knob.Years
        BuilderFocus.NarrowRow(NarrowRowKind.Runtime) -> Knob.Runtime
        BuilderFocus.NarrowRow(NarrowRowKind.Only) -> Knob.Only
        BuilderFocus.NarrowRow(NarrowRowKind.Rating) -> Knob.Rating
        else -> null
    }

/** [this] with [knob] turned [by] one step ([wrap]: past the last choice goes round to the first, as Select does). */
internal fun ChannelDraft.turned(
    knob: Knob,
    by: Int,
    wrap: Boolean,
    free: List<Int> = emptyList(),
): ChannelDraft =
    when (knob) {
        Knob.Order -> copy(order = (order ?: PlayOrder.Shuffle).turnedTo(by, wrap))
        Knob.SeriesCap, Knob.FilmCap -> capsTurned(knob, by)
        Knob.Number -> copy(number = numbered(by, free), numberChosen = true)
        else -> copy(filters = filters.turned(knob, by, wrap, kids))
    }

// Past the last order, a select goes round to the first.
private fun PlayOrder.turnedTo(
    by: Int,
    wrap: Boolean,
): PlayOrder {
    val to = stepped(by)
    return if (wrap && to == this) PlayOrder.Shuffle else to
}

// A cap turned gives both caps a value: one without the other would be incomplete.
private fun ChannelDraft.capsTurned(
    knob: Knob,
    by: Int,
): ChannelDraft {
    val series = seriesCap ?: ChannelDraft.DEFAULT_SERIES_CAP
    val film = filmCap ?: ChannelDraft.DEFAULT_FILM_CAP
    return if (knob == Knob.SeriesCap) {
        copy(seriesCap = (series + by).coerceIn(ChannelDraft.CAP_RANGE), filmCap = film)
    } else {
        copy(seriesCap = series, filmCap = (film + by).coerceIn(ChannelDraft.CAP_RANGE))
    }
}

private fun DraftFilters.turned(
    knob: Knob,
    by: Int,
    wrap: Boolean,
    kids: Boolean,
): DraftFilters =
    when (knob) {
        Knob.Years -> withYears(by, wrap)
        Knob.Runtime -> withRuntime(by, wrap)
        Knob.Only -> withOnly(by, wrap)
        else -> withRating(by, wrap, kids)
    }

// A number outside the channel's band (or none) starts at the band's first free number; inside it, one step to the
// next free number in that direction (the domain's list of them), staying where it is if there is none.
private fun ChannelDraft.numbered(
    by: Int,
    free: List<Int>,
): Int {
    val band = if (kids) ChannelNumbers.kids else ChannelNumbers.custom
    val inBand = free.filter { it in band }
    val current = number?.takeIf { it in band } ?: return inBand.firstOrNull() ?: band.first
    val to = if (by > 0) inBand.firstOrNull { it > current } else inBand.lastOrNull { it < current }
    return to ?: current
}

// The numbers the number rows step over: the free ones in the draft's band, and an edited channel's own.
internal fun BuilderModel.numberChoices(): List<Int> {
    val own = opened.number.takeIf { editing() && it != null && (it in ChannelNumbers.kids) == draft.kids }
    return (free + listOfNotNull(own)).distinct().sorted()
}
