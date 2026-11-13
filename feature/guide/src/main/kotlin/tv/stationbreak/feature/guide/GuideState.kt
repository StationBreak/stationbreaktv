// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import androidx.compose.ui.graphics.ImageBitmap
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemDetails

/**
 * How many channel rows the guide shows at once; [GuideState.Ready.firstVisibleRow] scrolls through the rest. Five
 * 40 dp rows (1 dp apart) share a 204 dp band, which with the clock strip and the hint row fits the 5% safe area of a
 * 960 x 540 dp screen under the details panel and the picture corner (#657). The screen draws exactly this many.
 */
internal const val GUIDE_VISIBLE_ROWS = 5

/** What the guide's grid shows. The focus travels with it in a [GuideSnapshot]; the panel and the overlay are apart. */
internal sealed interface GuideState {
    /** The guide is not open. */
    data object Closed : GuideState

    /** Open, and waiting for the first days of the schedule to be built. */
    data object Loading : GuideState

    /** Open, and there is nothing to show: nobody signed in, or no channel has anything on. */
    data object Empty : GuideState

    /**
     * The grid. [viewportStartMs] is the left edge of the visible time range, [firstVisibleRow] the first row shown
     * and [nowMs] the time of the "now" line; all in the same units as [GuideModel] (milliseconds since 1970).
     */
    data class Ready(
        val model: GuideModel,
        val viewportStartMs: Long,
        val firstVisibleRow: Int,
        val nowMs: Long,
    ) : GuideState
}

/**
 * What the grid draws, as one value: the [state] and the [focus] in it, replaced together so they always belong to the
 * same moment. A [focus] always resolves with `indexOf` in the model of a [GuideState.Ready] [state] (the ViewModel
 * checks it as it publishes); it is null when the guide is not showing a grid, or no row has a cell.
 *
 * [menu] is non-null while the menu sheet is open (#756) and names the entry in it that has the focus; null while the
 * focus is on the grid. The [focus] and [onChannel] then keep where the focus was, which closing the sheet returns to,
 * but no cell is drawn focused.
 *
 * [onChannel] is true while the focus is on the channel column (#631): reached by Left from a row's earliest cell, it
 * names the [focus]'s row, whose channel cell is drawn outlined and whose cell is not drawn focused. Right returns to
 * that cell. Under an open [menu] it is kept, but the channel cell is not drawn outlined.
 *
 * [menuByBack] says how the open [menu] sheet was opened: by Back (true), where Back again leaves live TV as Back from
 * the menu row always did, or by the Menu key (false), where Back closes it. False while there is no sheet.
 *
 * [noLibraries] is true while the stored library selection is gone (spec 26): no grid is shown then, the focus is on
 * the menu row and no programme is shown. The one place the guide reads that from: a screen draws from this flag only.
 */
internal data class GuideSnapshot(
    val state: GuideState,
    val focus: GuideFocus?,
    val menu: GuideMenuEntry? = null,
    val onChannel: Boolean = false,
    val noLibraries: Boolean = false,
    val menuByBack: Boolean = false,
) {
    companion object {
        /** The guide closed: nothing shown, nothing focused. */
        val Closed = GuideSnapshot(GuideState.Closed, null)
    }
}

/** The entries of the menu sheet (#756), top to bottom. */
internal enum class GuideMenuEntry {
    /** Opens channel management. */
    Channels,

    /** Opens the guide's Dark and Light choice. Channels and Exit are unchanged. */
    Settings,

    /** Leaves live TV, as Back does from the menu row. */
    Exit,
}

/**
 * A [cell] on its channel and what the server says about its item: what the details panel shows for the focused cell.
 * [loading] is true while the details are on their way; if they never come, [item] stays null and [loading] goes
 * false. [logo] is the item's logo once it is in hand in time to replace the text title (the viewer is never shown a
 * swap later than that), else null.
 */
internal data class CellDetails(
    val cell: GuideCell,
    val channelNumber: Int,
    val channelName: String,
    val item: ItemDetails?,
    val loading: Boolean,
    val logo: ImageBitmap? = null,
)

/** One-off things the guide asks of its screen. */
internal sealed interface GuideEvent {
    /** Select on the cell on air now: close the guide and tune [channel]. */
    data class Tune(
        val channel: ChannelId,
    ) : GuideEvent

    /** Select on the Channels entry: the guide shows channel management over itself. */
    data object OpenChannels : GuideEvent

    /** Select on the Settings entry: the guide shows its Dark and Light choice. */
    data object OpenSettings : GuideEvent

    /** Select on the Exit entry: leave live TV, the way Back from the menu row does. */
    data object Exit : GuideEvent

    /** Select on the channel column (#631): the guide shows the properties sheet of [channel] over itself. */
    data class OpenChannelProperties(
        val channel: ChannelId,
    ) : GuideEvent
}
