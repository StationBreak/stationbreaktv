// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress

/**
 * The exact place a pin applies to: scheme, host and port, in canonical form (spec 05, Unit 3). A pin is looked up by
 * the host and port a connection is being made to, never by "the current server", so it can't follow a redirect to
 * another host. The scheme is part of the key to make "no pin over `http`" visible: there is no way to build a key for
 * it, so there is nothing for a pin to be stored under.
 */
internal data class PinKey private constructor(
    val scheme: Scheme,
    val host: String,
    val port: Int,
) {
    companion object {
        /** The key for an `https` connection to [host]:[port]. The host is lower-cased and loses any IPv6 brackets. */
        fun https(
            host: String,
            port: Int,
        ): PinKey = PinKey(Scheme.Https, host.removePrefix("[").removeSuffix("]").lowercase(), port)

        /** The key for [address], or null for `http`: a pin is never made over cleartext. */
        fun of(address: ServerAddress): PinKey? {
            if (address.scheme != Scheme.Https) return null
            return https(address.host, address.port)
        }
    }

    // Shows nothing: the host is sensitive in diagnostics (S3.6.1).
    override fun toString(): String = "PinKey(scheme=$scheme, port=$port, host=<redacted>)"
}
