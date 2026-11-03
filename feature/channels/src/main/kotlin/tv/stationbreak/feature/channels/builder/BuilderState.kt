// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.DraftProblem
import tv.stationbreak.core.domain.lineup.builder.PreviewItem
import tv.stationbreak.core.domain.lineup.builder.SkippedPick
import tv.stationbreak.core.model.ChannelId

/** The three pages of building a channel. */
internal enum class BuilderStep { Sources, Play, Name }

/** The lists a source is picked from. */
internal enum class SourceKindTab { Libraries, Collections, Tags, Genres, Pick }

/** What the builder was opened for. */
internal sealed interface BuilderMode {
    data object New : BuilderMode

    /** A new channel started from the built-in channel [from]. */
    data class Copy(
        val from: ChannelId,
    ) : BuilderMode

    /** The custom channel [id], changed in place. */
    data class Edit(
        val id: ChannelId,
    ) : BuilderMode
}

internal enum class NarrowRowKind { Genres, Years, Runtime, Only, Rating, LeaveOut }

internal enum class PlayRowKind { Order, SeriesCap, FilmCap, Kids, Kept }

internal enum class NameRowKind { Name, Number, Save }

/** The rows of the edit sheet, in the order they are listed. */
internal enum class SheetRow {
    Name,
    Sources,
    NarrowDown,
    PlayOrder,
    SeriesCap,
    FilmCap,
    Kids,
    Number,
    Kept,
    Preview,
    Save,
    Reshuffle,
    Delete,
}

/** Where the D-pad is, by identity (a value, as ChannelsFocus is). */
internal sealed interface BuilderFocus {
    data class Tab(
        val tab: SourceKindTab,
    ) : BuilderFocus

    data class Entry(
        val key: String,
    ) : BuilderFocus

    data object Narrow : BuilderFocus

    data class NarrowRow(
        val row: NarrowRowKind,
    ) : BuilderFocus

    data object Next : BuilderFocus

    data object Search : BuilderFocus

    data class PlayRow(
        val row: PlayRowKind,
    ) : BuilderFocus

    data class NameRow(
        val row: NameRowKind,
    ) : BuilderFocus

    data class Sheet(
        val row: SheetRow,
    ) : BuilderFocus

    /** Inside a confirm. */
    data class Choice(
        val index: Int,
    ) : BuilderFocus
}

/** One row of a picker; [live] marks a source that follows the library (all but a picked film). */
internal data class PickerRow(
    val key: String,
    val label: String,
    val live: Boolean,
    val ticked: Boolean,
)

/** What a picker shows: Loading, Empty (nothing synced), Unavailable (the read failed) or the entries and ticks. */
internal sealed interface PickerView {
    data object Loading : PickerView

    data object Empty : PickerView

    data object Unavailable : PickerView

    data class Entries(
        val rows: List<PickerRow>,
    ) : PickerView
}

/** The "Narrow down" rows' own state: the genres to tick and where the cursor is, and whether ticks leave out. */
internal data class NarrowView(
    val genres: PickerView,
    val genreCursor: Int,
    val leavingOut: Boolean,
)

internal sealed interface PreviewView {
    data object Working : PreviewView

    data class Ready(
        val next: List<PreviewItem>,
        val itemCount: Int,
        val minutes: Long,
        val skipped: List<SkippedPick>,
    ) : PreviewView

    data class NothingFits(
        val skipped: List<SkippedPick>,
    ) : PreviewView

    /** The preview could not be worked out; saving still works. */
    data object Unavailable : PreviewView
}

/** A question over the builder; its choices are the action, then the keep (focus [BuilderFocus.Choice]). */
internal sealed interface BuilderConfirm {
    data object Leave : BuilderConfirm

    data object KidsOff : BuilderConfirm

    data class Delete(
        val name: String,
    ) : BuilderConfirm

    /** [draft] is the one that was refused: confirming sends that one, whatever happened since. */
    data class Swap(
        val number: Int,
        val holderName: String,
        val draft: ChannelDraft,
    ) : BuilderConfirm
}

/** A line the screen shows after something did not happen. An invalid draft is [BuilderUi.problem], not a message. */
internal enum class BuilderMessage {
    KidsBandFull,
    KidsBand,
    NoFreeNumber,
    OutOfRange,
    LastOn,

    /** The number is held by another channel and a new channel cannot trade for it: pick another. */
    NumberTaken,

    /** The number is held by a channel an edit cannot trade numbers with. */
    NumberNotTradable,
    SaveFailed,
    Failed,
    NotEditable,
    NotCustomisable,
    NotFound,
    OpenFailed,

    /** Not a failure: a Reshuffle saved its new seed. */
    Reshuffled,
}

/** One-shot effects, collected by the destination (as the guide's event channel is). */
internal sealed interface BuilderEvent {
    data class Saved(
        val id: ChannelId,
        val andWatch: Boolean,
    ) : BuilderEvent

    data object Closed : BuilderEvent
}

/** What the builder screens draw. */
@Suppress("LongParameterList") // The screens' state, one for one.
internal data class BuilderUi(
    val mode: BuilderMode,
    val step: BuilderStep,
    val draft: ChannelDraft,
    val tab: SourceKindTab,
    val entries: PickerView,
    val searchText: String,
    val focus: BuilderFocus?,
    val preview: PreviewView,
    /** The pool's size for the bar on the Sources page; null while it is worked out or cannot be. */
    val barCount: Int?,
    /**
     * The sheet's rows in order: caps hidden for In order, Kept while a kept setting is in force, Reshuffle only
     * for Shuffle with no unsaved changes.
     */
    val sheetRows: List<SheetRow>,
    val confirm: BuilderConfirm?,
    val message: BuilderMessage?,
    val saving: Boolean,
    val dirty: Boolean,
    val nextEnabled: Boolean,
    /** Opening found the channel (a failed open leaves this false, with a message). */
    val ready: Boolean = false,
    /** An edit shows the sheet; its rows open the pages and Back returns to it. */
    val onSheet: Boolean = false,
    /** Why the draft cannot be saved yet, or null. */
    val problem: DraftProblem? = null,
    /** The narrow-down rows while they are open. */
    val narrow: NarrowView? = null,
    /** The name of the channel that holds the draft's number, when a new channel found it taken. */
    val numberHolder: String? = null,
    /** A new channel was made; the builder takes no more saves until it is opened again. */
    val saved: Boolean = false,
    /** The step the shown [problem] belongs to, so a Save refused on another page can say where to fix it. */
    val problemStep: BuilderStep? = null,
    /** The name the draft was opened with (blank for a new channel): what heads the sheet while the name is cleared. */
    val storedName: String = "",
)
