# ADR-0027: Writing rules format 5 (the spread day end): the release rule

- **Status:** Accepted, 2026-11-13
- **Date:** 2026-11-13
- **Decided by:** the owner ("ship now and accept", 2026-11-13)
- **Amends:** [ADR-0026](0026-studio-fold-2.md) (its release gate, for format 5 only) and
  [ADR-0022](0022-rules-format-v2.md) (the lowest version, for new channels only; see its addendum)
- **Builds on:** [ADR-0002](0002-determinism-scope.md)
- **Informs:** spec `docs/superpowers/specs/2026-11-13-spread-day-end-design.md`; [docs/rules-format.md](../rules-format.md)

## Context

Rules format version 5 is fold 2 plus the spread end of the day. A file that says 5 spreads the time left at the end of
a broadcast day through the day as station breaks, and matches studios under studio fold 2 as a format 4 file does. A
file that says 1 to 4 gathers that time at the end, as before. Version 5 is a behaviour version, not a feature version:
it adds no field, and `ChannelRules.requiredVersion` never reports it.

The owner chose which channels move (spec, "Which channels move"): unedited starters, through a catalog bump, and every
channel created in the app from now on. Existing custom channels, edited starters and imported files keep their
version, even when saved again.

ADR-0026 sets a release gate for a new format: every port reads it, and every TV in the household runs a version that
does, before any release writes it. The Vega OS and Roku ports have no release, and checking every TV takes time.

## Decision

**Writing 5 is an Android-only exception to ADR-0026's release gate.** The Fire OS build that reads 5 also writes it.
It ships without waiting for the Vega OS or Roku ports, and without a check of every TV in the household. The
household's Fire TVs switch when they take the update.

Conditions:

- The release that writes 5 (the spread day end's Task 6) ships in the same release as the code that reads and lays out
  5 (Tasks 2 to 5), or after it, never before: a build that writes 5 must read it.
- It never ships before ADR-0026's read-4 code: it builds on ADR-0026's `ChannelRules.writeVersion`.

What is written at 5:

- **New channels** (made in the builder from scratch, or copied from a built-in channel) are written at 5: a floor of
  5 over `writeVersion`. An edit of a custom channel keeps `writeVersion`, so a saved channel keeps its version.
- **Unedited starters** are resolved at 5 (the same floor), and a starter resolved again keeps the higher of its stored
  version and the written one. `StarterCatalog.VERSION` goes up (to 4), so the planner resolves every unedited starter
  again: one on the air (a keeper) switches at the next broadcast-day boundary, any other at once.
- `writeVersion` never lowers a file that says 5.

**Fold 2 arrives early through 5.** Because every v5 file is matched under studio fold 2, the Android build brings fold 2
to studio starters and to new or copied studio channels ahead of ADR-0026's gate, whatever `StudioFold2Gate` says. That
is a second route around ADR-0026's gate, accepted on purpose; for those channels it also closes the fold mismatch
between a hold and an exclusion that ADR-0026 accepts until its upgrade. Existing custom studio files keep their version
and fold. This exception does not change ADR-0026's own gate for writing 4.

## Consequences

The cost the owner accepts:

- **A different day on an updated TV.** Lineups are per TV, and channel sharing is deferred (`docs/ASSUMPTIONS.md`,
  Q-SYNC-1 withdrawn). So the cost a household sees during the update window is that the same starter gives a different
  day on an updated TV (spread) than on one not yet updated (gather). ADR-0002 allows this: the app version is an input
  to a day.
- **An older reader.** A reader that stops at 4 (an older Fire OS build, or the Vega OS and Roku apps until they catch
  up) sees a v5 file only after a downgrade, a restore or an import. It then shows that channel as unusable ("update
  required", off the air) and keeps it byte for byte until it is updated. It fails closed: nothing is lost.
- The Vega OS and Roku ports must read 5 before their first release, as they must read 4 (ADR-0026).
