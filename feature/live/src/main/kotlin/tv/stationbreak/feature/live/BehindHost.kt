// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.logName
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.playback.PlayNote

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
