# ADR-0033: Hours of operation (Pro), domain only

- **Status:** Accepted, domain and storage only: no screen, string or enforcement yet
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

## The seam (not wired)

`HoursOfOperation.audience()` is a `Flow<AudienceLevel>` for the UI and the planner. `AudienceLevel.cap` maps a level to
the `KidsCap` that shows the same channels, so the Kids lock's filters (`KidsLockedChannelRepository`,
`KidsLockedScheduleSource`) can take it later by reading the effective cap instead of `KidsLockState.cap`; Off Air has
no cap and is shown by the UI as its own state. Nothing in the app binds or reads it yet.

## Not here

The bedtime card, holiday and sick days, every screen and string, and the enforcement in the player and guide.

## Addendum (2026-11-23): product names ([#1101](https://github.com/StationBreak/stationbreaktv/issues/1101))

This record keeps the names of its date. Since [#1100](https://github.com/StationBreak/stationbreaktv/issues/1100) the
1 to 99 setting is called **Family Mode** (it was Kids Mode), and its bands are **Tots** (1 to 33, was the Kids band),
**Kids** (34 to 66, was the Family band) and **Teens** (67 to 99, was the Teen band). Where this record says Kids Mode,
the Kids band or the Family band, read Family Mode, Tots and Kids. Code names, `kid-hold-*`, `kidsOnly`, the
`kids-odds-and-ends` key and stored values keep the old words. See the [glossary](../guide/glossary.md).
