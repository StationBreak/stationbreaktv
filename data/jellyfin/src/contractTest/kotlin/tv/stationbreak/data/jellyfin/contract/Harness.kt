// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** An answer from the server under test. Its body may hold a token, so it is parsed, never printed. */
internal class Reply(
    val code: Int,
    private val body: String,
) {
    val ok: Boolean get() = code in 200..299

    fun json(): JsonElement = Json.parseToJsonElement(body)

    fun obj(): JsonObject = json().jsonObject

    override fun toString(): String = "HTTP $code"
}

/**
 * The suite's own plain HTTP calls: seeding the server, approving a Quick Connect code, reading a resume position
 * back. It never prints a request or a body, because those carry the per-run passwords and tokens; a failed step says
 * the step's name and the status code and nothing else.
 */
internal class Harness(
    private val base: HttpUrl,
) {
    private val client =
        OkHttpClient
            .Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    private val deviceId = "contract-harness-${UUID.randomUUID()}"

    fun get(
        path: String,
        token: String? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): Reply = call("GET", path, null, token, query)

    fun post(
        path: String,
        body: String? = null,
        token: String? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): Reply = call("POST", path, body.orEmpty(), token, query)

    fun delete(
        path: String,
        token: String? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): Reply = call("DELETE", path, null, token, query)

    /** Fails the suite with the step's name and status, never the body. */
    fun expectOk(
        step: String,
        reply: Reply,
    ): Reply {
        check(reply.ok) { "harness step '$step' got $reply" }
        return reply
    }

    /**
     * As [expectOk] for a step that changes server state, retried while the server answers that it is busy: a
     * conflict, a rate limit, or a server error (Jellyfin's SQLite can refuse a write while a scan or a metadata
     * refresh holds the database). Any other status fails at once, and so does the last busy answer after [attempts]
     * tries. The failure names the step, the status and the tries, never a body.
     */
    fun expectOkEventually(
        step: String,
        attempts: Int = BUSY_ATTEMPTS,
        call: () -> Reply,
    ): Reply {
        var last = call()
        var tries = 1
        while (!last.ok && last.code in BUSY && tries < attempts) {
            Thread.sleep(BUSY_PAUSE_MS)
            last = call()
            tries++
        }
        check(last.ok) { "harness step '$step' got $last after $tries ${if (tries == 1) "try" else "tries"}" }
        return last
    }

    /**
     * Waits, bounded, until no scheduled task (a library scan, say) is running, so the next write does not race it.
     * The tasks listing needs an admin [token].
     */
    fun awaitIdle(token: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IDLE_SECONDS)
        while (true) {
            val tasks = expectOk("list tasks", get("ScheduledTasks", token)).json().jsonArray
            val running = tasks.count { it.jsonObject["State"]?.jsonPrimitive?.content != "Idle" }
            if (running == 0) return
            check(System.nanoTime() < deadline) { "harness: $running scheduled tasks still busy after $IDLE_SECONDS s" }
            Thread.sleep(BUSY_PAUSE_MS)
        }
    }

    /** A password sign-in made by the harness itself (a separate device from the adapter's). */
    fun signIn(
        name: String,
        password: String,
    ): String {
        val body =
            buildJsonObject {
                put("Username", name)
                put("Pw", password)
            }
        val reply = expectOk("sign in $name", post("Users/AuthenticateByName", body.toString()))
        return reply
            .obj()
            .getValue("AccessToken")
            .jsonPrimitive.content
    }

    private fun call(
        method: String,
        path: String,
        body: String?,
        token: String?,
        query: List<Pair<String, String>>,
    ): Reply {
        val url =
            base
                .newBuilder()
                .addPathSegments(path)
                .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", header(token))
                .method(method, body?.toRequestBody(JSON))
                .build()
        client.newCall(request).execute().use { return Reply(it.code, it.body.string()) }
    }

    private fun header(token: String?): String {
        val tokenPart = if (token == null) "" else ", Token=\"$token\""
        val who = "Client=\"Contract harness\", Device=\"Harness\", DeviceId=\"$deviceId\", Version=\"1\""
        return "MediaBrowser $who$tokenPart"
    }

    private companion object {
        const val BUSY_ATTEMPTS = 15
        const val BUSY_PAUSE_MS = 2_000L
        const val IDLE_SECONDS = 90L
        val BUSY = setOf(409, 423, 429, 500, 502, 503, 504)
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
