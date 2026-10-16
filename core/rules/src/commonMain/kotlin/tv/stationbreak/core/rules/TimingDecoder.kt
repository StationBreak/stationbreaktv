// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val EPOCH_HOURS = setOf(1, 2, 3, 4, 6, 8, 12, 24)
private val START_TIME = Regex("^([01][0-9]|2[0-3]):(00|30)$")
private const val MAX_BLOCK_LENGTH = 100
private const val MIN_OFFSET = -720
private const val MAX_OFFSET = 840
private const val OFFSET_STEP = 15

/** Reads the `timing` object and the `broadcastDay` inside it. */
internal object TimingDecoder {
    fun timing(element: JsonElement): Timing {
        val obj = element as? JsonObject ?: invalid("timing must be an object")
        val epochHours = obj["epochHours"]?.let { int(it, "timing.epochHours") }
        if (epochHours != null && epochHours !in EPOCH_HOURS) invalid("timing.epochHours must be on the list")
        return Timing(
            blockLength = obj["blockLength"]?.let { ranged(it, "timing.blockLength", 1, MAX_BLOCK_LENGTH) },
            alignToGrid = obj["alignToGrid"]?.let { boolean(it, "timing.alignToGrid") },
            epochHours = epochHours,
            broadcastDay = broadcastDay(required(obj, "broadcastDay", "timing")),
            extras = extras(obj, setOf("blockLength", "alignToGrid", "epochHours", "broadcastDay")),
        )
    }

    private fun broadcastDay(element: JsonElement): BroadcastDay {
        val obj = element as? JsonObject ?: invalid("timing.broadcastDay must be an object")
        val path = "timing.broadcastDay"
        val offset = ranged(required(obj, "utcOffsetMinutes", path), "$path.utcOffsetMinutes", MIN_OFFSET, MAX_OFFSET)
        if (offset % OFFSET_STEP != 0) invalid("$path.utcOffsetMinutes must be a multiple of 15")
        val start = obj["start"]?.let { string(it, "$path.start") }
        if (start != null && !START_TIME.matches(start)) invalid("$path.start must be HH:MM on the hour or half hour")
        return BroadcastDay(offset, start, extras(obj, setOf("utcOffsetMinutes", "start")))
    }
}
