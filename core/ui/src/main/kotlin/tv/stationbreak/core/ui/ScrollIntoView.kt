// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Scrolls the enclosing scrollable (a `verticalScroll` column) just enough to show this item whenever [focused] turns
 * true. For screens whose focus is drawn from a model rather than held by Compose: nothing else would move the
 * scroll, so a choice below the fold would be focused out of sight. The lists use `LazyListState.keepVisible`.
 */
@Composable
fun Modifier.scrollIntoViewWhen(focused: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }
    return bringIntoViewRequester(requester)
}
