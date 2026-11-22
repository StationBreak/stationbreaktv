# ADR-0023: Lineup curation (spec 23)

- **Status:** Accepted, 2026-11-04, when PR 5 ([#587](https://github.com/StationBreak/stationbreaktv/pull/587)) merged
- **Date:** 2026-11-04
- **Decided by:** the owner (rules R1 to R10, 2026-11-02; Comedy County and "family channels are their own spec",
  2026-11-03; on 2026-11-04, that real people's names may appear as collection anchors in the code table, and that a
  hidden channel holds back what its current recipe airs, and option C for the grown-up hold: nothing airs nowhere
  because of the holds) and the planning session (Resolutions 1 to 33)
- **Builds on:** [ADR-0002](0002-determinism-scope.md), [ADR-0019](0019-kid-hold.md) and [ADR-0020](0020-grown-up-hold.md)
  (their spec 23 addenda), [ADR-0022](0022-rules-format-v2.md) (its version 3 addendum)
- **Informs:** spec 23; [docs/rules-format.md](../rules-format.md), which holds the exact text of version 3

## Context

The starter lineup was chosen by size. Each recipe was evaluated alone, so a title sat on any number of channels. On a
real library it sat on 4.3 channels on average, and 686 titles sat on five or more. The 50 channels switched on were
the 50 biggest pools. Narrow channels with a clear identity lost to broad ones, and the programmes next to each other had
nothing in common. The owner's words: "There is no sense of where do I go to catch X."

Three constraints shape the answer:

- **Portable.** The lineup must be good on a server that has only standard Jellyfin and TMDB metadata. Library names,
  library types and folder layout are never signals. Household collections are an optional bonus.
- **Deterministic.** Two TVs with the same lineup and library must compute the same homes and the same days (ADR-0002).
- **Gentle.** A change must not cut the programme on now or move the rest of any channel's day. The oldest supported TV
  must not get noticeably slower.

## Decision

### 1. The model

Spec 23 adds a step between "each recipe's candidate pool" and "each channel's day". It is a pure function of the
library snapshot, the catalog, the holds and the household setting. Its version name is `curation-1`
(`Curation.VERSION_NAME`).

**A title** is a series (all its episodes together) or a film. Its **tone** is made for kids or not, adult animation or
not, and a format (live action, animation or anime). Tone is derived from genres, ratings, studios and tags, never tagged
by hand.

**One home per title.** When the lineup is chosen, every title is scored against every candidate starter (the non-opt-in
ones, and any the viewer switched on). When a day is built, it is scored against the curated starters on the air that day,
or the basis's set. It goes to the one where it fits best. Ties go to the lower **recipe** number (the recipe's default
number, never the channel's, so a viewer's renumbering never moves a home), then the channel ID. The score is an integer:

| Term | Points |
| --- | --- |
| Specificity: 100 divided by log2(2 + the channel's candidate titles) | a smaller channel fits each title more |
| Each of the title's genres that is one of the channel's theme genres | +40 |
| Each tag that hits the theme, at most 2 | +35 each |
| A theme studio matches | +120 |
| The channel is a decade (year-range) channel | -150 |
| The title is one of the channel's anchors | +1000 |
| A made-for-kids title on a kid channel | +200 |
| The channel's format promise fits (films only; short series) | +80 |
| The title's tone on the channel: 60 times the hours of that tone divided by the channel's candidate hours, rounded down | 0 to +60 |

**Guests.** A title that is not made for kids may also guest on up to two other grown-up channels where its tone is at
least a quarter of that channel's home tone mix, best fit first. Guests may take at most 30% of a channel's airtime
(3/7 of its home hours). When there are more, the weakest guest goes first. A channel with no homes takes no guests.

**Decade channels are guest-only.** A year-range channel takes a home only when no other channel fits. It is built from
guests, and the 30% cap does not apply to it.

**The anime lane.** On grown-up channels anime is its own lane. An anime-only channel's candidates are anime titles only,
and any other grown-up channel's candidates exclude anime. Kid channels take both (anime still has to pass the kid hold). An
anime item outside its lane is left out of a curated pool.

**Kid channels share one pool.** Every made-for-kids candidate a kid channel's recipe admits airs on it. A kid title
never guests on a grown-up channel. The 30% cap applies to grown-up channels only. A title that is not made for kids
but is homed on a kid channel may still guest on grown-up channels.

**The share cap becomes the trim window's keeps.** No series may take more than a quarter of the channel's curated hours (the
cap is at least one hour). The curation does not choose which episodes to drop. It gives the scheduler a cap in milliseconds for each
series over the cap (the **keeps**), and claims all the series. The scheduler keeps a window of episodes that moves each
day (rules format version 3, "The trim window"). So every episode airs in turn, and the keeps are the only place the 25%
rule is applied. Films are never trimmed.

**Weights.** Anchors weigh 3, home titles 2, guests 1. The `curated` play order uses them to bring a heavier title round
a little more often. They are a side input of the day build, not in the rules file.

**Selection by identity.** Each round, every title is homed over all the candidate channels that are left. A channel
qualifies with 12 usable hours (its hours under the share cap) and at least 6 titles (4 for kids); the recipes that are
the catalog's single lane for a niche theme carry a lower bar of their own, 6 usable hours and 4 titles (catalog 5, a
recipe field frozen in the catalog manifest, so it depends on the catalog and the library only). A qualifying channel
scores `min(usable hours, 80)` in hundredths of an hour, plus 1500 per anchor up to 4, plus 100 per title up to 30. The
top scores fill the room, ties to the lower recipe number. The lineup keeps its cap of 50 (48 starters and the two leftovers
channels; *amended 2026-11-21: 52, with the four Odds & Ends of catalog 8, see the addendum*). Channels the viewer chose count first. *Amended 2026-11-15: the cap and the seats are changed by the addendum at
the end.* There are at most 6 rounds, and a chosen channel that stopped
qualifying is dropped and the round repeats. (In practice removing channels only adds homes, so the loop settles at once.
A property test holds that claim, and the rounds stay as a safety net.)

**Shelf reasons.** Every starter off the lineup carries one reason, shown in Channels: nothing fits it (**empty**), too
little fits once every title has its best home (**too thin**), it fits but stronger channels filled the lineup (**below
the cap**), or it is only on if the viewer switches it on (**opt-in**). A thin channel comes back by itself when the
library grows. *Amended 2026-11-15: "below the cap" now means only that the planner's own picks are stronger (see the
addendum).*

