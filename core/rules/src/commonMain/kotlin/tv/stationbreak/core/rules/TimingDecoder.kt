// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val MAX_BLOCK = RulesVocabulary.MAX_BLOCK_LENGTH

/** Reads the `timing` object and the `broadcastDay` inside it. */
internal object TimingDecoder {
    fun timing(element: JsonElement): Timing {
        val obj = element as? JsonObject ?: invalid("timing must be an object")
        val epochHours = obj["epochHours"]?.let { int(it, "timing.epochHours") }
        val hours = RulesVocabulary.EPOCH_HOURS
        if (epochHours != null && epochHours !in hours) invalid("timing.epochHours must be on the list")
        val grid = obj["gridMinutes"]?.let { int(it, "timing.gridMinutes") }
        if (grid != null && grid !in RulesVocabulary.GRID_MINUTES) invalid("timing.gridMinutes must be 15, 30 or 60")
        val broadcastDay = broadcastDay(required(obj, "broadcastDay", "timing"))
        if (grid == RulesVocabulary.HOURLY_GRID && !broadcastDay.effectiveStart.endsWith(":00")) {
            invalid("timing.broadcastDay.start must be on the hour when timing.gridMinutes is 60")
        }
        return Timing(
            blockLength = obj["blockLength"]?.let { ranged(it, "timing.blockLength", 1, MAX_BLOCK) },
            alignToGrid = obj["alignToGrid"]?.let { boolean(it, "timing.alignToGrid") },
            epochHours = epochHours,
            graceHours = obj["graceHours"]?.let { ranged(it, "timing.graceHours", 0, RulesVocabulary.MAX_GRACE_HOURS) },
            gridMinutes = grid,
            minRuntimeSeconds =
                obj["minRuntimeSeconds"]?.let {
                    ranged(it, "timing.minRuntimeSeconds", 1, RulesVocabulary.MAX_MIN_RUNTIME_SECONDS)
                },
            broadcastDay = broadcastDay,
            extras = extras(obj, RulesVocabulary.TIMING),
        )
    }

    private fun broadcastDay(element: JsonElement): BroadcastDay {
        val obj = element as? JsonObject ?: invalid("timing.broadcastDay must be an object")
        val path = "timing.broadcastDay"
        val offset =
            ranged(
                required(obj, "utcOffsetMinutes", path),
                "$path.utcOffsetMinutes",
                RulesVocabulary.MIN_OFFSET,
                RulesVocabulary.MAX_OFFSET,
            )
        if (offset % RulesVocabulary.OFFSET_STEP != 0) invalid("$path.utcOffsetMinutes must be a multiple of 15")
        val start = obj["start"]?.let { string(it, "$path.start") }
        val validStart = start == null || RulesVocabulary.START_TIME.matches(start)
        if (!validStart) invalid("$path.start must be HH:MM on a half hour")
        return BroadcastDay(offset, start, extras(obj, setOf("utcOffsetMinutes", "start")))
    }
}
