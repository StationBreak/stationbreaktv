// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** What live TV shows while the channel changes: Stan on his standby screen (the default), or the old snow. */
enum class ChannelChange {
    Stan,
    Snow,
    ;

    companion object {
        /** Stan unless the stored value is Snow: a missing or unknown value is the default. */
        fun decode(stored: String?): ChannelChange = if (stored == encode(Snow)) Snow else Stan

        fun encode(change: ChannelChange): String = change.name
    }
}

/** The viewer's saved [ChannelChange], on this TV only. */
interface ChannelChangeStore {
    val change: StateFlow<ChannelChange>

    fun choose(change: ChannelChange)
}

/** A preference file of its own, next to the screen fit one. */
internal class DataStoreChannelChangeStore(
    data: DataStore<Preferences>,
    scope: CoroutineScope,
) : ChannelChangeStore {
    private val preference = ViewerPreference(data, KEY, ChannelChange::decode, ChannelChange::encode, scope)

    override val change: StateFlow<ChannelChange> = preference.value

    override fun choose(change: ChannelChange) = preference.choose(change)

    private companion object {
        val KEY = stringPreferencesKey("change")
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
