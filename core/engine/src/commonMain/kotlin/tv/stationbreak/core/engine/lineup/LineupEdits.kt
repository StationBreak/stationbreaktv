// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.ChannelRules

/** One change the user makes to a lineup's channels: on or off, a number, or a custom channel added or edited. */
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

    /**
     * Adds a custom channel, switched on. [number] null means "the first free one", worked out inside
     * [LineupEdits.apply] (so inside the caller's lock): from 600 for grown-up, 1..99 when [kids]. A given number
     * decides alone whether the channel is a kid channel (1..99); [kids] with a number outside that band is refused.
     */
    data class AddCustom(
        override val id: ChannelId,
        val rules: ChannelRules,
        val number: Int?,
        val kids: Boolean = false,
    ) : LineupEdit

    /**
     * Replaces a custom channel's rules and number; with [swapWith], that channel, which holds [number], takes the old
     * number. The stored seed is kept, whatever seed [rules] has: a reshuffle is [Reseed], never a side effect.
     */
    data class ReplaceCustom(
        override val id: ChannelId,
        val rules: ChannelRules,
        val number: Int,
        val swapWith: ChannelId? = null,
    ) : LineupEdit

    /** Changes only the stored seed of a custom channel (Reshuffle). */
    data class Reseed(
        override val id: ChannelId,
        val seed: Seed,
    ) : LineupEdit

    /** Removes a custom channel. */
    data class RemoveCustom(
        override val id: ChannelId,
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

    /** The edit is for a custom channel and this one is not. */
    data object NotCustom : EditRefusal

    /** A channel already has the ID of the channel to add. */
    data object IdTaken : EditRefusal

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
@Suppress("TooManyFunctions") // One edit, one function.
object LineupEdits {
    const val MIN_NUMBER = 1
    const val MAX_NUMBER = 999

    /**
     * Applies [edit] to [lineup] at [nowMs], numbering from [catalog]'s recipes and retired numbers. Bands and opt-in
     * come from [catalog]; whether a channel is a kid channel does not: [KidStatus] reads the global
     * `StarterCatalog.EVER_KID_SAFE_KEYS`, whatever [catalog] is.
     */
    fun apply(
        lineup: Lineup,
        edit: LineupEdit,
        nowMs: Long,
        catalog: StarterCatalogView = StarterCatalog,
    ): EditOutcome {
        val channel = lineup.channels.firstOrNull { it.id == edit.id }
        return when {
            edit is LineupEdit.AddCustom -> addCustom(lineup, edit, nowMs, catalog)
            channel == null -> refused(EditRefusal.UnknownChannel)
            else -> applyTo(lineup, channel, edit, nowMs, catalog)
        }
    }

    private fun applyTo(
        lineup: Lineup,
        channel: LineupChannel,
        edit: LineupEdit,
        nowMs: Long,
        catalog: StarterCatalogView,
    ): EditOutcome =
        when (edit) {
            is LineupEdit.AddCustom -> {
                addCustom(lineup, edit, nowMs, catalog)
            }

            is LineupEdit.ReplaceCustom -> {
                replaceCustom(lineup, channel, edit, catalog)
            }

            is LineupEdit.Reseed -> {
                reseed(lineup, channel, edit.seed)
            }

            is LineupEdit.RemoveCustom -> {
                removeCustom(lineup, channel)
            }

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

    /**
     * The lowest number free for a new custom channel: in the Kids band when [kids], else in
     * [LineupDefaults.CUSTOM_NUMBERS]. Free means no channel has it as a default or an override and [catalog] has not
     * retired it. Null when there is none.
     */
    fun firstFreeCustom(
        lineup: Lineup,
        kids: Boolean,
        catalog: StarterCatalogView = StarterCatalog,
    ): Int? {
        val used = lineup.channels.flatMapTo(HashSet()) { listOfNotNull(it.defaultNumber, it.numberOverride) }
        val range = if (kids) Band.Kids.numbers else LineupDefaults.CUSTOM_NUMBERS
        return range.firstOrNull { it !in used && it !in catalog.retiredNumbers }
    }

    private fun addCustom(
        lineup: Lineup,
        edit: LineupEdit.AddCustom,
        nowMs: Long,
        catalog: StarterCatalogView,
    ): EditOutcome {
        val given = edit.number
        val holder = given?.let { n -> lineup.channels.firstOrNull { it.number == n } }
        val number = given ?: firstFreeCustom(lineup, edit.kids, catalog)
        val refusal =
            when {
                lineup.channels.any { it.id == edit.id } -> EditRefusal.IdTaken
                number == null -> EditRefusal.NoFreeNumber
                number !in MIN_NUMBER..MAX_NUMBER -> EditRefusal.OutOfRange
                holder != null -> EditRefusal.NumberTaken(holder.id)
                edit.kids && number !in Band.Kids.numbers -> EditRefusal.KidsBandOnly
                else -> null
            }
        if (refusal != null || number == null) return refused(refusal ?: EditRefusal.NoFreeNumber)
        val added = LineupChannel(edit.id, null, number, null, ChannelState.On, ChannelSource.Custom(edit.rules), nowMs)
        return EditOutcome.Applied(lineup.copy(channels = lineup.channels + added))
    }

    // The stored seed stays; the number moves by the rules of SetNumber, on a lineup that already has the new rules.
    private fun replaceCustom(
        lineup: Lineup,
        channel: LineupChannel,
        edit: LineupEdit.ReplaceCustom,
        catalog: StarterCatalogView,
    ): EditOutcome {
        val stored = (channel.source as? ChannelSource.Custom) ?: return refused(EditRefusal.NotCustom)
        val rules = edit.rules.copy(seed = stored.rules.seed)
        val changed = channel.copy(source = ChannelSource.Custom(rules))
        return setNumber(replaced(lineup, changed), changed, edit.number, edit.swapWith, catalog)
    }

    private fun reseed(
        lineup: Lineup,
        channel: LineupChannel,
        seed: Seed,
    ): EditOutcome {
        val stored = (channel.source as? ChannelSource.Custom) ?: return refused(EditRefusal.NotCustom)
        return applied(lineup, channel.copy(source = ChannelSource.Custom(stored.rules.copy(seed = seed))))
    }

    private fun removeCustom(
        lineup: Lineup,
        channel: LineupChannel,
    ): EditOutcome {
        val onCount = lineup.channels.count { it.state == ChannelState.On }
        return when {
            channel.source !is ChannelSource.Custom -> refused(EditRefusal.NotCustom)
            channel.state == ChannelState.On && onCount == 1 -> refused(EditRefusal.LastOn)
            else -> EditOutcome.Applied(lineup.copy(channels = lineup.channels.filter { it.id != channel.id }))
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
