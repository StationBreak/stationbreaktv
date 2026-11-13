// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * The Kotlin copy of `docs/rules-format/vectors/upgrade-v4.json` (`RatingVectorsInSyncTest` fails if it differs from
 * the file). A test in commonTest cannot read files, because linuxX64Test cannot.
 */
@Suppress("LargeClass") // Data only: one vector file.
internal object UpgradeV4VectorFile {
    val json: String =
        """
        {
          "description": "The upgrade to formatVersion 4 (docs/adr/0026-studio-fold-2.md, Versioning). Each case gives a rules file ('input') and what the app writes back for it before the gate ('beforeGate': the release that reads 4 but does not yet write it) and after the gate ('afterGate': every port reads 4). 'operation' is 'read' (the file is read, imported or restored, then written back unedited) or 'save' (an app that edits the file writes it with no change made: at its write version). Once the gate has passed, a file that says 3 and holds a studio source, a studio exclusion or a studios filter comes out saying 4 with nothing else changed (unknown fields kept); a file without those features, and a file that says 1 or 2, comes out unchanged by a read; a file that says 4 and holds a studio feature is never written lower. The output of every case, read and written again the same way, is unchanged (a file read twice is written once). Files are compared as JSON values.",
          "source": "Hand-written from ADR-0026, Versioning (The rewrite, The invariant, Never lower a file that says 4).",
          "cases": [
            {
              "name": "read: a version 3 file with a studio source (and a curated order and an unknown field)",
              "operation": "read",
              "input": {
                "formatVersion": 3,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              },
              "beforeGate": {
                "formatVersion": 3,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              },
              "afterGate": {
                "formatVersion": 4,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              }
            },
            {
              "name": "read: a version 3 file with a studio exclusion",
              "operation": "read",
              "input": {
                "formatVersion": 3,
                "name": "No Studio B",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  },
                  {
                    "type": "genre",
                    "name": "Comedy"
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
              },
              "beforeGate": {
                "formatVersion": 3,
                "name": "No Studio B",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  },
                  {
                    "type": "genre",
                    "name": "Comedy"
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
              },
              "afterGate": {
                "formatVersion": 4,
                "name": "No Studio B",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  },
                  {
                    "type": "genre",
                    "name": "Comedy"
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
            },
            {
              "name": "read: a version 3 file with a studios filter (and an unknown filter field)",
              "operation": "read",
              "input": {
                "formatVersion": 3,
                "name": "Studio C Films",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  }
                ],
                "filters": {
                  "studios": [
                    "Studio C"
                  ],
                  "kind": "movie",
                  "futureFilter": 1
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                }
              },
              "beforeGate": {
                "formatVersion": 3,
                "name": "Studio C Films",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  }
                ],
                "filters": {
                  "studios": [
                    "Studio C"
                  ],
                  "kind": "movie",
                  "futureFilter": 1
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                }
              },
              "afterGate": {
                "formatVersion": 4,
                "name": "Studio C Films",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  }
                ],
                "filters": {
                  "studios": [
                    "Studio C"
                  ],
                  "kind": "movie",
                  "futureFilter": 1
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                }
              }
            },
            {
              "name": "read: a version 3 file without a studio feature is not upgraded",
              "operation": "read",
              "input": {
                "formatVersion": 3,
                "name": "Tagged",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "filters": {
                  "tags": [
                    "Tag A"
                  ]
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                }
              },
              "beforeGate": {
                "formatVersion": 3,
                "name": "Tagged",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "filters": {
                  "tags": [
                    "Tag A"
                  ]
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                }
              },
              "afterGate": {
                "formatVersion": 3,
                "name": "Tagged",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "filters": {
                  "tags": [
                    "Tag A"
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
            },
            {
              "name": "read: a version 2 file is never upgraded",
              "operation": "read",
              "input": {
                "formatVersion": 2,
                "name": "Rotation",
                "seed": "40",
                "sources": [
                  {
                    "type": "genre",
                    "name": "Drama"
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
              },
              "beforeGate": {
                "formatVersion": 2,
                "name": "Rotation",
                "seed": "40",
                "sources": [
                  {
                    "type": "genre",
                    "name": "Drama"
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
              },
              "afterGate": {
                "formatVersion": 2,
                "name": "Rotation",
                "seed": "40",
                "sources": [
                  {
                    "type": "genre",
                    "name": "Drama"
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
            },
            {
              "name": "read: a version 1 file is never upgraded",
              "operation": "read",
              "input": {
                "formatVersion": 1,
                "name": "Version 1",
                "seed": "40",
                "sources": [
                  {
                    "type": "genre",
                    "name": "Drama"
                  }
                ],
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                }
              },
              "beforeGate": {
                "formatVersion": 1,
                "name": "Version 1",
                "seed": "40",
                "sources": [
                  {
                    "type": "genre",
                    "name": "Drama"
                  }
                ],
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                }
              },
              "afterGate": {
                "formatVersion": 1,
                "name": "Version 1",
                "seed": "40",
                "sources": [
                  {
                    "type": "genre",
                    "name": "Drama"
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
            },
            {
              "name": "read: a version 4 file is left as it is",
              "operation": "read",
              "input": {
                "formatVersion": 4,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              },
              "beforeGate": {
                "formatVersion": 4,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              },
              "afterGate": {
                "formatVersion": 4,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              }
            },
            {
              "name": "save: a version 4 studio file saved before the gate stays 4",
              "operation": "save",
              "input": {
                "formatVersion": 4,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              },
              "beforeGate": {
                "formatVersion": 4,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              },
              "afterGate": {
                "formatVersion": 4,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              }
            },
            {
              "name": "save: a version 3 studio file saved is 3 before the gate and 4 after it",
              "operation": "save",
              "input": {
                "formatVersion": 3,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              },
              "beforeGate": {
                "formatVersion": 3,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              },
              "afterGate": {
                "formatVersion": 4,
                "name": "Studio A Night",
                "seed": "40",
                "sources": [
                  {
                    "type": "studio",
                    "name": "Studio A"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                },
                "futureField": "kept"
              }
            },
            {
              "name": "save: a version 4 file without a studio feature is written at the lowest version it needs",
              "operation": "save",
              "input": {
                "formatVersion": 4,
                "name": "Tagged",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "filters": {
                  "tags": [
                    "Tag A"
                  ]
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                }
              },
              "beforeGate": {
                "formatVersion": 3,
                "name": "Tagged",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "filters": {
                  "tags": [
                    "Tag A"
                  ]
                },
                "timing": {
                  "epochHours": 24,
                  "broadcastDay": {
                    "utcOffsetMinutes": -300,
                    "start": "06:00"
                  }
                }
              },
              "afterGate": {
                "formatVersion": 3,
                "name": "Tagged",
                "seed": "40",
                "sources": [
                  {
                    "type": "library",
                    "id": "11111111111111111111111111111111"
                  }
                ],
                "ordering": {
                  "mode": "curated",
                  "repeatGapMinutes": 120
                },
                "filters": {
                  "tags": [
                    "Tag A"
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
            }
          ]
        }
        """.trimIndent() + "\n"
}
