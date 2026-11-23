# Amazon Appstore listing (draft)

Status: **Draft for the owner (#33).** Nothing here has been entered in the Developer Console or submitted. The
display title and any tagline are branding decisions and are not in this file ([#118](https://github.com/StationBreak/stationbreaktv/issues/118)).
The listing images are in [`store/`](../../store/) ([#572](https://github.com/StationBreak/stationbreaktv/issues/572)).
Answers marked **Owner** are recommendations only.

Field names, limits and questionnaire categories are from Amazon's submission docs, read 2026-11-17:
[Step 2: store listing](https://developer.amazon.com/docs/app-submission/add-store-listing-information.html),
[Step 4: policy and compliance](https://developer.amazon.com/docs/app-submission/add-policy-and-compliance-details.html),
[privacy labels](https://developer.amazon.com/docs/app-submission/appstore-privacy-labels.html),
[categories](https://developer.amazon.com/docs/app-submission/categories.html). All fields are plain text: no HTML,
no emoji, no all caps.

What the copy describes is the free V1: the planner's lineup and up to 5 custom channels. Pro isn't for sale yet
([#36](https://github.com/StationBreak/stationbreaktv/issues/36)), so the listing doesn't mention it or any price.

## General information (Step 1)

| Field | Draft | Notes |
| --- | --- | --- |
| App title | StationBreak | The app's current label (`app_name`). **Owner** if it should differ |
| Category | Photos & Video → Video Player | Owner decision, 2026-11-18. The app ships no content of its own, and Amazon has moved at least one similar app here from TV Guides ([community thread](https://community.amazondeveloper.com/t/fire-tv-app-disappeared-from-search-results-after-required-category-change/29935)) |
| Customer support contact | support@stationbreak.tv | Owner decision, 2026-11-18 |

## Store listing (Step 2)

### Short description

Limit 1,200 characters. Fire TV shows only the first 200, so the point comes first. This draft is 357 characters; the first 200 end at "browse a program guide. B", so they carry the whole idea.

```text
Turn your own Jellyfin library into live TV. Your shows and films play on a schedule across themed channels, the way cable does: tune in to whatever is on, flip channels, and browse a program guide. Build up to 5 channels of your own. Tots channels show only titles rated for kids. Needs your own Jellyfin server, version 12.0 or later. No ads, no tracking.
```

### Long description

Limit 4,000 characters. Shown on the Appstore website and on Fire tablets, not on Fire TV. This draft is about 1,830
characters.

```text
StationBreak plays your own media library as live TV.

Instead of picking something to watch, you turn on the TV and something is already on. Your shows and films play on a schedule, across themed channels built from your library, chosen from the genres, ratings and other details your server already keeps. Tune in part-way through, flip to the next channel, or open the program guide to see what is on now and next.

What you can do:
- Watch built-in channels made from your own library, with a new schedule every day.
- Browse a program guide with what is on now and what is coming up.
- Pause, go back to the start of the show that is on, and skip ahead again.
- Choose audio tracks, subtitles and the version of a title while you watch.
- Even out loud and quiet programs with volume levelling.
- Build up to 5 channels of your own from libraries, collections, tags, genres, or shows and films you pick, played in shuffle, rotation or in order.
- Give kids their own channels. Tots channels show only titles made for kids and rated within the Tots limit; unrated titles are left out.

What you need:
- A Jellyfin media server, version 12.0 or later, with your own media on it. StationBreak has no content of its own.
- A Fire TV device running Fire OS 6 or later on the same network as your server, or able to reach it.

Private by design:
StationBreak talks only to your Jellyfin server. It has no ads, no analytics and no account with us. Your sign-in stays on the TV, encrypted. Crash reports are off unless you turn them on in Settings.

Works with your setup:
Connect by address, with or without https. Servers behind a reverse proxy at a path such as /jellyfin work. Self-signed certificates are supported: the app shows the certificate's fingerprint and asks before trusting it.

StationBreak is free and open source software under the GNU GPL. The source code is on GitHub.
```

### Product feature bullets

Amazon asks for three to five, one per line.

```text
Your own Jellyfin library, played as live TV channels with a program guide
Pause, go back to the start of the show, and skip ahead
Build up to 5 channels of your own; Tots channels show only titles rated for kids
Audio, subtitle and version choice, and volume levelling
No ads, no tracking: the app talks only to your server (crash reports are off unless you turn them on)
```

### Keywords

Optional, comma separated. Amazon rejects profanity and keywords that misuse other trademarks.

```text
live tv, channels, tv guide, program guide, jellyfin, media server, home media, cable, channel surfing, retro tv, schedule, kids channels
```

### Assets

The icons and the Fire TV app icon are rendered from `art/brand/` into [`store/`](../../store/) (#572).
The Fire TV background is the supplied PNG in that folder, with its SVG source beside it.
Screenshots are in [`screenshots/`](screenshots/). Image specs were re-read 2026-11-21 from this page (last
modified 2026-11-16).

| File | Amazon slot |
| --- | --- |
| `store/icon-114.png` | Small icon, 114×114 PNG |
| `store/icon-512.png` | Large icon, 512×512 PNG |
| `store/fire-tv-icon-1280x720.png` | Fire TV app icon, 1280×720 PNG, no transparency |
| `store/fire-tv-background-1920x1080.png` | Fire TV background, 1920×1080 PNG, no transparency, no text |
| `screenshots/*.png` | Fire TV screenshots, 1920×1080, 3 to 10 |

The optional 1024×500 promotional image is not in this folder. Screenshots
must not show personal information; these were taken against the demo server.

## Pricing and availability (Step 3)

| Field | Draft | Notes |
| --- | --- | --- |
| Price | Free | Owner lock 2026-11-04: the free listing ships first |
| In-app purchasing | None in V1 | Pro (#36) comes in a later update |
| Countries | United States, United Kingdom, Canada, Australia | Owner decision, 2026-11-18. Amazon defaults to all, so the rest are unticked. The app's strings are English only |

## Policy and compliance (Step 4)

### Content rating questionnaire

Amazon asks about the app's subject matter (violence, cartoon violence, drugs, nudity, sex, intolerance, profanity,
academic) and five additional items (personal information, advertisements, gambling, location, user-generated
content).

**The owner answers this questionnaire** (decision, 2026-11-18), truthfully, as a player of user-supplied
media. The facts it rests on:

- The app has no built-in content. Everything it plays comes from the viewer's own server, which the app neither
  supplies nor moderates.
- No advertisements and no purchases in V1.
- No account with us. The Jellyfin username and password go only to the viewer's own server, and only the server's
  token is kept, on the TV.
- No location permission (the merged manifest allows only INTERNET, WAKE_LOCK, ACCESS_NETWORK_STATE and
  RECEIVE_BOOT_COMPLETED; `BackupRulesTest`), and no user-to-user communication.
- The adult built-in channel is off, and it can't be switched on in the free app. A copy of it stays an adult
  channel, and copying it is Pro only. Copying its tags into a custom channel by hand is outside the app's control.
  The mature-content stance is on the docs site (`docs/guide/mature-content.md`).

### User data privacy

"Does your app collect user data or transfer user data to third parties?" Recommended for a build with no crash
report endpoint (`CRASH_REPORT_URL` empty, the default): **No. Owner.** For a build that has one, such as the Amazon Live
App Testing beta (#96, Q-PRIV-1): **Yes, diagnostics, only if the viewer opts in**, and no one else receives it.

Amazon's questionnaire is about user data the developer, its SDKs or its service providers receive. Without an
endpoint this app sends nothing to the developer or anyone else: no analytics, crash reporting or advertising SDK, and no
network call except to the server the viewer typed in. Data sent there is the viewer's own action, to their own server
(see the [privacy page](../guide/privacy.md)). With an endpoint, and only after the viewer turns on Settings, Send crash
reports, a crash report (the error's stack trace with web addresses, server names and addresses, tokens and keys taken
out, the app and Android version, and the TV's model and memory) goes to the developer's own server, and nowhere else.
Answer for the build you submit.

Even with No, give the privacy policy URL so the listing links it. The policy is hosted on stationbreak.tv (owner
decision, 2026-11-18), from the text in `docs/guide/privacy.md`: `https://stationbreak.tv/privacy/`, live once
GitHub Pages and the domain's DNS are set up. Check it loads before submitting.

Opt-in crash reporting is built (Q-PRIV-1, decided 2026-11-20), but a build only has it when `CRASH_REPORT_URL` was set
for that build. The tagged release workflow does not set it, so a tagged build answers No. If the store build ever
gets an endpoint, or any telemetry is added, the answer changes to Yes with Crash logs or Diagnostics for Analytics,
and the questionnaire must be updated before that release.

Amazon's [account and data deletion](https://developer.amazon.com/docs/app-submission/user-account-and-data-deletion.html)
rules are for apps that create accounts. This one doesn't: uninstalling removes everything the app keeps, and
revoking the TV's device in Jellyfin ends its sign-in.

## Release details (Step 5)

| Field | Draft | Notes |
| --- | --- | --- |
| APK | The `amazon` flavor release APK from the tagged release workflow | CONTRIBUTING.md, Cutting a release |
| Apply Amazon DRM | **No** | ADR-0003 §5: submit with no DRM |
| Device support | Fire TV devices only, Fire OS 6 or later | **Owner** to confirm in the console. The manifest marks leanback and touchscreen as not required, so the console will also list Fire tablets as compatible: untick them, the app is built for a remote |
| Export compliance | **Owner** | The app uses only the platform's standard TLS |

### Testing instructions

Amazon rejects an app its testers can't use, and this one does nothing without a server. The owner is setting up a
public demo server for Amazon's reviewers (decision, 2026-11-18), reachable from the internet, with a sign-in for them
and media licensed for that use, such as public-domain or Creative Commons films and cartoons.

**Never commit the demo server's address or sign-in to this repository**, in this file or anywhere else. They are
typed into the console's Testing instructions field at submission, where the placeholders below are.

Draft text, with the placeholders to fill in at submission:

```text
StationBreak is a client for Jellyfin, a self-hosted media server. It has no content of its own: it plays the viewer's library from their own server as scheduled live TV channels.

To test, connect to our test server:
1. On the first screen, enter this server address: <TEST SERVER ADDRESS>
2. Choose Test, then sign in with username <USERNAME> and password <PASSWORD>.
3. Choose the libraries, then wait for the guide to load. A channel starts playing.

The test server's media is public-domain or Creative Commons licensed.

Controls while watching: Up and Down change channels, Back opens the program guide, Select shows what is on, Play/Pause pauses, Menu goes back to the last channel. The app needs no touch input.
```

The controls line follows `feature/live/.../ui/LiveKeys.kt` and `OverlayKeys.kt`. Check it on a TV against the submitted build.

## Before submitting

Not part of this draft, tracked elsewhere:

- The Phase 2 and Phase 3 gates (owner, 2026-11-06): 14 days of daily use, then outside testers (#31).
- Nothing about the app-store GPL exception: since 2026-11-23 it no longer gates the listing. The owner is the sole copyright holder, so he can license the app to the store himself. The exception (#745) now gates the first outside contribution instead.
- Branding (#118). The listing icons and the 1280×720 Fire TV icon are in `store/` (#572). The 1920×1080 Fire TV background is not in that folder.
- The privacy policy published on stationbreak.tv, so its URL works.
- The reviewers' demo server running, with its address and sign-in ready for the Testing instructions field.
- Fire TV certification on a device (F4.2, 📺).
