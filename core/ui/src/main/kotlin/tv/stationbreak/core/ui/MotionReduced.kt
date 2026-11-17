// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The system's "Remove animations" setting: the animator duration scale is 0. Any other scale keeps motion on. */
internal fun isMotionReduced(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/**
 * Whether the TV's "Remove animations" setting is on. Read when first composed and again whenever the setting
 * changes while this is composed, so a change made with the screen open applies at once. The observer is
 * unregistered when this leaves the composition.
 */
@Composable
fun rememberMotionReduced(): State<Boolean> {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val state = remember(resolver) { mutableStateOf(false) }
    DisposableEffect(resolver) {
        fun read() {
            state.value =
                runCatching {
                    isMotionReduced(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
                }.getOrDefault(false)
        }
        read()
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = read()
            }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return state
}
