// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpkiPinTest {
    private val bytes = ByteArray(SpkiPin.SIZE) { it.toByte() }

    @Test
    fun isExactlyThirtyTwoBytes() {
        assertFailsWith<IllegalArgumentException> { SpkiPin(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { SpkiPin(ByteArray(33)) }
    }

    @Test
    fun comparesByContent() {
        assertEquals(SpkiPin(bytes), SpkiPin(bytes.copyOf()))
        assertEquals(SpkiPin(bytes).hashCode(), SpkiPin(bytes.copyOf()).hashCode())
        assertNotEquals(SpkiPin(bytes), SpkiPin(ByteArray(SpkiPin.SIZE)))
        assertTrue(SpkiPin(bytes).matches(SpkiPin(bytes.copyOf())))
        assertFalse(SpkiPin(bytes).matches(SpkiPin(ByteArray(SpkiPin.SIZE))))
    }

    @Test
    fun aChangeToTheCallersArrayDoesNotChangeThePin() {
        val mine = bytes.copyOf()
        val pin = SpkiPin(mine)
        mine[0] = 99
        assertEquals(SpkiPin(bytes), pin)
        pin.toByteArray()[1] = 99
        assertEquals(SpkiPin(bytes), pin)
    }

    @Test
    fun hexIsSixtyFourUpperCaseDigitsAndRoundTrips() {
        val hex = SpkiPin(bytes).hex
        assertEquals(64, hex.length)
        assertEquals("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F", hex)
        assertEquals(SpkiPin(bytes), SpkiPin.fromHex(hex))
        assertEquals(SpkiPin(bytes), SpkiPin.fromHex(hex.lowercase()))
    }

    @Test
    fun badHexIsNull() {
        assertNull(SpkiPin.fromHex(""))
        assertNull(SpkiPin.fromHex("00"))
        assertNull(SpkiPin.fromHex("zz".repeat(SpkiPin.SIZE)))
        assertNull(SpkiPin.fromHex("00".repeat(SpkiPin.SIZE) + "0"))
    }

    @Test
    fun toStringShowsNothingOfThePin() {
        assertEquals("SpkiPin(***)", SpkiPin(bytes).toString())
    }

    @Test
    fun toByteArrayGivesTheBytes() {
        assertContentEquals(bytes, SpkiPin(bytes).toByteArray())
    }
}

class CertificateEvidenceTest {
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 7 })

    @Test
    fun toStringHoldsNoCertificateData() {
        val evidence = CertificateEvidence(pin, selfSigned = true, notBeforeMillis = 1_234L, notAfterMillis = 7_654L)
        assertEquals("CertificateEvidence(***)", evidence.toString())
    }

    @Test
    fun comparesByValue() {
        val a = CertificateEvidence(pin, true, 1L, 2L)
        assertEquals(a, CertificateEvidence(SpkiPin(ByteArray(SpkiPin.SIZE) { 7 }), true, 1L, 2L))
        assertNotEquals(a, CertificateEvidence(pin, false, 1L, 2L))
    }
}
