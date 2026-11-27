---
permalink: /privacy/
---

# Privacy

StationBreak is a trade name of Test Pattern Labs LLC. The app talks to one place: the Jellyfin server you connect it
to. It has no analytics, no advertising and no account with us. The one exception is crash reports, which are off
until you turn them on (see [Crash reports](#crash-reports-optional)). Nobody but you and whoever runs your Jellyfin
server sees what you watch.

## What the app sends to your Jellyfin server

- Your sign-in: a username and password, or a Quick Connect request. The password is sent only to sign in, and only
  after any warning about an unencrypted connection.
- Requests to read your libraries and channel logos, and to play media.
- What this TV is playing, as other Jellyfin apps do, so the server's dashboard shows it and watched status can
  update.

## What the app keeps on the TV

- The server's address, and the fingerprint of its certificate if you chose to trust a self-signed one.
- The sign-in token the server issued, encrypted with a key held by the TV's secure key store. Never your password.
- A copy of your library's titles and metadata, the channel lineup, your channel changes and your settings.
- A cache of channel logos from your server.
- Your choice about crash reports (off unless you changed it), and, if you turned them on, any crash report that is
  still waiting to be sent.
- Your channel lineup: the names, numbers and rules of the channels, kept for each account you sign in with. It
  holds no password, token or media titles. Signing out keeps it, so signing in again brings your channels back.

None of it is backed up to the cloud. Uninstalling the app removes all of it, and so does clearing the app's data
(on Fire TV: **Settings → Applications → Manage Installed Applications**, then the app, then **Clear data**). That
includes the lineup and any copy of a file that was only partly written when the TV lost power. Revoking the TV's device in your
Jellyfin dashboard ends its sign-in.

## Crash reports (optional)

Crash reports are **off by default**. Nothing about them runs, and nothing is collected or stored, until you turn on
**Settings → Send crash reports** in the guide. If that row is not in your Settings, your copy of the app has nowhere to
send a report and cannot send one.

With it on, when the app crashes it sends one report to a server run by Test Pattern Labs LLC (the developer), over an
encrypted (HTTPS) connection, and to nowhere else. If the TV is offline, or the crash was on the screen thread, the
report waits on the TV and is sent the next time you open the app. A report holds:

- the technical record of the crash (a stack trace: the chain of code that failed, and the error's message);
- the app's version, Android's version, the TV's brand, model and product name, and how much memory it had;
- the time of the crash and a random number that names the report. It is not tied to you, your TV or your account.

It holds no log, no settings, no device or advertising id, and no copy of your library. Before it leaves the TV the app
takes out web addresses, server names and IP addresses, sign-in tokens, keys and passwords, e-mail addresses, and the
32-character ids a Jellyfin server uses. That cleaning is automatic and cannot promise to catch everything: an error
message could still include something else, such as the name of a channel, a title or a file path. Like any server, the one that
receives the report sees the network address it came from.

The server keeps each report for 90 days and then deletes it automatically.

Turning the row off stops collecting at once and deletes any report still waiting on the TV. A report that has already
been sent is not on your TV, so the app cannot take it back; email [support@stationbreak.tv](mailto:support@stationbreak.tv)
about it.

## Permissions

The app asks Android for network access (to reach your server and to know when the network is up), to keep the
TV awake while a background library sync finishes, and to restart its background library sync after the TV restarts. Nothing else:
no microphone, location, contacts or storage.

## Contact

Questions or concerns: email [support@stationbreak.tv](mailto:support@stationbreak.tv), or open an issue on
[GitHub](https://github.com/StationBreak/stationbreaktv/issues). To report a security problem privately, follow the
[security policy](https://github.com/StationBreak/stationbreaktv/blob/main/SECURITY.md).
