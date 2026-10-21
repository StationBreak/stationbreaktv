# ADR-0016: Playback routes and the media client

- **Status:** Accepted
- **Date:** 2026-10-21
- **Decided by:** a technical decision under the owner's standing instruction, from spec 13 (video player) and ADR-0005's rule that no server-supplied URL or path enters the app (R2); the Insignia decision is the owner's (Phil, 2026-10-20)
- **Revisit:** if a Jellyfin release changes the `/Videos/{id}/master.m3u8` query contract (the drift test below fails), if Media3 needs a request the client can't tag, or if the Insignia measurements argue for a lower gate
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
   server would deliver separately. The base address keeps its subpath. No URL has a credential, in the query or as
   userinfo.
2. **A drift test is the guard.** Building our own URL means we depend on the query contract of `master.m3u8`. The
   contract suite (against Jellyfin 12.1 and 12.2, spec 13 task 3) compares the parameters we send with the server's
   own `TranscodingUrl` for the same request, and fails when the server starts using one we do not send. The
   server's URL is read there only, never in the app.
3. **The `Authorization` header goes to one origin and base path, for a cleared session.** `mediaCallFactory` resolves
   the session for every request and tags the request only when (a) its scheme, host and port equal the session
   address and its path is the address's base path or below it, and (b) the session passes the adapter's own checks
   ([`AuthorizedCall.isCleared`](../../data/jellyfin/src/main/kotlin/tv/stationbreak/data/jellyfin/AuthorizedCall.kt)):
   the cleartext gate of ADR-0014 and the token binding. A session not yet bound is simply not cleared, because a media
   call must not wait on a probe. Any `Authorization` header the caller supplied is removed. The client is the
   credentialed one (pinned trust, no redirects), so a redirect cannot carry the header elsewhere.
4. **One fallback.** The player starts with the cheapest route the server allows (direct play, then direct stream via
   HLS copy). If that fails it falls back once, to a full transcode (`forceTranscode`), then reports the failure. It
   never loops.
5. **Every encode we start is stopped.** `stopEncoding` sends `DELETE /Videos/ActiveEncodings?DeviceId=&PlaySessionId=`
   through `AuthorizedCall` on a fallback, a stop and a release. A 404 (already gone) is success.
6. **Default tracks are the server's.** The request body carries `UserId`, so Jellyfin applies that user's audio and
   subtitle preferences. Explicit indices are sent only when a caller asks (a test override or a person's choice).
7. **Audio and subtitles follow the sink.** Audio codecs the HDMI sink reports are passed through; everything else is
   transcoded to AAC with a channel limit. Text subtitles are delivered in the stream or as WebVTT; picture subtitles
   (PGS, DVD, VobSub) are burned in when selected.
8. **Measure, don't grade, on the Insignia.** The Insignia is slow by nature. Its playback numbers are recorded, not
   scored, and the Phase 1 gate stays as it was (Phil, 2026-10-20). Wasted work is still removed.

## Consequences

- A newer Jellyfin that renames a `master.m3u8` parameter fails the drift test before it fails a viewer.
- The media client cannot authorize a request whose session was never signed in or resumed by this adapter in this
  run. The player is only reachable from a session that was, so this costs nothing in practice.
- `newCall` is synchronous, so the first tagged request reads the device ID with `runBlocking`; the provider caches it,
  and Media3 calls from its loader thread.
