// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Identifies a media server (Jellyfin's `Id` from the public system info). Never blank. */
@Serializable
@JvmInline
value class ServerId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A server ID can't be blank" }
    }
}

/** Identifies a user on a media server. Never blank. */
@Serializable
@JvmInline
value class UserId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A user ID can't be blank" }
    }
}

/**
 * Identifies an item (a movie, a show, a library) on a media server. It goes into request paths, so it may hold only
 * letters, digits, `-` and `_`: Jellyfin's IDs are GUIDs, and anything else could change which endpoint a path means.
 */
@Serializable
@JvmInline
value class ItemId(
    val value: String,
) {
    init {
        require(VALID.matches(value)) { "An item ID is letters, digits, '-' and '_' only" }
    }

    private companion object {
        val VALID = Regex("[A-Za-z0-9_-]+")
    }
}

/** Identifies one playable version of an item (a file). Never blank; it travels as a query value, so it is encoded. */
@Serializable
@JvmInline
value class MediaSourceId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A media source ID can't be blank" }
    }
}

/** Groups one playback's reports on the server. Never blank. Not a credential, but not printed either. */
@Serializable
@JvmInline
value class PlaySessionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A play session ID can't be blank" }
    }

    override fun toString(): String = "PlaySessionId(***)"
}
