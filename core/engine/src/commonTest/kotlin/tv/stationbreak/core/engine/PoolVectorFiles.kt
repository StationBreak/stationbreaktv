// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * The Kotlin copies of `docs/rules-format/vectors/pool.json` and `pool-v2.json`. `PoolVectorsInSyncTest` (jvmTest)
 * fail if one differs from its published file. A test in commonTest cannot read files, because linuxX64Test cannot.
 */
@Suppress("LargeClass") // Data only: one vector file.
internal object PoolVectorFiles {
    val pool: String =
        """
        {
          "description": "The pool: which items a channel's rules select from a library snapshot (docs/rules-format.md, Items and the pool, rules 1 to 5, with Sources, Exclusions, the rating ladder and String matching). A case has: name; libraries, the IDs of the libraries in the snapshot; items, the movies and episodes (id, kind 'movie' or 'episode', libraryId, seriesId, seasonId, productionYear, officialRating, genres, tags; seriesId, seasonId, productionYear and officialRating are null when the item has none); series, each series that an episode's seriesId points to and that is in the snapshot (id, officialRating, genres, tags); collections, each collection in the snapshot with the IDs of its member movies, episodes, series and seasons (a member that is itself a collection is not listed: collections are not followed); sources, exclusions and maxRating, as in a rules file (maxRating absent means no ceiling); and expected, the canonical IDs of the pool, sorted. The pool is a set: the order of items and of expected is not part of the rules. Fields that the pool does not read (runtime, dates, season and episode numbers) are left out; a test fills them with valid values. Counts are not part of the vectors. Names that are not ASCII are written as JSON escapes, so this file is all ASCII.",
          "source": "Expected values come from an independent Python reference written from docs/rules-format.md alone (Items and the pool rules 1 to 5, Sources, Exclusions, Maximum rating and the rating ladder, String matching), before the Kotlin evaluator in core:engine was read. It applies the rules as amended on 2026-10-19: exclusions fail closed (an exclusion naming a collection that is not in the snapshot empties the pool, and a genre or tag exclusion removes an episode whose series ID is set but whose series is not in the snapshot), and the rating ceiling checks an episode's series' rating too.",
          "cases": [
            {
              "name": "rule 1: only movies and episodes count; a series, season or collection is never an item",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 2 library: an item anywhere inside the library; other libraries do not match",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 2 collection: a member movie",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000502",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000503",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000504",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000505",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101"
              ]
            },
            {
              "name": "rule 2 collection: a member episode",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000502",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000503",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000504",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000505",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [],
                  "episodes": [
                    "00000000000000000000000000000203"
                  ],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 2 collection: an episode of a member series",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000502",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000503",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000504",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000505",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [],
                  "episodes": [],
                  "series": [
                    "00000000000000000000000000000301"
                  ],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000202"
              ]
            },
            {
              "name": "rule 2 collection: a member season selects only its own episodes",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000502",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000503",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000504",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000505",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [],
                  "episodes": [],
                  "series": [],
                  "seasons": [
                    "00000000000000000000000000000504"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000204"
              ]
            },
            {
              "name": "rule 2 collection: every member kind together",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000502",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000503",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000504",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000505",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [
                    "00000000000000000000000000000203"
                  ],
                  "series": [
                    "00000000000000000000000000000301"
                  ],
                  "seasons": [
                    "00000000000000000000000000000505"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203",
                "00000000000000000000000000000205"
              ]
            },
            {
              "name": "rule 2 collection: a member that is itself a collection selects nothing (no recursion)",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000502",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000503",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000504",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000505",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000402",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [
                    "00000000000000000000000000000301"
                  ],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": []
            },
            {
              "name": "rule 2 collection: a collection absent from the snapshot as a source matches nothing",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000502",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000503",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000504",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000505",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000499"
                }
              ],
              "exclusions": [],
              "expected": []
            },
            {
              "name": "rule 2 series: every episode of that series, no movie, no other series' episodes",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "series",
                  "id": "00000000000000000000000000000301"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 2 series: the series is not in the snapshot, its episodes still match by ID",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "series",
                  "id": "00000000000000000000000000000399"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 2 genre: the item's own genre, or for an episode its series' genre",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Drama"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [
                    "Drama"
                  ],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Comedy"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000201",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 2 genre: an episode of a series without the genre, a loose episode and an episode of a missing series use own names only",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Drama"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [
                    "Drama"
                  ],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Drama"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 2 tag: the item's own tag, or for an episode its series' tag",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Holiday"
                  ]
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Holiday"
                  ]
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Holiday"
                  ]
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "tag",
                  "name": "Holiday"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000201",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 2 genre: a movie takes a genre from itself only",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Comedy"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "rule 2 yearRange: the item's own year, both ends included; no year is not included; no guessing from the series",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1989,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1990,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1999,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 2000,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": 2005,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "yearRange",
                  "from": 1990,
                  "to": 1999
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 2 yearRange: a single-year range",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1999,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 2000,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 2001,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "yearRange",
                  "from": 2000,
                  "to": 2000
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "rule 3 union: an item matching several sources is in the pool once",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1996,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "genre",
                  "name": "Comedy"
                },
                {
                  "type": "yearRange",
                  "from": 1990,
                  "to": 1999
                },
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104"
              ]
            },
            {
              "name": "rule 3 union: sources are a union, not an intersection",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Comedy"
                },
                {
                  "type": "yearRange",
                  "from": 1990,
                  "to": 1999
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103"
              ]
            },
            {
              "name": "rule 3 exclusion genre: removes an item with the genre, and an episode whose series has it",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1950,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Seen It"
                  ]
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": [
                    "00000000000000000000000000000501"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "exclusions": [
                {
                  "type": "genre",
                  "name": "Horror"
                }
              ],
              "expected": [
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 3 exclusion tag",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1950,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Seen It"
                  ]
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": [
                    "00000000000000000000000000000501"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "exclusions": [
                {
                  "type": "tag",
                  "name": "Seen It"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 3 exclusion yearRange",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1950,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Seen It"
                  ]
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": [
                    "00000000000000000000000000000501"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "exclusions": [
                {
                  "type": "yearRange",
                  "from": 1900,
                  "to": 1969
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 3 exclusion library: removes every item of that library",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1950,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Seen It"
                  ]
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": [
                    "00000000000000000000000000000501"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "exclusions": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 3 exclusion series: removes the episodes of that series",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1950,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Seen It"
                  ]
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": [
                    "00000000000000000000000000000501"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "exclusions": [
                {
                  "type": "series",
                  "id": "00000000000000000000000000000302"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 3 exclusion collection: removes a member movie and the episodes of a member season",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1950,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Seen It"
                  ]
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": [
                    "00000000000000000000000000000501"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "exclusions": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000105",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202"
              ]
            },
            {
              "name": "rule 3 exclusions: several at once, any match removes",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1950,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Seen It"
                  ]
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": [
                    "00000000000000000000000000000501"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "exclusions": [
                {
                  "type": "genre",
                  "name": "Horror"
                },
                {
                  "type": "tag",
                  "name": "Seen It"
                },
                {
                  "type": "yearRange",
                  "from": 1900,
                  "to": 1969
                }
              ],
              "expected": [
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 3: a source equal to an exclusion contributes nothing; the exclusion wins",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1950,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Seen It"
                  ]
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": "00000000000000000000000000000501",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": [
                    "00000000000000000000000000000501"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "expected": []
            },
            {
              "name": "rule 3: an excluded item cannot be brought back by a second source",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Horror"
                },
                {
                  "type": "yearRange",
                  "from": 1990,
                  "to": 1999
                }
              ],
              "exclusions": [
                {
                  "type": "genre",
                  "name": "Horror"
                }
              ],
              "expected": [
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "rule 3 fail closed: a collection exclusion whose collection is not in the snapshot empties the pool",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000499"
                }
              ],
              "expected": []
            },
            {
              "name": "rule 3 fail closed: an unresolved collection exclusion empties the pool even next to a resolved one",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                },
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000499"
                }
              ],
              "expected": []
            },
            {
              "name": "rule 3 fail closed: a collection exclusion that resolves removes only its members (control)",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "expected": [
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "rule 3 fail closed: a genre exclusion removes an episode whose series is missing, whatever its own genres; a loose episode and a movie are checked on their own",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "genre",
                  "name": "Horror"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 3 fail closed: a tag exclusion removes an episode whose series is missing",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "tag",
                  "name": "Seen It"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 3 fail closed: a library exclusion is decided by IDs and does not touch an episode of a missing series in another library",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "exclusions": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001002"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 3 fail closed: a series exclusion is decided by IDs and does not touch an episode of another missing series",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000398",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "series",
                  "id": "00000000000000000000000000000398"
                }
              ],
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 3 fail closed: a yearRange exclusion does not remove an episode of a missing series by that alone",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": 2000,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": 1950,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "yearRange",
                  "from": 1900,
                  "to": 1969
                }
              ],
              "expected": [
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 3 fail closed: a genre source uses an episode's own names only when its series is missing",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Comedy"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 4 movie: passes only on its own rating, at or below the ceiling",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "tv-y7-fv",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000106",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000107",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000108",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "FSK 16",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000109",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "0000000000000000000000000000010a",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "Approved",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "0000000000000000000000000000010b",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-MA-LSV",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-Y7",
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000104",
                "0000000000000000000000000000010a"
              ]
            },
            {
              "name": "rule 4 episode: own TV-Y7, series TV-MA, ceiling TV-Y7 is excluded (the stale rating triple)",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-Y7",
              "expected": []
            },
            {
              "name": "rule 4 episode: series passes and own rating passes",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-G",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-Y7",
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000202"
              ]
            },
            {
              "name": "rule 4 (a): an episode with no own rating takes its series' rating",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-Y7",
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 4 (a): an own rating of NR, blank or Unrated is used as it is, never replaced by the series' rating",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-Y7",
              "expected": []
            },
            {
              "name": "rule 4 (b): a series rated NR, Unrated or blank blocks its episodes even with a passing own rating",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000305",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000307",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000306",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-Y7",
              "expected": []
            },
            {
              "name": "rule 4 (b): a series with no rating, or the empty string, does not block; (a) decides",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000304",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000304",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-Y7",
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000202"
              ]
            },
            {
              "name": "rule 4: a series above the ceiling blocks an episode whose own rating passes; a series at the ceiling does not",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000308",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000308",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-14",
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000202"
              ]
            },
            {
              "name": "rule 4: an episode whose series is missing from the snapshot is not allowed under a ceiling",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-Y7",
              "expected": [
                "00000000000000000000000000000202"
              ]
            },
            {
              "name": "rule 4: a loose episode (no series ID) is checked on its own rating alone",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "TV-Y7",
              "expected": [
                "00000000000000000000000000000201"
              ]
            },
            {
              "name": "rule 4: no ceiling admits everything, rated or not, even an episode of a missing series",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000305",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "FSK 16",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ]
            },
            {
              "name": "rule 4: the ceiling is applied on top of sources and exclusions",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "PG",
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "R",
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "PG",
                  "genres": [
                    "Comedy",
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "PG",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Comedy"
                }
              ],
              "exclusions": [
                {
                  "type": "genre",
                  "name": "Horror"
                }
              ],
              "maxRating": "PG-13",
              "expected": [
                "00000000000000000000000000000101"
              ]
            },
            {
              "name": "rule 4: the ceiling Approved is rank 0",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "G",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "Approved",
              "expected": [
                "00000000000000000000000000000101"
              ]
            },
            {
              "name": "rules 3 and 4 together: a genre exclusion and a ceiling over an episode of a missing series",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-Y7",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "NR",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000306",
                  "officialRating": "  ",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000307",
                  "officialRating": "Unrated",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000308",
                  "officialRating": "TV-14",
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "genre",
                  "name": "Horror"
                }
              ],
              "maxRating": "TV-Y7",
              "expected": [
                "00000000000000000000000000000202"
              ]
            },
            {
              "name": "an empty result: sources that match nothing",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Nothing"
                }
              ],
              "exclusions": [],
              "expected": []
            },
            {
              "name": "an empty result: a snapshot with no items",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "expected": []
            },
            {
              "name": "an empty result: every item is excluded",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "expected": []
            },
            {
              "name": "string matching: ASCII letters fold in both directions",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "COMEDY"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comed"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "cOmEdY"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103"
              ]
            },
            {
              "name": "string matching: a trailing space is not trimmed from the server's name",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy "
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Comedy"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "string matching: a space in the rule's name is not trimmed either",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy "
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Comedy "
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "string matching: NFC and NFD are different strings (the rule names NFC)",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Anim\u00e9"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Anime\u0301"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Anim\u00e9"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101"
              ]
            },
            {
              "name": "string matching: NFC and NFD are different strings (the rule names NFD)",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Anim\u00e9"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Anime\u0301"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "Anime\u0301"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "string matching: non-ASCII letters match exactly, with no case folding",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Anim\u00e9"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "ANIM\u00c9"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "ANIM\u00e9"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Anim\u00c9"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "ANIM\u00e9"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000103"
              ]
            },
            {
              "name": "string matching: the Turkish dotted capital I (U+0130) and dotless i (U+0131) do not fold to i",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Istanbul"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "istanbul"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "\u0130stanbul"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "\u0131stanbul"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "ISTANBUL"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "string matching: a rule naming the Turkish dotted capital I matches only that",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Istanbul"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "\u0130stanbul"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "\u0131stanbul"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "\u0130STANBUL"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "string matching: the Kelvin sign (U+212A) does not fold to k",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Kids"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "kids"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "\u212aids"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "KIDS"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "string matching: a rule naming the Kelvin sign matches only it",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Kids"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "\u212aids"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "genre",
                  "name": "\u212aids"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "string matching: a tag with an emoji (a surrogate pair in the file) matches by its code points",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Fun \ud83d\ude00"
                  ]
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "fun \ud83d\ude00"
                  ]
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Fun"
                  ]
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "tag",
                  "name": "FUN \ud83d\ude00"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ]
            },
            {
              "name": "string matching: the same rules apply to exclusions",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horr\u00f6r"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "genre",
                  "name": "HORROR"
                }
              ],
              "expected": [
                "00000000000000000000000000000103"
              ]
            }
          ]
        }
        """.trimIndent() + "\n"

