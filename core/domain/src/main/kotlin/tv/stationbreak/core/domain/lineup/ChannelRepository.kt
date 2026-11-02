// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/** Where a channel of the lineup stands (spec 12). */
enum class ChannelStatus {
    /** Switched on: on the guide and in flipping (unless empty for the day, #299). */
    On,

    /** Known but not switched on: it may switch on by itself once it fills, if it has a number. */
    Shelf,

    /** Hidden or removed by the user: it never comes back by itself. */
    UserHidden,
}

/** What a channel airs, as channel management shows it. */
enum class ChannelKind {
    /** A starter-catalog channel, edited by the user or not. */
    Starter,

    /** A leftovers channel: what no other switched-on channel airs. */
    Leftovers,

    /** The user's own rules, or a starter whose recipe left the catalog. */
    Custom,

    /** A stored channel this app could not read. It is kept as it was and never scheduled. */
    Unusable,
}

/**
 * One channel of an account's lineup, for features. [number] is null while the channel waits on the shelf without
 * one. [onSince] is when it was last switched on (for a channel never on, when it joined the lineup). [defaultNumber]
 * is the number the channel has by default, so a feature can tell a number the user chose; [kid] is a channel made for
 * kids (the 1 to 99 numbers are theirs alone); [optIn] is a starter whose recipe needs a deliberate confirmation to
 * switch on.
 */
data class ChannelSummary(
    val id: ChannelId,
    val number: Int?,
    val name: String,
    val state: ChannelStatus,
    val kind: ChannelKind,
    val onSince: Instant,
    val defaultNumber: Int? = null,
    val kid: Boolean = false,
    val optIn: Boolean = false,
)

/** The lineup as features see it (spec 12). */
interface ChannelRepository {
    /**
     * Every channel of [account]'s lineup, on, shelved or hidden, in number order (channels without a number last).
     * Empty while there is no lineup, and for an account whose IDs are not IDs.
     */
    fun channels(account: LibraryAccount): Flow<List<ChannelSummary>>
}
