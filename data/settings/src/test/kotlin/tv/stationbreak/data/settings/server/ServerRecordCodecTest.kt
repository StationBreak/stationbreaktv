// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.PinKind
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin

class ServerRecordCodecTest {
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { (it * 3).toByte() })
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920, "/jellyfin")

    @Test
    fun aRecordWithAPinRoundTrips() {
        val record = ServerRecord(address, pin)
        assertEquals(record, ServerRecordCodec.decode(ServerRecordCodec.encode(record)))
    }

    // #223: a CA pin round-trips under a key of its own, so an older app reads no pin at all from it.
    @Test
    fun aRecordWithAnAuthorityPinRoundTripsUnderItsOwnKey() {
        val record = ServerRecord(address, pin, PinKind.Authority)
        val text = ServerRecordCodec.encode(record)
        assertTrue("caPin=${pin.hex}" in text)
        assertFalse(text.lines().any { it.startsWith("pin=") })
        assertEquals(record, ServerRecordCodec.decode(text))
    }

    @Test
    fun aRecordWithBothKindsOfPinKeepsItsAddressAndTrustsNeither() {
        val other = SpkiPin(ByteArray(SpkiPin.SIZE) { 7 })
        val text = ServerRecordCodec.encode(ServerRecord(address, pin)) + "\ncaPin=${other.hex}\nlibraries=lib-a"
        assertEquals(ServerRecord(address), ServerRecordCodec.decode(text))
        assertEquals(setOf(ItemId("lib-a")), ServerRecordCodec.decodeStored(text)?.libraries)
    }

    @Test
    fun anAuthorityPinOverHttpIsNeverRead() {
        val text =
            ServerRecordCodec
                .encode(ServerRecord(address, pin, PinKind.Authority))
                .replace("scheme=https", "scheme=http")
        assertNull(ServerRecordCodec.decode(text))
    }

    @Test
    fun aTruncatedAuthorityPinIsRefused() {
        val text = ServerRecordCodec.encode(ServerRecord(address)) + "\ncaPin=${pin.hex.dropLast(2)}"
        assertNull(ServerRecordCodec.decode(text))
    }

    // The format as it was before #223, written out by hand: it still reads as a leaf pin with its selection.
    @Test
    fun aRecordWrittenBeforeCaPinsStillReadsAsALeafPin() {
        val text =
            listOf(
                "version=1",
                "scheme=https",
                "host=nas.example.test",
                "port=8920",
                "subpath=/jellyfin",
                "viaFallback=false",
                "pin=${pin.hex}",
                "libraries=lib-a,lib-b",
            ).joinToString("\n")
        val stored = ServerRecordCodec.decodeStored(text)
        assertEquals(ServerRecord(address, pin, PinKind.Leaf), stored?.record)
        assertEquals(setOf(ItemId("lib-a"), ItemId("lib-b")), stored?.libraries)
        assertFalse("caPin=" in ServerRecordCodec.encode(ServerRecord(address, pin)))
    }

    @Test
    fun anAuthorityRecordKeepsItsSelection() {
        val stored = StoredSettings(ServerRecord(address, pin, PinKind.Authority), libraries = setOf(ItemId("lib-a")))
        val back = ServerRecordCodec.decodeStored(ServerRecordCodec.encode(stored))
        assertEquals(stored.record, back?.record)
        assertEquals(stored.libraries, back?.libraries)
    }

    @Test
    fun aGarbledAuthorityPinIsRefusedNotReadAsNoPin() {
        val text = ServerRecordCodec.encode(ServerRecord(address)) + "\ncaPin=not-hex"
        assertNull(ServerRecordCodec.decode(text))
    }

    @Test
    fun aRecordWithoutAPinRoundTrips() {
        val http = ServerRecord(ServerAddress(Scheme.Http, "192.168.1.10", 8096, viaFallback = true))
        assertEquals(http, ServerRecordCodec.decode(ServerRecordCodec.encode(http)))
        val plain = ServerRecord(address)
        assertEquals(plain, ServerRecordCodec.decode(ServerRecordCodec.encode(plain)))
    }

    @Test
    fun anIpv6HostAndAnEmptySubpathRoundTrip() {
        val record = ServerRecord(ServerAddress(Scheme.Https, "2001:db8::1", 443), pin)
        assertEquals(record, ServerRecordCodec.decode(ServerRecordCodec.encode(record)))
    }

    @Test
    fun theTextHasTheVersionFirst() {
        assertEquals("version=1", ServerRecordCodec.encode(ServerRecord(address)).lines().first())
    }

    @Test
    fun anythingItDidNotWriteIsNull() {
        val good = ServerRecordCodec.encode(ServerRecord(address, pin))
        val bad =
            listOf(
                "",
                "garbage",
                good.replace("version=1", "version=2"),
                good.replace("version=1\n", ""),
                good.replace("scheme=https", "scheme=ftp"),
                good.replace("port=8920", "port=0"),
                good.replace("port=8920", "port=abc"),
                good.replace("host=nas.example.test", "host=NAS.EXAMPLE.TEST"),
                good.replace(pin.hex, pin.hex.take(60)),
                good.replace("scheme=https", "scheme=http"),
            )
        for (text in bad) assertNull(text, ServerRecordCodec.decode(text))
    }

    @Test
    fun aPinOverHttpIsNeverRead() {
        val text = ServerRecordCodec.encode(ServerRecord(address, pin)).replace("scheme=https", "scheme=http")
        assertNull(ServerRecordCodec.decode(text))
    }

    @Test
    fun anUnknownKeyIsIgnoredSoALaterVersionCanAddOne() {
        val text = ServerRecordCodec.encode(ServerRecord(address)) + "\nfuture=1"
        assertEquals(ServerRecord(address), ServerRecordCodec.decode(text))
    }

    @Test
    fun theTextHoldsNoSecretOnlyTheAddressAndPin() {
        val text = ServerRecordCodec.encode(ServerRecord(address, pin))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("password", ignoreCase = true))
    }

    @Test
    fun aSelectionRoundTripsWithTheRecordInOneText() {
        val stored = StoredSettings(ServerRecord(address, pin), setOf(ItemId("movies"), ItemId("tv-shows_2")))
        assertEquals(stored, ServerRecordCodec.decodeStored(ServerRecordCodec.encode(stored)))
        assertEquals(stored.record, ServerRecordCodec.decode(ServerRecordCodec.encode(stored)))
    }

    @Test
    fun noSelectionIsNoLine() {
        val text = ServerRecordCodec.encode(ServerRecord(address))
        assertFalse("libraries" in text)
        assertNull(ServerRecordCodec.decodeStored(text)?.libraries)
    }

    @Test
    fun aSelectionThatCannotBeReadIsDroppedButTheRecordSurvives() {
        val good = ServerRecordCodec.encode(StoredSettings(ServerRecord(address, pin), setOf(ItemId("movies"))))
        for (bad in listOf("libraries=", "libraries=a,,b", "libraries=a b", "libraries=a/b")) {
            val decoded = ServerRecordCodec.decodeStored(good.replace("libraries=movies", bad))
            assertEquals(bad, ServerRecord(address, pin), decoded?.record)
            assertNull(bad, decoded?.libraries)
        }
    }

    // The address refuses these already; the codec checks again, so a hole in either can't write a forged line.
    @Test
    fun aFieldWithALineBreakIsNeverWritten() {
        for (bad in listOf("a\nb", "a\rb", "x\npin=00")) {
            assertThrows(bad, IllegalArgumentException::class.java) { ServerRecordCodec.checked(bad) }
        }
        assertEquals("/jelly-fin", ServerRecordCodec.checked("/jelly-fin"))
    }

    // '=' in a value is harmless: a line is split on its first '='. A real URL can have one in its path.
    @Test
    fun anEqualsSignInTheSubpathRoundTrips() {
        for (subpath in listOf("/jelly=fin", "/a=b=c", "/=")) {
            val record = ServerRecord(ServerAddress(Scheme.Https, "nas.example.test", 443, subpath), pin)
            assertEquals(subpath, record.address.subpath, subpath)
            assertEquals(record, ServerRecordCodec.decode(ServerRecordCodec.encode(record)))
        }
    }
}
