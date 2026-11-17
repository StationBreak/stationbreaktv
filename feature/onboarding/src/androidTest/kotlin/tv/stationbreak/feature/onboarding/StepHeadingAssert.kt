// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText

/** The step marker and the headline are one heading node, so a screen reader says them together. */
internal fun SemanticsNodeInteractionsProvider.assertStepHeading(
    marker: String,
    headline: String,
) {
    onNode(keyIsDefined(SemanticsProperties.Heading) and hasText(marker))
        .assert(hasText(headline, substring = true))
}
