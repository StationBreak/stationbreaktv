// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.screenDetail

/** How many steps the first-run flow has: Server, Sign in, Libraries. */
private const val STEP_COUNT = 3

/**
 * A screen's headline, a heading for a screen reader. In the first-run flow ([step] is 1 to 3) "Step n of 3" sits above
 * it and is spoken with it as one node ("Step 1 of 3, Connect to your Jellyfin server"); nothing here takes focus.
 */
@Composable
internal fun StepHeadline(
    headline: String,
    step: Int?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (step != null) {
            Text(
                text = stringResource(R.string.step_marker, step, STEP_COUNT),
                style = MaterialTheme.typography.screenDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
