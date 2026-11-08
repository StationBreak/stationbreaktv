// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Where channel logos come from. Blocking; always called off the main thread. */
internal interface MarkLoader {
    /** The recipe keys that have a logo. */
    fun keys(): Set<String>

    /** The decoded logo for [key], or null when there is none or it cannot be decoded. */
    fun load(key: String): ImageBitmap?
}

/**
 * Decoded channel logos held in memory so the guide draws them without decoding on the main thread (spec 27). [request]
 * only schedules work on [io]; [peek] only reads. A logo that fails to load is not retried.
 */
internal class MarkCache(
    private val loader: MarkLoader,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val loaded = ConcurrentHashMap<String, ImageBitmap>()

    // Every key ever claimed (loading, loaded or failed): a claim is never given back, so nothing is decoded twice.
    private val claimed = ConcurrentHashMap.newKeySet<String>()
    private val known: Set<String> by lazy {
        try {
            loader.keys()
        } catch (_: Exception) {
            emptySet()
        }
    }
    private val count = MutableStateFlow(0)

    /** Counts logos as they arrive, so a composable that reads it redraws when one lands. */
    val arrivals: StateFlow<Int> = count.asStateFlow()

    /** The logo for [key] if it has loaded, else null. Never blocks. */
    fun peek(key: String): ImageBitmap? = loaded[key]

    /** Starts loading the logos for [keys] that are not loaded or loading. Unknown keys are ignored. Never blocks. */
    fun request(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val wanted = keys.toList()
        scope.launch(io) {
            for (key in wanted) {
                if (key !in known || !claimed.add(key)) continue
                val bitmap =
                    try {
                        loader.load(key)
                    } catch (_: Exception) {
                        null
                    }
                if (bitmap != null) {
                    loaded[key] = bitmap
                    count.update { it + 1 }
                }
            }
        }
    }
}

/**
 * The one cache for the process, so logos survive the guide opening and closing. It is never trimmed on low memory: it
 * holds at most 97 bitmaps of 16 KB each. The dispatcher is the cache's `io` argument alone (the scope adds none).
 */
internal object MarkCaches {
    @Volatile private var instance: MarkCache? = null

    @Suppress("InjectDispatcher") // The process-wide cache is a root: there is nothing to inject a dispatcher into.
    fun get(context: Context): MarkCache =
        instance ?: synchronized(this) {
            instance ?: MarkCache(
                AssetMarkLoader(context.applicationContext.assets),
                CoroutineScope(SupervisorJob()),
                Dispatchers.IO,
            ).also { instance = it }
        }
}

/** The cache the guide's rows draw from; null where none is provided (previews, tests), which draws initials. */
internal val LocalMarkCache = staticCompositionLocalOf<MarkCache?> { null }
