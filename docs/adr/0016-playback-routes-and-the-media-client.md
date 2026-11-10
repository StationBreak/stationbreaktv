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
6. **Default tracks are the server's, and the HLS URL carries them** (a viewer's own choice: see the update of 2026-11-09). The request body carries `UserId`, so Jellyfin
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
    *Amended 2026-10-24:* the Phase 1 gate itself changed to a two-tier gate, a 2 s bar on the Firestick 4K and a
    usable-floor bar on the Insignia (see the Phase 1 gate in [EPICS.md](../EPICS.md) and ADR-0007's amendment).

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
  contract suite's placement check ever shows HLS placement drifting. `TuneController`'s `startingAt` (a handle played
  from a later start, spec 15) is correct only while the URL has no start. Two things hold that: `StreamUrls.hls` takes
  no start, and the contract suite asserts the built URL has no `StartTimeTicks`
  (`assertSegmentsServeAt`); the day a URL carries a start, `startingAt` must re-resolve instead.

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
  pre-resolve is one `PlaybackInfo` call and the URL builders: it starts no encode (the server starts one with the
  first playlist or segment request, and the contract case `aPlaybackInfoAloneStartsNoEncode` checks that
  `PlaybackInfo` alone leaves none, with a stream request as its positive control), so a held handle costs nothing on
  the server. It does go stale when its block ends, so callers pre-resolve shortly before the cut (`preResolveLead`,
  45 s), or keep a handle only while its block is on. **Revisit:** if that case fails on a supported Jellyfin, a held
  handle holds an encode: `Neighbours` must stop holding handles (resolve at commit instead) and the just-in-time
  `preResolveLead` rule applies again. `tune(session, prepared, override,
  start)` may replace the handle's start (spec 15, look-ahead): the played copy has the new start, which is sound
  because the HLS URL carries no start.
- **No leaked encode.** The controller tracks every outstanding handle. `discard` ends any encode its play started (once; a handle
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

## Update 2026-10-24: the encode stop is issued in the background

Decision 5 still holds (every encode we start is stopped), but the `stopEncoding` call no longer holds up the next tune
(issue #392): a flip away from a transcode waited on the server's round trips under the controller lock. `player.stop()`
stays synchronous. The server call runs in the background, outside the caller's cancellation, and is tracked. Each
encode is still stopped once and a failed stop is still ignored.

- Encodes may now overlap briefly on the server: the old stop is in flight while the new resolve or transcode starts.
  This happens during rapid flips, and for the same item too (a fallback to a forced transcode, or a flip away and
  straight back). The server stops encodes by play session id, so the new encode is never the one stopped. The one
  exception is the track-override re-tune of a prepared handle, which
  still waits for the stop of the handle it replaces, so the server never runs two encodes of that item at once.
- `close` awaits the stops issued before it returns, so the order close, then release, keeps its promise: no encode is
  left running when the screen closes. A stop issued after `close` returns (a tune called after it) is not awaited, so
  a host must call `close` only after its tune callers have finished (the live screen's view model closes after the
  live channel's run ends).
- The stops run on a separate scope the controller owns. It is never cancelled and borrows only the dispatcher of the
  watch scope (Dispatchers.Default when the controller makes its own scope).

## Update 2026-10-30: the choice among an item's versions

An item can have several media sources (versions), and the player used to take the first (issues #496, #499).
`PlaybackResolver` now resolves every source in memory (no extra server calls) and chooses one.

- **Order.** A version the device can direct-play comes first. Among those, the largest picture within the decoder's
  width and height limits wins. If none qualifies, Jellyfin's order applies (the first). A tie on size also keeps
  Jellyfin's order.
- **Unknown size.** A version whose size is not reported counts as fitting, but loses to one with a known size.
- **The chosen version stays chosen.** The stream URL, the subtitle URL and playback reporting all come from that one
  source. The transcode fallback passes `prefer` (the current `media.id`), which narrows every route to that source, so
  a fallback transcodes the version that just failed, not the first (which may be the heaviest). A prepared play falls
  back the same way. A future `TrackOverride` re-tune (track switching, #497) must pass `prefer` too, so the override's
  stream indices keep meaning the same tracks; today only the benchmark builds an override.
- **What `fits` checks.** Only the video's width and height against its codec's decoder limits. Level, profile and bit
  depth are left to the server's `SupportsDirectPlay` for our `DeviceProfile`. **Revisit:** if versions that pass `fits`
  keep failing on a device, `fits` needs the codec level or bit depth.
- **The schedule is unchanged.** It keeps the item's `RunTimeTicks`; ADR-0017's cut and catch-up rules cover versions
  of different lengths.
- **Auth rules are unchanged.** The token goes only in the Authorization header, and `TranscodingUrl` is never used.
- **Not done here.** When no direct-play version fits the decoder, the first direct-play one is still tried and may
  cost an attempt before the transcode fallback. Preferring a transcode of a fitting version needs a measurement on
  older hardware first (#499).

## Update 2026-11-09: a viewer's track and version choices (design doc B2.5, B2.6)

Decision 6 says default tracks are the server's and explicit indices are sent only when a caller asks "a test override
or a person's choice". The person's choice now exists (#497, #630; `docs/design/live-banner-and-tracks.md`). Decision 6
stands for every tune that carries no choice, and these rules govern the ones that do.

- **A choice is one value: `TrackChoice(source, tracks)`.** An override's indices are one source's stream indices, so
  the tracks never travel without their source. `TuneController.tune` and `TunePort.tune` take a `TrackChoice?`; a
  version change alone is a `TrackChoice` with no tracks. If the chosen source is **not in the server's answer**, the
  tracks are dropped and the item is resolved again as if nothing was chosen (the indices reach the server only on the
  first call), so they are never applied to another version. A prepared default-track handle serves a tune only when
  there is no choice of tracks and no other version chosen; otherwise it is discarded (its encode stopped once, before
  the fresh resolve).
- **The subtitle choice has three states** (`SubtitleChoice`): `Default` (the server applies the user's preferences:
  no `SubtitleStreamIndex` is sent, and the source's default subtitle plays), `Off` (`SubtitleStreamIndex=-1`, no
  subtitle plays) and `Track(index)`. No null means two things. Before this, a null subtitle in a `TrackOverride` was
  read as "off" by the resolver and as "the server's default" by the request.
- **What carries to the next programme is the language, not the track.** The audio language and the subtitle choice
  (off, or on in a language, with whether the chosen track was SDH) are kept per signed-in user on the device
  (`TrackChoiceStore`, a file under `noBackupFilesDir`), across restarts. They are **never written back to Jellyfin**:
  the server's own preferences are the viewer's other apps' too, and decision 6's "the server applies the user's
  preferences" is unchanged for a viewer who has chosen nothing. A version is **stored now** per item (item id to media
  source id, the most recent 200) and is **applied to the tune in the #630 PR** (the version picker), and then only
  while the item still offers that source; nothing in this change reads it back.
- **The carry rules run in core:playback, after resolving.** The indices of a carried language are only known from the
  server's answer, so `TuneController.tune` and `preResolve` take the carried `TrackPreferences` and
  `PlaybackResolver` picks the indices from the answer (`TrackCarry`, in core:domain, is the pure rule). The first
  `PlaybackInfo` call is made with the server's defaults; **only when the carried tracks differ from what those gave**
  is a second call made asking for them (the route and delivery depend on the tracks, so the first answer's flags
  cannot be trusted for other tracks). A programme the server's defaults already serve, or one with no track in the
  language, costs one call; a failed second call keeps the first answer. A viewer's own choice of tracks beats the
  preferences; a version choice alone still gets the carried tracks on that version. The live screen's tuner supplies
  the preferences of the session's account to every tune and pre-resolve. A prepared handle records the preferences
  it was resolved with and serves a tune only when the tune's preferences are the same, so a preference changed while
  the handle was held (mid-programme) discards it as stale; the same preferences ride along through a fallback after
  the handle's play, whose fresh resolve must still carry the language.
- **Carry-over rules.** The carried audio language picks the programme's track in it that is default-flagged, else the
  first. **Commentary, forced and audio-description tracks never carry** (read from the server's forced flag and the
  track's titles). A carried subtitle language picks a **text** subtitle only; it uses a picture subtitle only when
  that is also the server's own default, so a preference never turns direct play into a transcode (decision 8).
  **SDH carries** (owner decision): a subtitle preference is a language plus whether the chosen track was
  hearing-impaired. With the flag set, the next programme prefers an SDH track in that language (the server's own
  `IsHearingImpaired`, else the track's titles) and falls back to a plain track; without it, a plain track is preferred
  and an SDH track in the language is the fallback (commentary, forced and audio-description tracks are never a
  fallback). **Subtitles off does not remove a forced track** (owner decision): when the source's default subtitle is
  forced, the server's default stands instead of Off, because a forced track carries only the foreign-language lines
  that a viewer who turned subtitles off still needs. No match leaves the server's default, silently, and the banner's
  stream line says what plays. Languages are compared after folding two- and three-letter and bibliographic and
  terminologic spellings (`ger` and `deu`).
- **Whether a change interrupts the picture is one function, and a screen never calls it.** `switchCost(playing,
  target, capabilities)` is internal to core:playback. `ResolvedPlayback.facts()` puts its answer on every audio
  track, subtitle track and version in `StreamFacts` (and `offCost` for subtitles off), so the picker's "restarts
  picture" label and what then happens come from one place. Only direct play can switch while it plays (the player
  holds the file's tracks): audio the device decodes, subtitles off, an embedded text subtitle, and a separate file only
  if it was side-loaded at the start are instant. A picture subtitle, audio the device cannot decode (the route moves
  to HLS, decision 8), any change on a direct stream or transcode, and every version change re-tune. Text subtitles
  while transcoding stay a re-tune until the PR 4 spike measures otherwise.
- **The sitting holds the viewer's choice for the programme on air.** A retune (a retry after a failure, a re-anchor of
  a kept place) reads it from the sitting when it fires, not when it is built, so a retry after an instant switch keeps
  the viewer's choice. A choice is keyed by its block and by the item playing in it, and is not applied to another
  programme, or to a different item of the same block (a stand-in piece's tracks are not the choice's).
- **The play's facts reach the screen.** `TuneResult.Played` becomes a `StreamFacts` (route, chosen source id, chosen
  audio and subtitle indices, and a summary of every source's video, audio and subtitle tracks) on `TuneOutcome.Playing`
  and the `Banner`: display text and numbers only, with no URL, path or player handle, so the decision-1 secrecy rule
  is untouched. `MediaStreamInfo` now carries the server's `Title`, `DisplayTitle` and `IsHearingImpaired` (display text
  and a flag, never logged), which is how Commentary and SDH are told apart.
- **Not done here.** The instant switch in `Media3VideoPlayer`, the re-tune fallback and the pickers are PR 4 (the
  update of 2026-11-10 below). Following Android's system caption setting is an open question for the owner (design doc, B2.2) and is not
  implemented. The `SubtitleStreamIndex=-1` request for "off" is the Jellyfin convention; the contract suite should
  exercise it on a live server before PR 4 relies on it.

## Update 2026-11-10: choosing audio and subtitles while watching (design doc B2.4, B2.7, B2.8; #497)

The pickers (PR 4) make the choices decision 6 and the 2026-11-09 update prepared for. Version choice is #630.

- **An instant switch is the player's, on its looper, and verified.** `VideoPlayer.switchTracks(generation, switch)`
  (default: cannot) puts a `TrackSwitch` (an audio `TrackPick`, and keep / off / a text `TrackPick` for subtitles) onto
  the player's track selection the way `applyPicks` does at the start. An audio pick takes only when the track at that
  position is the one the server named (language and codec, as `lastPickVerified`); otherwise nothing changes and the
  caller re-tunes. The picks come from `StreamFacts` (`AudioFacts.pick`, `SubtitleFacts.pick`, from the stream's
  position among its kind), so a screen never learns the file's layout. `LivePeriod`'s guarded player must override it
  (its guard test fails otherwise): without it every in-place switch would silently be a re-tune.
- **A viewer's switch enters the sitting as a transport op** (`TransportOp.Switch`), like Pause: it wakes the live
  hold, and `BehindPlay` makes it, so a switch while paused or behind works the same. `switchTo` asks the facts' cost:
  `Instant` and the control takes it, the play carries on with its facts updated; anything else is a **retune at the
  place under the snow** (`Retune.switching`: banner `SWITCHING AUDIO` / `SWITCHING SUBTITLES`). A retune made for a
  switch does **not** use the block's one retry (that is for failures). If it does not play, the previous choice is tuned
  at the same place and the one banner that follows says "Couldn't switch" in the stream line's place; if that does not
  play either, the item is dead for the block as for any failed retry. The retune carries the whole choice: changing
  the audio keeps the subtitles as they were (a subtitle the viewer turned off stays off), and changing the subtitles
  keeps the audio.
- **A choice that took is the programme's and leaves its language behind.** The sitting's `OnAirChoice` is set (so a
  stall retry of the live play, which now reads it as the behind play's retune already did, keeps it) and the adapter
  keeps the language in the `TrackChoiceStore` through `TunePort.remember`: the audio language, or subtitles off, or
  subtitles on in a language with the SDH bit. A commentary, forced or audio-description track plays but leaves nothing
  for the next programme (they never carry). The Subtitles picker's last row, **Server default**, is the way back: it
  always re-tunes with `SubtitleChoice.Default` and clears the stored subtitle choice (`saveSubtitles(account, null)`),
  so the next programme is the server's again. Nothing is written to Jellyfin.
- **The second `PlaybackInfo` call is timed.** `ResolvedPlayback.carriedMs` is how long the call a carried preference
  makes took; it rides to the tune log line as ` carriedMs=<n>` (a tune with no second call has no field). The cost
  on a TV has not been measured yet.
- **Not done here.** The on-TV spike of whether a text subtitle can switch without a re-tune while transcoding has
  **not been run**: those switches stay `Retune` (decision of `switchCost`), and this update records no result for it.
  Version choice and applying the remembered version are #630. Following Android's system caption setting (B2.2) is
  still the owner's open question and is not implemented.
