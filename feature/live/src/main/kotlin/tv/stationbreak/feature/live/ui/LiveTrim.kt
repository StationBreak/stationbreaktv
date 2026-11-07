// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import tv.stationbreak.core.playback.VideoPlayer

/**
 * Forwards the system's memory trims to [player] for one live visit (spec 24): from
 * [ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE], the lowest running level, upward. The player then drops its warm
 * standby and stays cold for the rest of the visit. The system calls it on the main thread and so it forwards there.
 */
internal class LiveTrim(
    private val player: VideoPlayer,
) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) player.trimMemory(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Superseded by onTrimMemory, which carries the level.")
    override fun onLowMemory() = Unit
}

/** Where a visit's [LiveTrim] is registered while it lives: the app's context in the app, a fake in a test. */
internal interface TrimHost {
    fun register(callbacks: ComponentCallbacks2)

    fun unregister(callbacks: ComponentCallbacks2)

    companion object {
        /** Registers nothing. */
        val None: TrimHost =
            object : TrimHost {
                override fun register(callbacks: ComponentCallbacks2) = Unit

                override fun unregister(callbacks: ComponentCallbacks2) = Unit
            }
    }
}

/** The app context's registry. It is the application's, so a registration never holds the activity. */
internal class ContextTrimHost(
    context: Context,
) : TrimHost {
    private val app = context.applicationContext ?: context

    override fun register(callbacks: ComponentCallbacks2) = app.registerComponentCallbacks(callbacks)

    override fun unregister(callbacks: ComponentCallbacks2) = app.unregisterComponentCallbacks(callbacks)
}
