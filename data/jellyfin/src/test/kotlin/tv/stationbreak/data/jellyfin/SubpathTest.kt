// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SignInRequest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun everyEndpointUnitThreeAddsKeepsTheSubpathOnTheWire() {
        val expected =
            listOf(
                "UserViews",
                "Items",
                "Items/i1/PlaybackInfo",
                "Sessions/Playing",
                "Sessions/Playing/Progress",
                "Sessions/Playing/Stopped",
            )
        for (subpath in listOf("", "/jellyfin", "/a/b/c", "/with%20space")) {
            FakeJellyfin().use { jellyfin ->
                jellyfin.on("GET", "/UserViews") { jellyfin.json("{\"Items\":[]}") }
                jellyfin.on("GET", "/Items") { jellyfin.json("{\"Items\":[],\"TotalRecordCount\":0}") }
                jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json("{\"MediaSources\":[]}") }
                for (path in listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped")) {
                    jellyfin.on("POST", path) { jellyfin.status(204) }
                }
                val session = testSession(jellyfin.address.copy(subpath = subpath))
                val adapter = testAdapter()
                val library = Library(ItemId("lib"), "Films", LibraryKind.Movies)
                val report = PlaybackReport(ItemId("i1"), MediaSourceId("s1"), null, 1.seconds)
                runBlocking {
                    adapter.libraries(session)
                    adapter.items(session, library, 0, 10)
                    adapter.playbackInfo(session, ItemId("i1"))
                    adapter.reportStart(session, report)
                    adapter.reportProgress(session, report)
                    adapter.reportStopped(session, report)
                }
                // The first request is the once-per-session binding probe, which keeps the subpath too.
                val paths = jellyfin.log.map { it.url.encodedPath }
                assertTrue(paths.all { it.startsWith("$subpath/") }, "subpath '$subpath': $paths")
                for (endpoint in expected) {
                    assertTrue("$subpath/$endpoint" in paths, "subpath '$subpath', endpoint '$endpoint': $paths")
                }
            }
        }
    }
}
