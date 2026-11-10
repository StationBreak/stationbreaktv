// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.view.SurfaceView
import androidx.media3.common.text.Cue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * What the player reports, as a hot stream for the player's lifetime (not per play). Times in `atMs` come from the
 * clock the player was given. Nothing here carries a URI, a URL or an exception message: they may hold a token.
 */
sealed interface PlayerEvent {
    /** The number [VideoPlayer.play] returned for the play that produced this event. */
    val generation: Long

    /**
     * The first picture of a play. [skippedFrames] is how many video frames the decoder decoded and dropped since this
     * play began, to reach an exact seek position; null when the player cannot say.
     */
    data class FirstFrame(
        val atMs: Long,
        override val generation: Long,
        val skippedFrames: Long? = null,
    ) : PlayerEvent

    /**
     * The container is parsed: Media3 reported the play's tracks for the first time. Sent once per play. [loads] is
     * what the media loads up to now cost (diagnostics only); null when the player cannot say.
     */
    data class TracksReady(
        val atMs: Long,
        override val generation: Long,
        val loads: LoadStats? = null,
    ) : PlayerEvent

    /** Playback is running at [position] within the stream. [av] times the audio and video start-up steps, if known. */
    data class Playing(
        val atMs: Long,
        val position: Duration,
        override val generation: Long,
        val av: AvTimings? = null,
    ) : PlayerEvent

    /**
     * Playback stopped to buffer again after it had begun. [atMs], [positionMs] (the player's position) and
     * [bufferedMs] (how much is buffered ahead) are [NOT_KNOWN] when the player cannot say (diagnostics only).
     */
    data class Stalled(
        override val generation: Long,
        val atMs: Long = NOT_KNOWN,
        val positionMs: Long = NOT_KNOWN,
        val bufferedMs: Long = NOT_KNOWN,
    ) : PlayerEvent

    /** The media played to its end. Sent once, when the player reaches the end of the stream. */
    data class Ended(
        override val generation: Long,
    ) : PlayerEvent

    data class Cues(
        val count: Int,
        override val generation: Long,
    ) : PlayerEvent

    /**
     * A sample of the audio clock. One when audio first advances, and one more at stop if it did. The playing position
     * follows the audio clock, so the two samples bracket how far the audio ran.
     */
    data class AudioAdvanced(
        val position: Duration,
        override val generation: Long,
    ) : PlayerEvent

    /**
     * [errorCode] is the stable name of the Media3 error code (`ERROR_CODE_...`), never a class name or a message.
     * [httpStatus] is set when a media load was answered with an HTTP error. [atMs], [positionMs] and [bufferedMs]
     * are as for [Stalled] (diagnostics only).
     */
    data class Failed(
        val errorCode: String,
        val httpStatus: Int?,
        override val generation: Long,
        val atMs: Long = NOT_KNOWN,
        val positionMs: Long = NOT_KNOWN,
        val bufferedMs: Long = NOT_KNOWN,
    ) : PlayerEvent

    /** [VideoPlayer.pause] paused the play at [positionMs]. */
    data class Paused(
        override val generation: Long,
        val atMs: Long,
        val positionMs: Long,
    ) : PlayerEvent

    /** The play was asked to play on after a pause; [PlayerEvent.Playing] follows when it does. */
    data class Resumed(
        override val generation: Long,
        val atMs: Long,
    ) : PlayerEvent

    /** [VideoPlayer.seekTo] moved the play from [fromMs] towards [toMs]. */
    data class Seeking(
        override val generation: Long,
        val atMs: Long,
        val fromMs: Long,
        val toMs: Long,
    ) : PlayerEvent

    /** The player is ready at the seek target again (playing or paused). Exactly one per [Seeking]. */
    data class SeekDone(
        override val generation: Long,
        val atMs: Long,
        val positionMs: Long,
    ) : PlayerEvent

    /**
     * Spec 24: what became of the warm standby at the play [generation]. Sent once by every play that was given a
     * [PlayerSource.warmKey], from inside that play on the player's looper, and before any other event of the play; a
     * play with no key sends none (the caller knows it gave none).
     */
    data class WarmDecided(
        override val generation: Long,
        val outcome: WarmOutcome,
    ) : PlayerEvent

    companion object {
        /** A number the player could not give. */
        const val NOT_KNOWN = -1L
    }
}

/**
 * What became of the warm standby at a play given a warm key ([PlayerEvent.WarmDecided], spec 24). [swapped] is true
 * when the play was a hand-over to the standby; then [reason] is `None`. Otherwise [reason] says why the play was
 * cold; the player only gives the reasons [WarmReason] marks as the player's. [ageMs] is
 * how long the standby had been ready (or, not ready, since it was asked for), [readyMs] how long it took to become
 * ready (0 if it never did) and [detachMs] how long the old front took to let go of the surface (0 when cold).
 */
data class WarmOutcome(
    val swapped: Boolean,
    val reason: WarmReason,
    val ageMs: Long,
    val readyMs: Long,
    val detachMs: Long,
)

/** One player for the host's lifetime: [play] replaces what is playing, [stop] ends it, [release] ends the player. */
@Suppress("TooManyFunctions") // Spec 24 added the warm standby's calls beside the transport.
interface VideoPlayer {
    val events: SharedFlow<PlayerEvent>

