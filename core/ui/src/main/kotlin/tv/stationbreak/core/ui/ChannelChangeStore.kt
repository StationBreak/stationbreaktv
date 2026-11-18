// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** What live TV shows while the channel changes: Stan on his standby screen (the default), or the old snow. */
enum class ChannelChangeStyle {
    Stan,
    Snow,
    ;

    companion object {
        // The stored words are literals of their own: renaming a constant must not change what is on a TV's disk.
        private const val STORED_STAN = "stan"
        private const val STORED_SNOW = "snow"

        /** Stan unless the stored value is Snow: a missing or unknown value is the default. */
        fun decode(stored: String?): ChannelChangeStyle = if (stored == STORED_SNOW) Snow else Stan

        fun encode(style: ChannelChangeStyle): String =
            when (style) {
                Stan -> STORED_STAN
                Snow -> STORED_SNOW
            }
    }
}

/** The viewer's saved [ChannelChangeStyle], on this TV only. */
interface ChannelChangeStore {
    val style: StateFlow<ChannelChangeStyle>

    /** Saves the choice; [from] names the path that made it (a short word such as `settingsLeft`), for the log. */
    fun choose(
        style: ChannelChangeStyle,
        from: String,
    )
}

/** A preference file of its own, next to the screen fit one. */
internal class DataStoreChannelChangeStore(
    data: DataStore<Preferences>,
    scope: CoroutineScope,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : ChannelChangeStore {
    private val preference =
        ViewerPreference(data, KEY, ChannelChangeStyle::decode, ChannelChangeStyle::encode, scope)

    init {
        log("channel change store read style=${preference.value.value} stored=${preference.stored ?: "absent"}")
    }

    override val style: StateFlow<ChannelChangeStyle> = preference.value

    // Always logged, as the levelling store's are: only the viewer's own setting and its source.
    override fun choose(
        style: ChannelChangeStyle,
        from: String,
    ) {
        log("channel change choose style=$style was=${preference.value.value} from=$from")
        preference.choose(style)
    }

    private companion object {
        const val TAG = "SBLIVE"
        val KEY = stringPreferencesKey("style")
    }
}

private val Context.channelChangeFile by viewerPreferencesFile("channel-change")

/** The one store of the process, so a choice made in the guide reaches live TV at once. */
object ChannelChanges {
    private var store: ChannelChangeStore? = null

    @Synchronized
    fun get(context: Context): ChannelChangeStore =
        store ?: DataStoreChannelChangeStore(
            context.applicationContext.channelChangeFile,
            PreferenceWrites,
        ).also { store = it }
}

@Composable
fun rememberChannelChangeStore(): ChannelChangeStore {
    val context = LocalContext.current
    return remember(context) { ChannelChanges.get(context) }
}
