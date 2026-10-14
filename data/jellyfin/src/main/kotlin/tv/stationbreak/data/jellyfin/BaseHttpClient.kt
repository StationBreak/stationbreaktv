// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import javax.inject.Qualifier

/**
 * Marks the `OkHttpClient` the adapter derives its own clients from: system trust today, F0.5's per-server pinned
 * client later (ADR-0005). A qualifier, so another client in the graph (an image loader's, say) can't be injected here
 * by mistake and the pinned one can't be confused with the unpinned one.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class BaseHttpClient
