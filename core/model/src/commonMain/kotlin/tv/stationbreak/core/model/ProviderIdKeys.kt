// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * The provider id keys the rule engine reads (the kid hold's bump list, ADR-0029), as the cache keeps them: lower
 * case. The library cache versions a series' ids on these keys only, and the bump list may only name these. Every other
 * key the server sends (zap2it, tvrage, plugin ids) is stored but never read.
 */
object ProviderIdKeys {
    val ENGINE: Set<String> = setOf("imdb", "tmdb", "tvdb")
}
