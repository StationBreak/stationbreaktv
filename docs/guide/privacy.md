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

None of it is backed up to the cloud. Uninstalling the app removes all of it. Revoking the TV's device in your
Jellyfin dashboard ends its sign-in.

## Permissions

The app asks Android only for internet access, which it uses to reach your server.

## Contact

Questions or concerns: open an issue on [GitHub](https://github.com/StationBreak/stationbreaktv/issues). To report
a security problem privately, follow the
[security policy](https://github.com/StationBreak/stationbreaktv/blob/main/SECURITY.md).
