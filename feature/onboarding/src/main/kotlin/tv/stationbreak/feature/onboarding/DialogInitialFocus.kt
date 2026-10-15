// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Gives [target] focus in the dialog this is called from. Call it inside the `Dialog { }` content, never beside the
 * `Dialog`: the dialog is its own window with its own focus, so a request made from the screen under it runs before
 * that window exists or has focus, and the remote's focus search then picks a button of its own (found on a Fire TV,
 * where it landed on "Connect anyway"). The request is made when the content is first composed and again when the
 * dialog window gains focus, which on slow hardware comes after.
 *
 * Because of that second request, focus also returns to [target] whenever the dialog window regains focus, for
 * example after a system overlay. That is intended: [target] is always the dialog's safe button. [target] must be
 * attached unconditionally in the dialog content, or the request throws.
 */
@Composable
internal fun RequestInitialDialogFocus(target: FocusRequester) {
    LaunchedEffect(Unit) { target.requestFocus() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (windowFocused) target.requestFocus() }
}
