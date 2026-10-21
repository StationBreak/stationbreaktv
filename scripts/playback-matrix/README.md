# Playback matrix tooling (spec 13)

Runs a spread of files through the player on a real device and renders the result as
`docs/compatibility/playback-matrix.md`. The tooling is for a laptop attached to the device; CI only runs its unit tests
(and that is held for the owner, so for now `scripts/preflight.sh` runs them).

| File | What it does |
| --- | --- |
| `synthetic-media.sh` | Writes the synthetic clips (test patterns and sines, nothing from anywhere). Used by the contract suite and by `temp-server.sh`. |
| `temp-server.sh` | `up` / `down`: a throwaway Jellyfin of those clips, for the synthetic section. |
| `run.py` | `plan` chooses start points; `run` plays a section on a device through `PlaybackTestActivity` and keeps its result lines. |
| `render.py` | Turns result files into the public markdown, refusing anything that could name a home network or a library. |
| `*_test.py`, `playback-matrix.test.sh` | The tests. `bash scripts/playback-matrix/playback-matrix.test.sh` runs all of them. |

Item IDs, plans and raw results are local: `items.local.json`, `plan*.json` and `results/` are gitignored. Never commit
or paste them.

## A device run

You need the **benchmark** APK from a fully green commit of main (a plain `adb install`; its application ID is
`tv.stationbreak.app.benchmark`), the device on adb, docker, curl and jq on the laptop, and the device on the same LAN.

```
# 1. The synthetic server on the LAN interface the device can reach (prints the address and a password, once)
scripts/playback-matrix/temp-server.sh up 192.0.2.10

# 2. On the device: open the benchmark app, connect to the printed address (accept the cleartext notice), sign in
#    as admin with the printed password.

# 3. Plan, then run the synthetic section
python3 -I scripts/playback-matrix/run.py plan --seed 1 --items scripts/playback-matrix/items.local.json \
  --out scripts/playback-matrix/plan.json
python3 -I scripts/playback-matrix/run.py run -s SERIAL --plan scripts/playback-matrix/plan.json --section synthetic

# 4. Remove the server
scripts/playback-matrix/temp-server.sh down

# 5. Real items: put 8 to 10 of the owner's items into items.local.json under "real" (id, runtimeMs, and optionally
#    audio / subtitle track indexes), sign the app in to the owner's server, then
python3 -I scripts/playback-matrix/run.py plan --seed 1 --items scripts/playback-matrix/items.local.json \
  --out scripts/playback-matrix/plan.json
python3 -I scripts/playback-matrix/run.py run -s SERIAL --plan scripts/playback-matrix/plan.json --section real

# 6. Render (exits 1 and prints nothing if any value could leak a path, ID, address or key)
python3 -I scripts/playback-matrix/render.py scripts/playback-matrix/results/*.jsonl > docs/compatibility/playback-matrix.md
```

`192.0.2.10` and `SERIAL` stand for the laptop's own LAN address and the device's `adb devices` serial. Nothing here
records an address. `run.py` waits for each run's `DONE` for items x 60 s + 60 s, and exits non-zero (saying how many
`RESULT` lines arrived) if it does not come. Use `--dry-run` with `temp-server.sh` to see its steps without running them.

Synthetic clips all start at 30 s. Real items start at a seeded random point between 10% of the runtime and 30 s before
the end (0 for anything under two minutes or of unknown runtime), so the same seed repeats the same run.
