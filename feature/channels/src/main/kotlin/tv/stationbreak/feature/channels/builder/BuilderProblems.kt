// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.lineup.builder.DraftProblem

// The name comes first among a draft's problems, so a nameless draft is given one to find the others.
private const val PROBE_NAME = "-"

// A problem shows where it can be put right: on the page it belongs to, and everything on the sheet.
internal fun BuilderModel.problemShown(): DraftProblem? {
    val found = if (step == BuilderStep.Name || onSheet) draft.problem() else draft.copy(name = PROBE_NAME).problem()
    return refused ?: found?.takeIf { onSheet || it.step() == step }
}

private fun DraftProblem.step(): BuilderStep =
    when (this) {
        DraftProblem.NameEmpty,
        DraftProblem.NameTooLong,
        DraftProblem.KidsNumberOutsideBand,
        DraftProblem.GrownUpNumberInKidsBand,
        -> BuilderStep.Name

        DraftProblem.CapOutOfRange, DraftProblem.CapsIncomplete -> BuilderStep.Play

        else -> BuilderStep.Sources
    }
