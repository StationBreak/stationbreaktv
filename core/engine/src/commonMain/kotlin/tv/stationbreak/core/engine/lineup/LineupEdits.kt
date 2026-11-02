// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId

/** One change the user makes to a lineup's channels: switching on or off, or changing a number (spec 21). */
sealed interface LineupEdit {
    /** The channel the edit is for. */
    val id: ChannelId

    /** [confirmedOptIn] must be true to switch on an opt-in (adult) starter. */
    data class SwitchOn(
        override val id: ChannelId,
        val confirmedOptIn: Boolean = false,
    ) : LineupEdit

    /** Hides the channel. */
    data class SwitchOff(
        override val id: ChannelId,
    ) : LineupEdit

    /** Moves [id] to [number]; with [swapWith], that channel, which must hold [number], takes [id]'s old number. */
    data class SetNumber(
        override val id: ChannelId,
        val number: Int,
        val swapWith: ChannelId? = null,
    ) : LineupEdit

    /** Back to the default number; with [swapWith], that channel, which must hold it, takes [id]'s current number. */
    data class ClearNumber(
        override val id: ChannelId,
        val swapWith: ChannelId? = null,
    ) : LineupEdit
}

/** Why an edit was not applied. */
sealed interface EditRefusal {
    /** No channel has the edit's ID. */
    data object UnknownChannel : EditRefusal

    /** The channel could not be read, so it cannot be switched on. */
    data object NotSwitchable : EditRefusal

    /** An opt-in starter needs the user's confirmation to switch on. */
    data object OptInNotConfirmed : EditRefusal

    /** The channel is the only one switched on. */
    data object LastOn : EditRefusal

    /** The channel has no number and none is free in its bands. */
    data object NoFreeNumber : EditRefusal

    /** The number is outside [LineupEdits.MIN_NUMBER]..[LineupEdits.MAX_NUMBER]. */
    data object OutOfRange : EditRefusal

    /** The numbers 1 to 99 are for kid channels. */
    data object KidsBandOnly : EditRefusal

    /** Another channel, [holder], has the number (or the one named to swap is not it). */
    data class NumberTaken(
        val holder: ChannelId,
    ) : EditRefusal
}

/** What an edit did: the new lineup, or the reason it did nothing. */
sealed interface EditOutcome {
    data class Applied(
        val lineup: Lineup,
    ) : EditOutcome

    data class Refused(
        val refusal: EditRefusal,
    ) : EditOutcome
}

/**
 * Edits to a lineup (spec 21): pure, and the result always keeps the [Lineup] invariants. Nothing here checks the
 * household cap; the user may switch on as many channels as they like.
 */
object LineupEdits {
    const val MIN_NUMBER = 1
    const val MAX_NUMBER = 999

    /** Applies [edit] to [lineup] at [nowMs], numbering from [catalog]'s recipes and retired numbers. */
    fun apply(
        lineup: Lineup,
        edit: LineupEdit,
        nowMs: Long,
        catalog: StarterCatalogView = StarterCatalog,
    ): EditOutcome {
        val channel = lineup.channels.firstOrNull { it.id == edit.id } ?: return refused(EditRefusal.UnknownChannel)
        return when (edit) {
            is LineupEdit.SwitchOn -> {
                switchOn(lineup, channel, edit.confirmedOptIn, nowMs, catalog)
            }

            is LineupEdit.SwitchOff -> {
                switchOff(lineup, channel)
            }

            is LineupEdit.SetNumber -> {
                setNumber(lineup, channel, edit.number, edit.swapWith, catalog)
            }

            is LineupEdit.ClearNumber -> {
                val default = channel.defaultNumber
                if (default == null || channel.number == default) {
                    EditOutcome.Applied(lineup)
                } else {
                    setNumber(lineup, channel, default, edit.swapWith, catalog)
                }
            }
        }
    }

    private fun refused(refusal: EditRefusal): EditOutcome = EditOutcome.Refused(refusal)

    private fun applied(
        lineup: Lineup,
        channel: LineupChannel,
    ): EditOutcome = EditOutcome.Applied(replaced(lineup, channel))

    private fun switchOn(
        lineup: Lineup,
        channel: LineupChannel,
        confirmedOptIn: Boolean,
        nowMs: Long,
        catalog: StarterCatalogView,
    ): EditOutcome =
        when {
            channel.state == ChannelState.On -> EditOutcome.Applied(lineup)
            channel.source is ChannelSource.Unusable -> refused(EditRefusal.NotSwitchable)
            !confirmedOptIn && isOptIn(channel, catalog) -> refused(EditRefusal.OptInNotConfirmed)
            else -> switchedOn(lineup, channel, nowMs, catalog)
        }

