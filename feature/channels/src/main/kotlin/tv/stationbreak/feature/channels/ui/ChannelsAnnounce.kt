// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsPanel
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.Choice
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.choicesOf
import tv.stationbreak.feature.channels.dialogOpen

/**
 * What the screen's focus is on, for a screen reader. The screen keeps one Compose focus stop and moves its own focus
 * (model-driven), so TalkBack would never follow it: the root says the focused item instead, as a polite live region.
 */
internal sealed interface Announced {
    data object Nothing : Announced

    data class Household(
        val on: Boolean,
    ) : Announced

    data object New : Announced

    data object Reset : Announced

    data class Channel(
        val row: ChannelRow,
    ) : Announced

    /** A choice of the open panel, the sheet or the reset question; [asking] is the question when it has one. */
    data class Pick(
        val choice: Choice,
        val panel: ChannelsPanel?,
        val asking: Asking?,
    ) : Announced

    /** A digit of the number picker: the 1-based place, of how many, and its value. */
    data class Digit(
        val place: Int,
        val of: Int,
        val value: Int,
    ) : Announced
}

/** The question an open panel asks, so it is heard with its first choice. */
internal enum class Asking { OptIn, Swap, Reset, Pro }

/** What the focus of [ui] is on: the open panel's choice when one is open, else the list's focused line. */
internal fun announcedOf(ui: ChannelsUi): Announced =
    when {
        ui.builder != null -> Announced.Nothing
        ui.dialogOpen -> announcedInDialog(ui)
        else -> announcedInList(ui)
    }

private fun announcedInDialog(ui: ChannelsUi): Announced {
    val panel = ui.panel
    val picker = ui.picker
    val choice = choicesOf(ui).getOrNull(ui.choice)
    return when {
        panel is ChannelsPanel.Number && picker != null -> {
            Announced.Digit(picker.at + 1, picker.digits.size, picker.digits.getOrElse(picker.at) { 0 })
        }

        choice == null -> {
            Announced.Nothing
        }

        else -> {
            Announced.Pick(choice, panel, askingOf(ui))
        }
    }
}

private fun askingOf(ui: ChannelsUi): Asking? =
    when {
        ui.panel is ChannelsPanel.ConfirmOptIn -> Asking.OptIn
        ui.panel is ChannelsPanel.Swap -> Asking.Swap
        ui.panel is ChannelsPanel.NeedsPro -> Asking.Pro
        ui.panel == null && ui.confirmingReset -> Asking.Reset
        else -> null
    }

private fun announcedInList(ui: ChannelsUi): Announced =
    when (val focus = ui.focus) {
        ChannelsFocus.Household -> Announced.Household(ui.useMyCollections)
        ChannelsFocus.New -> Announced.New
        ChannelsFocus.Reset -> Announced.Reset
        is ChannelsFocus.Row -> ui.rows.firstOrNull { it.id == focus.id }?.let(Announced::Channel) ?: Announced.Nothing
        null -> Announced.Nothing
    }

/** The words for [announced], and the state to say beside them (null when it has none). */
internal data class Speech(
    val text: String,
    val state: String?,
)

@Composable
internal fun speechOf(
    announced: Announced,
    ui: ChannelsUi,
): Speech? =
    when (announced) {
        Announced.Nothing -> {
            null
        }

        is Announced.Household -> {
            Speech(
                householdSpoken(),
                stringResource(if (announced.on) R.string.channels_switch_on else R.string.channels_switch_off),
            )
        }

        Announced.New -> {
            Speech(stringResource(R.string.builder_new_channel), null)
        }

        Announced.Reset -> {
            Speech(stringResource(R.string.channels_reset), null)
        }

        is Announced.Channel -> {
            Speech(spokenOf(announced.row, ui.isPro), null)
        }

        is Announced.Pick -> {
            val label = labelOf(announced.choice, announced.panel)
            Speech(spokenParts(question(announced, ui), label, join = joiner(R.string.spoken_sentence)), null)
        }

        is Announced.Digit -> {
            Speech(stringResource(R.string.channels_picker_digit, announced.place, announced.of, announced.value), null)
        }
    }

@Composable
private fun question(
    pick: Announced.Pick,
    ui: ChannelsUi,
): String? {
    val panel = pick.panel
    val name =
        ui.rows
            .firstOrNull { it.id == panel?.id }
            ?.name
            .orEmpty()
    return when {
        pick.asking == Asking.OptIn -> {
            stringResource(R.string.channels_confirm_opt_in, name)
        }

        pick.asking == Asking.Swap && panel is ChannelsPanel.Swap -> {
            stringResource(R.string.channels_number_taken, panel.number, panel.holderName)
        }

        pick.asking == Asking.Reset -> {
            stringResource(R.string.channels_reset_confirm)
        }

        pick.asking == Asking.Pro && panel is ChannelsPanel.NeedsPro -> {
            stringResource(proNeedText(panel.need))
        }

        else -> {
            null
        }
    }
}

/**
 * Makes the screen's root say what is focused, as a polite live region, so every move is announced. Nothing is said
 * while the builder has the keys (it speaks for itself), and no layout or look changes.
 */
@Composable
internal fun Modifier.announcingFocus(ui: ChannelsUi): Modifier {
    val speech = speechOf(announcedOf(ui), ui)
    return if (speech == null) {
        this
    } else {
        semantics {
            contentDescription = speech.text
            speech.state?.let { stateDescription = it }
            liveRegion = LiveRegionMode.Polite
        }
    }
}
