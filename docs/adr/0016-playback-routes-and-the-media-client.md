# ADR-0016: Playback routes and the media client

- **Status:** Accepted
- **Date:** 2026-10-21
- **Decided by:** a technical decision under the owner's standing instruction, from spec 13 (video player) and ADR-0005's rule that no server-supplied URL or path enters the app (R2); the Insignia decision is the owner's (Phil, 2026-10-20)
- **Revisit:** if a Jellyfin release changes the `/Videos/{id}/master.m3u8` query contract (the drift test below fails), if Media3 needs a request the client can't tag, if the Insignia measurements argue for a lower gate, or if either contract case named in decision 8 starts agreeing with the client (see there)
- **Informs:** spec 13 (the player), ADR-0005 (Jellyfin client), ADR-0014 (cleartext policy)

## Context

The player needs three things from the server: the sources and tracks of an item (`PlaybackInfo`), a stream URL for the
mode it picked, and a way to stop an encode it started. Jellyfin offers a ready-made `TranscodingUrl` and
`DeliveryUrl` in the `PlaybackInfo` answer. Both are server-supplied strings that may carry `api_key=<token>` and paths
on the server's disk, and the transcoding URL carries limits chosen by the server, not by this device. ADR-0005 already
forbids a server-supplied URL or path from entering adapter memory, and a `Media3` data source is a long-lived consumer
of whatever URL it is given.

## Decision

1. **URLs are built from IDs, with our own limits (R2).** The adapter never declares `TranscodingUrl`, `DirectStreamUrl`,
   `DeliveryUrl` or `Path` in a DTO, so they cannot be passed on. `StreamUrls.hls` builds
   `{base}/Videos/{item}/master.m3u8` itself, with the device's codecs, level, profile, bit depth, size, audio channels
   and bitrate from its capability probe; `subtitle` builds `.../Subtitles/{index}/0/Stream.vtt` for a text stream the
   server would deliver separately. The base address keeps its subpath. Item and source IDs are added as single path
   segments, and an ID of `.` or `..` is refused. **The URLs we build carry no credential**, in the query or as
   userinfo. The playlists Jellyfin serves behind them are another matter: the variant and segment URLs inside a
   playlist may carry `api_key=<token>`. So a URI the player holds is treated as sensitive: **no player URI is ever
   logged or reported** (not in a log line, an analytics event, a crash report or an error message; PR B and C of
   spec 13 must honour this), and the media client strips `api_key` before sending (decision 3).
