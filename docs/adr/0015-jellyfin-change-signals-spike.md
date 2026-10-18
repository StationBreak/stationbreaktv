# ADR-0015: Jellyfin change signals for collections and series (spike SP-5 result)

- **Status:** Accepted (spike requested by the owner; the sync strategy is a technical decision)
- **Date:** 2026-10-18
- **Decided by:** Rincewind, on the owner's request for the spike
- **Resolves:** spike SP-5 ([ROADMAP.md](../ROADMAP.md)); Open Questions 1 and 2 of spec 10 (library rule inputs)
- **Informs:** spec 10 units 1 and 2, R-JF-1

## Context

The app syncs incrementally with `GET /Items?...&MinDateLastSaved=<cursor>`. Jellyfin 12.x never returns
`DateLastSaved`; only the filter works. Spec 10 adds two more things to that pass: **BoxSets** (collections, with
their member lists) and **Series** (genres, tags, rating). It rests on two unverified assumptions:

1. A collection's membership can only change through something that bumps the BoxSet, so a periodic full re-list
   could be rare (24 h) once proven. Until then spec 10 defaults it to 6 h (Open Question 1).
2. Editing a Series bumps the Series itself (Open Question 2).

An earlier probe on a 12.1 container had found that adding or removing a member through the API bumps the
**BoxSet**, not the member, and that an episode's `Genres` can be empty while its series has genres. SP-5 tests every
other way a membership or a series can change, on both Jellyfin versions the contract-test CI jobs run.

## Spike

**Images.** Local images `jellyfin/jellyfin:12.1.20260915-010956` (server 12.1.0) and
`jellyfin/jellyfin:12.2.20261005-225228` (server 12.2.0). Their registry digests are the ones pinned by the
`jellyfin-contract` workflow (`sha256:008ec802...` for 12.1 and `sha256:c3efa69a...` for 12.2), so the results
apply to the CI targets.

**Harness.** [`scripts/spikes/sp5-change-signals.sh`](../../scripts/spikes/sp5-change-signals.sh) starts one
throwaway container on loopback (`--cpus 2 --memory 1.5g`, run under `nice`), completes the startup wizard through
the API with a generated throwaway admin, adds a Movies and a Shows library (internet metadata providers off, so
the synthetic titles stay as named), and runs every case below. It then removes the container and its volumes. The
media are tiny H.264/AAC files made with ffmpeg, in folders Jellyfin recognises as movies and as a series with two
seasons. Before each action the script records a timestamp, waits, acts, waits for the scan task and refresh queue
to go quiet, and then queries with `MinDateLastSaved` set to the timestamp (`Recursive=true`,
`collapseBoxSetItems=false`, one `IncludeItemTypes` per query). No owner server, credential or API key was used.
Re-run it against a newer image before raising any interval decided here.

"Returned" means the item's ID appears in the `MinDateLastSaved` query. "Bump" means the same.

## Results

Both versions gave identical results for every case.

### Collections (Q1)

| Case | 12.1 | 12.2 | Notes |
| --- | --- | --- | --- |
| (a) Add a member (`POST /Collections/{id}/Items`): BoxSet returned | yes | yes | The member item itself is not returned. |
| (a) Remove a member (`DELETE /Collections/{id}/Items`): BoxSet returned | yes | yes | The member item itself is not returned. |
| (a) Add a Series as a member: BoxSet returned | yes | yes | |
| (b) Refresh, replace-all options: BoxSet returned again | yes | yes | Membership unchanged (harmless extra read). |
| (b) Refresh, `Default` modes: BoxSet returned again | no | no | A no-op, no bump. Membership unchanged. |
| (b) Refresh, no parameters: BoxSet returned again | no | no | A no-op, no bump. Membership unchanged. |
| (b) Add, then a replace-all refresh, cursor from before the add: BoxSet returned | yes | yes | A refresh does not erase the add's bump, and the added member stays. |
| (c) Library scan, nothing changed: BoxSet bumped | no | no | No false positives. No movie, series or episode was bumped either. |
| **(d) Member file deleted from disk, then a scan: BoxSet bumped** | **no** | **no** | **The member silently leaves the member list.** The deleted movie is gone from the ID listing. A second scan also does not bump. |
| (d) Member deleted through the API (`DELETE /Items/{id}`): BoxSet bumped | no | no | Same: the member silently leaves the list. |
| (e) Automatic collections (TMDB box sets) | yes | yes | The container had internet access. A franchise library with the option on created one BoxSet holding both films, and the BoxSet was returned. No key or credential was given to the container. |
| (f) Deleted BoxSet is missing from `IncludeItemTypes=BoxSet` ID listing | yes | yes | |
| (g) Rename a BoxSet (`POST /Items/{id}`): BoxSet returned | yes | yes | |

