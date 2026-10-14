// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ServerAddress

/**
 * The cleartext rule (ADR-0014, Angua (Security) R7) as one pure function, so every adapter applies the same test.
 * An address that needs confirmation is usable only with an acknowledgement built from that exact address; one
 * given for another host, port, scheme or subpath doesn't count.
 */
object CleartextGate {
    /** The error to return before sending anything to [address], or null when the call may go ahead. */
    fun check(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): MediaServerError.CleartextNotConfirmed? =
        if (!address.cleartextNeedsConfirmation || acknowledgement?.matches(address) == true) {
            null
        } else {
            MediaServerError.CleartextNotConfirmed()
        }
}
