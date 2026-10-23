# Playback matrix tooling (spec 13)

Runs a spread of files through the player on a real device and renders the result as
`docs/compatibility/playback-matrix.md`. The tooling is for a laptop attached to the device; CI only runs its unit tests
(and that is held for the owner, so for now `scripts/preflight.sh` runs them).

| File | What it does |
| --- | --- |
| `synthetic-media.sh` | Writes the synthetic clips (test patterns and sines, nothing from anywhere). Used by the contract suite and by `temp-server.sh`. |
| `temp-server.sh` | `up` / `down`: a throwaway Jellyfin of those clips, for the synthetic section. Set up on loopback, then published on the LAN address you give it. |
| `run.py` | `plan` chooses start points; `run` plays a section on a device through `PlaybackTestActivity` and keeps its result lines. |
| `render.py` | Turns result files into the public markdown, refusing anything that could name a home network or a library. |
| `*_test.py`, `playback-matrix.test.sh` | The tests. `bash scripts/playback-matrix/playback-matrix.test.sh` runs all of them. |

Item IDs, plans and raw results are local: `items.local.json`, `plan*.json` and `results/` are gitignored. Never commit
or paste them.

## A device run

You need the **benchmark** APK, the device on adb, docker, curl and jq on the laptop, and the device on the same LAN.

Build the APK locally, from a commit whose CI run is fully green, with `./gradlew assembleGithubBenchmark`. CI does not
assemble it (it is never published); `scripts/preflight.sh` does, so it at least compiles. Record the APK's `sha256sum`
with the run, and install it with a plain `adb install` (its application ID is `tv.stationbreak.app.benchmark`).

**The benchmark app is a test entry point.** Its `PlaybackTestActivity` is exported, so any app on the device can start
it, and it plays with whatever account the app is signed in to. It refuses to run unless USB debugging is on (it logs
`ERROR adb-disabled` and finishes), but that is no reason to leave it installed. Remove it when you are done, as below.

```
# 1. The synthetic server on the LAN interface the device can reach (prints the address and a password, once)
scripts/playback-matrix/temp-server.sh up 192.0.2.10

# 2. On the device: open the benchmark app, connect to the printed address (accept the cleartext notice), sign in
#    as admin with the printed password.

# 3. Plan, then run the synthetic section
python3 -I scripts/playback-matrix/run.py plan --seed 1 --items scripts/playback-matrix/items.local.json \
  --out scripts/playback-matrix/plan.json
python3 -I scripts/playback-matrix/run.py run -s SERIAL --plan scripts/playback-matrix/plan.json --section synthetic

# 4. Remove the app, then the server (on the throwaway server there is no session worth revoking):
adb -s SERIAL uninstall tv.stationbreak.app.benchmark
scripts/playback-matrix/temp-server.sh down

# 5. Real items: put 8 to 10 of the owner's items into items.local.json under "real" (id, runtimeMs, and optionally
#    audio / subtitle track indexes), reinstall the same benchmark APK, sign it in to the owner's server, then
adb -s SERIAL install path/to/app-github-benchmark.apk
python3 -I scripts/playback-matrix/run.py plan --seed 1 --items scripts/playback-matrix/items.local.json \
  --out scripts/playback-matrix/plan.json
python3 -I scripts/playback-matrix/run.py run -s SERIAL --plan scripts/playback-matrix/plan.json --section real

#    Then uninstall it, and revoke its session on the owner's server: uninstalling deletes the token on the device,
#    but the server keeps the session until its device entry is removed (Dashboard > Devices, "StationBreak").
adb -s SERIAL uninstall tv.stationbreak.app.benchmark

# 6. Render (exits 1 and prints nothing if any value could leak a path, ID, address or key)
python3 -I scripts/playback-matrix/render.py scripts/playback-matrix/results/*.jsonl > docs/compatibility/playback-matrix.md
```

`192.0.2.10` and `SERIAL` stand for the laptop's own LAN address and the device's `adb devices` serial. Nothing here
records an address. `run.py` waits for each run's `DONE` for items x 60 s + 60 s, and exits non-zero (saying how many
`RESULT` lines arrived) if it does not come. Use `--dry-run` with `temp-server.sh` to see its steps without running them.

## Fast path: set the device up without the onboarding screens

`BenchSetupActivity` is a second benchmark-only, exported activity. Like `PlaybackTestActivity` it does nothing unless
USB debugging is on, and it is in no debug or release build (`BackupRulesTest` checks). It signs the app in with Quick
Connect and accepts the library picker's defaults (every library except home videos), so a device is ready in seconds.

```
# The server address comes from the environment or --server, never from a file in the repo.
export SB_MATRIX_SERVER=https://media.example.invalid     # your own server's address
python3 -I scripts/playback-matrix/run.py setup -s SERIAL
#   prints "Quick Connect code: NNNNNN": approve it in a signed-in Jellyfin client (its Quick Connect page)
#   exits 0 on "SETUP OK", non-zero on "SETUP ERROR <reason>" or after 400 s without an answer

python3 -I scripts/playback-matrix/run.py run -s SERIAL --plan scripts/playback-matrix/plan.json --section real

python3 -I scripts/playback-matrix/run.py signout -s SERIAL
#   exits 0 on "SIGNOUT OK revoked" or "SIGNOUT OK not-revoked", non-zero on "SIGNOUT ERROR <reason>"
```

- `setup` never replaces a session: with one stored it stops with `already-signed-in`. If a step fails after the sign-in
  (the library list, say) it signs out again, so a retry starts clean. Reasons are short codes (`bad-server`,
  `unreachable`, `tls`, `qc-disabled`, `qc-denied`, `qc-expired`, ...); the app logs no address, token, user or library
  name. A server with a self-signed certificate is not handled here (`tls`): trust it in the app's own screens first.
- `signout` clears the stored session and asks the server to revoke its token (`POST /Sessions/Logout`), so the token is
  dead on the server and not only deleted on the device. `not-revoked` means the server could not be reached or refused
  the call: the token may still be live. Whether Jellyfin also removes the device entry on that call is not shown by
  this repo's code or tests (they use a stub server), so the entry may still need removing in the dashboard
  (Dashboard > Devices, "StationBreak").

## Safety notes

- **The benchmark app stays installed only for a run.** `adb uninstall tv.stationbreak.app.benchmark` after each run
  (the exported activity is not worth keeping). After a run against the owner's server, run `run.py signout` first
  (it revokes the token) and check the dashboard for a leftover device entry. Its launcher label is "StationBreak
  benchmark".
- **Docker-published ports bypass the host firewall.** `ufw` and `firewalld` rules do not apply to a port docker
  publishes, so `temp-server.sh` is reachable by everything on the LAN from the moment it is published there. It
  therefore finishes the setup wizard over loopback first, sets a random admin password, and only then publishes on the
  LAN address, which must be a private one (10/8, 172.16/12, 192.168/16, or link-local 169.254/16). Run it on a LAN you
  trust and take it `down` when the run is over.
- The admin token reaches `curl` through a private temporary file, never its arguments, so it is not in the process list.
- `run.py` reads only the benchmark app's own log lines (`logcat --pid`, Android 7.0 and later), and each run's ID carries
  a random part, so another app logging under the same tag cannot add results to a run. It also re-checks a plan file
  before any `adb` call, so an edited plan cannot put anything but IDs and numbers into a shell command on the device.

Synthetic clips all start at 30 s. Real items start at a seeded random point between 10% of the runtime and 30 s before
the end (0 for anything under two minutes or of unknown runtime), so the same seed repeats the same run.
