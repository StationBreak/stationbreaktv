// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** S0.3.1 "server checks": it is Jellyfin, it is 12.0 or later, it is set up, and the body is size-limited. */
class ProbeChecksTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() = server.close()

    private fun body(
        version: String = "12.2.1",
        product: String? = "Jellyfin Server",
        wizard: Boolean? = true,
        extra: String = "",
    ) = buildString {
        append("{\"ServerName\":\"Home\",\"Version\":\"$version\",\"Id\":\"0123abcd\"")
        if (product != null) append(",\"ProductName\":\"$product\"")
        if (wizard != null) append(",\"StartupWizardCompleted\":$wizard")
        append(",\"LocalAddress\":\"http://10.0.0.5:8096\",\"OperatingSystem\":\"\"$extra}")
    }

    private fun probe(response: MockResponse): ServerResult<ResolvedServer> {
        server.enqueue(response)
        val address = ServerAddress(Scheme.Http, "127.0.0.1", server.port)
        return runBlocking { ServerProbe(OkHttpClient()).probe(address) }
    }

    private fun ok(text: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(text)
            .build()

    private fun errorOf(result: ServerResult<ResolvedServer>) = assertIs<ServerResult.Failure>(result).error

    @Test
    fun aJellyfin12ServerPasses() {
        for (version in listOf("12.0.0", "12.2.1", "12.9.9")) {
            val found = probe(ok(body(version = version))).getOrNull()
            val expected = ServerInfo("Home", ServerId("0123abcd"), ServerVersion.parse(version)!!)
            assertEquals(expected, found?.server, version)
        }
    }

    @Test
    fun aLaterMajorIsAllowedAndReportedAsIs() {
        assertEquals(ServerVersion(13, 0, 1), probe(ok(body(version = "13.0.1"))).getOrNull()?.server?.version)
    }

    @Test
    fun aVersionWithABuildSuffixIsRead() {
        assertEquals(ServerVersion(12, 0, 0), probe(ok(body(version = "12.0.0-rc.1"))).getOrNull()?.server?.version)
    }

    @Test
    fun anOlderMajorIsUnsupportedAndReportsWhatWasFound() {
        assertEquals(
            MediaServerError.UnsupportedVersion(ServerVersion(11, 0, 3)),
            errorOf(probe(ok(body(version = "11.0.3")))),
        )
        assertEquals(
            MediaServerError.UnsupportedVersion(ServerVersion(10, 11, 0)),
            errorOf(probe(ok(body(version = "10.11.0")))),
        )
    }

    @Test
    fun anOlderServerThatIsNotSetUpIsStillUnsupported() {
        assertIs<MediaServerError.UnsupportedVersion>(errorOf(probe(ok(body(version = "10.8.0", wizard = false)))))
    }

    @Test
    fun aBodyThatIsNotJsonIsNotJellyfin() {
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok("<html><body>Welcome to nginx</body></html>"))))
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok(""))))
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok("not json at all"))))
    }

    @Test
    fun aBodyWithoutTheExpectedShapeIsNotJellyfin() {
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok("{}"))))
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok("[]"))))
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok("\"Jellyfin Server\""))))
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok("{\"Version\":12}"))))
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok(body(version = "twelve")))))
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok(body(product = null)))))
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok(body(wizard = null)))))
    }

    @Test
    fun aDifferentProductNameIsNotJellyfin() {
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok(body(product = "Emby Server")))))
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok(body(product = "Plex Media Server")))))
    }

    @Test
    fun anUnfinishedStartupWizardIsServerNotSetUp() {
        assertIs<MediaServerError.ServerNotSetUp>(errorOf(probe(ok(body(wizard = false)))))
    }

    @Test
    fun anOversizeBodyIsNotJellyfinEvenWhenItWouldParse() {
        // A valid body padded past the limit: the limit applies before parsing, so this never reaches the parser.
        val padded = body(extra = ",\"Pad\":\"" + "x".repeat(ProbeChecks.MAX_BODY_BYTES * 2) + "\"")
        assertTrue(padded.length > ProbeChecks.MAX_BODY_BYTES)
        assertIs<MediaServerError.NotJellyfin>(errorOf(probe(ok(padded))))
        // A body just inside the limit still passes.
        val inside = body(extra = ",\"Pad\":\"" + "x".repeat(ProbeChecks.MAX_BODY_BYTES - 1_000) + "\"")
        assertTrue(inside.length <= ProbeChecks.MAX_BODY_BYTES)
        assertEquals(ServerVersion(12, 2, 1), probe(ok(inside)).getOrNull()?.server?.version)
    }

    @Test
    fun httpStatusesMapToTheirErrors() {
        fun status(code: Int) =
            errorOf(
                probe(
                    MockResponse
                        .Builder()
                        .code(code)
                        .body("nope")
                        .build(),
                ),
            )
        assertIs<MediaServerError.HttpUnauthorized>(status(401))
        assertIs<MediaServerError.HttpForbidden>(status(403))
        assertIs<MediaServerError.NotJellyfin>(status(404))
        assertIs<MediaServerError.ServerError>(status(500))
        assertIs<MediaServerError.ServerError>(status(503))
    }

    @Test
    fun theResolvedAddressIsTheOneProbedAndNotAFallback() {
        val resolved = probe(ok(body())).getOrNull()!!
        assertEquals(ServerAddress(Scheme.Http, "127.0.0.1", server.port), resolved.address)
        assertEquals(false, resolved.resolvedByFallback)
    }
}