2. **A drift test is the guard.** Building our own URL means we depend on the query contract of `master.m3u8`. The
   contract suite (against Jellyfin 12.1 and 12.2, spec 13 task 3) compares the parameters we send with the server's
   own `TranscodingUrl` for the same request, and fails when the server starts using one we do not send or sends a
   different value. It runs for an over-limit video, a burned-in subtitle, an AC-3 passthrough request and a
   preferred audio track that is not the file's first. The server's URL is read there only, never in the app. The
   parameters the comparison leaves out, and why, are listed in the KDoc of `IGNORED_PARAMETERS` in
   `PlaybackContractSuite` (the credential, the device and play-session identity, and facts the server takes from the
   source file, not from the device's limits); each case that needs a further exception says so where it makes it.
3. **The `Authorization` header goes to one origin and base path, for a cleared session, on a plain read.**
   `mediaCallFactory` resolves the session for every request and tags the request only when (a) its method is `GET` or
   `HEAD`, (b) its scheme, host and port equal the session address and its path is the address's base path or below it
   and holds no encoded separator (`%2f`, `%5c`, any case), and (c) the session passes the adapter's own checks
   ([`AuthorizedCall.isCleared`](../../data/jellyfin/src/main/kotlin/tv/stationbreak/data/jellyfin/AuthorizedCall.kt)):
   the cleartext gate of ADR-0014 and the token binding. A session not yet bound is simply not cleared, because a media
   call must not wait on a probe. Any `Authorization` header the caller supplied is removed, and every `api_key` /
   `ApiKey` query parameter (any case) is removed from every request, to the session's server or any other. The client
   is a media variant of the credentialed one: pinned trust, the same interceptors, no redirects (so a redirect
   cannot carry the header elsewhere), but no call timeout and a 30 s read timeout, because a stream's call lasts as
   long as the player plays it.
4. **One fallback, for a failure or a missed landing, never for an expired session.** The player starts with the
   cheapest route the server allows (direct play, then direct stream via HLS copy). There are two reasons to fall back,
   and either one falls back once, to a full transcode that starts at the requested time (`forceTranscode`; the player seeks to the
   start), then reports what happened. It never loops:
   - **The load failed** (a player error on a route that is not already a transcode).
   - **The landing missed** (Ruling R21, the spec 13 design's "Seek check and fallback"). When playing starts, the
     position is compared with the requested start; more than **2 s off** (either way) on direct play or direct stream
     is a miss. The tolerance is a parameter of `TuneController` with a 2 s default, so it is a configurable default,
     not a constant. The transcode is judged with the same tolerance; if it misses too there is no second retry, and
     the result is `Played` with the position error set, which the playback matrix records as a failed row.

   A **timeout** (nothing played within 20 s) on direct play does **not** fall back: nothing loaded, so a transcode
   would only add another 20 s wait to a stream that is not answering. It is a `Failed(TIMEOUT)` result. An HTTP 401 or
   403 on a media load, or a session error from the server, means the session has expired: the player does **not**
   fall back to a transcode (it would fail the same way) and the tune ends as `SessionExpired`, the case the app
   routes to the same handling as an `Unauthorized` API answer. The app branches on the `TuneResult` case, never on a
   message; its diagnostic is a stable name (a Media3 error code name, a written-out server error name, or
   "Unexpected"), never a class name or a message. A tune that ends with anything but `Played` has already stopped the
   player and its encode, so a caller need not call `stop()` after it.
5. **Every encode we start is stopped.** `stopEncoding` sends `DELETE /Videos/ActiveEncodings?DeviceId=&PlaySessionId=`
   through `AuthorizedCall` on a fallback, a stop and a release. A 404 (already gone) is success.
6. **Default tracks are the server's, and the HLS URL carries them.** The request body carries `UserId`, so Jellyfin
   applies that user's audio and subtitle preferences (including their own "play default audio track" setting; the
   client accepts the result). Explicit indices are sent only when a caller asks (a test override or a person's
   choice). The HLS URL is built from the track the server resolved: the audio index asked for, else the source's
   default audio index from the `PlaybackInfo` answer. A URL that names no audio track plays the file's first track,
   not the user's preference (contract test, 12.1: `theTranscodePlaysTheTrackTheServerResolved`). The subtitle choice
   lives in one place for HLS, the burn-in index.
7. **Audio and subtitles follow the sink.** Audio codecs the HDMI sink reports are passed through; everything else is
   transcoded to AAC with a channel limit. Text subtitles are delivered in the stream or as WebVTT; picture subtitles
   (PGS, DVD, VobSub) are burned in when selected. This is what the client asks the server for in its device profile
   and its HLS URL. It is not a promise about what the server reports as playable: see decision 8.
8. **The client's resolver overrules the server's `SupportsDirectPlay` in two cases.** The server's answer is not
   always consistent with the profile it was sent, so the resolver does not take it on trust:
   - **The chosen audio codec** is in neither the device's decoders nor its passthrough set, or the chosen track has
     more channels than the device's maximum. The route falls to HLS with AAC, even where the server says the file
     direct plays. The contract case `dtsIsDirectPlayedForAnAccountThatPlaysTheDefaultTrack` pins the server's real
     answer (DTS reported as direct playable for an account that plays the default track, on 12.1 and 12.2).
   - **The subtitle delivery is `BURNED_IN`.** The route is a transcode with the subtitle burned in, even where the
     server says the file direct plays (it does for a picture subtitle selected by an account that always shows
     subtitles). The contract case `burnedInImageSubtitleTranscodes` pins the server's answer.
   Both cases are the triggers to revisit: if a server version starts reporting these files as not direct playable, the
   case fails and the overrule can be dropped (or kept as a guard).
9. **The media client is wired by the app, as a Media3 data source.** `mediaCallFactory` is an extension of the
   Jellyfin adapter, so it is adapter-specific. The app is its only consumer: it wraps the media OkHttp client in
   `media3-datasource-okhttp` and injects the resulting `OkHttpDataSource.Factory` into `core:playback` as a plain
   Media3 `DataSource.Factory`. `core:playback` has no HTTP client of its own and never depends on `data:jellyfin` (the
   architecture rules allow it `core:model` and `core:domain` only). The factory goes **directly** to
   `DefaultMediaSourceFactory`, and is never wrapped in `DefaultDataSource.Factory`: that wrapper opens `file://`,
   `content://`, `asset://` and other schemes, so a server playlist could name a local file for the player to read.
   With the bare HTTP factory a playlist line that is not `http(s)` fails to load. **Media3's own logging is off** in
   every build (`Log.setLogLevel(LOG_LEVEL_OFF)`, set before the player is built), because it logs player errors with
   their full cause chains, which can quote a playlist line carrying `api_key` (decision 1). Our own events carry the
   diagnostics instead: the stable error code name and the HTTP status.
10. **Measure, don't grade, on the Insignia.** The Insignia is slow by nature. Its playback numbers are recorded, not
    scored, and the Phase 1 gate stays as it was (Phil, 2026-10-20). Wasted work is still removed.

## Update 2026-10-22: HLS starts by player position

Jellyfin 12.2 rejects `StartTimeTicks` on segment requests (HTTP 400, "StartTimeTicks is not allowed"), and the server
copies the `master.m3u8` query string into the variant playlist and segment URLs. So the HLS URL carries no
`StartTimeTicks`: the playlist covers the whole runtime from 0, the player starts at the requested position (as in
direct play), and the server encodes from the segment the player asks for. `PlayerSource.serverOffset` is gone. The
`PlaybackInfo` request body still carries `StartTimeTicks`; that endpoint accepts it. The contract suite now fetches the
master, the variant and the segments for a non-zero start, for a transcode and for a direct stream.

- **What guards what.** The landing check (`SeekCheck`) guards direct play. For HLS it only confirms the player's own
  playlist clock; HLS placement (that the segment at the player's position holds the picture for that time) is guarded
  by the contract suite, which reads the first timestamp of the covering segment with `ffprobe` and requires it within
  2 s of where the playlist puts it.
- **The cost.** Each tune to an offset makes the server start an ffmpeg job at segment N rather than at 0.
- **The dependency.** The server's variant playlist is built from the item's `RunTimeTicks`. An item with no runtime
  has no usable playlist. The scheduler never airs one: a program needs `minRuntimeMs <= runtime <= epochMs`, and the
  minimum is at least 1 s (`EpochPool`, `SchedulerSettings.isAirable`), so no resolver guard is added.
- **Why segment URLs are not rewritten.** Stripping `StartTimeTicks` from the server's segment URLs would mean the
  client rewriting playlists that are the server's own; we build the URL and leave the playlists alone.
- **Revisit** if Jellyfin accepts `StartTimeTicks` on segments again (server-side start would save the seek), or if the
  contract suite's placement check ever shows HLS placement drifting.

## Update 2026-10-22: embedded text subtitles on HLS

Found in the whole-branch review of spec 13.

- **An embedded text subtitle on an HLS route is delivered as WebVTT (R31).** When decision 8 moves a file to HLS, the
  server's own choice for its text subtitle is still `IN_STREAM`, and the playlist carries no text subtitle, so none
  showed while `chosenSubtitle` reported one. On an HLS route (direct stream or transcode) the resolver now treats an
  `IN_STREAM` text subtitle as `SEPARATE`: the player fetches `Subtitles/{index}/0/Stream.vtt`, which the server
  extracts from the file, and `chosenSubtitle` reports `SEPARATE`. The URL is built from IDs with no token, like the
  other, and fetched through the media client's auth rule. Image subtitles are unchanged (burned in). On direct play the
  subtitle stays in the file. The contract case `anEmbeddedSrtOfAFileWithUndecodableAudioServesAsVtt` proves the
  endpoint serves cues for an embedded SRT.
- **HEVC copy is deferred.** HEVC copy (design goal 4 for HEVC sources) is deferred to #351. Until then, HEVC with
  audio the device can't play is a full transcode. The route oracle still says DIRECT_STREAM for it (the goal).
  Until #351 the server re-encodes HEVC to H.264 on our URL. If the server reports `SupportsDirectStream`, the
  resolver and the oracle both label the matrix's `hevc-dts` row DIRECT_STREAM while the video is re-encoded: the row
  is mislabelled, not failed. The contract case `recordsWhatTheServerDoesForHevcWithUndecodableAudio` records which
  happens (the server's flags and the playlist's `CODECS`) and asserts nothing about it.
- **Also corrected:** a `BURNED_IN` subtitle now always resolves to a TRANSCODE (decision 8), not a direct stream.
  DVB and XSUB subtitles are picture subtitles, so they burn in too.
- **The oracle models the device, not the server's account or track rules (R35).** A real-section row with an audio
  override is graded on the chosen track, so it can fail on the server's own track rule, for example the account's
  "play default audio track" setting. Such a failure is a finding to explain, not a grading bug.

## Update 2026-10-23: the player's lifecycle seam

Found in the whole-branch review of spec 13 (#348), before F1.3 puts the player on a screen.

- **No Media3 type reaches a host.** `Media3VideoPlayer` took a Media3 `DataSource.Factory`, and `core:playback` keeps
  Media3 as `implementation`, so a feature module would have needed Media3 itself. `core:playback` now has
  `VideoPlayerFactory` (`create(context, clock): VideoPlayer`) and its Media3 implementation `Media3VideoPlayerFactory`.
  `app` binds the factory in `di/PlaybackModule` over the `@MediaDataSource` data source; a screen injects
  `VideoPlayerFactory`. The decision-9 rule is unchanged: the data source is still the OkHttp one, never wrapped.
- **Teardown.** `TuneController.close(session)` does what `stop` does but runs under `NonCancellable`, so a host whose
  scope is cancelled after `Played` still stops the player and the HLS encode (decision 5). It is final: a `tune` after
  it returns `Stopped` without playing, including one that was created but not yet in flight when `close` ran. The
  order is close, then release: the host waits for `close` and only then calls `VideoPlayer.release()`, and must not
  release the player from a lifecycle callback that can run before the tune's teardown has finished (the benchmark
  activity releases in the coroutine that ran the tune, after `close`).
- **A cancel anywhere in a tune cleans up.** `run()` now catches `CancellationException` around the whole tune, not
  only around the wait for Playing. A cancel from outside during the fallback's resolve used to leave the abandoned
  first attempt's player running (its encode was already stopped before the fallback, so no encode leaked); it now
  stops the player. A tune cancelled by a newer one stops only its encode and leaves the player running, because the
  new play replaces the item and a fast flip must not blank the screen. `VideoPlayer.stop` is a no-op when called
  twice, and implementations must keep it so.

## Update 2026-10-23: pre-resolve and the end of a play

Spec 14 (offset playback) cuts from one block to the next on a clock, so the server round trip has to come out of the
cut and a screen has to learn when a play is over.

- **Pre-resolve.** `TuneController.preResolve(session, item, start)` resolves ahead of time with the server's default
  tracks and returns an opaque `Prepared` (or null on a failure, or when closed). `tune(session, prepared, override)`
  plays it with no resolve call; if that play breaks, the normal fallback applies and re-resolves. A non-null
  `override` cannot be honoured by a default-track handle, so it discards the handle and tunes the ordinary way. A
  resolve of a transcode or direct stream holds a server encode, so callers pre-resolve shortly before the cut
  (`preResolveLead`, 45 s), not early.
- **No leaked encode.** The controller tracks every outstanding handle. `discard` ends its encode (once; a handle
  already played is untouched), `close` discards all of them, and a `preResolve` that finishes after `close` discards
  its own handle. **A handle is gone once it is passed to `tune`, or after `discard`; `discard` on a consumed handle is
  a no-op.** `tune(prepared)` takes the handle on entry, and when the tune ends without having started its play
  (cancelled while waiting for the lock, superseded, stopped, closed, or an override that sends it the ordinary way)
  the handle's encode is ended before the call returns, so a `Superseded` or `Stopped` result never leaves the caller
  guessing whether it still owns the handle. A prepared tune checks for a cancel again after it records its stream and
  before it touches the player.
- **The end of a play.** `PlayerEvent.Ended` is sent by `Media3VideoPlayer` on `STATE_ENDED`. `TuneResult.Played.end` is
  a cold flow of that play's generation that completes after the first `PlaybackEnd`: `Ended`, `Failed(errorCode)` for
  a `Failed` event, or `Failed("StallTimeout")` for a `Stalled` that no `Playing` follows within the controller's 20 s
  playing timeout. The controller watches each play from before it starts, in a scope it owns, and keeps the result, so a
  collector that comes late still gets it. The watcher is cancelled, and `end` completes
  with no value, as soon as a newer tune (either overload) takes the tune lock, and by `stop` and `close`: the old
  picture keeps playing until the new one is ready, but its end is no longer reported. A controller that made its own
  watch scope cancels it in `close`; a scope passed in is left to its owner.
- **Not done here.** No Media3 playlist queueing: the block-boundary cut is a prepared `tune`, and its gap is measured
  as resolve time plus play-to-first-frame time.

## Consequences

- A newer Jellyfin that renames a `master.m3u8` parameter fails the drift test before it fails a viewer.
- The media client cannot authorize a request whose session was never signed in or resumed by this adapter in this
  run. The player is only reachable from a session that was, so this costs nothing in practice.
- `newCall` is synchronous, so the first tagged request reads the device ID with `runBlocking`; the provider caches it,
  and Media3 calls from its loader thread.
- Anything that handles a player URI (the data source, the error mapper, the diagnostics) must treat it as a secret
  until the media client has stripped it.
