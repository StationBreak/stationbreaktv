#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Makes the exporter's fixtures again (tools/audit-export/README.md, "Tests"): `fixtures/server.json` (what the fake
server answers: invented titles, with one of each thing the acceptance rules drop or repair), `fixtures/rows.json`
(the rows a cache would hold for those answers) and `fixtures/expected-snapshot.json` (the snapshot).

  python3 -I tools/audit-export/make_fixtures.py

The rows and the snapshot are made by the exporter's own code, so read the diff by eye: the independent check is
`AuditExportFixtureTest` in data:library, which holds the app's own read of the rows to the snapshot.
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import acceptance  # noqa: E402
import export  # noqa: E402

FIXTURES = os.path.join(HERE, "fixtures")

A1 = "A0000000-0000-0000-0000-0000000000A1"
A2 = "a0000000-0000-0000-0000-0000000000a2"
A3 = "A0000000-0000-0000-0000-0000000000A3"
A4 = "a0000000-0000-0000-0000-0000000000a4"


def gid(prefix, n):
    return f"{prefix * 8}-{prefix * 4}-{prefix * 4}-{prefix * 4}-{prefix * 8}{n:04d}"


def movie(n, name, **kw):
    dto = {
        "Id": gid("1", n), "Name": name, "Type": "Movie", "RunTimeTicks": 54000000000,
        "DateCreated": "2023-04-05T06:07:08.1234567Z", "ParentId": A1,
    }
    dto.update(kw)
    return {k: v for k, v in dto.items() if v is not ...}


def episode(n, name, series, **kw):
    dto = {
        "Id": gid("3", n), "Name": name, "Type": "Episode", "RunTimeTicks": 13200000000,
        "DateCreated": "2022-01-02T03:04:05Z", "SeriesId": series, "SeasonId": gid("4", 1),
        "ParentIndexNumber": 1, "IndexNumber": n, "ParentId": gid("4", 1),
    }
    dto.update(kw)
    return {k: v for k, v in dto.items() if v is not ...}


S1 = gid("5", 1)
S2 = gid("5", 2)
S_MISSING = gid("6", 9)

movies = [
    movie(1, "Velmiren Zudar", PremiereDate="1999-12-31T23:30:00-05:00", ProductionYear=1999,
          OfficialRating="PG-13", Genres=["Drama", "Crime"], Tags=["slow burn"],
          Studios=[{"Name": " Invented Pictures​ "}, {"Name": None}, {"Name": ""}, None, {"Name": "Net One"}]),
    movie(2, "Zumaren Kyol", RunTimeTicks=0),
    movie(3, "Pelo Maxi", DateCreated=...),
    movie(4, "Daror Fabra", DateCreated="yesterday"),
    movie(5, "Senol Quinma", ProductionYear=0),
    movie(6, "Kyvel Torpe", Type="Video"),
    movie(7, "Maxi Lozu", SeriesId=S1),
    movie(8, "Renol Bratu", PremiereDate="not a date", OfficialRating="", DateCreated="2020-01-01T00:00:00",
          RunTimeTicks=36000000005, Genres=["Comedy"], Tags=[], ProductionYear=2020),
    movie(9, "Olpe Dartor", Id="not-an-id"),
    movie(1, "Velmiren Zudar Again", Id=gid("1", 1).upper()),
    movie(11, "Fabra Mizu", OfficialRating=" ", PremiereDate="0001-01-01T00:00:00+01:00"),
    movie(12, "Tormi Quenxi", PremiereDate="2001-03-04T23:30:00-05:00", ProductionYear=2001,
          DateCreated="2021-06-07T08:09:10.9999999+02:00", Genres=["Animation", "Comedy"], Studios=[]),
]
shows_items = [
    episode(1, "Pilot", S1.upper()),
    episode(2, "Second", S1, SeasonId="season-one"),
    episode(3, "Third", S1, ParentIndexNumber=-1),
    episode(4, "Fourth", S_MISSING, OfficialRating="TV-MA"),
    episode(5, "Fifth", "series-x"),
    episode(6, "Sixth", S1, IndexNumber=3, Genres=["Comedy"], Tags=["sitcom"]),
    movie(1, "Velmiren Zudar Elsewhere", ParentId=A2),
]
mixed_items = []

series = [
    {"Id": S1, "Name": "Fabra Quinzu", "OfficialRating": "TV-14", "Genres": ["Comedy"], "Tags": ["sitcom"],
     "Studios": [{"Name": "Net One"}]},
    {"Id": S2, "Name": "Unwatched Series", "OfficialRating": None, "Genres": [], "Tags": [], "Studios": []},
    {"Id": S1.upper(), "Name": "Duplicate Series Row", "Genres": ["Drama"]},
]

c1 = gid("7", 1)
c2 = gid("7", 2)
server = {
    "virtualFolders": [
        {"Name": "Movies", "CollectionType": "movies", "ItemId": A1},
        {"Name": "Shows", "CollectionType": "tvshows", "ItemId": A2},
        {"Name": "Music", "CollectionType": "music", "ItemId": A3},
        {"Name": "Mixed", "ItemId": A4},
    ],
    "items": {A1: movies, A2: shows_items, A4: mixed_items},
    "series": {A1: [], A2: series, A4: []},
    "boxsets": [{"Id": c1, "Name": "Velmiren Collection"}, {"Id": c2, "Name": "Mine"}],
    "members": {
        c1: [
            {"Id": gid("1", 1), "Type": "Movie"}, {"Id": gid("3", 1), "Type": "Episode"}, {"Id": S1, "Type": "Series"},
            {"Id": gid("4", 1), "Type": "Season"}, {"Id": gid("7", 2), "Type": "BoxSet"},
            {"Id": gid("8", 1), "Type": "Photo"}, {"Id": "bad-id", "Type": "Movie"}, {"Id": gid("1", 1).upper(), "Type": "Movie"},
        ],
        c2: [],
    },
}

CUTOFF = 1_800_000_000_000
counts = acceptance.Counts()
rows, series_rows = [], []
for library in sorted([A1, A2, A4], key=lambda x: acceptance.canonical_id(x)):
    for dto in server["items"][library]:
        rows.append(acceptance.held_row(dto, library, counts))
    for dto in server["series"][library]:
        series_rows.append(acceptance.series_row(dto, counts))
collection_rows = [acceptance.collection_row(d, server["members"][d["Id"]], counts) for d in server["boxsets"]]

snapshot, dropped = acceptance.build_snapshot(rows, series_rows, collection_rows, [A1, A2, A4], CUTOFF, True)
print("dropped", dict(dropped), "counts", dict(counts))

def j(v):
    return json.dumps(v, indent=2, ensure_ascii=False) + "\n"

open(os.path.join(FIXTURES, "server.json"), "w").write(j(server))
open(os.path.join(FIXTURES, "expected-dropped.json"), "w").write(j(dict(sorted(dropped.items()))))
open(os.path.join(FIXTURES, "rows.json"), "w").write(
    j({"rows": rows, "series": series_rows, "collections": collection_rows,
       "libraries": [A1, A2, A4], "cutoffMs": CUTOFF, "collectionsSynced": True}))
open(os.path.join(FIXTURES, "expected-snapshot.json"), "w").write(export.layout(snapshot))
