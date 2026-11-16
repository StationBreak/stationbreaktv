# ADB commands for test sessions

Commands the debug and benchmark builds answer to, for the sessions that drive a TV over ADB. The release builds
contain none of them (`BackupRulesTest` checks the merged release manifests).

## Rebuild today's schedule now

Without this, a change to the scheduler or planner shows only at the 06:00 boundary. The command plans the signed-in
account's lineup now as the boundary that began today would have (the same seat rule and catalog, with every change the
plan makes, or an earlier plan left waiting for tomorrow, applying from the start of today), then drops every built day
(in memory and on disk) so today's schedule is built again for every channel. The viewer's own edits, custom channels
and switch-ons are kept: it is not a Reset. The channel playing picks up the new schedule.

One consequence to know when reading results: a change the viewer made today (a switch-on, a switch-off, "Use my
collections") leaves a pending note for tomorrow's boundary, and the rebuild drops that note, so the viewer's choices
also take effect for today's schedule, as they would after the real boundary. The edits themselves stay as they were
(a hidden channel stays hidden and still leaves at tomorrow's boundary).

The app must be installed and signed in, and **in the foreground**: start it first and leave it on screen until the
command is done (a force-stopped app does not get broadcasts, and the work is built to run with the app open).

| Build     | Package                       | Command                                                                                              |
|-----------|-------------------------------|------------------------------------------------------------------------------------------------------|
| debug     | `tv.stationbreak.app.debug`     | `adb shell am broadcast -a tv.stationbreak.debug.REBUILD_TODAY -p tv.stationbreak.app.debug`         |
| benchmark | `tv.stationbreak.app.benchmark` | `adb shell am broadcast -a tv.stationbreak.debug.REBUILD_TODAY -p tv.stationbreak.app.benchmark`     |

The receiver is protected by `android.permission.DUMP`, which the ADB shell holds and an ordinary app cannot get.
`am broadcast` printing `Broadcast completed: result=0` only means it was delivered; the work runs on after it.

### Waiting for it to finish

The command reports under one logcat tag, `SB-Rebuild`. Every line it can write:

| Line                                    | Meaning                                                                   |
|-----------------------------------------|---------------------------------------------------------------------------|
| `started`                               | The command was received and the rebuild began.                           |
| `done in N ms`                          | The lineup is planned from today's start and the old days are dropped.    |
| `skipped: nobody is signed in`          | No active account. Nothing was done.                                      |
| `skipped: no libraries are chosen`      | The account has no chosen libraries. Nothing was done.                    |
| `skipped: the account has no lineup`    | The account's IDs are not canonical, so it has no lineup. Nothing done.   |
| `failed: <exception class>`             | The rebuild threw. The class name only; nothing else is logged.           |

Clear the log, fire the command, and wait for a line that is not `started`, **with a time limit**. The rebuild is a
plan plus a drop, a few seconds on a Firestick and longer on the Insignia; give it 120 s on the Insignia and treat no
`done` by then as a failure of the run, not as a slow one:

```sh
adb logcat -c
adb shell am broadcast -a tv.stationbreak.debug.REBUILD_TODAY -p tv.stationbreak.app.benchmark
timeout 120 adb logcat -s SB-Rebuild:I | grep -m1 -E 'done in|skipped|failed'
```

`done` means the lineup is planned and the old days are gone. The days themselves are built on the next tune or by the
schedule warmer, so the first tune after it is a cold one (watch the `DayBuild` lines under the `Lineup` tag).

Each TV computes its own schedule, so after a mid-day rebuild on one TV it can differ from the others until the next
boundary. That is acceptable for a test-only command.
