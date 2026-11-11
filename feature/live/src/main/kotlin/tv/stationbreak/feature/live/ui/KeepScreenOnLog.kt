// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle

internal const val KEEP_ON_TAG = "SBLIVE"

private const val KEEP_FLAG = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

/**
 * Whether the keep-screen-on diagnostics are written: a debuggable build, or a device switched on with
 * `setprop log.tag.SBLIVE DEBUG` (so a benchmark build can be asked too). Release builds on a normal device stay
 * silent. Read once per screen.
 */
internal fun keepOnLogging(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
        runCatching { Log.isLoggable(KEEP_ON_TAG, Log.DEBUG) }.getOrDefault(false)

/**
 * The line for one write of the view's keep-screen-on flag: the [reason], the [written] value, the owner's lifecycle
 * [state], and what the view and its root say right after the write. Numbers, flags and fixed names only.
 */
internal fun keepOnLine(
    reason: String,
    written: Boolean,
    state: Lifecycle.State,
    view: View,
): String {
    val root = view.rootView
    val windowFlag = (root.layoutParams as? WindowManager.LayoutParams)?.let { it.flags and KEEP_FLAG != 0 }
    return "keepScreenOn reason=$reason wrote=$written state=$state view=${System.identityHashCode(view)} " +
        "attached=${view.isAttachedToWindow} windowVisibility=${view.windowVisibility} " +
        "viewNow=${view.keepScreenOn} rootView=${root.keepScreenOn} rootWindowFlag=$windowFlag"
}
