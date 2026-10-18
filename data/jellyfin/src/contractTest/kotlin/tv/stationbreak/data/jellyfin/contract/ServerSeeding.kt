// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.testcontainers.containers.GenericContainer
import java.time.Duration
import java.util.UUID

/**
 * Sets up a fresh Jellyfin container through its API (D3): the startup wizard, three libraries of synthetic media made
 * inside the container with the `jellyfin-ffmpeg` it ships, a non-admin viewer, a disabled user, Quick Connect on.
 * Every step names itself and its status code when it fails; a request body or an answer is never printed.
 */
@Suppress("TooManyFunctions") // One function per seeding step.
internal object ServerSeeding {
    class Accounts {
        val admin = Account("admin", "A-${UUID.randomUUID()}")
        val viewer = Account("viewer", "V-${UUID.randomUUID()}")
        val disabled = Account("sleeper", "S-${UUID.randomUUID()}")
    }

    private const val FFMPEG = "/usr/lib/jellyfin-ffmpeg/ffmpeg"

    // Longer than 5 minutes, so a stopped report stores a resume position (ADR-0006).
    private const val MEDIA_SECONDS = 330
    private const val MOVIES = 3

    // Two episodes in the season folder, and a third in the series folder itself (spec 10: an episode with no season
    // folder).
    private const val SEASON_EPISODES = 2
    private const val EPISODES = SEASON_EPISODES + 1

    /** The metadata the one series is given (spec 10). Synthetic. */
    val SERIES_GENRES = listOf("Contract Drama", "Contract Mystery")
    val SERIES_TAGS = listOf("contract slow burn")
    const val SERIES_RATING = "TV-14"
    private const val BOX_SET_NAME = "Contract Box"
    private const val BOX_SET_MEMBERS = 2
    private val SCAN_TIMEOUT: Duration = Duration.ofMinutes(4)
    private val POLL: Duration = Duration.ofSeconds(2)
    private val WIZARD_TIMEOUT: Duration = Duration.ofMinutes(2)

    fun seed(
        jellyfin: GenericContainer<*>,
        http: Harness,
        accounts: Accounts,
    ): Seeded {
        completeWizard(http, accounts.admin)
        val token = http.signIn(accounts.admin.name, accounts.admin.rawPassword())
        generateMedia(jellyfin)
        val libraries = createLibraries(http, token)
        createUser(http, token, accounts.viewer)
        disable(http, token, createUser(http, token, accounts.disabled))
        enableQuickConnect(http, token)
        val (movies, episodes) = waitForScan(http, token, libraries.getValue("movies"))
        val premieres = seedPremiereDates(http, token, episodes)
        val seriesId = seedSeriesMetadata(http, token, libraries.getValue("tv"))
        val boxSetMembers = movies.take(BOX_SET_MEMBERS)
        return Seeded(
            libraries.getValue("movies"),
            libraries.getValue("tv"),
            libraries.getValue("mixed"),
            movies,
            episodes,
            premieres,
            seriesId,
            findLooseEpisode(http, token),
            seedBoxSet(http, token, boxSetMembers),
            boxSetMembers,
        )
    }

    private fun adminId(
        http: Harness,
        token: String,
    ): String =
        http
            .expectOk("read admin", http.get("Users/Me", token))
            .obj()
            .getValue("Id")
            .jsonPrimitive.content

    /** Gives the series genres, tags and a rating (spec 10), and returns its ID. Episodes are left without any. */
    private fun seedSeriesMetadata(
        http: Harness,
        token: String,
        tvLibrary: String,
    ): String {
        val query = listOf("recursive" to "true", "includeItemTypes" to "Series", "parentId" to tvLibrary)
        val found =
            http
                .expectOk("list series", http.get("Items", token, query))
                .obj()
                .getValue("Items")
                .jsonArray
        check(found.size == 1) { "the TV library should hold one series, not ${found.size}" }
        val id =
            found
                .single()
                .jsonObject
                .getValue("Id")
                .jsonPrimitive.content
        val dto = http.expectOk("read series", http.get("Users/${adminId(http, token)}/Items/$id", token)).obj()
        val edited =
            JsonObject(
                dto +
                    mapOf(
                        "Genres" to JsonArray(SERIES_GENRES.map(::JsonPrimitive)),
                        "Tags" to JsonArray(SERIES_TAGS.map(::JsonPrimitive)),
                        "OfficialRating" to JsonPrimitive(SERIES_RATING),
                    ),
            )
        http.expectOk("set series metadata", http.post("Items/$id", edited.toString(), token))
        return id
    }