### Series (Q2)

| Case | 12.1 | 12.2 | Notes |
| --- | --- | --- | --- |
| (a) Edit genres, tags and `OfficialRating` (`POST /Items/{id}`): Series returned | yes | yes | The new values are stored. |
| (b) Refresh, replace-all options: Series returned | yes | yes | Genres kept after the refresh. |
| (b) Refresh, no parameters: Series returned | no | no | A no-op. |
| (c) A series edit also bumps its episodes | yes | yes | All 3 episodes of the edited series were returned. |
| (d) New episode on disk, then a scan: Series returned | yes | yes | The new episode is returned too. |
| (e) Series deleted from disk, then a scan: gone from the ID listing | yes | yes | It is not returned by `MinDateLastSaved`, so only the ID listing shows it. Its episodes are gone from the listing too. |

Also seen: a new series has empty genres, tags and rating until edited, and an episode keeps empty `Genres` after
its series is given genres.

## Findings

1. **Every explicit membership edit bumps the BoxSet, but losing a member does not.** When a member is deleted
   (file removed and scanned, or `DELETE /Items`), the BoxSet is **not** bumped and the member **disappears from the
   member list**. An incremental pass can never see this on the BoxSet. The only trace is that the member leaves
   the item ID listing, which the app's item reconcile already reads.
2. A metadata refresh and a plain scan are safe. A refresh with replace-all options bumps the BoxSet again (a
   harmless extra read) and never loses a prior bump or changes membership. Defaults and no-parameter refreshes
   change nothing. A scan with no change bumps nothing.
3. Series edits are signalled by the Series itself, and they bump the episodes as well. New episodes bump the
   Series. A removed series shows only in the ID listing.
4. Collections created by Jellyfin's automatic box-set option behave like any other BoxSet. Membership changes
   made by a provider during a refresh were not exercised beyond that first creation.

## Decision

1. **Use `MinDateLastSaved` for BoxSets and Series as spec 10 describes.** Add `IncludeItemTypes=BoxSet` to the
   collection pass and `IncludeItemTypes=Series` to the series pass. Series metadata edits need no periodic
   re-list (Open Question 2 is settled: yes).
2. **A member removed by deletion is not signalled, so the item reconcile drives it.** The sync must not rely on
   the BoxSet bump to notice a lost member. Whenever the item reconcile (or any pass) tombstones an item, the
   collection pass must, in the same sync, **drop that item from every collection that held it** (its current
   `collection_member` rows are known locally, so no extra call is needed to find them). It may optionally
   re-read those collections' member lists, under the existing guards, to confirm. A tombstoned item must never be
   returned as a member by `ruleSnapshot`, whatever the member rows say.
3. **A periodic full re-list stays, as the safety net for anything not tested here** (a member hidden from the
   user without being deleted, a provider changing membership, a future Jellyfin version). **The default stays at
   6 h (`collectionRelistInterval`).** It may be raised to 24 h only after the member cascade in 2 has shipped and
   its contract test passes, because from then on the one known silent path no longer depends on the re-list.
4. **The deletion listing for BoxSets is required** (Finding f): a deleted BoxSet is visible only in the ID listing.
5. Metadata refreshes and scans need no special handling: extra BoxSet reads from a replace-all refresh are
   harmless and a scan produces no false positives.

## Consequences for spec 10

- **Open Question 2** is closed: a Series edit bumps the Series (and its episodes), so series metadata needs no
  re-list. `ContractSuite.seriesMetadataArrives` still checks it on 12.1 and 12.2.
- **Open Question 1** is closed with a caveat: the bump survives a refresh and a scan, but a **deleted member is not
  signalled at all**. Unit 2 gains a requirement: tombstoning an item removes it from the current member list of
  every collection that held it, in the same transaction as the tombstone, and `ruleSnapshot` excludes tombstoned
  members. The default for `collectionRelistInterval` stays 6 h, and the condition for raising it becomes the one
  in Decision 3.
- `ContractSuite.collectionChangesDetected` is extended to cover (d): after a member is deleted, the BoxSet is
  **not** expected in a `MinDateLastSaved` query, and the member is expected to be gone from the item ID listing
  and from the collection's member list. A test failure there on a future version means the harness result changed
  (and the cascade could be relaxed).
- Call cost is unchanged: the cascade uses local rows, and an optional confirming re-read is one member call per
  affected collection.
- The harness is re-runnable on any Jellyfin image (`scripts/spikes/sp5-change-signals.sh`), so the next 12.x
  minor can be checked before the contract-test matrix moves.
- **Not covered:** items hidden from the user by parental controls or library access without being deleted,
  provider-driven membership changes during a refresh, and nested BoxSets in a collection (spec 10 skips them).
  The periodic re-list is the net for these.
