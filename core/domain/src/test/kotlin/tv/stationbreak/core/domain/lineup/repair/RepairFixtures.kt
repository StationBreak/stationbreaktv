// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId

// Synthetic fixtures only: made-up IDs and times.

internal const val HALF_HOUR_MS = 1_800_000L

/** 2026-10-19T12:00:00Z, the instant the lineup tests treat as now. */
internal val NOW_MS: Long = NOON.toEpochMilliseconds()

/**
 * A repair of [channel]'s day [n] made at [atMs]: the kept window (the block on air at [atMs], the pin), then
 * half-hour tail blocks of [items] in turn to [dayEndMs], the last of them a slate.
 */
@Suppress("LongParameterList")
internal fun repairOf(
    channel: String = "starter:laughs",
    n: Long = 1L,
    atMs: Long = NOW_MS + 600_000L,
    dayEndMs: Long = NOW_MS + 6 * 3_600_000L,
    seq: Long = 3L,
    tailBlocks: Int = 8,
    firstItem: Int = 10,
    hidden: HiddenReason? = null,
): ChannelRepair {
    val keptFrom = atMs - Math.floorMod(atMs, HALF_HOUR_MS)
    val pinEnd = keptFrom + 2 * HALF_HOUR_MS
    val blocks = ArrayList<KeptBlock>()
    blocks += KeptBlock(keptFrom, pinEnd, KeptContent.Program(id(firstItem), 120_000L))
    require(dayEndMs - pinEnd >= tailBlocks) { "no room for the tail" }
    var cursor = pinEnd
    for (i in 0 until tailBlocks) {
        val end = pinEnd + (dayEndMs - pinEnd) * (i + 1) / tailBlocks
        blocks +=
            KeptBlock(
                cursor,
                end,
                if (i == tailBlocks - 1) KeptContent.Slate else KeptContent.Program(id(firstItem + 1 + i % 3), 0L),
            )
        cursor = end
    }
    return ChannelRepair(
        channel = ChannelId(channel),
        n = n,
        dayEndMs = dayEndMs,
        atMs = atMs,
        seq = seq,
        libraries = setOf(ItemId("lib-b"), ItemId("lib-a")),
        keptFromMs = keptFrom,
        pinEndMs = pinEnd,
        pinLibrary = ItemId("lib-a"),
        blocks = blocks,
        tailHidden = hidden,
    )
}

/**
 * A fix of the block `[blockStartMs, +1 h)` of [channel]'s day [n]: a match then a fill that starts at 0. Of the
 * repair of [seq] (0: of the day as built).
 */
@Suppress("LongParameterList")
internal fun fixOf(
    channel: String = "starter:laughs",
    n: Long = 1L,
    dayEndMs: Long = NOW_MS + 6 * 3_600_000L,
    blockStartMs: Long = NOW_MS,
    lengthMs: Long = 2 * HALF_HOUR_MS,
    gone: Int = 90,
    piece: Int = 50,
    seq: Long = 0L,
): LostFix =
    LostFix(
        channel = ChannelId(channel),
        n = n,
        dayEndMs = dayEndMs,
        blockStartMs = blockStartMs,
        blockEndMs = blockStartMs + lengthMs,
        gone = id(gone),
        pieces =
            listOf(
                KeptBlock(blockStartMs, blockStartMs + HALF_HOUR_MS, KeptContent.Program(id(piece), 0L)),
                KeptBlock(blockStartMs + HALF_HOUR_MS, blockStartMs + lengthMs, KeptContent.Slate),
            ),
        seq = seq,
    )

/**
 * One of each kind of kept content, a programme with a media start. The codec round-trip test runs over it and
 * checks it covers every subclass of [KeptContent] (`permittedSubclasses`), so a new kind fails until the codec and
 * this list handle it.
 */
internal fun everyKeptContent(): List<KeptContent> =
    listOf(
        KeptContent.Program(id(7), 90_000L),
        KeptContent.Slate,
        KeptContent.Break,
    )
