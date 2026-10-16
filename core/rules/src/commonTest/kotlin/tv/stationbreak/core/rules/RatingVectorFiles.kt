// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * Kotlin copies of `docs/rules-format/vectors/rating.json` and `auto-seed.json` (`RatingVectorsInSyncTest` fails if
 * they differ from the files).
 */
@Suppress("LargeClass") // Data only: one entry per vector file.
internal object RatingVectorFiles {
    val rating: String =
        """
        {
          "description": "Rating ladder and fail-closed filter (docs/rules-format.md, 'Maximum rating and the rating ladder'). normalise: trim U+0020 from both ends, upper-case ASCII a-z only, look the result up on the ladder (Approved is the ladder name Approved, rank 0, the same tier as G); otherwise a TV-PG-, TV-14- or TV-MA- rating followed by one descriptor group counts as its base rating, where the group is a non-empty run of D, L, S, V in that order, each at most once (TV-MA: L, S, V only). 'normalised' is the ladder spelling or null; 'rank' is 0 to 7 or null (no rank: blocked under any ceiling). Whitespace, control and format characters are written as JSON escapes (such as \\u00a0 for a no-break space) so that they can be seen; this file is all ASCII.",
          "source": "Expected values come from an independent Python reference that reads Jellyfin v12.1 Emby.Server.Implementations/Localization/Ratings/us.json: every Jellyfin US string appears in 'normalise' with the tier Jellyfin gives it, and Jellyfin's score maps to our rank (0->0, 7->1, 10->2, 13->3, 14->4, 17 sub-score 0->5, 17 sub-score 1->6, 18->7). Strings Jellyfin resolves by other means (country prefixes, 'Rated ', plain numbers, '/' lists) have no rank here on purpose: the filter fails closed.",
          "ladder": [
            {
              "rank": 0,
              "ratings": [
                "TV-Y",
                "TV-G",
                "G",
                "Approved"
              ]
            },
            {
              "rank": 1,
              "ratings": [
                "TV-Y7",
                "TV-Y7-FV"
              ]
            },
            {
              "rank": 2,
              "ratings": [
                "TV-PG",
                "PG"
              ]
            },
            {
              "rank": 3,
              "ratings": [
                "PG-13"
              ]
            },
            {
              "rank": 4,
              "ratings": [
                "TV-14"
              ]
            },
            {
              "rank": 5,
              "ratings": [
                "R"
              ]
            },
            {
              "rank": 6,
              "ratings": [
                "TV-MA",
                "NC-17"
              ]
            },
            {
              "rank": 7,
              "ratings": [
                "TV-X",
                "TV-AO"
              ]
            }
          ],
          "normalise": [
            {
              "input": "Approved",
              "normalised": "Approved",
              "rank": 0
            },
            {
              "input": "G",
              "normalised": "G",
              "rank": 0
            },
            {
              "input": "TV-G",
              "normalised": "TV-G",
              "rank": 0
            },
            {
              "input": "TV-Y",
              "normalised": "TV-Y",
              "rank": 0
            },
            {
              "input": "TV-Y7",
              "normalised": "TV-Y7",
              "rank": 1
            },
            {
              "input": "TV-Y7-FV",
              "normalised": "TV-Y7-FV",
              "rank": 1
            },
            {
              "input": "PG",
              "normalised": "PG",
              "rank": 2
            },
            {
              "input": "TV-PG",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-D",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-L",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-S",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-V",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-DL",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-DS",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-DV",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-LS",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-LV",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-SV",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-DLS",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-DLV",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-DSV",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-LSV",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "TV-PG-DLSV",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "PG-13",
              "normalised": "PG-13",
              "rank": 3
            },
            {
              "input": "TV-14",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-D",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-L",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-S",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-V",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-DL",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-DS",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-DV",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-LS",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-LV",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-SV",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-DLS",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-DLV",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-DSV",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-LSV",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "TV-14-DLSV",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "R",
              "normalised": "R",
              "rank": 5
            },
            {
              "input": "NC-17",
              "normalised": "NC-17",
              "rank": 6
            },
            {
              "input": "TV-MA",
              "normalised": "TV-MA",
              "rank": 6
            },
            {
              "input": "TV-MA-L",
              "normalised": "TV-MA",
              "rank": 6
            },
            {
              "input": "TV-MA-S",
              "normalised": "TV-MA",
              "rank": 6
            },
            {
              "input": "TV-MA-V",
              "normalised": "TV-MA",
              "rank": 6
            },
            {
              "input": "TV-MA-LS",
              "normalised": "TV-MA",
              "rank": 6
            },
            {
              "input": "TV-MA-LV",
              "normalised": "TV-MA",
              "rank": 6
            },
            {
              "input": "TV-MA-SV",
              "normalised": "TV-MA",
              "rank": 6
            },
            {
              "input": "TV-MA-LSV",
              "normalised": "TV-MA",
              "rank": 6
            },
            {
              "input": "TV-X",
              "normalised": "TV-X",
              "rank": 7
            },
            {
              "input": "TV-AO",
              "normalised": "TV-AO",
              "rank": 7
            },
            {
              "input": "tv-14-v",
              "normalised": "TV-14",
              "rank": 4
            },
            {
              "input": "Tv-Pg-DlSv",
              "normalised": "TV-PG",
              "rank": 2
            },
            {
              "input": "approved",
              "normalised": "Approved",
              "rank": 0
            },
            {
              "input": "APPROVED",
              "normalised": "Approved",
              "rank": 0
            },
            {
              "input": "pg-13",
              "normalised": "PG-13",
              "rank": 3
            },
            {
              "input": "nc-17",
              "normalised": "NC-17",
              "rank": 6
            },
            {
              "input": "  PG  ",
              "normalised": "PG",
              "rank": 2
            },
            {
              "input": " TV-MA-LSV ",
              "normalised": "TV-MA",
              "rank": 6
            },
            {
              "input": "tv-y7-fv",
              "normalised": "TV-Y7-FV",
              "rank": 1
            },
            {
              "input": "NR",
              "normalised": null,
              "rank": null
            },
            {
              "input": "Unrated",
              "normalised": null,
              "rank": null
            },
            {
              "input": "Not Rated",
              "normalised": null,
              "rank": null
            },
            {
              "input": "N/A",
              "normalised": null,
              "rank": null
            },
            {
              "input": "",
              "normalised": null,
              "rank": null
            },
            {
              "input": " ",
              "normalised": null,
              "rank": null
            },
            {
              "input": "FSK 16",
              "normalised": null,
              "rank": null
            },
            {
              "input": "Rated PG",
              "normalised": null,
              "rank": null
            },
            {
              "input": "US:PG-13",
              "normalised": null,
              "rank": null
            },
            {
              "input": "US-PG",
              "normalised": null,
              "rank": null
            },
            {
              "input": "13",
              "normalised": null,
              "rank": null
            },
            {
              "input": "16+",
              "normalised": null,
              "rank": null
            },
            {
              "input": "M",
              "normalised": null,
              "rank": null
            },
            {
              "input": "X",
              "normalised": null,
              "rank": null
            },
            {
              "input": "GP",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-14/TV-MA",
              "normalised": null,
              "rank": null
            },
            {
              "input": "PG-14",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-MA-D",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-MA-DL",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-G-V",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-Y-FV",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-Y7-V",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-Y7-FV-V",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-14-VD",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-14-VV",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-14-DLSVV",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-14-",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-14--V",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-14-X",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-14V",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-14 V",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-PG-F",
              "normalised": null,
              "rank": null
            },
            {
              "input": "TV-X-V",
              "normalised": null,
              "rank": null
            },
            {
              "input": "\tPG",
              "normalised": null,
              "rank": null
            },
            {
              "input": "PG\u00a0",
              "normalised": null,
              "rank": null
            },
            {
              "input": "\uff30\uff27",
              "normalised": null,
              "rank": null
            },
            {
              "input": "tv-14-v\n",
              "normalised": null,
              "rank": null
            }
          ],
          "eligibilityNote": "For each ceiling, ratings in 'eligible' pass and ratings in 'blocked' do not (a rank at or below the ceiling's rank passes; no rank never passes). A null rating is an item with no rating at all.",
          "eligibility": [
            {
              "maxRating": "TV-Y",
              "eligible": [
                "TV-Y",
                "G",
                "Approved"
              ],
              "blocked": [
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "TV-G",
              "eligible": [
                "TV-Y",
                "G",
                "Approved"
              ],
              "blocked": [
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "G",
              "eligible": [
                "TV-Y",
                "G",
                "Approved"
              ],
              "blocked": [
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "Approved",
              "eligible": [
                "TV-Y",
                "G",
                "Approved"
              ],
              "blocked": [
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "TV-Y7",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV"
              ],
              "blocked": [
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "TV-Y7-FV",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV"
              ],
              "blocked": [
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "TV-PG",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG"
              ],
              "blocked": [
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "PG",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG"
              ],
              "blocked": [
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "PG-13",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13"
              ],
              "blocked": [
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "TV-14",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14"
              ],
              "blocked": [
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "R",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R"
              ],
              "blocked": [
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "TV-MA",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17"
              ],
              "blocked": [
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "NC-17",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17"
              ],
              "blocked": [
                "TV-X",
                "TV-AO",
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "TV-X",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO"
              ],
              "blocked": [
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            },
            {
              "maxRating": "TV-AO",
              "eligible": [
                "TV-Y",
                "G",
                "Approved",
                "TV-Y7",
                "TV-Y7-FV",
                "tv-pg-v",
                "PG",
                "PG-13",
                "TV-14-DLSV",
                "TV-14",
                "R",
                "TV-MA-S",
                "NC-17",
                "TV-X",
                "TV-AO"
              ],
              "blocked": [
                "NR",
                "Unrated",
                "",
                "FSK 16",
                null
              ]
            }
          ],
          "inheritanceNote": "An episode whose own rating is absent (null or the empty string) uses its series' rating; any other own rating, including NR or a blank ' ', is used as it is. With maxRating null, every item is eligible.",
          "inheritance": [
            {
              "own": null,
              "series": "G",
              "maxRating": "PG",
              "eligible": true
            },
            {
              "own": "",
              "series": "G",
              "maxRating": "PG",
              "eligible": true
            },
            {
              "own": null,
              "series": "R",
              "maxRating": "PG",
              "eligible": false
            },
            {
              "own": null,
              "series": "R",
              "maxRating": "R",
              "eligible": true
            },
            {
              "own": null,
              "series": null,
              "maxRating": "PG",
              "eligible": false
            },
            {
              "own": null,
              "series": null,
              "maxRating": null,
              "eligible": true
            },
            {
              "own": "TV-14",
              "series": "G",
              "maxRating": "PG",
              "eligible": false
            },
            {
              "own": "G",
              "series": "R",
              "maxRating": "PG",
              "eligible": true
            },
            {
              "own": "NR",
              "series": "G",
              "maxRating": "PG",
              "eligible": false
            },
            {
              "own": " ",
              "series": "G",
              "maxRating": "PG",
              "eligible": false
            },
            {
              "own": "FSK 16",
              "series": "G",
              "maxRating": "TV-MA",
              "eligible": false
            },
            {
              "own": null,
              "series": "NR",
              "maxRating": "TV-MA",
              "eligible": false
            },
            {
              "own": null,
              "series": "TV-PG-V",
              "maxRating": "TV-PG",
              "eligible": true
            },
            {
              "own": null,
              "series": "TV-14-V",
              "maxRating": "TV-PG",
              "eligible": false
            },
            {
              "own": "",
              "series": "",
              "maxRating": "G",
              "eligible": false
            },
            {
              "own": "PG-13",
              "series": null,
              "maxRating": null,
              "eligible": true
            }
          ]
        }
        """.trimIndent() + "\n"

