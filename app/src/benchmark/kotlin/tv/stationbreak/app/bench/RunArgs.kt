// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import tv.stationbreak.core.playback.TrackOverride

/** One item to play: the server's item ID (never logged), where to start, and the track choice if any. */
data class Play(
    val item: String,
    val startMs: Long,
    val override: TrackOverride?,
)

/**
 * What one run was asked to play, in order. It comes from the launching intent's extras (see [PlaybackTestActivity]).
 * [parse] takes the raw arrays so it needs no Android type: [audio] and [subtitles] are per-item indexes where -1 means
 * none, and they apply only when [override] is true. With the override on, an audio of -1 leaves the choice to the
 * server and a subtitle of -1 turns subtitles off.
 */
data class RunArgs(
    val plays: List<Play>,
    val section: String,
    val runId: String,
) {
    companion object {
        const val MAX_ITEMS = 200
        const val MAX_LABEL = 64

        /** Throws [IllegalArgumentException] for a missing item list or an array whose length is not the item count. */
        @Suppress("LongParameterList") // The extras, one for one.
        fun parse(
            items: Array<String>?,
            startsMs: LongArray?,
            audio: IntArray?,
            subtitles: IntArray?,
            override: Boolean,
            section: String?,
            runId: String?,
        ): RunArgs {
            require(!items.isNullOrEmpty()) { "no items to play" }
            require(items.size <= MAX_ITEMS) { "more than $MAX_ITEMS items" }
            require(startsMs == null || startsMs.all { it >= 0 }) { "a negative start" }
            require(startsMs == null || startsMs.size == items.size) { "starts_ms has ${startsMs?.size} entries" }
            require(audio == null || audio.size == items.size) { "audio has ${audio?.size} entries" }
            require(subtitles == null || subtitles.size == items.size) { "subtitles has ${subtitles?.size} entries" }
            val plays =
                items.mapIndexed { i, item ->
                    val track =
                        if (override) {
                            TrackOverride(audio?.get(i)?.takeIf { it >= 0 }, subtitles?.get(i)?.takeIf { it >= 0 })
                        } else {
                            null
                        }
                    Play(item, startsMs?.get(i) ?: 0L, track)
                }
            val name = section.orEmpty().take(MAX_LABEL).ifBlank { "unnamed" }
            return RunArgs(plays, name, runId.orEmpty().take(MAX_LABEL).ifBlank { "none" })
        }
    }
}
