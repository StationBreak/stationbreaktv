// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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
internal class DataStoreVolumeLevellingStore(
    data: DataStore<Preferences>,
    scope: CoroutineScope,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : VolumeLevellingStore {
    private val preference = ViewerPreference(data, KEY, { it ?: DEFAULT }, { it }, scope)

    init {
        log("levelling store read enabled=${preference.value.value} stored=${preference.stored ?: "absent"}")
    }

    override val enabled: StateFlow<Boolean> = preference.value

    // Deliberately always logged (not gated on a debuggable build or log.tag): the line carries only the viewer's own
    // setting and its source, so a setting that changed between runs can be traced on any build.
    override fun choose(
        enabled: Boolean,
        from: String,
    ) {
        log("levelling choose enabled=$enabled was=${preference.value.value} from=$from")
        preference.choose(enabled)
    }

    private companion object {
        const val TAG = "SBLIVE"
        val KEY = booleanPreferencesKey("enabled")
        const val DEFAULT = true
    }
}

private val Context.volumeLevellingFile by viewerPreferencesFile("volume-levelling")

/** The one store of the process, so a choice made in the guide reaches the player at once. */
object VolumeLevellings {
    private var store: VolumeLevellingStore? = null

    @Synchronized
    fun get(context: Context): VolumeLevellingStore {
        val existing = store
        if (existing != null) return existing
        val file = context.applicationContext.volumeLevellingFile
        return DataStoreVolumeLevellingStore(file, PreferenceWrites).also { store = it }
    }
}

@Composable
fun rememberVolumeLevellingStore(): VolumeLevellingStore {
    val context = LocalContext.current
    return remember(context) { VolumeLevellings.get(context) }
}
