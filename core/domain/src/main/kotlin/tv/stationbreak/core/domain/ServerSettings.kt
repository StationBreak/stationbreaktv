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
 * the address is another server's, and [clearPin] keeps it.
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
     * Stores [ids] as the chosen libraries, in one atomic write with the record. Returns true when it was stored.
     * Returns false, storing nothing, when there is no record to attach it to, when [ids] is empty (a selection is
     * never empty), or when the write failed.
     */
    suspend fun saveLibraries(ids: Set<ItemId>): Boolean

    /**
     * Drops the library selection and keeps the record and its pin, in one atomic write. True when it was dropped or
     * there was nothing to drop. A new sign-in or a switch of user starts the choice again (S0.5.4).
     */
    suspend fun clearLibraries(): Boolean
}
