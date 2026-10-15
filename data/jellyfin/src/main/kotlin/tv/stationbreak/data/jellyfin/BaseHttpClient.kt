// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.OkHttpClient
import javax.inject.Qualifier

/**
 * Marks the [HttpClientSource] the adapter derives its own clients from: F0.5's per-server pinned client (S0.5.2,
 * ADR-0005). A qualifier, so another source in the graph (an image loader's, say) can't be injected here by mistake
 * and the pinned one can't be confused with an unpinned one.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class BaseHttpClient

/**
 * The client the adapter derives from, asked for on every call because the pinned client is replaced whenever a pin
 * changes. Nobody keeps what it returns: a kept client would go on trusting what the pins no longer allow.
 */
fun interface HttpClientSource {
    fun current(): OkHttpClient
}

/**
 * A client derived from the source's current one, rebuilt only when the source hands out a different client. Derived
 * clients keep the base's trust, so a pin change reaches them on the next call.
 */
internal class Derived<T>(
    private val source: HttpClientSource,
    private val build: (OkHttpClient) -> T,
) {
    private class Entry<T>(
        val base: OkHttpClient,
        val value: T,
    )

    @Volatile
    private var entry: Entry<T>? = null

    fun get(): T {
        val base = source.current()
        entry?.let { if (it.base === base) return it.value }
        return Entry(base, build(base)).also { entry = it }.value
    }
}
