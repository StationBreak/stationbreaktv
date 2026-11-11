// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the TV evens out volume between quiet and loud shows (#498): the viewer's choice on this TV only, kept on the
 * device (it does not change the schedule, so it is not a rules field). On until the viewer turns it off.
 */
interface VolumeLevellingStore {
    val enabled: StateFlow<Boolean>

    fun choose(enabled: Boolean)
}

/** A preference file of its own, next to the screen fit one. */
internal class SharedPrefsVolumeLevellingStore(
    context: Context,
) : VolumeLevellingStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(KEY, DEFAULT))

    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override fun choose(enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
        state.value = enabled
    }

    private companion object {
        const val PREFS = "volume-levelling"
        const val KEY = "enabled"
        const val DEFAULT = true
    }
}

/** The one store of the process, so a choice made in the guide reaches the player at once. */
object VolumeLevellings {
    private var store: VolumeLevellingStore? = null

    @Synchronized
    fun get(context: Context): VolumeLevellingStore =
        store ?: SharedPrefsVolumeLevellingStore(context).also { store = it }
}

@Composable
fun rememberVolumeLevellingStore(): VolumeLevellingStore {
    val context = LocalContext.current
    return remember(context) { VolumeLevellings.get(context) }
}
