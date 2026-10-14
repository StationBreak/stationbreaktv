// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
