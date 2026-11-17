// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.properties.ReadOnlyProperty

/**
 * One viewer preference kept under [key] in a Jetpack DataStore file (#820). It is read once, when the store is made,
 * blocking the caller as SharedPreferences' first read did, so the first frame already lays out with the saved choice.
 * A choice shows in [value] at once and is saved off the caller's thread, one write after another, so the last choice
 * is the one kept. Each write is atomic (DataStore writes a new file, syncs it and renames it), but it is not awaited:
 * a process killed within moments of a choice can still lose it. An unreadable file reads as missing, which every
 * caller decodes as its default; a failed write is logged and the choice stays in force for this run. Anything else
 * thrown is a programming error and is not caught.
 *
 * Make one per file and key for the life of the process (each keeps a writer running), as the stores' singletons do.
 */
class ViewerPreference<S, T>(
    private val data: DataStore<Preferences>,
    private val key: Preferences.Key<S>,
    decode: (S?) -> T,
    private val encode: (T) -> S,
    scope: CoroutineScope = PreferenceWrites,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
    /** The value stored before this store was made, `null` when there was none. */
    internal val stored: S? = runBlocking { readStored() }

    private val state = MutableStateFlow(decode(stored))
    private val writes = Channel<T>(Channel.CONFLATED)

    val value: StateFlow<T> = state.asStateFlow()

    init {
        scope.launch {
            for (chosen in writes) {
                try {
                    data.edit { it[key] = encode(chosen) }
                } catch (e: IOException) {
                    // The choice stays in force for this run; the next choice tries the file again.
                    warn("preference write failed key=${key.name}: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    fun choose(chosen: T) {
        state.value = chosen
        writes.trySend(chosen)
    }

    private suspend fun readStored(): S? =
        try {
            data.data.first()[key]
        } catch (e: IOException) {
            warn("preference read failed key=${key.name}: ${e.javaClass.simpleName}")
            null
        }
}

private const val TAG = "SBPREFS"

/** Where preference writes run: off the main thread, for the life of the process. */
@Suppress("InjectDispatcher") // A process-wide root, like MarkCache's: there is nothing to inject a dispatcher into.
internal val PreferenceWrites: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The DataStore file [name] for a [Context], one per process as DataStore requires. A SharedPreferences file of the
 * same name, from an older version of the app, is copied in once and then deleted, so a saved choice survives the
 * update: [name] must be the name of a viewer-preference file this app owns, never another component's file. A corrupt
 * file is logged and replaced by an empty one, so every choice reads as its default.
 */
fun viewerPreferencesFile(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> =
    preferencesDataStore(
        name = name,
        corruptionHandler =
            ReplaceFileCorruptionHandler { e ->
                Log.w(TAG, "preference file corrupt, reset name=$name: ${e.javaClass.simpleName}")
                emptyPreferences()
            },
        produceMigrations = { context -> listOf(SharedPreferencesMigration(context, name)) },
    )
