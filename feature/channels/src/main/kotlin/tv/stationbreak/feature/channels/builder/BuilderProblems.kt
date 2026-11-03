// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.lineup.builder.DraftProblem

// A problem shows where it can be put right: on the page it belongs to, and everything on the sheet.
internal fun BuilderModel.problemShown(): DraftProblem? {
    val here = draft.problems().firstOrNull { onSheet || it.step() == step }
    return refused ?: here
}

internal fun DraftProblem.step(): BuilderStep =
    when (this) {
        DraftProblem.NoSources,
        DraftProblem.YearsIncomplete,
        DraftProblem.YearsOutOfRange,
        DraftProblem.YearsReversed,
        DraftProblem.MinutesOutOfRange,
        DraftProblem.MinutesReversed,
        DraftProblem.TooManySources,
        DraftProblem.TooManyLeftOut,
        DraftProblem.TooManyGenres,
        -> BuilderStep.Sources

        DraftProblem.CapOutOfRange, DraftProblem.CapsIncomplete -> BuilderStep.Play

        DraftProblem.NameEmpty,
        DraftProblem.NameTooLong,
        DraftProblem.KidsNumberOutsideBand,
        DraftProblem.GrownUpNumberInKidsBand,
        -> BuilderStep.Name
    }
