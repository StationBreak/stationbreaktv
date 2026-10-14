// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/** The network half of sign-out; the clear-first ordering is `SessionRepository`'s (core:domain tests). */
class SignOutTest {
    private val jellyfin = FakeJellyfin()
    private val token = fake("token")

    private fun session() =
        Session(
            UserId("user1"),
            AccessToken(token),
            ServerInfo("Home", ServerId(SERVER_ID), ServerVersion(12, 2, 1)),
            jellyfin.address,
            null,
            false,
        )

    @AfterTest
    fun tearDown() = jellyfin.close()

    @Test
    fun postsToSessionsLogoutWithTheTokenAndReportsRevoked() =
        runBlocking {
            jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(204) }
            assertEquals(ServerResult.Success(Unit), testAdapter().revoke(session()))
            val request = jellyfin.requests("/Sessions/Logout").single()
            assertEquals("POST", request.method)
            assertEquals(true, request.headers["Authorization"]?.contains("Token=\"$token\""))
        }

    @Test
    fun aServerErrorIsNotRevoked() =
        runBlocking {
            jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(500) }
            assertIs<MediaServerError.ServerError>(testAdapter().revoke(session()).errorOrNull())
        }

    @Test
    fun aSlowServerIsATimeoutWithinTheShortLimit() =
        runBlocking {
            jellyfin.on("POST", "/Sessions/Logout") {
                jellyfin
                    .status(204)
                    .newBuilder()
                    .headersDelay(5, TimeUnit.SECONDS)
                    .build()
            }
            val adapter = testAdapter(tuning = AdapterTuning(revokeTimeout = 300.milliseconds))
            assertIs<MediaServerError.Timeout>(adapter.revoke(session()).errorOrNull())
        }

    @Test
    fun anUnreachableServerIsNotRevoked() =
        runBlocking {
            val dead = session()
            jellyfin.close()
            assertIs<MediaServerError>(testAdapter().revoke(dead).errorOrNull())
        }
}
