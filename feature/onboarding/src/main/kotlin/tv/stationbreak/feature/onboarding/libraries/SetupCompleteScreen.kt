// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.server.BodySize
import tv.stationbreak.feature.onboarding.server.Gap

private val HeadlineSize = 40.sp
private val ContentWidth = 640.dp
private const val OVERSCAN = 0.05f
private const val TOP_OFFSET = 0.1f

/**
 * The placeholder after setup (spec 05, Unit 4), until the first real screen exists (F1.x): a headline and one line
 * saying setup is saved. It has no actions. Back is the platform's: the navigation graph puts nothing under it, so Back
 * leaves the app and onboarding is never re-entered.
 */
@Composable
fun SetupCompleteDestination() = SetupCompleteScreen()

@Composable
internal fun SetupCompleteScreen(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .testTag(SetupCompleteTags.SCREEN),
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize().padding(
                    start = maxWidth * OVERSCAN,
                    end = maxWidth * OVERSCAN,
                    top = maxHeight * TOP_OFFSET,
                    bottom = maxHeight * OVERSCAN,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Gap),
            ) {
                Text(
                    text = stringResource(R.string.setup_complete_headline),
                    modifier = Modifier.testTag(SetupCompleteTags.HEADLINE),
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = HeadlineSize),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.setup_complete_body),
                    modifier = Modifier.testTag(SetupCompleteTags.BODY),
                    fontSize = BodySize,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
