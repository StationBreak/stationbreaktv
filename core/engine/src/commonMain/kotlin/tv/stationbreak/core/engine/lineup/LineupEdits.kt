// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.ChannelRules

/** One change the user makes to a lineup's channels: on or off, a number, or a custom channel added or edited. */
sealed interface LineupEdit {
    /** An edit of one channel. */
    sealed interface ChannelEdit : LineupEdit {
        /** The channel the edit is for. */
        val id: ChannelId
    }

    /**
     * "Use my collections" on or off (spec 23). A lineup-level edit, not a channel's: it marks no channel as chosen by
     * the viewer. The change starts at the next broadcast-day start (`withBasisFor`).
     */
    data class UseMyCollections(
        val on: Boolean,
    ) : LineupEdit

    /** [confirmedOptIn] must be true to switch on an opt-in (adult) starter. */
    data class SwitchOn(
        override val id: ChannelId,
        val confirmedOptIn: Boolean = false,
    ) : ChannelEdit

    /** Hides the channel. */
    data class SwitchOff(
        override val id: ChannelId,
    ) : ChannelEdit

    /** Moves [id] to [number]; with [swapWith], that channel, which must hold [number], takes [id]'s old number. */
    data class SetNumber(
        override val id: ChannelId,
        val number: Int,
        val swapWith: ChannelId? = null,
    ) : ChannelEdit

    /** Back to the default number; with [swapWith], that channel, which must hold it, takes [id]'s current number. */
    data class ClearNumber(
        override val id: ChannelId,
        val swapWith: ChannelId? = null,
    ) : ChannelEdit

    /**
     * Adds a custom channel, switched on. [number] null means "the first free one", worked out inside
     * [LineupEdits.apply] (so inside the caller's lock): from 600 for grown-up, the Kids band (1..33) when [kids]. A
     * given number decides alone whether the channel is a kid channel (the Kids band, 1..33); [kids] with a number
     * outside that band is refused. A given number may be one the catalog retired; only the automatic pick skips those.
     * [customLimit] is the most channels the viewer made ([viewerCustomCount]) the lineup may hold: one that already
     * holds that many refuses the add as [EditRefusal.CustomLimit], checked inside the caller's lock. Null is no limit.
     * The engine only enforces the number it is given; what that number is belongs to the caller.
     */
    data class AddCustom(
        override val id: ChannelId,
        val rules: ChannelRules,
        val number: Int?,
        val kids: Boolean,
        val customLimit: Int?,
    ) : ChannelEdit

    /**
     * Replaces a custom channel's rules and number; with [swapWith], that channel, which holds [number], takes the old
     * number. The stored seed is kept, whatever seed [rules] has: a reshuffle is [Reseed], never a side effect. The
     * channel itself may move into the Kids band, 1..33 (it becomes a kid channel by number) or out of it; a swap that
     * would send another, grown-up channel into the Kids band is refused. [number] null means the first free one in the
     * Kids band when [kids], else in the custom band, worked out inside [LineupEdits.apply] like [AddCustom]'s; a
     * channel that already has a number in the requested side (the Kids band, or anywhere outside it) keeps that
     * number. A swap never changes the holder's kid status, so one that would take a kid holder out of the band is
     * refused.
     */
    data class ReplaceCustom(
        override val id: ChannelId,
        val rules: ChannelRules,
        val number: Int?,
        val swapWith: ChannelId? = null,
        val kids: Boolean,
    ) : ChannelEdit

    /** Changes only the stored seed of a custom channel (Reshuffle). */
    data class Reseed(
        override val id: ChannelId,
        val seed: Seed,
    ) : ChannelEdit

    /**
     * Removes a custom channel. A custom channel this app cannot read (its rules need a newer app, stored as
     * [ChannelSource.Unusable]) is refused as [EditRefusal.NotCustom]: data this app cannot read is not deleted by it.
     */
    data class RemoveCustom(
        override val id: ChannelId,
    ) : ChannelEdit
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

    /**
     * The number is outside [LineupEdits.MIN_NUMBER]..[LineupEdits.MAX_NUMBER], or it is below
     * [KidStatus.AFTER_HOURS_FLOOR] for After Hours (#1078: the young bands promise their audience by number).
     */
    data object OutOfRange : EditRefusal

