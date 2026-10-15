// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

private const val HEX_DIGITS = "0123456789ABCDEF"
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0xF
private const val BYTE_MASK = 0xFF
private const val HEX_PER_BYTE = 2

/**
 * The SHA-256 of a certificate's SubjectPublicKeyInfo (its "SPKI"): what a user trusts and what is stored (spec 05,
 * Unit 3). It is not the hash a browser shows, which covers the whole certificate. The hashing happens in the adapter;
 * this is the value. It holds a copy of its bytes, compares in constant time, and [toString] shows nothing of it
 * (nothing logs a fingerprint). A pin isn't a secret, but it is not diagnostic data either.
 */
class SpkiPin(
    bytes: ByteArray,
) {
    private val value: ByteArray

    init {
        require(bytes.size == SIZE) { "An SPKI pin is $SIZE bytes" }
        value = bytes.copyOf()
    }

    /** The 64 upper-case hex digits, which is how a pin is stored and, grouped, how it is shown. */
    val hex: String
        get() =
            buildString(SIZE * HEX_PER_BYTE) {
                for (byte in value) {
                    val unsigned = byte.toInt() and BYTE_MASK
                    append(HEX_DIGITS[unsigned shr NIBBLE_BITS])
                    append(HEX_DIGITS[unsigned and NIBBLE_MASK])
                }
            }

    fun toByteArray(): ByteArray = value.copyOf()

    /** True when [other] holds the same bytes. Takes the same time wherever they differ. */
    fun matches(other: SpkiPin): Boolean {
        var difference = 0
        for (index in 0 until SIZE) difference = difference or (value[index].toInt() xor other.value[index].toInt())
        return difference == 0
    }

    override fun equals(other: Any?): Boolean = other is SpkiPin && matches(other)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "SpkiPin(***)"

    companion object {
        const val SIZE = 32

        /** The pin written as [hex] (either case), or null when [text] isn't exactly [SIZE] bytes of hex. */
        fun fromHex(text: String): SpkiPin? {
            val digits = text.uppercase().map(HEX_DIGITS::indexOf)
            val usable = text.length == SIZE * HEX_PER_BYTE && digits.none { it < 0 }
            if (!usable) return null
            return SpkiPin(ByteArray(SIZE) { byteAt(digits, it) })
        }

        private fun byteAt(
            digits: List<Int>,
            index: Int,
        ): Byte {
            val high = digits[index * HEX_PER_BYTE]
            val low = digits[index * HEX_PER_BYTE + 1]
            return ((high shl NIBBLE_BITS) or low).toByte()
        }
    }
}

/**
 * What a TLS failure says about the certificate the server presented, and nothing else (Angua (Security), spec 05
 * Unit 3): the [spki] pin captured in the failed handshake, whether the leaf is [selfSigned] (it verifies under its own
 * public key), and its validity dates as epoch milliseconds. No certificate, subject or SANs. [toString] shows none of
 * it. The trust screen shows [spki] and, if the user confirms, stores exactly it: there is no second fetch.
 */
data class CertificateEvidence(
    val spki: SpkiPin,
    val selfSigned: Boolean,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
) {
    override fun toString(): String = "CertificateEvidence(***)"
}
