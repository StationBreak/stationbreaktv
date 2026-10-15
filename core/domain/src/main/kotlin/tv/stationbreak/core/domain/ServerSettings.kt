// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

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
 */
interface ServerSettings {
    /** The stored record, or null when none was saved (or it couldn't be read). */
    suspend fun load(): ServerRecord?

    /** Replaces the whole record in one atomic write. Returns true when it was stored. */
    suspend fun save(record: ServerRecord): Boolean

    /** Keeps the address and drops its pin, in one atomic write. A no-op when there is no pin. */
    suspend fun clearPin(): Boolean
}