    val poolV2: String =
        """
        {
          "description": "The version 2 pool, in the pool.json case shape (see its description), for a file that says formatVersion 2. Two fields are added. filters, when present, is the rules file's filters object (genres, years, kind; an empty object is allowed); absent means none. The runtime part of filters is left out here: it is applied where the epoch's pool is cut (The pool of an epoch), not by the pool evaluator, and lineup-v2.json covers it. films, in every case, is the film-to-collection map of Groups in version 2 for this pool: each movie of the pool that is a member movie of a collection named in sources (a collection source) and present in the snapshot, mapped to that collection's ID, the smallest such ID (32 ASCII bytes, unsigned) when there are several; an empty object when none. Only rotation and a capped shuffle read the map; it is given here whatever the mode. Sources and exclusions may use type item.",
          "source": "Computed with an independent Python reference written from docs/rules-format.md only (Items and the pool, Sources, Exclusions, Filters, Maximum rating and the rating ladder, String matching, Groups in version 2), without reading any Kotlin. The reference script is not committed, as for the version 1 vectors; it reproduces every case of pool.json and of rating.json's ladder, normalise and eligibility tables. Item IDs are synthetic.",
          "cases": [
            {
              "name": "filters genres: any of the names passes",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Drama",
                    "War"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "filters": {
                "genres": [
                  "Comedy",
                  "Drama"
                ]
              },
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ],
              "films": {}
            },
            {
              "name": "filters genres: an episode takes its series' genres; with its series missing it uses its own names only",
              "note": "Series ...0399 and ...0398 are not in the snapshot. As for a genre source, the filter is not fail-closed: ...0203 passes on its own genre, ...0204 has none and fails. ...0205 and ...0206 are loose episodes.",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000398",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000206",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "filters": {
                "genres": [
                  "Comedy"
                ]
              },
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203",
                "00000000000000000000000000000205"
              ],
              "films": {}
            },
            {
              "name": "filters genres: names are matched as String matching says (ASCII letters fold, nothing else)",
              "note": "\u00e9 and \u00c9 are not ASCII and do not fold; a trailing space is not trimmed.",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "COMEDY"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Anim\u00e9"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "ANIM\u00c9"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy "
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "filters": {
                "genres": [
                  "comedy",
                  "anim\u00e9"
                ]
              },
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ],
              "films": {}
            },
            {
              "name": "filters years: from and to both included; an item with no production year fails",
              "note": "The episode ...0201 has no production year of its own; a year is never taken from the series.",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1989,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1990,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1999,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 2000,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000106",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "filters": {
                "years": {
                  "from": 1990,
                  "to": 1999
                }
              },
              "expected": [
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104"
              ],
              "films": {}
            },
            {
              "name": "filters years: a single-year range",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1999,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 2000,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 2001,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "filters": {
                "years": {
                  "from": 2000,
                  "to": 2000
                }
              },
              "expected": [
                "00000000000000000000000000000102"
              ],
              "films": {}
            },
            {
              "name": "filters kind movie",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "filters": {
                "kind": "movie"
              },
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ],
              "films": {}
            },
            {
              "name": "filters kind episode: series episodes and loose episodes",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "filters": {
                "kind": "episode"
              },
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000202"
              ],
              "films": {}
            },
            {
              "name": "filters: an empty filters object changes nothing",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1990,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "filters": {},
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000201"
              ],
              "films": {}
            },
            {
              "name": "filters: every part must pass (genres AND years AND kind)",
              "note": "...0101 is a movie; ...0202 is out of the years; ...0203's series is Drama.",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": 2005,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1996,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [
                    "Drama"
                  ],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "filters": {
                "genres": [
                  "Comedy"
                ],
                "years": {
                  "from": 1990,
                  "to": 1999
                },
                "kind": "episode"
              },
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000204"
              ],
              "films": {}
            },
            {
              "name": "filters AND the rating ceiling: an item has to pass both",
              "note": "...0102 is over the ceiling; ...0103 fails the years; ...0104 is unrated (fails closed); ...0202's series is TV-MA.",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": "PG",
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": "R",
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1985,
                  "officialRating": "PG",
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-PG",
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-MA",
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "maxRating": "PG-13",
              "filters": {
                "genres": [
                  "Comedy"
                ],
                "years": {
                  "from": 1990,
                  "to": 1999
                }
              },
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000201"
              ],
              "films": {}
            },
            {
              "name": "filters come after the exclusions: an excluded item that passes the filters stays out, and a second source does not bring back an item a filter rejects",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": [
                    "Seen It"
                  ]
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Drama"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000103"
                },
                {
                  "type": "genre",
                  "name": "Drama"
                }
              ],
              "exclusions": [
                {
                  "type": "tag",
                  "name": "Seen It"
                }
              ],
              "filters": {
                "genres": [
                  "Comedy"
                ]
              },
              "expected": [
                "00000000000000000000000000000102"
              ],
              "films": {}
            },
            {
              "name": "item source: adds one movie and one episode to the union",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000101"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000202"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000103",
                "00000000000000000000000000000202"
              ],
              "films": {}
            },
            {
              "name": "item exclusion: removes one episode from a series source",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "series",
                  "id": "00000000000000000000000000000301"
                }
              ],
              "exclusions": [
                {
                  "type": "item",
                  "id": "00000000000000000000000000000202"
                }
              ],
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000203"
              ],
              "films": {}
            },
            {
              "name": "item source: an ID not in the snapshot, or naming a series, a collection or a library, matches nothing",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "item",
                  "id": "00000000000000000000000000000999"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000301"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000401"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "expected": [],
              "films": {}
            },
            {
              "name": "item exclusion: an unknown ID removes nothing, and an item exclusion does not fail closed",
              "note": "Series ...0399 is not in the snapshot. The item exclusion is decided by the ID alone: it removes ...0201 and leaves ...0202.",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [
                {
                  "type": "item",
                  "id": "00000000000000000000000000000998"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000201"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000202"
              ],
              "films": {}
            },
            {
              "name": "item source and item exclusion of the same ID: the exclusion wins",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "item",
                  "id": "00000000000000000000000000000101"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000102"
                }
              ],
              "exclusions": [
                {
                  "type": "item",
                  "id": "00000000000000000000000000000101"
                }
              ],
              "expected": [
                "00000000000000000000000000000102"
              ],
              "films": {}
            },
            {
              "name": "item source: the rating ceiling still applies to a picked item",
              "note": "...0201's series ...0399 is not in the snapshot, so under a ceiling it fails closed, even when picked by ID.",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "R",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "PG",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-Y",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "item",
                  "id": "00000000000000000000000000000101"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000102"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000201"
                }
              ],
              "exclusions": [],
              "maxRating": "PG-13",
              "expected": [
                "00000000000000000000000000000102"
              ],
              "films": {}
            },
            {
              "name": "item source with a genre filter: a picked item still has to pass the filters",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Drama"
                  ],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "item",
                  "id": "00000000000000000000000000000101"
                },
                {
                  "type": "item",
                  "id": "00000000000000000000000000000102"
                }
              ],
              "exclusions": [],
              "filters": {
                "genres": [
                  "Comedy"
                ]
              },
              "expected": [
                "00000000000000000000000000000101"
              ],
              "films": {}
            },
            {
              "name": "films map: a film in two named collections maps to the smaller collection ID",
              "note": "The larger ID is listed first in sources and in collections: the order does not matter, the smaller 32-hex ID wins.",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "000000000000000000000000000004f2",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "000000000000000000000000000004a1",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000103"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "000000000000000000000000000004f2"
                },
                {
                  "type": "collection",
                  "id": "000000000000000000000000000004a1"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103"
              ],
              "films": {
                "00000000000000000000000000000101": "000000000000000000000000000004a1",
                "00000000000000000000000000000102": "000000000000000000000000000004f2",
                "00000000000000000000000000000103": "000000000000000000000000000004a1"
              }
            },
            {
              "name": "films map: the smaller ID is compared as bytes (a digit sorts before a letter)",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "a0000000000000000000000000000000",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "9fffffffffffffffffffffffffffffff",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "a0000000000000000000000000000000"
                },
                {
                  "type": "collection",
                  "id": "9fffffffffffffffffffffffffffffff"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101"
              ],
              "films": {
                "00000000000000000000000000000101": "9fffffffffffffffffffffffffffffff"
              }
            },
            {
              "name": "films map: a film in a named and an unnamed collection maps to the named one, even when the unnamed ID is smaller",
              "note": "Collection ...04a1 is in the snapshot but is not a source: it groups nothing, so ...0102 (in the pool through the library) is not in the map.",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "000000000000000000000000000004b2",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "000000000000000000000000000004a1",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "collection",
                  "id": "000000000000000000000000000004b2"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ],
              "films": {
                "00000000000000000000000000000101": "000000000000000000000000000004b2"
              }
            },
            {
              "name": "films map: a collection of shows maps no films; episodes keep their series",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000351",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": "00000000000000000000000000000352",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [],
                  "episodes": [
                    "00000000000000000000000000000202"
                  ],
                  "series": [
                    "00000000000000000000000000000301"
                  ],
                  "seasons": [
                    "00000000000000000000000000000352"
                  ]
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ],
              "films": {}
            },
            {
              "name": "films map: only member movies are grouped; a member episode in the same collection is not",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [
                    "00000000000000000000000000000201"
                  ],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000201"
              ],
              "films": {
                "00000000000000000000000000000101": "00000000000000000000000000000401",
                "00000000000000000000000000000102": "00000000000000000000000000000401"
              }
            },
            {
              "name": "films map: films outside the pool are not in the map (excluded, filtered out, over the ceiling)",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": "PG",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": "PG",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1970,
                  "officialRating": "PG",
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000104",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": "R",
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000102",
                    "00000000000000000000000000000103",
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [
                {
                  "type": "item",
                  "id": "00000000000000000000000000000102"
                }
              ],
              "maxRating": "PG-13",
              "filters": {
                "years": {
                  "from": 1990,
                  "to": 1999
                }
              },
              "expected": [
                "00000000000000000000000000000101"
              ],
              "films": {
                "00000000000000000000000000000101": "00000000000000000000000000000401"
              }
            },
            {
              "name": "films map: a film that entered the pool through another source is still grouped by a named collection",
              "libraries": [
                "00000000000000000000000000001001",
                "00000000000000000000000000001002"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001002",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ],
              "films": {
                "00000000000000000000000000000101": "00000000000000000000000000000401",
                "00000000000000000000000000000102": "00000000000000000000000000000401"
              }
            },
            {
              "name": "films map: a named collection that is not in the snapshot groups nothing (and selects nothing)",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "collection",
                  "id": "000000000000000000000000000004ff"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ],
              "films": {}
            },
            {
              "name": "films map: a collection that is only an exclusion groups nothing; a named collection with a film the exclusion removes",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000103",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000402",
                  "movies": [
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000402"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000103"
              ],
              "films": {
                "00000000000000000000000000000101": "00000000000000000000000000000401"
              }
            },
            {
              "name": "films map: a collection that is both a source and an exclusion contributes nothing and maps nothing",
              "libraries": [
                "00000000000000000000000000001001"
              ],
              "items": [
                {
                  "id": "00000000000000000000000000000101",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                },
                {
                  "id": "00000000000000000000000000000102",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                }
              ],
              "sources": [
                {
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                },
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "exclusions": [
                {
                  "type": "collection",
                  "id": "00000000000000000000000000000401"
                }
              ],
              "expected": [
                "00000000000000000000000000000102"
              ],
              "films": {}
            }
          ]
        }
        """.trimIndent() + "\n"
}
