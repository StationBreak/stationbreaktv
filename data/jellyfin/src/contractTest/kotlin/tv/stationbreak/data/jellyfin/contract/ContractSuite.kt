// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
    fun itemsReturnEpisodesAndCreationDate() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val shows = Library(ItemId(server.seeded.tvLibrary), "Shows", LibraryKind.TvShows)

            val page = adapter.libraryItems(session, shows, 0, BIG_PAGE).getOrNull()

            assertNotNull(page)
            // The TV library's direct children are series; the recursive call reaches the episodes under them.
            assertEquals(server.seeded.episodeIds.toSet(), page.items.map { it.id.value }.toSet())
            assertEquals(server.seeded.episodeIds.size, page.totalCount)
            for (item in page.items) {
                assertEquals(ItemKind.Episode, item.kind)
                assertNotNull(item.seriesId, "an episode names its series")
                assertEquals(1, item.seasonNumber)
                assertTrue(item.episodeNumber in 1..server.seeded.episodeIds.size)
                assertTrue((item.runtime ?: 0.seconds) > 5.minutes)
                // The creation date is what the server holds, read back raw with the harness's own device.
                val raw = rawItem(item.id.value)
                val rawCreated = Instant.parse(raw.getValue("DateCreated").jsonPrimitive.content)
                assertEquals(Instant.fromEpochMilliseconds(rawCreated.toEpochMilliseconds()), item.dateCreated)
            }
            val movies = Library(ItemId(server.seeded.moviesLibrary), "Movies", LibraryKind.Movies)
            val movieItems =
                adapter
                    .libraryItems(session, movies, 0, BIG_PAGE)
                    .getOrNull()
                    ?.items
                    .orEmpty()
            assertEquals(server.seeded.movieIds.toSet(), movieItems.map { it.id.value }.toSet())
            assertTrue(movieItems.all { it.kind == ItemKind.Movie && it.dateCreated != null })
            assertTrue(movieItems.all { it.productionYear in FIRST_YEAR..LAST_YEAR })
        }
    }

    @Test
    fun premiereDateArrives() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val shows = Library(ItemId(server.seeded.tvLibrary), "Shows", LibraryKind.TvShows)
            assertTrue(server.seeded.premieres.size >= 2, "the server seeds at least two premiere dates")

            val page = adapter.libraryItems(session, shows, 0, BIG_PAGE).getOrNull()

            assertNotNull(page)
            val byId = page.items.associateBy { it.id.value }
            for ((id, expected) in server.seeded.premieres) {
                assertEquals(expected, byId.getValue(id).premiereDate, "premiere date of $id")
            }
            // Every item agrees with the server's own text, read raw: the UTC date of it, or none.
            for (item in page.items) {
                val text = rawItem(item.id.value)["PremiereDate"]?.jsonPrimitive?.content
                val utcDate =
                    text?.let {
                        OffsetDateTime
                            .parse(it)
                            .withOffsetSameInstant(ZoneOffset.UTC)
                            .toLocalDate()
                            .toString()
                    }
                assertEquals(utcDate, item.premiereDate, "premiere date of ${item.id.value} (raw $text)")
            }
            // Open question 1: does Jellyfin send PremiereDate without it being in Fields?
            val query =
                listOf("parentId" to server.seeded.tvLibrary, "recursive" to "true", "includeItemTypes" to "Episode")
            val bare = server.http.expectOk("items without fields", server.http.get("Items", harnessToken(), query))
            val sent =
                bare
                    .obj()
                    .getValue("Items")
                    .jsonArray
                    .map { it.jsonObject }
                    .count { "PremiereDate" in it }
            finding("PremiereDate without Fields: $sent of ${server.seeded.episodeIds.size} episodes carried it")
            val stored =
                server.seeded.premieres.keys
                    .map { rawItem(it)["PremiereDate"]?.jsonPrimitive?.content }
            finding("seeded premiere dates as the server stores them: $stored")
        }
    }

    @Test
    fun pagesOfOneNeverSkipOrRepeatAndTheIdListingMatches() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val mixed = Library(ItemId(server.seeded.mixedLibrary), "Everything", LibraryKind.Mixed)
            val shows = Library(ItemId(server.seeded.tvLibrary), "Shows", LibraryKind.TvShows)

            for (library in listOf(shows, mixed)) {
                val whole = adapter.libraryItems(session, library, 0, BIG_PAGE).getOrNull()
                assertNotNull(whole)
                val oneAtATime =
                    (0..whole.items.size).flatMap { index ->
                        adapter
                            .libraryItems(session, library, index, 1)
                            .getOrNull()
                            ?.items
                            .orEmpty()
                    }
                assertEquals(whole.items.map { it.id }, oneAtATime.map { it.id }, "${library.kind} by pages of one")
                val ids = adapter.libraryItemIds(session, library, 0, BIG_PAGE).getOrNull()
                assertEquals(whole.items.map { it.id }, ids?.ids, "${library.kind} id listing")
                assertEquals(whole.items.size, ids?.totalCount)
            }
        }
    }

    @Test
    fun tiedNamesPageStably() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val movies = Library(ItemId(server.seeded.moviesLibrary), "Movies", LibraryKind.Movies)
            val admin = server.http.signIn(server.admin.name, server.admin.rawPassword())
            val adminId =
                server.http
                    .get("Users/Me", admin)
                    .obj()
                    .getValue("Id")
                    .jsonPrimitive.content
            for (id in server.seeded.movieIds) {
                val dto = server.http.get("Users/$adminId/Items/$id", admin).obj()
                val name = JsonPrimitive("Tie")
                val tied = JsonObject(dto + ("Name" to name) + ("ForcedSortName" to name))
                server.http.expectOk("tie", server.http.post("Items/$id", tied.toString(), admin))
            }
            delay(SETTLE_MS * TIE_SETTLE)

            val whole =
                adapter
                    .libraryItems(session, movies, 0, BIG_PAGE)
                    .getOrNull()
                    ?.items
                    .orEmpty()
            val oneAtATime =
                (0..whole.size).flatMap {
                    adapter
                        .libraryItems(session, movies, it, 1)
                        .getOrNull()
                        ?.items
                        .orEmpty()
                }

            assertEquals(server.seeded.movieIds.toSet(), whole.map { it.id.value }.toSet())
            assertEquals(whole.map { it.id }, oneAtATime.map { it.id }, "one name, no skip or repeat")
            // Jellyfin 12 has no sort by ID; it breaks a tie on the ID itself, ascending (the request says SortName).
            assertEquals(whole.map { it.id.value }.sorted(), whole.map { it.id.value }, "ties break on ascending ID")
        }
    }

    @Test
    fun changedSinceBoundary() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val movies = Library(ItemId(server.seeded.moviesLibrary), "Movies", LibraryKind.Movies)
            val (renamed, refreshed, watched) = server.seeded.movieIds
            val admin = server.http.signIn(server.admin.name, server.admin.rawPassword())
            val adminId =
                server.http
                    .get("Users/Me", admin)
                    .obj()
                    .getValue("Id")
                    .jsonPrimitive.content

            suspend fun changedSince(moment: Instant): Set<String> =
                adapter
                    .libraryItems(session, movies, 0, BIG_PAGE, moment)
                    .getOrNull()
                    ?.items
                    .orEmpty()
                    .map { it.id.value }
                    .toSet()

            // The server does not return DateLastSaved, even when asked, so the boundary is found by searching.
            val everything = Instant.parse("2000-01-01T00:00:00Z")
            assertEquals(server.seeded.movieIds.toSet(), changedSince(everything), "long ago matches every item")
            val future = Instant.fromEpochMilliseconds(System.currentTimeMillis() + FUTURE_MS)
            assertEquals(emptySet(), changedSince(future), "a moment in the future matches nothing")

            suspend fun edit(
                label: String,
                id: String,
                action: () -> Unit,
            ): Set<String> {
                delay(SETTLE_MS)
                val mark = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                delay(SETTLE_MS)
                action()
                delay(SETTLE_MS * EDIT_SETTLE)
                val changed = changedSince(mark)
                finding("$label: item returned=${id in changed}, others returned=${(changed - id).size}")
                return changed
            }

            val rename =
                edit("rename", renamed) {
                    val dto = server.http.get("Users/$adminId/Items/$renamed", admin).obj()
                    val edited = JsonObject(dto + ("Name" to JsonPrimitive("Renamed by the contract test")))
                    server.http.expectOk("rename", server.http.post("Items/$renamed", edited.toString(), admin))
                }
            assertEquals(setOf(renamed), rename, "a rename counts as a change, and only for the renamed item")

            // Where is the edge? Search for the last whole millisecond that still returns the renamed item.
            var low = Instant.fromEpochMilliseconds(System.currentTimeMillis() - BISECT_WINDOW_MS)
            var high = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            check(renamed in changedSince(low) && renamed !in changedSince(high)) { "the edge is outside the window" }
            while (high.toEpochMilliseconds() - low.toEpochMilliseconds() > 1) {
                val middle = Instant.fromEpochMilliseconds((low.toEpochMilliseconds() + high.toEpochMilliseconds()) / 2)
                if (renamed in changedSince(middle)) low = middle else high = middle
            }
            finding("rename: the item stops matching after $low (the moment it was saved, to the millisecond)")

            val refresh =
                edit("metadata refresh", refreshed) {
                    val query =
                        listOf(
                            "metadataRefreshMode" to "FullRefresh",
                            "imageRefreshMode" to "None",
                            "replaceAllMetadata" to "false",
                        )
                    val refresh = server.http.post("Items/$refreshed/Refresh", token = admin, query = query)
                    server.http.expectOk("refresh", refresh)
                }
            // Pinned on 12.1 and 12.2: a refresh saves the item, so it counts as a change. A full re-sync of
            // refreshed items is the cost, never a missed change.
            assertEquals(setOf(refreshed), refresh, "a metadata refresh counts as a change")

            val watch =
                edit("watched mark", watched) {
                    val viewerToken = harnessToken()
                    val viewerId =
                        server.http
                            .get("Users/Me", viewerToken)
                            .obj()
                            .getValue("Id")
                            .jsonPrimitive.content
                    val mark = server.http.post("Users/$viewerId/PlayedItems/$watched", token = viewerToken)
                    server.http.expectOk("watched", mark)
                }
            // Pinned on 12.1 and 12.2: a watched mark is user data, not an item save, so it is not a change.
            assertEquals(emptySet(), watch, "a watched mark does not count as a change")
        }
    }

    // Spec 10 task 1.9.1.

    @Test
    fun seriesMetadataArrives() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val shows = Library(ItemId(server.seeded.tvLibrary), "Shows", LibraryKind.TvShows)
            val seeded = server.seeded

            val page = adapter.librarySeries(session, shows, 0, BIG_PAGE).getOrNull()

            assertNotNull(page)
            assertEquals(1, page.totalCount)
            val series = page.series.single()
            assertEquals(ItemId(seeded.seriesId), series.id)
            assertEquals(ServerSeeding.SERIES_RATING, series.officialRating)
            // Genres and tags arrive; whether the server keeps their order is recorded (the cache stores the order).
            assertEquals(ServerSeeding.SERIES_GENRES.toSet(), series.genres.toSet())
            assertEquals(ServerSeeding.SERIES_TAGS.toSet(), series.tags.toSet())
            finding("series genres arrive as ${series.genres} tags ${series.tags}")
            val ids = adapter.librarySeriesIds(session, shows, 0, BIG_PAGE).getOrNull()
            assertEquals(listOf(ItemId(seeded.seriesId)), ids?.ids)
            assertEquals(1, ids?.totalCount)

            // Episodes carry the series and the season; an episode in the series folder is recorded, not assumed.
            val episodes =
                adapter
                    .libraryItems(session, shows, 0, BIG_PAGE)
                    .getOrNull()
                    ?.items
                    .orEmpty()
            assertEquals(seeded.episodeIds.toSet(), episodes.map { it.id.value }.toSet())
            for (episode in episodes) {
                assertEquals(ItemId(seeded.seriesId), episode.seriesId, "series of ${episode.id.value}")
                if (episode.id.value != seeded.looseEpisodeId) {
                    assertNotNull(episode.seasonId, "an episode in a season folder names its season")
                }
            }
            val loose = episodes.single { it.id.value == seeded.looseEpisodeId }
            val seasons = episodes.filter { it !== loose }.mapNotNull { it.seasonId }.toSet()
            finding("episode with no season folder: SeasonId=${loose.seasonId}, the others share $seasons")
            // An episode with no rating of its own gives null, not an empty text and not its series' rating.
            finding("episode ratings: ${episodes.map { it.officialRating }}")
            assertTrue(episodes.all { it.officialRating == null }, "an episode with no rating maps to null")

            // OQ2: an edit to the series' genres is returned by MinDateLastSaved; the item walk never returns series.
            val admin = server.http.signIn(server.admin.name, server.admin.rawPassword())
            val adminId =
                server.http
                    .get("Users/Me", admin)
                    .obj()
                    .getValue("Id")
                    .jsonPrimitive.content
            delay(SETTLE_MS)
            val mark = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            delay(SETTLE_MS)
            val before = adapter.librarySeries(session, shows, 0, BIG_PAGE, mark).getOrNull()
            assertEquals(emptyList(), before?.series, "nothing was saved since the mark")
            val dto = server.http.get("Users/$adminId/Items/${seeded.seriesId}", admin).obj()
            val genres = JsonArray(listOf(JsonPrimitive("Contract Edited")))
            val edited = JsonObject(dto + ("Genres" to genres))
            val post = server.http.post("Items/${seeded.seriesId}", edited.toString(), admin)
            server.http.expectOk("edit series genres", post)
            delay(SETTLE_MS * EDIT_SETTLE)

            val changed = adapter.librarySeries(session, shows, 0, BIG_PAGE, mark).getOrNull()
            assertEquals(listOf("Contract Edited"), changed?.series?.single()?.genres)
            val itemsSince =
                adapter
                    .libraryItems(session, shows, 0, BIG_PAGE, mark)
                    .getOrNull()
                    ?.items
                    .orEmpty()
            assertTrue(itemsSince.none { it.kind == ItemKind.Series }, "the item walk never returns a series")
        }
    }

    @Test
    fun moviesInBoxSetStillList() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val movies = Library(ItemId(server.seeded.moviesLibrary), "Movies", LibraryKind.Movies)
            val seeded = server.seeded
            assertTrue(seeded.boxSetMembers.size >= 2, "the server seeds a BoxSet with at least two movies")

            // The hazard, for the record: a server asked to collapse BoxSets hides their movies behind the BoxSet.
            val collapsing =
                listOf(
                    "parentId" to seeded.moviesLibrary,
                    "recursive" to "true",
                    "includeItemTypes" to "Movie,Episode,Video,MusicVideo",
                    "collapseBoxSetItems" to "true",
                )
            val raw = server.http.expectOk("collapsing walk", server.http.get("Items", harnessToken(), collapsing))
            val hidden =
                raw
                    .obj()
                    .getValue("Items")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("Id")
                            .jsonPrimitive.content
                    }
            finding("collapseBoxSetItems=true lists $hidden; the BoxSet's movies are ${seeded.boxSetMembers}")

            // The adapter sends collapseBoxSetItems=false, so the walk and the ID listing both list every movie.
            val walked = adapter.libraryItems(session, movies, 0, BIG_PAGE).getOrNull()
            assertNotNull(walked)
            val walkedIds = walked.items.map { it.id.value }.toSet()
            assertEquals(seeded.movieIds.toSet(), walkedIds)
            assertTrue(walkedIds.containsAll(seeded.boxSetMembers), "the walk lists the BoxSet's movies")
            val listed = adapter.libraryItemIds(session, movies, 0, BIG_PAGE).getOrNull()
            assertEquals(seeded.movieIds.toSet(), listed?.ids?.map { it.value }?.toSet())
            assertEquals(seeded.movieIds.size, listed?.totalCount)
            assertEquals(seeded.movieIds.size, walked.totalCount)
        }
    }

    private fun finding(text: String) = println("FINDING: $text")

    /** The server's own text for an item, read with the harness's device. */
    private fun rawItem(id: String): JsonObject {
        val token = harnessToken()
        val viewerId =
            server.http
                .get("Users/Me", token)
                .obj()
                .getValue("Id")
                .jsonPrimitive.content
        return server.http.expectOk("read item", server.http.get("Users/$viewerId/Items/$id", token)).obj()
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

    /** Waits, bounded, until [library]'s ID listing satisfies [done], and returns the IDs then. */
    private suspend fun awaitIds(
        adapter: JellyfinMediaServer,
        session: Session,
        library: Library,
        what: String,
        done: (Set<String>) -> Boolean,
    ): Set<String> {
        val deadline = System.nanoTime() + CHURN_WAIT_SECONDS.seconds.inWholeNanoseconds
        while (true) {
            val listed = adapter.libraryItemIds(session, library, 0, BIG_PAGE).getOrNull()
            val ids =
                listed
                    ?.ids
                    .orEmpty()
                    .map { it.value }
                    .toSet()
            if (listed != null && done(ids)) return ids
            check(System.nanoTime() < deadline) { "$what did not happen within $CHURN_WAIT_SECONDS s" }
            delay(CHURN_POLL_MS)
        }
    }

    /** Waits, bounded, until every item of [library] has a running time (the scan has finished with it). */
    private suspend fun awaitProbed(
        adapter: JellyfinMediaServer,
        session: Session,
        library: Library,
        count: Int,
    ) {
        val deadline = System.nanoTime() + CHURN_WAIT_SECONDS.seconds.inWholeNanoseconds
        while (true) {
            val items =
                adapter
                    .libraryItems(session, library, 0, BIG_PAGE)
                    .getOrNull()
                    ?.items
                    .orEmpty()
            if (items.size == count && items.all { (it.runtime ?: 0.seconds) > 0.seconds }) return
            check(System.nanoTime() < deadline) { "the scan did not finish within $CHURN_WAIT_SECONDS s" }
            delay(CHURN_POLL_MS)
        }
    }

    @Test
    fun deleteBecomesTombstone() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            ChurnLibrary(server).use { churn ->
                churn.create(listOf("Churn A", "Churn B", "Churn C"))
                val library = Library(ItemId(churn.id), "Churn", LibraryKind.Movies)
                awaitProbed(adapter, session, library, count = 3)
                // What the first sync would have saved: every ID of the library.
                val cached = awaitIds(adapter, session, library, "the first listing") { it.size == 3 }
                val doomed =
                    adapter
                        .libraryItems(session, library, 0, BIG_PAGE)
                        .getOrNull()
                        ?.items
                        .orEmpty()
                        .first { it.name.contains("Churn B") }
                        .id.value

                churn.removeFile("Churn B")
                churn.refresh()
                val serverIds = awaitIds(adapter, session, library, "the delete") { doomed !in it }

                // One reconciliation: what the cache holds and the server no longer lists is tombstoned.
                assertEquals(setOf(doomed), cached - serverIds, "exactly the deleted item is missing from the listing")
                assertEquals(cached - doomed, serverIds, "the other items are still listed")
                // And a changed-since pass can never see a delete, which is why reconciliation exists.
                val everything = Instant.parse("2000-01-01T00:00:00Z")
                val changed = adapter.libraryItems(session, library, 0, BIG_PAGE, everything).getOrNull()
                assertTrue(changed?.items.orEmpty().none { it.id.value == doomed }, "a deleted item is not returned")

                // Open question 5: a file put back. Revive a returning ID, fetch a new one: either way works.
                churn.addFile("Churn B")
                churn.refresh()
                val restored = awaitIds(adapter, session, library, "the restore") { it.size == 3 }
                finding("restored file keeps its ID = ${doomed in restored}")
                assertEquals(CHURN_FILES, restored.size)
            }
        }
    }

    @Test
    fun newItemAfterIncremental() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            ChurnLibrary(server).use { churn ->
                churn.create(listOf("Churn A", "Churn B"))
                val library = Library(ItemId(churn.id), "Churn", LibraryKind.Movies)
                awaitProbed(adapter, session, library, count = 2)
                val before = awaitIds(adapter, session, library, "the first listing") { it.size == 2 }
                delay(SETTLE_MS * EDIT_SETTLE)
                val mark = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                delay(SETTLE_MS)

                churn.addFile("Churn C")
                churn.refresh()
                val after = awaitIds(adapter, session, library, "the new item") { it.size == 3 }
                delay(SETTLE_MS * EDIT_SETTLE)

                // One incremental pass from the moment recorded before the add: the new item, and not the old ones.
                val added = (after - before).single()
                val page = adapter.libraryItems(session, library, 0, BIG_PAGE, mark).getOrNull()
                assertNotNull(page)
                assertEquals(setOf(added), page.items.map { it.id.value }.toSet(), "only the new item comes back")
                val item = page.items.single()
                assertNotNull(item.dateCreated)
                assertTrue((item.runtime ?: 0.seconds) > 0.seconds)
            }
        }
    }

    private companion object {
        const val CHURN_FILES = 3
        const val CHURN_WAIT_SECONDS = 120
        const val CHURN_POLL_MS = 2_000L
        const val SUPPORTED_MAJOR = 12
        const val PAGE_SIZE = 2
        const val BIG_PAGE = 20
        const val SETTLE_MS = 1_200L
        const val TIE_SETTLE = 3
        const val EDIT_SETTLE = 4
        const val FIRST_YEAR = 2001
        const val LAST_YEAR = 2003
        const val FUTURE_MS = 60_000L
        const val BISECT_WINDOW_MS = 30_000L
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
