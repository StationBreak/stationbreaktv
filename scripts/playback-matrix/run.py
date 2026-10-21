#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""The playback matrix driver (spec 13): plans plays and runs them on a device through the benchmark build's
PlaybackTestActivity, over adb. Standard library only; run it as `python3 -I scripts/playback-matrix/run.py`.

  run.py plan --seed N --items items.local.json [--out plan.json]
  run.py run -s SERIAL --plan plan.json --section real|synthetic [--results-dir DIR]

items.local.json holds item IDs, so it, the plan and the raw results stay on this laptop (see .gitignore). Its shape:
  {"synthetic": [{"id": "...", "runtimeMs": 60000}], "real": [{"id": "...", "runtimeMs": 5400000, "audio": 1,
   "subtitle": -1}]}
"audio" and "subtitle" are optional track indexes; an item with either one is played with the track override on
(an unset one then means: audio, the server's choice; subtitle, off).

The app must already be signed in to the server (the stored session is used), and the benchmark APK installed.
Item IDs are never printed.
"""

import argparse
import datetime
import json
import math
import os
import random
import re
import select
import subprocess
import sys
import time
from typing import NamedTuple, Optional

COMPONENT = "tv.stationbreak.app.benchmark/tv.stationbreak.app.bench.PlaybackTestActivity"
MAX_ITEMS = 200  # RunArgs.MAX_ITEMS
MAX_LABEL = 64  # RunArgs.MAX_LABEL
SECTIONS = ("synthetic", "real")
# Every file this holds IDs in lives beside this script, where .gitignore covers it, whatever the working directory.
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ITEMS = os.path.join(HERE, "items.local.json")
DEFAULT_PLAN = os.path.join(HERE, "plan.json")
SYNTHETIC_START_MS = 30_000  # ruling R9: the same for every synthetic clip, whatever its runtime
MIN_RANDOM_RUNTIME_MS = 120_000
TAIL_MS = 30_000
SECONDS_PER_ITEM = 60
SECONDS_SLACK = 60
SAFE = re.compile(r"^[A-Za-z0-9._-]+$")  # what may reach `adb shell` as an argument
LOGCAT_PREFIX = re.compile(r"^[A-Z]/SBPLAY\s*\(\s*\d+\):\s*")


def _int_or_none(value, what, minimum):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError(f"{what} must be an integer of at least {minimum}")
    return value


def make_plan(items, seed):
    """The plan for every section of [items]: where each item starts. Deterministic for a seed."""
    rng = random.Random(seed)
    plan = {"seed": seed}
    for section in SECTIONS:
        entries = items.get(section, [])
        if not isinstance(entries, list):
            raise ValueError(f"{section} must be a list")
        if len(entries) > MAX_ITEMS:
            raise ValueError(f"{section} has more than {MAX_ITEMS} items")
        planned = []
        for entry in entries:
            item = entry.get("id") if isinstance(entry, dict) else None
            if not isinstance(item, str) or not SAFE.match(item):
                raise ValueError(f"an item in {section} has no usable id")
            runtime = _int_or_none(entry.get("runtimeMs"), "runtimeMs", 0)
            audio = _int_or_none(entry.get("audio"), "audio", -1)
            subtitle = _int_or_none(entry.get("subtitle"), "subtitle", -1)
            if section == "synthetic":
                start = SYNTHETIC_START_MS
            elif runtime is None or runtime < MIN_RANDOM_RUNTIME_MS:
                start = 0
            else:
                start = rng.randint(math.ceil(runtime * 0.10), runtime - TAIL_MS)
            planned.append({"item": item, "startMs": start, "audio": audio, "subtitle": subtitle})
        plan[section] = planned
    return plan


def _overridden(entry):
    return entry.get("audio") is not None or entry.get("subtitle") is not None


def split_batches(entries):
    """Runs of consecutive entries that share the override flag, in order: one intent carries one flag."""
    batches = []
    for entry in entries:
        flag = _overridden(entry)
        if batches and batches[-1][0] == flag and len(batches[-1][1]) < MAX_ITEMS:
            batches[-1][1].append(entry)
        else:
            batches.append((flag, [entry]))
    return batches


def _label(text, what):
    if not SAFE.match(text) or len(text) > MAX_LABEL:
        raise ValueError(f"{what} must be 1 to {MAX_LABEL} letters, digits, dots, dashes or underscores")
    return text


def start_command(serial, batch, section, run_id, override):
    """The adb command that starts the activity with the extras it reads (see PlaybackTestActivity)."""
    _label(section, "section")
    _label(run_id, "run_id")
    if not batch or len(batch) > MAX_ITEMS:
        raise ValueError(f"a run plays 1 to {MAX_ITEMS} items")
    ids = ",".join(_label(entry["item"], "item") for entry in batch)
    starts = ",".join(str(int(entry["startMs"])) for entry in batch)
    command = ["adb", "-s", serial, "shell", "am", "start", "-n", COMPONENT, "--esa", "items", ids]
    command += ["--ela", "starts_ms", starts]
    if override:
        audio = ",".join(str(-1 if entry.get("audio") is None else entry["audio"]) for entry in batch)
        subtitles = ",".join(str(-1 if entry.get("subtitle") is None else entry["subtitle"]) for entry in batch)
        command += ["--eia", "audio", audio, "--eia", "subtitles", subtitles]
    command += ["--ez", "override", "true" if override else "false"]
    command += ["--es", "section", section, "--es", "run_id", run_id]
    return command


def timeout_seconds(items):
    return items * SECONDS_PER_ITEM + SECONDS_SLACK


class Collected(NamedTuple):
    meta: Optional[str]
    results: list
    errors: list
    done: bool


def collect(lines, run_id):
    """Reads SBPLAY messages (META, RESULT, ERROR) up to and including this run's DONE.

    A DONE for another run (a stale line) is ignored. The activity prints "DONE none" when it could not read its
    arguments, after an ERROR line, so that one ends the run only if an ERROR came first.
    """
    meta, results, errors, done = None, [], [], False
    for raw in lines:
        line = LOGCAT_PREFIX.sub("", raw.strip())
        if line.startswith("META ") and meta is None:
            meta = line
        elif line.startswith("RESULT "):
            results.append(line)
        elif line.startswith("ERROR"):
            errors.append(line)
        elif line.startswith("DONE"):
            named = line[4:].strip()
            if named == run_id or (named == "none" and errors):
                done = True
                break
    return Collected(meta, results, errors, done)


def shortfall_message(done, arrived, expected, errors):
    """None when the run was complete; otherwise what went wrong, with how many RESULT lines arrived."""
    problems = []
    if not done:
        problems.append("no DONE line before the timeout")
    problems.extend(errors)
    if not problems and arrived == expected:
        return None
    return f"{arrived} of {expected} RESULT lines arrived; " + "; ".join(problems)


def results_name(date, meta, section):
    """results/<date>-<model>-<section>.jsonl, the model made safe as a file name."""
    model = "unknown"
    try:
        model = str(json.loads(meta[len("META "):]).get("device", "unknown"))
    except (TypeError, ValueError):
        pass
    model = re.sub(r"[^A-Za-z0-9_-]", "_", model).strip("_") or "unknown"
    return f"{date}-{model}-{section}.jsonl"


def read_lines(fd, deadline):
    """Lines from a file descriptor until the deadline or end of input.

    It reads the descriptor itself and splits lines itself: select() on a buffered stream misses lines already
    buffered, so a burst (RESULT, RESULT, DONE in one chunk) would wait out the whole timeout.
    """
    pending = b""
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        ready, _, _ = select.select([fd], [], [], min(1.0, remaining))
        if not ready:
            continue
        chunk = os.read(fd, 65536)
        if not chunk:
            return
        pending += chunk
        *lines, pending = pending.split(b"\n")
        for line in lines:
            yield line.decode("utf-8", "replace")


def _run_batch(serial, batch, section, run_id, override):
    adb = ["adb", "-s", serial]
    subprocess.run(adb + ["logcat", "-c"], check=True, capture_output=True)
    logcat = subprocess.Popen(
        adb + ["logcat", "-v", "raw", "-s", "SBPLAY:I"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    try:
        started = subprocess.run(
            start_command(serial, batch, section, run_id, override), capture_output=True, text=True, check=True
        )
        if "Error" in started.stdout:
            raise RuntimeError("the activity did not start (is the benchmark APK installed?)")
        deadline = time.monotonic() + timeout_seconds(len(batch))
        return collect(read_lines(logcat.stdout.fileno(), deadline), run_id)
    finally:
        logcat.kill()
        logcat.wait()


def write_private(path, text):
    """Writes [text] to [path], which holds item IDs, readable by this user only (also if it already existed)."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(text)


def command_plan(args):
    with open(args.items, encoding="utf-8") as handle:
        plan = make_plan(json.load(handle), args.seed)
    write_private(args.out, json.dumps(plan, indent=2) + "\n")
    counts = ", ".join(f"{len(plan[s])} {s}" for s in SECTIONS)
    print(f"planned {counts} (seed {args.seed}) into {args.out}")
    return 0


def command_run(args):
    with open(args.plan, encoding="utf-8") as handle:
        entries = json.load(handle).get(args.section)
    if not entries:
        print(f"the plan has no {args.section} items", file=sys.stderr)
        return 2
    date = datetime.date.today().isoformat()
    meta, results, problems = None, [], []
    for number, (override, batch) in enumerate(split_batches(entries), start=1):
        run_id = f"{args.section}-{date}-{number}"
        print(f"run {number}: {len(batch)} items, waiting up to {timeout_seconds(len(batch))} s")
        got = _run_batch(args.serial, batch, args.section, run_id, override)
        meta = meta or got.meta
        results.extend(got.results)
        message = shortfall_message(got.done, len(got.results), len(batch), got.errors)
        if message:
            problems.append(f"run {number}: {message}")
    os.makedirs(args.results_dir, exist_ok=True)
    path = os.path.join(args.results_dir, results_name(date, meta or "", args.section))
    write_private(path, "\n".join(([meta] if meta else []) + results) + "\n")
    print(f"{len(results)} of {len(entries)} RESULT lines written to {path}")
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


def main(argv=None):
    here = HERE
    parser = argparse.ArgumentParser(description="Plan and run the playback matrix on a device.")
    sub = parser.add_subparsers(dest="command", required=True)
    plan = sub.add_parser("plan", help="choose start points for the items in items.local.json")
    plan.add_argument("--seed", type=int, required=True)
    plan.add_argument("--items", default=DEFAULT_ITEMS)
    plan.add_argument("--out", default=DEFAULT_PLAN)
    run = sub.add_parser("run", help="play one section of a plan on a device")
    run.add_argument("-s", "--serial", required=True)
    run.add_argument("--plan", default=DEFAULT_PLAN)
    run.add_argument("--section", choices=SECTIONS, required=True)
    run.add_argument("--results-dir", default=os.path.join(here, "results"))
    args = parser.parse_args(argv)
    try:
        return command_plan(args) if args.command == "plan" else command_run(args)
    except subprocess.CalledProcessError as error:
        # Not the command: its arguments hold item IDs.
        print(f"run.py: adb failed with status {error.returncode}", file=sys.stderr)
        return 2
    except (ValueError, OSError, RuntimeError) as error:
        print(f"run.py: {type(error).__name__}: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
