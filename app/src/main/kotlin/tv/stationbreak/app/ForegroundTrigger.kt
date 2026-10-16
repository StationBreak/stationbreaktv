// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [action] each time the app comes to the foreground (spec 06 task 4.0: "every app open" includes returning from
 * Home). Attach it to the process lifecycle (`ProcessLifecycleOwner`), whose start fires when the first activity starts
 * and whose stop waits a moment after the last one goes, so a configuration change (the activity is recreated, the
 * process stays started) does not fire it. The action runs in [scope], off the main thread when the scope is, and
 * whatever it throws stays here: an open that fails must never crash the app.
 */
class ForegroundTrigger(
    private val scope: CoroutineScope,
    private val action: suspend () -> Unit,
) : DefaultLifecycleObserver {
    @Suppress("TooGenericExceptionCaught") // Anything an open throws is dropped; the next open is the retry.
    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Dropped on purpose.
            }
        }
    }
}
