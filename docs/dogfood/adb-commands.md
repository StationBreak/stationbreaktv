# ADB commands for test sessions

Commands the debug and benchmark builds answer to, for the sessions that drive a TV over ADB. The release builds
contain none of them (`BackupRulesTest` checks the merged release manifests).

## Rebuild today's schedule now

Without this, a change to the scheduler or planner shows only at the 06:00 boundary. The command plans the signed-in
account's lineup now (the planning the boundary runs, with the same seat rule and catalog), then drops every built day
(in memory and on disk) so today's schedule is built again for every channel. The viewer's own edits, custom channels
and switch-ons are kept: it is not a Reset. The channel playing picks up the new schedule.

The app must be installed and signed in. Start it once first (a force-stopped app does not get broadcasts).

| Build     | Package                       | Command                                                                                              |
|-----------|-------------------------------|------------------------------------------------------------------------------------------------------|
| debug     | `tv.stationbreak.app.debug`     | `adb shell am broadcast -a tv.stationbreak.debug.REBUILD_TODAY -p tv.stationbreak.app.debug`         |
| benchmark | `tv.stationbreak.app.benchmark` | `adb shell am broadcast -a tv.stationbreak.debug.REBUILD_TODAY -p tv.stationbreak.app.benchmark`     |

The receiver is protected by `android.permission.DUMP`, which the ADB shell holds and an ordinary app cannot get.
`am broadcast` printing `Broadcast completed: result=0` only means it was delivered; the work runs on after it.

### Waiting for it to finish

The command reports under one logcat tag, `SB-Rebuild`, with these lines:

- `started`
- `done in N ms`
- `skipped: nobody is signed in` or `skipped: no libraries are chosen`
- `failed: <exception class>` (the class name only; nothing else about the failure is logged)

Clear the log, fire the command, and wait for a line that is not `started`:

```sh
adb logcat -c
adb shell am broadcast -a tv.stationbreak.debug.REBUILD_TODAY -p tv.stationbreak.app.benchmark
adb logcat -s SB-Rebuild:I | grep -m1 -E 'done in|skipped|failed'
```

`done` means the lineup is planned and the old days are gone. The days themselves are built on the next tune or by the
schedule warmer, so the first tune after it is a cold one (watch the `DayBuild` lines under the `Lineup` tag).

Each TV computes its own schedule, so after a mid-day rebuild on one TV it can differ from the others until the next
boundary. That is acceptable for a test-only command.
