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

Kids Mode needs a 4-digit PIN. Turning it on needs no PIN; turning it off, changing its limit while it is on, and
changing the PIN all need it. After 5 wrong PINs in a row, no PIN is accepted for 30 seconds, and each further 5 wrong
PINs double that wait, up to an hour. The count and the wait are kept on the TV, so restarting the app doesn't reset
them. The PIN itself is never stored: only a salted, slow hash of it, in the app's own files on the TV, never backed
up.

The PIN stops someone from leaving Kids Mode casually. It is a convenience, like a lock on a TV cabinet, not a security
boundary: the server's account controls remain the real limit. If you forget the PIN, the only way to clear it for now
is to clear the app's data in the TV's settings, which also signs the TV out and forgets its channels. A way to reset
it with your server's admin sign-in is planned.
