// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.model.Seed
import java.security.SecureRandom
import java.util.Random

/** Draws the seed of a new channel or a reshuffle: 64 bits that nothing else shares. */
fun interface NewSeed {
    fun next(): Seed

    companion object {
        private const val SEED_BYTES = 8
        private const val BYTE_MASK = 0xFFuL
        private const val BYTE_BITS = 8

        /** The production source (over a `SecureRandom`): [random]'s next eight bytes as an unsigned number. */
        fun secure(random: Random = SecureRandom()): NewSeed =
            NewSeed {
                val bytes = ByteArray(SEED_BYTES)
                random.nextBytes(bytes)
                Seed(bytes.fold(0uL) { seed, byte -> (seed shl BYTE_BITS) or (byte.toULong() and BYTE_MASK) })
            }
    }
}
