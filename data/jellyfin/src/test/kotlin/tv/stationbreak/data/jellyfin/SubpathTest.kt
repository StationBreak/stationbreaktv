// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SignInRequest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The subpath survives onto every endpoint the adapter calls. Units 2 and 3 add their endpoints to [calls]. */
class SubpathTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() = server.close()

    /** Every endpoint the adapter calls so far: its name and how to make one call to it at [address]. */
    private val calls: Map<String, suspend (ServerAddress) -> Unit> =
        mapOf(
            "System/Info/Public" to { address -> ServerProbe(OkHttpClient()).probe(address) },
        )

    @Test
    fun everyEndpointKeepsTheSubpathOnTheWire() {
        for (subpath in listOf("", "/jellyfin", "/a/b/c", "/with%20space")) {
            for ((endpoint, call) in calls) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body("{}")
                        .build(),
                )
                runBlocking { call(ServerAddress(Scheme.Http, "127.0.0.1", server.port, subpath)) }
                val path = server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath
                assertEquals("$subpath/$endpoint", path, "subpath '$subpath', endpoint '$endpoint'")
            }
        }
    }

    @Test
    fun everyEndpointUnitTwoAddsKeepsTheSubpathOnTheWire() {
        val expected =
            listOf(
                "Users/AuthenticateByName",
                "QuickConnect/Enabled",
                "QuickConnect/Initiate",
                "QuickConnect/Connect",
                "Users/AuthenticateWithQuickConnect",
                "Users/Me",
                "Sessions/Logout",
            )
        for (subpath in listOf("", "/jellyfin", "/a/b/c", "/with%20space")) {
            FakeJellyfin().use { jellyfin ->
                jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult("tok")) }
                jellyfin.on("GET", "/QuickConnect/Enabled") { jellyfin.json("true") }
                jellyfin.on("POST", "/QuickConnect/Initiate") {
                    jellyfin.json("{\"Authenticated\":false,\"Secret\":\"s\",\"Code\":\"1\"}")
                }
                jellyfin.on("GET", "/QuickConnect/Connect") { jellyfin.json("{\"Authenticated\":true}") }
                jellyfin.on("POST", "/Users/AuthenticateWithQuickConnect") { jellyfin.json(authResult("tok")) }
                jellyfin.on("GET", "/Users/Me") {
                    jellyfin.json("{\"Id\":\"user1\",\"Policy\":{\"IsAdministrator\":false}}")
                }
                jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(204) }
                val address = jellyfin.address.copy(subpath = subpath)
                val adapter = testAdapter()
                runBlocking {
                    val request = SignInRequest(address, "alice", "pw".toCharArray(), null)
                    val session = (adapter.signIn(request) as ServerResult.Success).value
                    adapter.quickConnect(address, null).toList()
                    adapter.resume(session.toStored())
                    adapter.revoke(session)
                }
                val paths = jellyfin.log.map { it.url.encodedPath }
                assertTrue(paths.all { it.startsWith("$subpath/") }, "subpath '$subpath': $paths")
                for (endpoint in expected) {
                    assertTrue("$subpath/$endpoint" in paths, "subpath '$subpath', endpoint '$endpoint': $paths")
                }
            }
        }
    }
}
