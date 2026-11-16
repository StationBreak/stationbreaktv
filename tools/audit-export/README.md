# The lineup audit and its exporter

The audit runs the real lineup planner and curation on a library snapshot and explains the result: where each title
lives, why a channel is on the shelf, how much channels overlap, and which titles have no home. It is the yardstick for
the seat rule and the catalog gaps (`docs/design/lineup-seats-and-gaps.md`, section 2, from the design PR) and a
stand-in for someone else's server.

- **The audit** is a `jvmAudit` compilation of `core:engine` (it sees the module's `internal` code) and a Gradle task.
  There is no new module and no public API; nothing in the app, the feature modules or the published jar can call it.
- **The exporter** (`export.py`) reads a Jellyfin server and writes a snapshot file the audit reads. It is run by hand on
  your own machine.
- **The format** of the snapshot, and the app's acceptance rules the exporter mirrors, are in [FORMAT.md](FORMAT.md).

## Scope: the planner's view

The audit runs `LineupPlanner.plan` and the curation as the planner does, on the snapshot after the extras filter. It does
**not** apply the rating ceilings or the late-on pools of a built day (`LineupDays.curationChannelOf`), and every report
says so. What a day airs is out of scope.

## Run the audit

```bash
# A profile library (invented titles, generated in code): sitcom-household, horror-fan, kids-household,
# anime-viewer, broad-1900, thin-200, or all.
./gradlew :core:engine:lineupAudit -PauditProfile=sitcom-household

# A snapshot file, with the report written to a folder (KEY.txt and KEY.json), or the JSON to the terminal.
./gradlew :core:engine:lineupAudit -PauditSnapshot=/path/to/audit-snapshot.json -PauditOut=/path/to/reports
./gradlew :core:engine:lineupAudit -PauditSnapshot=/path/to/audit-snapshot.json -PauditFormat=json
```

`-PauditFormat` is `text` (the default), `json` or `both` (with `-PauditOut`). The output is deterministic: the same
snapshot gives the same bytes. Every report has, per title, its home, runner-up, both scores by term and guest spots, or
why it has no home; per starter, on or off, its shelf reason, usable hours, titles, anchors and selection score; per
pair of channels that are on, the titles both air and the hours; and totals (titles with no home by cause, titles per
channel, channels per title). The JSON layout is described at the end of [FORMAT.md](FORMAT.md).

## The profile libraries and their pinned output

Six libraries generated in code from invented titles (`core/engine/src/jvmAudit/.../audit/`, `SmallProfiles.kt` and
`BroadLibrary.kt`): a sitcom household, a horror fan, a kids' household, an anime viewer, a broad 1,900-title library and
a thin 200-title server. Their titles are two made-up words from a fixed list of syllables: none is, or is meant to
resemble, anything real, and a test holds every title to it. Each carries the cases it is there to test: an adult
animated sitcom with no studio match, a small horror shelf, a sitcom with an extra genre.

The audit's output on each is **pinned** in `core/engine/src/audit/golden/KEY.txt` and `KEY.json`, and
`:core:engine:jvmAuditTest` (part of `check`) compares them byte for byte. A change to the planner, the curation, the
catalog or the audit that moves them shows as a diff of those files. After reading the diff, make them again:

```bash
./gradlew :core:engine:lineupAuditGoldens
```

## Export your own library

The exporter reads the server address and the API key from **environment variables only**, never from an argument (it
would land in your shell history) and never from a file in the repository. It writes the snapshot to a folder **outside
the repository**, and refuses a folder inside it or inside any git work tree.

```bash
export SB_AUDIT_SERVER='https://ADDRESS'   # your server's address (an http address only for this machine or network)
read -rs SB_AUDIT_API_KEY && export SB_AUDIT_API_KEY   # an API key from the server's dashboard, typed, not echoed
export SB_AUDIT_OUT="$HOME/stationbreak-audit"         # optional: the default is ~/.local/share/stationbreak-audit
python3 -I tools/audit-export/export.py                # --libraries ID,ID to read some libraries only
```

It prints the path of the file, `audit-snapshot-<UTC time>.json` (readable by you only), and counts: items, series and
collections written, and how many were left out for each reason. It prints no key, address, title or name. The file
holds titles: it stays on your machine. `.gitignore` keeps a stray copy out of a commit, but do not rely on that.

The server's answers are treated as untrusted: each is read to a size limit, parsed strictly (valid UTF-8, no duplicate
key, bounded nesting), a field of the wrong type fails the export, nothing from an answer goes into a path or a header,
and a redirect is refused (so the key never travels to another address).

## Tests

```bash
python3 -I -m unittest discover -s tools/audit-export -p '*_test.py'   # the exporter, against a fake server on 127.0.0.1
./gradlew :core:engine:jvmAuditTest                                    # the audit, the format, the profile libraries
./gradlew :data:library:testDebugUnitTest --tests '*AuditExportFixtureTest*'
```

The **fidelity test** has three parts over one set of invented fixtures in `fixtures/`: `server.json` (what a fake
server answers), `rows.json` (the rows a cache would hold for those answers) and `expected-snapshot.json` (the snapshot).
The exporter's test runs the exporter against the fake server and holds its output to `expected-snapshot.json`;
`AuditExportFixtureTest` in `data:library` gives the app's own `ScheduleInputs`, `RuleItemMapper` and `RuleInputs` the
rows and holds what they make to the same file, with the same count of what was left out for each reason; and
`ExporterFixtureTest` in `core:engine` reads the file with the audit's codec. The audit is a pure function of the
snapshot, so equal snapshots give equal audits. The fixtures hold no real title and no address. `make_fixtures.py` makes
them again (read the diff: the independent check is the `data:library` test).
