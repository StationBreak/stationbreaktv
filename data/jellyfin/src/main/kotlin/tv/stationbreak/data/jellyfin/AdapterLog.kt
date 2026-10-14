// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

/**
 * Where the adapter's own log statements go. A seam, no-op by default, so S3.6.1's redacting logger plugs in without
 * the adapter knowing. Every statement is a fixed phrase and an error's type name: never a header, a body, a URL,
 * a host, a username or a secret (R18).
 */
fun interface AdapterLog {
    fun log(message: String)

    companion object {
        val NONE = AdapterLog { }
    }
}
