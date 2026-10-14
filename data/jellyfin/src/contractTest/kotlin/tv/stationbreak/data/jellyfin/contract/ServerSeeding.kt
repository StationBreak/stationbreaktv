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
    private const val EPISODES = 2
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
        return Seeded(
            libraries.getValue("movies"),
            libraries.getValue("tv"),
            libraries.getValue("mixed"),
            movies,
            episodes,
        )
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
        for (n in 1..EPISODES) {
            sh("episode $n", "cp $source '/media/tv/Test Show/Season 01/Test Show - S01E0$n.mp4'")
        }
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
