// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.selection.ResumeGate
import tv.stationbreak.core.domain.selection.SelectionResume

/**
 * The library selection's resume at the app's start (spec 26, Part 4). [resume] is read here, so a selection that
 * cannot even be built is caught like a resume that fails: [report] is told `SelectionResumeFailed` (a name only), and
 * [gate] opens in every case, so onboarding, the guide and the library workers never wait on it for ever.
 */
@Suppress("TooGenericExceptionCaught") // Whatever building or resuming throws, the gate opens and the app goes on.
internal suspend fun resumeSelection(
    resume: () -> SelectionResume,
    gate: ResumeGate,
    report: (String) -> Unit,
) {
    try {
        resume().resumeAtStart()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        report("SelectionResumeFailed")
    } finally {
        gate.open()
    }
}