    val autoSeed: String =
        """
        {
          "description": "Automatic-channel seed: seed = splitmix64(fnv1a64(bytes)), bytes = ASCII server ID (32 lower-case hex) || 0x00 || ASCII kind (library, genre or collection) || 0x00 || key. The key is the 32 ASCII hex characters of the canonical ID for a library or a collection, and the exact UTF-8 bytes of the name for a genre, with no case folding and no Unicode normalisation (the last two genre rows are the same word in another case and in another normalisation form: different seeds). Every 64-bit value is an object: dec (unsigned decimal string), hex (16 lower-case digits, no prefix), hi and lo (its high and low 32 bits as 8 lower-case hex digits), so a port without a native 64-bit integer can check each step. Placeholder IDs only. Whitespace, control and format characters are written as JSON escapes (such as \\u00a0 for a no-break space) so that they can be seen.",
          "vectors": [
            {
              "serverId": "11111111111111111111111111111111",
              "kind": "library",
              "key": "22222222222222222222222222222222",
              "bytesHex": "3131313131313131313131313131313131313131313131313131313131313131006c696272617279003232323232323232323232323232323232323232323232323232323232323232",
              "fnv1a64": {
                "dec": "15144044620925153062",
                "hex": "d22a74589ce8af26",
                "hi": "d22a7458",
                "lo": "9ce8af26"
              },
              "seed": {
                "dec": "16427903836559823302",
                "hex": "e3fba380266cd1c6",
                "hi": "e3fba380",
                "lo": "266cd1c6"
              }
            },
            {
              "serverId": "11111111111111111111111111111111",
              "kind": "collection",
              "key": "33333333333333333333333333333333",
              "bytesHex": "313131313131313131313131313131313131313131313131313131313131313100636f6c6c656374696f6e003333333333333333333333333333333333333333333333333333333333333333",
              "fnv1a64": {
                "dec": "1538469569489516237",
                "hex": "1559bdec1b2bb6cd",
                "hi": "1559bdec",
                "lo": "1b2bb6cd"
              },
              "seed": {
                "dec": "1052978938816395171",
                "hex": "0e9ceec4141e77a3",
                "hi": "0e9ceec4",
                "lo": "141e77a3"
              }
            },
            {
              "serverId": "11111111111111111111111111111111",
              "kind": "genre",
              "key": "Animation",
              "bytesHex": "31313131313131313131313131313131313131313131313131313131313131310067656e726500416e696d6174696f6e",
              "fnv1a64": {
                "dec": "5863332115246780460",
                "hex": "515ebd7e596c902c",
                "hi": "515ebd7e",
                "lo": "596c902c"
              },
              "seed": {
                "dec": "4609163039755496289",
                "hex": "3ff7095d407c6b61",
                "hi": "3ff7095d",
                "lo": "407c6b61"
              }
            },
            {
              "serverId": "11111111111111111111111111111111",
              "kind": "genre",
              "key": "Animé",
              "bytesHex": "31313131313131313131313131313131313131313131313131313131313131310067656e726500416e696dc3a9",
              "fnv1a64": {
                "dec": "17137827712205279487",
                "hex": "edd5cb810fd750ff",
                "hi": "edd5cb81",
                "lo": "0fd750ff"
              },
              "seed": {
                "dec": "182484535837226886",
                "hex": "028850b7ef947786",
                "hi": "028850b7",
                "lo": "ef947786"
              }
            },
            {
              "serverId": "11111111111111111111111111111111",
              "kind": "genre",
              "key": "科幻",
              "bytesHex": "31313131313131313131313131313131313131313131313131313131313131310067656e726500e7a791e5b9bb",
              "fnv1a64": {
                "dec": "2952190068217095562",
                "hex": "28f84939c584258a",
                "hi": "28f84939",
                "lo": "c584258a"
              },
              "seed": {
                "dec": "12794934325292202904",
                "hex": "b190bcfa777e7b98",
                "hi": "b190bcfa",
                "lo": "777e7b98"
              }
            },
            {
              "serverId": "11111111111111111111111111111111",
              "kind": "genre",
              "key": "Crème brûlée 🎬",
              "bytesHex": "31313131313131313131313131313131313131313131313131313131313131310067656e7265004372c3a86d65206272c3bb6cc3a96520f09f8eac",
              "fnv1a64": {
                "dec": "15368215049941312294",
                "hex": "d546de29e3906326",
                "hi": "d546de29",
                "lo": "e3906326"
              },
              "seed": {
                "dec": "15785916124462239515",
                "hex": "db12d713ecaecf1b",
                "hi": "db12d713",
                "lo": "ecaecf1b"
              }
            },
            {
              "serverId": "00000000000000000000000000000002",
              "kind": "library",
              "key": "22222222222222222222222222222222",
              "bytesHex": "3030303030303030303030303030303030303030303030303030303030303032006c696272617279003232323232323232323232323232323232323232323232323232323232323232",
              "fnv1a64": {
                "dec": "18304367542607524872",
                "hex": "fe062d4c9f4fb408",
                "hi": "fe062d4c",
                "lo": "9f4fb408"
              },
              "seed": {
                "dec": "9914567505754619342",
                "hex": "89979e9dea227dce",
                "hi": "89979e9d",
                "lo": "ea227dce"
              }
            },
            {
              "serverId": "00000000000000000000000000000002",
              "kind": "genre",
              "key": "Animé",
              "bytesHex": "30303030303030303030303030303030303030303030303030303030303030320067656e726500416e696dc3a9",
              "fnv1a64": {
                "dec": "11195737640476350393",
                "hex": "9b5f4011031017b9",
                "hi": "9b5f4011",
                "lo": "031017b9"
              },
              "seed": {
                "dec": "2170857415995775172",
                "hex": "1e206f51727358c4",
                "hi": "1e206f51",
                "lo": "727358c4"
              }
            },
            {
              "serverId": "11111111111111111111111111111111",
              "kind": "genre",
              "key": "animé",
              "bytesHex": "31313131313131313131313131313131313131313131313131313131313131310067656e726500616e696dc3a9",
              "fnv1a64": {
                "dec": "3164093817051254431",
                "hex": "2be91e9001598e9f",
                "hi": "2be91e90",
                "lo": "01598e9f"
              },
              "seed": {
                "dec": "5707763799810880098",
                "hex": "4f360cef558fb262",
                "hi": "4f360cef",
                "lo": "558fb262"
              }
            },
            {
              "serverId": "11111111111111111111111111111111",
              "kind": "genre",
              "key": "Animé",
              "bytesHex": "31313131313131313131313131313131313131313131313131313131313131310067656e726500416e696d65cc81",
              "fnv1a64": {
                "dec": "2605359115050890221",
                "hex": "2428184fb4ef33ed",
                "hi": "2428184f",
                "lo": "b4ef33ed"
              },
              "seed": {
                "dec": "11314700803671737275",
                "hex": "9d05e46edeae43bb",
                "hi": "9d05e46e",
                "lo": "deae43bb"
              }
            },
            {
              "serverId": "11111111111111111111111111111111",
              "kind": "genre",
              "key": "Comedy",
              "bytesHex": "31313131313131313131313131313131313131313131313131313131313131310067656e726500436f6d656479",
              "fnv1a64": {
                "dec": "10544269687352010773",
                "hex": "9254c56a2a892815",
                "hi": "9254c56a",
                "lo": "2a892815"
              },
              "seed": {
                "dec": "15434576577207684584",
                "hex": "d632a19eff074de8",
                "hi": "d632a19e",
                "lo": "ff074de8"
              }
            },
            {
              "serverId": "00000000000000000000000000000002",
              "kind": "collection",
              "key": "00000000000000000000000000000004",
              "bytesHex": "303030303030303030303030303030303030303030303030303030303030303200636f6c6c656374696f6e003030303030303030303030303030303030303030303030303030303030303034",
              "fnv1a64": {
                "dec": "3177683768797536999",
                "hex": "2c19668d37b3a2e7",
                "hi": "2c19668d",
                "lo": "37b3a2e7"
              },
              "seed": {
                "dec": "7671108791419438445",
                "hex": "6a7540cd71ee856d",
                "hi": "6a7540cd",
                "lo": "71ee856d"
              }
            }
          ],
          "invalid": [
            {
              "why": "server ID is upper case",
              "serverId": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
              "kind": "library",
              "key": "22222222222222222222222222222222"
            },
            {
              "why": "server ID is hyphenated",
              "serverId": "11111111-1111-1111-1111-111111111111",
              "kind": "library",
              "key": "22222222222222222222222222222222"
            },
            {
              "why": "server ID is too short",
              "serverId": "1111111111111111111111111111111",
              "kind": "library",
              "key": "22222222222222222222222222222222"
            },
            {
              "why": "library key is not canonical",
              "serverId": "11111111111111111111111111111111",
              "kind": "library",
              "key": "2222222222222222222222222222222G"
            },
            {
              "why": "collection key is hyphenated",
              "serverId": "11111111111111111111111111111111",
              "kind": "collection",
              "key": "33333333-3333-3333-3333-333333333333"
            },
            {
              "why": "unknown kind",
              "serverId": "11111111111111111111111111111111",
              "kind": "series",
              "key": "22222222222222222222222222222222"
            },
            {
              "why": "kind is upper case",
              "serverId": "11111111111111111111111111111111",
              "kind": "Library",
              "key": "22222222222222222222222222222222"
            },
            {
              "why": "genre name is empty",
              "serverId": "11111111111111111111111111111111",
              "kind": "genre",
              "key": ""
            }
          ]
        }
        """.trimIndent() + "\n"

    /** The copies by file name, for the in-sync test. */
    val files: Map<String, String> =
        mapOf(
            "rating.json" to rating,
            "auto-seed.json" to autoSeed,
        )
}
