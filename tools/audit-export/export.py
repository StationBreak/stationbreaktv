#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Exports a Jellyfin library as an `audit-snapshot` file for the lineup audit (tools/audit-export/README.md).

  SB_AUDIT_SERVER=https://ADDRESS SB_AUDIT_API_KEY=KEY python3 -I tools/audit-export/export.py [--libraries ID,ID]

The server address and the API key come from the environment and nowhere else: never an argument (it would land in
shell history), never a file in the repository. The snapshot is written to a folder outside the repository
(`SB_AUDIT_OUT`, or `stationbreak-audit` in the user's data folder); a folder inside the repository, or inside any
git work tree, is refused. The key and the address are never printed, and no title or name is: only counts.

The server's answers are untrusted: a response is read to a size limit, parsed strictly, and a field of the wrong type
fails the export (as the app's decoder fails a page). Nothing from a response goes into a path, a header or a message.
Standard library only.
"""

import argparse
import datetime
import http.client
import ipaddress
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python3 -I leaves the script's own folder out
import acceptance  # noqa: E402

PAGE_SIZE = 200
MAX_BODY_BYTES = 16 * 1024 * 1024
MAX_ITEMS = 2_000_000
MAX_PAGES = 100_000
MAX_JSON_DEPTH = 64
TIMEOUT_SECONDS = 60
ITEM_TYPES = "Movie,Episode,Video,MusicVideo"
ITEM_FIELDS = "DateCreated,PremiereDate,Genres,Tags,Studios,ParentId"
SERIES_FIELDS = "Genres,Tags,Studios"
NO_EXTRAS = {"enableImages": "false", "enableUserData": "false", "collapseBoxSetItems": "false"}
# The collection types worth a channel (data:jellyfin `kind`): an absent type is mixed content.
VIDEO_LIBRARIES = {None, "movies", "tvshows", "homevideos", "musicvideos"}
KEY_RE = re.compile(r"^[A-Za-z0-9_.~+=/-]{8,512}$")
ID_QUERY_RE = re.compile(r"^[0-9A-Fa-f-]{32,38}$")
GIT_ENTRY = ".git"


class ExportError(Exception):
    """A failure with a message that holds no address, key, title or name."""


# --- the environment -----------------------------------------------------------------------------------------------


def read_server(environ):
    """The server's base address from SB_AUDIT_SERVER, checked: http or https, a host, no credentials, no query. Plain
    http only to a host on this machine or this network, unless SB_AUDIT_ALLOW_CLEARTEXT=1."""
    raw = environ.get("SB_AUDIT_SERVER", "").strip()
    if not raw:
        raise ExportError("SB_AUDIT_SERVER is not set. Set it to the server's address in your shell, not on the command line.")
    parts = urllib.parse.urlsplit(raw)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise ExportError("SB_AUDIT_SERVER must be an http or https address with a host.")
    if parts.username is not None or parts.password is not None or parts.query or parts.fragment:
        raise ExportError("SB_AUDIT_SERVER must have no credentials, query or fragment.")
    if parts.scheme == "http" and not _on_this_network(parts.hostname) and environ.get("SB_AUDIT_ALLOW_CLEARTEXT") != "1":
        raise ExportError("Plain http only to this machine or this network. Use https, or set SB_AUDIT_ALLOW_CLEARTEXT=1.")
    try:
        parts.port  # noqa: B018 - reading it checks it
    except ValueError:
        raise ExportError("SB_AUDIT_SERVER has a port that is not valid.") from None
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path.rstrip("/"), "", ""))


def _on_this_network(host):
    if host == "localhost" or host.endswith(".local"):
        return True
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return address.is_loopback or address.is_private or address.is_link_local


def read_key(environ):
    """The API key from SB_AUDIT_API_KEY: printable characters only, so it cannot break out of a header."""
    key = environ.get("SB_AUDIT_API_KEY", "")
    if not key:
        raise ExportError("SB_AUDIT_API_KEY is not set. Set it in your shell, not on the command line.")
    if not KEY_RE.match(key):
        raise ExportError("SB_AUDIT_API_KEY is not an API key (letters, digits and . _ ~ + = / - only).")
    return key


def repo_root():
    return Path(__file__).resolve().parents[2]


def output_folder(environ, home=None):
    """The folder the snapshot goes in: SB_AUDIT_OUT, else `stationbreak-audit` in the user's data folder. Never
    inside this repository, and never inside any git work tree."""
    given = environ.get("SB_AUDIT_OUT", "").strip()
    if given:
        folder = Path(given).expanduser()
    else:
        data = environ.get("XDG_DATA_HOME", "").strip() or str((home or Path.home()) / ".local" / "share")
        folder = Path(data) / "stationbreak-audit"
    folder = folder.resolve()
    inside_repo = folder == repo_root() or repo_root() in folder.parents
    in_a_work_tree = any((p / GIT_ENTRY).exists() for p in (folder, *folder.parents))
    if inside_repo or in_a_work_tree:
        raise ExportError("The output folder is inside a git work tree. Set SB_AUDIT_OUT to a folder outside any repository.")
    return folder


# --- the server ----------------------------------------------------------------------------------------------------


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ExportError("The server redirected the request. Give the address it redirects to.")


class Server:
    """Reads a Jellyfin server with an API key. Every request is a GET to a fixed path; only query values come from
    the server (an ID, checked), and none is logged."""

    def __init__(self, base, key):
        self._base = base
        self._header = f'MediaBrowser Client="StationBreak Audit Export", Device="audit-export", DeviceId="audit-export", Version="1", Token="{key}"'
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect)  # no proxy: the key goes to the server only
        self.requests = 0

    def get(self, path, params):
        query = urllib.parse.urlencode(params)
        request = urllib.request.Request(f"{self._base}/{path}?{query}", headers={"Authorization": self._header, "Accept": "application/json"})
        self.requests += 1
        try:
            with self._opener.open(request, timeout=TIMEOUT_SECONDS) as response:
                body = response.read(MAX_BODY_BYTES + 1)
        except urllib.error.HTTPError as error:
            error.close()
            raise ExportError(f"The server answered with status {error.code}.") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise ExportError("The server did not answer (a network error).") from None
        except (http.client.HTTPException, ValueError):
            raise ExportError("The server sent an answer that is not HTTP.") from None
        if len(body) > MAX_BODY_BYTES:
            raise ExportError("An answer was bigger than the limit.")
        return parse_json(body)


def parse_json(body):
    """Strict JSON: valid UTF-8, no NaN or Infinity, no duplicate key, nesting within a limit."""

    def refuse(_name):
        raise ExportError("An answer was not valid JSON.")

    def no_duplicates(pairs):
        keys = [k for k, _ in pairs]
        if len(keys) != len(set(keys)):
            raise ExportError("An answer was not valid JSON.")
        return dict(pairs)

    try:
        value = json.loads(body.decode("utf-8"), parse_constant=refuse, object_pairs_hook=no_duplicates)
    except (UnicodeDecodeError, ValueError, RecursionError):
        raise ExportError("An answer was not valid JSON.") from None
    if _depth(value) > MAX_JSON_DEPTH:
        raise ExportError("An answer was nested too deeply.")
    return value


def _depth(value):
    stack = [(value, 1)]
    deepest = 0
    while stack:
        node, depth = stack.pop()
        deepest = max(deepest, depth)
        if isinstance(node, dict):
            stack.extend((v, depth + 1) for v in node.values() if isinstance(v, (dict, list)))
        elif isinstance(node, list):
            stack.extend((v, depth + 1) for v in node if isinstance(v, (dict, list)))
    return deepest


def check_id(value):
    """An ID that goes into a query: hex digits and hyphens only."""
    if not isinstance(value, str) or not ID_QUERY_RE.match(value):
        raise ExportError("The server sent an ID that is not an ID.")
    return value


def pages(server, params, what):
    """Every item of a paged `Items` query, a page at a time, to the bounds. The total is asked for on the first page
    only, and a first page without one is an answer that cannot be trusted (as in the app)."""
    start = 0
    total = None
    for _ in range(MAX_PAGES):
        page = server.get("Items", {**params, "sortBy": "SortName", "sortOrder": "Ascending", "startIndex": start,
                                    "limit": PAGE_SIZE, "enableTotalRecordCount": str(start == 0).lower()})  # fmt: skip
        if not isinstance(page, dict) or not isinstance(page.get("Items"), list):
            raise ExportError(f"A page of {what} was not a list.")
        if start == 0:
            total = page.get("TotalRecordCount")
            if isinstance(total, bool) or not isinstance(total, int) or not 0 <= total <= MAX_ITEMS:
                raise ExportError(f"A first page of {what} had no usable total.")
        items = page["Items"]
        yield from items
        start += len(items)
        if not items or start >= total:
            return
    raise ExportError(f"Too many pages of {what}.")


def video_libraries(server, only):
    """The libraries the audit reads: the server's video libraries, or those of [only] (IDs). Returns the IDs as the
    server sent them, in canonical-ID order."""
    folders = server.get("Library/VirtualFolders", {})
    if not isinstance(folders, list):
        raise ExportError("The list of libraries was not a list.")
    found = {}
    for folder in folders:
        if not isinstance(folder, dict):
            raise ExportError("A library was not an object.")
        kind = folder.get("CollectionType")
        library_id = folder.get("ItemId")
        if (kind is not None and not isinstance(kind, str)) or kind not in VIDEO_LIBRARIES:
            continue
        canonical = acceptance.canonical_id(library_id)
        if canonical is not None:
            found[canonical] = check_id(library_id)
    wanted = {acceptance.canonical_id(i) for i in only} if only else set(found)
    return [found[k] for k in sorted(wanted & set(found))]


# --- the export ----------------------------------------------------------------------------------------------------


def export(server, libraries_only, now_ms):
    """Reads the libraries, the series and the collections, and returns the snapshot (a dict) and the counts."""
    counts = acceptance.Counts()
    libraries = video_libraries(server, libraries_only)
    if not libraries:
        raise ExportError("The server has no video library to read.")
    rows, series_rows = [], []
    for library in libraries:
        query = {"parentId": library, "recursive": "true", **NO_EXTRAS}
        for dto in pages(server, {**query, "includeItemTypes": ITEM_TYPES, "fields": ITEM_FIELDS}, "items"):
            rows.append(acceptance.held_row(dto, library, counts))
        for dto in pages(server, {**query, "includeItemTypes": "Series", "fields": SERIES_FIELDS}, "series"):
            series_rows.append(acceptance.series_row(dto, counts))
    collection_rows = collections(server, counts)
    snapshot, dropped = acceptance.build_snapshot(rows, series_rows, collection_rows, libraries, now_ms, True)
    counts.update({f"dropped_{k}": v for k, v in dropped.items()})
    counts["items"] = len(snapshot["items"])
    counts["series"] = len(snapshot["series"])
    counts["collections"] = len(snapshot["collections"])
    return snapshot, counts


def collections(server, counts):
    """Every BoxSet, with its direct members. A BoxSet is under no library, so no parent is asked for."""
    rows = []
    boxset = {"recursive": "true", "includeItemTypes": "BoxSet", **NO_EXTRAS}
    for dto in pages(server, boxset, "collections"):
        if not isinstance(dto, dict):
            raise ExportError("A collection was not an object.")
        members = list(pages(server, {"parentId": check_id(dto.get("Id")), **NO_EXTRAS}, "collection members"))
        rows.append(acceptance.collection_row(dto, members, counts))
    return rows


def layout(snapshot):
    """The file's text: one key to a line, each item, series and collection on a line of its own (as the audit's own
    writer lays it out). ASCII only, so a lone surrogate in a name cannot make the file unwritable."""
    parts = []
    for key, value in snapshot.items():
        if isinstance(value, list) and value and all(isinstance(v, dict) for v in value):
            body = "[\n" + ",\n".join("    " + _compact(v) for v in value) + "\n  ]"
        else:
            body = _compact(value)
        parts.append(f'  "{key}": {body}')
    return "{\n" + ",\n".join(parts) + "\n}\n"


def _compact(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True)


def _check_folder(folder):
    """The folder must be ours alone to write: owned by this user, with no group or world write."""
    info = folder.stat()
    if info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise ExportError("The output folder must be owned by you and not writable by others.")


def write_snapshot(folder, snapshot, now):
    """Writes the file under a name made here (never from the server), readable by this user only, never over another
    file: the text is made first, written to a temporary name with O_EXCL, and renamed."""
    text = layout(snapshot)
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    _check_folder(folder)
    path = folder / ("audit-snapshot-" + now.strftime("%Y%m%dT%H%M%SZ") + ".json")
    temporary = folder / (path.name + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="ascii") as out:
            out.write(text)
        if path.exists():
            raise ExportError("The snapshot file already exists.")
        os.rename(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return path


def main(argv, environ, out=sys.stdout, err=sys.stderr):
    parser = argparse.ArgumentParser(description="Export a Jellyfin library as an audit-snapshot (see README.md).")
    parser.add_argument("--libraries", default="", help="comma-separated library IDs (default: every video library)")
    args = parser.parse_args(argv)
    try:
        base = read_server(environ)
        key = read_key(environ)
        folder = output_folder(environ)
        only = [i for i in args.libraries.split(",") if i]
        now = datetime.datetime.now(datetime.timezone.utc)
        snapshot, counts = export(Server(base, key), only, int(now.timestamp() * 1000))
        path = write_snapshot(folder, snapshot, now)
    except (ExportError, acceptance.BadAnswer, OSError) as problem:
        err.write(f"Export failed: {_message(problem)}\n")
        return 1
    except Exception as problem:  # noqa: BLE001 - nothing unexpected may print server content
        err.write(f"Export failed: unexpected {type(problem).__name__}.\n")
        return 1
    out.write(f"Wrote {path}\n")
    for name in sorted(counts):
        out.write(f"  {name}: {counts[name]}\n")
    return 0


def _message(problem):
    # An OSError can name a path (ours, never the server's); everything else was made here without content.
    return problem.strerror if isinstance(problem, OSError) and problem.strerror else str(problem) or "unexpected error"


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))
