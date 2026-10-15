// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.model.ItemId
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
}
