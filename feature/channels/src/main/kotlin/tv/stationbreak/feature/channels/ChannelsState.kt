// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.builder.BuilderMode

/** The three lists of the channel screen: switched on, known but not on, and switched off by the viewer. */
internal enum class ChannelGroup { On, Shelf, Off }

/** One row of the channel screen. */
internal data class ChannelRow(
    val id: ChannelId,
    val number: Int?,
    val name: String,
    val group: ChannelGroup,
    val kind: ChannelKind,
    val kid: Boolean,
    val optIn: Boolean,
    /** Why an On channel has nothing to air today; null for the others and for a channel that has something. */
    val reason: HiddenReason?,
    /** The viewer chose this number, so "Default number" shows. */
    val hasOverride: Boolean,
)

/** The small dialog open over the list, about one channel. */
internal sealed interface ChannelsPanel {
    val id: ChannelId

    data class Actions(
        override val id: ChannelId,
    ) : ChannelsPanel

    data class ConfirmOptIn(
        override val id: ChannelId,
    ) : ChannelsPanel

    data class Number(
        override val id: ChannelId,
        val start: Int,
    ) : ChannelsPanel

    data class Swap(
        override val id: ChannelId,
        val number: Int,
        val holderName: String,
        val toDefault: Boolean,
    ) : ChannelsPanel
}

/** A line the screen shows after something did not happen. */
internal enum class ChannelsMessage {
    KidsBand,
    LastOn,
    NoNumber,
    NotEditable,
    NotChosenYet,
    SaveFailed,
    Failed,
    LibrariesUnavailable,
}

/** Where the focus is, by identity, so it follows a row that moves group. */
internal sealed interface ChannelsFocus {
    data class Row(
        val id: ChannelId,
    ) : ChannelsFocus

    /** The New channel row above the first group. */
    data object New : ChannelsFocus

    data object Reset : ChannelsFocus
}

/** What the channel screen draws. */
internal data class ChannelsUi(
    /** On, then Shelf, then Off; in number order inside each, numberless last. */
    val rows: List<ChannelRow>,
    val focus: ChannelsFocus?,
    /** Editable shows changes; Loading shows the list without actions; ReadOnly shows channels_not_editable. */
    val access: LineupAccess,
    /** While a reset runs. */
    val choosing: Boolean,
    val panel: ChannelsPanel?,
    val message: ChannelsMessage?,
    val confirmingReset: Boolean,
    /** Which choice of the open panel (or of the reset question) is focused; see [choicesOf]. */
    val choice: Int = 0,
    /** The number picker's digits while its panel is open. */
    val picker: PickerState? = null,
    /** The first read of why channels have nothing to air has landed (until then Watch is not offered). */
    val reasonsRead: Boolean = false,
    /** The channel builder is showing over the list, opened for this; the screen's keys are the builder's. */
    val builder: BuilderMode? = null,
) {
    companion object {
        val Empty =
            ChannelsUi(
                rows = emptyList(),
                focus = null,
                access = LineupAccess.Loading,
                choosing = false,
                panel = null,
                message = null,
                confirmingReset = false,
            )
    }
}
