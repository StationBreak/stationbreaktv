// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.ChannelPreview
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.model.ChannelId

// Every draft that is opened, or opened again, comes in through here: it is both the draft and the one to compare with.
// The builder hands over a kids channel's ceiling as one the kids hold knows, so opening it changes nothing.
private fun BuilderModel.opened(draft: ChannelDraft): BuilderModel = copy(draft = draft, opened = draft)

/** The builder's draft opened: the model of a visit the draft has arrived in. */
internal fun BuilderModel.arrived(
    account: LibraryAccount,
    draft: ChannelDraft,
): BuilderModel =
    opened(draft).copy(
        account = account,
        ready = true,
        // Whatever was open before the draft arrived is put away: a visit starts on the first tab.
        tab = SourceKindTab.Libraries,
        narrowOpen = false,
        leavingOut = false,
        genreCursor = 0,
        searchText = "",
        focus = if (onSheet) BuilderFocus.Sheet(SheetRow.Name) else BuilderFocus.Tab(SourceKindTab.Libraries),
    )

/** What the preview does not depend on is left out, so typing a name does not start the wait again. */
internal fun ChannelDraft.poolOnly() = copy(name = "", number = null, numberChosen = false)

internal fun BuilderModel.previewed(answer: ChannelPreview): BuilderModel =
    when (answer) {
        is ChannelPreview.Ready -> {
            val view = PreviewView.Ready(answer.next, answer.itemCount, answer.totalRuntimeMinutes, answer.skipped)
            copy(preview = view, barCount = answer.itemCount)
        }

        is ChannelPreview.NothingFits -> {
            copy(preview = PreviewView.NothingFits(answer.skipped), barCount = 0)
        }

        ChannelPreview.Unavailable -> {
            copy(preview = PreviewView.Unavailable, barCount = null)
        }
    }

internal fun BuilderModel.sourceFor(key: String): DraftSource? =
    if (tab == SourceKindTab.Pick) {
        (hits as? Listed.Ok)?.value?.firstOrNull { it.id.value == key }?.let { sourceOf(tab, key, it.name, it.kind) }
    } else {
        (reads[tab] as? Listed.Ok)?.value?.firstOrNull { it.key == key }?.let { sourceOf(tab, key, it.label) }
    }

internal fun BuilderModel.cursorAfter(by: Int): Int {
    val size = (reads[SourceKindTab.Genres] as? Listed.Ok)?.value?.size ?: 0
    return (genreCursor + by).coerceIn(0, (size - 1).coerceAtLeast(0))
}

// A draft without a name gets the first source's label to start from.
internal fun ChannelDraft.suggested(): ChannelDraft {
    val label =
        sources
            .firstOrNull()
            ?.label
            ?.trim()
            .orEmpty()
    if (name.isNotBlank() || label.isEmpty()) return this
    val points = minOf(label.codePointCount(0, label.length), ChannelDraft.MAX_NAME_CODE_POINTS)
    return copy(name = label.substring(0, label.offsetByCodePoints(0, points)))
}

/**
 * The kids switch went [on] or off with the number the builder found and the numbers free in the new band; with no
 * number the switch stays as it was.
 */
internal fun BuilderModel.kidsDone(
    on: Boolean,
    number: Int?,
    free: List<Int>,
): BuilderModel =
    when {
        number != null -> copy(draft = draft.withKids(on, number), free = free)
        on -> copy(message = BuilderMessage.KidsBandFull)
        else -> copy(message = BuilderMessage.NoFreeNumber)
    }

internal fun BuilderModel.reopened(opening: Opening): BuilderModel =
    when (opening) {
        is Opening.Draft -> opened(opening.draft).copy(saving = false)
        is Opening.Failed -> copy(message = opening.message, saving = false)
    }
