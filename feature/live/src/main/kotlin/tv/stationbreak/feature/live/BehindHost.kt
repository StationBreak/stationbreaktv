// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.logName
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.playback.PlayNote
import kotlin.time.Instant

/** What a [BehindPlay] needs of the sitting it runs in, and nothing more (spec 20, ADR-0021 9). */
internal interface BehindHost {
    val timings: LiveTimings

    /** The channel being watched, for the kept place. */
    val channel: ChannelId

    /** How far ahead of the clock the sitting runs (milliseconds). */
    val driftMs: Long

    /** The clock now (epoch ms). */
    fun nowMs(): Long

    /** The sitting's time now: the clock plus its drift. */
    fun vnowMs(): Long

    /** The next transport op for the block that started at [blockStartMs]. */
    suspend fun receive(blockStartMs: Long): TransportOp

    fun report(line: TuneReport)

    /** What is on the channel at the sitting's time [atMs]; null when nothing is (the channel is lost). */
    suspend fun onAir(atMs: Long): ChannelOnAir?

    /** Drops the block's wall-clock look-ahead: the viewer is behind. */
    suspend fun dropBoundaryLookAhead()

    /** Resolves [block]'s program ahead for its position at [atMs]; null when there is nothing to resolve. */
    suspend fun resolveCatchUp(
        block: AiringBlock,
        atMs: Long,
    ): Pending.CatchUp?

    /** Ends a catch-up handle that will not be played. */
    suspend fun discard(handle: Pending.CatchUp)

    /** A `press dropped` line with [reason] and the state on screen. */
    fun dropped(reason: String)

    /** Shows [view] as the transport, or none. */
    fun publishTransport(view: TransportView?)

    /** Runs [body] while the play's [notes] are reported (to the playback reporter too when [reporting]). */
    suspend fun <T> watchNotes(
        notes: Flow<PlayNote>,
        reporting: Boolean,
        drain: (T) -> Boolean,
        body: suspend () -> T,
    ): T
}

/** This sitting as a [BehindHost]. */
internal fun Sitting.behindHost(): BehindHost {
    val sitting = this
    return object : BehindHost {
        override val timings: LiveTimings get() = sitting.timings
        override val channel: ChannelId get() = sitting.channel
        override val driftMs: Long get() = sitting.driftMs

        override fun nowMs(): Long = sitting.clock.now().toEpochMilliseconds()

        override fun vnowMs(): Long = sitting.vnowMs()

        override suspend fun receive(blockStartMs: Long): TransportOp = sitting.commands.receive(blockStartMs)

        override fun report(line: TuneReport) = sitting.report(line)

        override suspend fun onAir(atMs: Long): ChannelOnAir? =
            sitting.schedule.onAir(sitting.channel, Instant.fromEpochMilliseconds(atMs), Sitting.WINDOW)

        override suspend fun dropBoundaryLookAhead() = sitting.dropBoundaryLookAhead()

        override suspend fun resolveCatchUp(
            block: AiringBlock,
            atMs: Long,
        ): Pending.CatchUp? = sitting.nextUp.resolveCatchUp(block, atMs)

        override suspend fun discard(handle: Pending.CatchUp) = sitting.nextUp.discard(handle)

        override fun dropped(reason: String) =
            sitting.report(
                TuneReport.PressDropped(
                    reason,
                    sitting.state.value.javaClass
                        .logName(),
                ),
            )

        override fun publishTransport(view: TransportView?) {
            sitting.state.transport.value = view
        }

        override suspend fun <T> watchNotes(
            notes: Flow<PlayNote>,
            reporting: Boolean,
            drain: (T) -> Boolean,
            body: suspend () -> T,
        ): T = sitting.watchNotes(notes, sitting.reporter.takeIf { reporting }, drain, body)
    }
}