    // A channel without a number gets one first, as the user's (an override), so its default stays empty.
    private fun switchedOn(
        lineup: Lineup,
        channel: LineupChannel,
        nowMs: Long,
        catalog: StarterCatalogView,
    ): EditOutcome {
        val on = channel.copy(state = ChannelState.On, onSinceMs = nowMs)
        val kid = KidStatus.isKidChannel(channel)
        val number = channel.number ?: FreeNumbers.lowest(lineup.channels, channel, kid, catalog)
        return when {
            number == null -> refused(EditRefusal.NoFreeNumber)
            channel.number == null -> applied(lineup, on.copy(numberOverride = number))
            else -> applied(lineup, on)
        }
    }

    private fun switchOff(
        lineup: Lineup,
        channel: LineupChannel,
    ): EditOutcome {
        val onCount = lineup.channels.count { it.state == ChannelState.On }
        return when {
            channel.state == ChannelState.UserHidden -> EditOutcome.Applied(lineup)
            channel.state == ChannelState.On && onCount == 1 -> refused(EditRefusal.LastOn)
            else -> applied(lineup, channel.copy(state = ChannelState.UserHidden))
        }
    }

    private fun setNumber(
        lineup: Lineup,
        channel: LineupChannel,
        number: Int,
        swapWith: ChannelId?,
        catalog: StarterCatalogView,
    ): EditOutcome {
        val holder = lineup.channels.firstOrNull { it.id != channel.id && it.number == number }
        val refusal =
            when {
                number !in MIN_NUMBER..MAX_NUMBER -> EditRefusal.OutOfRange
                number in Band.Kids.numbers && !KidStatus.isKidChannel(channel) -> EditRefusal.KidsBandOnly
                holder != null && swapWith != holder.id -> EditRefusal.NumberTaken(holder.id)
                else -> null
            }
        return when {
            refusal != null -> refused(refusal)
            channel.number == number -> EditOutcome.Applied(lineup)
            holder == null -> applied(lineup, channel.withNumber(number))
            else -> swapped(lineup, channel, holder, number, catalog)
        }
    }

    // The holder takes the edited channel's old number, or one of its own bands' free ones if that had none.
    private fun swapped(
        lineup: Lineup,
        channel: LineupChannel,
        holder: LineupChannel,
        number: Int,
        catalog: StarterCatalogView,
    ): EditOutcome {
        val old = channel.number
        val holderIsKid = KidStatus.isKidChannel(holder)
        if (old != null && old in Band.Kids.numbers && !holderIsKid) return refused(EditRefusal.KidsBandOnly)
        val moved = channel.withNumber(number)
        // Looking for the holder's number: the edited channel already has the new one, the holder keeps its default.
        val others =
            lineup.channels.map {
                when (it.id) {
                    channel.id -> moved
                    holder.id -> holder.copy(numberOverride = null)
                    else -> it
                }
            }
        val holderNumber = old ?: FreeNumbers.lowest(others, holder, holderIsKid, catalog)
        return if (holderNumber == null) {
            refused(EditRefusal.NoFreeNumber)
        } else {
            EditOutcome.Applied(replaced(lineup, moved, holder.withNumber(holderNumber)))
        }
    }

    // The override that shows the channel at [number]: none when that is its default.
    private fun LineupChannel.withNumber(number: Int): LineupChannel {
        val override = if (number == defaultNumber) null else number
        return copy(numberOverride = override)
    }

    private fun replaced(
        lineup: Lineup,
        vararg changed: LineupChannel,
    ): Lineup {
        val byId = changed.associateBy { it.id }
        return lineup.copy(channels = lineup.channels.map { byId[it.id] ?: it })
    }

    private fun isOptIn(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): Boolean {
        val source = channel.source
        return source is ChannelSource.Starter && catalog.recipes.any { it.key == source.recipeKey && it.optIn }
    }
}

// Finding the number a channel takes when it has none.
private object FreeNumbers {
    // Where a grown-up channel looks for a number once its own band is full.
    private val ANY_GROWN_UP = Band.General.numbers.first..LineupEdits.MAX_NUMBER

    // The lowest number free in the channel's own band, then (for a grown-up channel) anywhere from 100; free means
    // no channel has it as a default or an override and the catalog has not retired it.
    fun lowest(
        channels: List<LineupChannel>,
        channel: LineupChannel,
        isKid: Boolean,
        catalog: StarterCatalogView,
    ): Int? {
        val used = channels.flatMap { listOfNotNull(it.defaultNumber, it.numberOverride) } + catalog.retiredNumbers
        val ranges =
            when {
                isKid -> listOf(Band.Kids.numbers)
                else -> listOfNotNull(bandOf(channel, catalog), ANY_GROWN_UP)
            }
        return ranges.asSequence().flatMap { it.asSequence() }.firstOrNull { it !in used }
    }

    private fun bandOf(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): IntRange? =
        when (val source = channel.source) {
            is ChannelSource.Starter -> {
                catalog.recipes
                    .firstOrNull { it.key == source.recipeKey }
                    ?.band
                    ?.numbers
            }

            is ChannelSource.Leftovers -> {
                Band.General.numbers
            }

            is ChannelSource.Custom -> {
                LineupDefaults.CUSTOM_NUMBERS
            }

            is ChannelSource.Unusable -> {
                null
            }
        }
}
