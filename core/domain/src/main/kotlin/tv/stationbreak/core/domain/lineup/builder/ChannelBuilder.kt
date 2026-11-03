// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId

/** Why a build was not made. Nothing changed. */
sealed interface BuildRefusal {
    /** The draft cannot be saved yet. */
    data class Invalid(
        val problem: DraftProblem,
    ) : BuildRefusal

    data object UnknownChannel : BuildRefusal

    /** The channel is not one the viewer built. */
    data object NotCustom : BuildRefusal

    /** No number is free for a grown-up channel. */
    data object NoFreeNumber : BuildRefusal

    /** No number is free for a kids channel. */
    data object KidsBandFull : BuildRefusal

    data object OutOfRange : BuildRefusal

    /** The channel does not play in a shuffle, so a new seed would change nothing. */
    data object NotShuffle : BuildRefusal

    /** The change would move a channel into or out of the kids band where that is not allowed (the screen explains). */
    data object KidsBand : BuildRefusal

    /** The channel is the only one switched on. */
    data object LastOn : BuildRefusal

    /** Another channel, [holder], has the number. The builder never trades numbers: that is the Channels screen's. */
    data class NumberTaken(
        val holder: ChannelId,
    ) : BuildRefusal
}

/** What a build did. */
sealed interface BuildResult {
    /** Made; [id] is the channel's. */
    data class Done(
        val id: ChannelId,
    ) : BuildResult

    /** The lineup may not be written on this TV, or there is none. */
    data object NotEditable : BuildResult

    /** The change could not be saved, and was not made. */
    data object SaveFailed : BuildResult

    /** Something went wrong; nothing changed. */
    data object Failed : BuildResult

    data class Refused(
        val reason: BuildRefusal,
    ) : BuildResult
}

/** What opening a channel for the builder found. */
sealed interface OpenResult {
    /**
     * For a copy, [ChannelDraft.name] is the built-in's own name and [builtInName] repeats it; the screen formats the
     * "(copy)" name from `builder_copy_suffix`.
     */
    data class Opened(
        val draft: ChannelDraft,
        val builtInName: String?,
    ) : OpenResult

    /** The channel has no rules the builder can show (a leftovers channel, one that needs a newer app). */
    data object NotCustomisable : OpenResult

    data object NotFound : OpenResult

    /** The lineup could not be read; the channel may well be there. */
    data object Failed : OpenResult
}

/** How features build channels (spec 22). None of it throws, but for cancellation. */
interface ChannelBuilder {
    /**
     * A blank draft with a fresh seed and the first free number for grown-up (or kids) channels, null number if none
     * is free (a preview number only; create works the number out again inside the lock). A kids draft's ceiling is
     * one the kids hold knows, as in every draft [open] returns.
     */
    suspend fun blank(
        account: LibraryAccount,
        kids: Boolean,
    ): ChannelDraft

    /**
     * The draft of a custom channel to edit, or of a copy of a built-in channel (a new number; the name is the
     * built-in's own, the screen suffixes it).
     */
    suspend fun open(
        account: LibraryAccount,
        id: ChannelId,
        copy: Boolean,
    ): OpenResult

    /**
     * Creates the channel of [draft]. A draft with kept settings reads them from the rules of its
     * [ChannelDraft.origin]; with none, or one that is gone, it is refused with [BuildRefusal.UnknownChannel].
     */
    suspend fun create(
        account: LibraryAccount,
        draft: ChannelDraft,
    ): BuildResult

    /**
     * Saves [draft] over channel [id], keeping its stored seed. [id]
     * wins over [ChannelDraft.origin]: the kept settings are read from the stored rules of [id].
     */
    suspend fun save(
        account: LibraryAccount,
        id: ChannelId,
        draft: ChannelDraft,
    ): BuildResult

    /**
     * Sends a new seed and returns Done; the screen then reopens the draft, with the new seed and preview. A channel
     * that does not play in a shuffle is refused with [BuildRefusal.NotShuffle], under the lineup's lock.
     */
    suspend fun reshuffle(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult

    suspend fun delete(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult

    /**
     * Every number free for a channel of the band ([kids] or not), lowest first: no channel has it as its number, its
     * default or an override, and the catalog has not retired it. A blank draft and a kids switch take the first.
     * With [forChannel] (the channel being edited), that channel's own current number counts as free too when it is in
     * the asked-for band. [Listed.Unavailable] when the lineup can't be read.
     */
    suspend fun freeNumbers(
        account: LibraryAccount,
        kids: Boolean,
        forChannel: ChannelId? = null,
    ): Listed<List<Int>>
}
