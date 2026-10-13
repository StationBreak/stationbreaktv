// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import tv.stationbreak.core.model.ChannelId

/**
 * Reads a channel ID from outside input, where it may be missing. Also the ADR-0004 proof that `app` compiles
 * against the KMP shared core.
 */
internal fun parseChannelId(raw: String?): ChannelId? = raw?.takeIf { it.isNotBlank() }?.let(::ChannelId)
