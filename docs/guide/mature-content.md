# Mature content and kids

The app plays what is on your own server. It doesn't supply, check or moderate that media, and it can't know what is
in your library better than you do. Its content controls are conveniences for the person who runs the server and
knows what is on it. They are not a safety guarantee, and they don't replace the controls in Jellyfin itself.

## What the app does by default

- **After Hours stays off.** The one built-in channel for adult content, **After Hours**, is
  never chosen for your lineup, and it can't be switched on in the free app.
- **Kids channels show only titles made for kids**, rated within the kids limit: at most G, TV-G, TV-Y, TV-Y7 or PG
  (TV-PG is above it). Unrated titles are left out. This holds whatever a kids channel's sources say. A messy library gives
  thinner kids channels, never less safe ones.
- **Kids' titles stay on the kids channels.** The other built-in channels leave out titles made for kids.
- **Your own channels air what you choose.** A custom channel outside the kids numbers has no hidden filters. If you
  turn on **Kids channel** in the builder, it gets the kids rules above.
- **Copies of After Hours stay adult.** A copy of After Hours is always an adult channel. You aren't asked, and you
  can't choose otherwise or change it later. Copying After Hours is only for Pro, as any change to a built-in channel
  is. If you copy its tags into a new custom channel by hand, the app can't tell, and that channel is not marked adult.

Keeping children safe is free: the kids rules above, and Kids mode with a PIN when it arrives. Only conveniences for
parents, such as hours of operation and a bedtime card, are part of Pro. See [Free and Pro](free-and-pro.md).

## What the app relies on

Ratings are only as good as your server's metadata. A rating can be missing, wrong, or from a system the app reads
differently (a regional rating board, for example). A title with no rating is kept off kids channels, but a title
rated wrongly is treated as rated.

An episode of a series follows the series' rating. If a series is rated for kids, all its episodes can air on kids
channels, even one that carries a higher rating of its own (a special, or an episode filed under the wrong series).
That is a deliberate choice: episode ratings are often missing or inconsistent, and judging each one would empty
the kids channels of shows that are made for kids.

## The stronger lock is on your server

Jellyfin's own parental controls apply to everything the app shows. If an account has a maximum rating, blocked tags
or limited library access on the server, the app can only narrow that further, never get around it. To make sure an
account can't see something, set it up that way on the server, for the account that TV signs in with.

## Kids Mode and its PIN

Kids Mode keeps the TV to the kids channels. While it is on, the guide, channel flipping and every other way of
tuning show only the channels numbered from 1 up to its limit: 33, 66 or 99 (33 unless you choose otherwise). A
channel above the limit, or with no number, is hidden and can't be tuned. If the last channel watched, or the one
playing when Kids Mode comes on, is above the limit, the TV moves to the lowest-numbered channel that is allowed and
has something on.

### Turning it on and off

Open the guide, press Menu (or press Back while the guide is on the channel that's playing) and choose **Settings**. **Kids Mode**
has its own section there.

- **First time.** Choose **Kids Mode**. The app asks you to pick a 4-digit PIN and then enter it again to be sure. If
  the two don't match it says so and you start over. Once the PIN is set, Kids Mode turns on.
- **Turning it on later.** Choose **Kids Mode**. It turns on at once; no PIN is needed.
- **Turning it off.** Choose **Kids Mode** while it is on and enter the PIN.
- **Changing the PIN.** While Kids Mode is off, choose **Change PIN**: enter the current PIN, then the new one twice.

The PIN pad is on screen: move with the arrow keys and press OK on a digit, or type the digits on a remote that has
number keys. Four dots show how many digits you've entered, never the digits themselves. Back cancels. A wrong PIN
says how many tries are left; after too many, the pad says how long to wait and takes no digits until then.

While Kids Mode is on, anything that could undo it does nothing and says **Turn off Kids Mode first**: **Channels**
and **About** this channel in the menu, a channel's properties, and **Libraries**, **Update library** and **Sign out**
in Settings. Flipping channels, opening the guide and watching all work as usual, and so do the look, screen fit and
volume settings.

### Choosing how far it reaches

The limit is one of three: **Kids** (channels 1 to 33, the default), **Family** (1 to 66) or **Teen** (1 to 99). The
**Kids Mode** row in Settings shows it next to On or Off, for example "On · up to Family". With the **Kids Mode** row
selected, press Left or Right to move it one step down or up; it stops at Kids and at Teen. While Kids Mode is off the
limit changes at once. While it is on, Left or Right asks for the PIN first, and the limit changes only when the PIN is
right; a wrong PIN leaves it as it was and counts toward the wait below. Until the app has read the lock (a moment
after the guide opens) the limit can't be changed. The limit only decides which channels this TV shows: it never
changes what a channel plays or when.

### Left on and forgotten

If Kids Mode is off and nobody presses a key on the remote for 2 hours, the TV turns it back on by itself, at the limit
you last chose. No PIN is needed for that, as for turning it on yourself. Any key press starts the 2 hours over, and so
does turning Kids Mode off. It does not tell watching from being away, so a film longer than 2 hours that you watch
without touching the remote ends with the TV moving to an allowed channel. Press a key now and then to stay in. The wait
is not kept when the app is closed or the TV restarts; it starts again with the app. Kids Mode with no PIN set yet is
never turned on this way.

### The PIN and the wait after wrong ones

Kids Mode needs a 4-digit PIN. Turning it on needs no PIN; turning it off, changing its limit while it is on, and
changing the PIN all need it. After 5 wrong PINs in a row, no PIN is accepted for 30 seconds, and each further 5 wrong
PINs double that wait, up to an hour. The count and the wait are kept on the TV, so restarting the app doesn't reset
them. The PIN itself is never stored: only a salted hash of it, in the app's own files on the TV, never backed up. The
wait after wrong PINs is the real defence against guessing; the file is not proof against someone who can copy it off
the TV. Setting the TV's clock forward doesn't shorten a wait while the TV stays on (it counts from its own uptime as
well); after a restart it goes back to the TV's clock.

The PIN stops someone from leaving Kids Mode casually. It is a convenience, like a lock on a TV cabinet, not a security
boundary: the server's account controls remain the real limit. If you forget the PIN, the only way to clear it for now
is to clear the app's data in the TV's settings, which also signs the TV out and forgets its channels. A way to reset
it with your server's admin sign-in is planned. A damaged or missing lock record keeps Kids Mode on: if the
lock's file is ever damaged or deleted after a PIN was set, no PIN can be set or checked, and the TV needs its app data
cleared.
