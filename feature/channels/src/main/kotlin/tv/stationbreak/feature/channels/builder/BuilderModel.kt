// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.TitleHit
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftProblem
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.PickerEntry
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed

internal val NO_DRAFT = ChannelDraft("", emptyList(), emptyList(), DraftFilters(), kids = false, seed = Seed(0uL))

/** What one visit to the builder knows beyond what [BuilderUi] shows. The ViewModel changes it under its lock. */
@Suppress("LongParameterList") // The visit's state, one for one.
internal data class BuilderModel(
    val generation: Int = 0,
    val account: LibraryAccount? = null,
    val ready: Boolean = false,
    val mode: BuilderMode = BuilderMode.New,
    val step: BuilderStep = BuilderStep.Sources,
    val onSheet: Boolean = false,
    /** The sheet row to come back to when a page opened from it is left. */
    val sheetRow: SheetRow = SheetRow.Name,
    val draft: ChannelDraft = NO_DRAFT,
    /** The draft as opened or last saved; [BuilderUi.dirty] is the draft differing from it. */
    val opened: ChannelDraft = NO_DRAFT,
    val tab: SourceKindTab = SourceKindTab.Libraries,
    /** What each tab's read gave; a tab missing here is still being read. */
    val reads: Map<SourceKindTab, Listed<List<PickerEntry>>> = emptyMap(),
    val reading: Set<SourceKindTab> = emptySet(),
    val searchText: String = "",
    val searching: Boolean = false,
    val hits: Listed<List<TitleHit>>? = null,
    val narrowOpen: Boolean = false,
    val genreCursor: Int = 0,
    /** Ticks go to the left-out sources, not the sources. */
    val leavingOut: Boolean = false,
    val focus: BuilderFocus? = BuilderFocus.Tab(SourceKindTab.Libraries),
    /** Where the focus goes back to when the open confirm is closed. */
    val resume: BuilderFocus? = null,
    val preview: PreviewView = PreviewView.NothingFits(emptyList()),
    val barCount: Int? = null,
    val confirm: BuilderConfirm? = null,
    /** The channel a Swap trades numbers with, and whether the save it belongs to goes on to watch. */
    val swapHolder: ChannelId? = null,
    val andWatch: Boolean = false,
    val message: BuilderMessage? = null,
    val refused: DraftProblem? = null,
    val saving: Boolean = false,
)

internal fun BuilderModel.editing() = mode is BuilderMode.Edit

internal fun BuilderModel.dirty() = draft != opened

internal fun BuilderModel.sheetRows() = sheetRowsOf(draft, editing(), dirty())

private fun BuilderModel.ticks(): List<DraftSource> = if (leavingOut) draft.leaveOut else draft.sources

private fun BuilderModel.view(): PickerView {
    val ticked = ticks()
    return if (tab == SourceKindTab.Pick) {
        searchViewOf(searchText, searching, hits) { hit ->
            ticked.any { it.sameAs(sourceOf(tab, hit.id.value, hit.name, hit.kind)) }
        }
    } else {
        pickerViewOf(reads[tab]) { entry -> ticked.any { it.sameAs(sourceOf(tab, entry.key, entry.label)) } }
    }
}

internal fun BuilderModel.entryKeys(): List<String> = (view() as? PickerView.Entries)?.rows.orEmpty().map { it.key }

internal fun BuilderModel.nextEnabled(): Boolean =
    when (step) {
        BuilderStep.Sources -> draft.sources.isNotEmpty()
        BuilderStep.Play -> true
        BuilderStep.Name -> draft.problem() == null
    }

internal fun BuilderModel.navView() = NavView(step, tab, entryKeys(), narrowOpen, playRowsOf(draft), sheetRows())

// Keeps the focus on something that exists: an entry that went (a new search) and a sheet row that went.
internal fun BuilderModel.mended(): BuilderFocus? {
    val at = focus
    return when {
        at is BuilderFocus.Entry && at.key !in entryKeys() -> {
            if (tab == SourceKindTab.Pick) BuilderFocus.Search else BuilderFocus.Tab(tab)
        }

        at is BuilderFocus.Sheet && at.row !in sheetRows() -> {
            BuilderFocus.Sheet(SheetRow.Name)
        }

        else -> {
            at
        }
    }
}

internal fun BuilderModel.toUi(): BuilderUi {
    val narrow =
        if (narrowOpen) {
            NarrowView(
                genres = pickerViewOf(reads[SourceKindTab.Genres]) { it.key in draft.filters.genres },
                genreCursor = genreCursor,
                leavingOut = leavingOut,
            )
        } else {
            null
        }
    return BuilderUi(
        mode = mode,
        step = step,
        draft = draft,
        tab = tab,
        entries = view(),
        searchText = searchText,
        focus = focus,
        preview = preview,
        barCount = barCount,
        sheetRows = sheetRows(),
        confirm = confirm,
        message = message,
        saving = saving,
        dirty = dirty(),
        nextEnabled = nextEnabled(),
        ready = ready,
        onSheet = onSheet,
        problem = refused ?: draft.problem(),
        narrow = narrow,
    )
}
