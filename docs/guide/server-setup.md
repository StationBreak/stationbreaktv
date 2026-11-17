# Set up your server

## Jellyfin version

The app needs Jellyfin **12.0 or later**. An older server is refused with a message naming the version it needs.
Jellyfin 10.x is not supported.

The server must have finished its first-time setup (the setup wizard in the Jellyfin web page).

## Connect

On the first screen, type the server's address in **Server address**, then choose **Test**. The app does not search
your network for servers. Any of these forms work:

- a name and port: `jellyfin.local:8096`
- an IP address and port: `192.168.1.20:8096`
- a full address with a path: `https://media.example.com/jellyfin`

With no `http://` or `https://`, the app tries https first and then http. When a connection would not be encrypted,
it may ask you first: see [Certificates and http](certificates.md). Testing the address sends no password.

## Sign in

Sign in with a Jellyfin username and password, or with **Quick Connect** (approve the code from another signed-in
Jellyfin app or the web page). Quick Connect has to be turned on in the server's settings; when it isn't, the app says
so and you use the password instead.

Any account works, admin or not. The account must be allowed to play media: if the server refuses, ask whoever runs
it to check that this account and this TV are allowed. The TV keeps only the sign-in token the server issues, stored
encrypted, never the password. You can revoke that token from the server's dashboard, under its devices list.

## Choose libraries

Pick which of the server's libraries feed the channels. Change the choice later from the guide: **Settings →
Libraries**. **Settings → Sync now** reads the libraries again straight away.

Channels are built from the metadata Jellyfin keeps for every title: genres, ratings, studios, tags and the like. The
better your library's metadata, the better the channels. Library names and folder layout don't matter. If you made
collections in Jellyfin, the built-in channels can use them: turn on **Use my collections** in **Channels**.

## Playback and transcoding

The app plays files directly when the TV can decode them. When it can't, it asks Jellyfin to transcode, so leave
transcoding allowed for the account if some of your files use formats an older Fire TV can't play.
