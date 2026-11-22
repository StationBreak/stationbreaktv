# Rating-safety fixtures (#576, #987, #992, #1078)

These tests hold the five audience tiers (kids, family, teen, adult, explicit; [ADR-0028](../../../../../../../../../../docs/adr/0028-rating-tiers.md))
to the rule that a leak may only fail upward, toward explicit.

- `NeverInKids.kt`: the #992 seed list. Titles with a rating, tags, genres and studios, and no media. Each has a
  metadata-stripped copy. Do not add other real titles. Use the synthetic library.
- `SyntheticLibrary.kt`: a deterministic library built from every combination of a set of ratings, tags, genres and
  studios, as movies, episodes of a known series, episodes with a missing series and episodes with no series.
- `RatingSafetyLeakTest.kt`: the leak checks, and the pinned tier counts of the synthetic library.
- `SyntheticAdultSlice.kt`: the SYNTHETIC adult slice (#1078): invented titles with plugin-style metadata for every
  title the Explicit tier must catch and every control it must leave alone, and one file with no metadata. Invented
  titles only, never real ones.
- `ExplicitTierTest.kt` and `ExplicitPoolTest.kt`: every Explicit trigger alone, the Adult controls, and where the slice
  may air (After Hours alone for Explicit, no young pool for Adult, no Odds & Ends 199 for no metadata).
- The rating map and the tier rule also have shared vectors in `docs/rules-format/vectors/kid-hold-6.json`, for the
  `kid-hold-7` moves up `kid-hold-7.json`, and for the `kid-hold-8` Explicit tier `kid-hold-8.json`.

## Rebuilding a real mock library (by hand, not in CI)

A mock library tests what stock scrapers really return. It needs no API keys in StationBreak and no scripts in this
repository.

1. Start a throwaway Jellyfin server on a machine of your own (a container is fine). Never point this at a real
   household server.
2. Install the stock TMDb and TVDb metadata plugins. Leave the AniDB plugin off, because bulk scraping gets you
   banned. Anime is then matched through TMDb and TVDb, the way most servers do it.
3. Take a broad title sample from Wikidata (public domain, no key needed): films and series from 1940 to 2026,
   including unreleased titles. Add the titles from `NeverInKids.kt`.
4. For each title, create an empty placeholder file named like a real release (`Title (Year).mkv`, or
   `Show/Season 01/Show - S01E01.mkv`).
5. Add the folders as a Movies and a Shows library. Throttle the scan (for example, one library at a time and a low
   number of parallel metadata fetches), and wait for it to finish.
6. Point a debug build of the app at the server, or run the lineup audit (`core/engine`, `jvmAudit`) on a snapshot of
   it, and report:
   - the tier counts (kids, family, teen, adult);
   - every title in kids or family that has an explicit, sexual or gore tag, or no rating (each one is a leak);
   - the never-in-kids titles and where they landed (all must be adult, Sleepers at worst teen);
   - the rating strings that mapped to unknown, with their counts.
7. Compare with the previous run. Counts move when the library or the rules change. A leak toward kids is always a
   failure.
8. Delete the throwaway server when you are done.
