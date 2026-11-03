// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/** Kotlin copies of `docs/rules-format/examples/`. `ExampleFilesInSyncTest` fails if they differ from the files. */
@Suppress("LargeClass") // Data only: one entry per example file.
internal object ExampleFiles {
    val valid: Map<String, String> =
        mapOf(
            "library-only.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Everything",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "genre-with-max-rating.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Saturday Morning Cartoons",
                  "seed": "13835058055282163712",
                  "sources": [
                    {
                      "type": "genre",
                      "name": "Animation"
                    }
                  ],
                  "maxRating": "TV-Y7",
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "collection.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Holiday Movies",
                  "seed": "42",
                  "sources": [
                    {
                      "type": "collection",
                      "id": "22222222222222222222222222222222"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "tag.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Late Night",
                  "seed": "18446744073709551615",
                  "sources": [
                    {
                      "type": "tag",
                      "name": "Late Night"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "year-range.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "The Nineties",
                  "seed": "1990",
                  "sources": [
                    {
                      "type": "yearRange",
                      "from": 1990,
                      "to": 1999
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "series.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Just This Show",
                  "seed": "7",
                  "sources": [
                    {
                      "type": "series",
                      "id": "33333333333333333333333333333333"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "exclusion.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Comedy, Minus One",
                  "seed": "99",
                  "sources": [
                    {
                      "type": "genre",
                      "name": "Comedy"
                    },
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "exclusions": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    },
                    {
                      "type": "genre",
                      "name": "Horror"
                    },
                    {
                      "type": "collection",
                      "id": "22222222222222222222222222222222"
                    },
                    {
                      "type": "tag",
                      "name": "Seen It"
                    },
                    {
                      "type": "yearRange",
                      "from": 1900,
                      "to": 1969
                    },
                    {
                      "type": "series",
                      "id": "33333333333333333333333333333333"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "ordering-shuffle.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Shuffled",
                  "seed": "5",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "ordering": {
                    "mode": "shuffle"
                  },
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "ordering-chronological.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "In Order",
                  "seed": "5",
                  "sources": [
                    {
                      "type": "series",
                      "id": "33333333333333333333333333333333"
                    }
                  ],
                  "ordering": {
                    "mode": "chronological"
                  },
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "ordering-random-in-order.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Random Series, Episodes In Order",
                  "seed": "5",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "ordering": {
                    "mode": "randomInOrder"
                  },
                  "timing": {
                    "blockLength": 2,
                    "alignToGrid": true,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "timing-unaligned.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Marathon",
                  "seed": "5",
                  "sources": [
                    {
                      "type": "series",
                      "id": "00000000000000000000000000000004"
                    }
                  ],
                  "timing": {
                    "blockLength": 4,
                    "alignToGrid": false,
                    "epochHours": 12,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "broadcast-day-offset.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Eastern Evening",
                  "seed": "8",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filler-none.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "No Filler",
                  "seed": "9",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  },
                  "filler": {
                    "mode": "none"
                  }
                }
                """.trimIndent() + "\n",
            "filler-pad-to-grid.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Padded",
                  "seed": "9",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "alignToGrid": true,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  },
                  "filler": {
                    "mode": "padToGrid",
                    "source": {
                      "type": "library",
                      "id": "55555555555555555555555555555555"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filler-between-every-n.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Station Breaks",
                  "seed": "9",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  },
                  "filler": {
                    "mode": "betweenEveryN",
                    "every": 3,
                    "source": {
                      "type": "collection",
                      "id": "66666666666666666666666666666666"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filler-tag-source.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Tagged Filler",
                  "seed": "9",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "alignToGrid": true,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  },
                  "filler": {
                    "mode": "padToGrid",
                    "source": {
                      "type": "tag",
                      "name": "Station IDs"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "broadcast-day-offset-nepal.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Kathmandu Evening",
                  "seed": "11",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 345,
                      "start": "18:30"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "name-100-code-points.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀",
                  "seed": "13",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "full.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Saturday Morning Cartoons",
                  "seed": "13835058055282163712",
                  "sources": [
                    {
                      "type": "genre",
                      "name": "Animation"
                    },
                    {
                      "type": "collection",
                      "id": "22222222222222222222222222222222"
                    }
                  ],
                  "exclusions": [
                    {
                      "type": "series",
                      "id": "33333333333333333333333333333333"
                    }
                  ],
                  "maxRating": "TV-Y7",
                  "ordering": {
                    "mode": "randomInOrder"
                  },
                  "timing": {
                    "blockLength": 2,
                    "alignToGrid": true,
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  },
                  "filler": {
                    "mode": "betweenEveryN",
                    "every": 3,
                    "source": {
                      "type": "tag",
                      "name": "Station IDs"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "forward-compat-extras.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "From A Newer App",
                  "seed": "12",
                  "sources": [
                    {
                      "type": "genre",
                      "name": "Drama",
                      "futureSourceField": true
                    }
                  ],
                  "exclusions": [
                    {
                      "type": "tag",
                      "name": "Skip",
                      "futureExclusionField": "x"
                    }
                  ],
                  "maxRating": "TV-14",
                  "ordering": {
                    "mode": "shuffle",
                    "futureOrderingField": 1
                  },
                  "timing": {
                    "blockLength": 1,
                    "broadcastDay": {
                      "utcOffsetMinutes": 60,
                      "start": "05:30",
                      "futureDayField": []
                    },
                    "futureTimingField": null
                  },
                  "filler": {
                    "mode": "none",
                    "source": {
                      "type": "library",
                      "id": "77777777777777777777777777777777",
                      "futureFillerSourceField": 2
                    },
                    "futureFillerField": "y"
                  },
                  "futureTopLevel": {
                    "anything": [
                      1,
                      2,
                      3
                    ]
                  }
                }
                """.trimIndent() + "\n",
            "timing-grid-and-grace.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Hourly Grid",
                  "seed": "21",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "alignToGrid": true,
                    "epochHours": 12,
                    "graceHours": 12,
                    "gridMinutes": 60,
                    "minRuntimeSeconds": 90,
                    "broadcastDay": {
                      "utcOffsetMinutes": 330,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
        )

    val invalid: Map<String, String> =
        mapOf(
            "wrong-type.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": "library",
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "missing-format-version.json" to
                """
                {
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "format-version-zero.json" to
                """
                {
                  "formatVersion": 0,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "missing-seed.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "seed-leading-zeros.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "007",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "seed-negative.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "-1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "seed-not-a-string.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": 12345,
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "hyphenated-id.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111-1111-1111-1111-111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "uppercase-id.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "ceiling-off-ladder.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  },
                  "maxRating": "PG-14"
                }
                """.trimIndent() + "\n",
            "unknown-source-kind.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "playlist",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "no-sources.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "missing-timing.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ]
                }
                """.trimIndent() + "\n",
            "missing-utc-offset.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "offset-not-multiple-of-15.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 7
                    }
                  }
                }
                """.trimIndent() + "\n",
            "offset-out-of-range.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 855
                    }
                  }
                }
                """.trimIndent() + "\n",
            "epoch-hours-5.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "epochHours": 5,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "start-not-on-grid.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0,
                      "start": "06:10"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "ordering-mode-upper-case.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  },
                  "ordering": {
                    "mode": "SHUFFLE"
                  }
                }
                """.trimIndent() + "\n",
            "filler-mode-kebab-case.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  },
                  "filler": {
                    "mode": "pad-to-grid",
                    "source": {
                      "type": "library",
                      "id": "55555555555555555555555555555555"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "name-101-code-points.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filler-between-every-n-missing-n.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  },
                  "filler": {
                    "mode": "betweenEveryN",
                    "source": {
                      "type": "library",
                      "id": "55555555555555555555555555555555"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "grace-hours-49.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "graceHours": 49,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "min-runtime-seconds-0.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "minRuntimeSeconds": 0,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "grid-minutes-45.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "gridMinutes": 45,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "grid-60-start-half-hour.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "gridMinutes": 60,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0,
                      "start": "06:30"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "block-length-0.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "blockLength": 0,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "block-length-101.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "blockLength": 101,
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "max-rating-null.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "maxRating": null,
                  "timing": {
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "align-to-grid-string.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Invalid",
                  "seed": "1",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "timing": {
                    "alignToGrid": "yes",
                    "broadcastDay": {
                      "utcOffsetMinutes": 0
                    }
                  }
                }
                """.trimIndent() + "\n",
            "v3-feature-in-v2.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Version 2 Studio",
                  "seed": "46",
                  "sources": [
                    {
                      "type": "studio",
                      "name": "Studio A"
                    }
                  ],
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
        )

    val validV2: Map<String, String> =
        mapOf(
            "rotation.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Taking Turns",
                  "seed": "11",
                  "sources": [
                    {
                      "type": "collection",
                      "id": "22222222222222222222222222222222"
                    },
                    {
                      "type": "series",
                      "id": "33333333333333333333333333333333"
                    }
                  ],
                  "ordering": {
                    "mode": "rotation"
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "in-order.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "One Show At A Time",
                  "seed": "12",
                  "sources": [
                    {
                      "type": "series",
                      "id": "33333333333333333333333333333333"
                    }
                  ],
                  "ordering": {
                    "mode": "inOrder"
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "shuffle-caps.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Capped Shuffle",
                  "seed": "13",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "ordering": {
                    "mode": "shuffle",
                    "caps": {
                      "series": 2
                    }
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filters.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Short Nineties Comedy",
                  "seed": "14",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "filters": {
                    "genres": [
                      "Comedy",
                      "Animation"
                    ],
                    "years": {
                      "from": 1990,
                      "to": 1999
                    },
                    "runtime": {
                      "maxMinutes": 40
                    },
                    "kind": "episode"
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "item-source.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Two Picks",
                  "seed": "15",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    },
                    {
                      "type": "item",
                      "id": "44444444444444444444444444444444"
                    }
                  ],
                  "exclusions": [
                    {
                      "type": "item",
                      "id": "55555555555555555555555555555555"
                    }
                  ],
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "full.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Everything New",
                  "seed": "16",
                  "sources": [
                    {
                      "type": "collection",
                      "id": "22222222222222222222222222222222"
                    },
                    {
                      "type": "series",
                      "id": "33333333333333333333333333333333"
                    },
                    {
                      "type": "item",
                      "id": "44444444444444444444444444444444"
                    }
                  ],
                  "exclusions": [
                    {
                      "type": "item",
                      "id": "55555555555555555555555555555555"
                    }
                  ],
                  "ordering": {
                    "mode": "rotation",
                    "caps": {
                      "series": 4,
                      "film": 2
                    }
                  },
                  "filters": {
                    "genres": [
                      "Drama"
                    ],
                    "years": {
                      "from": 1980,
                      "to": 2010
                    },
                    "runtime": {
                      "minMinutes": 20,
                      "maxMinutes": 120
                    },
                    "kind": "movie"
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
        )

    val invalidV2: Map<String, String> =
        mapOf(
            "caps-zero.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Caps Zero",
                  "seed": "21",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "ordering": {
                    "mode": "rotation",
                    "caps": {
                      "series": 0
                    }
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "caps-eleven.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Caps Eleven",
                  "seed": "22",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "ordering": {
                    "mode": "rotation",
                    "caps": {
                      "film": 11
                    }
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "caps-short-epoch.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Caps Short Epoch",
                  "seed": "23",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "ordering": {
                    "mode": "rotation",
                    "caps": {
                      "series": 3
                    }
                  },
                  "timing": {
                    "epochHours": 12,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filters-years-reversed.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Years Reversed",
                  "seed": "24",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "filters": {
                    "years": {
                      "from": 1999,
                      "to": 1990
                    }
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filters-runtime-min-over-max.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Runtime Reversed",
                  "seed": "25",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "filters": {
                    "runtime": {
                      "minMinutes": 60,
                      "maxMinutes": 30
                    }
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filters-empty-genres.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Empty Genres",
                  "seed": "26",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "filters": {
                    "genres": []
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "item-source-upper-case-id.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Upper Case Item",
                  "seed": "27",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    },
                    {
                      "type": "item",
                      "id": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    }
                  ],
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "v1-file-rotation.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Version 1 Rotation",
                  "seed": "28",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "ordering": {
                    "mode": "rotation"
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "v1-file-in-order.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Version 1 In Order",
                  "seed": "29",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "ordering": {
                    "mode": "inOrder"
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "v1-file-item-source.json" to
                """
                {
                  "formatVersion": 1,
                  "name": "Version 1 Item",
                  "seed": "30",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    },
                    {
                      "type": "item",
                      "id": "44444444444444444444444444444444"
                    }
                  ],
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filters-kind-season.json" to
                """
                {
                  "formatVersion": 2,
                  "name": "Unknown Filter Kind",
                  "seed": "31",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "filters": {
                    "kind": "season"
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
        )

    val validV3: Map<String, String> =
        mapOf(
            "studio.json" to
                """
                {
                  "formatVersion": 3,
                  "name": "Studio A Night",
                  "seed": "40",
                  "sources": [
                    {
                      "type": "studio",
                      "name": "Studio A"
                    }
                  ],
                  "exclusions": [
                    {
                      "type": "studio",
                      "name": "Studio B"
                    }
                  ],
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "filters-tags-studios.json" to
                """
                {
                  "formatVersion": 3,
                  "name": "Tagged And Produced",
                  "seed": "41",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "filters": {
                    "tags": [
                      "Tag A",
                      "Tag B"
                    ],
                    "studios": [
                      "Studio A",
                      "Studio C"
                    ]
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "not-genres.json" to
                """
                {
                  "formatVersion": 3,
                  "name": "Comedy Without The Dark Side",
                  "seed": "42",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "filters": {
                    "genres": [
                      "Comedy"
                    ],
                    "notGenres": [
                      "Horror",
                      "Thriller"
                    ]
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "runtime-per-kind.json" to
                """
                {
                  "formatVersion": 3,
                  "name": "Short Shows, Mid-Length Films",
                  "seed": "43",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "filters": {
                    "runtime": {
                      "maxMinutes": 180,
                      "episode": {
                        "maxMinutes": 30
                      },
                      "movie": {
                        "minMinutes": 80,
                        "maxMinutes": 150
                      }
                    }
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "curated.json" to
                """
                {
                  "formatVersion": 3,
                  "name": "Programmed",
                  "seed": "44",
                  "sources": [
                    {
                      "type": "library",
                      "id": "11111111111111111111111111111111"
                    }
                  ],
                  "ordering": {
                    "mode": "curated"
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
            "curated-tuned.json" to
                """
                {
                  "formatVersion": 3,
                  "name": "Programmed And Tuned",
                  "seed": "45",
                  "sources": [
                    {
                      "type": "studio",
                      "name": "Studio A"
                    },
                    {
                      "type": "collection",
                      "id": "22222222222222222222222222222222"
                    }
                  ],
                  "ordering": {
                    "mode": "curated",
                    "repeatGapMinutes": 120,
                    "pairUpToMinutes": 25
                  },
                  "filters": {
                    "notGenres": [
                      "Horror"
                    ]
                  },
                  "timing": {
                    "epochHours": 24,
                    "broadcastDay": {
                      "utcOffsetMinutes": -300,
                      "start": "06:00"
                    }
                  }
                }
                """.trimIndent() + "\n",
        )
}