    /** The edit is for a custom channel and this one is not. */
    data object NotCustom : EditRefusal

    /** A reseed is for a channel that plays in a shuffle, and this one does not. */
    data object NotShuffle : EditRefusal

    /** A channel already has the ID of the channel to add. */
    data object IdTaken : EditRefusal

    /** The Kids band (1 to 33) is for kid channels, and a kid channel stays in it. */
    data object KidsBandOnly : EditRefusal

    /** The lineup already holds as many custom channels as [LineupEdit.AddCustom.customLimit] allows. */
    data object CustomLimit : EditRefusal

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
     *
     * An edit that changes the curated on-set (a starter switched on or off) is recorded in the lineup's basis, so the
     * days before [boundaryMs], the next broadcast-day start, keep the old one (`withBasisFor`).
     */
    fun apply(
        lineup: Lineup,
        edit: LineupEdit,
        nowMs: Long,
        boundaryMs: Long,
        catalog: StarterCatalogView = StarterCatalog,
    ): EditOutcome {
        val outcome =
            when (edit) {
                is LineupEdit.UseMyCollections -> EditOutcome.Applied(lineup.copy(useMyCollections = edit.on))
                is LineupEdit.ChannelEdit -> applyChannelEdit(lineup, edit, nowMs, boundaryMs, catalog)
            }
        return if (outcome is EditOutcome.Applied) {
            val keys = catalog.recipes.mapTo(HashSet()) { it.key }
            EditOutcome.Applied(lineup.withBasisFor(outcome.lineup, nowMs, boundaryMs, keys))
        } else {
            outcome
        }
    }

    private fun applyChannelEdit(
        lineup: Lineup,
        edit: LineupEdit.ChannelEdit,
        nowMs: Long,
        boundaryMs: Long,
        catalog: StarterCatalogView,
    ): EditOutcome {
        val channel = lineup.channels.firstOrNull { it.id == edit.id }
        return when {
            edit is LineupEdit.AddCustom -> {
                addCustom(lineup, edit, nowMs, catalog)
            }

            channel == null -> {
                refused(EditRefusal.UnknownChannel)
            }

            else -> {
                val outcome = applyTo(lineup, channel, edit, nowMs, boundaryMs, catalog)
                markChoice(lineup, edit, outcome, nowMs, boundaryMs, catalog)
            }
        }
    }

    // A viewer's switch or number marks the edited channel as chosen, and any channel the edit moved (a swap holder); a
    // marked channel on the air stops leaving.
    // A ReplaceCustom marks only the channel it swapped with: the edited one is the viewer's own anyway.
    // A mark only goes up (None, Touched, Added): the edited channel gets the mark its own edit earns ([ownChoice]),
    // a moved holder is Touched, and neither lowers a mark the channel already has.
    @Suppress("LongParameterList") // The edit, its outcome, the time, the boundary and the catalog.
    private fun markChoice(
        before: Lineup,
        edit: LineupEdit.ChannelEdit,
        outcome: EditOutcome,
        nowMs: Long,
        boundaryMs: Long,
        catalog: StarterCatalogView,
    ): EditOutcome {
        val marksSelf = marksItself(edit)
        val viewerEdit = marksSelf || (edit is LineupEdit.ReplaceCustom && edit.swapWith != null)
        // An edit that changed nothing is not a choice.
        if (!viewerEdit || outcome !is EditOutcome.Applied || outcome.lineup == before) return outcome
        val old = before.channels.associateBy { it.id }
        val marked =
            outcome.lineup.channels.map {
                val was = old[it.id]
                val moved = was != null && it.id != edit.id && (was.number != it.number || was.state != it.state)
                when {
                    marksSelf && it.id == edit.id && was != null -> {
                        marked(it, ownChoice(edit, was, nowMs, boundaryMs, catalog))
                    }

                    moved -> {
                        marked(it, ViewerChoice.Touched)
                    }

                    else -> {
                        it
                    }
                }
            }
        return EditOutcome.Applied(outcome.lineup.copy(channels = marked))
    }

