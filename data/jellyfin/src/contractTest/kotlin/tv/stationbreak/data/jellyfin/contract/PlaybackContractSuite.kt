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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.images.builder.Transferable
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
import tv.stationbreak.data.jellyfin.mediaCallFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
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
    @Suppress("LongParameterList") // A plain carrier for one case's state.
    private inner class Played(
        val adapter: JellyfinMediaServer,
        val session: Session,
        val deviceId: String,
        val item: ItemId,
        val info: PlaybackInfo,
        val request: PlaybackRequest,
        val account: Account,
    ) {
        val source: MediaSource get() = info.sources.first()
        val factory: Call.Factory get() = adapter.mediaCallFactory { session }

        fun subtitles(): List<MediaStreamInfo> = source.streams.filter { it.kind == StreamKind.SUBTITLE }

        fun hls(burnIn: Int? = null): String =
            info.streams.hls(
                source,
                request.audioIndex,
                burnIn,
                request.capabilities,
                request.forceTranscode,
            )
    }

    private fun adapterFor(deviceId: String): JellyfinMediaServer =
        JellyfinMediaServer(
            OkHttpClient(),
            identity,
            DeviceIdProvider { deviceId },
            AdapterLog.NONE,
            AdapterTuning(),
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
        return Played(adapter, signedIn.value, deviceId, item, info.value, request, account).also { started += it }
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

    // The reasons are n/a: the servers send them only inside the TranscodingUrl, so MediaSource.transcodeReasons is
    // empty by design (ruling R8). The recorder prints the server's own list from the raw answer.
    private fun route(source: MediaSource) =
        "directPlay=${source.supportsDirectPlay} directStream=${source.supportsDirectStream} " +
            "transcoding=${source.supportsTranscoding} reasons=n/a"

    /** Every play session a case started, so each encode is stopped when the case ends. */
    private val started = CopyOnWriteArrayList<Played>()

    @AfterEach
    fun stopEveryEncodeTheCaseStarted() {
        runBlocking {
            started.forEach { played ->
                played.info.playSessionId?.let { runCatching { played.adapter.stopEncoding(played.session, it) } }
            }
        }
        started.clear()
    }

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

    /**
     * Shows only what the server does for an account whose "play default audio track" is OFF (the seeded viewer): it
     * checks the audio codec against the profile and routes the DTS clip to a transcode. It does not show that DTS is
     * never direct played; see [dtsIsDirectPlayedForAnAccountThatPlaysTheDefaultTrack].
     */
    @Test
    fun dtsIsTranscodedWhenTheAccountDoesNotPlayTheDefaultTrack() {
        runBlocking {
            val played = play("h264-dts")

            assertTrue(!played.source.supportsDirectPlay, "the device has no DTS decoder")
            assertPlaylistsServe(played)
            finding("h264-dts viewer(PlayDefaultAudioTrack=false) ${route(played.source)}")
        }
    }

    /**
     * Pins the server's real answer for an account with the server's defaults ("play default audio track" ON, the
     * admin): the DTS clip is reported as direct playable (12.1 and 12.2 agree), although the profile lists no DTS. The
     * client guards this: the spec 13 resolver refuses DIRECT_PLAY when the chosen audio codec is in neither the
     * decoders nor the passthrough (ruling R6). If a server version starts agreeing with the profile, this fails and
     * the guard can be revisited.
     */
    @Test
    fun dtsIsDirectPlayedForAnAccountThatPlaysTheDefaultTrack() {
        runBlocking {
            val played = play("h264-dts", account = server.admin)

            assertTrue(played.source.supportsDirectPlay, "the server's answer for a default-config account changed")
            val audio = played.source.streams.single { it.kind == StreamKind.AUDIO }
            assertEquals("dts", audio.codec?.lowercase())
            finding("h264-dts admin(PlayDefaultAudioTrack=true) ${route(played.source)}")
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

    /**
     * The route the resolver takes for undecodable audio with an embedded text subtitle (ADR-0016, update 2026-10-22):
     * the playlist carries no text subtitle, so the stream is fetched as WebVTT from the server's own endpoint, which
     * extracts the embedded track. The server's delivery for this combination is recorded, not relied on.
     */
    @Test
    fun anEmbeddedSrtOfAFileWithUndecodableAudioServesAsVtt() {
        runBlocking {
            val probe = play("h264-dts-srt-embedded", account = server.admin)
            val text = probe.subtitles().single()
            val played = play("h264-dts-srt-embedded", requestFor(subtitleIndex = text.index), server.admin)

            assertTrue(played.source.supportsDirectStream, "the video can be copied, only the audio converted")
            val chosen = played.subtitles().single()
            finding("h264-dts-srt-embedded codec=${chosen.codec} delivery=${chosen.delivery} ${route(played.source)}")
            val asVtt = chosen.copy(delivery = SubtitleDelivery.SEPARATE)
            val url = played.info.streams.subtitle(played.source.id, asVtt)
            assertNotNull(url, "no WebVTT URL for an embedded text subtitle")
            val vtt = fetch(played.factory, url)
            assertEquals(HTTP_OK, vtt.code, "the subtitle URL")
            assertTrue(vtt.body.startsWith("WEBVTT"), "the subtitle doesn't start with the WEBVTT header")
            assertTrue("-->" in vtt.body && "Hello" in vtt.body, "the extracted track holds no cues")
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

    /**
     * The server applies the user's track preferences (null indices): the English audio and English subtitle are the
     * defaults, although the French audio is the one the file flags. Precondition, set when seeding: the user has
     * "play default audio track" OFF. With it on (the server's default) the file's flagged track wins over the
     * language preference; see [anAccountThatPlaysTheDefaultTrackGetsTheFilesFlaggedAudio].
     */
    @Test
    fun theServerAppliesTheUsersLanguagePreferencesWhenTheyDontPlayTheDefaultTrack() {
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

    /** The other side of the precondition above: with the server's defaults the file's flagged audio is the default. */
    @Test
    fun anAccountThatPlaysTheDefaultTrackGetsTheFilesFlaggedAudio() {
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
    fun theBuiltUrlCarriesNoStartAndTheSegmentsServe() {
        runBlocking {
            // The server copies the master's query into the variant and segment URLs, and refuses StartTimeTicks on a
            // segment (400, 12.2): so the URL has none, and the player starts by seeking. Fetch what it would.
            val played = play("h264-aac", requestFor(start = START_S.seconds, forceTranscode = true))

            assertSegmentsServeAt(played, START_S.toDouble())
        }
    }

    /**
     * The same for a direct stream, the route the resolver takes when the server agrees to a direct play the device
     * can't do: the admin's DTS clip is offered as direct play and direct stream (see
     * [dtsIsDirectPlayedForAnAccountThatPlaysTheDefaultTrack]), the device has no DTS decoder, so it plays by HLS with
     * the video copied (AllowVideoStreamCopy).
     */
    @Test
    fun aDirectStreamStartsAtThePlayersPositionToo() {
        runBlocking {
            val request = requestFor(start = START_S.seconds)
            val played = play("h264-dts", request, server.admin)

            assertTrue(played.source.supportsDirectStream, "the server should offer a direct stream")
            val audio = played.source.streams.single { it.kind == StreamKind.AUDIO }
            assertEquals("dts", audio.codec?.lowercase())
            assertTrue("dts" !in request.capabilities.audioDecoders, "the device would direct play it")
            assertEquals("true", played.hls().toHttpUrl().queryParameter("AllowVideoStreamCopy"))
            assertSegmentsServeAt(played, START_S.toDouble())
        }
    }

    /**
     * HEVC with audio the device can't play: the video is copied and only the audio converted (design goal 4), so the
     * server offers a direct stream and serves fMP4 segments (HEVC is not put in MPEG-TS). The device decodes HEVC.
     */
    @Test
    fun hevcWithUndecodableAudioIsADirectStreamOfFmp4Segments() {
        runBlocking {
            val played = play("hevc-dts", requestFor(HEVC_DEVICE), server.admin)

            assertTrue(played.source.supportsDirectStream, "the HEVC video should be copied, only the audio converted")
            val url = played.hls().toHttpUrl()
            assertEquals("h264,hevc", url.queryParameter("VideoCodec"))
            assertEquals("mp4", url.queryParameter("SegmentContainer"))
            val master = fetch(played.factory, played.hls())
            assertEquals(HTTP_OK, master.code, "the master playlist")
            val variant = fetch(played.factory, entries(master.url, master.body).first())
            assertEquals(HTTP_OK, variant.code, "the variant playlist")
            assertTrue("#EXT-X-MAP" in variant.body, "the variant has no fMP4 initialisation section")
            val segment = fetchBytes(played.factory, entries(variant.url, variant.body).first())
            assertEquals(HTTP_OK, segment.first, "the first segment")
            val type = segment.second.copyOfRange(BOX_TYPE_AT, BOX_TYPE_AT + BOX_TYPE_LENGTH)
            val box = String(type, Charsets.ISO_8859_1)
            assertTrue(box in FMP4_BOXES, "the first segment isn't fMP4 (starts with a '$box' box)")
            finding("hevc-dts ${route(played.source)} firstBox=$box")
        }
    }

    /**
     * Fetches the master, the variant, the segment covering [second] and segment 0 of [played]'s HLS URL, none with a
     * StartTimeTicks. Each is HTTP 200 and the segments are non-empty MPEG-TS. The covering segment starts where the
     * playlist says (within 2 s, from segment 0's own first timestamp), which is what guards HLS placement (ADR-0016).
     */
    private fun assertSegmentsServeAt(
        played: Played,
        second: Double,
    ) {
        assertNull(played.hls().toHttpUrl().queryParameter("StartTimeTicks"))
        val master = fetch(played.factory, played.hls())
        assertEquals(HTTP_OK, master.code, "the master playlist")
        val variant = fetch(played.factory, entries(master.url, master.body).first())
        assertEquals(HTTP_OK, variant.code, "the variant playlist")
        assertTrue(variant.body.startsWith("#EXTM3U"), "the variant isn't a playlist")
        val segments = entries(variant.url, variant.body)
        val covering = segmentIndexAt(variant.body, second)
        assertTrue(covering > 0 && covering < segments.size, "no segment covers $second s of ${segments.size}")
        val firstPts = mutableMapOf<Int, Double>()
        for (index in listOf(0, covering)) {
            val segment = fetchBytes(played.factory, segments[index])
            assertEquals(HTTP_OK, segment.first, "segment $index")
            assertTrue(segment.second.isNotEmpty(), "segment $index is empty")
            assertEquals(TS_SYNC, segment.second[0], "segment $index isn't MPEG-TS")
            firstPts[index] = firstTimestamp(segment.second)
        }
        val expected = firstPts.getValue(0) + playlistStart(variant.body, covering)
        val actual = firstPts.getValue(covering)
        assertTrue(
            abs(actual - expected) <= PLACEMENT_TOLERANCE_S,
            "segment $covering starts at $actual s, the playlist puts it at $expected s",
        )
    }

    /** The seconds before segment [index] of [playlist]: the sum of the earlier `#EXTINF` durations. */
    private fun playlistStart(
        playlist: String,
        index: Int,
    ): Double =
        playlist
            .lines()
            .filter { it.startsWith("#EXTINF:") }
            .take(index)
            .sumOf {
                it
                    .removePrefix("#EXTINF:")
                    .substringBefore(',')
                    .trim()
                    .toDouble()
            }

    /** The first timestamp of an MPEG-TS [segment], in seconds, by the server's own ffprobe. */
    private fun firstTimestamp(segment: ByteArray): Double {
        val path = "/tmp/segment-${UUID.randomUUID()}.ts"
        server.jellyfin.copyFileToContainer(Transferable.of(segment), path)
        val probed =
            server.jellyfin.execInContainer(
                "/usr/lib/jellyfin-ffmpeg/ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=start_time",
                "-of",
                "csv=p=0",
                path,
            )
        server.jellyfin.execInContainer("rm", "-f", path)
        assertEquals(0, probed.exitCode, "ffprobe on the segment")
        return assertNotNull(probed.stdout.trim().toDoubleOrNull(), "ffprobe found no start time")
    }

    /** The index of the segment of [playlist] that covers [second], by its `#EXTINF` durations. */
    private fun segmentIndexAt(
        playlist: String,
        second: Double,
    ): Int {
        var elapsed = 0.0
        var index = 0
        for (line in playlist.lines()) {
            if (!line.startsWith("#EXTINF:")) continue
            elapsed +=
                line
                    .removePrefix("#EXTINF:")
                    .substringBefore(',')
                    .trim()
                    .toDouble()
            if (elapsed > second) return index
            index++
        }
        return index
    }

    /** The status and the raw body of one fetch. */
    private fun fetchBytes(
        factory: Call.Factory,
        url: HttpUrl,
    ): Pair<Int, ByteArray> {
        val request = Request.Builder().url(url).build()
        return factory.newCall(request).execute().use { it.code to it.body.bytes() }
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
        runBlocking { assertNoDrift("limits", play("h264-high10-l51-3840")) }
    }

    /** A burn-in request: the server's `SubtitleMethod` and `SubtitleStreamIndex` are the built ones. */
    @Test
    fun theBuiltUrlMatchesTheServersBurnIn() {
        runBlocking {
            val admin = server.admin
            val image = play("h264-aac-dvdsub", account = admin).subtitles().single()
            val played = play("h264-aac-dvdsub", requestFor(subtitleIndex = image.index), admin)
            val burned = played.subtitles().single()
            assertEquals(SubtitleDelivery.BURNED_IN, burned.delivery)

            val serverUrl = serverTranscodingUrl(played)
            assertEquals("Encode", serverUrl.queryParameter("SubtitleMethod"), "the server's own burn-in method")
            assertEquals(burned.index.toString(), serverUrl.queryParameter("SubtitleStreamIndex"))
            assertNoDrift("burn-in", played, burnIn = burned.index, sourceFacts = COPIED_VIDEO_FACTS)
        }
    }

    /** An AC-3 passthrough request: the server's `AudioCodec` is the built one. */
    @Test
    fun theBuiltUrlMatchesTheServersPassthrough() {
        runBlocking {
            val capabilities = INSIGNIA_LIKE.copy(audioPassthrough = setOf("ac3"))
            val played = play("h264-ac3", requestFor(capabilities = capabilities, forceTranscode = true))

            assertTrue(
                played
                    .hls()
                    .toHttpUrl()
                    .queryParameter("AudioCodec")
                    .orEmpty()
                    .contains("ac3"),
            )
            // The server names the audio codec it chose (the clip's own AC-3) where the built URL lists those
            // allowed. Its audio bitrate is the clip's own 96 kb/s, where HlsUrls assumes stereo AAC's 192 kb/s (a
            // known limit of AUDIO_RESERVE, #324), and the video budget follows from it.
            assertNoDrift(
                "ac3 passthrough",
                played,
                sourceFacts = COPIED_VIDEO_FACTS + setOf("audiobitrate", "videobitrate"),
                permitted = setOf("audiocodec"),
            )
        }
    }

    /**
     * The HLS URL carries the audio track the server resolved from the user's own preference, which is not the file's
     * first. The built URL, the server's own URL and the transcoded audio itself all name the preferred track.
     */
    @Test
    fun theTranscodePlaysTheTrackTheServerResolved() {
        runBlocking {
            val played = play("h264-aac-pref-audio-second", requestFor(forceTranscode = true))

            val audio = played.source.streams.filter { it.kind == StreamKind.AUDIO }
            val preferred = audio.single { it.language == "eng" }
            val first = audio.minOf { it.index }
            assertTrue(preferred.index > first, "the preferred track should not be the first")
            assertEquals(preferred.index, played.source.defaultAudioIndex, "the server's resolved track")
            assertEquals(preferred.index.toString(), played.hls().toHttpUrl().queryParameter("AudioStreamIndex"))
            assertEquals(
                preferred.index.toString(),
                serverTranscodingUrl(played).queryParameter("AudioStreamIndex"),
                "the server's own URL",
            )
            assertNoDrift("preferred audio", played, sourceFacts = COPIED_VIDEO_FACTS)
            // The preferred track is the loud one; the file's first is quiet.
            val loudness = segmentLoudness(played, played.hls())
            assertTrue(loudness > LOUD_ABOVE_DB, "the transcode should carry the loud preferred track: $loudness dB")
            // The control, on a session of its own (a session reuses its running job whatever the URL says): the
            // request naming the file's first track carries the quiet one, so loudness tells the tracks apart.
            val explicit = play("h264-aac-pref-audio-second", requestFor(audioIndex = first, forceTranscode = true))
            val quiet = segmentLoudness(explicit, explicit.hls())
            assertTrue(quiet < LOUD_ABOVE_DB, "naming the file's first track should carry the quiet one: $quiet dB")
            // Recorded, not relied on: a URL that names no track, on a session of its own.
            val bare = play("h264-aac-pref-audio-second", requestFor(forceTranscode = true))
            val without =
                bare
                    .hls()
                    .toHttpUrl()
                    .newBuilder()
                    .removeAllQueryParameters("AudioStreamIndex")
                    .build()
            val unnamed = segmentLoudness(bare, without.toString())
            finding("h264-aac-pref-audio-second preferredIndex=${preferred.index} firstIndex=$first")
            finding("h264-aac-pref-audio-second loudness named=$loudness first=$quiet unnamed=$unnamed")
        }
    }

    /**
     * Fetches the first segment of the transcode behind [masterUrl] and measures the mean volume (dB) of its audio with
     * the server's own ffmpeg.
     */
    private fun segmentLoudness(
        played: Played,
        masterUrl: String,
    ): Double {
        val master = fetch(played.factory, masterUrl)
        val variant = fetch(played.factory, entries(master.url, master.body).first())
        val segmentUrl = entries(variant.url, variant.body).first()
        val request = Request.Builder().url(segmentUrl).build()
        val bytes =
            played.factory.newCall(request).execute().use {
                assertEquals(HTTP_OK, it.code, "the first segment")
                it.body.bytes()
            }
        val path = "/tmp/segment-${UUID.randomUUID()}.ts"
        server.jellyfin.copyFileToContainer(Transferable.of(bytes), path)
        val measured =
            server.jellyfin.execInContainer(
                "/usr/lib/jellyfin-ffmpeg/ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-nostats",
                "-i",
                path,
                "-vn",
                "-af",
                "volumedetect",
                "-f",
                "null",
                "-",
            )
        server.jellyfin.execInContainer("rm", "-f", path)
        assertEquals(0, measured.exitCode, "ffmpeg on the segment")
        val mean = assertNotNull(MEAN_VOLUME.find(measured.stderr), "ffmpeg measured no volume").groupValues[1]
        return mean.toDouble()
    }

    /**
     * Every parameter the server sets must be in the built URL with the same value, except [IGNORED_PARAMETERS] and
     * [sourceFacts] (parameters this case's server URL fills from the clip itself, which the clip's case explains).
     * Names compare case-insensitively (the server writes `allowVideoStreamCopy`, the built URL `AllowVideoStreamCopy`;
     * it reads either). A name in [permitted] is a list the server narrowed to what it will do: its values must be
     * among the built URL's. Only names are in a failure message (a value of api_key would be a token).
     */
    private fun assertNoDrift(
        label: String,
        played: Played,
        burnIn: Int? = null,
        sourceFacts: Set<String> = emptySet(),
        permitted: Set<String> = emptySet(),
    ) {
        val serverUrl = serverTranscodingUrl(played)
        val built = played.hls(burnIn).toHttpUrl()

        fun HttpUrl.value(name: String): String? =
            queryParameterNames.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::queryParameter)
        val skipped = IGNORED_PARAMETERS + sourceFacts
        val compared = serverUrl.queryParameterNames.filter { it.lowercase() !in skipped }
        val absent = compared.filter { built.value(it) == null }
        val different =
            compared
                .mapNotNull { name -> built.value(name)?.let { name to it } }
                .filter { (name, ours) ->
                    val theirs = serverUrl.value(name)
                    if (name.lowercase() in permitted) {
                        !ours.split(',').toSet().containsAll(theirs.orEmpty().split(','))
                    } else {
                        ours != theirs
                    }
                }.map { (name, _) -> name }
        val values = different.joinToString { "$it server=${serverUrl.value(it)} built=${built.value(it)}" }
        finding("drift $label: different values=${values.ifEmpty { "none" }} absent=${absent.sorted()}")
        assertEquals(emptyList(), absent.sorted(), "$label: parameters the server sets that the built URL lacks")
        assertEquals(emptyList(), different.sorted(), "$label: parameters set differently from the built URL")
    }

    /** The server's own `TranscodingUrl` for the clip in [played], read in test code only and never printed. */
    private fun serverTranscodingUrl(played: Played): HttpUrl {
        val token = server.http.signIn(played.account.name, played.account.rawPassword())
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

    /**
     * A recorder, not a check: it asserts nothing by design. It prints one `MATRIX` line per clip (route, container,
     * subtitle delivery, the wire shape of the reasons, the streams' facts) for the playback matrix and the reports.
     */
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
        val token = server.http.signIn(played.account.name, played.account.rawPassword())
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

        /** Where the placement cases start, in seconds of the 60 s clips. */
        const val START_S = 30

        /** How far a segment's first timestamp may be from where its playlist puts it. */
        const val PLACEMENT_TOLERANCE_S = 2.0

        /** The first byte of every MPEG-TS packet. */
        const val TS_SYNC: Byte = 0x47
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

        /**
         * The only server parameters the built URL need not carry (lowercase). Identity of the request or client:
         * api_key/apikey (a credential, never built), deviceid, playsessionid, transcodereasons. Facts the server
         * derives from the source file or its own defaults, not device limits: tag (the item's etag), audiosamplerate,
         * maxframerate and h264-audiochannels (the clip's own rate, fps and channel count), aac-audiochannels and
         * aac-profile (per-stream echoes of the audio it writes; the limit we send is TranscodingMaxAudioChannels),
         * enableaudiovbrencoding and requireavc (encoder defaults). starttimeticks is left off the built URL on
         * purpose: the server rejects it on segments (12.2), so the player seeks instead (ADR-0016).
         */
        val IGNORED_PARAMETERS =
            setOf(
                "api_key",
                "apikey",
                "deviceid",
                "playsessionid",
                "transcodereasons",
                "tag",
                "audiosamplerate",
                "maxframerate",
                "h264-audiochannels",
                "aac-audiochannels",
                "aac-profile",
                "enableaudiovbrencoding",
                "requireavc",
                "starttimeticks",
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

        /** [INSIGNIA_LIKE] that also decodes HEVC, as a Fire TV Stick does. */
        val HEVC_DEVICE =
            INSIGNIA_LIKE.copy(
                videoCodecs =
                    INSIGNIA_LIKE.videoCodecs +
                        VideoCodecSupport("hevc", setOf("main", "main 10"), 153, 3840, 2160, 10, emptySet()),
            )

        /** Where a segment's first box names its type, and the types an fMP4 segment may start with. */
        const val BOX_TYPE_AT = 4
        const val BOX_TYPE_LENGTH = 4
        val FMP4_BOXES = setOf("styp", "moof", "ftyp")

        const val LOUD_ABOVE_DB = -30.0
        val MEAN_VOLUME = Regex("""mean_volume: (-?[0-9.]+) dB""")

        /**
         * What the server states about a video it copies: the clip's own level and profile (these clips are within the
         * limits, so the server names what the file has), where for a re-encoded video it states the limit (that case
         * is the limits case, which compares both).
         */
        val COPIED_VIDEO_FACTS = setOf("h264-level", "h264-profile")

        fun requestFor(
            capabilities: DeviceCapabilities = INSIGNIA_LIKE,
            start: Duration = Duration.ZERO,
            audioIndex: Int? = null,
            subtitleIndex: Int? = null,
            forceTranscode: Boolean = false,
        ) = PlaybackRequest(capabilities, start, audioIndex, subtitleIndex, forceTranscode)
    }
}

/** The playback suite against a server on its own port, with the format clips seeded. */
class DirectPlaybackContractTest : PlaybackContractSuite() {
    override fun startServer(image: String): JellyfinServer = JellyfinServer.direct(image, formats = true)
}
