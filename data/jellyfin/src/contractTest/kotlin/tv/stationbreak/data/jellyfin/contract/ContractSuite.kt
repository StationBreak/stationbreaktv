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
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.SeriesInfo
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
import kotlin.time.Duration
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
@Suppress("TooManyFunctions", "LargeClass") // One function per contract case, plus the helpers they share.
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

    /**
     * The series' genres, tags and rating arrive, its episodes carry `SeriesId` and `SeasonId`, and an edit to the
     * series is returned by `MinDateLastSaved`.
     *
     * **Episode ratings (recorded on 12.1 and 12.2, identical).** An episode does not come back null just because it
     * has no rating of its own: an episode in a season folder is sent with its series' rating (`OfficialRating` as
     * sent, and so as mapped), while an episode in the series folder itself has none and maps to null. So the server
     * may already do rules-format rule 4's fallback to the series' rating for most episodes, and the cache must not
     * assume an episode's own rating is absent when the series has one. The adapter passes the value through untouched.
     */
    @Test
    fun seriesMetadataArrives() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val shows = Library(ItemId(server.seeded.tvLibrary), "Shows", LibraryKind.TvShows)

            val series = assertSeriesFields(adapter, session, shows)
            assertEpisodeSeasonsAndRatings(adapter, session, shows, series)
            assertSeriesEditIsChangedSince(adapter, session, shows)
        }
    }

    private suspend fun assertSeriesFields(
        adapter: JellyfinMediaServer,
        session: Session,
        shows: Library,
    ): SeriesInfo {
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
        return series
    }

    private suspend fun assertEpisodeSeasonsAndRatings(
        adapter: JellyfinMediaServer,
        session: Session,
        shows: Library,
        series: SeriesInfo,
    ) {
        val seeded = server.seeded
        val episodes =
            adapter
                .libraryItems(session, shows, 0, BIG_PAGE)
                .getOrNull()
                ?.items
                .orEmpty()
        assertEquals(seeded.episodeIds.toSet(), episodes.map { it.id.value }.toSet())
        for (episode in episodes) {
            assertEquals(ItemId(seeded.seriesId), episode.seriesId, "series of ${episode.id.value}")
            val loose = episode.id.value == seeded.looseEpisodeId
            if (!loose) assertNotNull(episode.seasonId, "an episode in a season folder names its season")
            val raw = rawItem(episode.id.value)["OfficialRating"]?.jsonPrimitive?.content
            val where = if (loose) "in the series folder" else "in a season folder"
            finding("episode $where: SeasonId=${episode.seasonId}, adapter rating=${episode.officialRating}, sent=$raw")
            // The adapter passes the server's value through; a season-folder episode carries the series' rating.
            assertEquals(raw, episode.officialRating, "the adapter does not change an episode's rating")
            val expected = if (loose) null else series.officialRating
            assertEquals(expected, episode.officialRating, "rating of an episode $where")
        }
    }

    // OQ2: an edit to the series' genres is returned by MinDateLastSaved; the item walk never returns a series.
    private suspend fun assertSeriesEditIsChangedSince(
        adapter: JellyfinMediaServer,
        session: Session,
        shows: Library,
    ) {
        val seriesId = server.seeded.seriesId
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
        val dto = server.http.get("Users/$adminId/Items/$seriesId", admin).obj()
        val edited = JsonObject(dto + ("Genres" to JsonArray(listOf(JsonPrimitive("Contract Edited")))))
        server.http.expectOk("edit series genres", server.http.post("Items/$seriesId", edited.toString(), admin))
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

    @Test
    fun moviesInBoxSetStillList() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val movies = Library(ItemId(server.seeded.moviesLibrary), "Movies", LibraryKind.Movies)
            val seeded = server.seeded
            assertTrue(seeded.boxSetMembers.size >= 2, "the server seeds a BoxSet with at least two movies")

            // The hazard is real on this server: with the server-wide grouping setting on (seeded), a request that
            // doesn't say otherwise, or says true, hides BoxSet movies behind the BoxSet.
            val base =
                listOf(
                    "parentId" to seeded.moviesLibrary,
                    "recursive" to "true",
                    "includeItemTypes" to "Movie,Episode,Video,MusicVideo",
                )
            val unflagged = listedIds(base)
            val collapsing = listedIds(base + ("collapseBoxSetItems" to "true"))
            finding("without the flag: $unflagged; with true: $collapsing")
            finding("the BoxSet's movies: ${seeded.boxSetMembers}")
            assertTrue(seeded.boxSetMembers.any { it !in unflagged }, "an unflagged walk hides a BoxSet movie")
            assertTrue(seeded.boxSetMembers.any { it !in collapsing }, "collapseBoxSetItems=true hides a BoxSet movie")

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

    /** The IDs the server lists for a raw `Items` request made with the harness's device. */
    private fun listedIds(query: List<Pair<String, String>>): Set<String> =
        server.http
            .expectOk("raw walk", server.http.get("Items", harnessToken(), query))
            .obj()
            .getValue("Items")
            .jsonArray
            .map {
                it.jsonObject
                    .getValue("Id")
                    .jsonPrimitive.content
            }.toSet()

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

    private val playbackRequest =
        PlaybackRequest(
            capabilities = DeviceCapabilities(setOf("mp4"), emptyList(), setOf("aac"), emptySet(), 2),
            start = Duration.ZERO,
            audioIndex = null,
            subtitleIndex = null,
            forceTranscode = false,
        )

    @Test
    fun playbackInfoListsSourcesAndTheBuiltUrlStreamsWithTheHeader() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val item = ItemId(server.seeded.movieIds.first())

            val info = adapter.playbackInfo(session, item, playbackRequest).getOrNull()

            assertNotNull(info)
            val source = info.sources.first()
            assertTrue(source.supportsDirectPlay || source.supportsDirectStream, "the profile should direct play H.264")
            val request =
                Request
                    .Builder()
                    .url(info.streams.original(source.id))
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
            val info = adapter.playbackInfo(session, item, playbackRequest).getOrNull()
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
                val returned = page.items.map { it.id.value }.toSet()
                assertTrue(added in returned, "the new item comes back")
                // The server may save an older item too inside the window (a background metadata or image task; seen
                // once on 12.2, #318), and it does not return DateLastSaved to compare. So an extra item is accepted
                // only if the cursor is still being honoured: a fresh cursor, taken once the server is quiet, must
                // return nothing. The churn can run on, so a few fresh cursors are tried; a query that ignored the
                // cursor would return every item each time, and fail here.
                val extras = returned - added
                assertTrue(extras.all { it in before }, "anything else returned is an earlier fixture item")
                if (extras.isNotEmpty()) {
                    finding("incremental also returned $extras: the server saved them inside the window")
                    var again: Set<String> = returned
                    for (attempt in 1..QUIET_CURSOR_TRIES) {
                        delay(SETTLE_MS * EDIT_SETTLE)
                        val later = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                        delay(SETTLE_MS)
                        val page2 = adapter.libraryItems(session, library, 0, BIG_PAGE, later).getOrNull()
                        assertNotNull(page2)
                        again = page2.items.map { it.id.value }.toSet()
                        if (again.isEmpty()) break
                        finding("a fresh cursor (try $attempt) still returned $again")
                    }
                    assertEquals(emptySet(), again, "a cursor taken once the server is quiet returns nothing")
                }
                val item = page.items.single { it.id.value == added }
                assertNotNull(item.dateCreated)
                assertTrue((item.runtime ?: 0.seconds) > 0.seconds)
            }
        }
    }

    // Spec 10, Unit 2 (collections). Each case makes its own BoxSets and removes them again, so the seeded BoxSet that
    // moviesInBoxSetStillList reads is never touched.

    @Test
    fun collectionMembersMixedKinds() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            val admin = adminToken()
            val seeded = server.seeded
            val shows = Library(ItemId(seeded.tvLibrary), "Shows", LibraryKind.TvShows)
            val season = seasonIdOf(adapter, session, shows)
            val inner = createBoxSet(admin, "Contract Inner", listOf(seeded.movieIds[2]))
            val members = listOf(seeded.movieIds[0], seeded.episodeIds[0], seeded.seriesId, season)
            val outer = createBoxSet(admin, "Contract Mixed", members)
            try {
                server.http.expectOk(
                    "nest BoxSet",
                    server.http.post(
                        "Collections/$outer/Items",
                        token = admin,
                        query =
                            listOf("ids" to inner),
                    ),
                )
                delay(SETTLE_MS)
                val expected =
                    mapOf(
                        seeded.movieIds[0] to CollectionMemberKind.Movie,
                        seeded.episodeIds[0] to CollectionMemberKind.Episode,
                        seeded.seriesId to CollectionMemberKind.Series,
                        season to CollectionMemberKind.Season,
                        inner to CollectionMemberKind.Collection,
                    )
                val page = adapter.collectionMembers(session, ItemId(outer), 0, BIG_PAGE).getOrNull()
                assertNotNull(page)
                finding("mixed members arrive as ${page.members.map { it.kind }}")
                assertEquals(expected, page.members.associate { it.id.value to it.kind })
                assertEquals(expected.size, page.totalCount)
                // The nested BoxSet is a member and is not followed: its own movie is not in the outer list.
                assertTrue(seeded.movieIds[2] !in page.members.map { it.id.value }, "a nested BoxSet is not flattened")
                val paged = pagedMembers(adapter, session, outer).toSet()
                assertEquals(expected.keys, paged, "paging reads the same members")
                val listed = listedBoxSets(adapter, session)
                assertTrue(listed.containsAll(listOf(outer, inner)), "the viewer lists both BoxSets: $listed")
            } finally {
                runCatching { server.http.delete("Items/$outer", admin) }
                runCatching { server.http.delete("Items/$inner", admin) }
            }
        }
    }

    /**
     * ADR-0015, pinned on 12.1 and 12.2. Adding and removing a member, and renaming the BoxSet, each make the BoxSet
     * appear in a `MinDateLastSaved` query; renaming a member does not (the spec expected it to); a deleted BoxSet is
     * missing from the BoxSet ID listing; a member deleted through a scan does NOT bump the BoxSet, yet is gone from
     * the item ID listing and from the member list.
     */
    @Test
    fun collectionChangesDetected() {
        runBlocking {
            val adapter = adapter()
            val session = session(adapter)
            ChurnLibrary(server).use { churn ->
                // The churn library's own admin token: a second sign-in from the harness's device would revoke it.
                val admin = churn.admin
                churn.create(listOf("Churn A", "Churn B", "Churn C"))
                val library = Library(ItemId(churn.id), "Churn", LibraryKind.Movies)
                awaitProbed(adapter, session, library, count = CHURN_FILES)
                server.http.awaitIdle(admin)
                val ids = churnIds(adapter, session, library)
                val box = createBoxSet(admin, "Contract Changes", listOf(ids.getValue("Churn A")))
                try {
                    assertMembershipEditsBumpTheBoxSet(adapter, session, admin, box, ids)
                    assertMemberRenameAndBoxSetRename(adapter, session, admin, box, ids.getValue("Churn A"))
                    val doomed = Doomed(churn, library, ids.getValue("Churn C"))
                    assertDeletedMemberIsSilent(adapter, session, admin, box, doomed)
                    server.http.expectOkEventually("delete BoxSet") { server.http.delete("Items/$box", admin) }
                    awaitTrue("a deleted BoxSet leaves the ID listing") { box !in listedBoxSets(adapter, session) }
                } finally {
                    runCatching { server.http.delete("Items/$box", admin) }
                }
            }
        }
    }

    private suspend fun assertMembershipEditsBumpTheBoxSet(
        adapter: JellyfinMediaServer,
        session: Session,
        admin: String,
        box: String,
        ids: Map<String, String>,
    ) {
        val added = ids.getValue("Churn B")
        val first = settledMark()
        val quiet = boxSetsChangedSince(adapter, session, first)
        assertEquals(emptySet<String>(), quiet, "nothing was saved since the mark")
        addToBoxSet(admin, box, added)
        awaitTrue("adding a member bumps the BoxSet") { box in boxSetsChangedSince(adapter, session, first) }
        awaitTrue("the added member is listed") { added in pagedMembers(adapter, session, box) }
        assertEquals(setOf(ids.getValue("Churn A"), added), pagedMembers(adapter, session, box).toSet())

        val second = settledMark()
        server.http.expectOkEventually("remove member") {
            server.http.delete("Collections/$box/Items", admin, listOf("ids" to added))
        }
        awaitTrue("removing a member bumps the BoxSet") { box in boxSetsChangedSince(adapter, session, second) }
        awaitTrue("the removed member is gone") { added !in pagedMembers(adapter, session, box) }
        assertEquals(listOf(ids.getValue("Churn A")), pagedMembers(adapter, session, box))
    }

    private suspend fun assertMemberRenameAndBoxSetRename(
        adapter: JellyfinMediaServer,
        session: Session,
        admin: String,
        box: String,
        member: String,
    ) {
        val memberMark = settledMark()
        renameItem(admin, member, "Contract Renamed Member")
        delay(SETTLE_MS * EDIT_SETTLE)
        val memberBumped = box in boxSetsChangedSince(adapter, session, memberMark)
        finding("renaming a member bumps its BoxSet: $memberBumped")
        // Spec 10 says a rename of a member bumps its BoxSet; 12.1 and 12.2 do not (this pins what they do, so a
        // change is noticed). The cache stores no member name, so nothing depends on it either way.
        assertTrue(!memberBumped, "renaming a member does not bump the BoxSet (recorded; the spec expected it to)")

        val boxMark = settledMark()
        renameItem(admin, box, "Contract Changes Renamed")
        awaitTrue("renaming the BoxSet bumps it") { box in boxSetsChangedSince(adapter, session, boxMark) }
    }

    // A member the case deletes: the throwaway library it lives in, and its ID.
    private data class Doomed(
        val churn: ChurnLibrary,
        val library: Library,
        val id: String,
    )

    private suspend fun assertDeletedMemberIsSilent(
        adapter: JellyfinMediaServer,
        session: Session,
        admin: String,
        box: String,
        doomed: Doomed,
    ) {
        val (churn, library, id) = doomed
        addToBoxSet(admin, box, id)
        awaitTrue("the member to delete is in the BoxSet") { id in pagedMembers(adapter, session, box) }
        val mark = settledMark()

        churn.removeFile("Churn C")
        churn.refresh()
        awaitIds(adapter, session, library, "the delete") { id !in it }
        delay(SETTLE_MS * EDIT_SETTLE)

        val bumped = box in boxSetsChangedSince(adapter, session, mark)
        finding("a deleted member bumps its BoxSet: $bumped")
        assertTrue(!bumped, "a deleted member does not bump the BoxSet (ADR-0015)")
        assertTrue(id !in pagedMembers(adapter, session, box), "the deleted member left the member list")
    }

    private fun addToBoxSet(
        admin: String,
        box: String,
        member: String,
    ) {
        server.http.expectOkEventually("add member") {
            server.http.post("Collections/$box/Items", token = admin, query = listOf("ids" to member))
        }
    }

    /** Waits, bounded, until [check] holds, for a server that applies a write a moment after it answers. */
    private suspend fun awaitTrue(
        what: String,
        check: suspend () -> Boolean,
    ) {
        val deadline = System.nanoTime() + CHURN_WAIT_SECONDS.seconds.inWholeNanoseconds
        while (!check()) {
            check(System.nanoTime() < deadline) { "$what did not happen within $CHURN_WAIT_SECONDS s" }
            delay(SETTLE_MS)
        }
    }

    private fun adminToken(): String = server.http.signIn(server.admin.name, server.admin.rawPassword())

    private fun createBoxSet(
        admin: String,
        name: String,
        members: List<String>,
    ): String {
        val query = listOf("name" to name, "ids" to members.joinToString(","))
        val create = { server.http.post("Collections", token = admin, query = query) }
        val reply = server.http.expectOkEventually("create BoxSet", call = create)
        return reply
            .obj()
            .getValue("Id")
            .jsonPrimitive.content
    }

    // Renames an item as the admin, the way the series edit does: read it, change one field, post it back.
    private fun renameItem(
        admin: String,
        id: String,
        name: String,
    ) {
        val adminId =
            server.http
                .get("Users/Me", admin)
                .obj()
                .getValue("Id")
                .jsonPrimitive.content
        val dto = server.http.expectOk("read item", server.http.get("Users/$adminId/Items/$id", admin)).obj()
        val edited = JsonObject(dto + ("Name" to JsonPrimitive(name)))
        server.http.expectOkEventually("rename item") { server.http.post("Items/$id", edited.toString(), admin) }
    }

    /** The moment now, with a pause either side so no earlier save falls on it. */
    private suspend fun settledMark(): Instant {
        delay(SETTLE_MS)
        val mark = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        delay(SETTLE_MS)
        return mark
    }

    private suspend fun churnIds(
        adapter: JellyfinMediaServer,
        session: Session,
        library: Library,
    ): Map<String, String> =
        adapter
            .libraryItems(session, library, 0, BIG_PAGE)
            .getOrNull()
            ?.items
            .orEmpty()
            .associate { item -> item.name.substringBefore(" (").let { it to item.id.value } }

    private suspend fun seasonIdOf(
        adapter: JellyfinMediaServer,
        session: Session,
        shows: Library,
    ): String =
        adapter
            .libraryItems(session, shows, 0, BIG_PAGE)
            .getOrNull()
            ?.items
            .orEmpty()
            .firstNotNullOf { it.seasonId?.value }

    /** Every BoxSet ID the viewer is listed, by the ID listing. */
    private suspend fun listedBoxSets(
        adapter: JellyfinMediaServer,
        session: Session,
    ): Set<String> =
        adapter
            .collectionIds(session, 0, BIG_PAGE)
            .getOrNull()
            ?.ids
            .orEmpty()
            .map { it.value }
            .toSet()

    /** The BoxSets a `MinDateLastSaved` query from [since] returns. */
    private suspend fun boxSetsChangedSince(
        adapter: JellyfinMediaServer,
        session: Session,
        since: Instant,
    ): Set<String> =
        adapter
            .collections(session, 0, BIG_PAGE, since)
            .getOrNull()
            ?.collections
            .orEmpty()
            .map { it.id.value }
            .toSet()

    /** All members of [box] read two to a page, in the order the server gives them. */
    private suspend fun pagedMembers(
        adapter: JellyfinMediaServer,
        session: Session,
        box: String,
    ): List<String> {
        val members = mutableListOf<String>()
        while (true) {
            val page = adapter.collectionMembers(session, ItemId(box), members.size, PAGE_SIZE).getOrNull()
            assertNotNull(page)
            if (page.members.isEmpty()) return members
            members += page.members.map { it.id.value }
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
        const val QUIET_CURSOR_TRIES = 3
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
