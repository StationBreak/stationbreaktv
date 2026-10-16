// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A throwaway movies library whose files the test adds and removes while it runs (spec 06 task 4.9: a new item, a
 * delete, a restored file). It lives in its own folder and its own library, so the seeded libraries the other cases
 * count are never touched, and [close] removes both again. Files are copies of a seeded movie made inside the
 * container; nothing is downloaded or committed.
 */
internal class ChurnLibrary(
    private val server: JellyfinServer,
) : AutoCloseable {
    private val admin = server.http.signIn(server.admin.name, server.admin.rawPassword())
    private var created = false

    /** The library's ID, once [create] has run. */
    lateinit var id: String
        private set

    /** Creates the library with one file for each of [names], and starts a scan. */
    fun create(names: List<String>) {
        sh("folder", "mkdir -p '$FOLDER'")
        names.forEach(::addFile)
        val options =
            buildJsonObject {
                putJsonObject("LibraryOptions") {
                    putJsonArray("PathInfos") { add(buildJsonObject { put("Path", FOLDER) }) }
                }
            }
        val query = listOf("name" to NAME, "collectionType" to "movies", "refreshLibrary" to "false")
        server.http.expectOk("churn library", server.http.post("Library/VirtualFolders", options.toString(), admin, query))
        created = true
        val folders =
            server.http
                .expectOk("churn list", server.http.get("Library/VirtualFolders", admin))
                .json()
                .jsonArray
        id =
            folders
                .map { it.jsonObject }
                .first { it.getValue("Name").jsonPrimitive.content == NAME }
                .getValue("ItemId")
                .jsonPrimitive.content
        refresh()
    }

    /** Puts a file called [name] in the library's folder (a copy of a seeded movie). Run [refresh] to see it. */
    fun addFile(name: String) = sh("add $name", "mkdir -p '${dir(name)}' && cp '$SOURCE' '${dir(name)}/$name ($YEAR).mp4'")

    /** Takes the file called [name] away. Run [refresh] to see it go. */
    fun removeFile(name: String) = sh("remove $name", "rm -rf '${dir(name)}'")

    /** Asks the server to scan its libraries. */
    fun refresh() {
        server.http.expectOk("churn scan", server.http.post("Library/Refresh", token = admin))
    }

    override fun close() {
        if (created) runCatching { server.http.delete("Library/VirtualFolders", admin, listOf("name" to NAME)) }
        runCatching { sh("tidy", "rm -rf '$FOLDER'") }
    }

    private fun dir(name: String) = "$FOLDER/$name ($YEAR)"

    private fun sh(
        step: String,
        command: String,
    ) {
        val result = server.jellyfin.execInContainer("sh", "-c", command)
        check(result.exitCode == 0) { "churn step '$step' exited with ${result.exitCode}" }
    }

    private companion object {
        const val NAME = "Churn"
        const val FOLDER = "/media/churn"
        const val YEAR = 2011
        const val SOURCE = "/media/movies/Movie 1 (2001)/Movie 1 (2001).mp4"
    }
}
