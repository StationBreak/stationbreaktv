// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.keystore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BlobCodecTest {
    private val iv = ByteArray(12) { (it + 1).toByte() }
    private val ciphertext = ByteArray(20) { (it + 100).toByte() }

    @Test
    fun encodeLaysOutVersionIvLengthIvThenCiphertext() {
        val blob = BlobCodec.encode(iv, ciphertext)

        assertEquals(1, blob[0].toInt())
        assertEquals(12, blob[1].toInt())
        assertArrayEquals(iv, blob.copyOfRange(2, 14))
        assertArrayEquals(ciphertext, blob.copyOfRange(14, blob.size))
    }

    @Test
    fun decodeRoundTripsEncode() {
        val decoded = BlobCodec.decode(BlobCodec.encode(iv, ciphertext))

        assertArrayEquals(iv, decoded?.iv)
        assertArrayEquals(ciphertext, decoded?.ciphertext)
    }

    @Test
    fun decodeAcceptsTheShortestValidBlob() {
        // An empty secret encrypts to just the 16-byte tag.
        val decoded = BlobCodec.decode(BlobCodec.encode(iv, ByteArray(BlobCodec.TAG_LENGTH)))

        assertEquals(BlobCodec.TAG_LENGTH, decoded?.ciphertext?.size)
    }

    @Test
    fun decodeRejectsAnEmptyBlob() {
        assertNull(BlobCodec.decode(ByteArray(0)))
    }

    @Test
    fun decodeRejectsABlobShorterThanItsHeader() {
        assertNull(BlobCodec.decode(byteArrayOf(1)))
    }

    @Test
    fun decodeRejectsAWrongVersion() {
        val blob = BlobCodec.encode(iv, ciphertext)
        for (version in listOf(0, 2, -1)) {
            blob[0] = version.toByte()
            assertNull("version $version", BlobCodec.decode(blob))
        }
    }

    @Test
    fun decodeRejectsAnIvLengthOtherThanTwelve() {
        val blob = BlobCodec.encode(iv, ciphertext)
        for (length in listOf(0, 11, 13, 16, -1)) {
            blob[1] = length.toByte()
            assertNull("iv length $length", BlobCodec.decode(blob))
        }
    }

    @Test
    fun decodeRejectsABlobShorterThanHeaderIvAndTag() {
        val shortest = BlobCodec.encode(iv, ByteArray(BlobCodec.TAG_LENGTH))
        assertNull(BlobCodec.decode(shortest.copyOf(shortest.size - 1)))
        // Truncated inside the IV.
        assertNull(BlobCodec.decode(shortest.copyOf(8)))
    }

    @Test
    fun aadIsTheTwoHeaderBytesThenTheUtf8KeyName() {
        val aad = BlobCodec.aad("session")

        assertEquals(1, aad[0].toInt())
        assertEquals(12, aad[1].toInt())
        assertArrayEquals("session".toByteArray(Charsets.UTF_8), aad.copyOfRange(2, aad.size))
    }

    @Test
    fun aadDiffersPerKeyName() {
        assertFalse(BlobCodec.aad("a").contentEquals(BlobCodec.aad("b")))
    }

    @Test
    fun aadEncodesNonAsciiNamesAsUtf8() {
        assertArrayEquals("é".toByteArray(Charsets.UTF_8), BlobCodec.aad("é").copyOfRange(2, 4))
    }

    @Test
    fun fileNameIsTheHexSha256OfTheKeyNameAndNeverTheName() {
        // SHA-256("abc"), the FIPS 180-2 test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BlobCodec.fileName("abc"),
        )
    }
}
