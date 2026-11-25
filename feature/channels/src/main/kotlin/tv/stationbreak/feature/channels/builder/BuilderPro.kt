// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

/**
 * The Pro lines of the builder with a way on (#1146). A line that says something is part of Pro, where the store is
 * available, becomes a [BuilderConfirm.Pro]: the keys are its two choices (See Pro, OK) the way a question's are, so
 * Select, Back and the D-pad keep every meaning they have elsewhere in the builder, and the focus moves to See Pro.
 * Back is OK. The focus before it came is kept in [BuilderModel.resume] and comes back when the line goes, whichever
 * way: OK, Back, or Pro bought meanwhile. Without a store the line stays text and the focus stays where it was.
 */
internal fun BuilderMessage.isProLine(): Boolean = this == BuilderMessage.NeedsPro || this == BuilderMessage.CustomLimit

/**
 * The model after [this] was reached from [old] with the store [offered] or not: a Pro line that just arrived becomes
 * the Pro question with the focus on See Pro, unless a question is already open (the line then stays as text).
 */
internal fun BuilderModel.proNoted(
    old: BuilderModel,
    offered: Boolean,
): BuilderModel {
    val line = message?.takeIf { offered && it.isProLine() && it != old.message && confirm == null }
    return if (line != null) {
        copy(message = null, confirm = BuilderConfirm.Pro(line), resume = focus, focus = BuilderFocus.Choice(0))
    } else {
        this
    }
}

/** Whether the keys do anything yet: the channel is open, or a Pro line is up (an open that failed has no channel). */
internal fun BuilderModel.answers(): Boolean = ready || confirm is BuilderConfirm.Pro
