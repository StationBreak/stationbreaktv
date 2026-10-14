// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId

/** Dummy values for the secret-storage tests (spec 04). Nothing here is, or looks like, a real credential. */
internal object SecretFixtures {
    const val DUMMY_TOKEN = "dummy-token-0001"

    /** A fixed, fake UUID standing in for the device ID. */
    const val FAKE_DEVICE_ID = "00000000-0000-4000-8000-000000000001"

    val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
    val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))
    val session = Session(UserId("user1"), AccessToken(DUMMY_TOKEN), info, address, null, true)
}
