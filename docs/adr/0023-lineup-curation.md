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
qualifies with 12 usable hours (its hours under the share cap) and at least 6 titles (4 for kids). A qualifying channel
scores `min(usable hours, 80)` in hundredths of an hour, plus 1500 per anchor up to 4, plus 100 per title up to 30. The
top scores fill the room, ties to the lower recipe number. The lineup keeps its cap of 50 (48 starters and the two leftovers
channels). Channels the viewer chose count first. There are at most 6 rounds, and a chosen channel that stopped
qualifying is dropped and the round repeats. (In practice removing channels only adds homes, so the loop settles at once.
A property test holds that claim, and the rounds stay as a safety net.)

**Shelf reasons.** Every starter off the lineup carries one reason, shown in Channels: nothing fits it (**empty**), too
little fits once every title has its best home (**too thin**), it fits but stronger channels filled the lineup (**below
the cap**), or it is only on if the viewer switches it on (**opt-in**). A thin channel comes back by itself when the
library grows.

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
  to the shelf (`leavesAtMs`). The first choice, which has aired nothing, switches on at once.
- **Per day, not per moment.** Whether a channel starts after, or has left by, a day is decided from the day
  (`LineupDay`), never from the moment someone asks. An earlier version decided by the moment, and a viewer switching a
  channel on in the middle of the day made one lookup cache an empty day. A day now gives the same answer to every
  lookup.
- **Viewer switches.** A viewer's own switch-on in the middle of a day airs from now. It is the viewer's wish and is
  pinned (never shelved by selection).
- **Play keys.** `LineupDays.playKeyOf(channel, lineup, atMs)` describes the day being played at `atMs`: the rules in
  force that day, the household flag in force that day, and (for a curated starter) the curation in force that day. A
  change waiting for the boundary moves no key until then. The background roll writes the new revision at the boundary.
- **Persisted days (format 6).** Saved days (`DaySnapshotCodec.VERSION` 6) carry the weights and the day pool after the
  trim window. A restored day is made with no keeps, so the window is applied once, when a fresh day is made. Days are
  keyed on the holds and on `curation-1`, so a hold or curation change rebuilds them.
- **The lineup file** is version 3. It adds the basis, previous rules, leave times, shelf reasons, the household flag,
  the viewer-choice marks and a re-plan time. Before the first save of an upgrade, the old file is copied to a `.v<old>.bak`
  file. An older app that finds a version 3 file shows a planned default lineup and cannot save. It never reads the copy.

Exceptions that apply at once: the back-to-back fix of an old file (applied at each TV's next start, so two TVs can
differ until both have started), a viewer's reset, the safety holds, and the edges listed in Costs.

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
  boundary it airs what the basis channels do not home. That can be nothing at all, and then it is hidden for the day with
  its reason. Both it and a basis channel can air the same title until the boundary.
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
