# ADR-0002: Determinism scope — same account, same lineup

- **Status:** Accepted (hash-keyed ordering approved by Granny (Architecture) with conditions, now incorporated)
- **Date:** 2026-10-04
- **Decided by:** Phil Critchfield

## Context

The spec says a schedule derived from (rules + library snapshot + seed + time) is the same on every device.
Jellyfin enforces per-user library access, parental rating limits, and blocked tags **on the server**, so
different accounts see different libraries. A kids account can't see *M\*A\*S\*H*, so it can't schedule it.
"The same everywhere, regardless of user" isn't achievable without an admin-level view, which ADR-0001 rules out.
It also isn't desirable: the kids' Boomerang shouldn't air MASH.

## Decision

1. **Scope: the same account on the same server gets the same lineup on any device, anywhere.**
   Two accounts with identical visibility within a channel's scope also get identical lineups.
   Accounts with different visibility get different lineups. That's by design.
2. **Channel rules set the content limits.** Rules may declare a rating ceiling (e.g. `maxRating: "PG-13"`)
   and library/tag scopes. Account limits (Jellyfin parental controls) can only narrow this further.
   They are never bypassed.
3. **Hash-keyed ordering** (replaces plain Fisher–Yates for `SHUFFLE` and series selection in
   `RANDOM_IN_ORDER`, keyed on series ID): sort items by `key = splitmix64(seed_n + fnv1a64(canonicalId))`,
   compared **unsigned**, with ties broken by canonical ID bytes. Canonical ID = 32 lowercase hex chars, no
   hyphens. Arithmetic wraps mod 2^64. Removing an item (hidden from a kids account, deleted, filtered) just
   drops it from the order, and every other item keeps its relative position. Later start times shift by
   that item's runtime, so the result is a recognisably similar *order*, not similar times. Adding items
   disturbs the order minimally. Exact primitives are in BACKLOG S1.1.3 and `docs/rules-format.md`.
4. **Watch-state filtering** (`skip watched`, F3.5) is allowed. The lineup stays the same across one
   account's devices but changes as the user watches.
5. **There's no blackout/substitution model.** Content a user can't see is never scheduled for them.

## Consequences

- The "two devices agree" check is **removed from the Phase 1 gate**. It becomes a non-blocking observation
  during the Phase 2 dogfood, with two devices on the **same account**. Golden vectors in CI still prove
  same-input → same-output.
- The rules format (F0.7) gains a `maxRating` field and a documented rating-order table.
- Custom channels must travel with the account for "same lineup at Grandma's" to hold → Q-SYNC-1.
