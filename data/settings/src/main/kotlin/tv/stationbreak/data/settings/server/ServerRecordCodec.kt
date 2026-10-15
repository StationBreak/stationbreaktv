// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin

/** Everything the settings file holds: the [record] and, once chosen, the [libraries] picked on that server. */
internal data class StoredSettings(
    val record: ServerRecord,
    val libraries: Set<ItemId>? = null,
)

/**
 * The stored text of the one [ServerRecord]: `key=value` lines, the version first. The address and the pin are in the
 * same text so they are written in one atomic replace and can never disagree. Nothing here is a secret, and a reader
 * that finds anything it did not write gets null rather than a guess. A key it does not know is ignored, so a later
 * version can add one. The library selection is one more line (`libraries=` and comma-separated item IDs, which are
 * letters, numbers, `-` and `_` only), so it is replaced in the same write as the address; a file written before it
 * existed simply has none.
 */
internal object ServerRecordCodec {
    private const val VERSION = "1"

    /**
     * [value] if it can sit in a `key=value` line without forging another: no line break and no `=`. Defence in depth,
     * since the address and the item IDs refuse these already.
     */
    fun checked(value: String): String {
        require(value.none { it == '\n' || it == '\r' || it == '=' }) { "Not storable" }
        return value
    }

    fun encode(record: ServerRecord): String = encode(StoredSettings(record))

    fun encode(stored: StoredSettings): String =
        buildList {
            val record = stored.record
            add("version=$VERSION")
            add("scheme=${checked(record.address.scheme.text)}")
            add("host=${checked(record.address.host)}")
            add("port=${record.address.port}")
            add("subpath=${checked(record.address.subpath)}")
            add("viaFallback=${record.address.viaFallback}")
            record.pin?.let { add("pin=${it.hex}") }
            stored.libraries?.let { ids -> add("libraries=${ids.joinToString(",") { checked(it.value) }}") }
        }.joinToString("\n")

    fun decode(text: String): ServerRecord? = decodeStored(text)?.record

    fun decodeStored(text: String): StoredSettings? {
        val values =
            text
                .lines()
                .filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        val record = if (values["version"] == VERSION) recordOf(values) else null
        // A selection that can't be read is as good as none: the picker asks again. The record is still good.
        return record?.let { StoredSettings(it, values["libraries"]?.let(::librariesOf)) }
    }

    private fun librariesOf(text: String): Set<ItemId>? =
        try {
            text
                .split(',')
                .map(::ItemId)
                .toSet()
                .takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
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
