# FAQ

**Do I need a Jellyfin server?**
Yes. The app plays your own media from your own Jellyfin server (12.0 or later). It has no content of its own. See
[Set up your server](server-setup.md).

**Does it need the internet?**
It needs to reach your Jellyfin server and nothing else. A server on your home network is enough.

**Can I pause, or catch the start of a show I tuned in to late?**
Yes. Tuning in joins whatever is on, part-way through, but you can pause, go back as far as the start of the show
that's on, and skip ahead again up to where the channel is now. The channel's schedule keeps running while you're
paused. A paused place is kept for 60 minutes after the screen stops; after that, the channel picks up live.

**Why isn't a channel in the guide today?**
A channel with nothing to air today is hidden, and comes back when it has something. **Channels** shows why a
channel isn't on, for example that nothing in your libraries fits it.

**I changed my library. When do the channels change?**
The app reads the libraries again by itself. To read them now, choose **Settings → Sync now** in the guide. Some
lineup changes start with the next day's schedule at 6 AM.

**My server uses a self-signed certificate. Can I still use https?**
Yes. The app shows the certificate's fingerprint and asks whether to trust it. See
[Certificates and http](certificates.md).

**The app says the certificate isn't trusted, and offers nothing else.**
The certificate is probably from a private certificate authority, which the app doesn't use. See
[What doesn't work](certificates.md#what-doesnt-work).

**Does the app collect data about me?**
No. It talks only to your Jellyfin server. See [Privacy](privacy.md).
