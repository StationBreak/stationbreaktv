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
- **Explicit titles air only on After Hours.** A title is explicit when it, or its series, has an adult-only rating
  (X, XXX, AO, NC-17, or a country's adult rating such as R18 or X18+, also as part of a combined rating such as
  R/NC-17), an explicit genre (such as Hentai, Adult or
  Erotica) or an explicit keyword from the server's metadata (such as pornography, softcore or sexploitation). Anime
  or not, no other built-in channel airs it, and Odds & Ends doesn't either. With After Hours off, explicit titles
  air nowhere.
- **Teen and family channels follow their audience, not only a rating.** A built-in channel numbered 34 to 66 airs
  only family titles, and one numbered 67 to 99 only family and teen titles, wherever its number came from: a
  built-in channel you move there follows the same rule. A title with nudity, sexual or adult keywords, or a rating
  above TV-14, stays off them even when its rating would fit. After Hours can't be given a number below 100. This
  promise covers the built-in channels; your own channels keep no hidden filters at any number above 33.
- **When in doubt, the app moves a title up.** NC-17 counts as explicit, and so does an explicit keyword on a
  mainstream film, so a few well-known films leave the regular channels for After Hours. Keywords that only describe
  a plot about the adult industry don't count.
- **Titles with no information stay out of Odds & Ends.** A title with no rating, no genre and no keyword is kept off
  the kids, family and teen channels and out of Odds & Ends (199). It can still air on a grown-up channel whose own
  sources pick it, such as a collection.
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

The explicit rule reads the same metadata. A file with no metadata at all can't be told apart from a test clip, so it
is not treated as explicit: it stays off the kids, family and teen channels and out of Odds & Ends, but a grown-up
channel that picks it by its library or collection can still air it. An episode whose series the app hasn't read is
treated the same way, because its series could be explicit. If a library holds adult media, limit which accounts can
see it on your server.

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
boundary: the server's account controls remain the real limit.

### If you forget the PIN

When the PIN pad asks for the PIN (to turn Kids Mode off, change the PIN or change the limit), choose **Forgot PIN?**
under **Delete**. The TV shows a code. On a phone or computer, open Jellyfin signed in as one of your server's
administrators, go to **Quick Connect** and enter the code. Nothing is typed on the TV, and only an administrator's
approval works: a code approved by anyone else changes nothing. When the administrator approves, the PIN is cleared and
Kids Mode is off. The limit you chose (Kids, Family or Teen) is kept. Choose **Kids Mode** again to set a new PIN.

Back cancels at any time. If the code runs out, or the server refuses it, press OK for a new code. Quick Connect has to
be switched on for the server: if it is off, the TV says so, and an administrator turns it on in the server's dashboard
first. The reset needs the TV to reach the server. It does not sign the TV out or forget its channels. A damaged or
missing lock record keeps Kids Mode on: if the lock's file is ever damaged or deleted after a PIN was set, no PIN can be
set or checked, the reset cannot repair it, and the TV needs its app data cleared in the TV's settings, which also signs
the TV out and forgets its channels.

## Hours of operation (Pro)

Hours of operation is a weekly schedule for this TV: a list of periods, each with a day, a start, an end and a level.
The levels are the ones Kids Mode uses (Kids, Family, Teen), plus **Adult** and **Off Air**. It is part of Pro and each
TV keeps its own schedule. An empty schedule means no hours: the TV behaves as it does without them.

**Note:** for now this screen only keeps your schedule. The TV does not yet change what it shows by it.

Open the guide's menu, choose **Settings**, then **Hours of operation**. The row appears with StationBreak Pro; without
Pro it is not there. It needs a PIN first (set one with **Kids Mode**, as above), so a grown-up can
always override the hours. While Kids Mode is on, the row does nothing and says **Turn off Kids Mode first**.

- **Adding or changing a period.** Choose **Add a period**, or a period in the list. Move down the rows and press Left
  or Right to change a value: the day, **From**, **To** and the level. Times move 15 minutes at a time and go round
  past midnight; the days go round the week. Choose **Save** to keep it. Back leaves without saving.
- **Overnight.** A period that ends at or before its start carries on into the next day, and the list says "the next
  day". For example Friday 10:00 PM to 6:00 AM is Friday night into Saturday morning. A period belongs to the day it
  starts on. A start and an end that are the same time are not allowed.
- **Deleting a period.** Choose the period, move down to **Delete this period** and press OK.
- **Overlaps.** Periods may overlap. Where they do, the strictest level applies, with Off Air the strictest, then Kids,
  Family, Teen and Adult.

The PIN overrides the hours: enter the PIN to turn Kids Mode off and the TV is grown-up until Kids Mode comes back on,
for example after the 2 hours without a key press described above.

If Pro goes away (for example after a refund) your schedule is kept, but it is switched off and can't be changed until
Pro is back.
