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

/** Why the planner left a channel off the lineup (spec 23); the channel screen words each one. */
enum class ShelfReason {
    /** Nothing in the libraries fits the channel. */
    Empty,

    /** Too little fits once every title has its best home. */
    TooThin,

    /** It fits, but stronger channels filled the lineup. */
    BelowCap,

    /** An opt-in channel: on only if the viewer switches it on. */
    OptIn,
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

    /** As [Unusable], but the stored source says custom: the viewer's own rules, written by a newer app. */
    UnusableCustom,
    ;

    /** The app could not read the channel's rules. */
    val unreadable: Boolean get() = this == Unusable || this == UnusableCustom
}

/**
 * One channel of an account's lineup, for features. [number] is null while the channel waits on the shelf without
 * one. [onSince] is when it was last switched on (for a channel never on, when it joined the lineup); for a channel the
 * planner switched on for the next day start it is that start, a time ahead, and the channel is not on the air before
 * it. Only a planner switch-on is ever ahead (a clock set back aside), and only to a day start, so `onSince > now` is
 * the engine's shared rule (`startsAfter` today): a feature leaves such a channel out of what is on the air now.
 * [defaultNumber]
 * is the number the channel has by default, so a feature can tell a number the user chose; [kid] is a channel made for
 * kids (the 1 to 99 numbers are theirs alone); [optIn] is a starter whose recipe needs a deliberate confirmation to
 * switch on. [playKey] stands for what the channel's rules do to its schedule (its order, seed, sources and the rest,
 * but not its name): it differs when the days built from the rules would, though the channel and its number do not.
 * [shelfReason] is why the planner shelved the channel, or why it leaves at the next day start; null for every other
 * channel, including one that starts at the next day start. [recipeKey] is the catalog recipe key of a starter or
 * leftovers channel; null for a custom channel and for an unusable one. It is a view-model field, never stored.
 */
data class ChannelSummary(
    val id: ChannelId,
    val number: Int?,
    val name: String,
    val state: ChannelStatus,
    val kind: ChannelKind,
    val onSince: Instant,
    val defaultNumber: Int?,
    val kid: Boolean,
    val optIn: Boolean,
    val playKey: PlayKey,
    val shelfReason: ShelfReason? = null,
    val recipeKey: String? = null,
)

/**
 * What a channel's built days depend on, as an opaque digest (the channel's part of the days' key,
 * `LineupDays.inputsOf`,
 * name excluded). Two summaries of a channel with equal keys have the same days; compare, never read.
 */
@JvmInline
value class PlayKey(
    internal val digest: String,
)

/** The lineup as features see it (spec 12). */
interface ChannelRepository {
    /**
     * Every channel of [account]'s lineup, on, shelved or hidden, in number order (channels without a number last).
     * Empty while there is no lineup, and for an account whose IDs are not IDs.
     */
    fun channels(account: LibraryAccount): Flow<List<ChannelSummary>>

    /**
     * [channels] of the active account, following it: a switch of account ends the old list and starts the new one,
     * and nothing is emitted while no account is active. The one signal a feature watches for the lineup changing
     * under it (see [onLineup]).
     */
    fun activeChannels(): Flow<List<ChannelSummary>>
}

/**
 * What the guide and flipping go by: the On channels' ids and numbers, in the order given. Two lists with the same
 * [onLineup] show the same channels in the same places, whatever else changed (a name, a shelf channel).
 */
fun List<ChannelSummary>.onLineup(): List<Pair<ChannelId, Int?>> {
    val on = filter { it.state == ChannelStatus.On }
    return on.map { it.id to it.number }
}

/**
 * The channel to tune when the one numbered [number] has left an [onLineup] list (deleted, switched off or hidden):
 * the next higher number, else the next lower, as cable does; null when no numbered channel is left.
 */
fun List<Pair<ChannelId, Int?>>.neighbourOf(number: Int): ChannelId? {
    val numbered = mapNotNull { (id, at) -> at?.let { id to it } }
    val next = numbered.filter { it.second > number }.minByOrNull { it.second }
    return (next ?: numbered.filter { it.second < number }.maxByOrNull { it.second })?.first
}

/**
 * What the guide goes by: [onLineup] with each channel's [ChannelSummary.playKey]. The guide shows the days, so a
 * change to how a channel plays (a new play order, a reshuffle) must reload it though no channel or number moved. Live
 * TV and flipping go by [onLineup]: they follow which channels exist and where, and a channel's order reaches them
 * through the schedule they read on the next block, so they need no key.
 */
fun List<ChannelSummary>.onGuide(): List<Triple<ChannelId, Int?, PlayKey>> {
    val on = filter { it.state == ChannelStatus.On }
    return on.map { Triple(it.id, it.number, it.playKey) }
}
