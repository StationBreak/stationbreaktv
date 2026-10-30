// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.model.ChannelId

/** Which way a seek key steps (spec 20): back by `LiveTimings.stepBack`, ahead by `LiveTimings.stepAhead`. */
enum class SeekDirection { Back, Ahead }

/** What a viewer's transport key asks of the sitting (spec 20, ADR-0021 decision 9). */
internal sealed interface TransportOp {
    data object Toggle : TransportOp

    data object Play : TransportOp

    data object Pause : TransportOp

    data class Step(
        val direction: SeekDirection,
        val repeat: Boolean,
        val atMs: Long?,
    ) : TransportOp

    data object StepReleased : TransportOp

    data object GoLive : TransportOp
}

/** An op for the sitting [sittingId] while it showed the block (or stand-in piece) that started at [blockStartMs]. */
internal data class Tagged(
    val sittingId: Long,
    val blockStartMs: Long,
    val op: TransportOp,
)

/**
 * One sitting's inbox: buffered ([CAPACITY], the oldest dropped). An op for another sitting is refused at the door, and
 * one for another block is skipped by [receive], so a press never acts on what plays after a cut or a retune.
 */
internal class SittingCommands(
    val sittingId: Long,
) {
    private val inbox = Channel<Tagged>(CAPACITY, BufferOverflow.DROP_OLDEST)

    /** False (dropped) when [command] is for another sitting, or this inbox is closed. */
    fun offer(command: Tagged): Boolean = command.sittingId == sittingId && inbox.trySend(command).isSuccess

    /**
     * The next op tagged for the block that started at [blockStartMs]; ops tagged for another block are skipped. Once
     * the inbox is closed the sitting is ending, so this waits to be cancelled with it; it never throws.
     */
    suspend fun receive(blockStartMs: Long): TransportOp {
        while (true) {
            val next = inbox.receiveCatching().getOrNull() ?: awaitCancellation()
            if (next.blockStartMs == blockStartMs) return next.op
        }
    }

    /** Empties the inbox and says how many ops it held. */
    fun drain(): Int {
        var dropped = 0
        while (inbox.tryReceive().isSuccess) dropped++
        return dropped
    }

    fun close() {
        inbox.close()
    }

    companion object {
        const val CAPACITY = 16
    }
}

/** Where a paused viewer is: the program block being played and the media position. Plain data. */
data class Place(
    val channel: ChannelId,
    val block: AiringBlock,
    val mediaMs: Long,
)

/**
 * What the screen shows of the transport while the viewer is behind or paused; null while live. [mediaMs] is the media
 * position as of [asOfMs] (the clock's epoch ms); it moves on from there only while the picture plays.
 * [blockStartMs] is the schedule time of the block's start, [inPointMs] and [cutMs] its media bounds, and [driftMs] how
 * far the sitting runs ahead of the clock.
 */
data class TransportView(
    val paused: Boolean,
    val seeking: Boolean,
    val seekable: Boolean,
    val visible: Boolean,
    val mediaMs: Long,
    val targetMs: Long?,
    val asOfMs: Long,
    val blockStartMs: Long,
    val inPointMs: Long,
    val cutMs: Long,
    val driftMs: Long,
) {
    /** The media position that is live at the clock time [nowMs]; not capped at the cut. */
    fun liveMs(nowMs: Long): Long = inPointMs + (nowMs + driftMs - blockStartMs)

    /** How far behind live the viewer is at [nowMs], never negative. */
    fun behindMs(nowMs: Long): Long {
        val moving = !paused && !seeking
        val media = if (moving) mediaMs + (nowMs - asOfMs) else mediaMs
        return TransportMath.behindMs(liveMs(nowMs), media)
    }
}

/** The place arithmetic of pause and seek, all in media milliseconds. */
internal object TransportMath {
    /** The block's first media millisecond: where a back seek stops. A slate has no media and counts as 0. */
    fun inPointMs(block: AiringBlock): Long = block.positionAt(block.startMs)?.inWholeMilliseconds ?: 0L

    /** Where the block is cut: the media position at its end. */
    fun cutMs(block: AiringBlock): Long = block.positionAt(block.endMs)?.inWholeMilliseconds ?: 0L

    /** The media position at the virtual time [vnowMs]; not capped at the cut. */
    fun liveMs(
        block: AiringBlock,
        vnowMs: Long,
    ): Long = block.positionAt(vnowMs)?.inWholeMilliseconds ?: 0L

    fun behindMs(
        liveMs: Long,
        mediaMs: Long,
    ): Long = maxOf(0L, liveMs - mediaMs)

    fun back(
        targetMs: Long,
        inPointMs: Long,
    ): Long = maxOf(targetMs, inPointMs)

    fun ahead(
        targetMs: Long,
        liveMs: Long,
        cutMs: Long,
    ): Long = minOf(targetMs, minOf(liveMs, cutMs))

    /** True when the ahead clamp hit live, which is before the cut: the viewer is back at live. */
    fun reachesLive(
        targetMs: Long,
        liveMs: Long,
        cutMs: Long,
    ): Boolean = liveMs < cutMs && targetMs >= liveMs
}