    /**
     * The shape the picture is shown in: width over height, with the pixel shape applied (an anamorphic 720x480 with a
     * 32:27 pixel is 16:9). Null until the first size is known. The last value is kept across plays, a stop and a
     * flip until the next item reports its size, so a screen sized by it never falls back to stretching. Rotation is
     * not applied here: rendering to a surface turns the picture itself, and Media3 then reports none left to apply.
     */
    val videoAspect: StateFlow<Float?>

    /**
     * The caption cues to draw over the picture now: the text (or picture) subtitle the player has selected and is
     * showing at this moment, empty when none shows. It is cleared when a play starts, and when the player stops or
     * is released, so one play's last line never stays up over the next. Media3's own cue type, because the view that
     * draws them takes it. A player with no captions answers an empty list.
     */
    val cues: StateFlow<List<Cue>> get() = NO_CUES

    /**
     * Starts [source], replacing what is playing, and returns the generation of this play. Every event the play
     * produces carries it, so a caller can ignore the events of an earlier play. Generations only increase.
     */
    fun play(source: PlayerSource): Long

    /** Ends what is playing. A second call is a no-op, and implementations must keep it so: teardown calls it twice. */
    fun stop()

    /**
     * Shows the video on [surface], replacing any earlier one; null detaches it. Runs on the player's looper, so it
     * may be called from any thread, and it works before the first [play]. The app attaches through this, so Media3
     * types stay inside this module.
     */
    fun attach(surface: SurfaceView?)

    fun release()

    /**
     * Pauses the play [generation]; nothing when it is not the play on the player, or is already paused. Emits
     * [PlayerEvent.Paused].
     */
    fun pause(generation: Long)

    /**
     * Plays on from where [generation] paused; nothing when it is not the play on the player, or is not paused. Emits
     * [PlayerEvent.Resumed] at once, then Playing.
     */
    fun resume(generation: Long)

    /**
     * Seeks [generation] to [position] in the stream, playing or paused as it was. Emits Seeking, then SeekDone. A seek
     * called while one is pending is coalesced: it moves the target and emits no second Seeking, so there is one
     * SeekDone per Seeking, at the final position. After a seek during playback, Playing follows once it plays on, as
     * after a rebuffer.
     */
    fun seekTo(
        generation: Long,
        position: Duration,
    )

    /**
     * The position of [generation] as of the last snapshot the player published; null when it is not the play on the
     * player (a newer play, or after [stop]) or the player cannot say. The snapshot is taken on the player's looper at
     * events and transport calls only, so during steady playback this is NOT "now": use [currentPosition] for the
     * live value. It is generation-safe from any thread: a snapshot of another play is never returned. Called from
     * another thread straight after [play], it is null until the looper has run the play.
     */
    fun position(generation: Long): Duration?

    /**
     * The live, exact position of [generation], read on the player's looper whatever the caller's thread; null when
     * it is not the play on the player (a newer play, or after [stop]) or the player cannot say.
     */
    suspend fun currentPosition(generation: Long): Duration?

    /** True when [generation] is on the player and its media can seek; false otherwise, after [stop], or unknown. */
    fun seekable(generation: Long): Boolean

    /**
     * Spec 24: prepares [source] on a hidden, muted, paused standby player, and returns the handle the player issued
     * for it: `w<n>`, from the player's own counter (from 1), never derived from the source. A later [play] of that
     * source stamped with it (`source.copy(warmKey = handle)`) hands the surface over to the standby when it is ready.
     * The handle is bound to the source: a play of another source with it (anything but the start position differs)
     * is cold, a [WarmReason.Mismatch].
     *
     * Idempotent per source: warming the source the standby already holds (the start aside), while it has not
     * failed, returns the same handle and prepares nothing again. Another source gets a new handle and replaces the
     * held standby. It does not touch what is playing. Returns at once; the work runs on the player's looper. A
     * player that cannot warm still issues a handle: the play stamped with it is cold, and [PlayerEvent.WarmDecided]
     * says why.
     *
     * Call [warm] from the player's looper (the main thread, where [play] is called). Both post to the looper in
     * order, so a warm returned before a play always runs first from any thread. What another thread costs is the
     * same-handle answer, which is exact only on the looper: from another thread every call issues a new handle and
     * builds the standby again.
     */
    fun warm(source: PlayerSource): String

    /** Drops the standby if it is held for the handle [key] (not yet played); nothing otherwise. */
    fun dropWarm(key: String)

    /**
     * A memory trim signal (`ComponentCallbacks2`), every level: a running level below [WarmConfig.warmTrimLevel]
     * (running-moderate, 5) is only logged; from it up (running-low, 10, by default) the standby is released and
     * warming is paused for the rest of this player's life (the visit); the UI-hidden level (20) and above release the
     * standby but do not pause warming.
     */
    fun trimMemory(level: Int)

    companion object {
        /** A warm handle no player issues (their counters start at 1): a play stamped with it is never warm. */
        const val NO_WARM_HANDLE = "w0"

        /** What a player with no captions answers: no cues, for good. */
        val NO_CUES: StateFlow<List<Cue>> = MutableStateFlow(emptyList<Cue>()).asStateFlow()
    }
}
