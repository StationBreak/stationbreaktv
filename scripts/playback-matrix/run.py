#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""The playback matrix driver (spec 13): plans plays and runs them on a device through the benchmark build's
PlaybackTestActivity, over adb. Standard library only; run it as `python3 -I scripts/playback-matrix/run.py`.

  run.py plan --seed N --items items.local.json [--out plan.json]
  run.py run -s SERIAL --plan plan.json --section real|synthetic [--results-dir DIR]
  run.py setup -s SERIAL --server ADDRESS     (or SB_MATRIX_SERVER=ADDRESS)
  run.py signout -s SERIAL

items.local.json holds item IDs, so it, the plan and the raw results stay on this laptop (see .gitignore). Its shape:
  {"synthetic": [{"id": "...", "name": "h264-aac-nocues", "runtimeMs": 60000}],
   "real": [{"id": "...", "runtimeMs": 5400000, "audio": 1, "subtitle": -1}]}
"audio" and "subtitle" are optional track indexes; an item with either one is played with the track override on
(an unset one then means: audio, the server's choice; subtitle, off).

The app must already be signed in to the server (the stored session is used), and the benchmark APK installed. `setup`
signs it in without the onboarding screens: it prints a Quick Connect code to approve on the server, and `signout`
signs it out again and revokes the token. The server address comes from --server or the SB_MATRIX_SERVER environment
variable, never a file. Item IDs are never printed.
"""

import argparse
import datetime
import json
import math
import os
import random
import re
import secrets
import select
import subprocess
import sys
import time
from typing import NamedTuple, Optional

APP_ID = "tv.stationbreak.app.benchmark"
COMPONENT = f"{APP_ID}/tv.stationbreak.app.bench.PlaybackTestActivity"
SETUP_COMPONENT = f"{APP_ID}/tv.stationbreak.app.bench.BenchSetupActivity"
SERVER_ENV = "SB_MATRIX_SERVER"
SERVER_SAFE = re.compile(r"^[A-Za-z0-9._:/\[\]-]{1,512}$")  # SetupArgs.MAX_SERVER; no space or shell character
SETUP_SECONDS = 400  # the app waits up to 6 minutes for the approval
SIGNOUT_SECONDS = 60
MAX_ITEMS = 200  # RunArgs.MAX_ITEMS
MAX_LABEL = 64  # RunArgs.MAX_LABEL
SECTIONS = ("synthetic", "real")
# Every file this holds IDs in lives beside this script, where .gitignore covers it, whatever the working directory.
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ITEMS = os.path.join(HERE, "items.local.json")
DEFAULT_PLAN = os.path.join(HERE, "plan.json")
# Synthetic clips that are expected to miss their seek and fall back to a transcode, by the "name" temp-server.sh
# writes beside each ID: Matroska muxed to a pipe has no Cues index to seek by. Any other clip that falls back fails.
SEEK_MISS_CLIPS = frozenset({"h264-aac-nocues"})
STRICT_FALLBACK_SECTIONS = frozenset({"synthetic"})  # as in render.py
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


def validate_entry(entry):
    """Refuses a planned play whose values could not have come from make_plan: nothing but a safe item ID and plain
    integers may reach `adb shell`, which hands its arguments to a shell on the device. A plan file is read back from
    disk, so it is checked again here."""
    if not isinstance(entry, dict):
        raise ValueError("a plan entry is not an object")
    item = entry.get("item")
    if not isinstance(item, str) or not SAFE.match(item):
        raise ValueError("a plan entry has no usable item id")
    _int_or_none(entry.get("startMs"), "startMs", 0)
    if entry.get("startMs") is None:
        raise ValueError("a plan entry has no startMs")
    _int_or_none(entry.get("audio"), "audio", -1)
    _int_or_none(entry.get("subtitle"), "subtitle", -1)


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
            expect_miss = section == "synthetic" and entry.get("name") in SEEK_MISS_CLIPS
            planned.append(
                {"item": item, "startMs": start, "audio": audio, "subtitle": subtitle, "seekMissExpected": expect_miss}
            )
        plan[section] = planned
    return plan


def _parse_result(line):
    value = json.loads(line[len("RESULT "):])
    if not isinstance(value, dict):
        raise ValueError("a RESULT line is not a JSON object")
    return value


def mark_expected(batch, lines, run_id, section):
    """(lines, problems): the RESULT [lines] of one batch, run [run_id], with "seekMissExpected" added from the plan and
    the seek-miss rule applied. A line is paired with its plan entry by the ordinal "n" the app prints, never by
    position. A batch whose lines are not all JSON objects of this run, or whose ordinals are not exactly 0..N-1 once
    each, is left as it is and reported: render.py then treats every seek-miss fallback in it as unexpected, which fails
    closed. In a strict section an unexpected seek-miss fallback is rewritten to a failure (pass false, error
    "unexpected-fallback") and reported, so the saved file and the exit status agree with render.py."""
    try:
        values = [_parse_result(line) for line in lines]
    except ValueError:
        return list(lines), ["a RESULT line is not a JSON object; its batch was not checked"]
    ordinals = [value.get("n") for value in values]
    if any(value.get("run_id") != run_id for value in values):
        return list(lines), ["a RESULT line is from another run; its batch was not checked"]
    valid = all(isinstance(n, int) and not isinstance(n, bool) for n in ordinals)
    if not valid or sorted(ordinals) != list(range(len(batch))):
        return list(lines), ["RESULT ordinals are missing, repeated or out of range; the batch was not checked"]
    problems, marked = [], []
    for value in values:
        expected = batch[value["n"]].get("seekMissExpected") is True
        value["seekMissExpected"] = expected
        if section in STRICT_FALLBACK_SECTIONS and value.get("fallback") == "seek-miss" and not expected:
            if value.get("pass") is True:
                problems.append("a clip fell back on a seek miss and was not expected to: unexpected-fallback")
            value["pass"] = False
            value["error"] = value.get("error") or "unexpected-fallback"
        marked.append("RESULT " + json.dumps(value))
    return marked, problems


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
    for entry in batch:
        validate_entry(entry)
    ids = ",".join(_label(entry["item"], "item") for entry in batch)
    starts = ",".join(str(int(entry["startMs"])) for entry in batch)
    command = ["adb", "-s", serial, "shell", "am", "start", "-n", COMPONENT, "--esa", "items", ids]
    command += ["--ela", "starts_ms", starts]
    if override:
        audio = ",".join(str(-1 if entry.get("audio") is None else int(entry["audio"])) for entry in batch)
        subtitles = ",".join(str(-1 if entry.get("subtitle") is None else int(entry["subtitle"])) for entry in batch)
        command += ["--eia", "audio", audio, "--eia", "subtitles", subtitles]
    command += ["--ez", "override", "true" if override else "false"]
    command += ["--es", "section", section, "--es", "run_id", run_id]
    return command


def new_run_id(section, date, number):
    """A run's ID, with a random part no other app can guess: the app echoes it on its DONE line, so a stale or forged
    DONE for another run is never taken as this run's."""
    return _label(f"{section}-{date}-{number}-{secrets.token_hex(4)}", "run_id")


def pid_command(serial):
    """The adb command that prints the benchmark app's process ID."""
    return ["adb", "-s", serial, "shell", "pidof", APP_ID]


def parse_pid(output):
    """The first process ID in the output of `pidof`, or None."""
    for word in output.split():
        if word.isascii() and word.isdigit() and int(word) > 0:
            return int(word)
    return None


def logcat_command(serial, pid):
    """The logcat command that reads SBPLAY lines from one process only (`--pid`, Android 7.0 and later), so another
    app on the device cannot add RESULT lines to a run by logging under the same tag."""
    if isinstance(pid, bool) or not isinstance(pid, int) or pid <= 0:
        raise ValueError("pid must be a positive integer")
    return ["adb", "-s", serial, "logcat", "-v", "raw", f"--pid={pid}", "-s", "SBPLAY:I"]


def timeout_seconds(items):
    return items * SECONDS_PER_ITEM + SECONDS_SLACK


class Collected(NamedTuple):
    meta: Optional[str]
    results: list
    errors: list
    done: bool


def _from_other_run(line, run_id):
    """True when a RESULT line names a run other than [run_id], or none. A line that is not JSON cannot say, so it is
    kept and mark_expected reports it."""
    try:
        return _parse_result(line).get("run_id") != run_id
    except ValueError:
        return False


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
            if _from_other_run(line, run_id):
                continue
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
            if pending:
                yield pending.decode("utf-8", "replace")
            return
        pending += chunk
        *lines, pending = pending.split(b"\n")
        for line in lines:
            yield line.decode("utf-8", "replace")


PID_WAIT_SECONDS = 15


def _wait_for_pid(serial):
    """The benchmark app's process ID, once the activity has started it."""
    deadline = time.monotonic() + PID_WAIT_SECONDS
    while True:
        answer = subprocess.run(pid_command(serial), capture_output=True, text=True)
        pid = parse_pid(answer.stdout)
        if pid is not None:
            return pid
        if time.monotonic() >= deadline:
            raise RuntimeError("the benchmark app did not start a process (is the APK installed?)")
        time.sleep(0.5)


def _run_batch(serial, batch, section, run_id, override):
    # Cleared first, so the buffer holds only this run's lines when logcat is read after the start (below).
    subprocess.run(["adb", "-s", serial, "logcat", "-c"], check=True, capture_output=True)
    started = subprocess.run(
        start_command(serial, batch, section, run_id, override), capture_output=True, text=True, check=True
    )
    if "Error" in started.stdout:
        raise RuntimeError("the activity did not start (is the benchmark APK installed?)")
    # Only this process's lines count; the buffer is dumped from the clear, then followed.
    logcat = subprocess.Popen(
        logcat_command(serial, _wait_for_pid(serial)),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    try:
        deadline = time.monotonic() + timeout_seconds(len(batch))
        return collect(read_lines(logcat.stdout.fileno(), deadline), run_id)
    finally:
        logcat.kill()
        logcat.wait()


def setup_command(serial, action, server=None):
    """The adb command that starts BenchSetupActivity with the extras it reads (see SetupArgs). The server address
    reaches a shell on the device, so only the characters an address is made of are let through."""
    if action not in ("setup", "signout"):
        raise ValueError("action must be setup or signout")
    command = ["adb", "-s", serial, "shell", "am", "start", "-n", SETUP_COMPONENT, "--es", "action", action]
    if action == "setup":
        if not isinstance(server, str) or not SERVER_SAFE.match(server):
            raise ValueError("the server address must be 1 to 512 letters, digits and . : / [ ] - _ (no spaces)")
        command += ["--es", "server", server]
    return command


class SetupLine(NamedTuple):
    kind: str  # "qc", "ok" or "error"
    value: str  # the code, the revoke outcome, or the reason


QC_CODE = re.compile(r"^[A-Za-z0-9]{1,16}$")
REASON = re.compile(r"^[a-z0-9-]{1,40}$")


def parse_setup_line(raw, action):
    """The meaning of one logcat line from BenchSetupActivity, or None for any other line. The line must have exactly
    the shape the app prints (see BenchSetup): anything else is ignored rather than guessed at. [action] picks the
    prefix, so a setup run does not take a sign-out line for its answer. A bare `ERROR <reason>` is the activity
    refusing to run (debugging off, bad extras)."""
    line = LOGCAT_PREFIX.sub("", raw.strip())
    prefix = "SETUP" if action == "setup" else "SIGNOUT"
    words = line.split(" ")
    if action == "setup" and len(words) == 2 and words[0] == "QC" and QC_CODE.match(words[1]):
        return SetupLine("qc", words[1])
    if len(words) == 3 and words[0] == prefix and words[1] == "ERROR" and REASON.match(words[2]):
        return SetupLine("error", words[2])
    if len(words) == 2 and words[0] == "ERROR" and REASON.match(words[1]):
        return SetupLine("error", words[1])
    if action == "setup" and words == ["SETUP", "OK"]:
        return SetupLine("ok", "")
    if action == "signout" and words[:2] == ["SIGNOUT", "OK"] and words[2:] in (["revoked"], ["not-revoked"]):
        return SetupLine("ok", words[2])
    return None


def follow_setup(lines, action, on_code=print):
    """(exit status, message) for the lines of one setup or sign-out. [on_code] gets each Quick Connect code as it
    arrives, because the operator has to approve it while the app is still waiting. 0 only for the app's OK line;
    1 for its ERROR line or for lines that ended without either."""
    for raw in lines:
        parsed = parse_setup_line(raw, action)
        if parsed is None:
            continue
        if parsed.kind == "qc":
            on_code(parsed.value)
        elif parsed.kind == "error":
            return 1, f"{action} failed: {parsed.value}"
        else:
            return 0, f"{action} ok" + (f" ({parsed.value})" if parsed.value else "")
    return 1, f"{action}: no answer from the app before the timeout"


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
    # Before any adb call: a plan file edited on disk is held to the rules make_plan applies.
    for entry in entries:
        validate_entry(entry)
    date = datetime.date.today().isoformat()
    meta, results, problems = None, [], []
    for number, (override, batch) in enumerate(split_batches(entries), start=1):
        run_id = new_run_id(args.section, date, number)
        print(f"run {number}: {len(batch)} items, waiting up to {timeout_seconds(len(batch))} s")
        got = _run_batch(args.serial, batch, args.section, run_id, override)
        meta = meta or got.meta
        marked, marking = mark_expected(batch, got.results, run_id, args.section)
        results.extend(marked)
        problems.extend(f"run {number}: {text}" for text in marking)
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


def _announce_code(code):
    print(f"\nQuick Connect code: {code}\nApprove it on the server (Settings > Quick Connect). Waiting...", flush=True)


def command_setup(args):
    """Starts BenchSetupActivity and follows its SBPLAY lines (this process's only), printing the Quick Connect code."""
    action = args.command
    server = None
    if action == "setup":
        server = args.server or os.environ.get(SERVER_ENV)
        if not server:
            print(f"run.py: give --server ADDRESS or set {SERVER_ENV}", file=sys.stderr)
            return 2
    command = setup_command(args.serial, action, server)
    subprocess.run(["adb", "-s", args.serial, "logcat", "-c"], check=True, capture_output=True)
    started = subprocess.run(command, capture_output=True, text=True, check=True)
    if "Error" in started.stdout:
        raise RuntimeError("the activity did not start (is the benchmark APK installed?)")
    logcat = subprocess.Popen(
        logcat_command(args.serial, _wait_for_pid(args.serial)),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    try:
        seconds = SETUP_SECONDS if action == "setup" else SIGNOUT_SECONDS
        lines = read_lines(logcat.stdout.fileno(), time.monotonic() + seconds)
        status, message = follow_setup(lines, action, _announce_code)
    finally:
        logcat.kill()
        logcat.wait()
    print(message, file=sys.stdout if status == 0 else sys.stderr)
    return status


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
    setup = sub.add_parser("setup", help="sign the benchmark app in with Quick Connect (no onboarding screens)")
    setup.add_argument("-s", "--serial", required=True)
    setup.add_argument("--server", help=f"the server address (or set {SERVER_ENV}); never put it in a file")
    signout = sub.add_parser("signout", help="sign the benchmark app out and revoke its token")
    signout.add_argument("-s", "--serial", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command in ("setup", "signout"):
            return command_setup(args)
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
