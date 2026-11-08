// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin

/**
 * The one configured server: its [address] and, if the user trusted a self-signed certificate for it, that
 * certificate's [pin]. They are one record so they are written together and can never disagree (spec 05, Unit 3). A
 * pin is not a secret, so this lives in settings, not the secret store. A pin is never made over `http`.
 */
data class ServerRecord(
    val address: ServerAddress,
    val pin: SpkiPin? = null,
) {
    init {
        require(pin == null || address.scheme == Scheme.Https) { "A pin is never made over http" }
    }
}

/**
 * The port to the stored server configuration (spec 05, Units 3 and 4). Nothing here throws for a storage fault: a
 * read that can't be answered is `null`, and a write reports whether it reached storage.
 *
 * Editing the address, or testing it again, clears the pin ([clearPin]); signing out does not touch it, because the
 * address is kept.
 *
 * The library selection (S0.5.4) lives beside the record and is written with it, so the two can never disagree about
 * which server the choice was made on: [save] keeps the selection when the address is the same one and drops it when
 * the address is another server's (keeping the seq and the counters, see [loadSelectionSeq]), and [clearPin] keeps it.
 *
 * Everyone reads the selection here; only the selection writes it, through [SelectionWriter] (spec 26, Part 4: one
 * writer, so its lock orders every selection write with the repair record and the library cache).
 */
interface ServerSettings {
    /** The stored record, or null when none was saved (or it couldn't be read). */
    suspend fun load(): ServerRecord?

    /** Replaces the whole record in one atomic write. Returns true when it was stored. */
    suspend fun save(record: ServerRecord): Boolean

    /** Keeps the address and drops its pin, in one atomic write. A no-op when there is no pin. */
    suspend fun clearPin(): Boolean

    /** The libraries the user chose on the stored server, or null when none was saved (or it couldn't be read). */
    suspend fun loadLibraries(): Set<ItemId>?

    /**
     * The whole stored selection (spec 26): the chosen libraries with their seq, the libraries being added, whether the
     * selection is gone, the libraries a listing missed, and the counters. Null when nothing is chosen, when there is
     * no record, or when the file can't be read. Served from memory after the first read: a selection read never waits
     * on a writer, because it is on the tune path.
     */
    suspend fun loadSelection(): StoredSelection?

    /**
     * The stored seq, which outlives the selection: [SelectionWriter.clearLibraries] and [save] for another address
     * keep it. 0 when no seq was ever stored. Null when there is no record or the file can't be read. A new selection
     * saved after a clear must carry a seq above this one.
     */
    suspend fun loadSelectionSeq(): Long?

    /**
     * The playable libraries: the chosen ones, or null when the selection is gone or none is stored. Never the
     * libraries being added. What plays from the saved library reads them: the schedule (and a repair's match or
     * fill), the refresher, stay current's passes, the channel editor and the previews. One read of the same memory as
     * [loadSelection], so it never waits on a writer. A caller that would take null as "no filter" (the builder's
     * library picker) reads [loadLibraries] instead.
     */
    suspend fun loadPlayableLibraries(): Set<ItemId>?
}

/**
 * The writes of the library selection (spec 26, Part 4), apart from [ServerSettings] so that only the selection's one
 * writer (`LibrarySelection`) is given them. The stores implement both ([SelectionStore]).
 */
interface SelectionWriter {
    /**
     * Drops the library selection and keeps the record and its pin, in one atomic write. True when it was dropped or
     * there was nothing to drop. A new sign-in or a switch of user starts the choice again (S0.5.4). The seq, the reset
     * seq and the listing count are kept, so a seq is never reused.
     */
    suspend fun clearLibraries(): Boolean

    /**
     * Replaces the stored selection with [selection] in one atomic write with the record. Returns false, storing
     * nothing, when there is no record, when the write failed, or when [selection] breaks a rule of
     * [StoredSelection.mayReplace] (an empty or overlapping choice, a stamp above its seq, a seq below the stored one).
     */
    suspend fun saveSelection(selection: StoredSelection): Boolean
}

/** A store of the server settings that also takes the selection's writes: what the app binds both ports to. */
interface SelectionStore :
    ServerSettings,
    SelectionWriter

/**
 * The first time a listing missed a chosen or adding library: when ([sinceMs]), and in which counted listing
 * ([sincePass]).
 */
data class Missing(
    val sinceMs: Long,
    val sincePass: Long,
)

/**
 * The stored library selection (spec 26, Part 4).
 *
 * - [chosen]: the libraries on the TV's channels. Never empty in a stored selection.
 * - [seq]: the seq of the last selection change. It never goes back: a clear or another address keeps it.
 * - [adding]: the libraries being added, each with its stamp (the seq of the change that ticked it). Never in [chosen].
 * - [gone]: the server no longer lists any chosen library. [chosen] is kept; nothing is playable.
 * - [missing]: chosen and adding libraries a listing omitted, with their first miss.
 * - [listings]: how many successful listings were counted. Kept by a clear.
 * - [resetSeq]: the seq the last setup saved (settings key `selectionResetSeq`). A selection repair below it predates
 *   that setup, so a build that reads it can hide such repairs without trusting the clock. Kept by a clear.
 */
data class StoredSelection(
    val chosen: Set<ItemId>,
    val seq: Long = 0,
    val adding: Map<ItemId, Long> = emptyMap(),
    val gone: Boolean = false,
    val missing: Map<ItemId, Missing> = emptyMap(),
    val listings: Long = 0,
    val resetSeq: Long = 0,
) {
    /** The rules a stored selection keeps on its own: what any reader of the store may rely on. */
    val isWellFormed: Boolean
        get() =
            chosen.isNotEmpty() &&
                seq >= 0 &&
                listings >= 0 &&
                resetSeq in 0..seq &&
                adding.keys.none { it in chosen } &&
                adding.values.all { it in 0..seq } &&
                missing.keys.all { it in chosen || it in adding } &&
                missing.values.all { it.sinceMs >= 0 && it.sincePass >= 0 }

    /**
     * Whether this selection may replace what is stored: [stored] (null when nothing is chosen), whose seq is
     * [storedSeq] and whose reset seq is [storedResetSeq] (both kept when the selection is dropped). It must be well
     * formed, its seq at or above the stored one, and its reset seq never below the stored one. An equal seq is only
     * for bookkeeping, [missing] and [listings]: anything else is a change and takes a new seq.
     */
    fun mayReplace(
        stored: StoredSelection?,
        storedSeq: Long,
        storedResetSeq: Long,
    ): Boolean =
        isWellFormed &&
            resetSeq >= storedResetSeq &&
            when {
                seq > storedSeq -> {
                    true
                }

                seq < storedSeq -> {
                    false
                }

                else -> {
                    stored != null &&
                        chosen == stored.chosen &&
                        adding == stored.adding &&
                        gone == stored.gone &&
                        resetSeq == stored.resetSeq
                }
            }
}
