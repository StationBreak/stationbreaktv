// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.OpenResult

/** What opening a channel for the builder came to: its draft, or the line that says why it did not open. */
internal sealed interface Opening {
    data class Draft(
        val draft: ChannelDraft,
    ) : Opening

    data class Failed(
        val message: BuilderMessage,
    ) : Opening
}

internal fun opening(
    result: OpenResult,
    draftOf: (OpenResult.Opened) -> ChannelDraft,
): Opening =
    when (result) {
        is OpenResult.Opened -> Opening.Draft(draftOf(result))
        OpenResult.NotCustomisable -> Opening.Failed(BuilderMessage.NotCustomisable)
        OpenResult.NotFound -> Opening.Failed(BuilderMessage.NotFound)
        OpenResult.Failed -> Opening.Failed(BuilderMessage.OpenFailed)
    }

/** The line for a build that did not make the channel; null for one that did. */
internal fun messageOf(result: BuildResult): BuilderMessage? =
    when (result) {
        is BuildResult.Done -> null
        BuildResult.NotEditable -> BuilderMessage.NotEditable
        BuildResult.SaveFailed -> BuilderMessage.SaveFailed
        BuildResult.Failed -> BuilderMessage.Failed
        is BuildResult.Refused -> refusalMessage(result.reason)
    }

// An invalid draft is shown on the step (BuilderUi.problem), never as a line; it cannot reach here but for a refusal
// the builder makes of a draft that was valid when it was sent.
internal fun refusalMessage(reason: BuildRefusal): BuilderMessage =
    when (reason) {
        is BuildRefusal.Invalid -> BuilderMessage.Failed
        BuildRefusal.UnknownChannel -> BuilderMessage.NotFound
        BuildRefusal.NotCustom -> BuilderMessage.NotChangeable
        BuildRefusal.NotShuffle -> BuilderMessage.NotShuffle
        BuildRefusal.NoFreeNumber -> BuilderMessage.NoFreeNumber
        BuildRefusal.KidsBandFull -> BuilderMessage.KidsBandFull
        BuildRefusal.OutOfRange -> BuilderMessage.OutOfRange
        BuildRefusal.KidsBand -> BuilderMessage.KidsBand
        BuildRefusal.LastOn -> BuilderMessage.LastOn
        is BuildRefusal.NumberTaken -> BuilderMessage.NumberTaken
    }
