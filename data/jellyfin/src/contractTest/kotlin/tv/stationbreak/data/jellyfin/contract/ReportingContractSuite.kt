// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.coroutines.delay
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F1.5 (ADR-0018): reporting a live sitting at a safe position leaves the user's data alone, on the real server.
 * Every case reports through the adapter's own calls and reads the user data back through `userData()`. The server
 * keeps the state per item and the cases run in any order, and the seed has only six items, so cases share an item
 * only where every case's end state is independent of order (an unwatched, resume-pointed or played item stays so;
 * counts are compared before and after). Failure messages name the step only: a body or a URL may carry a token.
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
        fun report(
            position: Duration,
            paused: Boolean = false,
        ) = PlaybackReport(
            item,
            source,
            playSession,
            position,
            isPaused = paused,
            playMethod = PlayMethod.DirectPlay,
            canSeek = false,
        )

        suspend fun start(position: Duration) = ok("start", adapter.reportStart(session, report(position)))

        suspend fun progress(
            position: Duration,
            paused: Boolean = false,
        ) = ok("progress", adapter.reportProgress(session, report(position, paused)))

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
        val playing = dashboardSession(v)["NowPlayingItem"]
        assertNotNull(playing, "the session shows nothing playing")
        assertEquals(
            itemId.plain(),
            playing.jsonObject
                .getValue("Id")
                .jsonPrimitive.content
                .plain(),
        )
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

    // Spec 20 (ADR-0021): a paused sitting is checked in with `IsPaused = true` and must leave the user's data alone,
    // the same as a playing one.

    /** Paused check-ins between playing ones leave an unwatched item unwatched. */
    @Test
    fun pausedCheckInsLeaveAnUnwatchedItemUnwatched() {
        runBlocking {
            val v = visit(server.seeded.movieIds[0])

            v.start(Duration.ZERO)
            repeat(PAUSED_CHECK_INS) { v.progress(Duration.ZERO, paused = true) }
            v.progress(Duration.ZERO)
            v.stop(Duration.ZERO, failed = true)

            assertEquals(UserItemState(Duration.ZERO, played = false), v.state())
        }
    }

    @Test
    fun pausedCheckInsKeepAResumePoint() {
        runBlocking {
            val itemId = server.seeded.movieIds[1]
            seedResume(itemId, RESUME)
            val v = visit(itemId)

            v.start(RESUME)
            repeat(PAUSED_CHECK_INS) { v.progress(RESUME, paused = true) }
            v.stop(RESUME, failed = true)

            assertEquals(UserItemState(RESUME, played = false), v.state())
        }
    }

    @Test
    fun pausedCheckInsKeepAPlayedItemPlayed() {
        runBlocking {
            val v = visit(server.seeded.movieIds[2])
            v.markPlayed()
            assertTrue(v.state().played, "the mark didn't take")

            v.start(Duration.ZERO)
            repeat(PAUSED_CHECK_INS) { v.progress(Duration.ZERO, paused = true) }
            v.stop(Duration.ZERO, failed = true)

            assertTrue(v.state().played)
        }
    }

    /**
     * A fresh Playing after a pause (same `PlaySessionId`) counts as a play on the server, so it moves `PlayCount` and
     * nothing else. The gap: the second Playing here comes **without** the server's idle auto-stop (about 10 min) in
     * between, because the long timers are too slow for CI. The full sequence (5 min of paused check-ins, silence, the
     * auto-stop, then a fresh Playing) is covered by the SP-7 re-run before the matrix moves, not by this suite.
     */
    @Test
    fun aSecondPlayingAfterAPauseChangesOnlyThePlayCount() {
        runBlocking {
            val itemId = server.seeded.movieIds[0]
            val v = visit(itemId)
            val before = v.state()
            val playCount0 = playCount(itemId)

            v.start(Duration.ZERO)
            v.progress(Duration.ZERO, paused = true)
            v.start(Duration.ZERO)
            v.progress(Duration.ZERO)
            v.stop(Duration.ZERO, failed = true)

            assertEquals(before, v.state())
            assertEquals(playCount0 + 2, playCount(itemId), "the play count moved by something other than two plays")
        }
    }

    @Test
    fun theDashboardShowsThePausedItem() {
        runBlocking {
            val itemId = server.seeded.movieIds[0]
            val v = visit(itemId)
            v.start(Duration.ZERO)
            try {
                v.progress(Duration.ZERO, paused = true)
                val mine = dashboardSession(v)
                val playing = mine["NowPlayingItem"]
                assertNotNull(playing, "the session shows nothing playing")
                assertEquals(
                    itemId.plain(),
                    playing.jsonObject
                        .getValue("Id")
                        .jsonPrimitive.content
                        .plain(),
                )
                val paused =
                    mine
                        .getValue("PlayState")
                        .jsonObject["IsPaused"]
                        ?.jsonPrimitive
                        ?.content
                assertEquals("true", paused, "the dashboard doesn't show the item as paused")
            } finally {
                v.stop(Duration.ZERO, failed = true)
            }
        }
    }

    /**
     * Tripwire for #511: records what each Jellyfin version does to `LastPlayedDate` on a positioned check-in of an
     * item that has a resume point. A change in either direction fails loudly, so the mitigation gets decided.
     */
    @Test
    fun lastPlayedDateAfterACheckInMatchesTheKnownBehaviour() {
        runBlocking {
            val version = serverVersion()
            val itemId = server.seeded.movieIds[1]
            seedResume(itemId, RESUME)
            val v = visit(itemId)

            v.start(RESUME)
            val first: Instant
            val second: Instant
            val third: Instant
            try {
                first = lastPlayed(itemId)
                delay(LAST_PLAYED_GAP)
                v.progress(RESUME)
                second = lastPlayed(itemId)
                delay(LAST_PLAYED_GAP)
                v.progress(RESUME, paused = true)
                third = lastPlayed(itemId)
            } finally {
                v.stop(RESUME, failed = true)
            }

            when (version) {
                V12_1 -> {
                    assertEquals(first, second, "12.1 moved LastPlayedDate on a check-in (#511)")
                    assertEquals(second, third, "12.1 moved LastPlayedDate on a paused check-in (#511)")
                }

                V12_2 -> {
                    assertTrue(
                        second.isAfter(first),
                        "12.2 moves LastPlayedDate on a check-in (#511): if this fails, recheck #511",
                    )
                    assertTrue(
                        third.isAfter(second),
                        "12.2 moves LastPlayedDate on a paused check-in (#511): if this fails, recheck #511",
                    )
                }

                else -> {
                    fail("record this version's LastPlayedDate behaviour (#511)")
                }
            }
        }
    }

    private fun playCount(itemId: String): Int =
        rawUserData(itemId)
            .getValue("PlayCount")
            .jsonPrimitive.content
            .toInt()

    private fun lastPlayed(itemId: String): Instant {
        val raw = rawUserData(itemId)["LastPlayedDate"]?.jsonPrimitive?.content
        assertNotNull(raw, "the item has no LastPlayedDate")
        return Instant.parse(raw)
    }

    /** The server's major and minor version, from the unauthenticated `System/Info/Public`. */
    private fun serverVersion(): Pair<Int, Int> {
        val version =
            server.http
                .expectOk("read server version", server.http.get("System/Info/Public"))
                .obj()
                .getValue("Version")
                .jsonPrimitive.content
        val parts = version.split('.').map { it.toIntOrNull() }
        val major = parts.getOrNull(0)
        val minor = parts.getOrNull(1)
        if (major == null || minor == null) fail("read server version: not a version number")
        return Pair(major, minor)
    }

    /** The viewer device's one session on the dashboard. */
    private fun dashboardSession(v: Visit): JsonObject {
        val admin = server.http.signIn(server.admin.name, server.admin.rawPassword())
        val reply = server.http.expectOk("read sessions", server.http.get("Sessions", admin))
        val mine =
            reply
                .json()
                .jsonArray
                .map { it.jsonObject }
                .filter { it["DeviceId"]?.jsonPrimitive?.content == v.deviceId }
        assertEquals(1, mine.size, "the viewer's device should have one session")
        return mine.single()
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
        const val PAUSED_CHECK_INS = 3
        val LAST_PLAYED_GAP = 2.seconds
        val V12_1 = Pair(12, 1)
        val V12_2 = Pair(12, 2)
    }
}

/** The reporting suite against a server on its own port. */
class DirectReportingContractTest : ReportingContractSuite() {
    override fun startServer(image: String): JellyfinServer = JellyfinServer.direct(image)
}