    // [channel] with the mark raised to [earned] (never lowered); a channel on the air stops leaving: the viewer's
    // choice beats the planner's.
    private fun marked(
        channel: LineupChannel,
        earned: ViewerChoice,
    ): LineupChannel {
        val choice = maxOf(channel.viewerChoice, earned)
        return if (channel.state == ChannelState.On) {
            channel.copy(viewerChoice = choice, leavesAtMs = null, shelfReason = null)
        } else {
            channel.copy(viewerChoice = choice)
        }
    }

    // The mark the viewer's own edit earns the edited channel [was] (before the edit), to be raised to, never lowered
    // to. Only an unedited starter can be Added; anything else, an opt-in starter (the planner never picks one, so it
    // is already unranked), a renumber and a swap are Touched.
    @Suppress("LongParameterList") // The edit, the channel, the time, the boundary and the catalog.
    private fun ownChoice(
        edit: LineupEdit.ChannelEdit,
        was: LineupChannel,
        nowMs: Long,
        boundaryMs: Long,
        catalog: StarterCatalogView,
    ): ViewerChoice {
        val adds = was.source.canHoldAddedMark() && !isOptIn(was, catalog)
        return when {
            !adds -> {
                ViewerChoice.Touched
            }

            // A leaving pick the viewer switches off is decided here: it holds Added while hidden, so the switch-on of
            // the same day, an undo, restores Added (undoing the switch-off of a leaving pick equals keeping it, which
            // is an addition); `Lineup.rolled` lowers it to Touched at the boundary.
            edit is LineupEdit.SwitchOff -> {
                if (leavingPick(was)) ViewerChoice.Added else ViewerChoice.Touched
            }

            edit !is LineupEdit.SwitchOn -> {
                ViewerChoice.Touched
            }

            isUndo(was, nowMs, boundaryMs) -> {
                ViewerChoice.Touched
            }

            // A planner pick already chosen for the boundary and shown as starting: it was never outside the room.
            was.state == ChannelState.On && was.startsAfter(LineupDay.endingAt(boundaryMs)) -> {
                ViewerChoice.Touched
            }

            // A leaving pick kept, or a shelf, hidden, kept or numberless starter: a channel the planner had not
            // picked, so it adds one.
            else -> {
                ViewerChoice.Added
            }
        }
    }

    // An unedited starter on the air that the planner has set to leave at the boundary.
    private fun leavingPick(channel: LineupChannel): Boolean {
        val leaves = channel.leavesAtMs != null
        return channel.state == ChannelState.On && leaves
    }

    // A switch-on that takes back the viewer's own switch-off, before the boundary. Either the starter was on the air
    // today and the switch-off recorded that (`hidden` leaves it hidden with `leavesAtMs` at the coming boundary, still
    // ahead now; this does not depend on the curation basis, which only a curated starter has), or it is a shelf
    // starter whose start is still ahead (a pick waiting for the boundary that the viewer switched off; the planner's
    // own unpick stamps the plan's time, never one ahead) with a mark. It restores the channel with the mark it had,
    // so an accidental off and on of a pick of the planner's does not grow the lineup. For a leaving pick the
    // switch-off gave Added, so the undo equals keeping it: an addition. A planner unpick written by an older build,
    // with its time still ahead, then renumbered (so marked), reads as an undo too, so as Touched, until the boundary.
    private fun isUndo(
        was: LineupChannel,
        nowMs: Long,
        boundaryMs: Long,
    ): Boolean {
        val offToday = was.state != ChannelState.On && was.leavesAtMs.let { it != null && it > nowMs }
        val waiting =
            was.state == ChannelState.Shelf &&
                was.startsAfter(LineupDay.endingAt(boundaryMs)) &&
                was.viewerChoice != ViewerChoice.None
        return offToday || waiting
    }

    private fun marksItself(edit: LineupEdit.ChannelEdit): Boolean =
        edit is LineupEdit.SwitchOn ||
            edit is LineupEdit.SwitchOff ||
            edit is LineupEdit.SetNumber ||
            edit is LineupEdit.ClearNumber

    @Suppress("LongParameterList") // The edit, its lineup and channel, the time, the boundary and the catalog.
    private fun applyTo(
        lineup: Lineup,
        channel: LineupChannel,
        edit: LineupEdit.ChannelEdit,
        nowMs: Long,
        boundaryMs: Long,
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
                switchOn(lineup, channel, edit.confirmedOptIn, nowMs, boundaryMs, catalog)
            }

