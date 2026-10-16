// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
