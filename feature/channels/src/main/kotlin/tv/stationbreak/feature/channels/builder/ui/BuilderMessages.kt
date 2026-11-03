// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.core.domain.lineup.builder.DraftProblem
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.domain.lineup.builder.PreviewItem
import tv.stationbreak.core.domain.lineup.builder.SkipReason
import tv.stationbreak.core.domain.lineup.builder.SkippedPick
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderConfirm
import tv.stationbreak.feature.channels.builder.BuilderMessage
import tv.stationbreak.feature.channels.builder.BuilderMode
import tv.stationbreak.feature.channels.builder.BuilderStep
import tv.stationbreak.feature.channels.builder.SourceKindTab

// The words of what did not happen, and of the questions the builder asks.

@Composable
internal fun messageText(
    message: BuilderMessage,
    holder: String?,
    number: Int?,
): String =
    when {
        message == BuilderMessage.KidsBand -> {
            stringResource(R.string.builder_kids_band, ChannelNumbers.kids.first, ChannelNumbers.kids.last)
        }

        message == BuilderMessage.NumberTaken && holder != null && number != null -> {
            stringResource(R.string.builder_number_held, number, holder)
        }

        else -> {
            stringResource(plainMessage(message))
        }
    }

private fun plainMessage(message: BuilderMessage): Int =
    when (message) {
        BuilderMessage.KidsBandFull -> R.string.builder_kids_band_full
        BuilderMessage.NoFreeNumber -> R.string.builder_no_free_number
        BuilderMessage.OutOfRange -> R.string.builder_out_of_range
        BuilderMessage.LastOn -> R.string.builder_last_on
        BuilderMessage.NumberNotTradable -> R.string.builder_number_not_tradable
        BuilderMessage.SaveFailed -> R.string.builder_save_failed
        BuilderMessage.Failed -> R.string.builder_failed
        BuilderMessage.NotEditable -> R.string.builder_not_editable
        BuilderMessage.NotCustomisable -> R.string.builder_not_customisable
        BuilderMessage.NotFound -> R.string.builder_not_found
        BuilderMessage.OpenFailed -> R.string.builder_open_failed
        BuilderMessage.KidsBand, BuilderMessage.NumberTaken -> R.string.builder_number_taken_pick
    }

@Composable
internal fun problemText(problem: DraftProblem): String {
    val kids = ChannelNumbers.kids
    return when (problem) {
        DraftProblem.KidsNumberOutsideBand -> {
            stringResource(R.string.builder_problem_kids_number, kids.first, kids.last)
        }

        DraftProblem.GrownUpNumberInKidsBand -> {
            stringResource(R.string.builder_problem_grown_up_number, kids.first, kids.last)
        }

        else -> {
            stringResource(plainProblem(problem))
        }
    }
}

// The problems that are one fixed line; the two that name the kids numbers are worded by problemText.
private val PLAIN_PROBLEMS: Map<DraftProblem, Int> =
    mapOf(
        DraftProblem.NameEmpty to R.string.builder_problem_name_empty,
        DraftProblem.NameTooLong to R.string.builder_problem_name_too_long,
        DraftProblem.NoSources to R.string.builder_problem_no_sources,
        DraftProblem.CapOutOfRange to R.string.builder_problem_cap_out_of_range,
        DraftProblem.YearsIncomplete to R.string.builder_problem_years_incomplete,
        DraftProblem.YearsOutOfRange to R.string.builder_problem_years_out_of_range,
        DraftProblem.YearsReversed to R.string.builder_problem_years_reversed,
        DraftProblem.MinutesOutOfRange to R.string.builder_problem_minutes_out_of_range,
        DraftProblem.MinutesReversed to R.string.builder_problem_minutes_reversed,
        DraftProblem.CapsIncomplete to R.string.builder_problem_caps_incomplete,
        DraftProblem.TooManySources to R.string.builder_problem_too_many_sources,
        DraftProblem.TooManyLeftOut to R.string.builder_problem_too_many_left_out,
        DraftProblem.TooManyGenres to R.string.builder_problem_too_many_genres,
    )

private fun plainProblem(problem: DraftProblem): Int = PLAIN_PROBLEMS[problem] ?: R.string.builder_failed

/** The question a confirm asks. */
@Composable
internal fun confirmText(confirm: BuilderConfirm): String =
    when (confirm) {
        BuilderConfirm.Leave -> stringResource(R.string.builder_leave_confirm)
        BuilderConfirm.KidsOff -> stringResource(R.string.builder_kids_off_confirm)
        is BuilderConfirm.Delete -> stringResource(R.string.builder_delete_confirm, confirm.name)
        is BuilderConfirm.Swap -> stringResource(R.string.builder_number_taken, confirm.number, confirm.holderName)
    }

/** The two choices of a confirm: the action, then the keep. */
@Composable
internal fun confirmChoices(
    confirm: BuilderConfirm,
    mode: BuilderMode,
): Pair<String, String> =
    when (confirm) {
        BuilderConfirm.Leave -> {
            val editing = mode is BuilderMode.Edit
            val keep = if (editing) R.string.builder_leave_keep_editing else R.string.builder_leave_keep_building
            stringResource(R.string.builder_leave_leave) to stringResource(keep)
        }

        BuilderConfirm.KidsOff -> {
            stringResource(R.string.builder_kids_off_turn_off) to stringResource(R.string.builder_kids_off_keep)
        }

        is BuilderConfirm.Delete -> {
            stringResource(R.string.builder_delete_action) to stringResource(R.string.builder_delete_cancel)
        }

        is BuilderConfirm.Swap -> {
            stringResource(R.string.builder_swap_action) to stringResource(R.string.builder_swap_cancel)
        }
    }
