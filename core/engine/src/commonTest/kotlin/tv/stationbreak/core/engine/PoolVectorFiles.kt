// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * The Kotlin copies of `docs/rules-format/vectors/pool.json`, `pool-v2.json`, `pool-v3.json` and `pool-v4.json`.
 * `PoolVectorsInSyncTest` (jvmTest) fail if one differs from its published file. A test in commonTest cannot read
 * files, because linuxX64Test cannot.
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

    val poolV3: String =
        """
        {
          "description": "The version 3 pool, in the pool-v2.json case shape (see its description), for a file that says formatVersion 3. Items and series also carry studios: the server's studio names as sent (before the studio fold; a matcher folds both sides, and the fold is idempotent, so a client that cleans the names when it reads them gets the same result). Sources and exclusions may use type studio; filters may hold tags, studios, notGenres and runtime (with the version 3 episode and movie windows). expected is the pool as the pool evaluator gives it, without the runtime part of filters, which is applied where the epoch's pool is cut (The pool of an epoch). A case whose filters hold runtime also gives each item runtimeMs (decimal string) and expectedAfterRuntime, the pool after the runtime part too, with every other epoch condition taken as passed. films is the version 2 film-to-collection map (collections named in sources). franchises, in every case, is the franchise map of curated: each movie of the pool that is a member movie of any collection of the snapshot whose name is a box set name, mapped to the smallest such collection ID (32 ASCII bytes, unsigned); an empty object when none. A snapshot collection carries a name (the server's name as sent, before any cleaning); a collection with no name field has no name. A box set name is one that, after the clean step of the studio fold and the ASCII fold, ends with a space and collection. Strings are written with \\u escapes for everything outside ASCII; one case has an unpaired surrogate escape in an item's studio name.",
          "source": "independent Python reference, not committed",
          "cases": [
            {
              "name": "studio source: a movie's own studio and an episode's series' studio match, ASCII letters fold",
              "note": "...0201 takes series ...0301's studio. ...0203 has the studio itself (its series has another). ...0204 is a loose episode with its own studio.",
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
                  "tags": [],
                  "studios": [
                    "STUDIO A"
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
                  "tags": [],
                  "studios": [
                    "Studio B",
                    "studio a"
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
                  "tags": [],
                  "studios": [
                    "Studio B"
                  ]
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": [
                    "Studio A"
                  ]
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
                  "tags": [],
                  "studios": [
                    "studio A"
                  ]
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio C"
                  ]
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": "Studio A"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000201",
                "00000000000000000000000000000203",
                "00000000000000000000000000000204"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "studio source: the studio fold cleans both sides (no-break space trimmed, zero-width space and soft hyphen dropped, tab and line separator become a space)",
              "note": "In: trailing U+00A0 trimmed; U+200B dropped; a leading tab becomes a space and is trimmed; U+2028 between the words becomes a space; U+00AD dropped; U+FEFF dropped and a trailing U+3000 trimmed; U+0085 (in U+007F to U+009F) dropped. ...0115 has a tab between the words (written as one space) and a trailing space (trimmed). Out: two spaces stay two; \u00f6 does not fold; U+200C is kept (not in the dropped set); a no-break space inside the name is not trimmed and is not a space; no space at all.",
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
                  "tags": [],
                  "studios": [
                    "Studio A\u00a0"
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
                  "tags": [],
                  "studios": [
                    "Stu\u200bdio A"
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
                  "tags": [],
                  "studios": [
                    "\tStudio A"
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
                  "tags": [],
                  "studios": [
                    "Studio\u2028A"
                  ]
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
                  "tags": [],
                  "studios": [
                    "Stu\u00addio A"
                  ]
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
                  "tags": [],
                  "studios": [
                    "\ufeffStudio A\u3000"
                  ]
                },
                {
                  "id": "00000000000000000000000000000107",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio\u0085 A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000111",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio  A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000112",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studi\u00f6 A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000113",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Stu\u200cdio A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000114",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio\u00a0A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000115",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio\tA "
                  ]
                },
                {
                  "id": "00000000000000000000000000000116",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "StudioA"
                  ]
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": "Studio A"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000106",
                "00000000000000000000000000000107",
                "00000000000000000000000000000115"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "studio source: the rules name is cleaned too; a name that is only spaces and dropped characters matches nothing",
              "note": "The first source cleans to STUDIO A and folds to studio a. The second cleans to nothing: it matches nothing, not even the items whose studio also cleans to nothing (such a studio is dropped).",
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
                  "tags": [],
                  "studios": [
                    "Studio A"
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
                  "tags": [],
                  "studios": [
                    "Studio B"
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
                  "tags": [],
                  "studios": [
                    "   "
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
                  "tags": [],
                  "studios": [
                    "\u200b"
                  ]
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": " \u00a0STUDIO\u200b A\u2003"
                },
                {
                  "type": "studio",
                  "name": " \u200b\u00a0 "
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "studio source: an episode whose series is not in the snapshot uses its own studios (no fail closed)",
              "libraries": [
                "00000000000000000000000000001001"
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
                  "tags": [],
                  "studios": [
                    "Studio A"
                  ]
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio A"
                  ]
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": "Studio A"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000203"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "studio source: a lone surrogate in a server studio name is dropped",
              "note": "The item names hold an unpaired surrogate (JSON escape \\ud800 or \\udfff alone), which the clean step drops. The emoji (a valid pair) is kept, so ...0103 does not match. A rules name with an unpaired surrogate would make the file invalid, so only item names carry one.",
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
                  "tags": [],
                  "studios": [
                    "Stu\ud800dio A"
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
                  "tags": [],
                  "studios": [
                    "Studio A\udfff"
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
                  "tags": [],
                  "studios": [
                    "Studio \ud83d\ude00"
                  ]
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": "Studio A"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "studio exclusion: removes a movie of the studio and every episode of a series of the studio; it does not fail closed",
              "note": "Series ...0399 is not in the snapshot: ...0203 stays (a studio exclusion is not fail-closed, unlike a genre or tag exclusion); ...0204 is removed by its own studio.",
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
                  "tags": [],
                  "studios": [
                    "Studio A"
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
                  "tags": [],
                  "studios": [
                    "Studio B"
                  ]
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": [
                    "studio a"
                  ]
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio C",
                    "Studio A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio C"
                  ]
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
                  "type": "studio",
                  "name": "Studio A"
                }
              ],
              "expected": [
                "00000000000000000000000000000102",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203",
                "00000000000000000000000000000205"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "studio source and exclusion together, with a genre source: the exclusion wins",
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
                    "Drama"
                  ],
                  "tags": [],
                  "studios": [
                    "Studio A"
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
                  "tags": [],
                  "studios": [
                    "Studio A",
                    "Studio X"
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
                  "genres": [
                    "Drama"
                  ],
                  "tags": [],
                  "studios": []
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
                    "Drama"
                  ],
                  "tags": [],
                  "studios": [
                    "STUDIO X"
                  ]
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": "Studio A"
                },
                {
                  "type": "genre",
                  "name": "Drama"
                }
              ],
              "exclusions": [
                {
                  "type": "studio",
                  "name": "Studio X"
                }
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000103"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "filters studios: any of the studios, under the studio fold; an episode takes its series' studios; a missing series uses its own only",
              "note": "The filter's third name cleans to nothing and matches nothing; the second cleans to Studio B. ...0203's series is missing and it has no studio of its own: it fails, but the filter does not fail closed (...0202 passes on its own studio).",
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
                  "tags": [],
                  "studios": [
                    "Studio A"
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
                  "tags": [],
                  "studios": [
                    "studio b "
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
                  "tags": [],
                  "studios": [
                    "Studio C"
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": [
                    "Studio B"
                  ]
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "STUDIO A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio C"
                  ]
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
                "studios": [
                  "Studio A",
                  "Studio\u200b B",
                  "  "
                ]
              },
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "filters tags: any of the names, ASCII letters fold only; an episode takes its series' tags; a missing series uses its own only",
              "note": "Tags are matched as a tag source: a trailing space is not trimmed and non-ASCII letters do not fold.",
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
                  "tags": [
                    "Holiday"
                  ],
                  "studios": []
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
                    "HOLIDAY",
                    "Other"
                  ],
                  "studios": []
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
                    "Holiday "
                  ],
                  "studios": []
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
                  "tags": [
                    "H\u00f6lid\u00e4y"
                  ],
                  "studios": []
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
                  "tags": [
                    "Feature"
                  ],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [
                    "feature"
                  ],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Holiday"
                  ],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [
                    "Holiday"
                  ],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
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
                "tags": [
                  "holiday",
                  "Feature"
                ]
              },
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000105",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000204"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "filters notGenres: none of the names; an episode also takes its series' genres; it fails closed on a missing series",
              "note": "...0201's series has Horror. ...0203 has it itself. ...0204 and ...0205 have series ...0399, which is not in the snapshot: they fail whatever their own genres are. ...0206 and ...0207 are loose episodes, checked on their own genres.",
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
                    "Horror"
                  ],
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                    "HORROR"
                  ],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horr\u00f6r"
                  ],
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                    "Horror"
                  ],
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000399",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000207",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "horror"
                  ],
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Comedy",
                    "Horror"
                  ],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": [],
                  "studios": []
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
                "notGenres": [
                  "horror"
                ]
              },
              "expected": [
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000105",
                "00000000000000000000000000000202",
                "00000000000000000000000000000206"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "filters notGenres with genres: an item needs one of genres and none of notGenres",
              "note": "...0203 passes genres on its own genre (that filter does not fail closed) but fails notGenres (it does).",
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
                  "tags": [],
                  "studios": []
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
                    "Comedy",
                    "Horror"
                  ],
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                    "Drama"
                  ],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [
                    "Horror"
                  ],
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [
                    "Comedy"
                  ],
                  "tags": [],
                  "studios": []
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
                "notGenres": [
                  "Horror",
                  "War"
                ]
              },
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000202"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "filters notGenres AND tags AND studios AND kind AND the rating ceiling",
              "note": "...0101 is a movie; ...0202's series is Horror; ...0203's series has no Pick tag; ...0204's series is Studio B; ...0205's series is over the ceiling.",
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
                  "officialRating": "PG",
                  "genres": [],
                  "tags": [
                    "Pick"
                  ],
                  "studios": [
                    "Studio A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000202",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000302",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000203",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000303",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000304",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000205",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000305",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": [
                    "Pick"
                  ],
                  "studios": [
                    "Studio A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": "TV-PG",
                  "genres": [
                    "Horror"
                  ],
                  "tags": [
                    "Pick"
                  ],
                  "studios": [
                    "Studio A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000303",
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Studio A"
                  ]
                },
                {
                  "id": "00000000000000000000000000000304",
                  "officialRating": "TV-PG",
                  "genres": [],
                  "tags": [
                    "Pick"
                  ],
                  "studios": [
                    "Studio B"
                  ]
                },
                {
                  "id": "00000000000000000000000000000305",
                  "officialRating": "TV-MA",
                  "genres": [],
                  "tags": [
                    "Pick"
                  ],
                  "studios": [
                    "Studio A"
                  ]
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
              "filters": {
                "notGenres": [
                  "Horror"
                ],
                "tags": [
                  "Pick"
                ],
                "studios": [
                  "studio a"
                ],
                "kind": "episode"
              },
              "expected": [
                "00000000000000000000000000000201"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "filters runtime: a general window and one for each kind, at their edges (expectedAfterRuntime)",
              "note": "The runtime part is applied where the epoch's pool is cut, not by the pool evaluator, so expected leaves it out; expectedAfterRuntime is the pool after the runtime part too (runtimeMs as at the cut-off). Movie window 80 to 150 minutes: 4,799,999 ms out, 4,800,000 in, 9,000,000 in, 9,000,001 out. Episode window up to 30 minutes: 1,800,000 in, 1,800,001 out. ...0204 is out by the general window (181 min > 180) and its own window.",
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "4799999"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "4800000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "9000000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "9000001"
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "1800000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "1800001"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "60000"
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "10860000"
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
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
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203",
                "00000000000000000000000000000204"
              ],
              "expectedAfterRuntime": [
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000201",
                "00000000000000000000000000000203"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "filters runtime: only an episode window: films are not limited by it",
              "note": "Episode window 2 to 181 minutes: ...0203 (60,000 ms) is out; ...0204 (181 min exactly) is in. Every movie stays, up to 1440 minutes.",
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "4799999"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "4800000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "9000000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "9000001"
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "1800000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "1800001"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "60000"
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "10860000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "86400000"
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
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
                "runtime": {
                  "episode": {
                    "minMinutes": 2,
                    "maxMinutes": 181
                  }
                }
              },
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203",
                "00000000000000000000000000000204"
              ],
              "expectedAfterRuntime": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000204"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "filters runtime: only a movie window with a minimum, and a general minimum",
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "4799999"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "4800000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "9000000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "9000001"
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "1800000"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "1800001"
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
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "60000"
                },
                {
                  "id": "00000000000000000000000000000204",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [],
                  "runtimeMs": "10860000"
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
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
                "runtime": {
                  "minMinutes": 1,
                  "movie": {
                    "minMinutes": 150
                  }
                }
              },
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203",
                "00000000000000000000000000000204"
              ],
              "expectedAfterRuntime": [
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203",
                "00000000000000000000000000000204"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "franchise map: every box set of the snapshot is a candidate, not only the ones named in sources (the films map stays empty)",
              "note": "Both collections have box set names (Set 4a1 Collection, Set 4b2 Collection). No collection is a source, so the version 2 films map is empty; the franchise map of curated still groups the films.",
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "000000000000000000000000000004a1",
                  "name": "Set 4a1 Collection",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "000000000000000000000000000004b2",
                  "name": "Set 4b2 Collection",
                  "movies": [
                    "00000000000000000000000000000103"
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
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104"
              ],
              "films": {},
              "franchises": {
                "00000000000000000000000000000101": "000000000000000000000000000004a1",
                "00000000000000000000000000000102": "000000000000000000000000000004a1",
                "00000000000000000000000000000103": "000000000000000000000000000004b2"
              }
            },
            {
              "name": "franchise map: a film in several box sets maps to the smallest collection ID, named in sources or not",
              "note": "All three collections have box set names. The films map (collections named in sources only) gives ...04f2 to both films; the franchise map takes the smallest ID among all the box sets each film is in.",
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "000000000000000000000000000004f2",
                  "name": "Set 4f2 Collection",
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
                  "name": "Set 4a1 Collection",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "000000000000000000000000000004c3",
                  "name": "Set 4c3 Collection",
                  "movies": [
                    "00000000000000000000000000000102",
                    "00000000000000000000000000000103"
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
                  "id": "000000000000000000000000000004f2"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103"
              ],
              "films": {
                "00000000000000000000000000000101": "000000000000000000000000000004f2",
                "00000000000000000000000000000102": "000000000000000000000000000004f2"
              },
              "franchises": {
                "00000000000000000000000000000101": "000000000000000000000000000004a1",
                "00000000000000000000000000000102": "000000000000000000000000000004c3",
                "00000000000000000000000000000103": "000000000000000000000000000004c3"
              }
            },
            {
              "name": "franchise map: the smallest ID is by ASCII bytes (a digit before a letter)",
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "a0000000000000000000000000000000",
                  "name": "Set A Collection",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "9fffffffffffffffffffffffffffffff",
                  "name": "Set Nine Collection",
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
                "00000000000000000000000000000101"
              ],
              "films": {},
              "franchises": {
                "00000000000000000000000000000101": "9fffffffffffffffffffffffffffffff"
              }
            },
            {
              "name": "franchise map: episodes are never mapped (member episodes, member series, member seasons); a collection of shows maps nothing",
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
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000201",
                  "kind": "episode",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": "00000000000000000000000000000301",
                  "seasonId": "00000000000000000000000000000351",
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                }
              ],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "name": "Set 401 Collection",
                  "movies": [],
                  "episodes": [
                    "00000000000000000000000000000203"
                  ],
                  "series": [
                    "00000000000000000000000000000302"
                  ],
                  "seasons": [
                    "00000000000000000000000000000351"
                  ]
                },
                {
                  "id": "00000000000000000000000000000402",
                  "name": "Set 402 Collection",
                  "movies": [
                    "00000000000000000000000000000101"
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
                  "type": "library",
                  "id": "00000000000000000000000000001001"
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203"
              ],
              "films": {},
              "franchises": {
                "00000000000000000000000000000101": "00000000000000000000000000000402"
              }
            },
            {
              "name": "franchise map: only films of the pool are mapped (excluded, filtered out or over the ceiling: not in it)",
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000105",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": 1995,
                  "officialRating": "PG",
                  "genres": [
                    "Horror"
                  ],
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "name": "Set 401 Collection",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000102",
                    "00000000000000000000000000000103",
                    "00000000000000000000000000000104",
                    "00000000000000000000000000000105"
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
                  "type": "item",
                  "id": "00000000000000000000000000000102"
                }
              ],
              "maxRating": "PG-13",
              "filters": {
                "years": {
                  "from": 1990,
                  "to": 1999
                },
                "notGenres": [
                  "Horror"
                ]
              },
              "expected": [
                "00000000000000000000000000000101"
              ],
              "films": {},
              "franchises": {
                "00000000000000000000000000000101": "00000000000000000000000000000401"
              }
            },
            {
              "name": "franchise map: a box set that is not in the snapshot maps nothing; a box set that is only an exclusion is a candidate but its films are out of the pool",
              "note": "Collection ...04ff is a source but not in the snapshot: it selects and groups nothing. Collection ...0402 is an exclusion: it removes ...0102, so that film is not in the pool and not in the map.",
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "name": "Set 401 Collection",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000402",
                  "name": "Set 402 Collection",
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
                  "id": "000000000000000000000000000004ff"
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
              "films": {},
              "franchises": {
                "00000000000000000000000000000101": "00000000000000000000000000000401"
              }
            },
            {
              "name": "franchise map: a named box set groups its films",
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "name": "Space Saga Collection",
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
                }
              ],
              "exclusions": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103"
              ],
              "films": {},
              "franchises": {
                "00000000000000000000000000000101": "00000000000000000000000000000401",
                "00000000000000000000000000000102": "00000000000000000000000000000401"
              }
            },
            {
              "name": "franchise map: an unnamed collection, an empty name and household names group nothing",
              "note": "...0401 has no name field; ...0402's name is empty; the other three are household lists. None is a box set name, so no film is mapped.",
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                },
                {
                  "id": "00000000000000000000000000000402",
                  "name": "",
                  "movies": [
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000403",
                  "name": "Favourites",
                  "movies": [
                    "00000000000000000000000000000103"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000404",
                  "name": "Weekend Watch List",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000405",
                  "name": "Kids Picks",
                  "movies": [
                    "00000000000000000000000000000105"
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
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105"
              ],
              "films": {},
              "franchises": {}
            },
            {
              "name": "franchise map: a film in a box set and in a household list is grouped by the box set, even when the list's ID is smaller",
              "note": "The household list ...0401 (Movie Night) is a source, so the version 2 films map uses it; it is not a box set name, so the franchise map passes over it and maps ...0101 and ...0102 to the box set ...04a2. ...0103 is only in the list: not mapped.",
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "name": "Movie Night",
                  "movies": [
                    "00000000000000000000000000000101",
                    "00000000000000000000000000000102",
                    "00000000000000000000000000000103"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "000000000000000000000000000004a2",
                  "name": "Harbour Tales Collection",
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
                "00000000000000000000000000000102",
                "00000000000000000000000000000103"
              ],
              "films": {
                "00000000000000000000000000000101": "00000000000000000000000000000401",
                "00000000000000000000000000000102": "00000000000000000000000000000401",
                "00000000000000000000000000000103": "00000000000000000000000000000401"
              },
              "franchises": {
                "00000000000000000000000000000101": "000000000000000000000000000004a2",
                "00000000000000000000000000000102": "000000000000000000000000000004a2"
              }
            },
            {
              "name": "franchise map: the \" collection\" suffix under the clean step and the ASCII fold (case, whitespace and fold edges)",
              "note": "Each film is in one collection of its own. ...401 \"Box Set 1 Collection\": box set (the plain form); ...402 \"box set 1 COLLECTION \": box set (ASCII letters fold; the trailing space is trimmed); ...403 \"Collection\": not a box set (no space before collection); ...404 \" Collection\": not a box set (the leading space is trimmed, so nothing comes before collection); ...405 \"Box Set Collections\": not a box set (does not end with collection); ...406 \"Box Set Collection 2\": not a box set (something after collection); ...407 \"Box Set\\u00a0Collection\": not a box set (a no-break space is not U+0020); ...408 \"Box Set\\tCollection\": box set (a tab is written as a space by the clean step); ...409 \"Box Set\\u2028Collection\": box set (U+2028 is written as a space); ...40a \"Box Set  Collection\": box set (two spaces: it still ends with a space and collection); ...40b \"Box Set Collection\\u200b\\u00a0\": box set (the zero-width space is dropped and the no-break space trimmed); ...40c \"Box Set Coll\\u00adection\": box set (the soft hyphen is dropped); ...40d \"Box Set COLLECT\\u0130ON\": not a box set (U+0130 does not fold); ...40e \"Box Set Collecti\\u00f6n\": not a box set (o with diaeresis is not o); ...40f \"Box Set \\u200bCollection\": box set (the zero-width space after the space is dropped); ...410 \"Box Set\\u200b Collection\": box set (a dropped code point before the space); ...411 \"Box Set\\u3000Collection\": not a box set (U+3000 inside the name is not U+0020); ...412 \"BOX SET COLLECTION\\t\": box set (the trailing tab becomes a space and is trimmed).",
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000107",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000108",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000109",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "0000000000000000000000000000010a",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "0000000000000000000000000000010b",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "0000000000000000000000000000010c",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "0000000000000000000000000000010d",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "0000000000000000000000000000010e",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "0000000000000000000000000000010f",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000110",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000111",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                },
                {
                  "id": "00000000000000000000000000000112",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [],
              "collections": [
                {
                  "id": "00000000000000000000000000000401",
                  "name": "Box Set 1 Collection",
                  "movies": [
                    "00000000000000000000000000000101"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000402",
                  "name": "box set 1 COLLECTION ",
                  "movies": [
                    "00000000000000000000000000000102"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000403",
                  "name": "Collection",
                  "movies": [
                    "00000000000000000000000000000103"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000404",
                  "name": " Collection",
                  "movies": [
                    "00000000000000000000000000000104"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000405",
                  "name": "Box Set Collections",
                  "movies": [
                    "00000000000000000000000000000105"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000406",
                  "name": "Box Set Collection 2",
                  "movies": [
                    "00000000000000000000000000000106"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000407",
                  "name": "Box Set\u00a0Collection",
                  "movies": [
                    "00000000000000000000000000000107"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000408",
                  "name": "Box Set\tCollection",
                  "movies": [
                    "00000000000000000000000000000108"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000409",
                  "name": "Box Set\u2028Collection",
                  "movies": [
                    "00000000000000000000000000000109"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "0000000000000000000000000000040a",
                  "name": "Box Set  Collection",
                  "movies": [
                    "0000000000000000000000000000010a"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "0000000000000000000000000000040b",
                  "name": "Box Set Collection\u200b\u00a0",
                  "movies": [
                    "0000000000000000000000000000010b"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "0000000000000000000000000000040c",
                  "name": "Box Set Coll\u00adection",
                  "movies": [
                    "0000000000000000000000000000010c"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "0000000000000000000000000000040d",
                  "name": "Box Set COLLECT\u0130ON",
                  "movies": [
                    "0000000000000000000000000000010d"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "0000000000000000000000000000040e",
                  "name": "Box Set Collecti\u00f6n",
                  "movies": [
                    "0000000000000000000000000000010e"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "0000000000000000000000000000040f",
                  "name": "Box Set \u200bCollection",
                  "movies": [
                    "0000000000000000000000000000010f"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000410",
                  "name": "Box Set\u200b Collection",
                  "movies": [
                    "00000000000000000000000000000110"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000411",
                  "name": "Box Set\u3000Collection",
                  "movies": [
                    "00000000000000000000000000000111"
                  ],
                  "episodes": [],
                  "series": [],
                  "seasons": []
                },
                {
                  "id": "00000000000000000000000000000412",
                  "name": "BOX SET COLLECTION\t",
                  "movies": [
                    "00000000000000000000000000000112"
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
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000106",
                "00000000000000000000000000000107",
                "00000000000000000000000000000108",
                "00000000000000000000000000000109",
                "0000000000000000000000000000010a",
                "0000000000000000000000000000010b",
                "0000000000000000000000000000010c",
                "0000000000000000000000000000010d",
                "0000000000000000000000000000010e",
                "0000000000000000000000000000010f",
                "00000000000000000000000000000110",
                "00000000000000000000000000000111",
                "00000000000000000000000000000112"
              ],
              "films": {},
              "franchises": {
                "00000000000000000000000000000101": "00000000000000000000000000000401",
                "00000000000000000000000000000102": "00000000000000000000000000000402",
                "00000000000000000000000000000108": "00000000000000000000000000000408",
                "00000000000000000000000000000109": "00000000000000000000000000000409",
                "0000000000000000000000000000010a": "0000000000000000000000000000040a",
                "0000000000000000000000000000010b": "0000000000000000000000000000040b",
                "0000000000000000000000000000010c": "0000000000000000000000000000040c",
                "0000000000000000000000000000010f": "0000000000000000000000000000040f",
                "00000000000000000000000000000110": "00000000000000000000000000000410",
                "00000000000000000000000000000112": "00000000000000000000000000000412"
              }
            }
          ]
        }
        """.trimIndent() + "\n"

    val poolV4: String =
        """
        {
          "description": "The version 4 pool (docs/adr/0026-studio-fold-2.md), in the pool-v3.json case shape (see its description), for studio sources, studio exclusions and the studios filter only. 'expected' is the pool of the case's rules in a file that says formatVersion 4: names match by their studio fold 2 key (see studio-fold-2.json); a studio source or a studios filter name whose key is empty matches nothing; a studio exclusion whose key is empty matches every item that has, or whose series has, a studio whose key is empty. 'expectedV3' is the pool of the same rules in a file that says formatVersion 3: studio fold 1 (docs/rules-format.md, Studio names), exactly as pool-v3.json. There is no films or franchises map here. Whitespace, control and format characters are written as JSON escapes so that they can be seen; this file is all ASCII.",
          "source": "Computed with an independent Python reference (scripts/rules-format/studio_fold_2_vectors.py) written from ADR-0026 alone; it checks list 2 of D against Unicode 15.1.0 DerivedCoreProperties.txt (Default_Ignorable_Code_Point, less U+200C and U+200D) before writing.",
          "cases": [
            {
              "name": "studio source: look-alikes of the name match under fold 2 (4), only the fold 1 twins under fold 1 (3)",
              "note": "One movie per spelling, in this order: \"Walt Disney\", \"WALT DISNEY\", \"Walt\\u00a0Disney\", \"Walt\\u2009Disney\", \"Walt\\u3000Disney\", \"Walt  Disney\", \"WaltDisney\", \"Walt\\u3164Disney\", \"Walt\\u115fDisney\", \"Walt\\u2800Disney\", \"Walt\\u000bDisney\", \"Walt\\u0085Disney\", \"Walt\\u2009\\u200dDisney\", \"Walt Dis\\u200dney\", \"Walt Dis\\u200c\\u200dney\", \"Walt Dis\\udb40\\udc02ney\", \"Walt Dis\\u034fney\", \"Walt Disne\\ufe0fy\", \"\\u3000\\u200dWalt Disney\", \"Walt Disney\\u200b\", \"Pixar\", \"Walt Disnay\". Only the last two are other studios.",
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
                  "tags": [],
                  "studios": [
                    "Walt Disney"
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
                  "tags": [],
                  "studios": [
                    "WALT DISNEY"
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
                  "tags": [],
                  "studios": [
                    "Walt\u00a0Disney"
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
                  "tags": [],
                  "studios": [
                    "Walt\u2009Disney"
                  ]
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
                  "tags": [],
                  "studios": [
                    "Walt\u3000Disney"
                  ]
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
                  "tags": [],
                  "studios": [
                    "Walt  Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000107",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "WaltDisney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000108",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u3164Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000109",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u115fDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010a",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u2800Disney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010b",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u000bDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010c",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u0085Disney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010d",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u2009\u200dDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010e",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u200dney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010f",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u200c\u200dney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000110",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\udb40\udc02ney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000111",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u034fney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000112",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disne\ufe0fy"
                  ]
                },
                {
                  "id": "00000000000000000000000000000113",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "\u3000\u200dWalt Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000114",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disney\u200b"
                  ]
                },
                {
                  "id": "00000000000000000000000000000115",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Pixar"
                  ]
                },
                {
                  "id": "00000000000000000000000000000116",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disnay"
                  ]
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": "Walt Disney"
                }
              ],
              "exclusions": [],
              "expectedV3": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000114"
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000106",
                "00000000000000000000000000000107",
                "00000000000000000000000000000108",
                "00000000000000000000000000000109",
                "0000000000000000000000000000010a",
                "0000000000000000000000000000010b",
                "0000000000000000000000000000010c",
                "0000000000000000000000000000010d",
                "0000000000000000000000000000010e",
                "0000000000000000000000000000010f",
                "00000000000000000000000000000110",
                "00000000000000000000000000000111",
                "00000000000000000000000000000112",
                "00000000000000000000000000000113",
                "00000000000000000000000000000114"
              ]
            },
            {
              "name": "studio exclusion: look-alikes are excluded under fold 2 (4), only the fold 1 twins under fold 1 (3)",
              "note": "One movie per spelling, in this order: \"Walt Disney\", \"WALT DISNEY\", \"Walt\\u00a0Disney\", \"Walt\\u2009Disney\", \"Walt\\u3000Disney\", \"Walt  Disney\", \"WaltDisney\", \"Walt\\u3164Disney\", \"Walt\\u115fDisney\", \"Walt\\u2800Disney\", \"Walt\\u000bDisney\", \"Walt\\u0085Disney\", \"Walt\\u2009\\u200dDisney\", \"Walt Dis\\u200dney\", \"Walt Dis\\u200c\\u200dney\", \"Walt Dis\\udb40\\udc02ney\", \"Walt Dis\\u034fney\", \"Walt Disne\\ufe0fy\", \"\\u3000\\u200dWalt Disney\", \"Walt Disney\\u200b\", \"Pixar\", \"Walt Disnay\". Only the last two are other studios.",
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
                  "tags": [],
                  "studios": [
                    "Walt Disney"
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
                  "tags": [],
                  "studios": [
                    "WALT DISNEY"
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
                  "tags": [],
                  "studios": [
                    "Walt\u00a0Disney"
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
                  "tags": [],
                  "studios": [
                    "Walt\u2009Disney"
                  ]
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
                  "tags": [],
                  "studios": [
                    "Walt\u3000Disney"
                  ]
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
                  "tags": [],
                  "studios": [
                    "Walt  Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000107",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "WaltDisney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000108",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u3164Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000109",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u115fDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010a",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u2800Disney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010b",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u000bDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010c",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u0085Disney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010d",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u2009\u200dDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010e",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u200dney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010f",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u200c\u200dney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000110",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\udb40\udc02ney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000111",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u034fney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000112",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disne\ufe0fy"
                  ]
                },
                {
                  "id": "00000000000000000000000000000113",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "\u3000\u200dWalt Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000114",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disney\u200b"
                  ]
                },
                {
                  "id": "00000000000000000000000000000115",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Pixar"
                  ]
                },
                {
                  "id": "00000000000000000000000000000116",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disnay"
                  ]
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
                  "type": "studio",
                  "name": "Walt Disney"
                }
              ],
              "expectedV3": [
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000106",
                "00000000000000000000000000000107",
                "00000000000000000000000000000108",
                "00000000000000000000000000000109",
                "0000000000000000000000000000010a",
                "0000000000000000000000000000010b",
                "0000000000000000000000000000010c",
                "0000000000000000000000000000010d",
                "0000000000000000000000000000010e",
                "0000000000000000000000000000010f",
                "00000000000000000000000000000110",
                "00000000000000000000000000000111",
                "00000000000000000000000000000112",
                "00000000000000000000000000000113",
                "00000000000000000000000000000115",
                "00000000000000000000000000000116"
              ],
              "expected": [
                "00000000000000000000000000000115",
                "00000000000000000000000000000116"
              ]
            },
            {
              "name": "filters studios: look-alikes pass under fold 2 (4), only the fold 1 twins under fold 1 (3)",
              "note": "One movie per spelling, in this order: \"Walt Disney\", \"WALT DISNEY\", \"Walt\\u00a0Disney\", \"Walt\\u2009Disney\", \"Walt\\u3000Disney\", \"Walt  Disney\", \"WaltDisney\", \"Walt\\u3164Disney\", \"Walt\\u115fDisney\", \"Walt\\u2800Disney\", \"Walt\\u000bDisney\", \"Walt\\u0085Disney\", \"Walt\\u2009\\u200dDisney\", \"Walt Dis\\u200dney\", \"Walt Dis\\u200c\\u200dney\", \"Walt Dis\\udb40\\udc02ney\", \"Walt Dis\\u034fney\", \"Walt Disne\\ufe0fy\", \"\\u3000\\u200dWalt Disney\", \"Walt Disney\\u200b\", \"Pixar\", \"Walt Disnay\". Only the last two are other studios.",
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
                  "tags": [],
                  "studios": [
                    "Walt Disney"
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
                  "tags": [],
                  "studios": [
                    "WALT DISNEY"
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
                  "tags": [],
                  "studios": [
                    "Walt\u00a0Disney"
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
                  "tags": [],
                  "studios": [
                    "Walt\u2009Disney"
                  ]
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
                  "tags": [],
                  "studios": [
                    "Walt\u3000Disney"
                  ]
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
                  "tags": [],
                  "studios": [
                    "Walt  Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000107",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "WaltDisney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000108",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u3164Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000109",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u115fDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010a",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u2800Disney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010b",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u000bDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010c",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u0085Disney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010d",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u2009\u200dDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010e",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u200dney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010f",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u200c\u200dney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000110",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\udb40\udc02ney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000111",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u034fney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000112",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disne\ufe0fy"
                  ]
                },
                {
                  "id": "00000000000000000000000000000113",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "\u3000\u200dWalt Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000114",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disney\u200b"
                  ]
                },
                {
                  "id": "00000000000000000000000000000115",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Pixar"
                  ]
                },
                {
                  "id": "00000000000000000000000000000116",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disnay"
                  ]
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
                "studios": [
                  "walt disney"
                ]
              },
              "expectedV3": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000114"
              ],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000106",
                "00000000000000000000000000000107",
                "00000000000000000000000000000108",
                "00000000000000000000000000000109",
                "0000000000000000000000000000010a",
                "0000000000000000000000000000010b",
                "0000000000000000000000000000010c",
                "0000000000000000000000000000010d",
                "0000000000000000000000000000010e",
                "0000000000000000000000000000010f",
                "00000000000000000000000000000110",
                "00000000000000000000000000000111",
                "00000000000000000000000000000112",
                "00000000000000000000000000000113",
                "00000000000000000000000000000114"
              ]
            },
            {
              "name": "studio source: the name in the rules is folded the same way (U+3000 and a joiner in it)",
              "note": "One movie per spelling, in this order: \"Walt Disney\", \"WALT DISNEY\", \"Walt\\u00a0Disney\", \"Walt\\u2009Disney\", \"Walt\\u3000Disney\", \"Walt  Disney\", \"WaltDisney\", \"Walt\\u3164Disney\", \"Walt\\u115fDisney\", \"Walt\\u2800Disney\", \"Walt\\u000bDisney\", \"Walt\\u0085Disney\", \"Walt\\u2009\\u200dDisney\", \"Walt Dis\\u200dney\", \"Walt Dis\\u200c\\u200dney\", \"Walt Dis\\udb40\\udc02ney\", \"Walt Dis\\u034fney\", \"Walt Disne\\ufe0fy\", \"\\u3000\\u200dWalt Disney\", \"Walt Disney\\u200b\", \"Pixar\", \"Walt Disnay\". Only the last two are other studios.",
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
                  "tags": [],
                  "studios": [
                    "Walt Disney"
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
                  "tags": [],
                  "studios": [
                    "WALT DISNEY"
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
                  "tags": [],
                  "studios": [
                    "Walt\u00a0Disney"
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
                  "tags": [],
                  "studios": [
                    "Walt\u2009Disney"
                  ]
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
                  "tags": [],
                  "studios": [
                    "Walt\u3000Disney"
                  ]
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
                  "tags": [],
                  "studios": [
                    "Walt  Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000107",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "WaltDisney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000108",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u3164Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000109",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u115fDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010a",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u2800Disney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010b",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u000bDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010c",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u0085Disney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010d",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u2009\u200dDisney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010e",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u200dney"
                  ]
                },
                {
                  "id": "0000000000000000000000000000010f",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u200c\u200dney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000110",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\udb40\udc02ney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000111",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Dis\u034fney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000112",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disne\ufe0fy"
                  ]
                },
                {
                  "id": "00000000000000000000000000000113",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "\u3000\u200dWalt Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000114",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disney\u200b"
                  ]
                },
                {
                  "id": "00000000000000000000000000000115",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Pixar"
                  ]
                },
                {
                  "id": "00000000000000000000000000000116",
                  "kind": "movie",
                  "libraryId": "00000000000000000000000000001001",
                  "seriesId": null,
                  "seasonId": null,
                  "productionYear": null,
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt Disnay"
                  ]
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": " Walt\u3000Dis\u200dney\u00a0"
                }
              ],
              "exclusions": [],
              "expectedV3": [],
              "expected": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000106",
                "00000000000000000000000000000107",
                "00000000000000000000000000000108",
                "00000000000000000000000000000109",
                "0000000000000000000000000000010a",
                "0000000000000000000000000000010b",
                "0000000000000000000000000000010c",
                "0000000000000000000000000000010d",
                "0000000000000000000000000000010e",
                "0000000000000000000000000000010f",
                "00000000000000000000000000000110",
                "00000000000000000000000000000111",
                "00000000000000000000000000000112",
                "00000000000000000000000000000113",
                "00000000000000000000000000000114"
              ]
            },
            {
              "name": "studio exclusion under fold 2: an episode takes its series' studios; a missing series uses the episode's own",
              "note": "...0201's series has a look-alike; ...0203 has one itself; ...0204's series is not in the snapshot.",
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": [
                    "Walt\u3000Disney"
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Walt\u00a0Disney"
                  ]
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Pixar"
                  ]
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
                  "type": "studio",
                  "name": "Walt Disney"
                }
              ],
              "expectedV3": [
                "00000000000000000000000000000201",
                "00000000000000000000000000000202",
                "00000000000000000000000000000203",
                "00000000000000000000000000000204"
              ],
              "expected": [
                "00000000000000000000000000000202",
                "00000000000000000000000000000204"
              ]
            },
            {
              "name": "the empty-key rule: under fold 2 an exclusion whose key is empty excludes every item with a studio whose key is empty (own or series'); under fold 1 it matches only its own fold 1 key",
              "note": "...0101 has the one studio U+2800 U+3164, ...0102 has Pixar and U+3164, ...0105 has U+115F, the series of ...0201 has U+FFA0: every one a studio whose fold 2 key is empty.",
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
                  "tags": [],
                  "studios": [
                    "\u2800\u3164"
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
                  "tags": [],
                  "studios": [
                    "Pixar",
                    "\u3164"
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
                  "tags": [],
                  "studios": [
                    "Pixar"
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": [
                    "\u115f"
                  ]
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "\uffa0"
                  ]
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Pixar"
                  ]
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
                  "type": "studio",
                  "name": "\u3164"
                }
              ],
              "expectedV3": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000105",
                "00000000000000000000000000000201",
                "00000000000000000000000000000202"
              ],
              "expected": [
                "00000000000000000000000000000103",
                "00000000000000000000000000000104",
                "00000000000000000000000000000202"
              ]
            },
            {
              "name": "the empty-key rule: a studio source whose fold 2 key is empty matches nothing",
              "note": "...0101 has the one studio U+2800 U+3164, ...0102 has Pixar and U+3164, ...0105 has U+115F, the series of ...0201 has U+FFA0: every one a studio whose fold 2 key is empty.",
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
                  "tags": [],
                  "studios": [
                    "\u2800\u3164"
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
                  "tags": [],
                  "studios": [
                    "Pixar",
                    "\u3164"
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
                  "tags": [],
                  "studios": [
                    "Pixar"
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": [
                    "\u115f"
                  ]
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "\uffa0"
                  ]
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Pixar"
                  ]
                }
              ],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": "\u3164"
                }
              ],
              "exclusions": [],
              "expectedV3": [
                "00000000000000000000000000000102"
              ],
              "expected": []
            },
            {
              "name": "the empty-key rule: a studios filter name whose fold 2 key is empty matches nothing",
              "note": "...0101 has the one studio U+2800 U+3164, ...0102 has Pixar and U+3164, ...0105 has U+115F, the series of ...0201 has U+FFA0: every one a studio whose fold 2 key is empty.",
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
                  "tags": [],
                  "studios": [
                    "\u2800\u3164"
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
                  "tags": [],
                  "studios": [
                    "Pixar",
                    "\u3164"
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
                  "tags": [],
                  "studios": [
                    "Pixar"
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": [
                    "\u115f"
                  ]
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
                  "tags": [],
                  "studios": []
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
                  "tags": [],
                  "studios": []
                }
              ],
              "series": [
                {
                  "id": "00000000000000000000000000000301",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "\uffa0"
                  ]
                },
                {
                  "id": "00000000000000000000000000000302",
                  "officialRating": null,
                  "genres": [],
                  "tags": [],
                  "studios": [
                    "Pixar"
                  ]
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
                "studios": [
                  "\u3164"
                ]
              },
              "expectedV3": [
                "00000000000000000000000000000102"
              ],
              "expected": []
            },
            {
              "name": "joiners: removed after a gap even after a non-Latin letter; kept between a non-Latin letter and a Latin one (a known residual gap)",
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
                  "tags": [],
                  "studios": [
                    "\u6771\u6620\u3000\u200dAnimation"
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
                  "tags": [],
                  "studios": [
                    "\u6771\u6620\u200dAnimation"
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
                  "tags": [],
                  "studios": [
                    "\u0915\u094d\u200d\u0937"
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
                  "tags": [],
                  "studios": [
                    "\u0915\u094d\u0937"
                  ]
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
                  "type": "studio",
                  "name": "\u6771\u6620 Animation"
                }
              ],
              "expectedV3": [
                "00000000000000000000000000000101",
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104"
              ],
              "expected": [
                "00000000000000000000000000000102",
                "00000000000000000000000000000103",
                "00000000000000000000000000000104"
              ]
            },
            {
              "name": "joiners: kept inside a non-Latin word, so a Devanagari conjunct and its plain form stay apart",
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
                  "tags": [],
                  "studios": [
                    "\u6771\u6620\u3000\u200dAnimation"
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
                  "tags": [],
                  "studios": [
                    "\u6771\u6620\u200dAnimation"
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
                  "tags": [],
                  "studios": [
                    "\u0915\u094d\u200d\u0937"
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
                  "tags": [],
                  "studios": [
                    "\u0915\u094d\u0937"
                  ]
                }
              ],
              "series": [],
              "collections": [],
              "sources": [
                {
                  "type": "studio",
                  "name": "\u0915\u094d\u200d\u0937"
                }
              ],
              "exclusions": [],
              "expectedV3": [
                "00000000000000000000000000000103"
              ],
              "expected": [
                "00000000000000000000000000000103"
              ]
            }
          ]
        }
        """.trimIndent() + "\n"
}
