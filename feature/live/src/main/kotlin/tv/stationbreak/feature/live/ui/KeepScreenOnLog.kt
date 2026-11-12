// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle

/** The tag of live TV's log lines. */
const val KEEP_ON_TAG = "SBLIVE"

/**
 * Whether the keep-screen-on diagnostics are written, here and in the activity's lifecycle lines: a debuggable build,
 * or a device switched on with `setprop log.tag.SBLIVE DEBUG` (so a benchmark build can be asked too). Release builds
 * on a normal device stay silent. Read once per screen.
 */
fun keepOnLogging(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
        runCatching { Log.isLoggable(KEEP_ON_TAG, Log.DEBUG) }.getOrDefault(false)

/**
 * The line for one write of the view's keep-screen-on flag: the [reason], the [written] value, the owner's lifecycle
 * [state], what the view says right after the write, and `ignored=notOwner` when an older composition's write was
 * dropped (#744). Numbers, flags and fixed names only.
 */
internal fun keepOnLine(
    reason: String,
    written: Boolean,
    state: Lifecycle.State,
    view: View,
    ignored: Boolean = false,
): String =
    "keepScreenOn reason=$reason wrote=$written state=$state view=${System.identityHashCode(view)} " +
        "attached=${view.isAttachedToWindow} windowVisibility=${view.windowVisibility} viewNow=${view.keepScreenOn}" +
        if (ignored) " ignored=notOwner" else ""
