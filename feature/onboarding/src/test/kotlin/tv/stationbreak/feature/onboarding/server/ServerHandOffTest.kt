// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion

class ServerHandOffTest {
    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))

    private fun connected(host: String): ConnectedServer {
        val address = ServerAddress(Scheme.Https, host, 443)
        return ConnectedServer(ResolvedServer(address, info), acknowledgement = null)
    }

    private val first = connected("one.example.test")
    private val second = connected("two.example.test")

    @Test
    fun clearingWhatIsHeldEmptiesIt() {
        val handOff = ServerHandOff()
        handOff.put(first)

        handOff.clear(first)

        assertNull(handOff.peek())
    }

    @Test
    fun clearingSomethingElseLeavesWhatIsHeld() {
        val handOff = ServerHandOff()
        handOff.put(first)
        handOff.put(second)

        handOff.clear(first)

        assertSame(second, handOff.peek())
    }
}
