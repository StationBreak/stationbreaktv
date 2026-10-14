// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.keystore

import java.security.MessageDigest
import java.util.Locale

/**
 * The stored blob: `[version][ivLength][iv][ciphertext + tag]` (ADR-0009). The two header bytes and the secret's key
 * name are GCM's additional authenticated data, so neither the header nor a move to another key name goes unnoticed.
 */
internal object BlobCodec {
    const val VERSION: Byte = 1
    const val IV_LENGTH = 12
    const val TAG_LENGTH = 16
    private const val HEADER_LENGTH = 2

    class Decoded(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    fun encode(
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(iv.size == IV_LENGTH) { "iv must be $IV_LENGTH bytes" }
        return byteArrayOf(VERSION, IV_LENGTH.toByte()) + iv + ciphertext
    }

    /** Null for any blob this version didn't write: a wrong version, a wrong IV length or too short to hold a tag. */
    fun decode(blob: ByteArray): Decoded? {
        val valid =
            blob.size >= HEADER_LENGTH + IV_LENGTH + TAG_LENGTH && blob[0] == VERSION && blob[1].toInt() == IV_LENGTH
        return if (valid) {
            Decoded(
                iv = blob.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + IV_LENGTH),
                ciphertext = blob.copyOfRange(HEADER_LENGTH + IV_LENGTH, blob.size),
            )
        } else {
            null
        }
    }

    fun aad(keyName: String): ByteArray = byteArrayOf(VERSION, IV_LENGTH.toByte()) + keyName.toByteArray(Charsets.UTF_8)

    /** The blob's file name: a hex SHA-256 of the key name, so no name and no secret is ever in a path. */
    fun fileName(keyName: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(keyName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
