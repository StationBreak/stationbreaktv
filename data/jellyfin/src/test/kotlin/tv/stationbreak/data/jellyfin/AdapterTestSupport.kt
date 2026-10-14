// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

internal val TEST_IDENTITY = ClientIdentity("StationBreak", "Living room TV", "0.0.1")
internal const val TEST_DEVICE_ID = "device-0001"
internal const val SERVER_ID = "0123abcd"

/** A random, obviously fake secret for one test run, so a leak test can't pass by matching a constant. */
internal fun fake(kind: String): String = "fake-$kind-${UUID.randomUUID()}"

/** An adapter on a bare client, with the test seams the production constructor leaves out. */
internal fun testAdapter(
    client: OkHttpClient = OkHttpClient(),
    tuning: AdapterTuning = AdapterTuning(ProbeTimeouts(connect = 2.seconds, read = 2.seconds, call = 5.seconds)),
    log: AdapterLog = AdapterLog.NONE,
): JellyfinMediaServer = JellyfinMediaServer(client, TEST_IDENTITY, DeviceIdProvider { TEST_DEVICE_ID }, log, tuning)

/** A [MockWebServer] that answers by method and path and keeps the request log. */
internal class FakeJellyfin : AutoCloseable {
    val server = MockWebServer()
    val log = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    log += request
                    // A route matches the end of the path, so a subpath doesn't need its own routes.
                    val route =
                        routes.entries
                            .filter { (key, _) -> key.substringBefore(' ') == request.method }
                            .firstOrNull { (key, _) -> request.url.encodedPath.endsWith(key.substringAfter(' ')) }
                            ?.value
                    return route?.invoke(request) ?: MockResponse.Builder().code(404).build()
                }
            }
        server.start()
        on("GET", "/System/Info/Public") { json(JELLYFIN_BODY) }
    }

    /** Plain http to the loopback: private, so it needs no acknowledgement. */
    val address: ServerAddress get() = ServerAddress(Scheme.Http, LOOPBACK, server.port)

    /** The same place reached by fallback, so cleartext to it needs an acknowledgement. */
    val fallbackAddress: ServerAddress get() = address.copy(viaFallback = true)

    fun on(
        method: String,
        path: String,
        handler: (RecordedRequest) -> MockResponse,
    ) {
        routes["$method $path"] = handler
    }

    fun requests(path: String): List<RecordedRequest> = log.filter { it.url.encodedPath.endsWith(path) }

    fun json(
        body: String,
        code: Int = 200,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    fun status(code: Int): MockResponse = MockResponse.Builder().code(code).build()

    fun redirect(location: String): MockResponse =
        MockResponse
            .Builder()
            .code(302)
            .addHeader("Location", location)
            .build()

    override fun close() = server.close()
}

internal fun authResult(
    token: String,
    admin: Boolean = false,
    userId: String = "user1",
    serverId: String = SERVER_ID,
): String =
    "{\"User\":{\"Id\":\"$userId\",\"Name\":\"alice\",\"Policy\":{\"IsAdministrator\":$admin}}," +
        "\"AccessToken\":\"$token\",\"ServerId\":\"$serverId\"}"
