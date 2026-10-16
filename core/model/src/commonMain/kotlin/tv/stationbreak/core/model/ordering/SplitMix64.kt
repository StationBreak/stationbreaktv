// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.ordering

private const val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL
private const val MIX_1 = 0xBF58476D1CE4E5B9uL
private const val MIX_2 = 0x94D049BB133111EBuL
private const val SHIFT_1 = 30
private const val SHIFT_2 = 27
private const val SHIFT_3 = 31

/**
 * One step of SplitMix64 from state [x] (docs/rules-format.md, "Primitives"). The golden gamma is added before
 * mixing, as in Vigna's `splitmix64.c`, so `splitmix64(0)` is `0xE220A8397B1DCDAF`. All arithmetic is unsigned 64-bit
 * and wraps modulo 2^64 (`ULong` multiplication and addition wrap, and `shr` on a `ULong` is a logical shift). The
 * platform's `Random` is never used: it differs between platforms, and a schedule must be the same on every client.
 */
fun splitmix64(x: ULong): ULong {
    var z = x + GOLDEN_GAMMA
    z = (z xor (z shr SHIFT_1)) * MIX_1
    z = (z xor (z shr SHIFT_2)) * MIX_2
    return z xor (z shr SHIFT_3)
}
