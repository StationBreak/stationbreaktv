// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin

/**
 * The stored text of the one [ServerRecord]: `key=value` lines, the version first. The address and the pin are in the
 * same text so they are written in one atomic replace and can never disagree. Nothing here is a secret, and a reader
 * that finds anything it did not write gets null rather than a guess. A key it does not know is ignored, so a later
 * version can add one.
 */
internal object ServerRecordCodec {
    private const val VERSION = "1"

    fun encode(record: ServerRecord): String =
        buildList {
            add("version=$VERSION")
            add("scheme=${record.address.scheme.text}")
            add("host=${record.address.host}")
            add("port=${record.address.port}")
            add("subpath=${record.address.subpath}")
            add("viaFallback=${record.address.viaFallback}")
            record.pin?.let { add("pin=${it.hex}") }
        }.joinToString("\n")

    fun decode(text: String): ServerRecord? {
        val values =
            text
                .lines()
                .filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        return if (values["version"] == VERSION) recordOf(values) else null
    }

    // ServerAddress and ServerRecord refuse what they should not hold (a bad host or port, a pin over http).
    private fun recordOf(values: Map<String, String>): ServerRecord? =
        try {
            val scheme = Scheme.entries.first { it.text == values["scheme"] }
            val address =
                ServerAddress(
                    scheme = scheme,
                    host = values.getValue("host"),
                    port = values.getValue("port").toInt(),
                    subpath = values["subpath"].orEmpty(),
                    viaFallback = values["viaFallback"] == "true",
                )
            val pin = values["pin"]?.let { SpkiPin.fromHex(it) ?: return null }
            ServerRecord(address, pin)
        } catch (_: NoSuchElementException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
