// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The system's "Remove animations" setting: the animator duration scale is 0. Any other scale keeps motion on. */
internal fun isMotionReduced(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/**
 * Whether the TV's "Remove animations" setting is on, for everything under [StationBreakTheme]. Provided once at the
 * root, so a composed list cell reads a value instead of registering an observer. Defaults to motion on, which is
 * what a test or preview without the theme wants.
 */
val LocalMotionReduced = compositionLocalOf { false }

private fun readMotionReduced(resolver: ContentResolver): Boolean =
    runCatching {
        isMotionReduced(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }.getOrDefault(false)

/**
 * The setting, read synchronously on first composition (so the first frame already honours it) and again whenever it
 * changes while this is composed. The single observer is unregistered when this leaves the composition. Used only by
 * [StationBreakTheme].
 */
@Composable
internal fun rememberMotionReduced(): State<Boolean> {
    val resolver = LocalContext.current.contentResolver
    val state = remember(resolver) { mutableStateOf(readMotionReduced(resolver)) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    state.value = readMotionReduced(resolver)
                }
            }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        // Catch a change made between the seed read and the registration.
        state.value = readMotionReduced(resolver)
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return state
}
