// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler.vectors

/**
 * DRAFT day timelines from the independent reference (spec 08, task 3.0), held exactly as the reference wrote them.
 * They are not published under `docs/rules-format/vectors/`, so nothing checks this copy against a file. Task 4.0
 * replaces them with `lineup.json`. A case is never changed to suit the code.
 */
@Suppress("LargeClass") // Data only: one draft file.
internal object TimelineDraftVectorFile {
    val text: String =
        """
        {
          "description": "DRAFT, not a final vector file. Day timelines from the reference for task 3.0, so an implementer can sanity-check build_day and Day.whatsOn before lineup.json is generated. Each case: settings, items (runtimeMs, createdAtMs, optional deletedAtMs as signed decimal strings), n, the day's blocks (half-open [start, end), milliseconds since the Unix epoch, itemId or slate, role = run / fill / slate, informational only) and queries: for each t, the index of the block containing it, the media offset (mediaStartOffset + t - blockStart) and the indexes of the next k blocks within the day. Rules implemented from spec 08 Unit 3; the cases marked in their note use a reading of text the spec leaves open. Seeds are objects (dec/hex/hi/lo).",
          "source": "Computed with an independent Python reference written from the format doc and spec 08 only, without reading any Kotlin.",
          "vectors": [
            {
              "name": "shuffle, aligned 30, six movies, 6 h epochs (slates before grid marks)",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 6,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "21600000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "5700000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "5700000",
                    "end": "7200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "7200000",
                    "end": "8520000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "8520000",
                    "end": "9000000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "9000000",
                    "end": "11400000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "11400000",
                    "end": "12600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "12600000",
                    "end": "15600000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "15600000",
                    "end": "16200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "16200000",
                    "end": "17700000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "17700000",
                    "end": "18000000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "18000000",
                    "end": "19800000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "19800000",
                    "end": "21120000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "21120000",
                    "end": "21600000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 6
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "5699999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "5699999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "5700000",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "7199999",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "7200000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "8519999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1319999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "10800000",
                  "k": 2,
                  "blockIndex": 4,
                  "offsetMs": "1800000",
                  "nextBlockIndexes": [
                    5,
                    6
                  ]
                },
                {
                  "t": "21120000",
                  "k": 2,
                  "blockIndex": 12,
                  "nextBlockIndexes": []
                },
                {
                  "t": "21599999",
                  "k": 2,
                  "blockIndex": 12,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "shuffle, not aligned, back to back",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": false,
                "gridMinutes": 30,
                "epochHours": 6,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "21600000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "5700000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "5700000",
                    "end": "7020000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "7020000",
                    "end": "9420000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "9420000",
                    "end": "12420000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "12420000",
                    "end": "13920000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "13920000",
                    "end": "15720000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "15720000",
                    "end": "21420000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "21420000",
                    "end": "21600000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 6
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "5699999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "5699999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "5700000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "7019999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1319999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "7020000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "9419999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "2399999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "10800000",
                  "k": 2,
                  "blockIndex": 3,
                  "offsetMs": "1380000",
                  "nextBlockIndexes": [
                    4,
                    5
                  ]
                },
                {
                  "t": "21420000",
                  "k": 2,
                  "blockIndex": 7,
                  "nextBlockIndexes": []
                },
                {
                  "t": "21599999",
                  "k": 2,
                  "blockIndex": 7,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "shuffle, grid 15",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 15,
                "epochHours": 6,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "3",
              "day": {
                "epochStart": "64800000",
                "epochEnd": "86400000",
                "blocks": [
                  {
                    "start": "64800000",
                    "end": "70500000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "70500000",
                    "end": "71100000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "71100000",
                    "end": "72600000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "72600000",
                    "end": "72900000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "72900000",
                    "end": "75900000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "75900000",
                    "end": "76500000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "76500000",
                    "end": "77820000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "77820000",
                    "end": "78300000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "78300000",
                    "end": "80700000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "80700000",
                    "end": "81000000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "81000000",
                    "end": "82800000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "82800000",
                    "end": "84300000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "84300000",
                    "end": "84600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "84600000",
                    "end": "85920000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "85920000",
                    "end": "86400000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 6
              },
              "queries": [
                {
                  "t": "64800000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "70499999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "5699999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "70500000",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "71099999",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "71100000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "72599999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1499999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "75600000",
                  "k": 2,
                  "blockIndex": 4,
                  "offsetMs": "2700000",
                  "nextBlockIndexes": [
                    5,
                    6
                  ]
                },
                {
                  "t": "85920000",
                  "k": 2,
                  "blockIndex": 14,
                  "nextBlockIndexes": []
                },
                {
                  "t": "86399999",
                  "k": 2,
                  "blockIndex": 14,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "shuffle, grid 60, start 06:00",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 60,
                "epochHours": 12,
                "utcOffsetMinutes": 0,
                "start": "06:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "2",
              "day": {
                "epochStart": "108000000",
                "epochEnd": "151200000",
                "blocks": [
                  {
                    "start": "108000000",
                    "end": "110400000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "110400000",
                    "end": "111600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "111600000",
                    "end": "113100000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "113100000",
                    "end": "115200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "115200000",
                    "end": "117000000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "117000000",
                    "end": "118800000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "118800000",
                    "end": "120120000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "120120000",
                    "end": "122400000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "122400000",
                    "end": "128100000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "128100000",
                    "end": "129600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "129600000",
                    "end": "132600000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "132600000",
                    "end": "133200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "133200000",
                    "end": "135600000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "135600000",
                    "end": "136800000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "136800000",
                    "end": "138300000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "138300000",
                    "end": "140400000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "140400000",
                    "end": "142200000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "142200000",
                    "end": "144000000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "144000000",
                    "end": "145320000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "145320000",
                    "end": "147600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "147600000",
                    "end": "150600000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "150600000",
                    "end": "151200000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 6
              },
              "queries": [
                {
                  "t": "108000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "110399999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "2399999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "110400000",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "111599999",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "111600000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "113099999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1499999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "129600000",
                  "k": 2,
                  "blockIndex": 10,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    11,
                    12
                  ]
                },
                {
                  "t": "150600000",
                  "k": 2,
                  "blockIndex": 21,
                  "nextBlockIndexes": []
                },
                {
                  "t": "151199999",
                  "k": 2,
                  "blockIndex": 21,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "shuffle, blockLength 2, aligned",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 2,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 8,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "1",
              "day": {
                "epochStart": "28800000",
                "epochEnd": "57600000",
                "blocks": [
                  {
                    "start": "28800000",
                    "end": "30120000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "30120000",
                    "end": "32520000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "32520000",
                    "end": "34200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "34200000",
                    "end": "36000000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "36000000",
                    "end": "39000000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "39000000",
                    "end": "39600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "39600000",
                    "end": "41100000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "41100000",
                    "end": "46800000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "46800000",
                    "end": "48120000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "48120000",
                    "end": "50520000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "50520000",
                    "end": "52200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "52200000",
                    "end": "54000000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "54000000",
                    "end": "57000000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "57000000",
                    "end": "57600000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 3
              },
              "queries": [
                {
                  "t": "28800000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "30119999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1319999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "30120000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "32519999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "2399999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "32520000",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "34199999",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "43200000",
                  "k": 2,
                  "blockIndex": 7,
                  "offsetMs": "2100000",
                  "nextBlockIndexes": [
                    8,
                    9
                  ]
                },
                {
                  "t": "57000000",
                  "k": 2,
                  "blockIndex": 13,
                  "nextBlockIndexes": []
                },
                {
                  "t": "57599999",
                  "k": 2,
                  "blockIndex": 13,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "chronological, seven 30-min episodes on a 30-min grid, day n",
              "note": "slots all equal the grid: lag delta is 0",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 6,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000065",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000066",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-02",
                  "season": 1,
                  "episode": 2,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000067",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-03",
                  "season": 1,
                  "episode": 3,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000068",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-04",
                  "season": 1,
                  "episode": 4,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000069",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-05",
                  "season": 1,
                  "episode": 5,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000000000000000000006a",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-06",
                  "season": 1,
                  "episode": 6,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000000000000000000006b",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-07",
                  "season": 1,
                  "episode": 7,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "5",
              "day": {
                "epochStart": "108000000",
                "epochEnd": "129600000",
                "blocks": [
                  {
                    "start": "108000000",
                    "end": "109800000",
                    "itemId": "00000000000000000000000000000069",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "109800000",
                    "end": "111600000",
                    "itemId": "0000000000000000000000000000006a",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "111600000",
                    "end": "113400000",
                    "itemId": "0000000000000000000000000000006b",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "113400000",
                    "end": "115200000",
                    "itemId": "00000000000000000000000000000065",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "115200000",
                    "end": "117000000",
                    "itemId": "00000000000000000000000000000066",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "117000000",
                    "end": "118800000",
                    "itemId": "00000000000000000000000000000067",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "118800000",
                    "end": "120600000",
                    "itemId": "00000000000000000000000000000068",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "120600000",
                    "end": "122400000",
                    "itemId": "00000000000000000000000000000069",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "122400000",
                    "end": "124200000",
                    "itemId": "0000000000000000000000000000006a",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "124200000",
                    "end": "126000000",
                    "itemId": "0000000000000000000000000000006b",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "126000000",
                    "end": "127800000",
                    "itemId": "00000000000000000000000000000065",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "127800000",
                    "end": "129600000",
                    "itemId": "00000000000000000000000000000066",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  }
                ],
                "startRunIndexInCycle": 4,
                "cycleRuns": 7,
                "posMs": "7200000",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "108000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "109799999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "109800000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "111599999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "111600000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "113399999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "118800000",
                  "k": 2,
                  "blockIndex": 6,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    7,
                    8
                  ]
                },
                {
                  "t": "127800000",
                  "k": 2,
                  "blockIndex": 11,
                  "offsetMs": "0",
                  "nextBlockIndexes": []
                },
                {
                  "t": "129599999",
                  "k": 2,
                  "blockIndex": 11,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "chronological, same channel, day n+1 (resumes without a skip)",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 6,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000065",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000066",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-02",
                  "season": 1,
                  "episode": 2,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000067",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-03",
                  "season": 1,
                  "episode": 3,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000068",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-04",
                  "season": 1,
                  "episode": 4,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000069",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-05",
                  "season": 1,
                  "episode": 5,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000000000000000000006a",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-06",
                  "season": 1,
                  "episode": 6,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000000000000000000006b",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000009",
                  "premiere": "2020-01-07",
                  "season": 1,
                  "episode": 7,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "6",
              "day": {
                "epochStart": "129600000",
                "epochEnd": "151200000",
                "blocks": [
                  {
                    "start": "129600000",
                    "end": "131400000",
                    "itemId": "00000000000000000000000000000067",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "131400000",
                    "end": "133200000",
                    "itemId": "00000000000000000000000000000068",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "133200000",
                    "end": "135000000",
                    "itemId": "00000000000000000000000000000069",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "135000000",
                    "end": "136800000",
                    "itemId": "0000000000000000000000000000006a",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "136800000",
                    "end": "138600000",
                    "itemId": "0000000000000000000000000000006b",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "138600000",
                    "end": "140400000",
                    "itemId": "00000000000000000000000000000065",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "140400000",
                    "end": "142200000",
                    "itemId": "00000000000000000000000000000066",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "142200000",
                    "end": "144000000",
                    "itemId": "00000000000000000000000000000067",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "144000000",
                    "end": "145800000",
                    "itemId": "00000000000000000000000000000068",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "145800000",
                    "end": "147600000",
                    "itemId": "00000000000000000000000000000069",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "147600000",
                    "end": "149400000",
                    "itemId": "0000000000000000000000000000006a",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "149400000",
                    "end": "151200000",
                    "itemId": "0000000000000000000000000000006b",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  }
                ],
                "startRunIndexInCycle": 2,
                "cycleRuns": 7,
                "posMs": "3600000",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "129600000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "131399999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "131400000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "133199999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "133200000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "134999999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "140400000",
                  "k": 2,
                  "blockIndex": 6,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    7,
                    8
                  ]
                },
                {
                  "t": "149400000",
                  "k": 2,
                  "blockIndex": 11,
                  "offsetMs": "0",
                  "nextBlockIndexes": []
                },
                {
                  "t": "151199999",
                  "k": 2,
                  "blockIndex": 11,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "chronological, seam with a skip, day n",
              "note": "Mixed runtimes: day n+1 skips 1 run(s) against the continuous playout.",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "15396698177038169412",
                  "hex": "d5ac0f6ab443b544",
                  "hi": "d5ac0f6a",
                  "lo": "b443b544"
                },
                "blockLength": 5,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 6,
                "utcOffsetMinutes": 75,
                "start": "23:00",
                "graceHours": 0
              },
              "items": [
                {
                  "id": "00a0f65afec748be97e9ed2992287d7e",
                  "kind": "movie",
                  "premiere": "2010-10-03",
                  "runtimeMs": "2520000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "006b6b1129393a06bfbac487de303719",
                  "kind": "movie",
                  "premiere": "2022-07-12",
                  "runtimeMs": "2520000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0069fcdb8f834cfd2855eb76a5b5a6f8",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000001eef",
                  "premiere": "2006-12-13",
                  "season": 3,
                  "episode": 7,
                  "runtimeMs": "3600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0080f7ff3e63ecc751492eff08141bdc",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000001eef",
                  "premiere": "2002-06-28",
                  "season": 3,
                  "episode": 6,
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "003a5cc64610225c8b7048c637734303",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000001eef",
                  "premiere": "2002-10-15",
                  "season": 3,
                  "episode": 1,
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00f81c866982ba8cdfe45fa1bf87e229",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000001eef",
                  "premiere": "2011-09-28",
                  "season": 1,
                  "episode": 2,
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "1",
              "day": {
                "epochStart": "99900000",
                "epochEnd": "121500000",
                "blocks": [
                  {
                    "start": "99900000",
                    "end": "101400000",
                    "itemId": "0080f7ff3e63ecc751492eff08141bdc",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "101400000",
                    "end": "102720000",
                    "itemId": "003a5cc64610225c8b7048c637734303",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "102720000",
                    "end": "106320000",
                    "itemId": "0069fcdb8f834cfd2855eb76a5b5a6f8",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "106320000",
                    "end": "108840000",
                    "itemId": "00a0f65afec748be97e9ed2992287d7e",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "108840000",
                    "end": "110340000",
                    "itemId": "00f81c866982ba8cdfe45fa1bf87e229",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "110340000",
                    "end": "110700000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "110700000",
                    "end": "113220000",
                    "itemId": "006b6b1129393a06bfbac487de303719",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "113220000",
                    "end": "114300000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "114300000",
                    "end": "115800000",
                    "itemId": "0080f7ff3e63ecc751492eff08141bdc",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "115800000",
                    "end": "116100000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "116100000",
                    "end": "117420000",
                    "itemId": "003a5cc64610225c8b7048c637734303",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "117420000",
                    "end": "117900000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "117900000",
                    "end": "121500000",
                    "itemId": "0069fcdb8f834cfd2855eb76a5b5a6f8",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 2,
                "posMs": "3600000",
                "lagDeltaMs": "3600000"
              },
              "queries": [
                {
                  "t": "99900000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "101399999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1499999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "101400000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "102719999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1319999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "102720000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "106319999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "3599999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "110700000",
                  "k": 2,
                  "blockIndex": 6,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    7,
                    8
                  ]
                },
                {
                  "t": "117900000",
                  "k": 2,
                  "blockIndex": 12,
                  "offsetMs": "0",
                  "nextBlockIndexes": []
                },
                {
                  "t": "121499999",
                  "k": 2,
                  "blockIndex": 12,
                  "offsetMs": "3599999",
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "chronological, seam with a skip, day n+1",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "15396698177038169412",
                  "hex": "d5ac0f6ab443b544",
                  "hi": "d5ac0f6a",
                  "lo": "b443b544"
                },
                "blockLength": 5,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 6,
                "utcOffsetMinutes": 75,
                "start": "23:00",
                "graceHours": 0
              },
              "items": [
                {
                  "id": "00a0f65afec748be97e9ed2992287d7e",
                  "kind": "movie",
                  "premiere": "2010-10-03",
                  "runtimeMs": "2520000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "006b6b1129393a06bfbac487de303719",
                  "kind": "movie",
                  "premiere": "2022-07-12",
                  "runtimeMs": "2520000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0069fcdb8f834cfd2855eb76a5b5a6f8",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000001eef",
                  "premiere": "2006-12-13",
                  "season": 3,
                  "episode": 7,
                  "runtimeMs": "3600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0080f7ff3e63ecc751492eff08141bdc",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000001eef",
                  "premiere": "2002-06-28",
                  "season": 3,
                  "episode": 6,
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "003a5cc64610225c8b7048c637734303",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000001eef",
                  "premiere": "2002-10-15",
                  "season": 3,
                  "episode": 1,
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00f81c866982ba8cdfe45fa1bf87e229",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000001eef",
                  "premiere": "2011-09-28",
                  "season": 1,
                  "episode": 2,
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "2",
              "day": {
                "epochStart": "121500000",
                "epochEnd": "143100000",
                "blocks": [
                  {
                    "start": "121500000",
                    "end": "124020000",
                    "itemId": "006b6b1129393a06bfbac487de303719",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "124020000",
                    "end": "125100000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "125100000",
                    "end": "126600000",
                    "itemId": "0080f7ff3e63ecc751492eff08141bdc",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "126600000",
                    "end": "127920000",
                    "itemId": "003a5cc64610225c8b7048c637734303",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "127920000",
                    "end": "131520000",
                    "itemId": "0069fcdb8f834cfd2855eb76a5b5a6f8",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "131520000",
                    "end": "134040000",
                    "itemId": "00a0f65afec748be97e9ed2992287d7e",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "134040000",
                    "end": "135540000",
                    "itemId": "00f81c866982ba8cdfe45fa1bf87e229",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "135540000",
                    "end": "135900000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "135900000",
                    "end": "138420000",
                    "itemId": "006b6b1129393a06bfbac487de303719",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "138420000",
                    "end": "139500000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "139500000",
                    "end": "141000000",
                    "itemId": "0080f7ff3e63ecc751492eff08141bdc",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "141000000",
                    "end": "141300000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "141300000",
                    "end": "142620000",
                    "itemId": "003a5cc64610225c8b7048c637734303",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "142620000",
                    "end": "143100000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 1,
                "cycleRuns": 2,
                "posMs": "10800000",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "121500000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "124019999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "2519999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "124020000",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "125099999",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "125100000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "126599999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1499999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "132300000",
                  "k": 2,
                  "blockIndex": 5,
                  "offsetMs": "780000",
                  "nextBlockIndexes": [
                    6,
                    7
                  ]
                },
                {
                  "t": "142620000",
                  "k": 2,
                  "blockIndex": 13,
                  "nextBlockIndexes": []
                },
                {
                  "t": "143099999",
                  "k": 2,
                  "blockIndex": 13,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "randomInOrder, blockLength 2, grid 30",
              "settings": {
                "mode": "randomInOrder",
                "seed": {
                  "dec": "12345",
                  "hex": "0000000000003039",
                  "hi": "00000000",
                  "lo": "00003039"
                },
                "blockLength": 2,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 8,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "000000000000000000000000000000c9",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000015",
                  "premiere": "2019-02-02",
                  "season": 1,
                  "episode": 1,
                  "runtimeMs": "1560000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000ca",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000016",
                  "premiere": "2019-03-03",
                  "season": 1,
                  "episode": 2,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000cb",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000014",
                  "premiere": "2019-04-04",
                  "season": 1,
                  "episode": 3,
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000cc",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000015",
                  "premiere": "2019-05-05",
                  "season": 1,
                  "episode": 4,
                  "runtimeMs": "1560000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000cd",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000016",
                  "premiere": "2019-06-06",
                  "season": 1,
                  "episode": 5,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000ce",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000014",
                  "premiere": "2019-07-07",
                  "season": 1,
                  "episode": 6,
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000cf",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000015",
                  "premiere": "2019-08-08",
                  "season": 1,
                  "episode": 7,
                  "runtimeMs": "1560000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000d0",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000016",
                  "premiere": "2019-09-09",
                  "season": 1,
                  "episode": 8,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000000000000000000012c",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000000000000000000012d",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "4",
              "day": {
                "epochStart": "115200000",
                "epochEnd": "144000000",
                "blocks": [
                  {
                    "start": "115200000",
                    "end": "116520000",
                    "itemId": "000000000000000000000000000000cb",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "116520000",
                    "end": "117840000",
                    "itemId": "000000000000000000000000000000ce",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "117840000",
                    "end": "118800000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "118800000",
                    "end": "124500000",
                    "itemId": "0000000000000000000000000000012c",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "124500000",
                    "end": "126000000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "126000000",
                    "end": "132600000",
                    "itemId": "0000000000000000000000000000012d",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "132600000",
                    "end": "133200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "133200000",
                    "end": "134760000",
                    "itemId": "000000000000000000000000000000c9",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "134760000",
                    "end": "136320000",
                    "itemId": "000000000000000000000000000000cc",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "136320000",
                    "end": "136800000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "136800000",
                    "end": "138600000",
                    "itemId": "000000000000000000000000000000ca",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "138600000",
                    "end": "140400000",
                    "itemId": "000000000000000000000000000000cd",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "140400000",
                    "end": "141960000",
                    "itemId": "000000000000000000000000000000cf",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "141960000",
                    "end": "142200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "142200000",
                    "end": "144000000",
                    "itemId": "000000000000000000000000000000d0",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 7,
                "posMs": "0",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "115200000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "116519999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1319999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "116520000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "117839999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1319999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "117840000",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "118799999",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "129600000",
                  "k": 2,
                  "blockIndex": 5,
                  "offsetMs": "3600000",
                  "nextBlockIndexes": [
                    6,
                    7
                  ]
                },
                {
                  "t": "142200000",
                  "k": 2,
                  "blockIndex": 14,
                  "offsetMs": "0",
                  "nextBlockIndexes": []
                },
                {
                  "t": "143999999",
                  "k": 2,
                  "blockIndex": 14,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "randomInOrder, blockLength 100 (turn = whole group)",
              "settings": {
                "mode": "randomInOrder",
                "seed": {
                  "dec": "99",
                  "hex": "0000000000000063",
                  "hi": "00000000",
                  "lo": "00000063"
                },
                "blockLength": 100,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 12,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "000000000000000000000000000000c9",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000015",
                  "premiere": "2019-02-02",
                  "season": 1,
                  "episode": 1,
                  "runtimeMs": "1560000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000ca",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000016",
                  "premiere": "2019-03-03",
                  "season": 1,
                  "episode": 2,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000cb",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000014",
                  "premiere": "2019-04-04",
                  "season": 1,
                  "episode": 3,
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000cc",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000015",
                  "premiere": "2019-05-05",
                  "season": 1,
                  "episode": 4,
                  "runtimeMs": "1560000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000cd",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000016",
                  "premiere": "2019-06-06",
                  "season": 1,
                  "episode": 5,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000ce",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000014",
                  "premiere": "2019-07-07",
                  "season": 1,
                  "episode": 6,
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000cf",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000015",
                  "premiere": "2019-08-08",
                  "season": 1,
                  "episode": 7,
                  "runtimeMs": "1560000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000000d0",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000016",
                  "premiere": "2019-09-09",
                  "season": 1,
                  "episode": 8,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000000000000000000012c",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "0000000000000000000000000000012d",
                  "kind": "movie",
                  "runtimeMs": "6600000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "-3",
              "day": {
                "epochStart": "-129600000",
                "epochEnd": "-86400000",
                "blocks": [
                  {
                    "start": "-129600000",
                    "end": "-127800000",
                    "itemId": "000000000000000000000000000000ca",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-127800000",
                    "end": "-126000000",
                    "itemId": "000000000000000000000000000000cd",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-126000000",
                    "end": "-124200000",
                    "itemId": "000000000000000000000000000000d0",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-124200000",
                    "end": "-117600000",
                    "itemId": "0000000000000000000000000000012d",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-117600000",
                    "end": "-117000000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-117000000",
                    "end": "-115440000",
                    "itemId": "000000000000000000000000000000c9",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-115440000",
                    "end": "-113880000",
                    "itemId": "000000000000000000000000000000cc",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-113880000",
                    "end": "-112320000",
                    "itemId": "000000000000000000000000000000cf",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-112320000",
                    "end": "-111600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-111600000",
                    "end": "-110280000",
                    "itemId": "000000000000000000000000000000cb",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-110280000",
                    "end": "-108960000",
                    "itemId": "000000000000000000000000000000ce",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-108960000",
                    "end": "-108000000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-108000000",
                    "end": "-102300000",
                    "itemId": "0000000000000000000000000000012c",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-102300000",
                    "end": "-100800000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-100800000",
                    "end": "-99000000",
                    "itemId": "000000000000000000000000000000ca",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-99000000",
                    "end": "-97200000",
                    "itemId": "000000000000000000000000000000cd",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-97200000",
                    "end": "-95400000",
                    "itemId": "000000000000000000000000000000d0",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-95400000",
                    "end": "-88800000",
                    "itemId": "0000000000000000000000000000012d",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-88800000",
                    "end": "-88200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-88200000",
                    "end": "-86640000",
                    "itemId": "000000000000000000000000000000c9",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "-86640000",
                    "end": "-86400000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 2,
                "cycleRuns": 5,
                "posMs": "14400000",
                "lagDeltaMs": "3600000"
              },
              "queries": [
                {
                  "t": "-129600000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "-127800001",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "-127800000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "-126000001",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "-126000000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "-124200001",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "-108000000",
                  "k": 2,
                  "blockIndex": 12,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    13,
                    14
                  ]
                },
                {
                  "t": "-86640000",
                  "k": 2,
                  "blockIndex": 20,
                  "nextBlockIndexes": []
                },
                {
                  "t": "-86400001",
                  "k": 2,
                  "blockIndex": 20,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "empty pool (one slate)",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 24,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "86400000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "86400000",
                    "slate": true,
                    "role": "slate"
                  }
                ]
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "nextBlockIndexes": []
                },
                {
                  "t": "43200000",
                  "k": 2,
                  "blockIndex": 0,
                  "nextBlockIndexes": []
                },
                {
                  "t": "86399999",
                  "k": 2,
                  "blockIndex": 0,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "only items longer than the epoch (pool empty)",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 1,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "5400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "3600000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "3600000",
                    "slate": true,
                    "role": "slate"
                  }
                ]
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "nextBlockIndexes": []
                },
                {
                  "t": "1800000",
                  "k": 2,
                  "blockIndex": 0,
                  "nextBlockIndexes": []
                },
                {
                  "t": "3599999",
                  "k": 2,
                  "blockIndex": 0,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "chronological, blockLength 3, epoch 2 h: the 150-min run is left out of the cycle",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 3,
                "alignToGrid": false,
                "gridMinutes": 30,
                "epochHours": 2,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 0
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "premiere": "2020-01-01",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "premiere": "2020-01-02",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "premiere": "2020-01-03",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "premiere": "2020-01-04",
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "premiere": "2020-01-05",
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "7200000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "1200000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "1200000",
                    "end": "2400000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "2400000",
                    "end": "3600000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "3600000",
                    "end": "4800000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "4800000",
                    "end": "6000000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "6000000",
                    "end": "7200000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 1,
                "posMs": "0",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "1199999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "1200000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "2399999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "2400000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "3599999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "3600000",
                  "k": 2,
                  "blockIndex": 3,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    4,
                    5
                  ]
                },
                {
                  "t": "6000000",
                  "k": 2,
                  "blockIndex": 5,
                  "offsetMs": "0",
                  "nextBlockIndexes": []
                },
                {
                  "t": "7199999",
                  "k": 2,
                  "blockIndex": 5,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "every run longer than the epoch, pool not empty (this reference: one slate)",
              "note": "Spec silent. The cycle is empty, so there is no run to fill from; the reference emits one slate for the day. Do not rely on this case until the spec says so.",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 3,
                "alignToGrid": false,
                "gridMinutes": 30,
                "epochHours": 1,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 0
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "premiere": "2020-01-01",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "premiere": "2020-01-02",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "premiere": "2020-01-03",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "3600000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "3600000",
                    "slate": true,
                    "role": "slate"
                  }
                ]
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "nextBlockIndexes": []
                },
                {
                  "t": "1800000",
                  "k": 2,
                  "blockIndex": 0,
                  "nextBlockIndexes": []
                },
                {
                  "t": "3599999",
                  "k": 2,
                  "blockIndex": 0,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "grace cut-off: createdAt < S-G kept, deletedAt >= S-G kept (edges on both sides)",
              "note": "Pool = ids 1 (created S-G-1), 5 (deleted exactly S-G), 6, 7. Excluded: 2 (created exactly S-G), 3 (after), 4 (deleted S-G-1).",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": false,
                "gridMinutes": 30,
                "epochHours": 24,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "842399999"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "842400000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "842400001"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000",
                  "deletedAtMs": "842399999"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000",
                  "deletedAtMs": "842400000"
                },
                {
                  "id": "00000000000000000000000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000",
                  "deletedAtMs": "842400001"
                },
                {
                  "id": "00000000000000000000000000000007",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "10",
              "day": {
                "epochStart": "864000000",
                "epochEnd": "950400000",
                "blocks": [
                  {
                    "start": "864000000",
                    "end": "865800000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "865800000",
                    "end": "867600000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "867600000",
                    "end": "869400000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "869400000",
                    "end": "871200000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "871200000",
                    "end": "873000000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "873000000",
                    "end": "874800000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "874800000",
                    "end": "876600000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "876600000",
                    "end": "878400000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "878400000",
                    "end": "880200000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "880200000",
                    "end": "882000000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "882000000",
                    "end": "883800000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "883800000",
                    "end": "885600000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "885600000",
                    "end": "887400000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "887400000",
                    "end": "889200000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "889200000",
                    "end": "891000000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "891000000",
                    "end": "892800000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "892800000",
                    "end": "894600000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "894600000",
                    "end": "896400000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "896400000",
                    "end": "898200000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "898200000",
                    "end": "900000000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "900000000",
                    "end": "901800000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "901800000",
                    "end": "903600000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "903600000",
                    "end": "905400000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "905400000",
                    "end": "907200000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "907200000",
                    "end": "909000000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "909000000",
                    "end": "910800000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "910800000",
                    "end": "912600000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "912600000",
                    "end": "914400000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "914400000",
                    "end": "916200000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "916200000",
                    "end": "918000000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "918000000",
                    "end": "919800000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "919800000",
                    "end": "921600000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "921600000",
                    "end": "923400000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "923400000",
                    "end": "925200000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "925200000",
                    "end": "927000000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "927000000",
                    "end": "928800000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "928800000",
                    "end": "930600000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "930600000",
                    "end": "932400000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "932400000",
                    "end": "934200000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "934200000",
                    "end": "936000000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "936000000",
                    "end": "937800000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "937800000",
                    "end": "939600000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "939600000",
                    "end": "941400000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "941400000",
                    "end": "943200000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "943200000",
                    "end": "945000000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "945000000",
                    "end": "946800000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "946800000",
                    "end": "948600000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "948600000",
                    "end": "950400000",
                    "itemId": "00000000000000000000000000000007",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 4,
                "posMs": "0",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "864000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "865799999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "865800000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "867599999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "867600000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "869399999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "907200000",
                  "k": 2,
                  "blockIndex": 24,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    25,
                    26
                  ]
                },
                {
                  "t": "948600000",
                  "k": 2,
                  "blockIndex": 47,
                  "offsetMs": "0",
                  "nextBlockIndexes": []
                },
                {
                  "t": "950399999",
                  "k": 2,
                  "blockIndex": 47,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "Nepal +345, start 18:30, grid 30",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 12,
                "utcOffsetMinutes": 345,
                "start": "18:30",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "runtimeMs": "1320000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "runtimeMs": "5700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "runtimeMs": "2400000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000006",
                  "kind": "movie",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "7",
              "day": {
                "epochStart": "348300000",
                "epochEnd": "391500000",
                "blocks": [
                  {
                    "start": "348300000",
                    "end": "350100000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "350100000",
                    "end": "352500000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "352500000",
                    "end": "353700000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "353700000",
                    "end": "359400000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "359400000",
                    "end": "360900000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "360900000",
                    "end": "362400000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "362400000",
                    "end": "362700000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "362700000",
                    "end": "365700000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "365700000",
                    "end": "366300000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "366300000",
                    "end": "367620000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "367620000",
                    "end": "368100000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "368100000",
                    "end": "369900000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "369900000",
                    "end": "372300000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "372300000",
                    "end": "373500000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "373500000",
                    "end": "379200000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "379200000",
                    "end": "380700000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "380700000",
                    "end": "382200000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "382200000",
                    "end": "382500000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "382500000",
                    "end": "385500000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "385500000",
                    "end": "386100000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "386100000",
                    "end": "387420000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "387420000",
                    "end": "387900000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "387900000",
                    "end": "389700000",
                    "itemId": "00000000000000000000000000000006",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "389700000",
                    "end": "391200000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "391200000",
                    "end": "391500000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 6
              },
              "queries": [
                {
                  "t": "348300000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "350099999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "350100000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "352499999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "2399999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "352500000",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "353699999",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "369900000",
                  "k": 2,
                  "blockIndex": 12,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    13,
                    14
                  ]
                },
                {
                  "t": "391200000",
                  "k": 2,
                  "blockIndex": 24,
                  "nextBlockIndexes": []
                },
                {
                  "t": "391499999",
                  "k": 2,
                  "blockIndex": 24,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "India +330, start 00:00, grid 60",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 60,
                "epochHours": 12,
                "utcOffsetMinutes": 330,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "premiere": "2021-01-01",
                  "runtimeMs": "2700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "premiere": "2021-02-01",
                  "runtimeMs": "3600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "premiere": "2021-03-01",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "premiere": "2021-04-01",
                  "runtimeMs": "3300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "premiere": "2021-05-01",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "9",
              "day": {
                "epochStart": "369000000",
                "epochEnd": "412200000",
                "blocks": [
                  {
                    "start": "369000000",
                    "end": "371700000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "371700000",
                    "end": "372600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "372600000",
                    "end": "376200000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "376200000",
                    "end": "382200000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "382200000",
                    "end": "383400000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "383400000",
                    "end": "386700000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "386700000",
                    "end": "387000000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "387000000",
                    "end": "388800000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "388800000",
                    "end": "390600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "390600000",
                    "end": "393300000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "393300000",
                    "end": "394200000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "394200000",
                    "end": "397800000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "397800000",
                    "end": "403800000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "403800000",
                    "end": "405000000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "405000000",
                    "end": "408300000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "408300000",
                    "end": "408600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "408600000",
                    "end": "410400000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "410400000",
                    "end": "412200000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 5,
                "posMs": "0",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "369000000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "371699999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "2699999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "371700000",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "372599999",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "372600000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "376199999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "3599999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "390600000",
                  "k": 2,
                  "blockIndex": 9,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    10,
                    11
                  ]
                },
                {
                  "t": "410400000",
                  "k": 2,
                  "blockIndex": 17,
                  "nextBlockIndexes": []
                },
                {
                  "t": "412199999",
                  "k": 2,
                  "blockIndex": 17,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "before 1970 (n < 0), UTC-5 start 06:00, chronological",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 6,
                "utcOffsetMinutes": -300,
                "start": "06:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "premiere": "1999-01-01",
                  "runtimeMs": "2700000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "movie",
                  "premiere": "1999-02-01",
                  "runtimeMs": "1500000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "movie",
                  "premiere": "1999-03-01",
                  "runtimeMs": "6000000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "movie",
                  "premiere": "1999-04-01",
                  "runtimeMs": "3300000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "movie",
                  "premiere": "1999-05-01",
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "-1000",
              "day": {
                "epochStart": "-21560400000",
                "epochEnd": "-21538800000",
                "blocks": [
                  {
                    "start": "-21560400000",
                    "end": "-21558900000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-21558900000",
                    "end": "-21558600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-21558600000",
                    "end": "-21552600000",
                    "itemId": "00000000000000000000000000000003",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-21552600000",
                    "end": "-21551400000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-21551400000",
                    "end": "-21548100000",
                    "itemId": "00000000000000000000000000000004",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-21548100000",
                    "end": "-21547800000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-21547800000",
                    "end": "-21546000000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-21546000000",
                    "end": "-21543300000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-21543300000",
                    "end": "-21542400000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-21542400000",
                    "end": "-21540900000",
                    "itemId": "00000000000000000000000000000002",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "-21540900000",
                    "end": "-21540600000",
                    "slate": true,
                    "role": "slate"
                  },
                  {
                    "start": "-21540600000",
                    "end": "-21538800000",
                    "itemId": "00000000000000000000000000000005",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  }
                ],
                "startRunIndexInCycle": 1,
                "cycleRuns": 5,
                "posMs": "3600000",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "-21560400000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "-21558900001",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1499999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "-21558900000",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "-21558600001",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "-21558600000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "-21552600001",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "5999999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "-21549600000",
                  "k": 2,
                  "blockIndex": 4,
                  "offsetMs": "1800000",
                  "nextBlockIndexes": [
                    5,
                    6
                  ]
                },
                {
                  "t": "-21540600000",
                  "k": 2,
                  "blockIndex": 11,
                  "offsetMs": "0",
                  "nextBlockIndexes": []
                },
                {
                  "t": "-21538800001",
                  "k": 2,
                  "blockIndex": 11,
                  "offsetMs": "1799999",
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "end fill, chronological: A3 does not fit, A4 would but is not placed (group rule)",
              "note": "Slate of 20 min at the end.",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": false,
                "gridMinutes": 30,
                "epochHours": 1,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 0
              },
              "items": [
                {
                  "id": "00000000000000000000000000000191",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-01",
                  "season": 1,
                  "episode": 1,
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000192",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-02",
                  "season": 1,
                  "episode": 2,
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000193",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-03",
                  "season": 1,
                  "episode": 3,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000194",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-04",
                  "season": 1,
                  "episode": 4,
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "3600000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "1200000",
                    "itemId": "00000000000000000000000000000191",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "1200000",
                    "end": "2400000",
                    "itemId": "00000000000000000000000000000192",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "2400000",
                    "end": "3600000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 4,
                "posMs": "0",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "1199999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "1200000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2
                  ]
                },
                {
                  "t": "1800000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "600000",
                  "nextBlockIndexes": [
                    2
                  ]
                },
                {
                  "t": "2399999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": [
                    2
                  ]
                },
                {
                  "t": "2400000",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": []
                },
                {
                  "t": "3599999",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "end fill, randomInOrder: same series, same rule",
              "settings": {
                "mode": "randomInOrder",
                "seed": {
                  "dec": "5",
                  "hex": "0000000000000005",
                  "hi": "00000000",
                  "lo": "00000005"
                },
                "blockLength": 1,
                "alignToGrid": false,
                "gridMinutes": 30,
                "epochHours": 1,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 0
              },
              "items": [
                {
                  "id": "00000000000000000000000000000191",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-01",
                  "season": 1,
                  "episode": 1,
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000192",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-02",
                  "season": 1,
                  "episode": 2,
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000193",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-03",
                  "season": 1,
                  "episode": 3,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000194",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-04",
                  "season": 1,
                  "episode": 4,
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "3600000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "1200000",
                    "itemId": "00000000000000000000000000000191",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "1200000",
                    "end": "2400000",
                    "itemId": "00000000000000000000000000000192",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "2400000",
                    "end": "3600000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 4,
                "posMs": "0",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "1199999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "1200000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2
                  ]
                },
                {
                  "t": "1800000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "600000",
                  "nextBlockIndexes": [
                    2
                  ]
                },
                {
                  "t": "2399999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": [
                    2
                  ]
                },
                {
                  "t": "2400000",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": []
                },
                {
                  "t": "3599999",
                  "k": 2,
                  "blockIndex": 2,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "end fill, shuffle: A3 does not fit, A4 is placed (no group rule)",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "3",
                  "hex": "0000000000000003",
                  "hi": "00000000",
                  "lo": "00000003"
                },
                "blockLength": 1,
                "alignToGrid": false,
                "gridMinutes": 30,
                "epochHours": 1,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 0
              },
              "items": [
                {
                  "id": "00000000000000000000000000000191",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-01",
                  "season": 1,
                  "episode": 1,
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000192",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-02",
                  "season": 1,
                  "episode": 2,
                  "runtimeMs": "1200000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000193",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-03",
                  "season": 1,
                  "episode": 3,
                  "runtimeMs": "1800000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "00000000000000000000000000000194",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000004",
                  "premiere": "2020-03-04",
                  "season": 1,
                  "episode": 4,
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "3600000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "600000",
                    "itemId": "00000000000000000000000000000194",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "600000",
                    "end": "1800000",
                    "itemId": "00000000000000000000000000000191",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "1800000",
                    "end": "3000000",
                    "itemId": "00000000000000000000000000000192",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "3000000",
                    "end": "3600000",
                    "itemId": "00000000000000000000000000000194",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 4
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "599999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "599999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "600000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "1799999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "1800000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3
                  ]
                },
                {
                  "t": "2999999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "1199999",
                  "nextBlockIndexes": [
                    3
                  ]
                },
                {
                  "t": "3000000",
                  "k": 2,
                  "blockIndex": 3,
                  "offsetMs": "0",
                  "nextBlockIndexes": []
                },
                {
                  "t": "3599999",
                  "k": 2,
                  "blockIndex": 3,
                  "offsetMs": "599999",
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "end fill, chronological: reaches the cycle's end and stops (no wrap), 30-min slate",
              "note": "Cycle M1 M2 M3 M4 (10, 10, 10, 50 min). M4 does not fit at minute 30. The fill visits M4 only; M1 would fit again but the fill does not wrap.",
              "settings": {
                "mode": "chronological",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": false,
                "gridMinutes": 30,
                "epochHours": 1,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 0
              },
              "items": [
                {
                  "id": "000000000000000000000000000001f5",
                  "kind": "movie",
                  "premiere": "2020-04-01",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000001f6",
                  "kind": "movie",
                  "premiere": "2020-04-02",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000001f7",
                  "kind": "movie",
                  "premiere": "2020-04-03",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000001f8",
                  "kind": "movie",
                  "premiere": "2020-04-04",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "3600000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "600000",
                    "itemId": "000000000000000000000000000001f5",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "600000",
                    "end": "1200000",
                    "itemId": "000000000000000000000000000001f6",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "1200000",
                    "end": "1800000",
                    "itemId": "000000000000000000000000000001f7",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "1800000",
                    "end": "3600000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 4,
                "posMs": "0",
                "lagDeltaMs": "0"
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "599999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "599999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "600000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "1199999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "599999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "1200000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3
                  ]
                },
                {
                  "t": "1799999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "599999",
                  "nextBlockIndexes": [
                    3
                  ]
                },
                {
                  "t": "1800000",
                  "k": 2,
                  "blockIndex": 3,
                  "nextBlockIndexes": []
                },
                {
                  "t": "3599999",
                  "k": 2,
                  "blockIndex": 3,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "end fill, shuffle: same pool and order, the fill wraps and re-places M1..M3",
              "note": "Shuffle order M1 M2 M3 M4 (seed chosen for it). M4 does not fit; the fill wraps to M1, M2, M3 and places all three, filling the day with no slate.",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "9",
                  "hex": "0000000000000009",
                  "hi": "00000000",
                  "lo": "00000009"
                },
                "blockLength": 1,
                "alignToGrid": false,
                "gridMinutes": 30,
                "epochHours": 1,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 0
              },
              "items": [
                {
                  "id": "000000000000000000000000000001f5",
                  "kind": "movie",
                  "premiere": "2020-04-01",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000001f6",
                  "kind": "movie",
                  "premiere": "2020-04-02",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000001f7",
                  "kind": "movie",
                  "premiere": "2020-04-03",
                  "runtimeMs": "600000",
                  "createdAtMs": "-1000000000000"
                },
                {
                  "id": "000000000000000000000000000001f8",
                  "kind": "movie",
                  "premiere": "2020-04-04",
                  "runtimeMs": "3000000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "3600000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "600000",
                    "itemId": "000000000000000000000000000001f5",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "600000",
                    "end": "1200000",
                    "itemId": "000000000000000000000000000001f6",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "1200000",
                    "end": "1800000",
                    "itemId": "000000000000000000000000000001f7",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "1800000",
                    "end": "2400000",
                    "itemId": "000000000000000000000000000001f5",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "2400000",
                    "end": "3000000",
                    "itemId": "000000000000000000000000000001f6",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  },
                  {
                    "start": "3000000",
                    "end": "3600000",
                    "itemId": "000000000000000000000000000001f7",
                    "mediaStartOffsetMs": "0",
                    "role": "fill"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 4
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "599999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "599999",
                  "nextBlockIndexes": [
                    1,
                    2
                  ]
                },
                {
                  "t": "600000",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "1199999",
                  "k": 2,
                  "blockIndex": 1,
                  "offsetMs": "599999",
                  "nextBlockIndexes": [
                    2,
                    3
                  ]
                },
                {
                  "t": "1200000",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "1799999",
                  "k": 2,
                  "blockIndex": 2,
                  "offsetMs": "599999",
                  "nextBlockIndexes": [
                    3,
                    4
                  ]
                },
                {
                  "t": "1800000",
                  "k": 2,
                  "blockIndex": 3,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    4,
                    5
                  ]
                },
                {
                  "t": "3000000",
                  "k": 2,
                  "blockIndex": 5,
                  "offsetMs": "0",
                  "nextBlockIndexes": []
                },
                {
                  "t": "3599999",
                  "k": 2,
                  "blockIndex": 5,
                  "offsetMs": "599999",
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "movies only, one 2 h movie in a 3 h epoch on a 60-min grid: long slate",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 60,
                "epochHours": 3,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "7200000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "10800000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "7200000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "7200000",
                    "end": "10800000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 1
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1
                  ]
                },
                {
                  "t": "5400000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "5400000",
                  "nextBlockIndexes": [
                    1
                  ]
                },
                {
                  "t": "7199999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "7199999",
                  "nextBlockIndexes": [
                    1
                  ]
                },
                {
                  "t": "7200000",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": []
                },
                {
                  "t": "10799999",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": []
                }
              ]
            },
            {
              "name": "movies only, 170-min movie in a 3 h epoch, 30-min grid: 10-min slate",
              "settings": {
                "mode": "shuffle",
                "seed": {
                  "dec": "8",
                  "hex": "0000000000000008",
                  "hi": "00000000",
                  "lo": "00000008"
                },
                "blockLength": 1,
                "alignToGrid": true,
                "gridMinutes": 30,
                "epochHours": 3,
                "utcOffsetMinutes": 0,
                "start": "00:00",
                "graceHours": 6
              },
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "movie",
                  "runtimeMs": "10200000",
                  "createdAtMs": "-1000000000000"
                }
              ],
              "n": "0",
              "day": {
                "epochStart": "0",
                "epochEnd": "10800000",
                "blocks": [
                  {
                    "start": "0",
                    "end": "10200000",
                    "itemId": "00000000000000000000000000000001",
                    "mediaStartOffsetMs": "0",
                    "role": "run"
                  },
                  {
                    "start": "10200000",
                    "end": "10800000",
                    "slate": true,
                    "role": "slate"
                  }
                ],
                "startRunIndexInCycle": 0,
                "cycleRuns": 1
              },
              "queries": [
                {
                  "t": "0",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "0",
                  "nextBlockIndexes": [
                    1
                  ]
                },
                {
                  "t": "5400000",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "5400000",
                  "nextBlockIndexes": [
                    1
                  ]
                },
                {
                  "t": "10199999",
                  "k": 2,
                  "blockIndex": 0,
                  "offsetMs": "10199999",
                  "nextBlockIndexes": [
                    1
                  ]
                },
                {
                  "t": "10200000",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": []
                },
                {
                  "t": "10799999",
                  "k": 2,
                  "blockIndex": 1,
                  "nextBlockIndexes": []
                }
              ]
            }
          ]
        }
        """.trimIndent() + "\n"
}
