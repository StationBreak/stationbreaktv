---
permalink: /privacy/
---

# Privacy

The app talks to one place: the Jellyfin server you connect it to. It has no analytics, no advertising, no crash
reporting and no account with us. Nobody but you and whoever runs your Jellyfin server sees what you watch.

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
- Your channel lineup: the names, numbers and rules of the channels, kept for each account you sign in with. It
  holds no password, token or media titles. Signing out keeps it, so signing in again brings your channels back.

None of it is backed up to the cloud. Uninstalling the app removes all of it, and so does clearing the app's data
(on Fire TV: **Settings → Applications → Manage Installed Applications**, then the app, then **Clear data**). That
includes the lineup and any copy of a file that was only partly written when the TV lost power. Revoking the TV's device in your
Jellyfin dashboard ends its sign-in.

## Permissions

The app asks Android for network access (to reach your server and to know when the network is up), to keep the
TV awake while a background library sync finishes, and to restart its background library sync after the TV restarts. Nothing else:
no microphone, location, contacts or storage.

## Contact

Questions or concerns: email [support@stationbreak.tv](mailto:support@stationbreak.tv), or open an issue on
[GitHub](https://github.com/StationBreak/stationbreaktv/issues). To report a security problem privately, follow the
[security policy](https://github.com/StationBreak/stationbreaktv/blob/main/SECURITY.md).
