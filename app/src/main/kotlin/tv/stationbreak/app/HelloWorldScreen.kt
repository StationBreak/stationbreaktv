// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.tv.material3.Button
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

internal const val HELLO_WORLD_FIRST_TAG = "helloWorld:first"
internal const val HELLO_WORLD_SECOND_TAG = "helloWorld:second"

private val ItemSpacing = 24.dp

/**
 * Two focusable items in a row, the first focused at launch, so the D-pad has something to move between. It
 * exists only to prove Compose for TV focus on the Insignia (S0.2.2), uses tv-material's defaults, and is deleted
 * when feature:live arrives.
 */
@Composable
internal fun HelloWorldScreen(modifier: Modifier = Modifier) {
    val firstItem = remember { FocusRequester() }
    Surface(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(ItemSpacing, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {},
                modifier = Modifier.focusRequester(firstItem).testTag(HELLO_WORLD_FIRST_TAG),
            ) {
                Text(stringResource(R.string.hello_world_first))
            }
            Button(
                onClick = {},
                modifier = Modifier.testTag(HELLO_WORLD_SECOND_TAG),
            ) {
                Text(stringResource(R.string.hello_world_second))
            }
        }
    }
    LaunchedEffect(Unit) { firstItem.requestFocus() }
}
