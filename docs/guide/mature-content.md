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
  turn on **Kids channel** in the builder, it gets the kids rules above. A copy of After Hours is your own channel
  and airs what it holds.

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

## A PIN, when it comes

There is no PIN in this version. A kids mode with a PIN is planned. When it arrives, the PIN will stop someone from
leaving kids mode casually. It is a convenience, like a lock on a TV cabinet, not a security boundary: the server's
account controls remain the real limit.
