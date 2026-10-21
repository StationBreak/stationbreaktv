// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.PlayerEvent
import tv.stationbreak.core.playback.ResolvedPlayback
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.TuneController
import tv.stationbreak.core.playback.TuneResult
import tv.stationbreak.core.playback.VideoPlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Plays one item through the [TuneController] and watches it for [window]: it counts stalls and subtitle cues and
 * measures how far the audio clock moved, then stops the tune. The events are tallied by play generation and the
 * highest generation wins, so the abandoned first attempt of a fallback does not count against the transcode.
 * Nothing here logs; the caller prints the [PlaybackResult].
 */
class PlaybackRun(
    private val controller: TuneController,
    private val player: VideoPlayer,
    private val window: Duration = 15.seconds,
) {
    /** What one play's events add up to. */
    private class Tally {
        var stalls = 0
        var cues = 0
        var firstAudio: Duration? = null
        var lastAudio: Duration? = null

        fun add(event: PlayerEvent) {
            when (event) {
                is PlayerEvent.Stalled -> {
                    stalls++
                }

                is PlayerEvent.Cues -> {
                    if (event.count > 0) cues++
                }

                is PlayerEvent.AudioAdvanced -> {
                    firstAudio = firstAudio ?: event.position
                    lastAudio = event.position
                }

                else -> {
                    Unit
                }
            }
        }

        fun watch(): Watch {
            val advanced = (lastAudio ?: Duration.ZERO) - (firstAudio ?: Duration.ZERO)
            return Watch(stalls, cues, advanced.inWholeMilliseconds)
        }
    }

    /** [expected] is the route the oracle predicts for what was resolved, or null when it has none. */
    suspend fun run(
        session: Session,
        section: String,
        play: Play,
        expected: ((ResolvedPlayback) -> Route?)?,
    ): PlaybackResult =
        coroutineScope {
            val tallies = HashMap<Long, Tally>()
            // Subscribed before the tune starts (the flow replays nothing), and on the caller's thread so no event of
            // the first frame is missed.
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    player.events.collect { tallies.getOrPut(it.generation) { Tally() }.add(it) }
                }
            val outcome = controller.tune(session, ItemId(play.item), play.startMs.milliseconds, play.override)
            if (outcome is TuneResult.Played) {
                delay(window)
                // Any other outcome has already stopped the player and the encode (TuneController).
                controller.stop(session)
            }
            collector.cancelAndJoin()
            val watch = tallies.maxByOrNull { it.key }?.value?.watch() ?: Watch(0, 0, 0)
            val oracle = (outcome as? TuneResult.Played)?.let { expected?.invoke(it.resolved) }
            PlaybackResult.from(section, outcome, watch, oracle, play.startMs)
        }
}
