# ADR-0033: Hours of operation (Pro), domain only

- **Status:** Accepted. Domain and storage; the guide and the player follow the schedule since the 2026-11-23 addendum
- **Date:** 2026-11-21
- **Decided by:** the owner ([#789](https://github.com/StationBreak/stationbreaktv/issues/789): hours of operation is a
  Pro schedule, per TV, PIN Kids mode and the levels are free) and Rincewind (the mechanism)
- **Builds on:** [ADR-0028](0028-rating-tiers.md) (the four tiers), [ADR-0023](0023-lineup-curation.md) (lock, never
  delete), [ADR-0032](0032-purchase-switch.md) (the Pro gate)

## Decision

1. **A weekly schedule of periods** (`HoursSchedule`, `core:domain` package `hours`). A period is a day of the week, a
   start and an end minute of the local day, and a level: Kids, Family, Teen, Adult (ADR-0028) or Off Air. An end at or
   before the start runs overnight into the next day; a period belongs to the day it starts on; start equal to end is
   refused. Periods may overlap: the most restrictive wins (Off Air, Kids, Family, Teen, Adult). No period covering a
   moment means no hours for it. An empty schedule is no schedule.
2. **A pure lookup.** `levelAt(dayOfWeek, minuteOfDay)` takes local wall-clock values only. `HoursOfOperation` reads
   the TV's clock and zone at each tick (every minute), so a DST change or a new time zone needs no special case: the
   edge is a local time, and a skipped or repeated local hour just is or is not inside a period.
3. **Effective level** (`effectiveAudience`): the more restrictive of the period and the Kids lock's cap (a lock that is
   off is no limit). **The PIN overrides the period**: with the lock off and a PIN set (the grown-up has entered the
   PIN), the answer is Adult, even in an Off Air period, for as long as the lock stays off (the free idle fallback turns
   it on again after the idle time). The caps are the existing `KidsCap` values; no new rating logic.
4. **Pro gate.** With Pro off the schedule is inert (no period applies, so the free behaviour is unchanged) and
   `edit` returns `ProRequired`. What is stored is never deleted or rewritten by a refund; it applies again when Pro
   returns. `ProEntitlement.pro` is read at each check. Editing also needs a PIN set (`NoPin`), because without one
   nobody could override an Off Air period.
5. **Storage** (`DataStoreHoursStore`, `data:settings`): a DataStore file of its own in `noBackupFilesDir` (per TV,
   never backed up), one string in the `HoursCodec` JSON format with a version field (`{"v":1,"periods":[...]}`).
   Absent, damaged, newer-version or out-of-range input reads as no schedule, and never throws. The file is left alone
   until the viewer saves, so a newer version's data survives a downgrade until then.

## The seam (wired: see the 2026-11-23 enforcement addendum)

`HoursOfOperation.audience()` is a `Flow<AudienceLevel>` for the UI and the planner. `AudienceLevel.cap` maps a level to
the `KidsCap` that shows the same channels, so the Kids lock's filters (`KidsLockedChannelRepository`,
`KidsLockedScheduleSource`) can take it later by reading the effective cap instead of `KidsLockState.cap`; Off Air has
no cap and is shown by the UI as its own state. As first written, nothing in the app bound or read it.

## Not here

The bedtime card, holiday and sick days, every screen and string, and the enforcement in the player and guide.

## Addendum (2026-11-23): product names ([#1101](https://github.com/StationBreak/stationbreaktv/issues/1101))

This record keeps the names of its date. Since [#1100](https://github.com/StationBreak/stationbreaktv/issues/1100) the
1 to 99 setting is called **Family Mode** (it was Kids Mode), and its bands are **Tots** (1 to 33, was the Kids band),
**Kids** (34 to 66, was the Family band) and **Teens** (67 to 99, was the Teen band). Where this record says Kids Mode,
the Kids band or the Family band, read Family Mode, Tots and Kids. Code names, `kid-hold-*`, `kidsOnly`, the
`kids-odds-and-ends` key and stored values keep the old words. See the [glossary](../guide/glossary.md).

## Addendum (2026-11-23): enforcement ([#789](https://github.com/StationBreak/stationbreaktv/issues/789))

The seam is wired. The two Kids lock filters and live TV no longer read the Kids lock alone: they read a `KidsLockView`
(`KidsLock` is one), and the app binds `AudienceLockView`, whose state is the effective `HoursOfOperation.audience()`
mapped back to a `KidsLockState`: a level with a cap is the lock on at that cap, Adult is the lock off, and Off Air is
the lock on with `offAir` set, which `allows` no channel. Nothing new filters, so the lock-never-delete rule of
ADR-0023 holds: channels are left out of the lists, not removed, and return when the period ends.

- **The guide and schedule** (`KidsLockedChannelRepository`, `KidsLockedScheduleSource`) read it at each call, and the
  lineup re-emits at the minute a period edge is crossed, because `audience()` looks again each minute.
- **The player** uses the Kids lock's own mechanism (`followKidsLock` in `LiveChannel`): a playing channel the audience
  no longer allows is left for the first allowed channel. In Off Air nothing is allowed, so playback stops and live TV
  shows the existing off-the-air ident card (no new strings); a period ending, or the PIN, tunes again.
- **The PIN** keeps overriding (decision 3) through the whole path, because `effectiveAudience` answers Adult for a lock
  that is off with a PIN set: the filters then show everything, Off Air included. While Off Air shows and the lock is
  on (the idle fallback), the grown-up reaches Settings through the guide's menu and turns the lock off with the PIN.
- **Free** is unchanged: with Pro off, or no schedule, `audience()` is the lock's own level.

Still not here: the bedtime card, holiday and sick days, and any change to the screens.
