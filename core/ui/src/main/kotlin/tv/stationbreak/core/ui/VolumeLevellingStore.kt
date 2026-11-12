// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.Context
import android.util.Log
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

    /** Saves the choice; [from] names the path that made it (a short word such as `settingsLeft`), for the log. */
    fun choose(
        enabled: Boolean,
        from: String,
    )
}

/** A preference file of its own, next to the screen fit one. */
internal class SharedPrefsVolumeLevellingStore(
    context: Context,
) : VolumeLevellingStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(KEY, DEFAULT))

    init {
        val stored = if (prefs.contains(KEY)) prefs.getBoolean(KEY, DEFAULT).toString() else "absent"
        log("levelling store read enabled=${state.value} stored=$stored")
    }

    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override fun choose(
        enabled: Boolean,
        from: String,
    ) {
        log("levelling choose enabled=$enabled was=${state.value} from=$from")
        prefs.edit().putBoolean(KEY, enabled).apply()
        state.value = enabled
    }

    // Deliberately always on (not gated on a debuggable build or log.tag): the line carries only the viewer's own
    // setting and its source, so a setting that changed between runs can be traced on any build.
    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "SBLIVE"
        const val PREFS = "volume-levelling"
        const val KEY = "enabled"
        const val DEFAULT = true
    }
}

/** The one store of the process, so a choice made in the guide reaches the player at once. */
object VolumeLevellings {
    private var store: VolumeLevellingStore? = null

    @Synchronized
    fun get(context: Context): VolumeLevellingStore {
        val existing = store
        if (existing != null) return existing
        return SharedPrefsVolumeLevellingStore(context).also { store = it }
    }
}

@Composable
fun rememberVolumeLevellingStore(): VolumeLevellingStore {
    val context = LocalContext.current
    return remember(context) { VolumeLevellings.get(context) }
}
