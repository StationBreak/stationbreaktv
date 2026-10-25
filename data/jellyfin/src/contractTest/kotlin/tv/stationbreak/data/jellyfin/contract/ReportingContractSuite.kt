// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.UserItemState
import tv.stationbreak.core.model.VideoCodecSupport
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.AdapterTuning
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F1.5 (ADR-0018): reporting a live sitting at a safe position leaves the user's data alone, on the real server.
 * Every case reports through the adapter's own calls and reads the user data back through `userData()`. Each case has
 * an item of its own, because the server keeps the state per item and the cases run in any order. Failure messages
 * name the step only: a body or a URL may carry a token.
 */
@Suppress("TooManyFunctions") // One function per contract case, plus the helpers they share.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ReportingContractSuite {
    internal abstract fun startServer(image: String): JellyfinServer

    private lateinit var server: JellyfinServer
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

    /** One signed-in viewer on one device, reporting one item. */
    private inner class Visit(
        val adapter: JellyfinMediaServer,
        val session: Session,
        val deviceId: String,
        val item: ItemId,
        private val source: MediaSourceId,
        private val playSession: PlaySessionId?,
    ) {
        fun report(position: Duration) =
            PlaybackReport(item, source, playSession, position, playMethod = PlayMethod.DirectPlay, canSeek = false)

        suspend fun start(position: Duration) = ok("start", adapter.reportStart(session, report(position)))

        suspend fun progress(position: Duration) = ok("progress", adapter.reportProgress(session, report(position)))

        suspend fun stop(
            position: Duration,
            failed: Boolean,
        ) = ok("stop", adapter.reportStopped(session, report(position), failed))

        suspend fun markPlayed() = ok("mark played", adapter.markPlayed(session, item))

        suspend fun state(): UserItemState {
            val result = adapter.userData(session, item)
            assertIs<ServerResult.Success<UserItemState>>(result, "userData answered an error")
            return result.value
        }

        private fun ok(
            step: String,
            result: ServerResult<Unit>,
        ) = assertEquals(ServerResult.Success(Unit), result, step)
    }

    private suspend fun visit(itemId: String): Visit {
        val deviceId = "contract-reporting-${UUID.randomUUID()}"
        val adapter =
            JellyfinMediaServer(
                OkHttpClient(),
                identity,
                DeviceIdProvider { deviceId },
                AdapterLog.NONE,
                AdapterTuning(),
            )
        val address = server.address
        val acknowledgement = if (address.cleartextNeedsConfirmation) CleartextAcknowledgement(address) else null
        val account = server.viewer
        val signedIn = adapter.signIn(SignInRequest(address, account.name, account.password(), acknowledgement))
        assertIs<ServerResult.Success<Session>>(signedIn, "sign-in")
        val item = ItemId(itemId)
        val info = adapter.playbackInfo(signedIn.value, item, request).getOrNull()
        assertNotNull(info, "playback info")
        return Visit(adapter, signedIn.value, deviceId, item, info.sources.first().id, info.playSessionId)
    }

    /** The viewer's resume list, read by the harness's own device. */
    private fun resumeIds(): Set<String> {
        val token = viewerToken
        val resume = server.http.get("UserItems/Resume", token, userQuery(token))
        val reply = server.http.expectOk("read resume list", resume)
        return reply
            .obj()
            .getValue("Items")
            .jsonArray
            .map {
                it.jsonObject
                    .getValue("Id")
                    .jsonPrimitive.content
                    .plain()
            }.toSet()
    }

    private fun rawUserData(itemId: String): JsonObject {
        val token = viewerToken
        return server.http
            .expectOk("read raw user data", server.http.get("UserItems/$itemId/UserData", token, userQuery(token)))
            .obj()
    }

    private val viewerToken: String by lazy { server.http.signIn(server.viewer.name, server.viewer.rawPassword()) }

    private fun userQuery(token: String): List<Pair<String, String>> =
        listOf(
            "userId" to
                server.http
                    .get("Users/Me", token)
                    .obj()
                    .getValue("Id")
                    .jsonPrimitive.content,
        )

    private fun String.plain() = replace("-", "").lowercase()

    /** Gives [itemId] the resume point [at] the way the app never does: a normal start and a normal stop. */
    private suspend fun seedResume(
        itemId: String,
        at: Duration,
    ) {
        val seeding = visit(itemId)
        seeding.start(Duration.ZERO)
        seeding.progress(at)
        seeding.stop(at, failed = false)
        assertEquals(UserItemState(at, played = false), seeding.state(), "seeding the resume point")
    }

    @Test
    fun anUnwatchedItemStaysUnwatched() {
        runBlocking {
            val v = visit(server.seeded.movieIds[0])

            v.start(Duration.ZERO)
            v.progress(Duration.ZERO)
            v.progress(Duration.ZERO)
            v.stop(Duration.ZERO, failed = true)

            assertEquals(UserItemState(Duration.ZERO, played = false), v.state())
        }
    }

    @Test
    fun aResumePointIsKept() {
        runBlocking {
            val itemId = server.seeded.movieIds[1]
            seedResume(itemId, RESUME)
            val v = visit(itemId)

            v.start(RESUME)
            v.progress(RESUME)
            v.stop(RESUME, failed = true)

            assertEquals(UserItemState(RESUME, played = false), v.state())
            // The raw read of the same state, so the adapter's parsing is not the only witness.
            val raw = rawUserData(itemId)
            val ticks =
                raw
                    .getValue("PlaybackPositionTicks")
                    .jsonPrimitive.content
                    .toLong()
            assertEquals(RESUME.inWholeSeconds * TICKS_PER_SECOND, ticks)
            assertEquals("false", raw.getValue("Played").jsonPrimitive.content)
        }
    }

    @Test
    fun aPlayedItemStaysPlayed() {
        runBlocking {
            val v = visit(server.seeded.movieIds[2])
            v.markPlayed()
            assertTrue(v.state().played, "the mark didn't take")

            v.start(Duration.ZERO)
            v.progress(Duration.ZERO)
            v.stop(Duration.ZERO, failed = true)

            assertTrue(v.state().played)
        }
    }

    @Test
    fun markingPlayedMidVisitHolds() {
        runBlocking {
            val itemId = server.seeded.episodeIds[0]
            seedResume(itemId, RESUME)
            assertTrue(itemId.plain() in resumeIds(), "the seeded item should be in the resume list")
            val v = visit(itemId)

            v.start(RESUME)
            v.markPlayed()
            v.progress(Duration.ZERO)
            v.stop(Duration.ZERO, failed = true)

            val state = v.state()
            assertTrue(state.played, "the mark was lost")
            assertEquals(Duration.ZERO, state.position)
            assertFalse(itemId.plain() in resumeIds(), "a played item is still in the resume list")
        }
    }

    @Test
    fun theDashboardShowsTheViewerPlayingTheItem() {
        runBlocking {
            val itemId = server.seeded.movieIds[0]
            val v = visit(itemId)
            v.start(Duration.ZERO)
            try {
                v.progress(Duration.ZERO)
                assertDashboardShows(v, itemId)
            } finally {
                v.stop(Duration.ZERO, failed = true)
            }
        }
    }

    private fun assertDashboardShows(
        v: Visit,
        itemId: String,
    ) {
        run {
            val admin = server.http.signIn(server.admin.name, server.admin.rawPassword())
            val reply = server.http.expectOk("read sessions", server.http.get("Sessions", admin))
            val mine =
                reply
                    .json()
                    .jsonArray
                    .map { it.jsonObject }
                    .filter { it["DeviceId"]?.jsonPrimitive?.content == v.deviceId }
            assertEquals(1, mine.size, "the viewer's device should have one session")
            val playing = mine.single()["NowPlayingItem"]
            assertNotNull(playing, "the session shows nothing playing")
            assertEquals(
                itemId.plain(),
                playing.jsonObject
                    .getValue("Id")
                    .jsonPrimitive.content
                    .plain(),
            )
        }
    }

    /**
     * Cases a to c keep the safe position, so they would pass even if the server ignored `Failed`. This pair tells the
     * two apart: the same stop at 40% of the runtime, once failed and once not. [aFailedStopSavesNothing] is the
     * claim; [aStopThatHasNotFailedSavesTheResumePoint] is the control that proves the server honours the flag.
     */
    @Test
    fun aFailedStopSavesNothing() {
        runBlocking {
            val itemId = server.seeded.episodeIds[1]
            val v = visit(itemId)

            v.start(Duration.ZERO)
            v.stop(FORTY_PERCENT, failed = true)

            assertEquals(UserItemState(Duration.ZERO, played = false), v.state())
            assertFalse(itemId.plain() in resumeIds(), "a failed stop left a resume point")
        }
    }

    /** The control for [aFailedStopSavesNothing]: the same visit without `Failed` does save the position. */
    @Test
    fun aStopThatHasNotFailedSavesTheResumePoint() {
        runBlocking {
            val itemId = server.seeded.episodeIds[2]
            val v = visit(itemId)

            v.start(Duration.ZERO)
            v.stop(FORTY_PERCENT, failed = false)

            assertEquals(UserItemState(FORTY_PERCENT, played = false), v.state())
            assertTrue(itemId.plain() in resumeIds(), "the control stop saved no resume point")
        }
    }

    private val request =
        PlaybackRequest(
            capabilities =
                DeviceCapabilities(
                    containers = setOf("mp4"),
                    videoCodecs = listOf(VideoCodecSupport("h264", emptySet(), null, 1920, 1080, 8, emptySet())),
                    audioDecoders = setOf("aac"),
                    audioPassthrough = emptySet(),
                    maxAudioChannels = 2,
                ),
            start = Duration.ZERO,
            audioIndex = null,
            subtitleIndex = null,
            forceTranscode = false,
        )

    private companion object {
        /** Half of the seeded 330 s items. */
        val RESUME = 165.seconds

        /** 40% of the seeded 330 s items. */
        val FORTY_PERCENT = 132.seconds
        const val TICKS_PER_SECOND = 10_000_000L
    }
}

/** The reporting suite against a server on its own port. */
class DirectReportingContractTest : ReportingContractSuite() {
    override fun startServer(image: String): JellyfinServer = JellyfinServer.direct(image)
}