    /** The episode whose file is in the series folder, not under `Season 01`. */
    private fun findLooseEpisode(
        http: Harness,
        token: String,
    ): String {
        val query = listOf("recursive" to "true", "includeItemTypes" to "Episode", "fields" to "Path")
        val episodes =
            http
                .expectOk("list episodes", http.get("Items", token, query))
                .obj()
                .getValue("Items")
                .jsonArray
        val loose =
            episodes.map { it.jsonObject }.filter { "/Season 01/" !in it.getValue("Path").jsonPrimitive.content }
        check(loose.size == 1) { "exactly one episode should sit outside a season folder, not ${loose.size}" }
        return loose
            .single()
            .getValue("Id")
            .jsonPrimitive.content
    }

    /** A BoxSet holding [members] (spec 10, Vimes H5): the movies that "group into collections" would hide. */
    private fun seedBoxSet(
        http: Harness,
        token: String,
        members: List<String>,
    ): String {
        val query = listOf("name" to BOX_SET_NAME, "ids" to members.joinToString(","))
        return http
            .expectOk("create BoxSet", http.post("Collections", token = token, query = query))
            .obj()
            .getValue("Id")
            .jsonPrimitive.content
    }

    /**
     * Gives the episodes known premiere dates (spec 09): one a few minutes before midnight in a UTC-5 offset, so its
     * UTC date is the next day, and one before 1970. Returns the UTC date each must read as. The server converts the
     * offset to UTC on the way in (the contract case records what it keeps).
     */
    private fun seedPremiereDates(
        http: Harness,
        token: String,
        episodes: List<String>,
    ): Map<String, String> {
        val wanted =
            listOf(
                Triple("2001-03-04T23:30:00-05:00", "2001-03-05", episodes[0]),
                Triple("1969-07-20T20:17:00Z", "1969-07-20", episodes[1]),
            )
        val adminId =
            http
                .expectOk("read admin", http.get("Users/Me", token))
                .obj()
                .getValue("Id")
                .jsonPrimitive.content
        for ((text, _, id) in wanted) {
            val dto = http.expectOk("read item", http.get("Users/$adminId/Items/$id", token)).obj()
            val edited = JsonObject(dto + ("PremiereDate" to JsonPrimitive(text)))
            http.expectOk("set premiere date", http.post("Items/$id", edited.toString(), token))
        }
        return wanted.associate { (_, utcDate, id) -> id to utcDate }
    }

    private fun completeWizard(
        http: Harness,
        admin: Account,
    ) {
        waitForWizard(http)
        val culture = """{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}"""
        http.expectOk("wizard configuration", http.post("Startup/Configuration", culture))
        // The wizard creates its first user on request; reading it first is what the web client does.
        http.expectOk("wizard first user", http.get("Startup/User"))
        val user =
            buildJsonObject {
                put("Name", admin.name)
                put("Password", admin.rawPassword())
            }
        http.expectOk("wizard user", http.post("Startup/User", user.toString()))
        val remote = """{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}"""
        http.expectOk("wizard remote access", http.post("Startup/RemoteAccess", remote))
        http.expectOk("wizard complete", http.post("Startup/Complete"))
    }

    /** The public info answers before the wizard's endpoints do (503 or a reset connection while the server loads). */
    private fun waitForWizard(http: Harness) {
        val deadline = System.nanoTime() + WIZARD_TIMEOUT.toNanos()
        // A connection reset while the server is still loading is the same as a 503: not ready yet.
        while (!runCatching { http.get("Startup/Configuration").ok }.getOrDefault(false)) {
            check(System.nanoTime() < deadline) { "the startup wizard didn't become available" }
            Thread.sleep(POLL.toMillis())
        }
    }

    /** Synthetic media made inside the container (D3): nothing is downloaded or committed. */
    private fun generateMedia(jellyfin: GenericContainer<*>) {
        fun sh(
            step: String,
            command: String,
        ) {
            val result = jellyfin.execInContainer("sh", "-c", command)
            check(result.exitCode == 0) { "media step '$step' exited with ${result.exitCode}" }
        }
        sh("directories", "mkdir -p /media/movies '/media/tv/Test Show/Season 01' /media/mixed")
        val source = "/media/movies/source.mp4"
        val lavfi = "-f lavfi -i testsrc=size=320x240:rate=5:duration=$MEDIA_SECONDS"
        val sine = "-f lavfi -i sine=frequency=440:duration=$MEDIA_SECONDS"
        val encode = "-c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac"
        sh("ffmpeg", "$FFMPEG -nostdin -loglevel error -y $lavfi $sine $encode $source")
        for (n in 1..MOVIES) {
            val folder = "/media/movies/Movie $n (200$n)"
            sh("movie $n", "mkdir -p '$folder' && cp $source '$folder/Movie $n (200$n).mp4'")
        }
        for (n in 1..SEASON_EPISODES) {
            sh("episode $n", "cp $source '/media/tv/Test Show/Season 01/Test Show - S01E0$n.mp4'")
        }
        // The same series folder, no season folder: its SeasonId is recorded by the contract suite (spec 10).
        sh("loose episode", "cp $source '/media/tv/Test Show/Test Show - S01E03.mp4'")
        val mixed = "/media/mixed/Mixed One (2004)"
        sh("mixed", "mkdir -p '$mixed' && cp $source '$mixed/Mixed One (2004).mp4'")
        sh("tidy", "rm $source")
    }

