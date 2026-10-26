// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemDetails

/** How many channel rows the guide shows at once; [GuideState.Ready.firstVisibleRow] scrolls through the rest. */
const val GUIDE_VISIBLE_ROWS = 5

/** What the guide's grid shows. The focused cell, the details panel and the info overlay are separate flows. */
sealed interface GuideState {
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
 * The panel under the grid: the focused [cell] and what the server says about its item. [loading] is true from the
 * focus landing until the details arrive; if they never do, [item] stays null and [loading] goes false.
 */
data class PanelDetails(
    val cell: GuideCell,
    val channelNumber: Int,
    val channelName: String,
    val item: ItemDetails?,
    val loading: Boolean,
)

/** The info overlay opened with Select on a cell that is not on air: the same facts as [PanelDetails]. */
data class GuideInfo(
    val cell: GuideCell,
    val channelNumber: Int,
    val channelName: String,
    val item: ItemDetails?,
    val loading: Boolean,
)

/** One-off things the guide asks of its screen. */
sealed interface GuideEvent {
    /** Select on the cell on air now: close the guide and tune [channel]. */
    data class Tune(
        val channel: ChannelId,
    ) : GuideEvent
}
