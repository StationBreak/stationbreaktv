// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.model.VideoCodecSupport
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.AdapterTuning
import tv.stationbreak.data.jellyfin.DeviceProfiles
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.jellyfin.ProbeTimeouts
import tv.stationbreak.data.jellyfin.mediaCallFactory
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Spec 13: the playback half of the adapter against a real Jellyfin, with the synthetic format clips of
 * `scripts/playback-matrix/synthetic-media.sh` (clips are named by format only). The player's capabilities are a fixed
 * set-top-box-like [INSIGNIA_LIKE]. A case prints what the server answered as `FINDING` / `MATRIX` lines made of format
 * names, routes and stream numbers only: never a URL, a token or an ID. The built URLs are fetched through the
 * adapter's own media call factory (the `Authorization` header is added there), and no assertion message shows a URL or
 * a playlist, because either may carry a token.
 */
@Suppress("TooManyFunctions", "LargeClass") // One function per contract case, plus the helpers they share.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PlaybackContractSuite {
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

    /** One signed-in adapter with the answer to `PlaybackInfo` for one clip. */
    private inner class Played(
        val adapter: JellyfinMediaServer,
        val session: Session,
        val deviceId: String,
        val item: ItemId,
        val info: PlaybackInfo,
        val request: PlaybackRequest,
    ) {
        val source: MediaSource get() = info.sources.first()
        val factory: Call.Factory get() = adapter.mediaCallFactory { session }

        fun subtitles(): List<MediaStreamInfo> = source.streams.filter { it.kind == StreamKind.SUBTITLE }

        fun hls(burnIn: Int? = null): String = info.streams.hls(source.id, request, burnIn)
    }

    private fun adapterFor(deviceId: String): JellyfinMediaServer =
        JellyfinMediaServer(
            OkHttpClient(),
            identity,
            DeviceIdProvider { deviceId },
            AdapterLog.NONE,
            AdapterTuning(timeouts = ProbeTimeouts(connect = 10.seconds, read = 90.seconds, call = 120.seconds)),
        )

    private suspend fun play(
        clip: String,
        request: PlaybackRequest = requestFor(),
        account: Account = server.viewer,
    ): Played {
        val id = server.seeded.formats[clip]
        assertNotNull(id, "the Formats library has no clip '$clip'")
        val deviceId = "contract-playback-${UUID.randomUUID()}"
        val adapter = adapterFor(deviceId)
        val address = server.address
        val acknowledgement = if (address.cleartextNeedsConfirmation) CleartextAcknowledgement(address) else null
        val signedIn = adapter.signIn(SignInRequest(address, account.name, account.password(), acknowledgement))
        assertIs<ServerResult.Success<Session>>(signedIn, "sign-in: ${signedIn.errorOrNull()}")
        val item = ItemId(id)
        val info = adapter.playbackInfo(signedIn.value, item, request)
        assertIs<ServerResult.Success<PlaybackInfo>>(info, "playback info for '$clip': ${info.errorOrNull()}")
        return Played(adapter, signedIn.value, deviceId, item, info.value, request)
    }

    /** What the server answered to one fetch. [body] may hold a token: never print it. */
    private class Fetched(
        val code: Int,
        val body: String,
        val url: HttpUrl,
    ) {
        val ok: Boolean get() = code in 200..299
    }

    private fun fetch(
        factory: Call.Factory,
        url: String,
        range: String? = null,
    ): Fetched = fetch(factory, url.toHttpUrl(), range)

    private fun fetch(
        factory: Call.Factory,
        url: HttpUrl,
        range: String? = null,
    ): Fetched {
        val request =
            Request
                .Builder()
                .url(url)
                .apply { if (range != null) header("Range", range) }
                .build()
        factory.newCall(request).execute().use {
            val text =
                it.body.source().let { source ->
                    source.request(MAX_BODY)
                    source.buffer.readUtf8()
                }
            return Fetched(it.code, text, url)
        }
    }

    /** Every line of [playlist] that is not a tag, resolved against [base] and kept on [base]'s own server. */
    private fun entries(
        base: HttpUrl,
        playlist: String,
    ): List<HttpUrl> =
        playlist.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
            val url = base.resolve(it.trim())
            assertNotNull(url, "a playlist entry isn't a URL")
            assertEquals(base.host, url.host, "a playlist entry points at another host")
            assertEquals(base.port, url.port, "a playlist entry points at another port")
            url
        }

    /** Fetches the built master playlist, its first variant, and returns the master's text. */
    private fun assertPlaylistsServe(
        played: Played,
        burnIn: Int? = null,
    ): String {
        val master = fetch(played.factory, played.hls(burnIn))
        assertEquals(HTTP_OK, master.code, "the master playlist")
        assertTrue(master.body.startsWith("#EXTM3U"), "the master playlist isn't a playlist")
        val variant = fetch(played.factory, entries(master.url, master.body).first())
        assertEquals(HTTP_OK, variant.code, "the first variant playlist")
        assertTrue(variant.body.startsWith("#EXTM3U"), "the variant isn't a playlist")
        return master.body
    }

    private fun finding(text: String) = println("FINDING: $text")

    private fun route(source: MediaSource) =
        "directPlay=${source.supportsDirectPlay} directStream=${source.supportsDirectStream} " +
            "transcoding=${source.supportsTranscoding} reasons=${source.transcodeReasons.sorted()}"

    @Test
    fun h264AacMp4DirectPlays() {
        runBlocking {
            val played = play("h264-aac")

            assertTrue(played.source.supportsDirectPlay, "the stored H.264/AAC MP4 should play as it is")
            val original = fetch(played.factory, played.info.streams.original(played.source.id), "bytes=0-1023")
            assertTrue(original.code in STREAM_OK, "the built original URL didn't stream with the header")
            finding("h264-aac ${route(played.source)}")
        }
    }

    @Test
    fun hevcMkvTranscodes() {
        runBlocking {
            val played = play("hevc-aac")

            assertTrue(!played.source.supportsDirectPlay, "HEVC isn't in the device's list")
            assertTrue(played.source.supportsTranscoding, "the server should offer a transcode")
            assertPlaylistsServe(played)
            finding("hevc-aac ${route(played.source)}")
        }
    }

    @Test
    fun av1Transcodes() {
        runBlocking {
            assumeTrue("av1-aac" in server.seeded.formats, "this ffmpeg has no AV1 encoder: skipped")
            val played = play("av1-aac")

            assertTrue(!played.source.supportsDirectPlay, "AV1 isn't in the device's list")
            assertTrue(played.source.supportsTranscoding, "the server should offer a transcode")
            assertPlaylistsServe(played)
            finding("av1-aac ${route(played.source)}")
        }
    }

    @Test
    fun mpeg4AviTranscodes() {
        runBlocking {
            val played = play("mpeg4-mp3")

            assertTrue(!played.source.supportsDirectPlay, "MPEG-4 part 2 in AVI isn't in the device's list")
            assertTrue(played.source.supportsTranscoding, "the server should offer a transcode")
            assertPlaylistsServe(played)
            finding("mpeg4-mp3 ${route(played.source)}")
        }
    }

    @Test
    fun dtsIsNotDirectPlayed() {
        runBlocking {
            val played = play("h264-dts")

            assertTrue(!played.source.supportsDirectPlay, "the device has no DTS decoder")
            assertPlaylistsServe(played)
            finding("h264-dts viewer(PlayDefaultAudioTrack=false) ${route(played.source)}")
            // Not asserted, because it's the server's answer and not ours to change here: an account with the server's
            // defaults ("play the default audio track" on) is told the same file direct plays (12.1 and 12.2).
            val defaults = play("h264-dts", account = server.admin)
            finding("h264-dts admin(PlayDefaultAudioTrack=true) ${route(defaults.source)}")
        }
    }

    @Test
    fun anOverLimitH264IsReencodedWithinLimits() {
        runBlocking {
            val played = play("h264-high10-l51-3840")

            assertTrue(!played.source.supportsDirectPlay, "10-bit level 5.1 at 3840 is over every limit")
            val master = assertPlaylistsServe(played)
            val streamInf = master.lines().first { it.startsWith("#EXT-X-STREAM-INF") }
            val resolution = assertNotNull(RESOLUTION.find(streamInf), "the variant states no RESOLUTION").destructured
            val codecs = assertNotNull(CODECS.find(streamInf), "the variant states no CODECS").groupValues[1]
            val (width, height) = resolution.toList().map { it.toInt() }
            assertTrue(width <= LIMIT_WIDTH && height <= LIMIT_HEIGHT, "RESOLUTION ${width}x$height is over the limit")
            val avc = assertNotNull(AVC.find(codecs), "the variant states no H.264 codec").destructured
            val profile = avc.component1().toInt(HEX)
            val level = avc.component2().toInt(HEX)
            assertTrue(profile in ALLOWED_PROFILES, "CODECS has H.264 profile_idc $profile, not baseline, main or high")
            assertTrue(level <= LIMIT_LEVEL, "CODECS has H.264 level_idc $level, over 41")
            val variant = "variant=${width}x$height profile=$profile level=$level"
            finding("h264-high10-l51-3840 ${route(played.source)} $variant")
        }
    }

    @Test
    fun burnedInImageSubtitleTranscodes() {
        runBlocking {
            val account = server.admin
            val clip = play("h264-aac-dvdsub", account = account)
            val image = clip.subtitles().single()
            val played = play("h264-aac-dvdsub", requestFor(subtitleIndex = image.index), account)

            val chosen = played.subtitles().single()
            assertEquals(SubtitleDelivery.BURNED_IN, chosen.delivery, "the server should burn the picture subtitle in")
            assertTrue(!played.source.supportsDirectPlay, "burning in needs a transcode")
            assertPlaylistsServe(played, burnIn = chosen.index)
            finding("h264-aac-dvdsub admin codec=${chosen.codec} delivery=${chosen.delivery} ${route(played.source)}")
            // Not asserted: the viewer (subtitles always, English first) selecting the same picture track is told the
            // file still direct plays, with the track marked burned in (12.1 and 12.2). A player has to read the
            // delivery, not only the route.
            val viewer = play("h264-aac-dvdsub", requestFor(subtitleIndex = image.index))
            finding("h264-aac-dvdsub viewer delivery=${viewer.subtitles().single().delivery} ${route(viewer.source)}")
        }
    }

    @Test
    fun externalSrtIsSeparate() {
        runBlocking {
            val probe = play("h264-aac-srt-sidecar")
            val external = probe.subtitles().single { it.isExternal }
            val played = play("h264-aac-srt-sidecar", requestFor(subtitleIndex = external.index))

            val chosen = played.subtitles().single { it.isExternal }
            assertEquals(SubtitleDelivery.SEPARATE, chosen.delivery, "an external SRT is delivered as its own file")
            assertWebVtt(played, chosen)
            finding("h264-aac-srt-sidecar codec=${chosen.codec} delivery=${chosen.delivery} ${route(played.source)}")
        }
    }

    @Test
    fun embeddedSrtOnTranscodeIsSeparate() {
        runBlocking {
            val probe = play("hevc-aac-srt-embedded")
            val text = probe.subtitles().single()
            val played = play("hevc-aac-srt-embedded", requestFor(subtitleIndex = text.index))

            assertTrue(!played.source.supportsDirectPlay, "HEVC needs a transcode")
            val chosen = played.subtitles().single()
            assertEquals(SubtitleDelivery.SEPARATE, chosen.delivery, "a text subtitle on a transcode is its own file")
            assertWebVtt(played, chosen)
            finding("hevc-aac-srt-embedded codec=${chosen.codec} delivery=${chosen.delivery} ${route(played.source)}")
        }
    }

    private fun assertWebVtt(
        played: Played,
        stream: MediaStreamInfo,
    ) {
        val url = played.info.streams.subtitle(played.source.id, stream)
        assertNotNull(url, "no WebVTT URL for a separate text subtitle")
        val vtt = fetch(played.factory, url)
        assertEquals(HTTP_OK, vtt.code, "the subtitle URL")
        assertTrue(vtt.body.startsWith("WEBVTT"), "the subtitle doesn't start with the WEBVTT header")
    }

    @Test
    fun theServerAppliesTheUsersTrackPreferences() {
        runBlocking {
            val played = play("h264-aac-2audio-2subs")

            val audio = played.source.streams.filter { it.kind == StreamKind.AUDIO }
            val subtitles = played.subtitles()
            // Matroska's "fre" reads back as "fra" on 12.1: only English is named, the other is "not English".
            assertEquals(TRACKS_PER_KIND, audio.size)
            assertEquals(TRACKS_PER_KIND, subtitles.size)
            assertEquals(1, audio.count { it.language == "eng" })
            assertEquals(1, subtitles.count { it.language == "eng" })
            val audioIndex = assertNotNull(played.source.defaultAudioIndex, "the server named no default audio")
            val subtitleIndex = assertNotNull(played.source.defaultSubtitleIndex, "no default subtitle named")
            assertEquals("eng", audio.single { it.index == audioIndex }.language, "the default audio isn't English")
            assertEquals(
                "eng",
                subtitles.single { it.index == subtitleIndex }.language,
                "the default subtitle isn't English",
            )
            finding("h264-aac-2audio-2subs defaultAudio=$audioIndex defaultSubtitle=$subtitleIndex")
        }
    }

    @Test
    fun anAccountWithoutPreferencesGetsTheFilesOwnDefaults() {
        runBlocking {
            // The admin keeps the server's defaults: "play the default audio track" on, subtitles only when forced.
            val played = play("h264-aac-2audio-2subs", account = server.admin)

            val audio = played.source.streams.filter { it.kind == StreamKind.AUDIO }
            val flagged = audio.single { it.isDefault }
            assertEquals(flagged.index, played.source.defaultAudioIndex, "the file's default audio track")
            assertNull(played.source.defaultSubtitleIndex, "no subtitle without a preference")
            finding("h264-aac-2audio-2subs default-config defaultAudio=${played.source.defaultAudioIndex}")
        }
    }

    @Test
    fun noCuesMkvIsListed() {
        runBlocking {
            val played = play("h264-aac-nocues")

            assertTrue(played.source.streams.any { it.kind == StreamKind.VIDEO }, "the server found no video stream")
            assertTrue(played.source.supportsDirectPlay, "an MKV without Cues is still the stored H.264/AAC")
            finding("h264-aac-nocues ${route(played.source)} runtime=${played.source.runtime}")
        }
    }

    @Test
    fun theBuiltUrlStartsAtTheOffset() {
        runBlocking {
            val played = play("hevc-aac", requestFor(start = 30.seconds))

            assertEquals("300000000", played.hls().toHttpUrl().queryParameter("StartTimeTicks"))
            assertPlaylistsServe(played)
        }
    }

    @Test
    fun stopEncodingEndsTheJob() {
        runBlocking {
            // A clip no other case transcodes, forced through a full encode, so the files below are this job's alone.
            val played = play("h264-aac-moov-end", requestFor(forceTranscode = true))
            val before = transcodeFiles()
            val master = fetch(played.factory, played.hls())
            val variant = fetch(played.factory, entries(master.url, master.body).first())
            assertEquals(HTTP_OK, variant.code, "the first variant playlist")
            val segment = fetch(played.factory, entries(variant.url, variant.body).first())
            assertEquals(HTTP_OK, segment.code, "the first segment")
            val playSession = assertNotNull(played.info.playSessionId, "the server gave no play session")

            val mine = transcodeFiles() - before
            assertTrue(mine.isNotEmpty(), "the transcode left no files to watch")
            assertEquals(ServerResult.Success(Unit), played.adapter.stopEncoding(played.session, playSession))
            assertTrue(awaitGone(mine), "the job's files outlived stopEncoding")
        }
    }

    /** The transcode files in the server container (paths hold the job's hash, no library name), by path. */
    private fun transcodeFiles(): Set<String> =
        server.jellyfin
            .execInContainer("sh", "-c", "find /cache /config /tmp -type f -path '*/transcodes/*' 2>/dev/null")
            .stdout
            .lines()
            .filter { it.isNotBlank() }
            .toSet()

    /** Polls, bounded, until none of [files] is left. */
    private suspend fun awaitGone(files: Set<String>): Boolean {
        repeat(JOB_POLLS) {
            if ((transcodeFiles() intersect files).isEmpty()) return true
            delay(JOB_POLL_MS)
        }
        return false
    }

    @Test
    fun theBuiltUrlMatchesTheServersLimits() {
        runBlocking {
            val played = play("h264-high10-l51-3840")
            val serverUrl = serverTranscodingUrl(played)
            val built = played.hls().toHttpUrl()

            val compared = serverUrl.queryParameterNames.filter { it.lowercase() !in IGNORED_PARAMETERS }
            val missing = compared.filter { built.queryParameter(it) == null }
            val different =
                compared.filter { built.queryParameter(it) != null }.filter {
                    built.queryParameter(it) != serverUrl.queryParameter(it)
                }
            val values =
                different.joinToString {
                    "$it server=${serverUrl.queryParameter(it)} built=${built.queryParameter(it)}"
                }
            val serverOnly = missing.sorted().joinToString { "$it=${serverUrl.queryParameter(it)}" }
            finding("drift: server-only parameters=[$serverOnly] different values=${values.ifEmpty { "none" }}")
            assertEquals(emptyList(), different.sorted(), "parameters the server sets differently from the built URL")
            assertEquals(emptyList(), missing.filter { it.lowercase() in CODEC_AND_LIMIT }.sorted(), "limits not built")
        }
    }

    /** The server's own `TranscodingUrl` for the clip in [played], read in test code only and never printed. */
    private fun serverTranscodingUrl(played: Played): HttpUrl {
        val token = server.http.signIn(server.viewer.name, server.viewer.rawPassword())
        val body = String(DeviceProfiles.body(played.request, played.session.userId), Charsets.UTF_8)
        val reply =
            server.http.expectOk(
                "raw playback info",
                server.http.post("Items/${played.item.value}/PlaybackInfo", body, token),
            )
        val source =
            reply
                .obj()
                .getValue("MediaSources")
                .jsonArray
                .first()
                .jsonObject
        val relative = source.getValue("TranscodingUrl").jsonPrimitive.content
        return relativeUrl(relative)
    }

    @Test
    fun recordWhatTheServerAnswersForEveryClip() {
        runBlocking {
            for (clip in server.seeded.formats.keys
                .sorted()) {
                val played = play(clip)
                val subs =
                    played.subtitles().joinToString(",") {
                        "${it.index}:${it.codec}:${it.delivery}:ext=${it.isExternal}"
                    }
                println("MATRIX clip=$clip ${route(played.source)} container=${played.source.container} subs=[$subs]")
                println("MATRIX clip=$clip reasonsWire=${reasonsWire(played)}")
                println("MATRIX clip=$clip facts: ${streamFacts(played)}")
            }
        }
    }

    /** The server's raw answer for [played]'s first source (test code only: it holds the server's URLs). */
    private fun rawSource(played: Played): JsonObject {
        val token = server.http.signIn(server.viewer.name, server.viewer.rawPassword())
        val body = String(DeviceProfiles.body(played.request, played.session.userId), Charsets.UTF_8)
        val reply =
            server.http.expectOk(
                "raw playback info",
                server.http.post("Items/${played.item.value}/PlaybackInfo", body, token),
            )
        return reply
            .obj()
            .getValue("MediaSources")
            .jsonArray
            .first()
            .jsonObject
    }

    /**
     * What `TranscodeReasons` looks like on the wire for [played]'s first source: its JSON shape in the source itself,
     * and the reasons in the `TranscodingUrl`'s own `TranscodeReasons` parameter (enum names).
     */
    private fun reasonsWire(played: Played): String {
        val source = rawSource(played)
        val inSource =
            when (val reasons: JsonElement? = source["TranscodeReasons"]) {
                null -> "absent"
                is JsonNull -> "null"
                is JsonArray -> "array${reasons.map { (it as? JsonPrimitive)?.content }}"
                is JsonPrimitive -> "primitive(isString=${reasons.isString})'${reasons.content}'"
                is JsonObject -> "object"
            }
        val url = source["TranscodingUrl"]?.jsonPrimitive?.content
        val inUrl = url?.let { relativeUrl(it).queryParameter("TranscodeReasons") }
        return "inSource=$inSource inUrl=$inUrl sourceKeys=${source.keys.sorted()}"
    }

    private fun relativeUrl(relative: String): HttpUrl = "http://x.invalid/${relative.trimStart('/')}".toHttpUrl()

    /** The video and audio streams' own facts (format names and numbers), to read the server's decision against. */
    private fun streamFacts(played: Played): String {
        val streams = rawSource(played).getValue("MediaStreams").jsonArray.map { it.jsonObject }
        return streams
            .filter { it["Type"]?.jsonPrimitive?.content in setOf("Video", "Audio") }
            .joinToString(" ; ") { stream ->
                listOf("Type", "Codec", "Profile", "Level", "BitDepth", "Width", "Height", "Channels", "Language")
                    .mapNotNull { key -> stream[key]?.let { "$key=${it.jsonPrimitive.content}" } }
                    .joinToString(" ")
            }
    }

    @Test
    fun embeddedSrtOnDirectPlayIsInStream() {
        runBlocking {
            val probe = play("h264-aac-srt-embedded")
            val text = probe.subtitles().single()
            val played = play("h264-aac-srt-embedded", requestFor(subtitleIndex = text.index))

            assertTrue(played.source.supportsDirectPlay, "H.264/AAC in MKV plays as it is")
            assertEquals(SubtitleDelivery.IN_STREAM, played.subtitles().single().delivery)
            assertNull(played.info.streams.subtitle(played.source.id, played.subtitles().single()))
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val TRACKS_PER_KIND = 2
        val STREAM_OK = setOf(HTTP_OK, 206)
        const val MAX_BODY = 1_000_000L
        const val HEX = 16
        const val LIMIT_WIDTH = 1920
        const val LIMIT_HEIGHT = 1080
        const val LIMIT_LEVEL = 41
        val ALLOWED_PROFILES = setOf(66, 77, 100)
        val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")
        val CODECS = Regex("""CODECS="([^"]*)"""")
        val AVC = Regex("""avc1\.([0-9a-fA-F]{2})[0-9a-fA-F]{2}([0-9a-fA-F]{2})""")
        const val JOB_POLLS = 20
        const val JOB_POLL_MS = 1_000L

        /** Parameters that identify the request or the client, not a codec or a limit (lowercase). */
        val IGNORED_PARAMETERS = setOf("api_key", "apikey", "deviceid", "playsessionid", "transcodereasons")

        /** The parameters that carry a codec or a limit the built URL must also set, when the server sets them. */
        val CODEC_AND_LIMIT =
            setOf(
                "videocodec",
                "audiocodec",
                "maxaudiochannels",
                "transcodingmaxaudiochannels",
                "maxwidth",
                "maxheight",
                "h264-level",
                "h264-profile",
                "h264-videobitdepth",
                "videobitrate",
                "maxstreamingbitrate",
                "segmentcontainer",
            )

        /** An Insignia-like set-top box: H.264 to level 4.1 at 1080p, MPEG-2, AAC/MP3/AC-3, stereo, no passthrough. */
        val INSIGNIA_LIKE =
            DeviceCapabilities(
                containers = setOf("mp4", "m4v", "mkv", "webm", "ts"),
                videoCodecs =
                    listOf(
                        VideoCodecSupport("h264", setOf("high", "main", "baseline"), 41, 1920, 1080, 8, emptySet()),
                        VideoCodecSupport("mpeg2video", emptySet(), null, 1920, 1080, 8, emptySet()),
                    ),
                audioDecoders = setOf("aac", "mp3", "ac3"),
                audioPassthrough = emptySet(),
                maxAudioChannels = 2,
            )

        fun requestFor(
            start: Duration = Duration.ZERO,
            audioIndex: Int? = null,
            subtitleIndex: Int? = null,
            forceTranscode: Boolean = false,
        ) = PlaybackRequest(INSIGNIA_LIKE, start, audioIndex, subtitleIndex, forceTranscode)
    }
}

/** The playback suite against a server on its own port, with the format clips seeded. */
class DirectPlaybackContractTest : PlaybackContractSuite() {
    override fun startServer(image: String): JellyfinServer = JellyfinServer.direct(image, formats = true)
}
