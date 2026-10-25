// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserItemState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `reportStart`, `reportProgress`, `reportStopped`. Cadence and the played threshold are S1.5.1's, not tested. */
class ReportingTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun report(
        position: Duration = 0.seconds,
        paused: Boolean = false,
        playSession: PlaySessionId? = PlaySessionId("play-1"),
    ) = PlaybackReport(ItemId("i1"), MediaSourceId("src1"), playSession, position, paused)

    private data class Call(
        val name: String,
        val path: String,
        val send: suspend (MediaServer, Session, PlaybackReport) -> ServerResult<Unit>,
    )

    private val calls =
        listOf(
            Call("start", "/Sessions/Playing") { s, session, r -> s.reportStart(session, r) },
            Call("progress", "/Sessions/Playing/Progress") { s, session, r -> s.reportProgress(session, r) },
            Call("stopped", "/Sessions/Playing/Stopped") { s, session, r -> s.reportStopped(session, r) },
        )

    private fun body(path: String) =
        Json
            .parseToJsonElement(
                jellyfin.log
                    .last { it.url.encodedPath == path }
                    .body!!
                    .utf8(),
            ).jsonObject

    @Test
    fun eachCallPostsToItsOwnEndpointWithTheItemAndTheSource() {
        runBlocking {
            for (call in calls) {
                jellyfin.on("POST", call.path) { jellyfin.status(204) }

                val result = call.send(testAdapter(), testSession(jellyfin.address), report(5.seconds))

                assertEquals(ServerResult.Success(Unit), result, call.name)
                val request = jellyfin.log.last { it.url.encodedPath == call.path }
                assertEquals("POST", request.method, call.name)
                val sent = body(call.path)
                assertEquals("i1", sent["ItemId"]!!.jsonPrimitive.content, call.name)
                assertEquals("src1", sent["MediaSourceId"]!!.jsonPrimitive.content, call.name)
                assertEquals("play-1", sent["PlaySessionId"]!!.jsonPrimitive.content, call.name)
            }
        }
    }

    @Test
    fun positionTicksAreOneHundredNanosecondUnits() {
        runBlocking {
            val cases =
                listOf(
                    0.seconds to 0L,
                    1.seconds to 10_000_000L,
                    1.hours to 36_000_000_000L,
                    1500.milliseconds to 15_000_000L,
                )
            jellyfin.on("POST", "/Sessions/Playing/Progress") { jellyfin.status(204) }
            for ((position, ticks) in cases) {
                testAdapter().reportProgress(testSession(jellyfin.address), report(position))

                val sent = body("/Sessions/Playing/Progress")["PositionTicks"]!!.jsonPrimitive.long
                assertEquals(ticks, sent, "$position")
            }
        }
    }

    @Test
    fun aPositionNearTheLimitClampsInsteadOfOverflowing() {
        runBlocking {
            jellyfin.on("POST", "/Sessions/Playing/Progress") { jellyfin.status(204) }

            val huge = listOf(Duration.INFINITE, Long.MAX_VALUE.milliseconds, (Long.MAX_VALUE / 2).milliseconds)
            for (position in huge) {
                testAdapter().reportProgress(testSession(jellyfin.address), report(position))

                val ticks = body("/Sessions/Playing/Progress")["PositionTicks"]!!.jsonPrimitive.long
                assertTrue(ticks > 0, "$position gave $ticks")
            }
        }
    }

    @Test
    fun aNegativePositionIsZero() {
        runBlocking {
            jellyfin.on("POST", "/Sessions/Playing/Progress") { jellyfin.status(204) }

            testAdapter().reportProgress(testSession(jellyfin.address), report((-5).seconds))

            assertEquals(0L, body("/Sessions/Playing/Progress")["PositionTicks"]!!.jsonPrimitive.long)
        }
    }

    @Test
    fun pausedIsReportedOnStartAndProgress() {
        runBlocking {
            jellyfin.on("POST", "/Sessions/Playing/Progress") { jellyfin.status(204) }

            testAdapter().reportProgress(testSession(jellyfin.address), report(paused = true))

            assertTrue(body("/Sessions/Playing/Progress")["IsPaused"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun aMissingPlaySessionIsLeftOutNotSentAsNull() {
        runBlocking {
            jellyfin.on("POST", "/Sessions/Playing") { jellyfin.status(204) }

            testAdapter().reportStart(testSession(jellyfin.address), report(playSession = null))

            assertFalse("PlaySessionId" in body("/Sessions/Playing"))
        }
    }

    @Test
    fun everyCallGoesThroughTheGateTheNoRedirectClientAndMapsAFailure() {
        runBlocking {
            for (call in calls) {
                jellyfin.log.clear()
                jellyfin.on("POST", call.path) { jellyfin.redirect("http://127.0.0.1:1/x") }
                val redirected = call.send(testAdapter(), testSession(jellyfin.address), report()).errorOrNull()
                assertIs<MediaServerError.Redirected>(redirected, call.name)
                assertEquals(1, jellyfin.requests(call.path).size, call.name)

                jellyfin.log.clear()
                val blocked = call.send(testAdapter(), testSession(jellyfin.fallbackAddress), report()).errorOrNull()
                assertIs<MediaServerError.CleartextNotConfirmed>(blocked, call.name)
                assertEquals(emptyList(), jellyfin.log.toList(), call.name)

                jellyfin.on("POST", call.path) { jellyfin.status(401) }
                val expired = call.send(testAdapter(), testSession(jellyfin.address), report()).errorOrNull()
                assertIs<MediaServerError.SessionExpired>(expired, call.name)
            }
        }
    }

    @Test
    fun theTokenIsInTheHeaderOnlyNeverTheBodyOrTheUrl() {
        runBlocking {
            val token = fake("token")
            for (call in calls) {
                jellyfin.on("POST", call.path) { jellyfin.status(204) }
                call.send(testAdapter(), testSession(jellyfin.address, token), report(1.seconds))
            }

            for (request in jellyfin.log.filter { it.method == "POST" }) {
                assertFalse(token in request.body!!.utf8())
                assertFalse(token in request.url.toString())
                assertTrue(request.headers["Authorization"]!!.contains("Token=\"$token\""))
            }
        }
    }

    @Test
    fun stoppedSendsFailedOnlyWhenAskedAndOtherCallsNever() {
        runBlocking {
            jellyfin.on("POST", "/Sessions/Playing/Stopped") { jellyfin.status(204) }
            jellyfin.on("POST", "/Sessions/Playing") { jellyfin.status(204) }
            jellyfin.on("POST", "/Sessions/Playing/Progress") { jellyfin.status(204) }

            testAdapter().reportStopped(testSession(jellyfin.address), report(), failed = true)
            assertTrue(body("/Sessions/Playing/Stopped")["Failed"]!!.jsonPrimitive.boolean)

            testAdapter().reportStopped(testSession(jellyfin.address), report())
            assertFalse("Failed" in body("/Sessions/Playing/Stopped"))

            testAdapter().reportStart(testSession(jellyfin.address), report())
            testAdapter().reportProgress(testSession(jellyfin.address), report())
            assertFalse("Failed" in body("/Sessions/Playing"))
            assertFalse("Failed" in body("/Sessions/Playing/Progress"))
        }
    }

    @Test
    fun startAndProgressCarryPlayMethodAndCanSeekAndStoppedLeavesPlayMethodOut() {
        runBlocking {
            val full = report().copy(playMethod = PlayMethod.DirectStream, canSeek = false)
            for (call in calls.take(2)) {
                jellyfin.on("POST", call.path) { jellyfin.status(204) }
                call.send(testAdapter(), testSession(jellyfin.address), full)
                val sent = body(call.path)
                assertEquals("DirectStream", sent["PlayMethod"]!!.jsonPrimitive.content, call.name)
                assertFalse(sent["CanSeek"]!!.jsonPrimitive.boolean, call.name)
            }
            for ((method, wire) in listOf(PlayMethod.DirectPlay to "DirectPlay", PlayMethod.Transcode to "Transcode")) {
                testAdapter().reportStart(testSession(jellyfin.address), report().copy(playMethod = method))
                assertEquals(wire, body("/Sessions/Playing")["PlayMethod"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun aReportWithNoPlayMethodLeavesItOutAndCanSeekDefaultsTrue() {
        runBlocking {
            jellyfin.on("POST", "/Sessions/Playing") { jellyfin.status(204) }

            testAdapter().reportStart(testSession(jellyfin.address), report())

            assertFalse("PlayMethod" in body("/Sessions/Playing"))
            assertTrue(body("/Sessions/Playing")["CanSeek"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun userDataReadsPositionAndPlayed() {
        runBlocking {
            jellyfin.on("GET", "/UserItems/i1/UserData") {
                jellyfin.json("{\"PlaybackPositionTicks\":15000000,\"Played\":true,\"Key\":\"x\"}")
            }

            val result = testAdapter().userData(testSession(jellyfin.address), ItemId("i1"))

            assertEquals(ServerResult.Success(UserItemState(1500.milliseconds, true)), result)
        }
    }

    @Test
    fun userDataWithNothingSetIsZeroAndNotPlayed() {
        runBlocking {
            jellyfin.on("GET", "/UserItems/i1/UserData") { jellyfin.json("{}") }

            val result = testAdapter().userData(testSession(jellyfin.address), ItemId("i1"))

            assertEquals(ServerResult.Success(UserItemState(0.seconds, false)), result)
        }
    }

    @Test
    fun markPlayedPostsToUserPlayedItems() {
        runBlocking {
            jellyfin.on("POST", "/UserPlayedItems/i1") { jellyfin.json("{\"Played\":true}") }

            val result = testAdapter().markPlayed(testSession(jellyfin.address), ItemId("i1"))

            assertEquals(ServerResult.Success(Unit), result)
            assertEquals("POST", jellyfin.requests("/UserPlayedItems/i1").single().method)
        }
    }

    @Test
    fun userDataAndMarkPlayedMapA401ToSessionExpired() {
        runBlocking {
            jellyfin.on("GET", "/UserItems/i1/UserData") { jellyfin.status(401) }
            jellyfin.on("POST", "/UserPlayedItems/i1") { jellyfin.status(401) }

            val read = testAdapter().userData(testSession(jellyfin.address), ItemId("i1")).errorOrNull()
            val mark = testAdapter().markPlayed(testSession(jellyfin.address), ItemId("i1")).errorOrNull()

            assertIs<MediaServerError.SessionExpired>(read)
            assertIs<MediaServerError.SessionExpired>(mark)
        }
    }
}