Custom channels are never re-homed and never lose a title to a starter. An edited starter is treated like one: it is never curated and airs its own pool.

### 2. Integer and deterministic

All of the above is integer arithmetic. The specificity term uses Q16 fixed point (16 fraction bits). The binary
logarithm is worked out by integer squaring, so it truncates and is a little low (the test tolerance is 1.0001 units of
the exact value). It is the same on every device. There is no floating point, no random number and no hash-map
iteration order in any result. Weights are "integer stride scheduling" with a per-day hash jitter of up to 90 minutes. All
ties are broken by recipe number (never the channel's number), then ID, and titles are processed in canonical-ID order. The test that matters is
that two builds of one lineup give identical homes, weights, keeps and days whatever order the inputs arrive in.

Every input that changes a day feeds the day's input digest and the pool cache key: the curation version, the household
flag in force that day, the curation basis, the weights and the keeps. A curated channel's play key carries the curation
digest.

Cost if wrong: a rounding difference between two builds of the app would give two TVs different homes. Tests pin the Q16 log's
accuracy and values, and a change to it is a new curation version.

### 3. The day boundary (D1)

The rule: **nothing changes another channel's rest of today.** Every change that would move what a channel airs waits
for the next broadcast-day start (06:00 at the lineup's offset). That includes:

- new rules for an unedited starter, from a catalog update or from a re-resolve after a sync, and an anchor appearing;
- the set of channels switched on that the curation homes over (the curated on-set);
- "Use my collections";
- the first use of the extras filter.

How it works:

- **The basis.** When the curated on-set or the household flag changes, the lineup records a basis: the on-set and flag
  as they stand for the days before the boundary. Until the boundary the curation, and the leftovers' claims, read the
  basis. After it, they read the new set. The lineup rolls forward at the boundary, clearing what is past.
- **Keepers.** A starter that takes new rules at the boundary keeps its previous rules for the days before it. Those
  starters are the **keepers**. The **protected** ones come first: a member of a pending basis, or an unedited starter on
  the air today whose rules for today are programmed. They are what today's curation reads, so they must not change.
  Only 50 keepers get a copy: the protected starters first, then the rest on the air by number, then recipe key. Then come
  the starters that hold back content from the leftovers (hidden ones and opt-in ones), up to the room the file has left.
  Each keeper costs a copy of its rules in the lineup file. The file is capped at 1 MiB, and a copy is limited to a byte
  budget (3,084 bytes, about 27 collection sources). Only a starter whose rules fit the budget is a keeper.
- **Starting and leaving.** A starter that selection switches on is on from the boundary: its `onSinceMs` is a time
  ahead, and it is not on the air before it. A starter that selection leaves out stays on until the boundary, then goes
  to the shelf (`leavesAtMs`). The first choice, which has aired nothing, switches on at once. A starter that selection
  switched on for the boundary and takes back before it airs goes back to the shelf with `onSinceMs` set to the time of
  that plan, never a time ahead. So a Shelf starter whose start is still ahead is only ever the viewer's switch-off of one
  waiting to start, which is how a later switch-on is told to be the undo of it (see the amendment of 2026-11-16 in the
  addendum). The `onSinceMs` of a Shelf starter therefore has two meanings: when the viewer switched it off a start still
  ahead, or when the planner took it back.
- **Per day, not per moment.** Whether a channel starts after, or has left by, a day is decided from the day
  (`LineupDay`), never from the moment someone asks. An earlier version decided by the moment, and a viewer switching a
  channel on in the middle of the day made one lookup cache an empty day. A day now gives the same answer to every
  lookup.
- **Viewer switches.** A viewer's own switch-on in the middle of a day airs from now. It is the viewer's wish and is
  pinned (never shelved by selection). A switch-on of a starter the planner had not picked adds a channel and takes no
  planner seat (the amendment of 2026-11-16).
- **Play keys.** `LineupDays.playKeyOf(channel, lineup, atMs)` describes the day being played at `atMs`: the rules in
  force that day, the household flag in force that day, and (for a curated starter) the curation in force that day. A
  change waiting for the boundary moves no key until then. The background roll writes the new revision at the boundary.
- **Persisted days (format 6).** Saved days (`DaySnapshotCodec.VERSION` 6) carry the weights and the day pool after the
  trim window. A restored day is made with no keeps, so the window is applied once, when a fresh day is made. Days are
  keyed on the holds and on `curation-1`, so a hold or curation change rebuilds them.
- **The lineup file** is version 3. It adds the basis, previous rules, leave times, shelf reasons, the household flag,
  the viewer-choice marks and a re-plan time. Before the first save of an upgrade, the old file is copied to a `.v<old>.bak`
  file. An older app that finds a version 3 file shows a planned default lineup and cannot save. It never reads the copy.
  Version 4 added the repair record (ADR-0025) and version 5 the viewer's added channels (the amendment of 2026-11-16);
  the file is written at the lowest version that holds its content, and the copy of the file an upgrade replaces is
  `.v<from>-to<to>.bak` (`.v3.bak` for the upgrade to 4, as before).

Exceptions that apply at once: the back-to-back fix of an old file (applied at each TV's next start, so two TVs can
differ until both have started), a viewer's reset, the safety holds, and the edges listed in Costs.

*Amended 2026-11-08 by [ADR-0025](0025-repairing-today.md) (spec 26): three triggers relax this rule for the rest of
today, and only these: a change of the library selection in Settings, Sync now when it finds changes, and a background
pass that changed the library. Each repairs every switched-on channel's rest of today from the programme on air, and a
title added that way gets its home only, with no guest spots until the boundary. Everything listed above still waits for
the next boundary.*

### 4. Portability and the household setting

Anchors (collection, series and film names, studios and networks) and theme studios are rules sources, matched by exact,
prefix, whole-word or contains rules on ASCII-folded names. No regular expression runs per item. The anchor table is in
code, and per the owner, may name franchises, studios and, since 2026-11-04, real people as collection anchors. It is the
one place that does. A server with no collections, studios or tags still gets a full lineup from TMDB genres alone. A
test holds that.

**Use my collections.** A setting kept with the lineup (per account on this TV), **on by default**, with a row on the
Channels screen. It takes effect from the next broadcast-day start. It **survives a channel reset**. When it is off, a
starter's **household-only** collection sources are left out. A collection is household-only unless its name, after
cleaning and ASCII folding, ends in " Collection". That is how TMDB's automatic collections are named, and Jellyfin does not
mark them, so this is a heuristic. A collection with no name on this TV counts as household-only. The setting applies to unedited starters only. When it leaves a starter no source, the day
is empty with its own reason (`HouseholdCollectionsOff`). The franchise map of the `curated` order does not depend on the
setting. It uses box-set names the same way, always.

### 5. Kid safety

Kid safety holds at every step. The curation only ever takes items out of a pool already evaluated under its kid or
grown-up policy. It never adds one. Anchors and theme studios pass the kid hold and the ceiling on a kid channel.

- **`kid-hold-3`** ([ADR-0019](0019-kid-hold.md) addendum) adds the animated-family signal to "made for kids". Kid
  channels share that pool.
- **`grown-up-hold-2`, option C** ([ADR-0020](0020-grown-up-hold.md) addendum, PR 3, #577). A grown-up channel drops an
  item only when a kid channel could take it. A made-for-kids title that a kid channel cannot take (unrated, or rated
  above the widest kid ceiling) **stays on grown-up channels** until family channels
  ([#576](https://github.com/StationBreak/stationbreaktv/issues/576)) or parent library trust (spec 19, S2.6.1) give it a home. The owner
  chose this on 2026-11-04 so that nothing airs nowhere because of the holds. (The plan said these titles would air nowhere.
  That was the first draft of the rule and is out of date.) The grown-up Odds & Ends is `GrownUp` while Kids' Odds & Ends is
  on and `Open` while it is not.
- **The opt-in holdback never narrows.** What an opt-in starter holds back from the leftovers keeps every source and
  exclusion of catalog 2, and filters no more. A test pins it against catalog 2. A later catalog needs the same test.

### 6. Costs and known effects

These are accepted. Each is in the planner's KDoc, which is the authority.

**What changes in the middle of a day, and what does not**

- **Hold changes apply at once** (Resolution 32). `kid-hold-3`, `grown-up-hold-2` and the move of the grown-up leftovers
  to `GrownUp` are safety policy. They apply at the update, and a programme on now can be cut once. A newer catalog that
  adds Kids' Odds & Ends switches the grown-up leftovers' policy at once for the same reason.
- **Resolved again, at the boundary.** Rules resolved again from a sync or a newer catalog start at the next boundary. The
  exceptions apply at once: an old file's back-to-back fix and a viewer's reset.
- **More than 50 channels on.** Only the viewer's own switch-ons can exceed 50. Past 50, a re-resolve can change those
  channels' today, because only the first 50 by number get a copy of their previous rules. A protected starter beyond the
  limit takes its new rules at once.
- **Many hidden starters on a full lineup.** A starter that withholds content from the leftovers beyond the room in the
  file takes its rules at once, so what the leftovers withhold can shift once in the middle of a day.
- **A starter switched on mid-day by the viewer** airs from now. A programmed one is not in the basis, so until the
  boundary it airs its whole pool: the titles no basis channel homes as its own (weight 2) and the titles a basis channel
  homes as guests (weight 1), so it is not empty for the day. A made-for-kids title never guests on a grown-up channel (a kid channel airs its whole pool, already held to kid
  content), and the anime rule holds for every title. The curation's tone test and guest cap are not applied: they read a channel's home hours,
  which it has none of. Both it and a basis channel can air the same title until the boundary. *Amended 2026-11-14
  ([#776](https://github.com/StationBreak/stationbreaktv/issues/776)): before, it aired only what the basis did not
  home, which could be nothing, and then it was hidden for the day with its reason.*
- **A switch-on that is not curated.** A starter the viewer switches on mid-day that is not curated (an edited one) claims
  its pool at once, so the leftovers' current day moves once.
- **Rules over the budget.** A starter whose stored rules are over the per-copy budget (rules from an older app) is never
  a keeper. It takes new rules at once, so its day can change mid-day.
- **A newer catalog that retires a curated recipe** changes today's curated on-set at once. One that moves a recipe in or
  out of the curated order waits for the boundary only on a keeper. Catalog 3 retires nothing.
- **A downgrade** to a build from before the day-boundary machinery reads no future start time, so a starter switched on
  for the boundary airs at once there.

**Choices the viewer made before the update**

- **The swap inference.** A lineup written before version 3 does not record which starters the viewer touched. The app is
  unreleased, so the planner infers it **once**, before its first selection. An On starter that is opt-in, or that the
  old planner would not have picked, reads as the viewer's and is pinned. So does a starter the old planner put on that no
  longer fills. The old top is worked out by ranking the extras-filtered snapshot of that moment (E7). The inference
  can miss in **both** directions. Too many pinned is accepted, because the viewer can switch them off. Too few means a
  switch-on from before the update that sat inside the old top reads as the planner's, and a later selection can shelve it.
  Switching it on again pins it. The result **depends on the library at that moment**, so two TVs that swap on different days
  can infer differently.
- **A waiting catalog.** While a newer catalog that selects by identity waits for a full collection pass, no starter
  switches on or leaves. A newer ranked catalog waits with the lineup still ranked. On the first move to catalog 3 the
  ranked path runs, and a starter it switches on is resolved from the newer recipe.

**Hiding a channel**

- **A hidden channel holds back what its current recipe airs** (owner, 2026-11-04). A hidden starter keeps its titles out
  of the Odds & Ends channels, using its recipe as it is now, not as it was when it was hidden. A catalog update can narrow
  what it holds back, from the next day. The cost: a recipe whose genre spellings are not TMDB's can hold back less than
  catalog 2 did. This is grown-up only and is not a safety matter.

**What the guide and the builder show**

- **The guide past the next 06:00** shows the days as the lineup holds them until the background roll writes the new
  revision at the boundary. A change waiting for the boundary shows there only from then. This includes the household
  setting's play key.
- **The curation's inputs about a recipe** (its themes, tags, studios and format) are not frozen for the day. A catalog
  update can move today's homes on curated channels. Every app build rebuilds the days anyway, so this is the same
  once-per-update change.
- **Planning and airing read different snapshots.** The planner homes on the snapshot of that plan, without channels' rating
  ceilings. A day is built from the snapshot at its cut-off and under its ceilings. A channel the selection judged full
  enough can air thinner than judged.
- **The builder's preview** shows a copied starter's days uncurated
  ([#594](https://github.com/StationBreak/stationbreaktv/issues/594)).
- **A clock that jumps backwards** over a day start makes a started channel look as if it starts later, so it has an empty
  day, and a viewer's switch-off shelves it instead of hiding it. It recovers when the clock passes the day start again.
- **A viewer's number override always holds.** A new recipe whose default number is held by an override joins the shelf
  with no number, and never shares one.

**Upgrade day**

- The first launch after the update runs a full refresh (the inference and the selection) and two day builds. On the
  Insignia that was 22.7 s and 18.2 s, with the picture at 36 s. Today's guide was unchanged (the same 50 numbers). The
  channels that start at the boundary show "Nothing to air today" until it.

### 7. Measured results

Numbers only.

**The live audit** ran on the PR 5 branch against a real library of about 1,900 titles, with the default setting and with
"Use my collections" off. Both runs passed all targets.

| Target | Before | After |
| --- | --- | --- |
| Titles on 5 or more channels (after: grown-up titles) | 686 | 0 |
| Channels per title, mean | 4.3 | 2.16 |
| Titles on air | | all but 3 over-length single files |
| Kid audit | | 0 violations in about 14,000 checks |
| Anchors cut by the per-starter budget | | none (at most 19 sources on a starter, against about 27) |

**The swap simulation** (a lineup written by the old planner, updated): the inference pinned 1 starter, today's blocks were
unchanged on every channel, and every target passed after the boundary.

**Insignia timings** (debug build against debug build, main against PR 5, relative to the 10% bar):

| Step | Change |
| --- | --- |
| Guide open | -1% |
| Day restore | -16% |
| Day build | +9% |
| Sync to lineup ready | +7% |

All four are within the bar. The day build and the sync were single samples. Not gated: the app's displayed time +15%, days
ready +14%, memory +12.5 MB. A benchmark-build confirmation on main after the merge is still to do. Follow-up issues track the timings:
[#588](https://github.com/StationBreak/stationbreaktv/issues/588) (cold launch),
[#589](https://github.com/StationBreak/stationbreaktv/issues/589) (the upgrade's double day build) and
[#590](https://github.com/StationBreak/stationbreaktv/issues/590) (refresh cost).

## Consequences

- The lineup is programmed: a title has a home you can learn, and at most two guests. The 50 channels are chosen by
  identity, not size.
- The rules format has a third version (`curated`, studios, theme filters, the trim window). The independent vectors cover
  it. No version 1 or 2 file changed.
- The lineup file, the saved days and the library cache each moved a version (3, 6 and 9 for the names). An app older than
  these cannot save a lineup written by this one.
- The inference of earlier choices is a once-only guess that exists only because the app is unreleased. After release, a
  catalog change must not need one.
- Family channels ([#576](https://github.com/StationBreak/stationbreaktv/issues/576)) and parent library trust (spec 19,
  S2.6.1) give made-for-kids titles that no kid channel can take a home of their own. Until then they air on grown-up
  channels.
- Kid channels share one made-for-kids pool, so the same titles repeat across them
  ([#591](https://github.com/StationBreak/stationbreaktv/issues/591)).

**Follow-up issues.** Family channels: [#576](https://github.com/StationBreak/stationbreaktv/issues/576). From the PR 4
review: [#582](https://github.com/StationBreak/stationbreaktv/issues/582) to
[#586](https://github.com/StationBreak/stationbreaktv/issues/586). From PR 5: the Insignia timings
[#588](https://github.com/StationBreak/stationbreaktv/issues/588) to
[#590](https://github.com/StationBreak/stationbreaktv/issues/590) (above), kid-channel repetition
[#591](https://github.com/StationBreak/stationbreaktv/issues/591), seasonal channels on out of season
[#592](https://github.com/StationBreak/stationbreaktv/issues/592), the "starts at 6 AM" copy
[#593](https://github.com/StationBreak/stationbreaktv/issues/593) and the builder preview
[#594](https://github.com/StationBreak/stationbreaktv/issues/594). Extras counts for the diagnostics view:
[#553](https://github.com/StationBreak/stationbreaktv/issues/553).

## Addendum (2026-11-15): the viewer's channels take no seats

Decided by the owner on 2026-11-15, as a v1 requirement from the 14-day trial ([#752](https://github.com/StationBreak/stationbreaktv/issues/752),
track G). Issues: the audit [#784](https://github.com/StationBreak/stationbreaktv/issues/784), the seat rule
[#785](https://github.com/StationBreak/stationbreaktv/issues/785), catalog gaps
[#786](https://github.com/StationBreak/stationbreaktv/issues/786).

This supersedes two things in section 1 and section 3: "The lineup keeps its cap of 50 (48 starters and the two leftovers
channels). Channels the viewer chose count first.", and the keeper bound of 50.

### The rule

The planner's own picks are capped at `PLANNER_PICKS` = 48. A channel the planner would not have picked costs no seat.
These cost none: custom channels, edited starters, opt-in pins, pinned starters outside the top 48 or with no score, and
the leftovers channels. The lineup is the planner's 48, plus those of the above that are on, plus the leftovers.
`CAP` (52, 48 plus the four Odds & Ends) remains only for ranked (non-selecting) catalogs and for the inference for older files.

**Ranking pins.** Each Selection round homes every title over all the candidates left, pinned and free. It scores every
qualifying candidate, pinned ones too, and puts them in one total order: by score, then `CHANNEL_ORDER` (ties to the lower
recipe number). A pinned starter inside the top 48 uses a seat. Outside it, or with no score, it sits on top. Free places
are 48 minus the pins inside the top, worked out again each round. Opt-in pins take part in homing but are never ranked.
All of it is integer and ordered, so it is deterministic. The rounds still settle. Two tests cover it: a scripted multi-round
test (round one drops the best free channel, round two lifts an outside-top pin into the top, where it takes the place of
the next free channel) and a one-round property test that checks the result against an independent ranking oracle and
that real homings take one round. The oracle reuses `channelScore` and `CHANNEL_ORDER`, so it pins the ranking rule, not
the settling.

**Homing is unchanged for pins.** A pinned starter still takes part in homing. One that takes titles from a planner's pick
can leave that pick too thin, and that pick goes to the shelf as "too thin".

**First choice and reset.** Room is `PLANNER_PICKS` and kept custom channels sit on top, so a reset and the next boundary
agree. A numbered leftovers channel is always on.

### Keepers and the catalog

`KEEPER_COPIES` = 60 replaces `CAP` for `previousKeepers` and `keeperRoom`. `MAX_CATALOG_STARTERS` goes from 120 to 110
(95 recipes today, plus the two leftovers). The per-copy budget stays 3,084 bytes, which is (1 MiB / 2) / (110 + 60). No
stored rules change on the day of the update.

Cost: the catalog has room to grow by 15 more recipes, not 25. A test holds `StarterCatalog.recipes.size <=
MAX_CATALOG_STARTERS` and the 3,084 budget. Past 60 starters on, a re-resolve can change today on the rest, as section 6
already says it can past 50.

### Determinism

[ADR-0002](0002-determinism-scope.md)'s scope is unchanged. An identical lineup file, selection and library still give
identical homes and days. Customs now leave the app's own channels identical to a TV without them. A switch-on outside the
top 48 differs per TV, as it already does today.

### Known effects

These are accepted.

1. **The first boundary after the update grows the lineup.** It adds one planner pick for each custom, edited, opt-in or
   outside-top pinned channel that is on. That includes the inference's pins of starters the older planner put on that no
   longer fill.
2. **Drift at the margin.** A touched pin (a renumbered or swapped starter, and every pin in a file from before the
   amendment of 2026-11-16) near rank 48 can move in or out of the top as the library changes. Out: it sits on top and the
   planner fills its seat. Back in: it takes a seat again and the 48th free pick leaves. A renumber that un-leaves a
   leaving pick makes an outside-top pin the same way. A starter the viewer switches on after the amendment is an
   addition, never ranked, and does not drift into a seat.
3. **A downgrade** to a build from before this change reads the lineup under the old rule (room = 50 minus pinned and
   leftovers) and shelves planner picks at the boundary.
4. **The shelf reason "below the cap"** (section 1, "Shelf reasons": it fits but stronger channels filled the lineup) now
   means only that the planner's own picks are stronger. The copy reads "Fits, but stronger channels were picked for you".
   Anywhere else this ADR says "below the cap" for the reason, read it this way.

### Amendment (2026-11-16): a starter the viewer switches on adds a channel

Decided by the owner on 2026-11-16. It corrects the root cause of the seat loss and reverses one decision of the design
doc ("no new field"). The design doc, `docs/design/lineup-seats-and-gaps.md`, "What a seat is", says why.

**The root cause.** Under the rule above a starter on the shelf that missed the room is already a free candidate: it is
homed and ranked outside the top 48 and costs no seat, so switching it on changed nothing. A seat was lost only when a
starter that was not a candidate before the switch-on entered the homing and the ranking and landed inside the top: a
hidden starter switched back on (it is no candidate while hidden), a kept starter (shelf, chosen by the viewer: switched
off before it aired, or renumbered), an untouched starter with no number (the switch-on gives it one), and a switched-on
pin that drifts into the top as the library changes.

**The rule.** The planner's room is a fixed `PLANNER_PICKS` (48) and a switch-on never reduces it. Each boundary it fills
48 from the starters the viewer has not added (opt-in starters excluded, as now). It falls short only when it runs out of
qualifying candidates: the pool less the added starters and the opt-in ones has fewer than 48 that qualify, or the homing
to the added starters thins some picks below the bar and no free starter is left to replace them. It never falls short to
make room for a switch-on. An **added** starter takes part in the homing, is never ranked, uses no seat and always sits on
top. So switching on N shelved, hidden or kept starters adds N channels, under the one stated condition above.

**The marks.** `LineupChannel.viewerChoice` is `None`, `Touched` or `Added` (`chosenByViewer` is computed from it).
`Touched` is what a renumber, a swap, a switch-off and an undo give: a ranked pin, a seat inside the top, none outside,
as before. `Added` applies to an unedited starter and means something only while it is on: switched off, it counts as a
kept starter (shelf) or as hidden, and keeps the mark. The edit rules, first match wins, for the viewer's switch-on of
an unedited starter:

1. an opt-in starter: `Touched` (it is already unranked);
2. an **undo**: the viewer's own switch-off is taken back before the boundary. Either the starter was on the air today
   and its switch-off recorded that (it is hidden with a leave time at the coming boundary, still ahead; this does not
   depend on the curation basis, which only a curated starter has), or it is a Shelf starter whose start is still ahead
   and has a mark. It comes back with `max(its mark, Touched)`, never `None`, so an accidental off and on of a pick of
   the planner's does not grow the lineup. For a **leaving** pick the switch-off gave `Added`, so undoing it equals
   keeping it: it stays on as an addition (a permanent one, until a reset). An older build's planner unpick (a Shelf
   starter whose time is still ahead), then renumbered and switched on before the boundary, reads as an undo and so as
   `Touched` until the boundary;
3. a planner pick already chosen for the boundary and shown as starting: `Touched`;
4. anything else, a shelf, hidden, kept or numberless starter or a leaving pick the viewer keeps: `Added`.

A mark only goes up through the viewer's edits (a renumber, a swap, a swap holder and a clear are `max(mark, Touched)`).
Switching off a pick that is leaving gives it `Added` while it is hidden, so the switch-on of the same day (an undo)
restores `Added`; at the boundary `Lineup.rolled` clears the leave time the switch-off set and lowers a hidden `Added` to
`Touched` (with no curation basis needed), and a switch-on after the boundary is an addition again by rule 4. Any other switch-off of an on-air or starting starter raises `None`
to `Touched`, as before. Marks never clear except on a reset (the starters are rebuilt, so only custom channels are kept),
when the recipe leaves the catalog (the channel becomes custom, which cannot hold the mark) and for that one-day lowering
of a hidden channel. There is no hand-back yet.

**No migration inference.** The marks of a file from before version 3 are still inferred as `Touched`, never `Added`
(owner decision). An old file's switch-on that later drifts into the top takes a seat as it did, and so does one made
before the update. The viewer can switch it off and on again on a later day, which makes it an addition (on the same day
it is an undo and changes nothing). If a later decision wants the inference to give `Added`, this ADR must
then state the growth it causes and the version-5 trigger, and a test must pin the size.

**Free, Pro and a refund (#793, #99).** Pro is a one-time purchase (#99), so Pro does not lapse; it can be revoked by a
refund. On free, switching a starter on is Pro (#793), so an `Added` channel can only exist for a household that has
bought Pro. Owner decision (2026-11-16) for a refund revocation:

- **The starters are reset by force.** The lineup returns to the planner's 48 picks plus the four Odds & Ends (52). The viewer's
  renumbers, hides, switch-offs and switch-ons, and every `Added` mark, are discarded, as in a viewer's reset (which keeps
  custom channels). They are not stashed: a viewer who buys Pro again re-edits the starters, which the owner accepts as
  a small inconvenience. A lineup with no `Added` mark writes version 3 or 4 again.
- **Custom channels beyond the first 5 are locked, not deleted.** Five stay usable, ranked by channel ID (ADR-0032: creation time is not stored, and no other rank is stable). The others stay
  in the lineup file as the household's data, but are hidden and cannot be edited or aired. Buying Pro again unlocks
  them. This refines #793's "a stored lineup with more than 5 customs keeps them": for a revoked household the extra
  customs are kept but inaccessible.
- Built by [ADR-0032](0032-purchase-switch.md) (#99) without a stored marker. The lock needed a stored marker or a rule over creation order (a new lineup-file field if
  stored, so a format-version decision), and the revocation reset needs the `ProEntitlement` port (#99). Both are to be
  designed with #99.

**The lineup file is version 5 for a household that switches a channel on** (and, for one day, one that switches a
leaving channel off). The channel's `addedByViewer: true` needs version 5; a lineup with no added channel stays version 3
or 4. The consequences, plainly:

- An older build reads a version 5 file as "from a newer app": it shows a planned default lineup and cannot save over it.
  Nothing is lost or misread; the older build is just unusable for that household while the file stays at version 5.
- The file leaves version 5 only when no channel holds the mark: after a reset (which drops every mark; the next save
  writes version 3 or 4), when the recipes leave the catalog, and after the boundary for a hidden channel.
- Before the first save of a higher version the file it replaces is kept under `<name>.v<from>-to<to>.bak` (`v3-to5`,
  `v4-to5`; the old `v1`, `v2` and `v3` tags are unchanged). The first copy written under a tag stays (first write
  wins), so a `-to` copy kept at a first 3 to 5 save can be months old after a reset back to 3 and a later 3 to 5 save.
  An older build's `forget` removes only the tags it knows, so it leaves the `v3-to5` and `v4-to5` files behind.
- **The copy moved to save time, and saves read the file when they must.** The copy of an older file is now made at the
  one place every save goes through (`LineupService.saveVerified`), from the version the service knows is on disk, and
  not at start. So the `v1` and `v2` copies of a version 1 or 2 file are made at the first save of version 3, not when the
  file is read. Where that version is not known (nothing was read, or a save failed or was cut off, so the file may be
  either), the save reads the file again first and refuses to write when the file is unreadable, from a newer app,
  another account's, or cannot be read: it reports the event and leaves the lineup unwritable for the session, as a
  start does. That is a behaviour change from saving blind.
- **The rollback cost is accepted (owner decision, 2026-11-16).** Version 5 is written on an ordinary switch-on and
  stays until a reset. A rollback to a build before this change leaves those households with an older app that cannot
  save: reset on the new build first, then downgrade; after a downgrade the only exit is `forget`. The owner weighed this
  and accepted it, and chose not to ship a release that reads version 5 first. The context is that every release today is
  local to the owner, for development and testing, with no public users, so the cost is accepted now. Revisit it before
  any public release.
- **Recovery, in this order.** On a downgraded build a version 5 file reads as newer, and a reset there changes nothing.
  So the supported way back is to **reset on the new build** (every mark drops, the next save writes version 3 or 4) and
  then downgrade. After a downgrade the only exit is `forget`, which deletes the lineup, custom channels included, and
  leaves the `-to` backups behind (a downgraded build's `forget` does not know their names). Restoring a `-to` backup by
  hand is possible and can be stale.
- **What a reset costs:** every starter renumber, hide and switch-on is lost; only custom channels are kept. A reset
  returns `Saved.Unavailable`, and changes nothing, when the library cannot be read.

**Determinism.** The mark is stored, so an identical lineup file, selection and library still give identical homes and
days (ADR-0002). The mark is not a day input: it changes no on-set by itself, so it never discards built days, and the
days digest and the day files are unchanged.

**Audit.** The audit builds the viewer's switch-ons with `LineupEdits.apply`, so it cannot drift from the edit rules, and
the `seat-household` profile gains three picks hidden and switched on again and two kept starters switched on again, beside
its five shelf starters and a custom channel (the planner's 48, ten added starters, the custom channel and the leftovers:
61 channels). Its golden changes for that reason only; the other six profiles are unchanged.

### Audit evidence

The #784 audit, from the planner's view. On the 6 existing profile libraries the rule changes nothing, because none has a
viewer's channel. On the new seat-household profile (invented titles; 5 starters switched on from the shelf and 1 custom
channel, a full lineup):

| Measure | Old rule | New rule |
| --- | --- | --- |
| Planner picks | 42 | 48 |
| Lineup size | 50 | 56 |
| Starters off | 48 | 42 |
| Titles with no home | 16 | 16 |

Six picks the old rule lost at the boundary are back on: prestige, order-in-the-court, code-blue, dead-drop, big-rumble and
kung-fu-theater.

### Open: pending the owner's device test session

**The days file, measured on the JVM** (`PersistedDaysSizeTest`, a generated library of 20,000 items, custom channels over
genre sources): 1,716 KB at 50 channels, 1,749 KB at 60, 1,799 KB at 75 and 1,881 KB at 100, against
`FileDayStore.MAX_BYTES` of 16 MiB. A curated 50-channel day is 2,402 KB; the curated file was not scaled to 100.

**Insignia timings** (AFTJMST12, API 25, 2026-11-15; a library-module test over a generated library of 33,000 items in the
real Room repository and `FileDayStore`; median of 3, against main at 50 channels on). The day build: +0.3% at 50 on the
branch, +3.2% at 60, +10.7% at 75 and +26.8% with every starter on (97). The day restore passes at every count (+5.1% at
97). The slope is about +0.3% per extra channel, so the build crosses the 10% bar at about 73 channels on (interpolated, not
measured). Not measured: guide open and sync-to-lineup-ready (they need the app, which is bound to a real server), heap, and
custom channels. On the generated library the planner switched on only 15 channels, so each count was reached by switching
starters on in lineup order.

**The warning is permanent.** The owner chose a heads-up that is always on the Channels panel, with no threshold: "The more
channels you turn on, the longer the guide takes to get ready." Nothing is shelved to make the guide faster. Since #799 it
shows only to viewers who can switch channels on (Pro); free viewers can't switch starters on, so they don't see it.

## Addendum 2026-11-21: the free on-set is 52

Owner decision ([#1002](https://github.com/StationBreak/stationbreaktv/issues/1002)): `PLANNER_PICKS` stays 48. Catalog 8
made Odds & Ends four always-on channels (33, 66, 99, 199) that cost no seat, so the free on-set is 48 + 4 = 52 and
`LineupDefaults.CAP` is 52. Earlier sections that say 50 describe the lineup before catalog 8; their measurements stand.
