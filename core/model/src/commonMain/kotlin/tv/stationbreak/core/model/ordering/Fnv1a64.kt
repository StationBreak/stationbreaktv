// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.ordering

import tv.stationbreak.core.model.CanonicalId

private const val OFFSET_BASIS = 0xCBF29CE484222325uL
private const val PRIME = 0x100000001B3uL
private const val BYTE_MASK = 0xFFuL

/**
 * 64-bit FNV-1a of [bytes] (docs/rules-format.md, "Primitives"): start at the offset basis, then for each byte xor it in and
 * multiply by the prime, wrapping modulo 2^64. A byte is taken as an unsigned value (0 to 255), never sign-extended.
 */
fun fnv1a64(bytes: ByteArray): ULong {
    var h = OFFSET_BASIS
    for (b in bytes) h = (h xor (b.toULong() and BYTE_MASK)) * PRIME
    return h
}

/** [fnv1a64] of the 32 ASCII characters of a canonical ID: not UTF-16 text, and not the 16 raw bytes of the GUID. */
fun fnv1a64(id: CanonicalId): ULong = fnv1a64(id.value.encodeToByteArray())
