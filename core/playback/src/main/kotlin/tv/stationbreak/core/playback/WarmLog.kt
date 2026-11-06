// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.os.Debug
import android.util.Log

/** The `SBWARM` log lines: numbers, fixed names and the warm key only. The tag is [WarmSwitches.MASTER_TAG]. */
internal object WarmLog {
    const val TAG = WarmSwitches.MASTER_TAG
    private const val KIB = 1_024L

    /** Sees every line as well, for instrumented tests; null in the app. */
    @Volatile var sink: ((String) -> Unit)? = null

    fun line(text: String) {
        sink?.invoke(text)
        runCatching { Log.i(TAG, text) }
    }

    /** The memory now, as fields: Java heap in use and its limit, and the native heap allocated, in KiB. */
    fun memoryFields(): String {
        val runtime = Runtime.getRuntime()
        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        return "javaKb=${(runtime.totalMemory() - runtime.freeMemory()) / KIB} " +
            "javaMaxKb=${runtime.maxMemory() / KIB} nativeKb=${native / KIB}"
    }
}
