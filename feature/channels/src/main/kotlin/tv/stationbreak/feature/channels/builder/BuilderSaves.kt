// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft

/** The save came back: [draft] is now the saved one, or the refusal is shown. */
internal fun BuilderModel.saved(
    draft: ChannelDraft,
    result: BuildResult,
    holderName: String,
    free: List<Int>? = null,
): BuilderModel {
    val idle = copy(saving = false, free = free ?: this.free)
    return when (result) {
        // A new channel is made once: the builder takes no more saves until it is opened again.
        is BuildResult.Done -> idle.copy(opened = draft, saved = !editing())

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
        reason is BuildRefusal.NumberTaken -> numberTaken(draft, holderName)
        else -> copy(message = refusalMessage(reason))
    }

// The builder never trades numbers (Change number on the Channels screen does): a number taken in the meantime says
// who has it, and the viewer picks another.
private fun BuilderModel.numberTaken(
    draft: ChannelDraft,
    holderName: String,
): BuilderModel = copy(message = BuilderMessage.NumberTaken, held = held + heldBy(draft, holderName))

// The number the draft asked for, with the name of the channel that has it.
private fun heldBy(
    draft: ChannelDraft,
    holderName: String,
): Map<Int, String> = draft.number?.let { mapOf(it to holderName) }.orEmpty()
