// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeMediaServerTest {
    private val address = ServerAddress(Scheme.Https, "media.example.com", 443)
    private val resolved = ResolvedServer(address, ServerInfo("Home", ServerId("abc"), ServerVersion(12, 2, 0)), false)

    @Test
    fun answersWithWhatItWasToldAndRecordsTheCalls() =
        runBlocking {
            val fake = FakeMediaServer()
            fake.discoverResult = ServerResult.Success(resolved)
            fake.connectResult = ServerResult.Failure(MediaServerError.Timeout())

            assertEquals(ServerResult.Success(resolved), fake.discover("media.example.com"))
            val acknowledgement = CleartextAcknowledgement(address)
            assertEquals(ServerResult.Failure(MediaServerError.Timeout()), fake.connect(address, acknowledgement))

            assertEquals(listOf("media.example.com"), fake.discoverCalls)
            val expectedCalls: List<Pair<ServerAddress, CleartextAcknowledgement?>> = listOf(address to acknowledgement)
            assertEquals(expectedCalls, fake.connectCalls)
        }
}
