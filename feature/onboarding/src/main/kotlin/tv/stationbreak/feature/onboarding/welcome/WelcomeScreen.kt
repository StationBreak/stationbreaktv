// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.screenBody
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.SetupScaffold
import tv.stationbreak.feature.onboarding.StepHeadline
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ServerButton
import tv.stationbreak.feature.onboarding.withTop

private val ContentWidth = 640.dp
private const val TOP_OFFSET = 0.1f

/**
 * The welcome screen (#94): what StationBreak needs from the viewer, and one button, "Get started", which has default
 * focus. Launch opens it when nothing is stored, with nothing beneath it, so Back leaves the app.
 *
 * [covered] is true while the opening is drawn over it: Stan waits, and greets as it hands over.
 */
@Composable
fun WelcomeDestination(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
    covered: Boolean = false,
) {
    val getStarted = remember { FocusRequester() }
    LaunchedEffect(Unit) { getStarted.requestFocus() }
    SetupScaffold(welcomeCue, modifier, covered = covered) { contentPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag(WelcomeTags.SCREEN)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding.withTop(maxHeight * TOP_OFFSET)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Gap),
                ) {
                    StepHeadline(headline = stringResource(R.string.welcome_title), step = null)
                    Text(
                        text = stringResource(R.string.welcome_tagline),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.welcome_body, stringResource(R.string.server_product_name)),
                        style = MaterialTheme.typography.screenBody,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ServerButton(
                        label = R.string.welcome_get_started,
                        onClick = onGetStarted,
                        emphasis = true,
                        modifier = Modifier.focusRequester(getStarted).testTag(WelcomeTags.GET_STARTED),
                    )
                }
            }
        }
    }
}
