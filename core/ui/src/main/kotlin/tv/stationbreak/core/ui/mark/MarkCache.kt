// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.mark

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
interface MarkLoader {
    /** The recipe keys that have a logo. */
    fun keys(): Set<String>

    /** The decoded logo for [key], or null when there is none or it cannot be decoded. */
    fun load(key: String): ImageBitmap?

    /** The all-white render of [key] (the Light theme tints it), or null when there is none. */
    fun loadMono(key: String): ImageBitmap? = null
}

/**
 * Decoded channel logos held in memory so the guide draws them without decoding on the main thread (spec 27). [request]
 * only schedules work on [io]; [peek] only reads. A logo that fails to load is not retried.
 */
class MarkCache(
    private val loader: MarkLoader,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    // One map and one claim set per variant: a key's colour and mono renders are decoded, and given up on, separately.
    private val loaded = MarkVariant.entries.associateWith { ConcurrentHashMap<String, ImageBitmap>() }

    // Every key ever claimed (loading, loaded or failed): a claim is never given back, so nothing is decoded twice.
    private val claimed = MarkVariant.entries.associateWith { ConcurrentHashMap.newKeySet<String>() }
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

    /** The logo for [key] in [variant] if it has loaded, else null. Never blocks. */
    fun peek(
        key: String,
        variant: MarkVariant = MarkVariant.Colour,
    ): ImageBitmap? = loaded.getValue(variant)[key]

    /**
     * Starts loading the [variant] logos for [keys] that are not loaded or loading. Unknown keys are ignored. Never
     * blocks.
     */
    fun request(
        keys: Collection<String>,
        variant: MarkVariant = MarkVariant.Colour,
    ) {
        if (keys.isEmpty()) return
        val wanted = keys.toList()
        scope.launch(io) {
            for (key in wanted) {
                if (key !in known || !claimed.getValue(variant).add(key)) continue
                val bitmap =
                    try {
                        if (variant == MarkVariant.Mono) loader.loadMono(key) else loader.load(key)
                    } catch (_: Exception) {
                        null
                    }
                if (bitmap != null) {
                    loaded.getValue(variant)[key] = bitmap
                    count.update { it + 1 }
                }
            }
        }
    }
}

/**
 * The one cache for the process, so logos survive the guide opening and closing. It is never trimmed on low memory: it
 * holds at most 97 logos in each of two variants, each a 128 by 72 bitmap of 37 KB (the Light theme decodes only the
 * mono ones, the Dark theme only the colour ones). The dispatcher is the cache's `io` argument alone (the scope adds
 * none).
 */
object MarkCaches {
    @Volatile private var instance: MarkCache? = null

    @Suppress("InjectDispatcher") // The process-wide cache is a root: there is nothing to inject a dispatcher into.
    fun get(context: Context): MarkCache =
        instance ?: synchronized(this) {
            instance ?: MarkCache(
                AssetMarkLoader(
                    context.applicationContext.assets,
                    context.resources.displayMetrics.density,
                ),
                CoroutineScope(SupervisorJob()),
                Dispatchers.IO,
            ).also { instance = it }
        }
}

/**
 * The cache the guide's rows and live TV's banner draw from; null where none is provided (previews, tests), which draws
 * initials.
 */
val LocalMarkCache = staticCompositionLocalOf<MarkCache?> { null }
