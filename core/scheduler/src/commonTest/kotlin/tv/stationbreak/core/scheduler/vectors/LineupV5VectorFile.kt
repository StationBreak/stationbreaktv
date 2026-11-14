// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler.vectors

/**
 * The Kotlin copy of `docs/rules-format/vectors/lineup-v5.json`. `VectorFilesInSyncTest` (jvmTest) fails if it
 * differs from the published file. A test in commonTest cannot read files, because linuxX64Test cannot.
 */
@Suppress("LargeClass") // Data only: one vector file.
internal object LineupV5VectorFile {
    val text: String =
        """
        {
          "description": "Version 5 whole days: the spread end of the day (docs/rules-format.md, The spread end of the day). The case shape is lineup-v3.json's (see its description; version 4 has no lineup vectors, its days are version 3's). Every case is a rules file that says formatVersion 5, so it spreads its end of the day: there is no dayEnd key. A block is {startMs, endMs, itemId} for a program, {startMs, endMs, slate: true} for a slate, or {startMs, endMs, break: true} for a break (a station-break card: no item and no media offset). A query on a break has no offsetMs. Times in the notes are minutes from S. Every case has errorTimes S - 1 and E.",
          "source": "Hand-derived from the version 5 text of docs/rules-format.md (the spread end of the day), not from the code that is tested. The cycle order of each day (the version 3 orders, unchanged in version 5 and covered by key-order*.json) was read from the app's order to choose seeds and runtimes; every layout, fill, break point and card was then worked out by hand.",
          "cases": [
            {
              "name": "spread curated",
              "note": "Order (groups of one): i9 i6 i2 i4 i5 i3 | i1 i10 i8 i7 i11. c = 645, L = 75 min. U = {i10, i8, i11} = 71 min (firstFit 63). k = 4, targets 161.25, 322.5, 483.75: points 220, 425, 645 (the third target is skipped). One unit per break, R = 4 min as three 80 s cards.",
              "settings": {
                "seed": "1",
                "ordering": {
                  "mode": "curated"
                },
                "repeatGapMs": "10800000",
                "pairUpToMs": "2100000",
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050001000000000001",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050001000000000002",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050001000000000003",
                  "kind": "movie",
                  "runtimeMs": "6900000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050001000000000004",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050001000000000005",
                  "kind": "movie",
                  "runtimeMs": "6300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050001000000000006",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050001000000000007",
                  "kind": "movie",
                  "runtimeMs": "1080000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050001000000000008",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050001000000000009",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000100000000000a",
                  "kind": "movie",
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000100000000000b",
                  "kind": "movie",
                  "runtimeMs": "1560000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864006000000",
                  "itemId": "00000000000000050001000000000009"
                },
                {
                  "startMs": "864006000000",
                  "endMs": "864013200000",
                  "itemId": "00000000000000050001000000000006"
                },
                {
                  "startMs": "864013200000",
                  "endMs": "864014400000",
                  "itemId": "0000000000000005000100000000000a"
                },
                {
                  "startMs": "864014400000",
                  "endMs": "864014480000",
                  "break": true
                },
                {
                  "startMs": "864014480000",
                  "endMs": "864020180000",
                  "itemId": "00000000000000050001000000000002"
                },
                {
                  "startMs": "864020180000",
                  "endMs": "864026780000",
                  "itemId": "00000000000000050001000000000004"
                },
                {
                  "startMs": "864026780000",
                  "endMs": "864028280000",
                  "itemId": "00000000000000050001000000000008"
                },
                {
                  "startMs": "864028280000",
                  "endMs": "864028360000",
                  "break": true
                },
                {
                  "startMs": "864028360000",
                  "endMs": "864034660000",
                  "itemId": "00000000000000050001000000000005"
                },
                {
                  "startMs": "864034660000",
                  "endMs": "864041560000",
                  "itemId": "00000000000000050001000000000003"
                },
                {
                  "startMs": "864041560000",
                  "endMs": "864043120000",
                  "itemId": "0000000000000005000100000000000b"
                },
                {
                  "startMs": "864043120000",
                  "endMs": "864043200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864014400001",
                  "k": 1,
                  "blockIndex": 3,
                  "nextIndexes": [
                    4
                  ]
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 11,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread curated same-series short",
              "note": "Order: F5 F4 X1 F6 F1 F3 F2 | F7 X2 a; X1 and X2 are one series, repeatGapMs 2 h. X1 ends at 275 (unshifted), so X2 may take a break at 395 or later: it is unit 0, but breaks 0 (225) and 1 (390) are too early, so it waits for break 2 (695). Unit 1 (a) takes break 1.",
              "settings": {
                "seed": "9",
                "ordering": {
                  "mode": "curated"
                },
                "repeatGapMs": "7200000",
                "pairUpToMs": "2100000",
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050002000000000001",
                  "kind": "episode",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-01",
                  "seriesId": "000000000000000500025e0000000001",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000050002000000000002",
                  "kind": "episode",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-02",
                  "seriesId": "000000000000000500025e0000000001",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000050002000000000003",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050002000000000004",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050002000000000005",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050002000000000006",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050002000000000007",
                  "kind": "movie",
                  "runtimeMs": "6300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050002000000000008",
                  "kind": "movie",
                  "runtimeMs": "6900000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050002000000000009",
                  "kind": "movie",
                  "runtimeMs": "7500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000200000000000a",
                  "kind": "movie",
                  "runtimeMs": "360000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864006300000",
                  "itemId": "00000000000000050002000000000007"
                },
                {
                  "startMs": "864006300000",
                  "endMs": "864013500000",
                  "itemId": "00000000000000050002000000000006"
                },
                {
                  "startMs": "864013500000",
                  "endMs": "864013680000",
                  "break": true
                },
                {
                  "startMs": "864013680000",
                  "endMs": "864016680000",
                  "itemId": "00000000000000050002000000000001"
                },
                {
                  "startMs": "864016680000",
                  "endMs": "864023580000",
                  "itemId": "00000000000000050002000000000008"
                },
                {
                  "startMs": "864023580000",
                  "endMs": "864023940000",
                  "itemId": "0000000000000005000200000000000a"
                },
                {
                  "startMs": "864023940000",
                  "endMs": "864024120000",
                  "break": true
                },
                {
                  "startMs": "864024120000",
                  "endMs": "864030120000",
                  "itemId": "00000000000000050002000000000003"
                },
                {
                  "startMs": "864030120000",
                  "endMs": "864035820000",
                  "itemId": "00000000000000050002000000000005"
                },
                {
                  "startMs": "864035820000",
                  "endMs": "864042420000",
                  "itemId": "00000000000000050002000000000004"
                },
                {
                  "startMs": "864042420000",
                  "endMs": "864043020000",
                  "itemId": "00000000000000050002000000000002"
                },
                {
                  "startMs": "864043020000",
                  "endMs": "864043200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864013500001",
                  "k": 1,
                  "blockIndex": 2,
                  "nextIndexes": [
                    3
                  ]
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 11,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread curated collection",
              "note": "K1, K2 and K3 are one franchise group (the walk's group). Order: K1 F1 F2 F3 F4 F6 | F5 a K2 K3. F = a, K2, K3: the franchise is one unit (K2 then K3, its walk order), unit 1, so it takes break 1 (405 >= 100 + 180).",
              "settings": {
                "seed": "14",
                "ordering": {
                  "mode": "curated"
                },
                "repeatGapMs": "10800000",
                "pairUpToMs": "2100000",
                "franchises": {
                  "00000000000000050003000000000001": "00000000000000050003c01100000001",
                  "00000000000000050003000000000002": "00000000000000050003c01100000001",
                  "00000000000000050003000000000003": "00000000000000050003c01100000001"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050003000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-01"
                },
                {
                  "id": "00000000000000050003000000000002",
                  "kind": "movie",
                  "runtimeMs": "900000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2002-01-01"
                },
                {
                  "id": "00000000000000050003000000000003",
                  "kind": "movie",
                  "runtimeMs": "720000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2003-01-01"
                },
                {
                  "id": "00000000000000050003000000000004",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050003000000000005",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050003000000000006",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050003000000000007",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050003000000000008",
                  "kind": "movie",
                  "runtimeMs": "6300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050003000000000009",
                  "kind": "movie",
                  "runtimeMs": "6900000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000300000000000a",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864006000000",
                  "itemId": "00000000000000050003000000000001"
                },
                {
                  "startMs": "864006000000",
                  "endMs": "864012000000",
                  "itemId": "00000000000000050003000000000004"
                },
                {
                  "startMs": "864012000000",
                  "endMs": "864012420000",
                  "itemId": "0000000000000005000300000000000a"
                },
                {
                  "startMs": "864012420000",
                  "endMs": "864013340000",
                  "break": true
                },
                {
                  "startMs": "864013340000",
                  "endMs": "864019940000",
                  "itemId": "00000000000000050003000000000005"
                },
                {
                  "startMs": "864019940000",
                  "endMs": "864025640000",
                  "itemId": "00000000000000050003000000000006"
                },
                {
                  "startMs": "864025640000",
                  "endMs": "864026540000",
                  "itemId": "00000000000000050003000000000002"
                },
                {
                  "startMs": "864026540000",
                  "endMs": "864027260000",
                  "itemId": "00000000000000050003000000000003"
                },
                {
                  "startMs": "864027260000",
                  "endMs": "864028180000",
                  "break": true
                },
                {
                  "startMs": "864028180000",
                  "endMs": "864035380000",
                  "itemId": "00000000000000050003000000000007"
                },
                {
                  "startMs": "864035380000",
                  "endMs": "864042280000",
                  "itemId": "00000000000000050003000000000009"
                },
                {
                  "startMs": "864042280000",
                  "endMs": "864043200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864012420001",
                  "k": 1,
                  "blockIndex": 3,
                  "nextIndexes": [
                    4
                  ]
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 11,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread curated wrapped group",
              "note": "Series W (W1 W2 W3), repeatGapMs 0, pairUpToMs 60000 (no pairs). Order: W1 F6 W2 F4 F1 F2 F5 | F3 W3, so the visit is F3 W3 then wraps to W1 F6 W2 ... U = {W3} (9 min); A = {W3, W1} (21 min, the visit wraps inside group W). A's unit is at W3's place in F but airs W1 then W3, cycle order. Its group last aired at 227 (W2), so break 0 (127) is too early and it takes break 1 (347). A places 21 > 9.",
              "settings": {
                "seed": "1",
                "ordering": {
                  "mode": "curated"
                },
                "repeatGapMs": "0",
                "pairUpToMs": "60000",
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050004000000000001",
                  "kind": "episode",
                  "runtimeMs": "720000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-01",
                  "seriesId": "000000000000000500045e0000000001",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000050004000000000002",
                  "kind": "episode",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-02",
                  "seriesId": "000000000000000500045e0000000001",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000050004000000000003",
                  "kind": "episode",
                  "runtimeMs": "540000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-03",
                  "seriesId": "000000000000000500045e0000000001",
                  "season": 1,
                  "episode": 3
                },
                {
                  "id": "00000000000000050004000000000004",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050004000000000005",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050004000000000006",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050004000000000007",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050004000000000008",
                  "kind": "movie",
                  "runtimeMs": "6300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050004000000000009",
                  "kind": "movie",
                  "runtimeMs": "6900000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864000720000",
                  "itemId": "00000000000000050004000000000001"
                },
                {
                  "startMs": "864000720000",
                  "endMs": "864007620000",
                  "itemId": "00000000000000050004000000000009"
                },
                {
                  "startMs": "864007620000",
                  "endMs": "864008360000",
                  "break": true
                },
                {
                  "startMs": "864008360000",
                  "endMs": "864014360000",
                  "itemId": "00000000000000050004000000000002"
                },
                {
                  "startMs": "864014360000",
                  "endMs": "864021560000",
                  "itemId": "00000000000000050004000000000007"
                },
                {
                  "startMs": "864021560000",
                  "endMs": "864022280000",
                  "itemId": "00000000000000050004000000000001"
                },
                {
                  "startMs": "864022280000",
                  "endMs": "864022820000",
                  "itemId": "00000000000000050004000000000003"
                },
                {
                  "startMs": "864022820000",
                  "endMs": "864023560000",
                  "break": true
                },
                {
                  "startMs": "864023560000",
                  "endMs": "864029560000",
                  "itemId": "00000000000000050004000000000004"
                },
                {
                  "startMs": "864029560000",
                  "endMs": "864036160000",
                  "itemId": "00000000000000050004000000000005"
                },
                {
                  "startMs": "864036160000",
                  "endMs": "864042460000",
                  "itemId": "00000000000000050004000000000008"
                },
                {
                  "startMs": "864042460000",
                  "endMs": "864043200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864007620001",
                  "k": 1,
                  "blockIndex": 2,
                  "nextIndexes": [
                    3
                  ]
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 11,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread all movies",
              "note": "Shuffle order i7 i11 i6 i3 i12 i5 i8 i9 i2 i13 i14 i4 | i1 i10. c = 1385, L = 55 min, no film fits. k = 9; targets (i + 1) * 1385 / 9 give the points 110, 330, 560, 780, 998 and c, then nothing is separated from them, so k' = 6: a break after every other film, six cards of 9 min 10 s.",
              "settings": {
                "seed": "5",
                "ordering": {
                  "mode": "shuffle"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 24,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050005000000000001",
                  "kind": "movie",
                  "runtimeMs": "7080000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050005000000000002",
                  "kind": "movie",
                  "runtimeMs": "5880000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050005000000000003",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050005000000000004",
                  "kind": "movie",
                  "runtimeMs": "8400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050005000000000005",
                  "kind": "movie",
                  "runtimeMs": "6300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050005000000000006",
                  "kind": "movie",
                  "runtimeMs": "7500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050005000000000007",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050005000000000008",
                  "kind": "movie",
                  "runtimeMs": "6900000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050005000000000009",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000500000000000a",
                  "kind": "movie",
                  "runtimeMs": "6120000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000500000000000b",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000500000000000c",
                  "kind": "movie",
                  "runtimeMs": "7800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000500000000000d",
                  "kind": "movie",
                  "runtimeMs": "8100000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000500000000000e",
                  "kind": "movie",
                  "runtimeMs": "6720000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "1728000000000",
                  "endMs": "1728006600000",
                  "itemId": "00000000000000050005000000000007"
                },
                {
                  "startMs": "1728006600000",
                  "endMs": "1728007150000",
                  "break": true
                },
                {
                  "startMs": "1728007150000",
                  "endMs": "1728012850000",
                  "itemId": "0000000000000005000500000000000b"
                },
                {
                  "startMs": "1728012850000",
                  "endMs": "1728020350000",
                  "itemId": "00000000000000050005000000000006"
                },
                {
                  "startMs": "1728020350000",
                  "endMs": "1728020900000",
                  "break": true
                },
                {
                  "startMs": "1728020900000",
                  "endMs": "1728026900000",
                  "itemId": "00000000000000050005000000000003"
                },
                {
                  "startMs": "1728026900000",
                  "endMs": "1728034700000",
                  "itemId": "0000000000000005000500000000000c"
                },
                {
                  "startMs": "1728034700000",
                  "endMs": "1728035250000",
                  "break": true
                },
                {
                  "startMs": "1728035250000",
                  "endMs": "1728041550000",
                  "itemId": "00000000000000050005000000000005"
                },
                {
                  "startMs": "1728041550000",
                  "endMs": "1728048450000",
                  "itemId": "00000000000000050005000000000008"
                },
                {
                  "startMs": "1728048450000",
                  "endMs": "1728049000000",
                  "break": true
                },
                {
                  "startMs": "1728049000000",
                  "endMs": "1728056200000",
                  "itemId": "00000000000000050005000000000009"
                },
                {
                  "startMs": "1728056200000",
                  "endMs": "1728062080000",
                  "itemId": "00000000000000050005000000000002"
                },
                {
                  "startMs": "1728062080000",
                  "endMs": "1728062630000",
                  "break": true
                },
                {
                  "startMs": "1728062630000",
                  "endMs": "1728070730000",
                  "itemId": "0000000000000005000500000000000d"
                },
                {
                  "startMs": "1728070730000",
                  "endMs": "1728077450000",
                  "itemId": "0000000000000005000500000000000e"
                },
                {
                  "startMs": "1728077450000",
                  "endMs": "1728085850000",
                  "itemId": "00000000000000050005000000000004"
                },
                {
                  "startMs": "1728085850000",
                  "endMs": "1728086400000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "1728000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "1728006600001",
                  "k": 1,
                  "blockIndex": 1,
                  "nextIndexes": [
                    2
                  ]
                },
                {
                  "t": "1728086399999",
                  "k": 3,
                  "blockIndex": 17,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "1727999999999",
                "1728086400000"
              ]
            },
            {
              "name": "spread one film",
              "note": "One 100-minute film in an 8-hour day: it airs four times (c = 400, L = 80). Nothing fits. k = 3; target 133.33 takes 100; target 266.67 finds no run end separated from 100 and 400, so k' = 2 and the 80 minutes are two 40-minute cards.",
              "settings": {
                "seed": "6",
                "ordering": {
                  "mode": "shuffle"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 8,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050006000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "576000000000",
                  "endMs": "576006000000",
                  "itemId": "00000000000000050006000000000001"
                },
                {
                  "startMs": "576006000000",
                  "endMs": "576008400000",
                  "break": true
                },
                {
                  "startMs": "576008400000",
                  "endMs": "576014400000",
                  "itemId": "00000000000000050006000000000001"
                },
                {
                  "startMs": "576014400000",
                  "endMs": "576020400000",
                  "itemId": "00000000000000050006000000000001"
                },
                {
                  "startMs": "576020400000",
                  "endMs": "576026400000",
                  "itemId": "00000000000000050006000000000001"
                },
                {
                  "startMs": "576026400000",
                  "endMs": "576028800000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "576000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "576006000001",
                  "k": 1,
                  "blockIndex": 1,
                  "nextIndexes": [
                    2
                  ]
                },
                {
                  "t": "576028799999",
                  "k": 3,
                  "blockIndex": 5,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "575999999999",
                "576028800000"
              ]
            },
            {
              "name": "spread aired short",
              "note": "Shuffle order i7 i3 i2 i8 i5 | i6 i1 i4; i3 is a 10-minute short that aired at 100 to 110. No unaired item fits L = 45, so U places nothing and A = {i3}. k = 3, points 110 and 435. i3 is unit 0, but break 0 is at 110, the end of its own run (not after it), so it goes to break 1.",
              "settings": {
                "seed": "7",
                "ordering": {
                  "mode": "shuffle"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 8,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050007000000000001",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050007000000000002",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050007000000000003",
                  "kind": "movie",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050007000000000004",
                  "kind": "movie",
                  "runtimeMs": "3600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050007000000000005",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050007000000000006",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050007000000000007",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050007000000000008",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "576000000000",
                  "endMs": "576006000000",
                  "itemId": "00000000000000050007000000000007"
                },
                {
                  "startMs": "576006000000",
                  "endMs": "576006600000",
                  "itemId": "00000000000000050007000000000003"
                },
                {
                  "startMs": "576006600000",
                  "endMs": "576007650000",
                  "break": true
                },
                {
                  "startMs": "576007650000",
                  "endMs": "576014250000",
                  "itemId": "00000000000000050007000000000002"
                },
                {
                  "startMs": "576014250000",
                  "endMs": "576019950000",
                  "itemId": "00000000000000050007000000000008"
                },
                {
                  "startMs": "576019950000",
                  "endMs": "576027150000",
                  "itemId": "00000000000000050007000000000005"
                },
                {
                  "startMs": "576027150000",
                  "endMs": "576027750000",
                  "itemId": "00000000000000050007000000000003"
                },
                {
                  "startMs": "576027750000",
                  "endMs": "576028800000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "576000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "576006600001",
                  "k": 1,
                  "blockIndex": 2,
                  "nextIndexes": [
                    3
                  ]
                },
                {
                  "t": "576028799999",
                  "k": 3,
                  "blockIndex": 7,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "575999999999",
                "576028800000"
              ]
            },
            {
              "name": "spread ordered grouped",
              "note": "chronological; series A (A1 A2 A3) and B (B1 B2). c = 425, L = 55. A1 (60) is over the budget, so A is passed over and A2, A3 are cut. G = {B1, B2, F5} = 47 min. R = 8 min: k = 3, points 100 and 425; two 4-minute cards; the last break is its card, then the fill, so the day ends on a programme.",
              "settings": {
                "seed": "8",
                "ordering": {
                  "mode": "chronological"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 8,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050008000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-01"
                },
                {
                  "id": "00000000000000050008000000000002",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-02"
                },
                {
                  "id": "00000000000000050008000000000003",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-03"
                },
                {
                  "id": "00000000000000050008000000000004",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-04"
                },
                {
                  "id": "00000000000000050008000000000005",
                  "kind": "episode",
                  "runtimeMs": "3600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-05",
                  "seriesId": "000000000000000500085e0000000001",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000050008000000000006",
                  "kind": "episode",
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-06",
                  "seriesId": "000000000000000500085e0000000001",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000050008000000000007",
                  "kind": "episode",
                  "runtimeMs": "900000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-07",
                  "seriesId": "000000000000000500085e0000000001",
                  "season": 1,
                  "episode": 3
                },
                {
                  "id": "00000000000000050008000000000008",
                  "kind": "episode",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-08",
                  "seriesId": "000000000000000500085e0000000002",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000050008000000000009",
                  "kind": "episode",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-09",
                  "seriesId": "000000000000000500085e0000000002",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "0000000000000005000800000000000a",
                  "kind": "movie",
                  "runtimeMs": "720000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-10"
                },
                {
                  "id": "0000000000000005000800000000000b",
                  "kind": "movie",
                  "runtimeMs": "23580000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-11"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "576000000000",
                  "endMs": "576006000000",
                  "itemId": "00000000000000050008000000000001"
                },
                {
                  "startMs": "576006000000",
                  "endMs": "576006240000",
                  "break": true
                },
                {
                  "startMs": "576006240000",
                  "endMs": "576012840000",
                  "itemId": "00000000000000050008000000000002"
                },
                {
                  "startMs": "576012840000",
                  "endMs": "576018540000",
                  "itemId": "00000000000000050008000000000003"
                },
                {
                  "startMs": "576018540000",
                  "endMs": "576025740000",
                  "itemId": "00000000000000050008000000000004"
                },
                {
                  "startMs": "576025740000",
                  "endMs": "576025980000",
                  "break": true
                },
                {
                  "startMs": "576025980000",
                  "endMs": "576026580000",
                  "itemId": "00000000000000050008000000000008"
                },
                {
                  "startMs": "576026580000",
                  "endMs": "576028080000",
                  "itemId": "00000000000000050008000000000009"
                },
                {
                  "startMs": "576028080000",
                  "endMs": "576028800000",
                  "itemId": "0000000000000005000800000000000a"
                }
              ],
              "queries": [
                {
                  "t": "576000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "576006000001",
                  "k": 1,
                  "blockIndex": 1,
                  "nextIndexes": [
                    2
                  ]
                },
                {
                  "t": "576028799999",
                  "k": 3,
                  "blockIndex": 8,
                  "nextIndexes": [],
                  "offsetMs": "719999"
                }
              ],
              "errorTimes": [
                "575999999999",
                "576028800000"
              ]
            },
            {
              "name": "spread grid curated",
              "note": "Aligned, grid 30. Order i1 i3 i5 i7 i8 | i4 i6 i2 i9. c = 580, G = 720 - 600 = 120. By slot, i6 = 60, i2 = 90, i9 = 30: the best fit is {i2, i9} (120), ending at 710; first fit takes i6 and i9 and ends at 680. The later end is kept; one closing break.",
              "settings": {
                "seed": "9",
                "ordering": {
                  "mode": "curated"
                },
                "repeatGapMs": "10800000",
                "pairUpToMs": "2100000",
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": true,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050009000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050009000000000002",
                  "kind": "movie",
                  "runtimeMs": "4200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050009000000000003",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050009000000000004",
                  "kind": "movie",
                  "runtimeMs": "7800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050009000000000005",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050009000000000006",
                  "kind": "movie",
                  "runtimeMs": "2700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050009000000000007",
                  "kind": "movie",
                  "runtimeMs": "6900000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050009000000000008",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050009000000000009",
                  "kind": "movie",
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864006000000",
                  "itemId": "00000000000000050009000000000001"
                },
                {
                  "startMs": "864006000000",
                  "endMs": "864007200000",
                  "slate": true
                },
                {
                  "startMs": "864007200000",
                  "endMs": "864013800000",
                  "itemId": "00000000000000050009000000000003"
                },
                {
                  "startMs": "864013800000",
                  "endMs": "864014400000",
                  "slate": true
                },
                {
                  "startMs": "864014400000",
                  "endMs": "864020100000",
                  "itemId": "00000000000000050009000000000005"
                },
                {
                  "startMs": "864020100000",
                  "endMs": "864021600000",
                  "slate": true
                },
                {
                  "startMs": "864021600000",
                  "endMs": "864028500000",
                  "itemId": "00000000000000050009000000000007"
                },
                {
                  "startMs": "864028500000",
                  "endMs": "864028800000",
                  "slate": true
                },
                {
                  "startMs": "864028800000",
                  "endMs": "864034800000",
                  "itemId": "00000000000000050009000000000008"
                },
                {
                  "startMs": "864034800000",
                  "endMs": "864036000000",
                  "slate": true
                },
                {
                  "startMs": "864036000000",
                  "endMs": "864040200000",
                  "itemId": "00000000000000050009000000000002"
                },
                {
                  "startMs": "864040200000",
                  "endMs": "864041400000",
                  "slate": true
                },
                {
                  "startMs": "864041400000",
                  "endMs": "864042600000",
                  "itemId": "00000000000000050009000000000009"
                },
                {
                  "startMs": "864042600000",
                  "endMs": "864043200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864042600001",
                  "k": 1,
                  "blockIndex": 13,
                  "nextIndexes": []
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 13,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread grid ordered",
              "note": "chronological, aligned, grid 30. c = 335, G = 120. Best (grouped, by slot) = {P, Q}, ending at 470; first fit = {P, R, T}, ending at 475. First fit ends later and is kept; one closing break.",
              "settings": {
                "seed": "8",
                "ordering": {
                  "mode": "chronological"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": true,
                  "epochHours": 8,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "0000000000000005000a000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-01"
                },
                {
                  "id": "0000000000000005000a000000000002",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-02"
                },
                {
                  "id": "0000000000000005000a000000000003",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-03"
                },
                {
                  "id": "0000000000000005000a000000000004",
                  "kind": "movie",
                  "runtimeMs": "7800000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-04"
                },
                {
                  "id": "0000000000000005000a000000000005",
                  "kind": "movie",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-05"
                },
                {
                  "id": "0000000000000005000a000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-06"
                },
                {
                  "id": "0000000000000005000a000000000007",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-07"
                },
                {
                  "id": "0000000000000005000a000000000008",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-08"
                },
                {
                  "id": "0000000000000005000a000000000009",
                  "kind": "movie",
                  "runtimeMs": "16200000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-09"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "576000000000",
                  "endMs": "576006000000",
                  "itemId": "0000000000000005000a000000000001"
                },
                {
                  "startMs": "576006000000",
                  "endMs": "576007200000",
                  "slate": true
                },
                {
                  "startMs": "576007200000",
                  "endMs": "576013800000",
                  "itemId": "0000000000000005000a000000000002"
                },
                {
                  "startMs": "576013800000",
                  "endMs": "576014400000",
                  "slate": true
                },
                {
                  "startMs": "576014400000",
                  "endMs": "576020100000",
                  "itemId": "0000000000000005000a000000000003"
                },
                {
                  "startMs": "576020100000",
                  "endMs": "576021600000",
                  "slate": true
                },
                {
                  "startMs": "576021600000",
                  "endMs": "576024000000",
                  "itemId": "0000000000000005000a000000000005"
                },
                {
                  "startMs": "576024000000",
                  "endMs": "576025200000",
                  "slate": true
                },
                {
                  "startMs": "576025200000",
                  "endMs": "576027000000",
                  "itemId": "0000000000000005000a000000000006"
                },
                {
                  "startMs": "576027000000",
                  "endMs": "576028500000",
                  "itemId": "0000000000000005000a000000000008"
                },
                {
                  "startMs": "576028500000",
                  "endMs": "576028800000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "576000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "576028500001",
                  "k": 1,
                  "blockIndex": 10,
                  "nextIndexes": []
                },
                {
                  "t": "576028799999",
                  "k": 3,
                  "blockIndex": 10,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "575999999999",
                "576028800000"
              ]
            },
            {
              "name": "spread one-hour epoch",
              "note": "epochHours 1: k = 1, one break at c = 40. U = {i6, i4} (17 min). A = {i5} (18 min), but i5 aired in the last run (its run end is c), so it is dropped and A places 0. U wins; a 3-minute card.",
              "settings": {
                "seed": "11",
                "ordering": {
                  "mode": "shuffle"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 1,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "0000000000000005000b000000000001",
                  "kind": "movie",
                  "runtimeMs": "540000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000b000000000002",
                  "kind": "movie",
                  "runtimeMs": "300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000b000000000003",
                  "kind": "movie",
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000b000000000004",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000b000000000005",
                  "kind": "movie",
                  "runtimeMs": "1080000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000b000000000006",
                  "kind": "movie",
                  "runtimeMs": "720000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "72000000000",
                  "endMs": "72001320000",
                  "itemId": "0000000000000005000b000000000003"
                },
                {
                  "startMs": "72001320000",
                  "endMs": "72002400000",
                  "itemId": "0000000000000005000b000000000005"
                },
                {
                  "startMs": "72002400000",
                  "endMs": "72003120000",
                  "itemId": "0000000000000005000b000000000006"
                },
                {
                  "startMs": "72003120000",
                  "endMs": "72003420000",
                  "itemId": "0000000000000005000b000000000002"
                },
                {
                  "startMs": "72003420000",
                  "endMs": "72003600000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "72000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "72003420001",
                  "k": 1,
                  "blockIndex": 4,
                  "nextIndexes": []
                },
                {
                  "t": "72003599999",
                  "k": 3,
                  "blockIndex": 4,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "71999999999",
                "72003600000"
              ]
            },
            {
              "name": "spread shuffle",
              "note": "Shuffle order i2 i7 i3 i1 | i5 i6 i8 i9 i10 i4. c = 610, L = 110; the five shorts (100 min) are the fill. k = 4, but only the target 152.5 finds a point (150), so k' = 2. Unit j goes to break j mod 2: i6, i9, i4 at 150 and i8, i10 at c; two 5-minute cards.",
              "settings": {
                "seed": "12",
                "ordering": {
                  "mode": "shuffle"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "0000000000000005000c000000000001",
                  "kind": "movie",
                  "runtimeMs": "9300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000c000000000002",
                  "kind": "movie",
                  "runtimeMs": "9000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000c000000000003",
                  "kind": "movie",
                  "runtimeMs": "8700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000c000000000004",
                  "kind": "movie",
                  "runtimeMs": "660000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000c000000000005",
                  "kind": "movie",
                  "runtimeMs": "7800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000c000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000c000000000007",
                  "kind": "movie",
                  "runtimeMs": "9600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000c000000000008",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000c000000000009",
                  "kind": "movie",
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000c00000000000a",
                  "kind": "movie",
                  "runtimeMs": "840000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864009000000",
                  "itemId": "0000000000000005000c000000000002"
                },
                {
                  "startMs": "864009000000",
                  "endMs": "864010800000",
                  "itemId": "0000000000000005000c000000000006"
                },
                {
                  "startMs": "864010800000",
                  "endMs": "864012000000",
                  "itemId": "0000000000000005000c000000000009"
                },
                {
                  "startMs": "864012000000",
                  "endMs": "864012660000",
                  "itemId": "0000000000000005000c000000000004"
                },
                {
                  "startMs": "864012660000",
                  "endMs": "864012960000",
                  "break": true
                },
                {
                  "startMs": "864012960000",
                  "endMs": "864022560000",
                  "itemId": "0000000000000005000c000000000007"
                },
                {
                  "startMs": "864022560000",
                  "endMs": "864031260000",
                  "itemId": "0000000000000005000c000000000003"
                },
                {
                  "startMs": "864031260000",
                  "endMs": "864040560000",
                  "itemId": "0000000000000005000c000000000001"
                },
                {
                  "startMs": "864040560000",
                  "endMs": "864042060000",
                  "itemId": "0000000000000005000c000000000008"
                },
                {
                  "startMs": "864042060000",
                  "endMs": "864042900000",
                  "itemId": "0000000000000005000c00000000000a"
                },
                {
                  "startMs": "864042900000",
                  "endMs": "864043200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864012660001",
                  "k": 1,
                  "blockIndex": 4,
                  "nextIndexes": [
                    5
                  ]
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 10,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread S2 point skipped",
              "note": "chronological, 12 h, seven 100-minute films: run ends 100 to 700. k = 4 (R0 = 12 min); targets are (c - S) / 4 apart: 175 -> 200, 350 -> 400, 525 -> none separated, skipped. So k' = 3 (dividing by k' would give 200, 500 instead). Three 4-minute cards; the fill A1 goes after the last card.",
              "settings": {
                "seed": "8",
                "ordering": {
                  "mode": "chronological"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "0000000000000005000d000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-01"
                },
                {
                  "id": "0000000000000005000d000000000002",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-02"
                },
                {
                  "id": "0000000000000005000d000000000003",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-03"
                },
                {
                  "id": "0000000000000005000d000000000004",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-04"
                },
                {
                  "id": "0000000000000005000d000000000005",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-05"
                },
                {
                  "id": "0000000000000005000d000000000006",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-06"
                },
                {
                  "id": "0000000000000005000d000000000007",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-07"
                },
                {
                  "id": "0000000000000005000d000000000008",
                  "kind": "movie",
                  "runtimeMs": "3600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-08"
                },
                {
                  "id": "0000000000000005000d000000000009",
                  "kind": "episode",
                  "runtimeMs": "480000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-09",
                  "seriesId": "0000000000000005000d5e0000000001",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "0000000000000005000d00000000000a",
                  "kind": "movie",
                  "runtimeMs": "40320000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-10"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864006000000",
                  "itemId": "0000000000000005000d000000000001"
                },
                {
                  "startMs": "864006000000",
                  "endMs": "864012000000",
                  "itemId": "0000000000000005000d000000000002"
                },
                {
                  "startMs": "864012000000",
                  "endMs": "864012240000",
                  "break": true
                },
                {
                  "startMs": "864012240000",
                  "endMs": "864018240000",
                  "itemId": "0000000000000005000d000000000003"
                },
                {
                  "startMs": "864018240000",
                  "endMs": "864024240000",
                  "itemId": "0000000000000005000d000000000004"
                },
                {
                  "startMs": "864024240000",
                  "endMs": "864024480000",
                  "break": true
                },
                {
                  "startMs": "864024480000",
                  "endMs": "864030480000",
                  "itemId": "0000000000000005000d000000000005"
                },
                {
                  "startMs": "864030480000",
                  "endMs": "864036480000",
                  "itemId": "0000000000000005000d000000000006"
                },
                {
                  "startMs": "864036480000",
                  "endMs": "864042480000",
                  "itemId": "0000000000000005000d000000000007"
                },
                {
                  "startMs": "864042480000",
                  "endMs": "864042720000",
                  "break": true
                },
                {
                  "startMs": "864042720000",
                  "endMs": "864043200000",
                  "itemId": "0000000000000005000d000000000009"
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864012000001",
                  "k": 1,
                  "blockIndex": 2,
                  "nextIndexes": [
                    3
                  ]
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 10,
                  "nextIndexes": [],
                  "offsetMs": "479999"
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread S2 nearest tie",
              "note": "chronological, run ends 90, 180, 270, 360, 450 (c). k = 2 (R0 = 150 s), target 225 is 45 minutes from both 180 and 270: the smaller, 180, is taken. m = 2 cards of 75 s.",
              "settings": {
                "seed": "8",
                "ordering": {
                  "mode": "chronological"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 8,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "0000000000000005000e000000000001",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-01"
                },
                {
                  "id": "0000000000000005000e000000000002",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-02"
                },
                {
                  "id": "0000000000000005000e000000000003",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-03"
                },
                {
                  "id": "0000000000000005000e000000000004",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-04"
                },
                {
                  "id": "0000000000000005000e000000000005",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-05"
                },
                {
                  "id": "0000000000000005000e000000000006",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-06"
                },
                {
                  "id": "0000000000000005000e000000000007",
                  "kind": "episode",
                  "runtimeMs": "1650000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-07",
                  "seriesId": "0000000000000005000e5e0000000001",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "0000000000000005000e000000000008",
                  "kind": "movie",
                  "runtimeMs": "25950000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-08"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "576000000000",
                  "endMs": "576005400000",
                  "itemId": "0000000000000005000e000000000001"
                },
                {
                  "startMs": "576005400000",
                  "endMs": "576010800000",
                  "itemId": "0000000000000005000e000000000002"
                },
                {
                  "startMs": "576010800000",
                  "endMs": "576010875000",
                  "break": true
                },
                {
                  "startMs": "576010875000",
                  "endMs": "576016275000",
                  "itemId": "0000000000000005000e000000000003"
                },
                {
                  "startMs": "576016275000",
                  "endMs": "576021675000",
                  "itemId": "0000000000000005000e000000000004"
                },
                {
                  "startMs": "576021675000",
                  "endMs": "576027075000",
                  "itemId": "0000000000000005000e000000000005"
                },
                {
                  "startMs": "576027075000",
                  "endMs": "576027150000",
                  "break": true
                },
                {
                  "startMs": "576027150000",
                  "endMs": "576028800000",
                  "itemId": "0000000000000005000e000000000007"
                }
              ],
              "queries": [
                {
                  "t": "576000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "576010800001",
                  "k": 1,
                  "blockIndex": 2,
                  "nextIndexes": [
                    3
                  ]
                },
                {
                  "t": "576028799999",
                  "k": 3,
                  "blockIndex": 7,
                  "nextIndexes": [],
                  "offsetMs": "1649999"
                }
              ],
              "errorTimes": [
                "575999999999",
                "576028800000"
              ]
            },
            {
              "name": "spread fewer cards than breaks",
              "note": "Shuffle order i1 i10 i9 i7 i4 i6 | i5 i2 i3 i8. L = 50 min, fill i2, i3, i8 (47 min 29.5 s), R = 150.5 s. k = 4, k' = 3 (points 220, 425, 670), m = min(3, 2) = 2: cards on breaks 0 and 2 (75 s, and 75.5 s with the remainder); break 1 has no card.",
              "settings": {
                "seed": "15",
                "ordering": {
                  "mode": "shuffle"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "0000000000000005000f000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000f000000000002",
                  "kind": "movie",
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000f000000000003",
                  "kind": "movie",
                  "runtimeMs": "1020000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000f000000000004",
                  "kind": "movie",
                  "runtimeMs": "6300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000f000000000005",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000f000000000006",
                  "kind": "movie",
                  "runtimeMs": "8400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000f000000000007",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000f000000000008",
                  "kind": "movie",
                  "runtimeMs": "629500",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000f000000000009",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005000f00000000000a",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864006000000",
                  "itemId": "0000000000000005000f000000000001"
                },
                {
                  "startMs": "864006000000",
                  "endMs": "864013200000",
                  "itemId": "0000000000000005000f00000000000a"
                },
                {
                  "startMs": "864013200000",
                  "endMs": "864014400000",
                  "itemId": "0000000000000005000f000000000002"
                },
                {
                  "startMs": "864014400000",
                  "endMs": "864014475000",
                  "break": true
                },
                {
                  "startMs": "864014475000",
                  "endMs": "864020175000",
                  "itemId": "0000000000000005000f000000000009"
                },
                {
                  "startMs": "864020175000",
                  "endMs": "864026775000",
                  "itemId": "0000000000000005000f000000000007"
                },
                {
                  "startMs": "864026775000",
                  "endMs": "864027795000",
                  "itemId": "0000000000000005000f000000000003"
                },
                {
                  "startMs": "864027795000",
                  "endMs": "864034095000",
                  "itemId": "0000000000000005000f000000000004"
                },
                {
                  "startMs": "864034095000",
                  "endMs": "864042495000",
                  "itemId": "0000000000000005000f000000000006"
                },
                {
                  "startMs": "864042495000",
                  "endMs": "864043124500",
                  "itemId": "0000000000000005000f000000000008"
                },
                {
                  "startMs": "864043124500",
                  "endMs": "864043200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864014400001",
                  "k": 1,
                  "blockIndex": 3,
                  "nextIndexes": [
                    4
                  ]
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 10,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread short remainder",
              "note": "Shuffle order i1 i3 i2 i7 | i4 i6 i8 i5. L = 55 min, fill i6 + i8 = 54 min 20 s, R = 40 s. k = 2 (points 210, 425); m = 1, one 40-second card on the last break.",
              "settings": {
                "seed": "16",
                "ordering": {
                  "mode": "shuffle"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 8,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050010000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050010000000000002",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050010000000000003",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050010000000000004",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050010000000000005",
                  "kind": "movie",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050010000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050010000000000007",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050010000000000008",
                  "kind": "movie",
                  "runtimeMs": "1460000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "576000000000",
                  "endMs": "576006000000",
                  "itemId": "00000000000000050010000000000001"
                },
                {
                  "startMs": "576006000000",
                  "endMs": "576012600000",
                  "itemId": "00000000000000050010000000000003"
                },
                {
                  "startMs": "576012600000",
                  "endMs": "576014400000",
                  "itemId": "00000000000000050010000000000006"
                },
                {
                  "startMs": "576014400000",
                  "endMs": "576020100000",
                  "itemId": "00000000000000050010000000000002"
                },
                {
                  "startMs": "576020100000",
                  "endMs": "576027300000",
                  "itemId": "00000000000000050010000000000007"
                },
                {
                  "startMs": "576027300000",
                  "endMs": "576028760000",
                  "itemId": "00000000000000050010000000000008"
                },
                {
                  "startMs": "576028760000",
                  "endMs": "576028800000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "576000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "576028760001",
                  "k": 1,
                  "blockIndex": 6,
                  "nextIndexes": []
                },
                {
                  "t": "576028799999",
                  "k": 3,
                  "blockIndex": 6,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "575999999999",
                "576028800000"
              ]
            },
            {
              "name": "spread first fit beats best fit",
              "note": "82 items, 2-hour day: 14 seven-minute shorts and the 3-minute i5 air, then the 115-minute i65 does not fit (c = 101, L = 19). The visit has 66 unaired sevens, so the 64-candidate cap keeps only sevens for U and A (14 min). First fit takes two sevens and, after the wrap, the aired i5 (its run ended at 38, before c): 17 min. firstFit wins; a 2-minute card.",
              "settings": {
                "seed": "1",
                "ordering": {
                  "mode": "shuffle"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 2,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050011000000000001",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000002",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000003",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000004",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000005",
                  "kind": "movie",
                  "runtimeMs": "180000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000006",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000007",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000008",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000009",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000000a",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000000b",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000000c",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000000d",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000000e",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000000f",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000010",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000011",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000012",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000013",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000014",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000015",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000016",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000017",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000018",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000019",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000001a",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000001b",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000001c",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000001d",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000001e",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000001f",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000020",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000021",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000022",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000023",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000024",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000025",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000026",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000027",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000028",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000029",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000002a",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000002b",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000002c",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000002d",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000002e",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000002f",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000030",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000031",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000032",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000033",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000034",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000035",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000036",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000037",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000038",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000039",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000003a",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000003b",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000003c",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000003d",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000003e",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000003f",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000040",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000041",
                  "kind": "movie",
                  "runtimeMs": "6900000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000042",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000043",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000044",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000045",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000046",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000047",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000048",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000049",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000004a",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000004b",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000004c",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000004d",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000004e",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001100000000004f",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000050",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000051",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050011000000000052",
                  "kind": "movie",
                  "runtimeMs": "420000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "144000000000",
                  "endMs": "144000420000",
                  "itemId": "00000000000000050011000000000017"
                },
                {
                  "startMs": "144000420000",
                  "endMs": "144000840000",
                  "itemId": "00000000000000050011000000000010"
                },
                {
                  "startMs": "144000840000",
                  "endMs": "144001260000",
                  "itemId": "0000000000000005001100000000002e"
                },
                {
                  "startMs": "144001260000",
                  "endMs": "144001680000",
                  "itemId": "0000000000000005001100000000003e"
                },
                {
                  "startMs": "144001680000",
                  "endMs": "144002100000",
                  "itemId": "00000000000000050011000000000015"
                },
                {
                  "startMs": "144002100000",
                  "endMs": "144002280000",
                  "itemId": "00000000000000050011000000000005"
                },
                {
                  "startMs": "144002280000",
                  "endMs": "144002700000",
                  "itemId": "00000000000000050011000000000042"
                },
                {
                  "startMs": "144002700000",
                  "endMs": "144003120000",
                  "itemId": "0000000000000005001100000000002b"
                },
                {
                  "startMs": "144003120000",
                  "endMs": "144003540000",
                  "itemId": "00000000000000050011000000000004"
                },
                {
                  "startMs": "144003540000",
                  "endMs": "144003960000",
                  "itemId": "0000000000000005001100000000001d"
                },
                {
                  "startMs": "144003960000",
                  "endMs": "144004380000",
                  "itemId": "0000000000000005001100000000004c"
                },
                {
                  "startMs": "144004380000",
                  "endMs": "144004800000",
                  "itemId": "00000000000000050011000000000034"
                },
                {
                  "startMs": "144004800000",
                  "endMs": "144005220000",
                  "itemId": "00000000000000050011000000000047"
                },
                {
                  "startMs": "144005220000",
                  "endMs": "144005640000",
                  "itemId": "0000000000000005001100000000004b"
                },
                {
                  "startMs": "144005640000",
                  "endMs": "144006060000",
                  "itemId": "00000000000000050011000000000028"
                },
                {
                  "startMs": "144006060000",
                  "endMs": "144006480000",
                  "itemId": "00000000000000050011000000000040"
                },
                {
                  "startMs": "144006480000",
                  "endMs": "144006900000",
                  "itemId": "00000000000000050011000000000001"
                },
                {
                  "startMs": "144006900000",
                  "endMs": "144007080000",
                  "itemId": "00000000000000050011000000000005"
                },
                {
                  "startMs": "144007080000",
                  "endMs": "144007200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "144000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "144007080001",
                  "k": 1,
                  "blockIndex": 18,
                  "nextIndexes": []
                },
                {
                  "t": "144007199999",
                  "k": 3,
                  "blockIndex": 18,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "143999999999",
                "144007200000"
              ]
            },
            {
              "name": "spread grouped several prefixes",
              "note": "chronological. Visit after c: F5 (50, cut), B1 6, A1 4, A2 3, A3 5. Budget 12.5 min (750 s). Group A's options are [A1] 4, [A1 A2] 7, [A1 A2 A3] 12; with B1 the best is 12, all of A. First fit takes B1 and A1 (10), then A2 does not fit and A is passed over. G wins; a 30-second card, then A1 A2 A3.",
              "settings": {
                "seed": "8",
                "ordering": {
                  "mode": "chronological"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 8,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050012000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-01"
                },
                {
                  "id": "00000000000000050012000000000002",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-02"
                },
                {
                  "id": "00000000000000050012000000000003",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-03"
                },
                {
                  "id": "00000000000000050012000000000004",
                  "kind": "movie",
                  "runtimeMs": "8250000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-04"
                },
                {
                  "id": "00000000000000050012000000000005",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-05"
                },
                {
                  "id": "00000000000000050012000000000006",
                  "kind": "episode",
                  "runtimeMs": "360000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-02-01",
                  "seriesId": "000000000000000500125e0000000002",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000050012000000000007",
                  "kind": "episode",
                  "runtimeMs": "240000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-03-01",
                  "seriesId": "000000000000000500125e0000000001",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000050012000000000008",
                  "kind": "episode",
                  "runtimeMs": "180000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-03-02",
                  "seriesId": "000000000000000500125e0000000001",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000050012000000000009",
                  "kind": "episode",
                  "runtimeMs": "300000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-03-03",
                  "seriesId": "000000000000000500125e0000000001",
                  "season": 1,
                  "episode": 3
                },
                {
                  "id": "0000000000000005001200000000000a",
                  "kind": "movie",
                  "runtimeMs": "25470000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-12-31"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "576000000000",
                  "endMs": "576006000000",
                  "itemId": "00000000000000050012000000000001"
                },
                {
                  "startMs": "576006000000",
                  "endMs": "576012600000",
                  "itemId": "00000000000000050012000000000002"
                },
                {
                  "startMs": "576012600000",
                  "endMs": "576019800000",
                  "itemId": "00000000000000050012000000000003"
                },
                {
                  "startMs": "576019800000",
                  "endMs": "576028050000",
                  "itemId": "00000000000000050012000000000004"
                },
                {
                  "startMs": "576028050000",
                  "endMs": "576028080000",
                  "break": true
                },
                {
                  "startMs": "576028080000",
                  "endMs": "576028320000",
                  "itemId": "00000000000000050012000000000007"
                },
                {
                  "startMs": "576028320000",
                  "endMs": "576028500000",
                  "itemId": "00000000000000050012000000000008"
                },
                {
                  "startMs": "576028500000",
                  "endMs": "576028800000",
                  "itemId": "00000000000000050012000000000009"
                }
              ],
              "queries": [
                {
                  "t": "576000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "576028050001",
                  "k": 1,
                  "blockIndex": 4,
                  "nextIndexes": [
                    5
                  ]
                },
                {
                  "t": "576028799999",
                  "k": 3,
                  "blockIndex": 7,
                  "nextIndexes": [],
                  "offsetMs": "299999"
                }
              ],
              "errorTimes": [
                "575999999999",
                "576028800000"
              ]
            },
            {
              "name": "spread unit dropped",
              "note": "curated, repeatGapMs 400 min. Order: F2 F3 F5 F6 F4 Y1 F1 | F7 b Y2; Y1 and Y2 are one series. F = b, Y2. b takes break 0 (205). Y2 is unit 1: break 1 (425) and break 2 (690) are before Y1's end plus the gap (990), and it is never tried at break 0: it is dropped. Its time goes to the cards.",
              "settings": {
                "seed": "54",
                "ordering": {
                  "mode": "curated"
                },
                "repeatGapMs": "24000000",
                "pairUpToMs": "2100000",
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050013000000000001",
                  "kind": "episode",
                  "runtimeMs": "2700000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-01",
                  "seriesId": "000000000000000500135e0000000001",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000050013000000000002",
                  "kind": "episode",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-02",
                  "seriesId": "000000000000000500135e0000000001",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000050013000000000003",
                  "kind": "movie",
                  "runtimeMs": "480000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050013000000000004",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050013000000000005",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050013000000000006",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050013000000000007",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050013000000000008",
                  "kind": "movie",
                  "runtimeMs": "6300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050013000000000009",
                  "kind": "movie",
                  "runtimeMs": "6900000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001300000000000a",
                  "kind": "movie",
                  "runtimeMs": "7500000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864006600000",
                  "itemId": "00000000000000050013000000000005"
                },
                {
                  "startMs": "864006600000",
                  "endMs": "864012300000",
                  "itemId": "00000000000000050013000000000006"
                },
                {
                  "startMs": "864012300000",
                  "endMs": "864012780000",
                  "itemId": "00000000000000050013000000000003"
                },
                {
                  "startMs": "864012780000",
                  "endMs": "864013220000",
                  "break": true
                },
                {
                  "startMs": "864013220000",
                  "endMs": "864019520000",
                  "itemId": "00000000000000050013000000000008"
                },
                {
                  "startMs": "864019520000",
                  "endMs": "864026420000",
                  "itemId": "00000000000000050013000000000009"
                },
                {
                  "startMs": "864026420000",
                  "endMs": "864026860000",
                  "break": true
                },
                {
                  "startMs": "864026860000",
                  "endMs": "864034060000",
                  "itemId": "00000000000000050013000000000007"
                },
                {
                  "startMs": "864034060000",
                  "endMs": "864036760000",
                  "itemId": "00000000000000050013000000000001"
                },
                {
                  "startMs": "864036760000",
                  "endMs": "864042760000",
                  "itemId": "00000000000000050013000000000004"
                },
                {
                  "startMs": "864042760000",
                  "endMs": "864043200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864012780001",
                  "k": 1,
                  "blockIndex": 3,
                  "nextIndexes": [
                    4
                  ]
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 10,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread plan tie",
              "note": "chronological. Visit after c: F5 (40, cut), a 20, b 15, c 15, d 10; L = 30.5 min. G = {b, c} (30); first fit = {a, d} (30). Equal placed sums: the earlier candidate, G, is kept. A 30-second card, then b and c.",
              "settings": {
                "seed": "8",
                "ordering": {
                  "mode": "chronological"
                },
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 8,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050014000000000001",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-01"
                },
                {
                  "id": "00000000000000050014000000000002",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-02"
                },
                {
                  "id": "00000000000000050014000000000003",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-03"
                },
                {
                  "id": "00000000000000050014000000000004",
                  "kind": "movie",
                  "runtimeMs": "7170000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-04"
                },
                {
                  "id": "00000000000000050014000000000005",
                  "kind": "movie",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-05"
                },
                {
                  "id": "00000000000000050014000000000006",
                  "kind": "movie",
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-06"
                },
                {
                  "id": "00000000000000050014000000000007",
                  "kind": "movie",
                  "runtimeMs": "900000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-07"
                },
                {
                  "id": "00000000000000050014000000000008",
                  "kind": "movie",
                  "runtimeMs": "900000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-08"
                },
                {
                  "id": "00000000000000050014000000000009",
                  "kind": "movie",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-09"
                },
                {
                  "id": "0000000000000005001400000000000a",
                  "kind": "movie",
                  "runtimeMs": "24630000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2000-01-10"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "576000000000",
                  "endMs": "576006000000",
                  "itemId": "00000000000000050014000000000001"
                },
                {
                  "startMs": "576006000000",
                  "endMs": "576012600000",
                  "itemId": "00000000000000050014000000000002"
                },
                {
                  "startMs": "576012600000",
                  "endMs": "576019800000",
                  "itemId": "00000000000000050014000000000003"
                },
                {
                  "startMs": "576019800000",
                  "endMs": "576026970000",
                  "itemId": "00000000000000050014000000000004"
                },
                {
                  "startMs": "576026970000",
                  "endMs": "576027000000",
                  "break": true
                },
                {
                  "startMs": "576027000000",
                  "endMs": "576027900000",
                  "itemId": "00000000000000050014000000000007"
                },
                {
                  "startMs": "576027900000",
                  "endMs": "576028800000",
                  "itemId": "00000000000000050014000000000008"
                }
              ],
              "queries": [
                {
                  "t": "576000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "576026970001",
                  "k": 1,
                  "blockIndex": 4,
                  "nextIndexes": [
                    5
                  ]
                },
                {
                  "t": "576028799999",
                  "k": 3,
                  "blockIndex": 6,
                  "nextIndexes": [],
                  "offsetMs": "899999"
                }
              ],
              "errorTimes": [
                "575999999999",
                "576028800000"
              ]
            },
            {
              "name": "spread repeat gap unshifted",
              "note": "curated, repeatGapMs 430 min. Order: F6 F5 v Z1 F4 F3 F1 F2 | F7 u Z2; Z1 and Z2 are one series. k = 2: points 389 and 694. u takes break 0. Z2 (unit 1) needs a point at or after 269 + 430 = 699 on unshifted times; c = 694, so it is dropped. On shifted times break 1 would start at 714.5 and Z2 would wrongly be placed.",
              "settings": {
                "seed": "34",
                "ordering": {
                  "mode": "curated"
                },
                "repeatGapMs": "25800000",
                "pairUpToMs": "2100000",
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": false,
                  "epochHours": 12,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050015000000000001",
                  "kind": "episode",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-01",
                  "seriesId": "000000000000000500155e0000000001",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000050015000000000002",
                  "kind": "episode",
                  "runtimeMs": "660000",
                  "createdAtMs": "-1000000000000",
                  "premiereDate": "2001-01-02",
                  "seriesId": "000000000000000500155e0000000001",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000050015000000000003",
                  "kind": "movie",
                  "runtimeMs": "900000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050015000000000004",
                  "kind": "movie",
                  "runtimeMs": "540000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050015000000000005",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050015000000000006",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050015000000000007",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050015000000000008",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050015000000000009",
                  "kind": "movie",
                  "runtimeMs": "6300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001500000000000a",
                  "kind": "movie",
                  "runtimeMs": "6900000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000005001500000000000b",
                  "kind": "movie",
                  "runtimeMs": "7500000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "864000000000",
                  "endMs": "864006900000",
                  "itemId": "0000000000000005001500000000000a"
                },
                {
                  "startMs": "864006900000",
                  "endMs": "864013200000",
                  "itemId": "00000000000000050015000000000009"
                },
                {
                  "startMs": "864013200000",
                  "endMs": "864013740000",
                  "itemId": "00000000000000050015000000000004"
                },
                {
                  "startMs": "864013740000",
                  "endMs": "864016140000",
                  "itemId": "00000000000000050015000000000001"
                },
                {
                  "startMs": "864016140000",
                  "endMs": "864023340000",
                  "itemId": "00000000000000050015000000000008"
                },
                {
                  "startMs": "864023340000",
                  "endMs": "864024240000",
                  "itemId": "00000000000000050015000000000003"
                },
                {
                  "startMs": "864024240000",
                  "endMs": "864024570000",
                  "break": true
                },
                {
                  "startMs": "864024570000",
                  "endMs": "864030270000",
                  "itemId": "00000000000000050015000000000007"
                },
                {
                  "startMs": "864030270000",
                  "endMs": "864036270000",
                  "itemId": "00000000000000050015000000000005"
                },
                {
                  "startMs": "864036270000",
                  "endMs": "864042870000",
                  "itemId": "00000000000000050015000000000006"
                },
                {
                  "startMs": "864042870000",
                  "endMs": "864043200000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "864000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "864024240001",
                  "k": 1,
                  "blockIndex": 6,
                  "nextIndexes": [
                    7
                  ]
                },
                {
                  "t": "864043199999",
                  "k": 3,
                  "blockIndex": 10,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "863999999999",
                "864043200000"
              ]
            },
            {
              "name": "spread grid prefers unaired",
              "note": "Aligned, grid 30. Order i9 i5 i6 i4 | i7 i8 i3 i2 i1; i9 (25 min) aired at the start. G = 90. The unaired best fit is {i8} (slot 60); the best fit of all items is {i8, i9} (slot 90), strictly more, so it is kept and i9 airs again (no repeat gap or run rule on the grid). First fit ends at the same 475, so it does not replace it.",
              "settings": {
                "seed": "22",
                "ordering": {
                  "mode": "curated"
                },
                "repeatGapMs": "10800000",
                "pairUpToMs": "2100000",
                "timing": {
                  "blockLength": 1,
                  "alignToGrid": true,
                  "epochHours": 8,
                  "graceHours": 6,
                  "gridMinutes": 30,
                  "minRuntimeSeconds": 60,
                  "broadcastDay": {
                    "utcOffsetMinutes": 0,
                    "start": "00:00"
                  }
                }
              },
              "items": [
                {
                  "id": "00000000000000050016000000000001",
                  "kind": "movie",
                  "runtimeMs": "2700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050016000000000002",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050016000000000003",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050016000000000004",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050016000000000005",
                  "kind": "movie",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050016000000000006",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050016000000000007",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050016000000000008",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000050016000000000009",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "20000",
              "blocks": [
                {
                  "startMs": "576000000000",
                  "endMs": "576001500000",
                  "itemId": "00000000000000050016000000000009"
                },
                {
                  "startMs": "576001500000",
                  "endMs": "576001800000",
                  "slate": true
                },
                {
                  "startMs": "576001800000",
                  "endMs": "576007800000",
                  "itemId": "00000000000000050016000000000005"
                },
                {
                  "startMs": "576007800000",
                  "endMs": "576009000000",
                  "slate": true
                },
                {
                  "startMs": "576009000000",
                  "endMs": "576015600000",
                  "itemId": "00000000000000050016000000000006"
                },
                {
                  "startMs": "576015600000",
                  "endMs": "576016200000",
                  "slate": true
                },
                {
                  "startMs": "576016200000",
                  "endMs": "576021900000",
                  "itemId": "00000000000000050016000000000004"
                },
                {
                  "startMs": "576021900000",
                  "endMs": "576023400000",
                  "slate": true
                },
                {
                  "startMs": "576023400000",
                  "endMs": "576026400000",
                  "itemId": "00000000000000050016000000000008"
                },
                {
                  "startMs": "576026400000",
                  "endMs": "576027000000",
                  "slate": true
                },
                {
                  "startMs": "576027000000",
                  "endMs": "576028500000",
                  "itemId": "00000000000000050016000000000009"
                },
                {
                  "startMs": "576028500000",
                  "endMs": "576028800000",
                  "break": true
                }
              ],
              "queries": [
                {
                  "t": "576000000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "576028500001",
                  "k": 1,
                  "blockIndex": 11,
                  "nextIndexes": []
                },
                {
                  "t": "576028799999",
                  "k": 3,
                  "blockIndex": 11,
                  "nextIndexes": []
                }
              ],
              "errorTimes": [
                "575999999999",
                "576028800000"
              ]
            }
          ]
        }
        """.trimIndent() + "\n"
}
