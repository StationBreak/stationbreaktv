// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.playback.Fallback
import tv.stationbreak.core.playback.FallbackCause
import tv.stationbreak.core.playback.ResolvedPlayback
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.TuneResult

/** What the player did during the watch window: stalls and cues seen, and how far the audio clock ran. */
data class Watch(
    val stalls: Int,
    val cues: Int,
    val audioAdvancedMs: Long,
)

/**
 * One item's line in the playback matrix. Every field is a fact about the file, the route or the timing: no item ID,
 * title, URL or error message (those can hold a token or name the owner's library). [error] is a stable string built
 * from the result type and the controller's stable names, never an exception message or class name, so a minified
 * build reads the same.
 */
@Suppress("LongParameterList") // One column of the matrix each.
data class PlaybackResult(
    val section: String,
    val container: String,
    val video: String,
    val audio: String,
    val subtitle: String,
    val route: String,
    val expectedRoute: String,
    val transcodeReasons: String,
    val burnIn: Boolean,
    val fallback: String,
    val requestedMs: Long,
    val positionErrorMs: Long?,
    val firstFrameMs: Long?,
    val playingMs: Long?,
    val stalls: Int,
    val audioOk: Boolean,
    val subtitleOk: Boolean?,
    val pass: Boolean,
    val error: String?,
) {
    /** The RESULT line. [runId] and [ordinal] (the item's index in its batch) let the driver pair it with its plan. */
    fun toJsonLine(
        runId: String,
        ordinal: Int,
    ): String = jsonObject(listOf<Pair<String, Any?>>("run_id" to runId, "n" to ordinal) + fields())

    fun toJsonLine(): String = jsonObject(fields())

    private fun fields(): List<Pair<String, Any?>> =
        listOf(
            "section" to section,
            "container" to container,
            "video" to video,
            "audio" to audio,
            "subtitle" to subtitle,
            "route" to route,
            "expectedRoute" to expectedRoute,
            "transcodeReasons" to transcodeReasons,
            "burnIn" to burnIn,
            "fallback" to fallback,
            "requestedMs" to requestedMs,
            "positionErrorMs" to positionErrorMs,
            "firstFrameMs" to firstFrameMs,
            "playingMs" to playingMs,
            "stalls" to stalls,
            "audioOk" to audioOk,
            "subtitleOk" to subtitleOk,
            "pass" to pass,
            "error" to error,
        )

    companion object {
        /** Media that moved this far during the watch window counts as audible (the window is 15 s). */
        const val AUDIO_ADVANCE_MS = 10_000L
        private const val NONE = "—"

        /**
         * The section that plays the owner's files from a random start. A text subtitle may have no cue in its 15 s
         * window there (a dialogue gap): subtitleOk reports it and the row is not failed for it.
         */
        const val REAL_SECTION = "real"

        /**
         * [expected] is the oracle's route, null when the oracle gave none (nothing played, or the caller passed no
         * oracle, as a unit test does). It is compared with the route abandoned by a fallback, if there was one: a
         * direct play whose seek missed falls back to a transcode by design (ADR-0016, 4), which the Fallback column
         * reports and is not a failure by itself. A fallback after a load failure always fails the row, with its
         * diagnostic in the error. The Route column is the route that finally played.
         */
        fun from(
            section: String,
            outcome: TuneResult,
            watch: Watch,
            expected: Route?,
            requestedMs: Long,
        ): PlaybackResult {
            val played = outcome as? TuneResult.Played
            val resolved = resolvedOf(outcome)
            val chosen = resolved?.chosenSubtitle
            val burnIn = chosen?.delivery == SubtitleDelivery.BURNED_IN
            // Not known when nothing played: a failure is reported by its error, not by a subtitle verdict.
            val subtitleOk = played?.let { subtitleOk(chosen, burnIn, watch) }
            val audioOk = played != null && audioHeard(resolved, watch)
            val route = resolved?.route
            val fallback = fallbackOf(outcome)
            val routeOk = expected == null || (fallback?.from ?: route) == expected
            val positionError = played?.positionError
            val loadFailed = fallback?.cause is FallbackCause.LoadFailed
            val passes =
                played != null && positionError == null && !loadFailed && routeOk && audioOk &&
                    !subtitleFails(section, chosen, subtitleOk)
            return PlaybackResult(
                section = section,
                container = resolved?.media?.container ?: NONE,
                video = videoOf(resolved),
                audio = resolved?.chosenAudio?.let(::describeAudio) ?: NONE,
                subtitle = subtitleClass(chosen),
                route = route?.wire() ?: NONE,
                expectedRoute = expected?.wire() ?: NONE,
                transcodeReasons = reasonsOf(resolved),
                burnIn = burnIn,
                fallback = fallbackName(fallback),
                requestedMs = requestedMs,
                positionErrorMs = positionError?.inWholeMilliseconds,
                firstFrameMs = played?.firstFrameMs,
                playingMs = played?.playingMs,
                stalls = watch.stalls,
                audioOk = audioOk,
                subtitleOk = subtitleOk,
                pass = passes,
                error = errorOf(outcome),
            )
        }

        /** An item with no audio track has nothing to hear. */
        private fun audioHeard(
            resolved: ResolvedPlayback?,
            watch: Watch,
        ): Boolean = resolved?.chosenAudio == null || watch.audioAdvancedMs >= AUDIO_ADVANCE_MS

        private fun videoOf(resolved: ResolvedPlayback?): String =
            resolved
                ?.media
                ?.streams
                ?.firstOrNull { it.kind == StreamKind.VIDEO }
                ?.let(::describeVideo) ?: NONE

        // Jellyfin 12.x reports none here (ruling R8): the dash says "nothing reported", not "none happened".
        private fun reasonsOf(resolved: ResolvedPlayback?): String =
            resolved
                ?.media
                ?.transcodeReasons
                ?.sorted()
                ?.joinToString(",")
                .orEmpty()
                .ifEmpty { NONE }

        private fun resolvedOf(outcome: TuneResult): ResolvedPlayback? =
            when (outcome) {
                is TuneResult.Played -> outcome.resolved
                is TuneResult.Failed -> outcome.resolved
                is TuneResult.SessionExpired -> outcome.resolved
                TuneResult.Superseded, TuneResult.Stopped -> null
            }

        private fun fallbackOf(outcome: TuneResult): Fallback? =
            when (outcome) {
                is TuneResult.Played -> outcome.fallback
                is TuneResult.Failed -> outcome.fallback
                else -> null
            }

        private fun fallbackName(fallback: Fallback?): String =
            when (fallback?.cause) {
                null -> "no"
                FallbackCause.SeekMiss -> "seek-miss"
                is FallbackCause.LoadFailed -> "load-failed"
            }

        private fun errorOf(outcome: TuneResult): String? =
            when (outcome) {
                is TuneResult.Played -> playedError(outcome)
                TuneResult.Superseded -> "superseded"
                TuneResult.Stopped -> "stopped"
                is TuneResult.SessionExpired -> "session-expired"
                is TuneResult.Failed -> failedError(outcome)
            }

        private fun playedError(outcome: TuneResult.Played): String? {
            val cause = outcome.fallback?.cause as? FallbackCause.LoadFailed
            val loaded = cause?.let { listOfNotNull("fallback", "load", it.diagnostic).joinToString(":") }
            val missed = if (outcome.positionError != null) "position-miss" else null
            return listOfNotNull(loaded, missed).joinToString(";").ifEmpty { null }
        }

        private fun failedError(outcome: TuneResult.Failed): String =
            listOfNotNull("failed", outcome.reason.name.lowercase(), outcome.diagnostic).joinToString(":")

        /** Null when there is no subtitle. A text one is shown if its cues came; an image one if it was burned in. */
        private fun subtitleOk(
            chosen: MediaStreamInfo?,
            burnIn: Boolean,
            watch: Watch,
        ): Boolean? =
            when {
                chosen == null -> null

                isImage(chosen) -> burnIn

                // Text: cues seen, or the server burned it into the picture, which leaves no cues to count.
                else -> watch.cues > 0 || burnIn
            }
    }
}

/** Ruling R30: in the real section a text subtitle with no cue is reported (subtitleOk stays false), not graded. */
private fun subtitleFails(
    section: String,
    chosen: MediaStreamInfo?,
    subtitleOk: Boolean?,
): Boolean = subtitleOk == false && !(section == PlaybackResult.REAL_SECTION && chosen != null && !isImage(chosen))
