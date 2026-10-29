// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

private const val MIB = 1_024L * 1_024L

/**
 * The player's periodic memory line (`SBLIVE mem ...`), so a device run can see the heap against the player's own
 * media buffer without a heap dump: the heap in use and its limit, the bytes the player's buffer holds and how much
 * media that is. Numbers and fixed names only.
 */
internal fun memoryLine(
    heapUsedBytes: Long,
    heapMaxBytes: Long,
    playerBufferBytes: Long,
    bufferedMs: Long,
): String =
    "mem heapMb=${heapUsedBytes / MIB} maxMb=${heapMaxBytes / MIB} playerBufMb=${playerBufferBytes / MIB} " +
        "bufferedMs=$bufferedMs"
