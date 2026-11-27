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
A channel with nothing to air today is not in today's guide, and comes back when it has something. **Channels** shows why a
channel isn't on, for example that nothing in your libraries fits it.

**I changed my library. When do the channels change?**
The app reads the libraries again by itself. To read them now, choose **Settings → Update library** in the guide. Some
lineup changes start with the next day's schedule at 6 AM.

**How do I turn Family Mode on, or off?**
In the guide, open the menu and choose **Settings → Family Mode**. The first time it asks you to choose a 4-digit PIN;
after that it turns on at once, and turning it off needs the PIN. While it is on, Channels, Libraries, Update library and
Sign out stay shut until you turn it off. See [Mature content and Family Mode](mature-content.md#turning-it-on-and-off).

**I forgot my Family Mode PIN.**
On the PIN pad, choose **Forgot PIN?**. The TV shows a code: a server administrator approves it with Quick Connect in
Jellyfin, and the PIN is cleared and Family Mode turned off. See
[If you forget the PIN](mature-content.md#if-you-forget-the-pin).

**Can I set hours for when the TV is limited to Tots, Kids or Teens, or off the air?**
That is hours of operation. The **Hours of operation** row in Settings appears with StationBreak Pro. It needs a PIN first, and the PIN always
overrides the hours. The TV follows the schedule, and the PIN always gets you past it. See
[Hours of operation](mature-content.md#hours-of-operation-pro).

**Can Family Mode show more than the Tots channels?**
Yes. On the **Cap** row in Settings, right under **Family Mode**, press Left or Right to choose how far it reaches:
Tots (channels 1 to 33), Kids (1 to 66) or Teens (1 to 99). While Family Mode is on, changing it asks for the PIN. See
[Choosing how far it reaches](mature-content.md#choosing-how-far-it-reaches).

**How do I sign out, or change server?**
In the guide, open the menu and choose **Settings → Sign out**. The app asks first, with **Stay signed in** highlighted, so
pressing OK by accident keeps you signed in. Choose **Sign out** to sign out of this TV. You'll need to sign in again on
this TV, and you can pick another server then.

**What does "When changing channel" do?**
It chooses what shows for the moment a channel takes to tune in: the station's **Stand-by card** (the default) or
**Static**, like an old TV. It's in **Settings** in the guide.

**My server uses a self-signed certificate. Can I still use https?**
Yes. The app shows the certificate's fingerprint and asks whether to trust it. See
[Certificates and http](certificates.md).

**The app says the certificate isn't trusted, and offers nothing else.**
The certificate is probably from a private certificate authority, which the app doesn't use. See
[What doesn't work](certificates.md#what-doesnt-work).

**Does the app collect data about me?**
No. It talks only to your Jellyfin server, with one optional exception: a build that has crash reports turned
on (they are off until you turn them on) can send a crash report to the developer. See [Privacy](privacy.md).

**Is StationBreak free? What does Pro add?**
The app is free, with the channels it builds for you and up to 5 of your own. Pro, a one-time purchase that comes after
the free app, adds changing the lineup (switching built-in channels on or off, hiding, renumbering, reset) and more
than 5 channels of your own. See [Free and Pro](free-and-pro.md).

**Where can I buy Pro? What if I get a refund?**
Paid Pro is sold on Amazon, for Fire TV only. The GitHub download has Pro unlocked. After a refund your built-in
channels return to the lineup StationBreak builds, and channels of your own beyond five (the five with the lowest IDs keep working) are locked, not
deleted. See [If a purchase is refunded](free-and-pro.md#if-a-purchase-is-refunded).