            is LineupEdit.SwitchOff -> {
                switchOff(lineup, channel, boundaryMs)
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
     * The lowest number free for a new custom channel: the head of [freeCustomNumbers]. Null when there is none.
     */
    fun firstFreeCustom(
        lineup: Lineup,
        kids: Boolean,
        catalog: StarterCatalogView = StarterCatalog,
    ): Int? = freeCustomNumbers(lineup, kids, catalog).firstOrNull()

    /**
     * Every number free for a new custom channel, lowest first: in the Kids band when [kids], else in
     * [LineupDefaults.CUSTOM_NUMBERS]. Free means no channel has it as a default or an override and [catalog] has not
     * retired it.
     */
    fun freeCustomNumbers(
        lineup: Lineup,
        kids: Boolean,
        catalog: StarterCatalogView = StarterCatalog,
    ): List<Int> {
        val used = lineup.channels.flatMapTo(HashSet()) { listOfNotNull(it.defaultNumber, it.numberOverride) }
        val range = if (kids) Band.Kids.numbers else LineupDefaults.CUSTOM_NUMBERS
        return range.filter { it !in used && it !in catalog.retiredNumbers }
    }

    private fun addCustom(
        lineup: Lineup,
        edit: LineupEdit.AddCustom,
        nowMs: Long,
        catalog: StarterCatalogView,
    ): EditOutcome {
        val number = edit.number ?: firstFreeCustom(lineup, edit.kids, catalog)
        return when {
            lineup.channels.any { it.id == edit.id } -> {
                refused(EditRefusal.IdTaken)
            }

            edit.customLimit != null && lineup.viewerCustomCount() >= edit.customLimit -> {
                refused(EditRefusal.CustomLimit)
            }

            number == null -> {
                refused(EditRefusal.NoFreeNumber)
            }

            else -> {
                val refusal = newNumberRefusal(lineup, number, edit.kids)
                val channel =
                    LineupChannel(edit.id, null, number, null, ChannelState.On, ChannelSource.Custom(edit.rules), nowMs)
                if (refusal != null) {
                    refused(refusal)
                } else {
                    EditOutcome.Applied(lineup.copy(channels = lineup.channels + channel))
                }
            }
        }
    }

    // Why [number] cannot be a new custom channel's: out of range, held, or not a kids number for a kids request.
    private fun newNumberRefusal(
        lineup: Lineup,
        number: Int,
        kids: Boolean,
    ): EditRefusal? {
        val holder = lineup.channels.firstOrNull { it.number == number }
        return when {
            number !in MIN_NUMBER..MAX_NUMBER -> EditRefusal.OutOfRange
            holder != null -> EditRefusal.NumberTaken(holder.id)
            kids && number !in Band.Kids.numbers -> EditRefusal.KidsBandOnly
            else -> null
        }
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
        val number = edit.number ?: keptOrFirstFree(lineup, channel, edit.kids, catalog)
        return when {
            number == null -> refused(EditRefusal.NoFreeNumber)
            edit.kids && number !in Band.Kids.numbers -> refused(EditRefusal.KidsBandOnly)
            else -> setNumber(replaced(lineup, changed), changed, number, edit.swapWith, catalog, ownKidsMove = true)
        }
    }

    // The channel's own number when it is already in the requested band, else the first free one of that band.
    private fun keptOrFirstFree(
        lineup: Lineup,
        channel: LineupChannel,
        kids: Boolean,
        catalog: StarterCatalogView,
    ): Int? {
        val current = channel.number
        val inBand = current != null && (current in Band.Kids.numbers) == kids
        return if (inBand) current else firstFreeCustom(lineup, kids, catalog)
    }

    private fun reseed(
        lineup: Lineup,
        channel: LineupChannel,
        seed: Seed,
    ): EditOutcome {
        val stored = (channel.source as? ChannelSource.Custom)
        return when {
            stored == null -> refused(EditRefusal.NotCustom)
            !shuffles(stored.rules) -> refused(EditRefusal.NotShuffle)
            else -> applied(lineup, channel.copy(source = ChannelSource.Custom(stored.rules.copy(seed = seed))))
        }
    }

    // Only a shuffle takes a new seed to a new order: rotation, in order and a copied built-in's own orders do not.
    private fun shuffles(rules: ChannelRules): Boolean = rules.orderingMode == OrderingMode.Shuffle

    private fun removeCustom(
        lineup: Lineup,
        channel: LineupChannel,
    ): EditOutcome {
        val onCount = lineup.channels.count { it.state == ChannelState.On }
        return when {
            channel.source !is ChannelSource.Custom -> refused(EditRefusal.NotCustom)
            channel.state == ChannelState.On && onCount == 1 -> refused(EditRefusal.LastOn)
            else -> EditOutcome.Applied(without(lineup, channel.id))
        }
    }

    // The lineup without the channel [id]; the curation basis forgets it too.
    private fun without(
        lineup: Lineup,
        id: ChannelId,
    ): Lineup {
        val basis = lineup.curationBasis
        return lineup.copy(
            channels = lineup.channels.filter { it.id != id },
            curationBasis = basis?.copy(on = basis.on - id),
        )
    }

    private fun refused(refusal: EditRefusal): EditOutcome = EditOutcome.Refused(refusal)

    private fun applied(
        lineup: Lineup,
        channel: LineupChannel,
    ): EditOutcome = EditOutcome.Applied(replaced(lineup, channel))

    @Suppress("LongParameterList") // The edit's channel and confirmation, the time, the boundary and the catalog.
    private fun switchOn(
        lineup: Lineup,
        channel: LineupChannel,
        confirmedOptIn: Boolean,
        nowMs: Long,
        boundaryMs: Long,
        catalog: StarterCatalogView,
    ): EditOutcome =
        when {
            // Already on but leaving: switching it on keeps it (the viewer's choice beats the planner's).
            channel.state == ChannelState.On && !channel.startsAfter(LineupDay.endingAt(boundaryMs)) -> {
                applied(lineup, channel.copy(leavesAtMs = null, shelfReason = null))
            }

            channel.source is ChannelSource.Unusable -> {
                refused(EditRefusal.NotSwitchable)
            }

            // Checked before anything goes on the air now, a channel starting at the boundary included: the consent
            // gate is never passed by.
            !confirmedOptIn && isOptIn(channel, catalog) -> {
                refused(EditRefusal.OptInNotConfirmed)
            }

            // Switched on by the planner to start at the boundary: the viewer pressing On expects it on now, so it
            // airs from now, as any mid-day switch-on does (its whole pool until the boundary: its own titles and
            // the basis's homes as guests).
            channel.state == ChannelState.On -> {
                applied(lineup, channel.copy(onSinceMs = nowMs, leavesAtMs = null, shelfReason = null))
            }

            else -> {
                switchedOn(lineup, channel, nowMs, catalog)
            }
        }

    // A channel without a number gets one first, as the user's (an override), so its default stays empty.
    private fun switchedOn(
        lineup: Lineup,
        channel: LineupChannel,
        nowMs: Long,
        catalog: StarterCatalogView,
    ): EditOutcome {
        val on = channel.copy(state = ChannelState.On, onSinceMs = nowMs, leavesAtMs = null, shelfReason = null)
        val kid = KidStatus.isKidChannel(channel)
        val number = channel.number ?: FreeNumbers.lowest(lineup.channels, channel, kid, catalog)
        return when {
            number == null -> refused(EditRefusal.NoFreeNumber)
            channel.number == null -> applied(lineup, on.copy(numberOverride = number))
            else -> applied(lineup, on)
        }
    }

    // Hidden by the viewer: it no longer sits on the shelf for a reason. A starter that was on the air is hidden from
    // the boundary: it keeps a leave time at it, which is how a switch-on before then is told to be an undo, and which
    // `Lineup.rolled` clears when the boundary passes.
    private fun hidden(
        lineup: Lineup,
        channel: LineupChannel,
        boundaryMs: Long,
    ): EditOutcome {
        val wasOn = channel.state == ChannelState.On && channel.source is ChannelSource.Starter
        val leaves = if (wasOn) boundaryMs else null
        val hidden = channel.copy(state = ChannelState.UserHidden, leavesAtMs = leaves, shelfReason = null)
        return applied(lineup, hidden)
    }

    // Today is the day that ends at [boundaryMs] ([LineupDay.endingAt]).
    private fun switchOff(
        lineup: Lineup,
        channel: LineupChannel,
        boundaryMs: Long,
    ): EditOutcome {
        val onCount = lineup.channels.count { it.state == ChannelState.On }
        return when {
            channel.state == ChannelState.UserHidden -> {
                EditOutcome.Applied(lineup)
            }

            // Switched on by the planner to start at the boundary: it never aired, so it goes back on the shelf.
            // Checked before the last-on rule: it is not on the air yet, so switching it off takes nothing off it.
            channel.state == ChannelState.On && channel.startsAfter(LineupDay.endingAt(boundaryMs)) -> {
                applied(lineup, channel.copy(state = ChannelState.Shelf, leavesAtMs = null, shelfReason = null))
            }

            channel.state == ChannelState.On && onCount == 1 -> {
                refused(EditRefusal.LastOn)
            }

            else -> {
                hidden(lineup, channel, boundaryMs)
            }
        }
    }

    @Suppress("LongParameterList") // The edit's own fields, one for one.
    private fun setNumber(
        lineup: Lineup,
        channel: LineupChannel,
        number: Int,
        swapWith: ChannelId?,
        catalog: StarterCatalogView,
        ownKidsMove: Boolean = false,
    ): EditOutcome {
        val holder = lineup.channels.firstOrNull { it.id != channel.id && it.number == number }
        val refusal = numberRefusal(channel, number, holder, swapWith, ownKidsMove)
        return when {
            refusal != null -> refused(refusal)
            channel.number == number -> EditOutcome.Applied(lineup)
            holder == null -> applied(lineup, channel.withNumber(number))
            else -> swapped(lineup, channel, holder, number, catalog)
        }
    }

    // Why [channel] cannot take [number], held by [holder] (or free). Kids off is the owner's explicit switch
    // (ReplaceCustom, [ownKidsMove]), never a renumber, and only a kid channel moves into the Kids band.
    private fun numberRefusal(
        channel: LineupChannel,
        number: Int,
        holder: LineupChannel?,
        swapWith: ChannelId?,
        ownKidsMove: Boolean,
    ): EditRefusal? =
        when {
            number !in MIN_NUMBER..MAX_NUMBER -> EditRefusal.OutOfRange
            number < KidStatus.AFTER_HOURS_FLOOR && isAfterHoursStarter(channel) -> EditRefusal.OutOfRange
            sendsAfterHoursDown(channel, holder, swapWith) -> EditRefusal.OutOfRange
            ownKidsMove -> holder?.takeIf { swapWith != it.id }?.let { EditRefusal.NumberTaken(it.id) }
            number in Band.Kids.numbers && !KidStatus.isKidChannel(channel) -> EditRefusal.KidsBandOnly
            leavesKids(channel, number) -> EditRefusal.KidsBandOnly
            holder != null && swapWith != holder.id -> EditRefusal.NumberTaken(holder.id)
            else -> null
        }

    // Whether a swap would give After Hours, the [holder], the edited [channel]'s number below 100 (#1078).
    private fun sendsAfterHoursDown(
        channel: LineupChannel,
        holder: LineupChannel?,
        swapWith: ChannelId?,
    ): Boolean {
        val old = channel.number ?: return false
        val swapsWithHolder = holder != null && swapWith == holder.id
        return swapsWithHolder && old < KidStatus.AFTER_HOURS_FLOOR && isAfterHoursStarter(checkNotNull(holder))
    }

    // Whether [channel], a kid channel now, would not be one at [number] (a kid key stays kid at any number).
    private fun leavesKids(
        channel: LineupChannel,
        number: Int,
    ): Boolean = KidStatus.isKidChannel(channel) && !KidStatus.isKidChannel(channel.withNumber(number))

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
        val taken = holderNumber?.let { holder.withNumber(it) }
        return when {
            taken == null -> refused(EditRefusal.NoFreeNumber)

            // A swap never changes the holder's kid status: a kid channel that would leave the band stays put.
            holderIsKid && !KidStatus.isKidChannel(taken) -> refused(EditRefusal.KidsBandOnly)

            else -> EditOutcome.Applied(replaced(lineup, moved, taken))
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
