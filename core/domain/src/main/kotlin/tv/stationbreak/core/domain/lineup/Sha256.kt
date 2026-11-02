// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import java.security.MessageDigest

private const val HEX = "0123456789abcdef"
private const val NIBBLE = 4
private const val LOW = 0x0f

/** The SHA-256 of [bytes], in lower-case hex: the day inputs' digest and the day files' name hash. */
internal fun sha256Hex(bytes: ByteArray): String =
    buildString {
        for (b in MessageDigest.getInstance("SHA-256").digest(bytes)) {
            append(HEX[(b.toInt() shr NIBBLE) and LOW])
            append(HEX[b.toInt() and LOW])
        }
    }
