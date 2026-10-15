// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.AdapterTuning
import tv.stationbreak.data.jellyfin.AuthHeaderBuilder
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.jellyfin.ProbeTimeouts
import tv.stationbreak.data.jellyfin.QuickConnectLimits
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The contract suite (S0.3.6): the adapter against a real Jellyfin server in Docker. Every case runs twice, directly
 * and behind a subpath proxy (see the two subclasses). It is skipped, not failed, when Docker is absent. Failures
 * print the assertion and test output only: the harness never prints a request body, and there is no container log
 * consumer. Each case uses a fresh adapter and device ID, because Jellyfin revokes a device's earlier token when the
 * same device signs in again.
 */
@Suppress("TooManyFunctions") // One function per contract case, plus the helpers they share.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ContractSuite {
    internal abstract fun startServer(image: String): JellyfinServer

    private lateinit var server: JellyfinServer
    private val recorder = RecordingInterceptor()
    private val identity = ClientIdentity("StationBreak", "Contract test", "0.0.0")

    @BeforeAll
    fun startContainers() {
        ContractEnvironment.requireDocker()
        server = startServer(ContractEnvironment.jellyfinImage())
    }

    @AfterAll
    fun stopContainers() {
        if (::server.isInitialized) server.close()
    }

    @BeforeEach
    fun forgetEarlierRequests() = recorder.seen.clear()

    private val address get() = server.address
    private val acknowledgement
        get() = if (address.cleartextNeedsConfirmation) CleartextAcknowledgement(address) else null

    private fun adapter(): JellyfinMediaServer =
        JellyfinMediaServer(
            OkHttpClient(),
            identity,
            DeviceIdProvider { "contract-${UUID.randomUUID()}" },
            AdapterLog.NONE,
            AdapterTuning(
                timeouts = ProbeTimeouts(connect = 10.seconds, read = 60.seconds, call = 90.seconds),
                testInterceptors = listOf(recorder),
                quickConnect = QuickConnectLimits(interval = 1.seconds, total = 2.minutes),
            ),
        )

    private suspend fun signIn(
        adapter: JellyfinMediaServer,
        account: Account = server.viewer,
        password: CharArray = account.password(),
    ): ServerResult<Session> = adapter.signIn(SignInRequest(address, account.name, password, acknowledgement))

    private suspend fun session(adapter: JellyfinMediaServer): Session {
        val result = signIn(adapter)
        assertIs<ServerResult.Success<Session>>(result, "sign-in: ${result.errorOrNull()}")
        return result.value
    }

    /** A token for the viewer from the harness's own device, to read the server's state back. */
    private fun harnessToken(): String = server.http.signIn(server.viewer.name, server.viewer.rawPassword())

    /** Every request used only the modern `Authorization: MediaBrowser ...` form: no legacy header or query key. */
    @AfterEach
    fun everyRequestUsedOnlyTheModernAuthorizationHeader() {
        assertTrue(recorder.seen.isNotEmpty(), "the case made no request the recorder could see")
        for (request in recorder.seen) {
            val what = "${request.method} ${request.path}"
            assertTrue(request.authorization.size <= 1, "$what sent several Authorization headers")
            request.authorization.forEach { assertTrue(MODERN_HEADER.matches(it), "$what used an unexpected form") }
            assertEquals(emptyList(), request.legacyHeaders, "$what used a legacy header")
            assertEquals(emptyList(), request.legacyQueryNames, "$what used a legacy query key")
            if (request.path.endsWith("/System/Info/Public")) {
                // Not assertEquals on the list: a failure would print the header into the job log.
                assertTrue(request.authorization.isEmpty(), "the probe must carry no credential")
            }
        }
    }

    @Test
    fun probeFindsTheServerAndPassesTheVersionGate() {
        runBlocking {
            val adapter = adapter()

            val found = adapter.discover(address.baseUrl)
            assertIs<ServerResult.Success<ResolvedServer>>(found, "discover: ${found.errorOrNull()}")
            val connected = adapter.connect(address, acknowledgement)
            assertIs<ServerResult.Success<ResolvedServer>>(connected, "connect: ${connected.errorOrNull()}")

            assertEquals(SUPPORTED_MAJOR, connected.value.server.version.major)
            assertEquals(address, connected.value.address)
        }
    }

    @Test
    fun aWrongSubpathIsNotJellyfin() {
        runBlocking {
            val result = adapter().connect(address.copy(subpath = "/not-jellyfin"), acknowledgement)

            assertIs<MediaServerError.NotJellyfin>(result.errorOrNull())
        }
    }

    @Test
    fun passwordSignInGivesANonAdminSessionAndABadPasswordIsUnauthorized() {
        runBlocking {
            val adapter = adapter()

            val session = session(adapter)
            assertEquals(false, session.isAdministrator)
            assertEquals(address, session.address)
            val wrong = signIn(adapter, password = "not-the-password".toCharArray())
            assertIs<MediaServerError.HttpUnauthorized>(wrong.errorOrNull())
        }
    }

    @Test
    fun anAdminAndANonAdminBothSignInAndTheRightsAreRecorded() {
        runBlocking {
            // Spec 05, decision 9: any account may sign in, and the app only records what rights it has.
            val admin = signIn(adapter(), server.admin)
            assertIs<ServerResult.Success<Session>>(admin, "admin sign-in: ${admin.errorOrNull()}")
            assertEquals(true, admin.value.isAdministrator)

            val viewer = signIn(adapter(), server.viewer)
            assertIs<ServerResult.Success<Session>>(viewer, "non-admin sign-in: ${viewer.errorOrNull()}")
            assertEquals(false, viewer.value.isAdministrator)
        }
    }

    @Test
    fun aDisabledUserIsForbidden() {
        runBlocking {
            val result = signIn(adapter(), server.disabled)

            // 2.8 maps a locked user to HttpForbidden. If a real server answers differently, fix the mapper.
            assertIs<MediaServerError.HttpForbidden>(result.errorOrNull(), "disabled user: ${result.errorOrNull()}")
        }
    }

    @Test
    fun quickConnectSignsInOnceAnotherDeviceApprovesTheCode() {
        runBlocking {
            val approver = harnessToken()
            val states = mutableListOf<QuickConnectState>()

            val last =
                adapter()
                    .quickConnect(address, acknowledgement)
                    .onEach { state ->
                        states += state
                        if (state is QuickConnectState.CodeIssued) approve(approver, state.code)
                    }.first { it !is QuickConnectState.CodeIssued && it !is QuickConnectState.Waiting }

            assertIs<QuickConnectState.SignedIn>(last, "Quick Connect ended in ${last::class.simpleName}")
            assertIs<QuickConnectState.CodeIssued>(states.first())
            assertEquals(false, last.session.isAdministrator)
        }
    }

    private fun approve(
        token: String,
        code: String,
    ) {
        val reply = server.http.post("QuickConnect/Authorize", token = token, query = listOf("code" to code))
        check(reply.ok) { "harness step 'authorize Quick Connect' got $reply" }
    }

    @Test
    fun librariesAreTheVideoCapableOnesAndMixedHasNoCollectionType() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)

            val libraries = adapter.libraries(session).getOrNull()

            assertNotNull(libraries)
            val seeded = server.seeded
            val expected =
                mapOf(
                    LibraryKind.Movies to seeded.moviesLibrary,
                    LibraryKind.TvShows to seeded.tvLibrary,
                    LibraryKind.Mixed to seeded.mixedLibrary,
                )
            assertEquals(expected, libraries.associate { it.kind to it.id.value })
            // OQ3: the wire value for mixed content is an absent type on this server. If it ever isn't, this fails.
            val views = server.http.get("UserViews", harnessToken())
            check(views.ok) { "harness step 'read views' got $views" }
            val mixed =
                views
                    .obj()
                    .getValue("Items")
                    .jsonArray
                    .map { it.jsonObject }
                    .first { it.getValue("Id").jsonPrimitive.content == seeded.mixedLibrary }
            val type = mixed["CollectionType"]
            assertTrue(type == null || type is JsonNull, "mixed content has a collection type")
        }
    }

    @Test
    fun itemsPageThroughALibrary() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val movies = Library(ItemId(server.seeded.moviesLibrary), "Movies", LibraryKind.Movies)

            val first = adapter.items(session, movies, 0, PAGE_SIZE).getOrNull()
            val second = adapter.items(session, movies, PAGE_SIZE, PAGE_SIZE).getOrNull()

            assertNotNull(first)
            assertNotNull(second)
            val seeded = server.seeded.movieIds
            assertEquals(seeded.size, first.totalCount)
            assertEquals(seeded.size, second.totalCount)
            assertEquals(listOf(PAGE_SIZE, seeded.size - PAGE_SIZE), listOf(first.items.size, second.items.size))
            val items = first.items + second.items
            assertEquals(seeded.toSet(), items.map { it.id.value }.toSet())
            for (item in items) {
                assertEquals(ItemKind.Movie, item.kind)
                assertTrue((item.runtime ?: 0.seconds) > 5.minutes, "a short item can't store a resume point")
            }

            val shows = Library(ItemId(server.seeded.tvLibrary), "Shows", LibraryKind.TvShows)
            val series = adapter.items(session, shows, 0, PAGE_SIZE).getOrNull()
            assertEquals(listOf(ItemKind.Series), series?.items?.map { it.kind })
        }
    }

    @Test
    fun playbackInfoListsSourcesAndTheBuiltUrlStreamsWithTheHeader() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val item = ItemId(server.seeded.movieIds.first())

            val info = adapter.playbackInfo(session, item).getOrNull()

            assertNotNull(info)
            val source = info.sources.first()
            assertTrue(source.supportsDirectPlay || source.supportsDirectStream, "the profile should direct play H.264")
            val request =
                Request
                    .Builder()
                    .url(info.streams.directStream(source.id))
                    .header("Range", "bytes=0-1023")
                    .header("Authorization", AuthHeaderBuilder(identity).build("contract-stream", session.token))
                    .build()
            val code = OkHttpClient().newCall(request).execute().use { it.code }
            // Jellyfin also serves a static stream with no header at all (12.0 and 12.2 checked), so a missing header
            // can't be detected here; what is asserted: the URL, which carries no credential of its own
            // (StreamUrlTest), streams when the header is sent.
            assertTrue(code in STREAM_OK, "the built URL didn't stream with the header")
        }
    }

    @Test
    fun reportingStoresAResumePositionThatReadsBack() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val item = ItemId(server.seeded.movieIds.first())
            val info = adapter.playbackInfo(session, item).getOrNull()
            assertNotNull(info)
            val source = info.sources.first().id

            fun report(seconds: Int) = PlaybackReport(item, source, info.playSessionId, seconds.seconds)
            assertEquals(ServerResult.Success(Unit), adapter.reportStart(session, report(0)))
            assertEquals(ServerResult.Success(Unit), adapter.reportProgress(session, report(PROGRESS_SECONDS)))
            assertEquals(ServerResult.Success(Unit), adapter.reportStopped(session, report(RESUME_SECONDS)))

            val reply = server.http.get("Users/${session.userId.value}/Items/${item.value}", harnessToken())
            check(reply.ok) { "harness step 'read user data' got $reply" }
            val data = reply.obj().getValue("UserData").jsonObject
            val ticks =
                data
                    .getValue("PlaybackPositionTicks")
                    .jsonPrimitive.content
                    .toLong()
            assertEquals(RESUME_SECONDS * TICKS_PER_SECOND, ticks)
        }
    }

    @Test
    fun logoutInvalidatesTheTokenSoTheOldOneIsSessionExpired() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            assertNotNull(adapter.libraries(session).getOrNull())

            assertEquals(ServerResult.Success(Unit), adapter.revoke(session))

            assertIs<MediaServerError.SessionExpired>(adapter.libraries(session).errorOrNull())
        }
    }

    private companion object {
        const val SUPPORTED_MAJOR = 12
        const val PAGE_SIZE = 2
        const val PROGRESS_SECONDS = 10
        const val RESUME_SECONDS = 120
        const val TICKS_PER_SECOND = 10_000_000L
        val STREAM_OK = setOf(200, 206)
        val MODERN_HEADER =
            Regex(
                """MediaBrowser Client="[^"]+", Device="[^"]+", DeviceId="[^"]+", Version="[^"]+"(, Token="[^"]+")?""",
            )
    }
}

/** The suite against a server on its own port, at no subpath. */
class DirectContractTest : ContractSuite() {
    override fun startServer(image: String): JellyfinServer = JellyfinServer.direct(image)
}

/** The same suite through a Caddy proxy to a server whose Base URL is the subpath (no prefix stripping). */
class SubpathContractTest : ContractSuite() {
    override fun startServer(image: String): JellyfinServer = JellyfinServer.behindProxy(image)
}
