// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.model.ChannelId

/** The save came back: [draft] is now the saved one, or the refusal is shown. */
internal fun BuilderModel.saved(
    draft: ChannelDraft,
    result: BuildResult,
    holderName: String,
): BuilderModel {
    val idle = copy(saving = false)
    return when (result) {
        // A new channel that was made is now a channel being edited: another Save saves it, it does not make a second.
        is BuildResult.Done -> idle.copy(opened = draft, mode = if (editing()) mode else BuilderMode.Edit(result.id))

        is BuildResult.Refused -> idle.refusedWith(draft, result.reason, holderName)

        else -> idle.copy(message = messageOf(result))
    }
}

// [draft] is the one that was sent: the viewer may have moved on while it was out.
private fun BuilderModel.refusedWith(
    draft: ChannelDraft,
    reason: BuildRefusal,
    holderName: String,
): BuilderModel =
    when {
        reason is BuildRefusal.Invalid -> copy(refused = reason.problem)
        reason is BuildRefusal.NumberTaken -> numberTaken(draft, reason, holderName)
        else -> copy(message = refusalMessage(reason))
    }

// An edit may trade numbers (Swap) where the domain allows it; a new channel cannot, so it learns who has the number.
private fun BuilderModel.numberTaken(
    draft: ChannelDraft,
    reason: BuildRefusal.NumberTaken,
    holderName: String,
): BuilderModel =
    when {
        editing() && reason.swappable -> swapAsked(draft.number ?: 0, reason.holder, holderName)
        editing() -> copy(message = BuilderMessage.NumberNotTradable)
        else -> copy(message = BuilderMessage.NumberTaken, held = held + heldBy(draft, holderName))
    }

private fun BuilderModel.swapAsked(
    number: Int,
    holder: ChannelId,
    holderName: String,
): BuilderModel =
    copy(
        confirm = BuilderConfirm.Swap(number, holderName),
        resume = focus,
        focus = BuilderFocus.Choice(1),
        swapHolder = holder,
    )

// The number the draft asked for, with the name of the channel that has it.
private fun heldBy(
    draft: ChannelDraft,
    holderName: String,
): Map<Int, String> = draft.number?.let { mapOf(it to holderName) }.orEmpty()
