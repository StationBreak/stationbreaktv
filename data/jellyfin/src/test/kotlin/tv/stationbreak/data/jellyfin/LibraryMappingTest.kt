// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** S0.3.3: `GET /UserViews` becomes the video-capable libraries, and only those. */
class LibraryMappingTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun view(
        id: String,
        name: String,
        type: String?,
    ): String {
        val collectionType = if (type == null) "" else ",\"CollectionType\":\"$type\""
        return "{\"Id\":\"$id\",\"Name\":\"$name\",\"Type\":\"CollectionFolder\"$collectionType}"
    }

    private fun views(vararg items: String): MockResponse {
        val list = items.joinToString(",")
        return jellyfin.json("{\"Items\":[$list],\"TotalRecordCount\":${items.size}}")
    }

    @Test
    fun keepsMoviesTvShowsMixedHomeVideosAndMusicVideos() {
        runBlocking {
            jellyfin.on("GET", "/UserViews") {
                views(
                    view("a1", "Films", "movies"),
                    view("a2", "Series", "tvshows"),
                    view("a3", "Everything", null),
                    view("a4", "Clips", "homevideos"),
                    view("a5", "Concerts", "musicvideos"),
                )
            }

            val libraries = testAdapter().libraries(testSession(jellyfin.address)).getOrNull()

            assertEquals(
                listOf(
                    Library(ItemId("a1"), "Films", LibraryKind.Movies),
                    Library(ItemId("a2"), "Series", LibraryKind.TvShows),
                    Library(ItemId("a3"), "Everything", LibraryKind.Mixed),
                    Library(ItemId("a4"), "Clips", LibraryKind.HomeVideos),
                    Library(ItemId("a5"), "Concerts", LibraryKind.MusicVideos),
                ),
                libraries,
            )
        }
    }

    @Test
    fun dropsPlaylistsBoxsetsMusicAndEverythingElse() {
        runBlocking {
            jellyfin.on("GET", "/UserViews") {
                views(
                    view("b1", "Playlists", "playlists"),
                    view("b2", "Collections", "boxsets"),
                    view("b3", "Songs", "music"),
                    view("b4", "Books", "books"),
                    view("b5", "Photos", "photos"),
                    view("b6", "Live", "livetv"),
                    view("b7", "Trailers", "trailers"),
                    view("b8", "Files", "folders"),
                    view("b9", "Future", "somethingfromthefuture"),
                    view("b10", "Odd case", "Movies"),
                    view("b11", "Empty", ""),
                    view("keep", "Films", "movies"),
                )
            }

            val libraries = testAdapter().libraries(testSession(jellyfin.address)).getOrNull()

            // An unknown wire value is dropped, never read as the absent value that means "mixed".
            assertEquals(listOf("keep"), libraries?.map { it.id.value })
        }
    }

    @Test
    fun anEmptyAnswerIsNoLibraries() {
        runBlocking {
            jellyfin.on("GET", "/UserViews") { views() }

            assertEquals(ServerResult.Success(emptyList()), testAdapter().libraries(testSession(jellyfin.address)))
        }
    }

    @Test
    fun sendsOneGetWithTheTokenAndNoCredentialInTheUrl() {
        runBlocking {
            val token = fake("token")
            jellyfin.on("GET", "/UserViews") { views() }

            testAdapter().libraries(testSession(jellyfin.address, token))

            val request = jellyfin.requests("/UserViews").single()
            assertEquals("GET", request.method)
            assertTrue(request.headers["Authorization"]!!.contains("Token=\"$token\""))
            assertEquals(null, request.url.queryParameter("ApiKey"))
            assertEquals(null, request.url.queryParameter("api_key"))
            assertTrue(token !in request.url.toString())
        }
    }

    @Test
    fun aRejectedTokenIsSessionExpired() {
        runBlocking {
            jellyfin.on("GET", "/UserViews") { jellyfin.status(401) }

            val error = testAdapter().libraries(testSession(jellyfin.address)).errorOrNull()

            assertIs<MediaServerError.SessionExpired>(error)
        }
    }

    @Test
    fun aRedirectIsNotFollowedAndSendsNothingElse() {
        runBlocking {
            jellyfin.on("GET", "/UserViews") { jellyfin.redirect("http://127.0.0.1:1/UserViews") }

            val error = testAdapter().libraries(testSession(jellyfin.address)).errorOrNull()

            assertIs<MediaServerError.Redirected>(error)
            assertEquals(1, jellyfin.requests("/UserViews").size)
        }
    }

    @Test
    fun cleartextThatNeedsConfirmationSendsNothingWithoutIt() {
        runBlocking {
            jellyfin.on("GET", "/UserViews") { views() }
            val session = testSession(jellyfin.fallbackAddress)

            val error = testAdapter().libraries(session).errorOrNull()

            assertIs<MediaServerError.CleartextNotConfirmed>(error)
            assertEquals(emptyList(), jellyfin.log.toList())
        }
    }

    @Test
    fun cleartextWithItsAcknowledgementIsSent() {
        runBlocking {
            jellyfin.on("GET", "/UserViews") { views(view("c1", "Films", "movies")) }
            val acknowledgement = CleartextAcknowledgement(jellyfin.fallbackAddress)

            val session = testSession(jellyfin.fallbackAddress, acknowledgement = acknowledgement)
            val result = testAdapter().libraries(session)

            assertEquals(1, result.getOrNull()?.size)
        }
    }

    @Test
    fun aTokenForAnotherServerIsNotSent() {
        runBlocking {
            jellyfin.on("GET", "/UserViews") { views() }
            jellyfin.on("GET", "/System/Info/Public") { jellyfin.json(JELLYFIN_BODY.replace("0123abcd", "ffffffff")) }

            val error = testAdapter().libraries(testSession(jellyfin.address)).errorOrNull()

            assertIs<MediaServerError.ServerMismatch>(error)
            assertEquals(emptyList(), jellyfin.requests("/UserViews"))
        }
    }

    @Test
    fun anAnswerThatIsNotJellyfinIsNotJellyfin() {
        runBlocking {
            jellyfin.on("GET", "/UserViews") { jellyfin.json("<html>hello</html>") }

            val error = testAdapter().libraries(testSession(jellyfin.address)).errorOrNull()

            assertIs<MediaServerError.NotJellyfin>(error)
        }
    }
}
