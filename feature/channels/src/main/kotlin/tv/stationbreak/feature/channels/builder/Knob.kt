// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.DraftChoices
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
    taken: Set<Int> = emptySet(),
): ChannelDraft =
    when (knob) {
        Knob.Order -> copy(order = (order ?: PlayOrder.Shuffle).turnedTo(by, wrap))
        Knob.SeriesCap, Knob.FilmCap -> capsTurned(knob, by)
        Knob.Number -> copy(number = numbered(by, taken), numberChosen = true)
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
        copy(seriesCap = (series + by).coerceIn(CAP_RANGE), filmCap = film)
    } else {
        copy(seriesCap = series, filmCap = (film + by).coerceIn(CAP_RANGE))
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
// next free number in that direction, never leaving the band (and staying where it is if there is none).
private fun ChannelDraft.numbered(
    by: Int,
    taken: Set<Int>,
): Int {
    val band = if (kids) DraftChoices.kidsNumbers else DraftChoices.grownUpNumbers
    val current = number?.takeIf { it in band } ?: return band.firstOrNull { it !in taken } ?: band.first
    var to = current + by
    while (to in band && to in taken) to += by
    return if (to in band) to else current
}