    private fun createLibraries(
        http: Harness,
        token: String,
    ): Map<String, String> {
        fun create(
            name: String,
            type: String?,
            path: String,
        ) {
            val query =
                buildList {
                    add("name" to name)
                    // Mixed content is the library with no collection type at all.
                    if (type != null) add("collectionType" to type)
                    add("refreshLibrary" to "false")
                }
            val options =
                buildJsonObject {
                    putJsonObject("LibraryOptions") {
                        putJsonArray("PathInfos") { add(buildJsonObject { put("Path", path) }) }
                    }
                }
            http.expectOk("library $name", http.post("Library/VirtualFolders", options.toString(), token, query))
        }
        create("Movies", "movies", "/media/movies")
        create("Shows", "tvshows", "/media/tv")
        create("Everything", null, "/media/mixed")
        val folders = http.expectOk("library list", http.get("Library/VirtualFolders", token)).json().jsonArray
        return mapOf(
            "movies" to idOf(folders, "Movies"),
            "tv" to idOf(folders, "Shows"),
            "mixed" to idOf(folders, "Everything"),
        )
    }

    private fun idOf(
        folders: JsonArray,
        name: String,
    ): String =
        folders
            .map { it.jsonObject }
            .first { it.getValue("Name").jsonPrimitive.content == name }
            .getValue("ItemId")
            .jsonPrimitive.content

    private fun createUser(
        http: Harness,
        token: String,
        account: Account,
    ): String {
        val body =
            buildJsonObject {
                put("Name", account.name)
                put("Password", account.rawPassword())
            }
        val reply = http.expectOk("create ${account.name}", http.post("Users/New", body.toString(), token))
        return reply
            .obj()
            .getValue("Id")
            .jsonPrimitive.content
    }

    private fun disable(
        http: Harness,
        token: String,
        userId: String,
    ) {
        val user = http.expectOk("read user", http.get("Users/$userId", token)).obj()
        val policy = user.getValue("Policy").jsonObject
        val disabled = JsonObject(policy + ("IsDisabled" to JsonPrimitive(true)))
        http.expectOk("disable user", http.post("Users/$userId/Policy", disabled.toString(), token))
    }

    private fun enableQuickConnect(
        http: Harness,
        token: String,
    ) {
        val configuration = http.expectOk("read configuration", http.get("System/Configuration", token)).obj()
        val enabled = JsonObject(configuration + ("QuickConnectAvailable" to JsonPrimitive(true)))
        http.expectOk("enable Quick Connect", http.post("System/Configuration", enabled.toString(), token))
        val status = http.expectOk("Quick Connect status", http.get("QuickConnect/Enabled", token))
        check(status.json().jsonPrimitive.boolean) { "Quick Connect did not turn on" }
    }

    /** Starts a scan and waits, bounded, until every file is in the library with its runtime known. */
    private fun waitForScan(
        http: Harness,
        token: String,
        moviesLibrary: String,
    ): Pair<List<String>, List<String>> {
        http.expectOk("start scan", http.post("Library/Refresh", token = token))
        val deadline = System.nanoTime() + SCAN_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            val movies = items(http, token, "Movie", moviesLibrary)
            val episodes = items(http, token, "Episode", null)
            // The mixed library holds one more movie than the Movies library.
            val allMovies = items(http, token, "Movie", null)
            val complete = movies.size == MOVIES && episodes.size == EPISODES && allMovies.size == MOVIES + 1
            if (complete && (allMovies + episodes).all { it.second > 0 }) {
                return movies.map { it.first } to episodes.map { it.first }
            }
            Thread.sleep(POLL.toMillis())
        }
        error("the library scan did not finish within $SCAN_TIMEOUT")
    }

    /** The IDs and runtimes (ticks) of every [type] item, under [parent] or anywhere. */
    private fun items(
        http: Harness,
        token: String,
        type: String,
        parent: String?,
    ): List<Pair<String, Long>> {
        val query =
            buildList {
                add("recursive" to "true")
                add("includeItemTypes" to type)
                if (parent != null) add("parentId" to parent)
            }
        val reply = http.expectOk("list $type", http.get("Items", token, query))
        return reply
            .obj()
            .getValue("Items")
            .jsonArray
            .map { it.jsonObject }
            .map { item ->
                val ticks = item["RunTimeTicks"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                item.getValue("Id").jsonPrimitive.content to ticks
            }
    }
}
