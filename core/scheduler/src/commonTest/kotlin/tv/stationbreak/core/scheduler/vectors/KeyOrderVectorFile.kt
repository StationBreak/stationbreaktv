// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler.vectors

/**
 * The Kotlin copy of `docs/rules-format/vectors/key-order.json`. `VectorFilesInSyncTest` (jvmTest) fails if it differs
 * from the published file. A test in commonTest cannot read files, because linuxX64Test cannot.
 */
internal object KeyOrderVectorFile {
    val text: String =
        """
        {
          "description": "Orderings of the pool and run formation, one case per mode and pool. Every case has the pool in items (in the order given, which does not matter), blockLength, and the expected order and runs; order is the item IDs of one cycle, runs is that order cut into runs. item fields: id (canonical, 32 lower-case hex), kind (movie or episode), optional seriesId, premiere (UTC calendar date YYYY-MM-DD), year, season, episode. groupId = seriesId if present, else id. date = premiere, else the production year as YYYY-01-01 with the year zero-padded to four digits, else undated. shuffle: key = splitmix64(seedN + fnv1a64(idBytes)); sort ascending by key as unsigned 64-bit, ties by canonical ID bytes ascending. seedN is given (cases that also give seed and n have seedN = splitmix64(seed + n * gamma)). chronological: key = (undated flag, date as text, groupId bytes, season, episode, id bytes) with a missing season or episode after every present one; keys lists the parts. randomInOrder: group items by groupId; inside a group sort by the chronological key without the groupId part; group key = splitmix64(seed + fnv1a64(groupIdBytes)) with the CHANNEL seed (not seed_n), sorted ascending as unsigned, ties by group ID bytes; walk in rounds, each turn taking up to blockLength next items of one group and skipping used-up groups until all are used (one cycle); groups lists the groups in key order. Runs: for shuffle and chronological, blockLength consecutive items counted from the first item of the cycle, the last run possibly shorter; for randomInOrder, one turn is one run. Every 64-bit value is an object: dec (unsigned decimal string), hex (16 lower-case digits, no prefix), hi and lo (its high and low 32 bits as 8 lower-case hex digits).",
          "source": "Computed with an independent Python reference written from the format doc and spec 08 only, without reading any Kotlin. The tie IDs (b3b828bb3655e2a7 and bf13eaba83dea434, each followed by 0123456789abcdef) were found by a Pollard-rho search for an FNV-1a 64 collision and verified with the reference fnv1a64. The IDs are synthetic.",
          "vectors": [
            {
              "name": "shuffle, 8 items, keys with and without the high bit, signed compare would differ",
              "mode": "shuffle",
              "blockLength": 1,
              "note": "Items are listed in input order, not sorted. seedN comes from seed and a negative n.",
              "items": [
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "kind": "movie"
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "kind": "movie"
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "kind": "movie"
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "kind": "movie"
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "kind": "movie"
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "kind": "movie"
                },
                {
                  "id": "875b9307abf550055de186dcba779207",
                  "kind": "movie"
                },
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "kind": "movie"
                }
              ],
              "seed": {
                "dec": "1",
                "hex": "0000000000000001",
                "hi": "00000000",
                "lo": "00000001"
              },
              "n": "-1",
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "fnv1a64": {
                    "dec": "10937791263775970312",
                    "hex": "97cad73418bcd408",
                    "hi": "97cad734",
                    "lo": "18bcd408"
                  },
                  "key": {
                    "dec": "11908513588867654102",
                    "hex": "a5438a03db5e61d6",
                    "hi": "a5438a03",
                    "lo": "db5e61d6"
                  }
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "fnv1a64": {
                    "dec": "14085039705671131850",
                    "hex": "c3781cfc8fcce2ca",
                    "hi": "c3781cfc",
                    "lo": "8fcce2ca"
                  },
                  "key": {
                    "dec": "6893039688311782565",
                    "hex": "5fa8ff135c7e3ca5",
                    "hi": "5fa8ff13",
                    "lo": "5c7e3ca5"
                  }
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "fnv1a64": {
                    "dec": "17290834846207924714",
                    "hex": "eff562ae8e2b59ea",
                    "hi": "eff562ae",
                    "lo": "8e2b59ea"
                  },
                  "key": {
                    "dec": "6925821950981047500",
                    "hex": "601d765eadd404cc",
                    "hi": "601d765e",
                    "lo": "add404cc"
                  }
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "fnv1a64": {
                    "dec": "5234580602984906477",
                    "hex": "48a4f7530d10beed",
                    "hi": "48a4f753",
                    "lo": "0d10beed"
                  },
                  "key": {
                    "dec": "704711946996120263",
                    "hex": "09c7a3c835ad7ac7",
                    "hi": "09c7a3c8",
                    "lo": "35ad7ac7"
                  }
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "fnv1a64": {
                    "dec": "2194379552081164761",
                    "hex": "1e740093882db9d9",
                    "hi": "1e740093",
                    "lo": "882db9d9"
                  },
                  "key": {
                    "dec": "18374023990997050735",
                    "hex": "fefda57855e2656f",
                    "hi": "fefda578",
                    "lo": "55e2656f"
                  }
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "fnv1a64": {
                    "dec": "14055740565426251774",
                    "hex": "c3100592e147a7fe",
                    "hi": "c3100592",
                    "lo": "e147a7fe"
                  },
                  "key": {
                    "dec": "4635095393886066843",
                    "hex": "40532ab3f4a0d89b",
                    "hi": "40532ab3",
                    "lo": "f4a0d89b"
                  }
                },
                {
                  "id": "875b9307abf550055de186dcba779207",
                  "fnv1a64": {
                    "dec": "116129276822946599",
                    "hex": "019c92f6450e0327",
                    "hi": "019c92f6",
                    "lo": "450e0327"
                  },
                  "key": {
                    "dec": "6759848589115789304",
                    "hex": "5dcfce7a01ac0ff8",
                    "hi": "5dcfce7a",
                    "lo": "01ac0ff8"
                  }
                },
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "fnv1a64": {
                    "dec": "12268042146837344138",
                    "hex": "aa40d7556bc2af8a",
                    "hi": "aa40d755",
                    "lo": "6bc2af8a"
                  },
                  "key": {
                    "dec": "1394174070767846471",
                    "hex": "135919ee432d8447",
                    "hi": "135919ee",
                    "lo": "432d8447"
                  }
                }
              ],
              "order": [
                "aeaf52febe706064088712be8a582fca",
                "808475f02ee373631120b3d00955f032",
                "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                "875b9307abf550055de186dcba779207",
                "63033b0ca389c35abd64a5d9adefe000",
                "63cbe1e459320dd79e5651b0ef953636",
                "1d0b14e4db018fed6e73e372e2338aca",
                "50f5647d2380309d943ff9fc99de8f03"
              ],
              "runs": [
                [
                  "aeaf52febe706064088712be8a582fca"
                ],
                [
                  "808475f02ee373631120b3d00955f032"
                ],
                [
                  "c4ca37b7f8ad8aff6aa9d61435dbe63e"
                ],
                [
                  "875b9307abf550055de186dcba779207"
                ],
                [
                  "63033b0ca389c35abd64a5d9adefe000"
                ],
                [
                  "63cbe1e459320dd79e5651b0ef953636"
                ],
                [
                  "1d0b14e4db018fed6e73e372e2338aca"
                ],
                [
                  "50f5647d2380309d943ff9fc99de8f03"
                ]
              ]
            },
            {
              "name": "shuffle, same pool, seed_n = 0",
              "mode": "shuffle",
              "blockLength": 1,
              "items": [
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "kind": "movie"
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "kind": "movie"
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "kind": "movie"
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "kind": "movie"
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "kind": "movie"
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "kind": "movie"
                },
                {
                  "id": "875b9307abf550055de186dcba779207",
                  "kind": "movie"
                },
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "0",
                "hex": "0000000000000000",
                "hi": "00000000",
                "lo": "00000000"
              },
              "keys": [
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "fnv1a64": {
                    "dec": "10937791263775970312",
                    "hex": "97cad73418bcd408",
                    "hi": "97cad734",
                    "lo": "18bcd408"
                  },
                  "key": {
                    "dec": "16694883192997167948",
                    "hex": "e7b023cf8d3c434c",
                    "hi": "e7b023cf",
                    "lo": "8d3c434c"
                  }
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "fnv1a64": {
                    "dec": "14085039705671131850",
                    "hex": "c3781cfc8fcce2ca",
                    "hi": "c3781cfc",
                    "lo": "8fcce2ca"
                  },
                  "key": {
                    "dec": "9085075888094169153",
                    "hex": "7e14ac62ab51c841",
                    "hi": "7e14ac62",
                    "lo": "ab51c841"
                  }
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "fnv1a64": {
                    "dec": "17290834846207924714",
                    "hex": "eff562ae8e2b59ea",
                    "hi": "eff562ae",
                    "lo": "8e2b59ea"
                  },
                  "key": {
                    "dec": "1127716815338256576",
                    "hex": "0fa67477f978b8c0",
                    "hi": "0fa67477",
                    "lo": "f978b8c0"
                  }
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "fnv1a64": {
                    "dec": "5234580602984906477",
                    "hex": "48a4f7530d10beed",
                    "hi": "48a4f753",
                    "lo": "0d10beed"
                  },
                  "key": {
                    "dec": "13747870729323169602",
                    "hex": "beca3f96c74c4742",
                    "hi": "beca3f96",
                    "lo": "c74c4742"
                  }
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "fnv1a64": {
                    "dec": "2194379552081164761",
                    "hex": "1e740093882db9d9",
                    "hi": "1e740093",
                    "lo": "882db9d9"
                  },
                  "key": {
                    "dec": "2312812268907458940",
                    "hex": "2018c2813864f17c",
                    "hi": "2018c281",
                    "lo": "3864f17c"
                  }
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "fnv1a64": {
                    "dec": "14055740565426251774",
                    "hex": "c3100592e147a7fe",
                    "hi": "c3100592",
                    "lo": "e147a7fe"
                  },
                  "key": {
                    "dec": "8592415011613141207",
                    "hex": "773e63edb1ef68d7",
                    "hi": "773e63ed",
                    "lo": "b1ef68d7"
                  }
                },
                {
                  "id": "875b9307abf550055de186dcba779207",
                  "fnv1a64": {
                    "dec": "116129276822946599",
                    "hex": "019c92f6450e0327",
                    "hi": "019c92f6",
                    "lo": "450e0327"
                  },
                  "key": {
                    "dec": "1713540749534245200",
                    "hex": "17c7b83ba4253150",
                    "hi": "17c7b83b",
                    "lo": "a4253150"
                  }
                },
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "fnv1a64": {
                    "dec": "12268042146837344138",
                    "hex": "aa40d7556bc2af8a",
                    "hi": "aa40d755",
                    "lo": "6bc2af8a"
                  },
                  "key": {
                    "dec": "7862432552162019106",
                    "hex": "6d1cf8bfce15c722",
                    "hi": "6d1cf8bf",
                    "lo": "ce15c722"
                  }
                }
              ],
              "order": [
                "63cbe1e459320dd79e5651b0ef953636",
                "875b9307abf550055de186dcba779207",
                "50f5647d2380309d943ff9fc99de8f03",
                "808475f02ee373631120b3d00955f032",
                "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                "63033b0ca389c35abd64a5d9adefe000",
                "aeaf52febe706064088712be8a582fca",
                "1d0b14e4db018fed6e73e372e2338aca"
              ],
              "runs": [
                [
                  "63cbe1e459320dd79e5651b0ef953636"
                ],
                [
                  "875b9307abf550055de186dcba779207"
                ],
                [
                  "50f5647d2380309d943ff9fc99de8f03"
                ],
                [
                  "808475f02ee373631120b3d00955f032"
                ],
                [
                  "c4ca37b7f8ad8aff6aa9d61435dbe63e"
                ],
                [
                  "63033b0ca389c35abd64a5d9adefe000"
                ],
                [
                  "aeaf52febe706064088712be8a582fca"
                ],
                [
                  "1d0b14e4db018fed6e73e372e2338aca"
                ]
              ]
            },
            {
              "name": "shuffle, same pool, seed_n = 2^64 - 1",
              "mode": "shuffle",
              "blockLength": 1,
              "items": [
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "kind": "movie"
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "kind": "movie"
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "kind": "movie"
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "kind": "movie"
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "kind": "movie"
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "kind": "movie"
                },
                {
                  "id": "875b9307abf550055de186dcba779207",
                  "kind": "movie"
                },
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "18446744073709551615",
                "hex": "ffffffffffffffff",
                "hi": "ffffffff",
                "lo": "ffffffff"
              },
              "keys": [
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "fnv1a64": {
                    "dec": "10937791263775970312",
                    "hex": "97cad73418bcd408",
                    "hi": "97cad734",
                    "lo": "18bcd408"
                  },
                  "key": {
                    "dec": "10326454727648352652",
                    "hex": "8f4eefdce6a2198c",
                    "hi": "8f4eefdc",
                    "lo": "e6a2198c"
                  }
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "fnv1a64": {
                    "dec": "14085039705671131850",
                    "hex": "c3781cfc8fcce2ca",
                    "hi": "c3781cfc",
                    "lo": "8fcce2ca"
                  },
                  "key": {
                    "dec": "7996362557928253594",
                    "hex": "6ef8c961408c289a",
                    "hi": "6ef8c961",
                    "lo": "408c289a"
                  }
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "fnv1a64": {
                    "dec": "17290834846207924714",
                    "hex": "eff562ae8e2b59ea",
                    "hi": "eff562ae",
                    "lo": "8e2b59ea"
                  },
                  "key": {
                    "dec": "14028189440456036945",
                    "hex": "c2ae23f8b8449251",
                    "hi": "c2ae23f8",
                    "lo": "b8449251"
                  }
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "fnv1a64": {
                    "dec": "5234580602984906477",
                    "hex": "48a4f7530d10beed",
                    "hi": "48a4f753",
                    "lo": "0d10beed"
                  },
                  "key": {
                    "dec": "18216717352367320772",
                    "hex": "fccec7ea698c6ec4",
                    "hi": "fccec7ea",
                    "lo": "698c6ec4"
                  }
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "fnv1a64": {
                    "dec": "2194379552081164761",
                    "hex": "1e740093882db9d9",
                    "hi": "1e740093",
                    "lo": "882db9d9"
                  },
                  "key": {
                    "dec": "12296236597989911694",
                    "hex": "aaa50209a158ac8e",
                    "hi": "aaa50209",
                    "lo": "a158ac8e"
                  }
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "fnv1a64": {
                    "dec": "14055740565426251774",
                    "hex": "c3100592e147a7fe",
                    "hi": "c3100592",
                    "lo": "e147a7fe"
                  },
                  "key": {
                    "dec": "4631576276714581356",
                    "hex": "4046aa15a3b2ed6c",
                    "hi": "4046aa15",
                    "lo": "a3b2ed6c"
                  }
                },
                {
                  "id": "875b9307abf550055de186dcba779207",
                  "fnv1a64": {
                    "dec": "116129276822946599",
                    "hex": "019c92f6450e0327",
                    "hi": "019c92f6",
                    "lo": "450e0327"
                  },
                  "key": {
                    "dec": "572195022174901209",
                    "hex": "07f0d8574efb53d9",
                    "hi": "07f0d857",
                    "lo": "4efb53d9"
                  }
                },
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "fnv1a64": {
                    "dec": "12268042146837344138",
                    "hex": "aa40d7556bc2af8a",
                    "hi": "aa40d755",
                    "lo": "6bc2af8a"
                  },
                  "key": {
                    "dec": "8105760963324807063",
                    "hex": "707d72a665340797",
                    "hi": "707d72a6",
                    "lo": "65340797"
                  }
                }
              ],
              "order": [
                "875b9307abf550055de186dcba779207",
                "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                "63033b0ca389c35abd64a5d9adefe000",
                "808475f02ee373631120b3d00955f032",
                "1d0b14e4db018fed6e73e372e2338aca",
                "50f5647d2380309d943ff9fc99de8f03",
                "63cbe1e459320dd79e5651b0ef953636",
                "aeaf52febe706064088712be8a582fca"
              ],
              "runs": [
                [
                  "875b9307abf550055de186dcba779207"
                ],
                [
                  "c4ca37b7f8ad8aff6aa9d61435dbe63e"
                ],
                [
                  "63033b0ca389c35abd64a5d9adefe000"
                ],
                [
                  "808475f02ee373631120b3d00955f032"
                ],
                [
                  "1d0b14e4db018fed6e73e372e2338aca"
                ],
                [
                  "50f5647d2380309d943ff9fc99de8f03"
                ],
                [
                  "63cbe1e459320dd79e5651b0ef953636"
                ],
                [
                  "aeaf52febe706064088712be8a582fca"
                ]
              ]
            },
            {
              "name": "shuffle, same pool listed in reverse (input order does not matter)",
              "mode": "shuffle",
              "blockLength": 1,
              "items": [
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "kind": "movie"
                },
                {
                  "id": "875b9307abf550055de186dcba779207",
                  "kind": "movie"
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "kind": "movie"
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "kind": "movie"
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "kind": "movie"
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "kind": "movie"
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "kind": "movie"
                },
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "fnv1a64": {
                    "dec": "12268042146837344138",
                    "hex": "aa40d7556bc2af8a",
                    "hi": "aa40d755",
                    "lo": "6bc2af8a"
                  },
                  "key": {
                    "dec": "1394174070767846471",
                    "hex": "135919ee432d8447",
                    "hi": "135919ee",
                    "lo": "432d8447"
                  }
                },
                {
                  "id": "875b9307abf550055de186dcba779207",
                  "fnv1a64": {
                    "dec": "116129276822946599",
                    "hex": "019c92f6450e0327",
                    "hi": "019c92f6",
                    "lo": "450e0327"
                  },
                  "key": {
                    "dec": "6759848589115789304",
                    "hex": "5dcfce7a01ac0ff8",
                    "hi": "5dcfce7a",
                    "lo": "01ac0ff8"
                  }
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "fnv1a64": {
                    "dec": "14055740565426251774",
                    "hex": "c3100592e147a7fe",
                    "hi": "c3100592",
                    "lo": "e147a7fe"
                  },
                  "key": {
                    "dec": "4635095393886066843",
                    "hex": "40532ab3f4a0d89b",
                    "hi": "40532ab3",
                    "lo": "f4a0d89b"
                  }
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "fnv1a64": {
                    "dec": "2194379552081164761",
                    "hex": "1e740093882db9d9",
                    "hi": "1e740093",
                    "lo": "882db9d9"
                  },
                  "key": {
                    "dec": "18374023990997050735",
                    "hex": "fefda57855e2656f",
                    "hi": "fefda578",
                    "lo": "55e2656f"
                  }
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "fnv1a64": {
                    "dec": "5234580602984906477",
                    "hex": "48a4f7530d10beed",
                    "hi": "48a4f753",
                    "lo": "0d10beed"
                  },
                  "key": {
                    "dec": "704711946996120263",
                    "hex": "09c7a3c835ad7ac7",
                    "hi": "09c7a3c8",
                    "lo": "35ad7ac7"
                  }
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "fnv1a64": {
                    "dec": "17290834846207924714",
                    "hex": "eff562ae8e2b59ea",
                    "hi": "eff562ae",
                    "lo": "8e2b59ea"
                  },
                  "key": {
                    "dec": "6925821950981047500",
                    "hex": "601d765eadd404cc",
                    "hi": "601d765e",
                    "lo": "add404cc"
                  }
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "fnv1a64": {
                    "dec": "14085039705671131850",
                    "hex": "c3781cfc8fcce2ca",
                    "hi": "c3781cfc",
                    "lo": "8fcce2ca"
                  },
                  "key": {
                    "dec": "6893039688311782565",
                    "hex": "5fa8ff135c7e3ca5",
                    "hi": "5fa8ff13",
                    "lo": "5c7e3ca5"
                  }
                },
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "fnv1a64": {
                    "dec": "10937791263775970312",
                    "hex": "97cad73418bcd408",
                    "hi": "97cad734",
                    "lo": "18bcd408"
                  },
                  "key": {
                    "dec": "11908513588867654102",
                    "hex": "a5438a03db5e61d6",
                    "hi": "a5438a03",
                    "lo": "db5e61d6"
                  }
                }
              ],
              "order": [
                "aeaf52febe706064088712be8a582fca",
                "808475f02ee373631120b3d00955f032",
                "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                "875b9307abf550055de186dcba779207",
                "63033b0ca389c35abd64a5d9adefe000",
                "63cbe1e459320dd79e5651b0ef953636",
                "1d0b14e4db018fed6e73e372e2338aca",
                "50f5647d2380309d943ff9fc99de8f03"
              ],
              "runs": [
                [
                  "aeaf52febe706064088712be8a582fca"
                ],
                [
                  "808475f02ee373631120b3d00955f032"
                ],
                [
                  "c4ca37b7f8ad8aff6aa9d61435dbe63e"
                ],
                [
                  "875b9307abf550055de186dcba779207"
                ],
                [
                  "63033b0ca389c35abd64a5d9adefe000"
                ],
                [
                  "63cbe1e459320dd79e5651b0ef953636"
                ],
                [
                  "1d0b14e4db018fed6e73e372e2338aca"
                ],
                [
                  "50f5647d2380309d943ff9fc99de8f03"
                ]
              ]
            },
            {
              "name": "shuffle, 4th item of the first case removed (others keep their relative order)",
              "mode": "shuffle",
              "blockLength": 1,
              "items": [
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "kind": "movie"
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "kind": "movie"
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "kind": "movie"
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "kind": "movie"
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "kind": "movie"
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "kind": "movie"
                },
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [
                {
                  "id": "1d0b14e4db018fed6e73e372e2338aca",
                  "fnv1a64": {
                    "dec": "10937791263775970312",
                    "hex": "97cad73418bcd408",
                    "hi": "97cad734",
                    "lo": "18bcd408"
                  },
                  "key": {
                    "dec": "11908513588867654102",
                    "hex": "a5438a03db5e61d6",
                    "hi": "a5438a03",
                    "lo": "db5e61d6"
                  }
                },
                {
                  "id": "63033b0ca389c35abd64a5d9adefe000",
                  "fnv1a64": {
                    "dec": "14085039705671131850",
                    "hex": "c3781cfc8fcce2ca",
                    "hi": "c3781cfc",
                    "lo": "8fcce2ca"
                  },
                  "key": {
                    "dec": "6893039688311782565",
                    "hex": "5fa8ff135c7e3ca5",
                    "hi": "5fa8ff13",
                    "lo": "5c7e3ca5"
                  }
                },
                {
                  "id": "63cbe1e459320dd79e5651b0ef953636",
                  "fnv1a64": {
                    "dec": "17290834846207924714",
                    "hex": "eff562ae8e2b59ea",
                    "hi": "eff562ae",
                    "lo": "8e2b59ea"
                  },
                  "key": {
                    "dec": "6925821950981047500",
                    "hex": "601d765eadd404cc",
                    "hi": "601d765e",
                    "lo": "add404cc"
                  }
                },
                {
                  "id": "aeaf52febe706064088712be8a582fca",
                  "fnv1a64": {
                    "dec": "5234580602984906477",
                    "hex": "48a4f7530d10beed",
                    "hi": "48a4f753",
                    "lo": "0d10beed"
                  },
                  "key": {
                    "dec": "704711946996120263",
                    "hex": "09c7a3c835ad7ac7",
                    "hi": "09c7a3c8",
                    "lo": "35ad7ac7"
                  }
                },
                {
                  "id": "50f5647d2380309d943ff9fc99de8f03",
                  "fnv1a64": {
                    "dec": "2194379552081164761",
                    "hex": "1e740093882db9d9",
                    "hi": "1e740093",
                    "lo": "882db9d9"
                  },
                  "key": {
                    "dec": "18374023990997050735",
                    "hex": "fefda57855e2656f",
                    "hi": "fefda578",
                    "lo": "55e2656f"
                  }
                },
                {
                  "id": "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                  "fnv1a64": {
                    "dec": "14055740565426251774",
                    "hex": "c3100592e147a7fe",
                    "hi": "c3100592",
                    "lo": "e147a7fe"
                  },
                  "key": {
                    "dec": "4635095393886066843",
                    "hex": "40532ab3f4a0d89b",
                    "hi": "40532ab3",
                    "lo": "f4a0d89b"
                  }
                },
                {
                  "id": "808475f02ee373631120b3d00955f032",
                  "fnv1a64": {
                    "dec": "12268042146837344138",
                    "hex": "aa40d7556bc2af8a",
                    "hi": "aa40d755",
                    "lo": "6bc2af8a"
                  },
                  "key": {
                    "dec": "1394174070767846471",
                    "hex": "135919ee432d8447",
                    "hi": "135919ee",
                    "lo": "432d8447"
                  }
                }
              ],
              "order": [
                "aeaf52febe706064088712be8a582fca",
                "808475f02ee373631120b3d00955f032",
                "c4ca37b7f8ad8aff6aa9d61435dbe63e",
                "63033b0ca389c35abd64a5d9adefe000",
                "63cbe1e459320dd79e5651b0ef953636",
                "1d0b14e4db018fed6e73e372e2338aca",
                "50f5647d2380309d943ff9fc99de8f03"
              ],
              "runs": [
                [
                  "aeaf52febe706064088712be8a582fca"
                ],
                [
                  "808475f02ee373631120b3d00955f032"
                ],
                [
                  "c4ca37b7f8ad8aff6aa9d61435dbe63e"
                ],
                [
                  "63033b0ca389c35abd64a5d9adefe000"
                ],
                [
                  "63cbe1e459320dd79e5651b0ef953636"
                ],
                [
                  "1d0b14e4db018fed6e73e372e2338aca"
                ],
                [
                  "50f5647d2380309d943ff9fc99de8f03"
                ]
              ]
            },
            {
              "name": "shuffle, forced key tie (equal fnv1a64, different IDs), seed_n = 0",
              "mode": "shuffle",
              "blockLength": 1,
              "note": "The two tied IDs have the same hash, so the same key for every seed; the smaller ID bytes (b3b8...) comes first. They are listed larger-first.",
              "items": [
                {
                  "id": "bf13eaba83dea4340123456789abcdef",
                  "kind": "movie"
                },
                {
                  "id": "417ffd1301eaa631322f69afa8a70bea",
                  "kind": "movie"
                },
                {
                  "id": "b3b828bb3655e2a70123456789abcdef",
                  "kind": "movie"
                },
                {
                  "id": "8c741196acc47e35d6967248fbe68cc3",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "0",
                "hex": "0000000000000000",
                "hi": "00000000",
                "lo": "00000000"
              },
              "keys": [
                {
                  "id": "bf13eaba83dea4340123456789abcdef",
                  "fnv1a64": {
                    "dec": "5954680025363978854",
                    "hex": "52a345ef0eef1a66",
                    "hi": "52a345ef",
                    "lo": "0eef1a66"
                  },
                  "key": {
                    "dec": "14259928744909141981",
                    "hex": "c5e571a422b0f7dd",
                    "hi": "c5e571a4",
                    "lo": "22b0f7dd"
                  }
                },
                {
                  "id": "417ffd1301eaa631322f69afa8a70bea",
                  "fnv1a64": {
                    "dec": "15162477800505993565",
                    "hex": "d26bf139ade7e95d",
                    "hi": "d26bf139",
                    "lo": "ade7e95d"
                  },
                  "key": {
                    "dec": "5104182052620060451",
                    "hex": "46d5b288a357c723",
                    "hi": "46d5b288",
                    "lo": "a357c723"
                  }
                },
                {
                  "id": "b3b828bb3655e2a70123456789abcdef",
                  "fnv1a64": {
                    "dec": "5954680025363978854",
                    "hex": "52a345ef0eef1a66",
                    "hi": "52a345ef",
                    "lo": "0eef1a66"
                  },
                  "key": {
                    "dec": "14259928744909141981",
                    "hex": "c5e571a422b0f7dd",
                    "hi": "c5e571a4",
                    "lo": "22b0f7dd"
                  }
                },
                {
                  "id": "8c741196acc47e35d6967248fbe68cc3",
                  "fnv1a64": {
                    "dec": "13364326132937895873",
                    "hex": "b9779fcf883bafc1",
                    "hi": "b9779fcf",
                    "lo": "883bafc1"
                  },
                  "key": {
                    "dec": "18260809953956668491",
                    "hex": "fd6b6de73881244b",
                    "hi": "fd6b6de7",
                    "lo": "3881244b"
                  }
                }
              ],
              "order": [
                "417ffd1301eaa631322f69afa8a70bea",
                "b3b828bb3655e2a70123456789abcdef",
                "bf13eaba83dea4340123456789abcdef",
                "8c741196acc47e35d6967248fbe68cc3"
              ],
              "runs": [
                [
                  "417ffd1301eaa631322f69afa8a70bea"
                ],
                [
                  "b3b828bb3655e2a70123456789abcdef"
                ],
                [
                  "bf13eaba83dea4340123456789abcdef"
                ],
                [
                  "8c741196acc47e35d6967248fbe68cc3"
                ]
              ]
            },
            {
              "name": "shuffle, forced key tie (equal fnv1a64, different IDs), seed_n of seed 5, n 7",
              "mode": "shuffle",
              "blockLength": 1,
              "note": "The two tied IDs have the same hash, so the same key for every seed; the smaller ID bytes (b3b8...) comes first. They are listed larger-first.",
              "items": [
                {
                  "id": "bf13eaba83dea4340123456789abcdef",
                  "kind": "movie"
                },
                {
                  "id": "417ffd1301eaa631322f69afa8a70bea",
                  "kind": "movie"
                },
                {
                  "id": "b3b828bb3655e2a70123456789abcdef",
                  "kind": "movie"
                },
                {
                  "id": "8c741196acc47e35d6967248fbe68cc3",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "9428158358266441515",
                "hex": "82d78c130699ef2b",
                "hi": "82d78c13",
                "lo": "0699ef2b"
              },
              "keys": [
                {
                  "id": "bf13eaba83dea4340123456789abcdef",
                  "fnv1a64": {
                    "dec": "5954680025363978854",
                    "hex": "52a345ef0eef1a66",
                    "hi": "52a345ef",
                    "lo": "0eef1a66"
                  },
                  "key": {
                    "dec": "2828565044317459163",
                    "hex": "274114ebe2a112db",
                    "hi": "274114eb",
                    "lo": "e2a112db"
                  }
                },
                {
                  "id": "417ffd1301eaa631322f69afa8a70bea",
                  "fnv1a64": {
                    "dec": "15162477800505993565",
                    "hex": "d26bf139ade7e95d",
                    "hi": "d26bf139",
                    "lo": "ade7e95d"
                  },
                  "key": {
                    "dec": "16258274834171385487",
                    "hex": "e1a0fed25775568f",
                    "hi": "e1a0fed2",
                    "lo": "5775568f"
                  }
                },
                {
                  "id": "b3b828bb3655e2a70123456789abcdef",
                  "fnv1a64": {
                    "dec": "5954680025363978854",
                    "hex": "52a345ef0eef1a66",
                    "hi": "52a345ef",
                    "lo": "0eef1a66"
                  },
                  "key": {
                    "dec": "2828565044317459163",
                    "hex": "274114ebe2a112db",
                    "hi": "274114eb",
                    "lo": "e2a112db"
                  }
                },
                {
                  "id": "8c741196acc47e35d6967248fbe68cc3",
                  "fnv1a64": {
                    "dec": "13364326132937895873",
                    "hex": "b9779fcf883bafc1",
                    "hi": "b9779fcf",
                    "lo": "883bafc1"
                  },
                  "key": {
                    "dec": "1323356806940919853",
                    "hex": "125d820124cf082d",
                    "hi": "125d8201",
                    "lo": "24cf082d"
                  }
                }
              ],
              "order": [
                "8c741196acc47e35d6967248fbe68cc3",
                "b3b828bb3655e2a70123456789abcdef",
                "bf13eaba83dea4340123456789abcdef",
                "417ffd1301eaa631322f69afa8a70bea"
              ],
              "runs": [
                [
                  "8c741196acc47e35d6967248fbe68cc3"
                ],
                [
                  "b3b828bb3655e2a70123456789abcdef"
                ],
                [
                  "bf13eaba83dea4340123456789abcdef"
                ],
                [
                  "417ffd1301eaa631322f69afa8a70bea"
                ]
              ]
            },
            {
              "name": "shuffle, one item",
              "mode": "shuffle",
              "blockLength": 1,
              "items": [
                {
                  "id": "9192105c8367ccf50de7f33488454a0c",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [
                {
                  "id": "9192105c8367ccf50de7f33488454a0c",
                  "fnv1a64": {
                    "dec": "8359573330282867837",
                    "hex": "74032ba72956ec7d",
                    "hi": "74032ba7",
                    "lo": "2956ec7d"
                  },
                  "key": {
                    "dec": "14076677511786548442",
                    "hex": "c35a679d93c44cda",
                    "hi": "c35a679d",
                    "lo": "93c44cda"
                  }
                }
              ],
              "order": [
                "9192105c8367ccf50de7f33488454a0c"
              ],
              "runs": [
                [
                  "9192105c8367ccf50de7f33488454a0c"
                ]
              ]
            },
            {
              "name": "shuffle, empty pool",
              "mode": "shuffle",
              "blockLength": 1,
              "items": [],
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [],
              "order": [],
              "runs": []
            },
            {
              "name": "chronological, the format doc's worked example",
              "mode": "chronological",
              "blockLength": 1,
              "note": "Series 1...1 (a, b), 2...2 (c, d), 3...3 (e special, f undated), movie 4...4. Expected order from the doc: e, a, c, movie, d, b, f.",
              "items": [
                {
                  "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "kind": "episode",
                  "seriesId": "11111111111111111111111111111111",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "kind": "episode",
                  "seriesId": "11111111111111111111111111111111",
                  "premiere": "2020-01-08",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "cccccccccccccccccccccccccccccccc",
                  "kind": "episode",
                  "seriesId": "22222222222222222222222222222222",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "dddddddddddddddddddddddddddddddd",
                  "kind": "episode",
                  "seriesId": "22222222222222222222222222222222",
                  "premiere": "2020-01-02",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "kind": "episode",
                  "seriesId": "33333333333333333333333333333333",
                  "premiere": "2019-12-25",
                  "season": 0,
                  "episode": 1
                },
                {
                  "id": "ffffffffffffffffffffffffffffffff",
                  "kind": "episode",
                  "seriesId": "33333333333333333333333333333333",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                }
              ],
              "keys": [
                {
                  "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "11111111111111111111111111111111",
                    "season": 1,
                    "episode": 1,
                    "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }
                },
                {
                  "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-08",
                    "groupId": "11111111111111111111111111111111",
                    "season": 1,
                    "episode": 2,
                    "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                  }
                },
                {
                  "id": "cccccccccccccccccccccccccccccccc",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "22222222222222222222222222222222",
                    "season": 1,
                    "episode": 1,
                    "id": "cccccccccccccccccccccccccccccccc"
                  }
                },
                {
                  "id": "dddddddddddddddddddddddddddddddd",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-02",
                    "groupId": "22222222222222222222222222222222",
                    "season": 1,
                    "episode": 2,
                    "id": "dddddddddddddddddddddddddddddddd"
                  }
                },
                {
                  "id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "keyParts": {
                    "undated": 0,
                    "date": "2019-12-25",
                    "groupId": "33333333333333333333333333333333",
                    "season": 0,
                    "episode": 1,
                    "id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                  }
                },
                {
                  "id": "ffffffffffffffffffffffffffffffff",
                  "keyParts": {
                    "undated": 1,
                    "date": null,
                    "groupId": "33333333333333333333333333333333",
                    "season": 1,
                    "episode": 1,
                    "id": "ffffffffffffffffffffffffffffffff"
                  }
                },
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "44444444444444444444444444444444",
                    "season": null,
                    "episode": null,
                    "id": "44444444444444444444444444444444"
                  }
                }
              ],
              "order": [
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "cccccccccccccccccccccccccccccccc",
                "44444444444444444444444444444444",
                "dddddddddddddddddddddddddddddddd",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "ffffffffffffffffffffffffffffffff"
              ],
              "runs": [
                [
                  "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                ],
                [
                  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                ],
                [
                  "cccccccccccccccccccccccccccccccc"
                ],
                [
                  "44444444444444444444444444444444"
                ],
                [
                  "dddddddddddddddddddddddddddddddd"
                ],
                [
                  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                ],
                [
                  "ffffffffffffffffffffffffffffffff"
                ]
              ]
            },
            {
              "name": "chronological, the worked example listed in reverse",
              "mode": "chronological",
              "blockLength": 1,
              "items": [
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "ffffffffffffffffffffffffffffffff",
                  "kind": "episode",
                  "seriesId": "33333333333333333333333333333333",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "kind": "episode",
                  "seriesId": "33333333333333333333333333333333",
                  "premiere": "2019-12-25",
                  "season": 0,
                  "episode": 1
                },
                {
                  "id": "dddddddddddddddddddddddddddddddd",
                  "kind": "episode",
                  "seriesId": "22222222222222222222222222222222",
                  "premiere": "2020-01-02",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "cccccccccccccccccccccccccccccccc",
                  "kind": "episode",
                  "seriesId": "22222222222222222222222222222222",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "kind": "episode",
                  "seriesId": "11111111111111111111111111111111",
                  "premiere": "2020-01-08",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "kind": "episode",
                  "seriesId": "11111111111111111111111111111111",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                }
              ],
              "keys": [
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "44444444444444444444444444444444",
                    "season": null,
                    "episode": null,
                    "id": "44444444444444444444444444444444"
                  }
                },
                {
                  "id": "ffffffffffffffffffffffffffffffff",
                  "keyParts": {
                    "undated": 1,
                    "date": null,
                    "groupId": "33333333333333333333333333333333",
                    "season": 1,
                    "episode": 1,
                    "id": "ffffffffffffffffffffffffffffffff"
                  }
                },
                {
                  "id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "keyParts": {
                    "undated": 0,
                    "date": "2019-12-25",
                    "groupId": "33333333333333333333333333333333",
                    "season": 0,
                    "episode": 1,
                    "id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                  }
                },
                {
                  "id": "dddddddddddddddddddddddddddddddd",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-02",
                    "groupId": "22222222222222222222222222222222",
                    "season": 1,
                    "episode": 2,
                    "id": "dddddddddddddddddddddddddddddddd"
                  }
                },
                {
                  "id": "cccccccccccccccccccccccccccccccc",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "22222222222222222222222222222222",
                    "season": 1,
                    "episode": 1,
                    "id": "cccccccccccccccccccccccccccccccc"
                  }
                },
                {
                  "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-08",
                    "groupId": "11111111111111111111111111111111",
                    "season": 1,
                    "episode": 2,
                    "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                  }
                },
                {
                  "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "11111111111111111111111111111111",
                    "season": 1,
                    "episode": 1,
                    "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }
                }
              ],
              "order": [
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "cccccccccccccccccccccccccccccccc",
                "44444444444444444444444444444444",
                "dddddddddddddddddddddddddddddddd",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "ffffffffffffffffffffffffffffffff"
              ],
              "runs": [
                [
                  "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                ],
                [
                  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                ],
                [
                  "cccccccccccccccccccccccccccccccc"
                ],
                [
                  "44444444444444444444444444444444"
                ],
                [
                  "dddddddddddddddddddddddddddddddd"
                ],
                [
                  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                ],
                [
                  "ffffffffffffffffffffffffffffffff"
                ]
              ]
            },
            {
              "name": "chronological, same dates: group ID decides, then season, then episode",
              "mode": "chronological",
              "blockLength": 1,
              "items": [
                {
                  "id": "99999999999999999999999999999999",
                  "kind": "episode",
                  "seriesId": "88888888888888888888888888888888",
                  "premiere": "2021-05-05",
                  "season": 2,
                  "episode": 1
                },
                {
                  "id": "77777777777777777777777777777777",
                  "kind": "episode",
                  "seriesId": "88888888888888888888888888888888",
                  "premiere": "2021-05-05",
                  "season": 1,
                  "episode": 9
                },
                {
                  "id": "66666666666666666666666666666666",
                  "kind": "episode",
                  "seriesId": "55555555555555555555555555555555",
                  "premiere": "2021-05-05",
                  "season": 3,
                  "episode": 3
                },
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "movie",
                  "premiere": "2021-05-05"
                },
                {
                  "id": "33333333333333333333333333333333",
                  "kind": "episode",
                  "seriesId": "88888888888888888888888888888888",
                  "premiere": "2021-05-05",
                  "season": 1,
                  "episode": 2
                }
              ],
              "keys": [
                {
                  "id": "99999999999999999999999999999999",
                  "keyParts": {
                    "undated": 0,
                    "date": "2021-05-05",
                    "groupId": "88888888888888888888888888888888",
                    "season": 2,
                    "episode": 1,
                    "id": "99999999999999999999999999999999"
                  }
                },
                {
                  "id": "77777777777777777777777777777777",
                  "keyParts": {
                    "undated": 0,
                    "date": "2021-05-05",
                    "groupId": "88888888888888888888888888888888",
                    "season": 1,
                    "episode": 9,
                    "id": "77777777777777777777777777777777"
                  }
                },
                {
                  "id": "66666666666666666666666666666666",
                  "keyParts": {
                    "undated": 0,
                    "date": "2021-05-05",
                    "groupId": "55555555555555555555555555555555",
                    "season": 3,
                    "episode": 3,
                    "id": "66666666666666666666666666666666"
                  }
                },
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "2021-05-05",
                    "groupId": "44444444444444444444444444444444",
                    "season": null,
                    "episode": null,
                    "id": "44444444444444444444444444444444"
                  }
                },
                {
                  "id": "33333333333333333333333333333333",
                  "keyParts": {
                    "undated": 0,
                    "date": "2021-05-05",
                    "groupId": "88888888888888888888888888888888",
                    "season": 1,
                    "episode": 2,
                    "id": "33333333333333333333333333333333"
                  }
                }
              ],
              "order": [
                "44444444444444444444444444444444",
                "66666666666666666666666666666666",
                "33333333333333333333333333333333",
                "77777777777777777777777777777777",
                "99999999999999999999999999999999"
              ],
              "runs": [
                [
                  "44444444444444444444444444444444"
                ],
                [
                  "66666666666666666666666666666666"
                ],
                [
                  "33333333333333333333333333333333"
                ],
                [
                  "77777777777777777777777777777777"
                ],
                [
                  "99999999999999999999999999999999"
                ]
              ]
            },
            {
              "name": "chronological, specials (season 0) sort before season 1 on equal dates",
              "mode": "chronological",
              "blockLength": 1,
              "items": [
                {
                  "id": "22222222222222222222222222222222",
                  "kind": "episode",
                  "seriesId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "premiere": "2022-02-02",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "episode",
                  "seriesId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "premiere": "2022-02-02",
                  "season": 0,
                  "episode": 7
                },
                {
                  "id": "33333333333333333333333333333333",
                  "kind": "episode",
                  "seriesId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "premiere": "2022-02-01",
                  "season": 4,
                  "episode": 4
                }
              ],
              "keys": [
                {
                  "id": "22222222222222222222222222222222",
                  "keyParts": {
                    "undated": 0,
                    "date": "2022-02-02",
                    "groupId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "season": 1,
                    "episode": 1,
                    "id": "22222222222222222222222222222222"
                  }
                },
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 0,
                    "date": "2022-02-02",
                    "groupId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "season": 0,
                    "episode": 7,
                    "id": "11111111111111111111111111111111"
                  }
                },
                {
                  "id": "33333333333333333333333333333333",
                  "keyParts": {
                    "undated": 0,
                    "date": "2022-02-01",
                    "groupId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "season": 4,
                    "episode": 4,
                    "id": "33333333333333333333333333333333"
                  }
                }
              ],
              "order": [
                "33333333333333333333333333333333",
                "11111111111111111111111111111111",
                "22222222222222222222222222222222"
              ],
              "runs": [
                [
                  "33333333333333333333333333333333"
                ],
                [
                  "11111111111111111111111111111111"
                ],
                [
                  "22222222222222222222222222222222"
                ]
              ]
            },
            {
              "name": "chronological, missing season and episode sort after present ones",
              "mode": "chronological",
              "blockLength": 1,
              "items": [
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "episode",
                  "seriesId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "premiere": "2022-02-02"
                },
                {
                  "id": "22222222222222222222222222222222",
                  "kind": "episode",
                  "seriesId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "premiere": "2022-02-02",
                  "season": 1
                },
                {
                  "id": "33333333333333333333333333333333",
                  "kind": "episode",
                  "seriesId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "premiere": "2022-02-02",
                  "season": 1,
                  "episode": 5
                },
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "episode",
                  "seriesId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "premiere": "2022-02-02",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "55555555555555555555555555555555",
                  "kind": "episode",
                  "seriesId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "premiere": "2022-02-02",
                  "season": 9,
                  "episode": 9
                }
              ],
              "keys": [
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 0,
                    "date": "2022-02-02",
                    "groupId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "season": null,
                    "episode": null,
                    "id": "11111111111111111111111111111111"
                  }
                },
                {
                  "id": "22222222222222222222222222222222",
                  "keyParts": {
                    "undated": 0,
                    "date": "2022-02-02",
                    "groupId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "season": 1,
                    "episode": null,
                    "id": "22222222222222222222222222222222"
                  }
                },
                {
                  "id": "33333333333333333333333333333333",
                  "keyParts": {
                    "undated": 0,
                    "date": "2022-02-02",
                    "groupId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "season": 1,
                    "episode": 5,
                    "id": "33333333333333333333333333333333"
                  }
                },
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "2022-02-02",
                    "groupId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "season": 1,
                    "episode": 1,
                    "id": "44444444444444444444444444444444"
                  }
                },
                {
                  "id": "55555555555555555555555555555555",
                  "keyParts": {
                    "undated": 0,
                    "date": "2022-02-02",
                    "groupId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "season": 9,
                    "episode": 9,
                    "id": "55555555555555555555555555555555"
                  }
                }
              ],
              "order": [
                "44444444444444444444444444444444",
                "33333333333333333333333333333333",
                "22222222222222222222222222222222",
                "55555555555555555555555555555555",
                "11111111111111111111111111111111"
              ],
              "runs": [
                [
                  "44444444444444444444444444444444"
                ],
                [
                  "33333333333333333333333333333333"
                ],
                [
                  "22222222222222222222222222222222"
                ],
                [
                  "55555555555555555555555555555555"
                ],
                [
                  "11111111111111111111111111111111"
                ]
              ]
            },
            {
              "name": "chronological, production year gives YYYY-01-01 (zero-padded); premiere wins; undated last",
              "mode": "chronological",
              "blockLength": 1,
              "note": "id 3 has year 987, date 0987-01-01. id 4 has a premiere in 1999, so its year 1990 is ignored. Ids 1 and 2 tie on the date 1999-01-01 and on the empty season/episode; the ID decides.",
              "items": [
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "movie",
                  "year": 1999
                },
                {
                  "id": "22222222222222222222222222222222",
                  "kind": "movie",
                  "premiere": "1999-01-01"
                },
                {
                  "id": "33333333333333333333333333333333",
                  "kind": "movie",
                  "year": 987
                },
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "movie",
                  "premiere": "1999-06-01",
                  "year": 1990
                },
                {
                  "id": "55555555555555555555555555555555",
                  "kind": "movie"
                },
                {
                  "id": "66666666666666666666666666666666",
                  "kind": "movie",
                  "premiere": "2001-01-01"
                },
                {
                  "id": "77777777777777777777777777777777",
                  "kind": "movie",
                  "year": 2001
                }
              ],
              "keys": [
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 0,
                    "date": "1999-01-01",
                    "groupId": "11111111111111111111111111111111",
                    "season": null,
                    "episode": null,
                    "id": "11111111111111111111111111111111"
                  }
                },
                {
                  "id": "22222222222222222222222222222222",
                  "keyParts": {
                    "undated": 0,
                    "date": "1999-01-01",
                    "groupId": "22222222222222222222222222222222",
                    "season": null,
                    "episode": null,
                    "id": "22222222222222222222222222222222"
                  }
                },
                {
                  "id": "33333333333333333333333333333333",
                  "keyParts": {
                    "undated": 0,
                    "date": "0987-01-01",
                    "groupId": "33333333333333333333333333333333",
                    "season": null,
                    "episode": null,
                    "id": "33333333333333333333333333333333"
                  }
                },
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "1999-06-01",
                    "groupId": "44444444444444444444444444444444",
                    "season": null,
                    "episode": null,
                    "id": "44444444444444444444444444444444"
                  }
                },
                {
                  "id": "55555555555555555555555555555555",
                  "keyParts": {
                    "undated": 1,
                    "date": null,
                    "groupId": "55555555555555555555555555555555",
                    "season": null,
                    "episode": null,
                    "id": "55555555555555555555555555555555"
                  }
                },
                {
                  "id": "66666666666666666666666666666666",
                  "keyParts": {
                    "undated": 0,
                    "date": "2001-01-01",
                    "groupId": "66666666666666666666666666666666",
                    "season": null,
                    "episode": null,
                    "id": "66666666666666666666666666666666"
                  }
                },
                {
                  "id": "77777777777777777777777777777777",
                  "keyParts": {
                    "undated": 0,
                    "date": "2001-01-01",
                    "groupId": "77777777777777777777777777777777",
                    "season": null,
                    "episode": null,
                    "id": "77777777777777777777777777777777"
                  }
                }
              ],
              "order": [
                "33333333333333333333333333333333",
                "11111111111111111111111111111111",
                "22222222222222222222222222222222",
                "44444444444444444444444444444444",
                "66666666666666666666666666666666",
                "77777777777777777777777777777777",
                "55555555555555555555555555555555"
              ],
              "runs": [
                [
                  "33333333333333333333333333333333"
                ],
                [
                  "11111111111111111111111111111111"
                ],
                [
                  "22222222222222222222222222222222"
                ],
                [
                  "44444444444444444444444444444444"
                ],
                [
                  "66666666666666666666666666666666"
                ],
                [
                  "77777777777777777777777777777777"
                ],
                [
                  "55555555555555555555555555555555"
                ]
              ]
            },
            {
              "name": "chronological, two undated items, group ID then ID decide",
              "mode": "chronological",
              "blockLength": 1,
              "items": [
                {
                  "id": "22222222222222222222222222222222",
                  "kind": "movie"
                },
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "episode",
                  "seriesId": "ffffffffffffffffffffffffffffffff",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "33333333333333333333333333333333",
                  "kind": "episode",
                  "seriesId": "00000000000000000000000000000000",
                  "season": 5,
                  "episode": 5
                }
              ],
              "keys": [
                {
                  "id": "22222222222222222222222222222222",
                  "keyParts": {
                    "undated": 1,
                    "date": null,
                    "groupId": "22222222222222222222222222222222",
                    "season": null,
                    "episode": null,
                    "id": "22222222222222222222222222222222"
                  }
                },
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 1,
                    "date": null,
                    "groupId": "ffffffffffffffffffffffffffffffff",
                    "season": 1,
                    "episode": 1,
                    "id": "11111111111111111111111111111111"
                  }
                },
                {
                  "id": "33333333333333333333333333333333",
                  "keyParts": {
                    "undated": 1,
                    "date": null,
                    "groupId": "00000000000000000000000000000000",
                    "season": 5,
                    "episode": 5,
                    "id": "33333333333333333333333333333333"
                  }
                }
              ],
              "order": [
                "33333333333333333333333333333333",
                "22222222222222222222222222222222",
                "11111111111111111111111111111111"
              ],
              "runs": [
                [
                  "33333333333333333333333333333333"
                ],
                [
                  "22222222222222222222222222222222"
                ],
                [
                  "11111111111111111111111111111111"
                ]
              ]
            },
            {
              "name": "chronological, episode with no series is a group of one (groupId = its own id)",
              "mode": "chronological",
              "blockLength": 1,
              "items": [
                {
                  "id": "55555555555555555555555555555555",
                  "kind": "episode",
                  "premiere": "2023-03-03",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "episode",
                  "premiere": "2023-03-03",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "66666666666666666666666666666666",
                  "kind": "episode",
                  "seriesId": "33333333333333333333333333333333",
                  "premiere": "2023-03-03",
                  "season": 1,
                  "episode": 1
                }
              ],
              "keys": [
                {
                  "id": "55555555555555555555555555555555",
                  "keyParts": {
                    "undated": 0,
                    "date": "2023-03-03",
                    "groupId": "55555555555555555555555555555555",
                    "season": 1,
                    "episode": 1,
                    "id": "55555555555555555555555555555555"
                  }
                },
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "2023-03-03",
                    "groupId": "44444444444444444444444444444444",
                    "season": 1,
                    "episode": 1,
                    "id": "44444444444444444444444444444444"
                  }
                },
                {
                  "id": "66666666666666666666666666666666",
                  "keyParts": {
                    "undated": 0,
                    "date": "2023-03-03",
                    "groupId": "33333333333333333333333333333333",
                    "season": 1,
                    "episode": 1,
                    "id": "66666666666666666666666666666666"
                  }
                }
              ],
              "order": [
                "66666666666666666666666666666666",
                "44444444444444444444444444444444",
                "55555555555555555555555555555555"
              ],
              "runs": [
                [
                  "66666666666666666666666666666666"
                ],
                [
                  "44444444444444444444444444444444"
                ],
                [
                  "55555555555555555555555555555555"
                ]
              ]
            },
            {
              "name": "chronological, same everything, only the ID differs",
              "mode": "chronological",
              "blockLength": 1,
              "items": [
                {
                  "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "kind": "movie",
                  "premiere": "2020-02-02"
                },
                {
                  "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "kind": "movie",
                  "premiere": "2020-02-02"
                }
              ],
              "keys": [
                {
                  "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-02-02",
                    "groupId": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "season": null,
                    "episode": null,
                    "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                  }
                },
                {
                  "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-02-02",
                    "groupId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "season": null,
                    "episode": null,
                    "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }
                }
              ],
              "order": [
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
              ],
              "runs": [
                [
                  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                ],
                [
                  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                ]
              ]
            },
            {
              "name": "randomInOrder, the format doc's worked example (group B, A, C by key), blockLength 1",
              "mode": "randomInOrder",
              "blockLength": 1,
              "note": "Groups B (2 items), A (3), C (1). Their real group keys, from the channel seed 8, put them in the order B, A, C. Item IDs end in the 2-digit position in the group.",
              "items": [
                {
                  "id": "00000000000000000000000000000301",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000003f5",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000000000000000000302",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000003f5",
                  "premiere": "2020-01-08",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-02",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-03",
                  "season": 1,
                  "episode": 3
                },
                {
                  "id": "00000000000000000000000000000701",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000007dd",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                }
              ],
              "seed": {
                "dec": "8",
                "hex": "0000000000000008",
                "hi": "00000000",
                "lo": "00000008"
              },
              "groups": [
                {
                  "groupId": "000000000000000000000000000003f5",
                  "key": {
                    "dec": "6698989484632538537",
                    "hex": "5cf7977189997da9",
                    "hi": "5cf79771",
                    "lo": "89997da9"
                  },
                  "itemIds": [
                    "00000000000000000000000000000301",
                    "00000000000000000000000000000302"
                  ]
                },
                {
                  "groupId": "0000000000000000000000000000000d",
                  "key": {
                    "dec": "14487537339651690204",
                    "hex": "c90e1273c0258edc",
                    "hi": "c90e1273",
                    "lo": "c0258edc"
                  },
                  "itemIds": [
                    "00000000000000000000000000000001",
                    "00000000000000000000000000000002",
                    "00000000000000000000000000000003"
                  ]
                },
                {
                  "groupId": "000000000000000000000000000007dd",
                  "key": {
                    "dec": "17569372152942007697",
                    "hex": "f3d2f2e2f6bf9591",
                    "hi": "f3d2f2e2",
                    "lo": "f6bf9591"
                  },
                  "itemIds": [
                    "00000000000000000000000000000701"
                  ]
                }
              ],
              "order": [
                "00000000000000000000000000000301",
                "00000000000000000000000000000001",
                "00000000000000000000000000000701",
                "00000000000000000000000000000302",
                "00000000000000000000000000000002",
                "00000000000000000000000000000003"
              ],
              "runs": [
                [
                  "00000000000000000000000000000301"
                ],
                [
                  "00000000000000000000000000000001"
                ],
                [
                  "00000000000000000000000000000701"
                ],
                [
                  "00000000000000000000000000000302"
                ],
                [
                  "00000000000000000000000000000002"
                ],
                [
                  "00000000000000000000000000000003"
                ]
              ]
            },
            {
              "name": "randomInOrder, the format doc's worked example (group B, A, C by key), blockLength 2",
              "mode": "randomInOrder",
              "blockLength": 2,
              "note": "Groups B (2 items), A (3), C (1). Their real group keys, from the channel seed 8, put them in the order B, A, C. Item IDs end in the 2-digit position in the group.",
              "items": [
                {
                  "id": "00000000000000000000000000000301",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000003f5",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000000000000000000302",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000003f5",
                  "premiere": "2020-01-08",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-02",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-03",
                  "season": 1,
                  "episode": 3
                },
                {
                  "id": "00000000000000000000000000000701",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000007dd",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                }
              ],
              "seed": {
                "dec": "8",
                "hex": "0000000000000008",
                "hi": "00000000",
                "lo": "00000008"
              },
              "groups": [
                {
                  "groupId": "000000000000000000000000000003f5",
                  "key": {
                    "dec": "6698989484632538537",
                    "hex": "5cf7977189997da9",
                    "hi": "5cf79771",
                    "lo": "89997da9"
                  },
                  "itemIds": [
                    "00000000000000000000000000000301",
                    "00000000000000000000000000000302"
                  ]
                },
                {
                  "groupId": "0000000000000000000000000000000d",
                  "key": {
                    "dec": "14487537339651690204",
                    "hex": "c90e1273c0258edc",
                    "hi": "c90e1273",
                    "lo": "c0258edc"
                  },
                  "itemIds": [
                    "00000000000000000000000000000001",
                    "00000000000000000000000000000002",
                    "00000000000000000000000000000003"
                  ]
                },
                {
                  "groupId": "000000000000000000000000000007dd",
                  "key": {
                    "dec": "17569372152942007697",
                    "hex": "f3d2f2e2f6bf9591",
                    "hi": "f3d2f2e2",
                    "lo": "f6bf9591"
                  },
                  "itemIds": [
                    "00000000000000000000000000000701"
                  ]
                }
              ],
              "order": [
                "00000000000000000000000000000301",
                "00000000000000000000000000000302",
                "00000000000000000000000000000001",
                "00000000000000000000000000000002",
                "00000000000000000000000000000701",
                "00000000000000000000000000000003"
              ],
              "runs": [
                [
                  "00000000000000000000000000000301",
                  "00000000000000000000000000000302"
                ],
                [
                  "00000000000000000000000000000001",
                  "00000000000000000000000000000002"
                ],
                [
                  "00000000000000000000000000000701"
                ],
                [
                  "00000000000000000000000000000003"
                ]
              ]
            },
            {
              "name": "randomInOrder, same pool, blockLength 100 (each group is one run)",
              "mode": "randomInOrder",
              "blockLength": 100,
              "items": [
                {
                  "id": "00000000000000000000000000000301",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000003f5",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000000000000000000302",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000003f5",
                  "premiere": "2020-01-08",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-02",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-03",
                  "season": 1,
                  "episode": 3
                },
                {
                  "id": "00000000000000000000000000000701",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000007dd",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                }
              ],
              "seed": {
                "dec": "8",
                "hex": "0000000000000008",
                "hi": "00000000",
                "lo": "00000008"
              },
              "groups": [
                {
                  "groupId": "000000000000000000000000000003f5",
                  "key": {
                    "dec": "6698989484632538537",
                    "hex": "5cf7977189997da9",
                    "hi": "5cf79771",
                    "lo": "89997da9"
                  },
                  "itemIds": [
                    "00000000000000000000000000000301",
                    "00000000000000000000000000000302"
                  ]
                },
                {
                  "groupId": "0000000000000000000000000000000d",
                  "key": {
                    "dec": "14487537339651690204",
                    "hex": "c90e1273c0258edc",
                    "hi": "c90e1273",
                    "lo": "c0258edc"
                  },
                  "itemIds": [
                    "00000000000000000000000000000001",
                    "00000000000000000000000000000002",
                    "00000000000000000000000000000003"
                  ]
                },
                {
                  "groupId": "000000000000000000000000000007dd",
                  "key": {
                    "dec": "17569372152942007697",
                    "hex": "f3d2f2e2f6bf9591",
                    "hi": "f3d2f2e2",
                    "lo": "f6bf9591"
                  },
                  "itemIds": [
                    "00000000000000000000000000000701"
                  ]
                }
              ],
              "order": [
                "00000000000000000000000000000301",
                "00000000000000000000000000000302",
                "00000000000000000000000000000001",
                "00000000000000000000000000000002",
                "00000000000000000000000000000003",
                "00000000000000000000000000000701"
              ],
              "runs": [
                [
                  "00000000000000000000000000000301",
                  "00000000000000000000000000000302"
                ],
                [
                  "00000000000000000000000000000001",
                  "00000000000000000000000000000002",
                  "00000000000000000000000000000003"
                ],
                [
                  "00000000000000000000000000000701"
                ]
              ]
            },
            {
              "name": "randomInOrder, same pool listed in reverse",
              "mode": "randomInOrder",
              "blockLength": 2,
              "items": [
                {
                  "id": "00000000000000000000000000000701",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000007dd",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-03",
                  "season": 1,
                  "episode": 3
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-02",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000000000000000000302",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000003f5",
                  "premiere": "2020-01-08",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000000000000000000301",
                  "kind": "episode",
                  "seriesId": "000000000000000000000000000003f5",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                }
              ],
              "seed": {
                "dec": "8",
                "hex": "0000000000000008",
                "hi": "00000000",
                "lo": "00000008"
              },
              "groups": [
                {
                  "groupId": "000000000000000000000000000003f5",
                  "key": {
                    "dec": "6698989484632538537",
                    "hex": "5cf7977189997da9",
                    "hi": "5cf79771",
                    "lo": "89997da9"
                  },
                  "itemIds": [
                    "00000000000000000000000000000301",
                    "00000000000000000000000000000302"
                  ]
                },
                {
                  "groupId": "0000000000000000000000000000000d",
                  "key": {
                    "dec": "14487537339651690204",
                    "hex": "c90e1273c0258edc",
                    "hi": "c90e1273",
                    "lo": "c0258edc"
                  },
                  "itemIds": [
                    "00000000000000000000000000000001",
                    "00000000000000000000000000000002",
                    "00000000000000000000000000000003"
                  ]
                },
                {
                  "groupId": "000000000000000000000000000007dd",
                  "key": {
                    "dec": "17569372152942007697",
                    "hex": "f3d2f2e2f6bf9591",
                    "hi": "f3d2f2e2",
                    "lo": "f6bf9591"
                  },
                  "itemIds": [
                    "00000000000000000000000000000701"
                  ]
                }
              ],
              "order": [
                "00000000000000000000000000000301",
                "00000000000000000000000000000302",
                "00000000000000000000000000000001",
                "00000000000000000000000000000002",
                "00000000000000000000000000000701",
                "00000000000000000000000000000003"
              ],
              "runs": [
                [
                  "00000000000000000000000000000301",
                  "00000000000000000000000000000302"
                ],
                [
                  "00000000000000000000000000000001",
                  "00000000000000000000000000000002"
                ],
                [
                  "00000000000000000000000000000701"
                ],
                [
                  "00000000000000000000000000000003"
                ]
              ]
            },
            {
              "name": "randomInOrder, movies, a series and a series-less episode; high-bit group keys",
              "mode": "randomInOrder",
              "blockLength": 1,
              "note": "Group keys include values with the high bit set. A signed compare gives a different group order. Group d lists its episodes in date order (f before e).",
              "items": [
                {
                  "id": "d1024a5fad64d71711402f2430284f2c",
                  "kind": "movie",
                  "premiere": "2018-03-01"
                },
                {
                  "id": "19e7518defa0f0b047784b983a872ed6",
                  "kind": "movie",
                  "premiere": "2018-04-01"
                },
                {
                  "id": "bba2aa66f998b193225ac33c6d679b92",
                  "kind": "movie",
                  "premiere": "2018-05-01"
                },
                {
                  "id": "230275d791e422cccd19f198025ce714",
                  "kind": "movie",
                  "premiere": "2018-06-01"
                },
                {
                  "id": "b32f0f46fbfbccde9089f05fbf88f8e7",
                  "kind": "movie",
                  "premiere": "2018-07-01"
                },
                {
                  "id": "f9364c1f89270349738032f6d1fb2e3a",
                  "kind": "movie",
                  "premiere": "2018-08-01"
                },
                {
                  "id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "kind": "episode",
                  "seriesId": "dddddddddddddddddddddddddddddddd",
                  "premiere": "2019-01-02",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "ffffffffffffffffffffffffffffffff",
                  "kind": "episode",
                  "seriesId": "dddddddddddddddddddddddddddddddd",
                  "premiere": "2019-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "99999999999999999999999999999999",
                  "kind": "episode",
                  "premiere": "2019-03-03",
                  "season": 1,
                  "episode": 1
                }
              ],
              "seed": {
                "dec": "1",
                "hex": "0000000000000001",
                "hi": "00000000",
                "lo": "00000001"
              },
              "groups": [
                {
                  "groupId": "b32f0f46fbfbccde9089f05fbf88f8e7",
                  "key": {
                    "dec": "1860318910891112495",
                    "hex": "19d12e316c9b382f",
                    "hi": "19d12e31",
                    "lo": "6c9b382f"
                  },
                  "itemIds": [
                    "b32f0f46fbfbccde9089f05fbf88f8e7"
                  ]
                },
                {
                  "groupId": "19e7518defa0f0b047784b983a872ed6",
                  "key": {
                    "dec": "5008445616701393780",
                    "hex": "458192c0a4594b74",
                    "hi": "458192c0",
                    "lo": "a4594b74"
                  },
                  "itemIds": [
                    "19e7518defa0f0b047784b983a872ed6"
                  ]
                },
                {
                  "groupId": "f9364c1f89270349738032f6d1fb2e3a",
                  "key": {
                    "dec": "8617446427491774473",
                    "hex": "779751de5d9b3009",
                    "hi": "779751de",
                    "lo": "5d9b3009"
                  },
                  "itemIds": [
                    "f9364c1f89270349738032f6d1fb2e3a"
                  ]
                },
                {
                  "groupId": "dddddddddddddddddddddddddddddddd",
                  "key": {
                    "dec": "12155056006880296505",
                    "hex": "a8af6f09bb13fe39",
                    "hi": "a8af6f09",
                    "lo": "bb13fe39"
                  },
                  "itemIds": [
                    "ffffffffffffffffffffffffffffffff",
                    "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                  ]
                },
                {
                  "groupId": "99999999999999999999999999999999",
                  "key": {
                    "dec": "13214696376612677773",
                    "hex": "b76408570ea3e88d",
                    "hi": "b7640857",
                    "lo": "0ea3e88d"
                  },
                  "itemIds": [
                    "99999999999999999999999999999999"
                  ]
                },
                {
                  "groupId": "d1024a5fad64d71711402f2430284f2c",
                  "key": {
                    "dec": "14759382949922556926",
                    "hex": "ccd3dc982a174bfe",
                    "hi": "ccd3dc98",
                    "lo": "2a174bfe"
                  },
                  "itemIds": [
                    "d1024a5fad64d71711402f2430284f2c"
                  ]
                },
                {
                  "groupId": "bba2aa66f998b193225ac33c6d679b92",
                  "key": {
                    "dec": "16008330528603928837",
                    "hex": "de2903cccc7a7905",
                    "hi": "de2903cc",
                    "lo": "cc7a7905"
                  },
                  "itemIds": [
                    "bba2aa66f998b193225ac33c6d679b92"
                  ]
                },
                {
                  "groupId": "230275d791e422cccd19f198025ce714",
                  "key": {
                    "dec": "17701142855659437072",
                    "hex": "f5a717a47e063c10",
                    "hi": "f5a717a4",
                    "lo": "7e063c10"
                  },
                  "itemIds": [
                    "230275d791e422cccd19f198025ce714"
                  ]
                }
              ],
              "order": [
                "b32f0f46fbfbccde9089f05fbf88f8e7",
                "19e7518defa0f0b047784b983a872ed6",
                "f9364c1f89270349738032f6d1fb2e3a",
                "ffffffffffffffffffffffffffffffff",
                "99999999999999999999999999999999",
                "d1024a5fad64d71711402f2430284f2c",
                "bba2aa66f998b193225ac33c6d679b92",
                "230275d791e422cccd19f198025ce714",
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
              ],
              "runs": [
                [
                  "b32f0f46fbfbccde9089f05fbf88f8e7"
                ],
                [
                  "19e7518defa0f0b047784b983a872ed6"
                ],
                [
                  "f9364c1f89270349738032f6d1fb2e3a"
                ],
                [
                  "ffffffffffffffffffffffffffffffff"
                ],
                [
                  "99999999999999999999999999999999"
                ],
                [
                  "d1024a5fad64d71711402f2430284f2c"
                ],
                [
                  "bba2aa66f998b193225ac33c6d679b92"
                ],
                [
                  "230275d791e422cccd19f198025ce714"
                ],
                [
                  "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                ]
              ]
            },
            {
              "name": "randomInOrder, same, blockLength 2",
              "mode": "randomInOrder",
              "blockLength": 2,
              "items": [
                {
                  "id": "d1024a5fad64d71711402f2430284f2c",
                  "kind": "movie",
                  "premiere": "2018-03-01"
                },
                {
                  "id": "19e7518defa0f0b047784b983a872ed6",
                  "kind": "movie",
                  "premiere": "2018-04-01"
                },
                {
                  "id": "bba2aa66f998b193225ac33c6d679b92",
                  "kind": "movie",
                  "premiere": "2018-05-01"
                },
                {
                  "id": "230275d791e422cccd19f198025ce714",
                  "kind": "movie",
                  "premiere": "2018-06-01"
                },
                {
                  "id": "b32f0f46fbfbccde9089f05fbf88f8e7",
                  "kind": "movie",
                  "premiere": "2018-07-01"
                },
                {
                  "id": "f9364c1f89270349738032f6d1fb2e3a",
                  "kind": "movie",
                  "premiere": "2018-08-01"
                },
                {
                  "id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "kind": "episode",
                  "seriesId": "dddddddddddddddddddddddddddddddd",
                  "premiere": "2019-01-02",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "ffffffffffffffffffffffffffffffff",
                  "kind": "episode",
                  "seriesId": "dddddddddddddddddddddddddddddddd",
                  "premiere": "2019-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "99999999999999999999999999999999",
                  "kind": "episode",
                  "premiere": "2019-03-03",
                  "season": 1,
                  "episode": 1
                }
              ],
              "seed": {
                "dec": "1",
                "hex": "0000000000000001",
                "hi": "00000000",
                "lo": "00000001"
              },
              "groups": [
                {
                  "groupId": "b32f0f46fbfbccde9089f05fbf88f8e7",
                  "key": {
                    "dec": "1860318910891112495",
                    "hex": "19d12e316c9b382f",
                    "hi": "19d12e31",
                    "lo": "6c9b382f"
                  },
                  "itemIds": [
                    "b32f0f46fbfbccde9089f05fbf88f8e7"
                  ]
                },
                {
                  "groupId": "19e7518defa0f0b047784b983a872ed6",
                  "key": {
                    "dec": "5008445616701393780",
                    "hex": "458192c0a4594b74",
                    "hi": "458192c0",
                    "lo": "a4594b74"
                  },
                  "itemIds": [
                    "19e7518defa0f0b047784b983a872ed6"
                  ]
                },
                {
                  "groupId": "f9364c1f89270349738032f6d1fb2e3a",
                  "key": {
                    "dec": "8617446427491774473",
                    "hex": "779751de5d9b3009",
                    "hi": "779751de",
                    "lo": "5d9b3009"
                  },
                  "itemIds": [
                    "f9364c1f89270349738032f6d1fb2e3a"
                  ]
                },
                {
                  "groupId": "dddddddddddddddddddddddddddddddd",
                  "key": {
                    "dec": "12155056006880296505",
                    "hex": "a8af6f09bb13fe39",
                    "hi": "a8af6f09",
                    "lo": "bb13fe39"
                  },
                  "itemIds": [
                    "ffffffffffffffffffffffffffffffff",
                    "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                  ]
                },
                {
                  "groupId": "99999999999999999999999999999999",
                  "key": {
                    "dec": "13214696376612677773",
                    "hex": "b76408570ea3e88d",
                    "hi": "b7640857",
                    "lo": "0ea3e88d"
                  },
                  "itemIds": [
                    "99999999999999999999999999999999"
                  ]
                },
                {
                  "groupId": "d1024a5fad64d71711402f2430284f2c",
                  "key": {
                    "dec": "14759382949922556926",
                    "hex": "ccd3dc982a174bfe",
                    "hi": "ccd3dc98",
                    "lo": "2a174bfe"
                  },
                  "itemIds": [
                    "d1024a5fad64d71711402f2430284f2c"
                  ]
                },
                {
                  "groupId": "bba2aa66f998b193225ac33c6d679b92",
                  "key": {
                    "dec": "16008330528603928837",
                    "hex": "de2903cccc7a7905",
                    "hi": "de2903cc",
                    "lo": "cc7a7905"
                  },
                  "itemIds": [
                    "bba2aa66f998b193225ac33c6d679b92"
                  ]
                },
                {
                  "groupId": "230275d791e422cccd19f198025ce714",
                  "key": {
                    "dec": "17701142855659437072",
                    "hex": "f5a717a47e063c10",
                    "hi": "f5a717a4",
                    "lo": "7e063c10"
                  },
                  "itemIds": [
                    "230275d791e422cccd19f198025ce714"
                  ]
                }
              ],
              "order": [
                "b32f0f46fbfbccde9089f05fbf88f8e7",
                "19e7518defa0f0b047784b983a872ed6",
                "f9364c1f89270349738032f6d1fb2e3a",
                "ffffffffffffffffffffffffffffffff",
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "99999999999999999999999999999999",
                "d1024a5fad64d71711402f2430284f2c",
                "bba2aa66f998b193225ac33c6d679b92",
                "230275d791e422cccd19f198025ce714"
              ],
              "runs": [
                [
                  "b32f0f46fbfbccde9089f05fbf88f8e7"
                ],
                [
                  "19e7518defa0f0b047784b983a872ed6"
                ],
                [
                  "f9364c1f89270349738032f6d1fb2e3a"
                ],
                [
                  "ffffffffffffffffffffffffffffffff",
                  "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                ],
                [
                  "99999999999999999999999999999999"
                ],
                [
                  "d1024a5fad64d71711402f2430284f2c"
                ],
                [
                  "bba2aa66f998b193225ac33c6d679b92"
                ],
                [
                  "230275d791e422cccd19f198025ce714"
                ]
              ]
            },
            {
              "name": "randomInOrder, forced group-key tie (equal fnv1a64), seed 0",
              "mode": "randomInOrder",
              "blockLength": 1,
              "note": "TIE_A and TIE_B hash alike, so their group keys are equal for every seed; the smaller group ID bytes (b3b8...) comes first.",
              "items": [
                {
                  "id": "bf13eaba83dea4340123456789abcdef",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "b3b828bb3655e2a70123456789abcdef",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                }
              ],
              "seed": {
                "dec": "0",
                "hex": "0000000000000000",
                "hi": "00000000",
                "lo": "00000000"
              },
              "groups": [
                {
                  "groupId": "11111111111111111111111111111111",
                  "key": {
                    "dec": "9834741923131099073",
                    "hex": "887c05ac21df07c1",
                    "hi": "887c05ac",
                    "lo": "21df07c1"
                  },
                  "itemIds": [
                    "11111111111111111111111111111111"
                  ]
                },
                {
                  "groupId": "b3b828bb3655e2a70123456789abcdef",
                  "key": {
                    "dec": "14259928744909141981",
                    "hex": "c5e571a422b0f7dd",
                    "hi": "c5e571a4",
                    "lo": "22b0f7dd"
                  },
                  "itemIds": [
                    "b3b828bb3655e2a70123456789abcdef"
                  ]
                },
                {
                  "groupId": "bf13eaba83dea4340123456789abcdef",
                  "key": {
                    "dec": "14259928744909141981",
                    "hex": "c5e571a422b0f7dd",
                    "hi": "c5e571a4",
                    "lo": "22b0f7dd"
                  },
                  "itemIds": [
                    "bf13eaba83dea4340123456789abcdef"
                  ]
                }
              ],
              "order": [
                "11111111111111111111111111111111",
                "b3b828bb3655e2a70123456789abcdef",
                "bf13eaba83dea4340123456789abcdef"
              ],
              "runs": [
                [
                  "11111111111111111111111111111111"
                ],
                [
                  "b3b828bb3655e2a70123456789abcdef"
                ],
                [
                  "bf13eaba83dea4340123456789abcdef"
                ]
              ]
            },
            {
              "name": "randomInOrder, forced group-key tie (equal fnv1a64), seed 18446744073709551615",
              "mode": "randomInOrder",
              "blockLength": 1,
              "note": "TIE_A and TIE_B hash alike, so their group keys are equal for every seed; the smaller group ID bytes (b3b8...) comes first.",
              "items": [
                {
                  "id": "bf13eaba83dea4340123456789abcdef",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "b3b828bb3655e2a70123456789abcdef",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                }
              ],
              "seed": {
                "dec": "18446744073709551615",
                "hex": "ffffffffffffffff",
                "hi": "ffffffff",
                "lo": "ffffffff"
              },
              "groups": [
                {
                  "groupId": "b3b828bb3655e2a70123456789abcdef",
                  "key": {
                    "dec": "10509300572702129886",
                    "hex": "91d88930b1b83ede",
                    "hi": "91d88930",
                    "lo": "b1b83ede"
                  },
                  "itemIds": [
                    "b3b828bb3655e2a70123456789abcdef"
                  ]
                },
                {
                  "groupId": "bf13eaba83dea4340123456789abcdef",
                  "key": {
                    "dec": "10509300572702129886",
                    "hex": "91d88930b1b83ede",
                    "hi": "91d88930",
                    "lo": "b1b83ede"
                  },
                  "itemIds": [
                    "bf13eaba83dea4340123456789abcdef"
                  ]
                },
                {
                  "groupId": "11111111111111111111111111111111",
                  "key": {
                    "dec": "15237655279116691516",
                    "hex": "d37706be699b283c",
                    "hi": "d37706be",
                    "lo": "699b283c"
                  },
                  "itemIds": [
                    "11111111111111111111111111111111"
                  ]
                }
              ],
              "order": [
                "b3b828bb3655e2a70123456789abcdef",
                "bf13eaba83dea4340123456789abcdef",
                "11111111111111111111111111111111"
              ],
              "runs": [
                [
                  "b3b828bb3655e2a70123456789abcdef"
                ],
                [
                  "bf13eaba83dea4340123456789abcdef"
                ],
                [
                  "11111111111111111111111111111111"
                ]
              ]
            },
            {
              "name": "randomInOrder, empty pool",
              "mode": "randomInOrder",
              "blockLength": 1,
              "items": [],
              "seed": {
                "dec": "3",
                "hex": "0000000000000003",
                "hi": "00000000",
                "lo": "00000003"
              },
              "groups": [],
              "order": [],
              "runs": []
            },
            {
              "name": "randomInOrder, one group, blockLength 2 (last turn is short)",
              "mode": "randomInOrder",
              "blockLength": 2,
              "items": [
                {
                  "id": "00000000000000000000000000000001",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-01",
                  "season": 1,
                  "episode": 1
                },
                {
                  "id": "00000000000000000000000000000002",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-02",
                  "season": 1,
                  "episode": 2
                },
                {
                  "id": "00000000000000000000000000000003",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-03",
                  "season": 1,
                  "episode": 3
                },
                {
                  "id": "00000000000000000000000000000004",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-04",
                  "season": 1,
                  "episode": 4
                },
                {
                  "id": "00000000000000000000000000000005",
                  "kind": "episode",
                  "seriesId": "0000000000000000000000000000000d",
                  "premiere": "2020-01-05",
                  "season": 1,
                  "episode": 5
                }
              ],
              "seed": {
                "dec": "3",
                "hex": "0000000000000003",
                "hi": "00000000",
                "lo": "00000003"
              },
              "groups": [
                {
                  "groupId": "0000000000000000000000000000000d",
                  "key": {
                    "dec": "6842684036298448297",
                    "hex": "5ef618e07dce1da9",
                    "hi": "5ef618e0",
                    "lo": "7dce1da9"
                  },
                  "itemIds": [
                    "00000000000000000000000000000001",
                    "00000000000000000000000000000002",
                    "00000000000000000000000000000003",
                    "00000000000000000000000000000004",
                    "00000000000000000000000000000005"
                  ]
                }
              ],
              "order": [
                "00000000000000000000000000000001",
                "00000000000000000000000000000002",
                "00000000000000000000000000000003",
                "00000000000000000000000000000004",
                "00000000000000000000000000000005"
              ],
              "runs": [
                [
                  "00000000000000000000000000000001",
                  "00000000000000000000000000000002"
                ],
                [
                  "00000000000000000000000000000003",
                  "00000000000000000000000000000004"
                ],
                [
                  "00000000000000000000000000000005"
                ]
              ]
            },
            {
              "name": "runs, chronological pool of 9, blockLength 1",
              "mode": "chronological",
              "blockLength": 1,
              "items": [
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "22222222222222222222222222222222",
                  "kind": "movie",
                  "premiere": "2020-01-02"
                },
                {
                  "id": "33333333333333333333333333333333",
                  "kind": "movie",
                  "premiere": "2020-01-03"
                },
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "movie",
                  "premiere": "2020-01-04"
                },
                {
                  "id": "55555555555555555555555555555555",
                  "kind": "movie",
                  "premiere": "2020-01-05"
                },
                {
                  "id": "66666666666666666666666666666666",
                  "kind": "movie",
                  "premiere": "2020-01-06"
                },
                {
                  "id": "77777777777777777777777777777777",
                  "kind": "movie",
                  "premiere": "2020-01-07"
                },
                {
                  "id": "88888888888888888888888888888888",
                  "kind": "movie",
                  "premiere": "2020-01-08"
                },
                {
                  "id": "99999999999999999999999999999999",
                  "kind": "movie",
                  "premiere": "2020-01-09"
                }
              ],
              "keys": [
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "11111111111111111111111111111111",
                    "season": null,
                    "episode": null,
                    "id": "11111111111111111111111111111111"
                  }
                },
                {
                  "id": "22222222222222222222222222222222",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-02",
                    "groupId": "22222222222222222222222222222222",
                    "season": null,
                    "episode": null,
                    "id": "22222222222222222222222222222222"
                  }
                },
                {
                  "id": "33333333333333333333333333333333",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-03",
                    "groupId": "33333333333333333333333333333333",
                    "season": null,
                    "episode": null,
                    "id": "33333333333333333333333333333333"
                  }
                },
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-04",
                    "groupId": "44444444444444444444444444444444",
                    "season": null,
                    "episode": null,
                    "id": "44444444444444444444444444444444"
                  }
                },
                {
                  "id": "55555555555555555555555555555555",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-05",
                    "groupId": "55555555555555555555555555555555",
                    "season": null,
                    "episode": null,
                    "id": "55555555555555555555555555555555"
                  }
                },
                {
                  "id": "66666666666666666666666666666666",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-06",
                    "groupId": "66666666666666666666666666666666",
                    "season": null,
                    "episode": null,
                    "id": "66666666666666666666666666666666"
                  }
                },
                {
                  "id": "77777777777777777777777777777777",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-07",
                    "groupId": "77777777777777777777777777777777",
                    "season": null,
                    "episode": null,
                    "id": "77777777777777777777777777777777"
                  }
                },
                {
                  "id": "88888888888888888888888888888888",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-08",
                    "groupId": "88888888888888888888888888888888",
                    "season": null,
                    "episode": null,
                    "id": "88888888888888888888888888888888"
                  }
                },
                {
                  "id": "99999999999999999999999999999999",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-09",
                    "groupId": "99999999999999999999999999999999",
                    "season": null,
                    "episode": null,
                    "id": "99999999999999999999999999999999"
                  }
                }
              ],
              "order": [
                "11111111111111111111111111111111",
                "22222222222222222222222222222222",
                "33333333333333333333333333333333",
                "44444444444444444444444444444444",
                "55555555555555555555555555555555",
                "66666666666666666666666666666666",
                "77777777777777777777777777777777",
                "88888888888888888888888888888888",
                "99999999999999999999999999999999"
              ],
              "runs": [
                [
                  "11111111111111111111111111111111"
                ],
                [
                  "22222222222222222222222222222222"
                ],
                [
                  "33333333333333333333333333333333"
                ],
                [
                  "44444444444444444444444444444444"
                ],
                [
                  "55555555555555555555555555555555"
                ],
                [
                  "66666666666666666666666666666666"
                ],
                [
                  "77777777777777777777777777777777"
                ],
                [
                  "88888888888888888888888888888888"
                ],
                [
                  "99999999999999999999999999999999"
                ]
              ]
            },
            {
              "name": "runs, chronological pool of 9, blockLength 2",
              "mode": "chronological",
              "blockLength": 2,
              "note": "The cycle's runs repeat from the first item each cycle; a run never spans the wrap.",
              "items": [
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "22222222222222222222222222222222",
                  "kind": "movie",
                  "premiere": "2020-01-02"
                },
                {
                  "id": "33333333333333333333333333333333",
                  "kind": "movie",
                  "premiere": "2020-01-03"
                },
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "movie",
                  "premiere": "2020-01-04"
                },
                {
                  "id": "55555555555555555555555555555555",
                  "kind": "movie",
                  "premiere": "2020-01-05"
                },
                {
                  "id": "66666666666666666666666666666666",
                  "kind": "movie",
                  "premiere": "2020-01-06"
                },
                {
                  "id": "77777777777777777777777777777777",
                  "kind": "movie",
                  "premiere": "2020-01-07"
                },
                {
                  "id": "88888888888888888888888888888888",
                  "kind": "movie",
                  "premiere": "2020-01-08"
                },
                {
                  "id": "99999999999999999999999999999999",
                  "kind": "movie",
                  "premiere": "2020-01-09"
                }
              ],
              "keys": [
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "11111111111111111111111111111111",
                    "season": null,
                    "episode": null,
                    "id": "11111111111111111111111111111111"
                  }
                },
                {
                  "id": "22222222222222222222222222222222",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-02",
                    "groupId": "22222222222222222222222222222222",
                    "season": null,
                    "episode": null,
                    "id": "22222222222222222222222222222222"
                  }
                },
                {
                  "id": "33333333333333333333333333333333",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-03",
                    "groupId": "33333333333333333333333333333333",
                    "season": null,
                    "episode": null,
                    "id": "33333333333333333333333333333333"
                  }
                },
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-04",
                    "groupId": "44444444444444444444444444444444",
                    "season": null,
                    "episode": null,
                    "id": "44444444444444444444444444444444"
                  }
                },
                {
                  "id": "55555555555555555555555555555555",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-05",
                    "groupId": "55555555555555555555555555555555",
                    "season": null,
                    "episode": null,
                    "id": "55555555555555555555555555555555"
                  }
                },
                {
                  "id": "66666666666666666666666666666666",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-06",
                    "groupId": "66666666666666666666666666666666",
                    "season": null,
                    "episode": null,
                    "id": "66666666666666666666666666666666"
                  }
                },
                {
                  "id": "77777777777777777777777777777777",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-07",
                    "groupId": "77777777777777777777777777777777",
                    "season": null,
                    "episode": null,
                    "id": "77777777777777777777777777777777"
                  }
                },
                {
                  "id": "88888888888888888888888888888888",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-08",
                    "groupId": "88888888888888888888888888888888",
                    "season": null,
                    "episode": null,
                    "id": "88888888888888888888888888888888"
                  }
                },
                {
                  "id": "99999999999999999999999999999999",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-09",
                    "groupId": "99999999999999999999999999999999",
                    "season": null,
                    "episode": null,
                    "id": "99999999999999999999999999999999"
                  }
                }
              ],
              "order": [
                "11111111111111111111111111111111",
                "22222222222222222222222222222222",
                "33333333333333333333333333333333",
                "44444444444444444444444444444444",
                "55555555555555555555555555555555",
                "66666666666666666666666666666666",
                "77777777777777777777777777777777",
                "88888888888888888888888888888888",
                "99999999999999999999999999999999"
              ],
              "runs": [
                [
                  "11111111111111111111111111111111",
                  "22222222222222222222222222222222"
                ],
                [
                  "33333333333333333333333333333333",
                  "44444444444444444444444444444444"
                ],
                [
                  "55555555555555555555555555555555",
                  "66666666666666666666666666666666"
                ],
                [
                  "77777777777777777777777777777777",
                  "88888888888888888888888888888888"
                ],
                [
                  "99999999999999999999999999999999"
                ]
              ]
            },
            {
              "name": "runs, chronological pool of 9, blockLength 3",
              "mode": "chronological",
              "blockLength": 3,
              "note": "The cycle's runs repeat from the first item each cycle; a run never spans the wrap.",
              "items": [
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "22222222222222222222222222222222",
                  "kind": "movie",
                  "premiere": "2020-01-02"
                },
                {
                  "id": "33333333333333333333333333333333",
                  "kind": "movie",
                  "premiere": "2020-01-03"
                },
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "movie",
                  "premiere": "2020-01-04"
                },
                {
                  "id": "55555555555555555555555555555555",
                  "kind": "movie",
                  "premiere": "2020-01-05"
                },
                {
                  "id": "66666666666666666666666666666666",
                  "kind": "movie",
                  "premiere": "2020-01-06"
                },
                {
                  "id": "77777777777777777777777777777777",
                  "kind": "movie",
                  "premiere": "2020-01-07"
                },
                {
                  "id": "88888888888888888888888888888888",
                  "kind": "movie",
                  "premiere": "2020-01-08"
                },
                {
                  "id": "99999999999999999999999999999999",
                  "kind": "movie",
                  "premiere": "2020-01-09"
                }
              ],
              "keys": [
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "11111111111111111111111111111111",
                    "season": null,
                    "episode": null,
                    "id": "11111111111111111111111111111111"
                  }
                },
                {
                  "id": "22222222222222222222222222222222",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-02",
                    "groupId": "22222222222222222222222222222222",
                    "season": null,
                    "episode": null,
                    "id": "22222222222222222222222222222222"
                  }
                },
                {
                  "id": "33333333333333333333333333333333",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-03",
                    "groupId": "33333333333333333333333333333333",
                    "season": null,
                    "episode": null,
                    "id": "33333333333333333333333333333333"
                  }
                },
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-04",
                    "groupId": "44444444444444444444444444444444",
                    "season": null,
                    "episode": null,
                    "id": "44444444444444444444444444444444"
                  }
                },
                {
                  "id": "55555555555555555555555555555555",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-05",
                    "groupId": "55555555555555555555555555555555",
                    "season": null,
                    "episode": null,
                    "id": "55555555555555555555555555555555"
                  }
                },
                {
                  "id": "66666666666666666666666666666666",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-06",
                    "groupId": "66666666666666666666666666666666",
                    "season": null,
                    "episode": null,
                    "id": "66666666666666666666666666666666"
                  }
                },
                {
                  "id": "77777777777777777777777777777777",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-07",
                    "groupId": "77777777777777777777777777777777",
                    "season": null,
                    "episode": null,
                    "id": "77777777777777777777777777777777"
                  }
                },
                {
                  "id": "88888888888888888888888888888888",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-08",
                    "groupId": "88888888888888888888888888888888",
                    "season": null,
                    "episode": null,
                    "id": "88888888888888888888888888888888"
                  }
                },
                {
                  "id": "99999999999999999999999999999999",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-09",
                    "groupId": "99999999999999999999999999999999",
                    "season": null,
                    "episode": null,
                    "id": "99999999999999999999999999999999"
                  }
                }
              ],
              "order": [
                "11111111111111111111111111111111",
                "22222222222222222222222222222222",
                "33333333333333333333333333333333",
                "44444444444444444444444444444444",
                "55555555555555555555555555555555",
                "66666666666666666666666666666666",
                "77777777777777777777777777777777",
                "88888888888888888888888888888888",
                "99999999999999999999999999999999"
              ],
              "runs": [
                [
                  "11111111111111111111111111111111",
                  "22222222222222222222222222222222",
                  "33333333333333333333333333333333"
                ],
                [
                  "44444444444444444444444444444444",
                  "55555555555555555555555555555555",
                  "66666666666666666666666666666666"
                ],
                [
                  "77777777777777777777777777777777",
                  "88888888888888888888888888888888",
                  "99999999999999999999999999999999"
                ]
              ]
            },
            {
              "name": "runs, chronological pool of 9, blockLength 100",
              "mode": "chronological",
              "blockLength": 100,
              "items": [
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "22222222222222222222222222222222",
                  "kind": "movie",
                  "premiere": "2020-01-02"
                },
                {
                  "id": "33333333333333333333333333333333",
                  "kind": "movie",
                  "premiere": "2020-01-03"
                },
                {
                  "id": "44444444444444444444444444444444",
                  "kind": "movie",
                  "premiere": "2020-01-04"
                },
                {
                  "id": "55555555555555555555555555555555",
                  "kind": "movie",
                  "premiere": "2020-01-05"
                },
                {
                  "id": "66666666666666666666666666666666",
                  "kind": "movie",
                  "premiere": "2020-01-06"
                },
                {
                  "id": "77777777777777777777777777777777",
                  "kind": "movie",
                  "premiere": "2020-01-07"
                },
                {
                  "id": "88888888888888888888888888888888",
                  "kind": "movie",
                  "premiere": "2020-01-08"
                },
                {
                  "id": "99999999999999999999999999999999",
                  "kind": "movie",
                  "premiere": "2020-01-09"
                }
              ],
              "keys": [
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "11111111111111111111111111111111",
                    "season": null,
                    "episode": null,
                    "id": "11111111111111111111111111111111"
                  }
                },
                {
                  "id": "22222222222222222222222222222222",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-02",
                    "groupId": "22222222222222222222222222222222",
                    "season": null,
                    "episode": null,
                    "id": "22222222222222222222222222222222"
                  }
                },
                {
                  "id": "33333333333333333333333333333333",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-03",
                    "groupId": "33333333333333333333333333333333",
                    "season": null,
                    "episode": null,
                    "id": "33333333333333333333333333333333"
                  }
                },
                {
                  "id": "44444444444444444444444444444444",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-04",
                    "groupId": "44444444444444444444444444444444",
                    "season": null,
                    "episode": null,
                    "id": "44444444444444444444444444444444"
                  }
                },
                {
                  "id": "55555555555555555555555555555555",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-05",
                    "groupId": "55555555555555555555555555555555",
                    "season": null,
                    "episode": null,
                    "id": "55555555555555555555555555555555"
                  }
                },
                {
                  "id": "66666666666666666666666666666666",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-06",
                    "groupId": "66666666666666666666666666666666",
                    "season": null,
                    "episode": null,
                    "id": "66666666666666666666666666666666"
                  }
                },
                {
                  "id": "77777777777777777777777777777777",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-07",
                    "groupId": "77777777777777777777777777777777",
                    "season": null,
                    "episode": null,
                    "id": "77777777777777777777777777777777"
                  }
                },
                {
                  "id": "88888888888888888888888888888888",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-08",
                    "groupId": "88888888888888888888888888888888",
                    "season": null,
                    "episode": null,
                    "id": "88888888888888888888888888888888"
                  }
                },
                {
                  "id": "99999999999999999999999999999999",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-09",
                    "groupId": "99999999999999999999999999999999",
                    "season": null,
                    "episode": null,
                    "id": "99999999999999999999999999999999"
                  }
                }
              ],
              "order": [
                "11111111111111111111111111111111",
                "22222222222222222222222222222222",
                "33333333333333333333333333333333",
                "44444444444444444444444444444444",
                "55555555555555555555555555555555",
                "66666666666666666666666666666666",
                "77777777777777777777777777777777",
                "88888888888888888888888888888888",
                "99999999999999999999999999999999"
              ],
              "runs": [
                [
                  "11111111111111111111111111111111",
                  "22222222222222222222222222222222",
                  "33333333333333333333333333333333",
                  "44444444444444444444444444444444",
                  "55555555555555555555555555555555",
                  "66666666666666666666666666666666",
                  "77777777777777777777777777777777",
                  "88888888888888888888888888888888",
                  "99999999999999999999999999999999"
                ]
              ]
            },
            {
              "name": "runs, shuffle pool of 7, blockLength 1",
              "mode": "shuffle",
              "blockLength": 1,
              "items": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "kind": "movie"
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "kind": "movie"
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "kind": "movie"
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "kind": "movie"
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "kind": "movie"
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "kind": "movie"
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "fnv1a64": {
                    "dec": "3892281138735638033",
                    "hex": "36042912d04a6611",
                    "hi": "36042912",
                    "lo": "d04a6611"
                  },
                  "key": {
                    "dec": "13220412463707475644",
                    "hex": "b77857174b6e4ebc",
                    "hi": "b7785717",
                    "lo": "4b6e4ebc"
                  }
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "fnv1a64": {
                    "dec": "8956987785889047584",
                    "hex": "7c4d9cef636de420",
                    "hi": "7c4d9cef",
                    "lo": "636de420"
                  },
                  "key": {
                    "dec": "8174558478372347155",
                    "hex": "7171dda01b0a9d13",
                    "hi": "7171dda0",
                    "lo": "1b0a9d13"
                  }
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "fnv1a64": {
                    "dec": "3328394049101643263",
                    "hex": "2e30d4c0c93a3dff",
                    "hi": "2e30d4c0",
                    "lo": "c93a3dff"
                  },
                  "key": {
                    "dec": "9619433577062641928",
                    "hex": "857f17df5548b108",
                    "hi": "857f17df",
                    "lo": "5548b108"
                  }
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "fnv1a64": {
                    "dec": "4123138836933311984",
                    "hex": "393854ed4706fdf0",
                    "hi": "393854ed",
                    "lo": "4706fdf0"
                  },
                  "key": {
                    "dec": "8001706539345268569",
                    "hex": "6f0bc5b3e2984f59",
                    "hi": "6f0bc5b3",
                    "lo": "e2984f59"
                  }
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "fnv1a64": {
                    "dec": "13945209636678556684",
                    "hex": "c187564798945c0c",
                    "hi": "c1875647",
                    "lo": "98945c0c"
                  },
                  "key": {
                    "dec": "9937740757140020178",
                    "hex": "89e9f290f3409bd2",
                    "hi": "89e9f290",
                    "lo": "f3409bd2"
                  }
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "fnv1a64": {
                    "dec": "4312345867182901628",
                    "hex": "3bd887b7ea0f897c",
                    "hi": "3bd887b7",
                    "lo": "ea0f897c"
                  },
                  "key": {
                    "dec": "16966051428150877639",
                    "hex": "eb7385e247f11dc7",
                    "hi": "eb7385e2",
                    "lo": "47f11dc7"
                  }
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "fnv1a64": {
                    "dec": "768180060243188519",
                    "hex": "0aa91fb1de555b27",
                    "hi": "0aa91fb1",
                    "lo": "de555b27"
                  },
                  "key": {
                    "dec": "2139152374707679101",
                    "hex": "1dafcbc046e26f7d",
                    "hi": "1dafcbc0",
                    "lo": "46e26f7d"
                  }
                }
              ],
              "order": [
                "03ef5b87efea98e8e85c7afb627bf59e",
                "3fadb6bde9285e98abf42acc138fedbe",
                "b4dc9bd462de412bd1b89df7e5b467f9",
                "ca87cb278bbed782b41d1166f0fa7f73",
                "96403e918bdbd015740b401493c72543",
                "28bf8a80bc3eab521882c195e4347c74",
                "8e48a85c3260d9d0b24ce50ea345fe83"
              ],
              "runs": [
                [
                  "03ef5b87efea98e8e85c7afb627bf59e"
                ],
                [
                  "3fadb6bde9285e98abf42acc138fedbe"
                ],
                [
                  "b4dc9bd462de412bd1b89df7e5b467f9"
                ],
                [
                  "ca87cb278bbed782b41d1166f0fa7f73"
                ],
                [
                  "96403e918bdbd015740b401493c72543"
                ],
                [
                  "28bf8a80bc3eab521882c195e4347c74"
                ],
                [
                  "8e48a85c3260d9d0b24ce50ea345fe83"
                ]
              ]
            },
            {
              "name": "runs, shuffle pool of 7, blockLength 3",
              "mode": "shuffle",
              "blockLength": 3,
              "items": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "kind": "movie"
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "kind": "movie"
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "kind": "movie"
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "kind": "movie"
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "kind": "movie"
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "kind": "movie"
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "fnv1a64": {
                    "dec": "3892281138735638033",
                    "hex": "36042912d04a6611",
                    "hi": "36042912",
                    "lo": "d04a6611"
                  },
                  "key": {
                    "dec": "13220412463707475644",
                    "hex": "b77857174b6e4ebc",
                    "hi": "b7785717",
                    "lo": "4b6e4ebc"
                  }
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "fnv1a64": {
                    "dec": "8956987785889047584",
                    "hex": "7c4d9cef636de420",
                    "hi": "7c4d9cef",
                    "lo": "636de420"
                  },
                  "key": {
                    "dec": "8174558478372347155",
                    "hex": "7171dda01b0a9d13",
                    "hi": "7171dda0",
                    "lo": "1b0a9d13"
                  }
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "fnv1a64": {
                    "dec": "3328394049101643263",
                    "hex": "2e30d4c0c93a3dff",
                    "hi": "2e30d4c0",
                    "lo": "c93a3dff"
                  },
                  "key": {
                    "dec": "9619433577062641928",
                    "hex": "857f17df5548b108",
                    "hi": "857f17df",
                    "lo": "5548b108"
                  }
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "fnv1a64": {
                    "dec": "4123138836933311984",
                    "hex": "393854ed4706fdf0",
                    "hi": "393854ed",
                    "lo": "4706fdf0"
                  },
                  "key": {
                    "dec": "8001706539345268569",
                    "hex": "6f0bc5b3e2984f59",
                    "hi": "6f0bc5b3",
                    "lo": "e2984f59"
                  }
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "fnv1a64": {
                    "dec": "13945209636678556684",
                    "hex": "c187564798945c0c",
                    "hi": "c1875647",
                    "lo": "98945c0c"
                  },
                  "key": {
                    "dec": "9937740757140020178",
                    "hex": "89e9f290f3409bd2",
                    "hi": "89e9f290",
                    "lo": "f3409bd2"
                  }
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "fnv1a64": {
                    "dec": "4312345867182901628",
                    "hex": "3bd887b7ea0f897c",
                    "hi": "3bd887b7",
                    "lo": "ea0f897c"
                  },
                  "key": {
                    "dec": "16966051428150877639",
                    "hex": "eb7385e247f11dc7",
                    "hi": "eb7385e2",
                    "lo": "47f11dc7"
                  }
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "fnv1a64": {
                    "dec": "768180060243188519",
                    "hex": "0aa91fb1de555b27",
                    "hi": "0aa91fb1",
                    "lo": "de555b27"
                  },
                  "key": {
                    "dec": "2139152374707679101",
                    "hex": "1dafcbc046e26f7d",
                    "hi": "1dafcbc0",
                    "lo": "46e26f7d"
                  }
                }
              ],
              "order": [
                "03ef5b87efea98e8e85c7afb627bf59e",
                "3fadb6bde9285e98abf42acc138fedbe",
                "b4dc9bd462de412bd1b89df7e5b467f9",
                "ca87cb278bbed782b41d1166f0fa7f73",
                "96403e918bdbd015740b401493c72543",
                "28bf8a80bc3eab521882c195e4347c74",
                "8e48a85c3260d9d0b24ce50ea345fe83"
              ],
              "runs": [
                [
                  "03ef5b87efea98e8e85c7afb627bf59e",
                  "3fadb6bde9285e98abf42acc138fedbe",
                  "b4dc9bd462de412bd1b89df7e5b467f9"
                ],
                [
                  "ca87cb278bbed782b41d1166f0fa7f73",
                  "96403e918bdbd015740b401493c72543",
                  "28bf8a80bc3eab521882c195e4347c74"
                ],
                [
                  "8e48a85c3260d9d0b24ce50ea345fe83"
                ]
              ]
            },
            {
              "name": "runs, shuffle pool of 7, blockLength 7",
              "mode": "shuffle",
              "blockLength": 7,
              "items": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "kind": "movie"
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "kind": "movie"
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "kind": "movie"
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "kind": "movie"
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "kind": "movie"
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "kind": "movie"
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "fnv1a64": {
                    "dec": "3892281138735638033",
                    "hex": "36042912d04a6611",
                    "hi": "36042912",
                    "lo": "d04a6611"
                  },
                  "key": {
                    "dec": "13220412463707475644",
                    "hex": "b77857174b6e4ebc",
                    "hi": "b7785717",
                    "lo": "4b6e4ebc"
                  }
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "fnv1a64": {
                    "dec": "8956987785889047584",
                    "hex": "7c4d9cef636de420",
                    "hi": "7c4d9cef",
                    "lo": "636de420"
                  },
                  "key": {
                    "dec": "8174558478372347155",
                    "hex": "7171dda01b0a9d13",
                    "hi": "7171dda0",
                    "lo": "1b0a9d13"
                  }
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "fnv1a64": {
                    "dec": "3328394049101643263",
                    "hex": "2e30d4c0c93a3dff",
                    "hi": "2e30d4c0",
                    "lo": "c93a3dff"
                  },
                  "key": {
                    "dec": "9619433577062641928",
                    "hex": "857f17df5548b108",
                    "hi": "857f17df",
                    "lo": "5548b108"
                  }
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "fnv1a64": {
                    "dec": "4123138836933311984",
                    "hex": "393854ed4706fdf0",
                    "hi": "393854ed",
                    "lo": "4706fdf0"
                  },
                  "key": {
                    "dec": "8001706539345268569",
                    "hex": "6f0bc5b3e2984f59",
                    "hi": "6f0bc5b3",
                    "lo": "e2984f59"
                  }
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "fnv1a64": {
                    "dec": "13945209636678556684",
                    "hex": "c187564798945c0c",
                    "hi": "c1875647",
                    "lo": "98945c0c"
                  },
                  "key": {
                    "dec": "9937740757140020178",
                    "hex": "89e9f290f3409bd2",
                    "hi": "89e9f290",
                    "lo": "f3409bd2"
                  }
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "fnv1a64": {
                    "dec": "4312345867182901628",
                    "hex": "3bd887b7ea0f897c",
                    "hi": "3bd887b7",
                    "lo": "ea0f897c"
                  },
                  "key": {
                    "dec": "16966051428150877639",
                    "hex": "eb7385e247f11dc7",
                    "hi": "eb7385e2",
                    "lo": "47f11dc7"
                  }
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "fnv1a64": {
                    "dec": "768180060243188519",
                    "hex": "0aa91fb1de555b27",
                    "hi": "0aa91fb1",
                    "lo": "de555b27"
                  },
                  "key": {
                    "dec": "2139152374707679101",
                    "hex": "1dafcbc046e26f7d",
                    "hi": "1dafcbc0",
                    "lo": "46e26f7d"
                  }
                }
              ],
              "order": [
                "03ef5b87efea98e8e85c7afb627bf59e",
                "3fadb6bde9285e98abf42acc138fedbe",
                "b4dc9bd462de412bd1b89df7e5b467f9",
                "ca87cb278bbed782b41d1166f0fa7f73",
                "96403e918bdbd015740b401493c72543",
                "28bf8a80bc3eab521882c195e4347c74",
                "8e48a85c3260d9d0b24ce50ea345fe83"
              ],
              "runs": [
                [
                  "03ef5b87efea98e8e85c7afb627bf59e",
                  "3fadb6bde9285e98abf42acc138fedbe",
                  "b4dc9bd462de412bd1b89df7e5b467f9",
                  "ca87cb278bbed782b41d1166f0fa7f73",
                  "96403e918bdbd015740b401493c72543",
                  "28bf8a80bc3eab521882c195e4347c74",
                  "8e48a85c3260d9d0b24ce50ea345fe83"
                ]
              ]
            },
            {
              "name": "runs, shuffle pool of 7, blockLength 8",
              "mode": "shuffle",
              "blockLength": 8,
              "items": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "kind": "movie"
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "kind": "movie"
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "kind": "movie"
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "kind": "movie"
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "kind": "movie"
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "kind": "movie"
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "fnv1a64": {
                    "dec": "3892281138735638033",
                    "hex": "36042912d04a6611",
                    "hi": "36042912",
                    "lo": "d04a6611"
                  },
                  "key": {
                    "dec": "13220412463707475644",
                    "hex": "b77857174b6e4ebc",
                    "hi": "b7785717",
                    "lo": "4b6e4ebc"
                  }
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "fnv1a64": {
                    "dec": "8956987785889047584",
                    "hex": "7c4d9cef636de420",
                    "hi": "7c4d9cef",
                    "lo": "636de420"
                  },
                  "key": {
                    "dec": "8174558478372347155",
                    "hex": "7171dda01b0a9d13",
                    "hi": "7171dda0",
                    "lo": "1b0a9d13"
                  }
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "fnv1a64": {
                    "dec": "3328394049101643263",
                    "hex": "2e30d4c0c93a3dff",
                    "hi": "2e30d4c0",
                    "lo": "c93a3dff"
                  },
                  "key": {
                    "dec": "9619433577062641928",
                    "hex": "857f17df5548b108",
                    "hi": "857f17df",
                    "lo": "5548b108"
                  }
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "fnv1a64": {
                    "dec": "4123138836933311984",
                    "hex": "393854ed4706fdf0",
                    "hi": "393854ed",
                    "lo": "4706fdf0"
                  },
                  "key": {
                    "dec": "8001706539345268569",
                    "hex": "6f0bc5b3e2984f59",
                    "hi": "6f0bc5b3",
                    "lo": "e2984f59"
                  }
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "fnv1a64": {
                    "dec": "13945209636678556684",
                    "hex": "c187564798945c0c",
                    "hi": "c1875647",
                    "lo": "98945c0c"
                  },
                  "key": {
                    "dec": "9937740757140020178",
                    "hex": "89e9f290f3409bd2",
                    "hi": "89e9f290",
                    "lo": "f3409bd2"
                  }
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "fnv1a64": {
                    "dec": "4312345867182901628",
                    "hex": "3bd887b7ea0f897c",
                    "hi": "3bd887b7",
                    "lo": "ea0f897c"
                  },
                  "key": {
                    "dec": "16966051428150877639",
                    "hex": "eb7385e247f11dc7",
                    "hi": "eb7385e2",
                    "lo": "47f11dc7"
                  }
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "fnv1a64": {
                    "dec": "768180060243188519",
                    "hex": "0aa91fb1de555b27",
                    "hi": "0aa91fb1",
                    "lo": "de555b27"
                  },
                  "key": {
                    "dec": "2139152374707679101",
                    "hex": "1dafcbc046e26f7d",
                    "hi": "1dafcbc0",
                    "lo": "46e26f7d"
                  }
                }
              ],
              "order": [
                "03ef5b87efea98e8e85c7afb627bf59e",
                "3fadb6bde9285e98abf42acc138fedbe",
                "b4dc9bd462de412bd1b89df7e5b467f9",
                "ca87cb278bbed782b41d1166f0fa7f73",
                "96403e918bdbd015740b401493c72543",
                "28bf8a80bc3eab521882c195e4347c74",
                "8e48a85c3260d9d0b24ce50ea345fe83"
              ],
              "runs": [
                [
                  "03ef5b87efea98e8e85c7afb627bf59e",
                  "3fadb6bde9285e98abf42acc138fedbe",
                  "b4dc9bd462de412bd1b89df7e5b467f9",
                  "ca87cb278bbed782b41d1166f0fa7f73",
                  "96403e918bdbd015740b401493c72543",
                  "28bf8a80bc3eab521882c195e4347c74",
                  "8e48a85c3260d9d0b24ce50ea345fe83"
                ]
              ]
            },
            {
              "name": "runs, shuffle pool of 7, blockLength 100",
              "mode": "shuffle",
              "blockLength": 100,
              "items": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "kind": "movie"
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "kind": "movie"
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "kind": "movie"
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "kind": "movie"
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "kind": "movie"
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "kind": "movie"
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "kind": "movie"
                }
              ],
              "seedN": {
                "dec": "6238072747940578789",
                "hex": "5692161d100b05e5",
                "hi": "5692161d",
                "lo": "100b05e5"
              },
              "keys": [
                {
                  "id": "28bf8a80bc3eab521882c195e4347c74",
                  "fnv1a64": {
                    "dec": "3892281138735638033",
                    "hex": "36042912d04a6611",
                    "hi": "36042912",
                    "lo": "d04a6611"
                  },
                  "key": {
                    "dec": "13220412463707475644",
                    "hex": "b77857174b6e4ebc",
                    "hi": "b7785717",
                    "lo": "4b6e4ebc"
                  }
                },
                {
                  "id": "b4dc9bd462de412bd1b89df7e5b467f9",
                  "fnv1a64": {
                    "dec": "8956987785889047584",
                    "hex": "7c4d9cef636de420",
                    "hi": "7c4d9cef",
                    "lo": "636de420"
                  },
                  "key": {
                    "dec": "8174558478372347155",
                    "hex": "7171dda01b0a9d13",
                    "hi": "7171dda0",
                    "lo": "1b0a9d13"
                  }
                },
                {
                  "id": "ca87cb278bbed782b41d1166f0fa7f73",
                  "fnv1a64": {
                    "dec": "3328394049101643263",
                    "hex": "2e30d4c0c93a3dff",
                    "hi": "2e30d4c0",
                    "lo": "c93a3dff"
                  },
                  "key": {
                    "dec": "9619433577062641928",
                    "hex": "857f17df5548b108",
                    "hi": "857f17df",
                    "lo": "5548b108"
                  }
                },
                {
                  "id": "3fadb6bde9285e98abf42acc138fedbe",
                  "fnv1a64": {
                    "dec": "4123138836933311984",
                    "hex": "393854ed4706fdf0",
                    "hi": "393854ed",
                    "lo": "4706fdf0"
                  },
                  "key": {
                    "dec": "8001706539345268569",
                    "hex": "6f0bc5b3e2984f59",
                    "hi": "6f0bc5b3",
                    "lo": "e2984f59"
                  }
                },
                {
                  "id": "96403e918bdbd015740b401493c72543",
                  "fnv1a64": {
                    "dec": "13945209636678556684",
                    "hex": "c187564798945c0c",
                    "hi": "c1875647",
                    "lo": "98945c0c"
                  },
                  "key": {
                    "dec": "9937740757140020178",
                    "hex": "89e9f290f3409bd2",
                    "hi": "89e9f290",
                    "lo": "f3409bd2"
                  }
                },
                {
                  "id": "8e48a85c3260d9d0b24ce50ea345fe83",
                  "fnv1a64": {
                    "dec": "4312345867182901628",
                    "hex": "3bd887b7ea0f897c",
                    "hi": "3bd887b7",
                    "lo": "ea0f897c"
                  },
                  "key": {
                    "dec": "16966051428150877639",
                    "hex": "eb7385e247f11dc7",
                    "hi": "eb7385e2",
                    "lo": "47f11dc7"
                  }
                },
                {
                  "id": "03ef5b87efea98e8e85c7afb627bf59e",
                  "fnv1a64": {
                    "dec": "768180060243188519",
                    "hex": "0aa91fb1de555b27",
                    "hi": "0aa91fb1",
                    "lo": "de555b27"
                  },
                  "key": {
                    "dec": "2139152374707679101",
                    "hex": "1dafcbc046e26f7d",
                    "hi": "1dafcbc0",
                    "lo": "46e26f7d"
                  }
                }
              ],
              "order": [
                "03ef5b87efea98e8e85c7afb627bf59e",
                "3fadb6bde9285e98abf42acc138fedbe",
                "b4dc9bd462de412bd1b89df7e5b467f9",
                "ca87cb278bbed782b41d1166f0fa7f73",
                "96403e918bdbd015740b401493c72543",
                "28bf8a80bc3eab521882c195e4347c74",
                "8e48a85c3260d9d0b24ce50ea345fe83"
              ],
              "runs": [
                [
                  "03ef5b87efea98e8e85c7afb627bf59e",
                  "3fadb6bde9285e98abf42acc138fedbe",
                  "b4dc9bd462de412bd1b89df7e5b467f9",
                  "ca87cb278bbed782b41d1166f0fa7f73",
                  "96403e918bdbd015740b401493c72543",
                  "28bf8a80bc3eab521882c195e4347c74",
                  "8e48a85c3260d9d0b24ce50ea345fe83"
                ]
              ]
            },
            {
              "name": "runs, pool smaller than a run (2 items, blockLength 100)",
              "mode": "chronological",
              "blockLength": 100,
              "items": [
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                },
                {
                  "id": "22222222222222222222222222222222",
                  "kind": "movie",
                  "premiere": "2020-01-02"
                }
              ],
              "keys": [
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "11111111111111111111111111111111",
                    "season": null,
                    "episode": null,
                    "id": "11111111111111111111111111111111"
                  }
                },
                {
                  "id": "22222222222222222222222222222222",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-02",
                    "groupId": "22222222222222222222222222222222",
                    "season": null,
                    "episode": null,
                    "id": "22222222222222222222222222222222"
                  }
                }
              ],
              "order": [
                "11111111111111111111111111111111",
                "22222222222222222222222222222222"
              ],
              "runs": [
                [
                  "11111111111111111111111111111111",
                  "22222222222222222222222222222222"
                ]
              ]
            },
            {
              "name": "runs, single item, blockLength 2",
              "mode": "chronological",
              "blockLength": 2,
              "items": [
                {
                  "id": "11111111111111111111111111111111",
                  "kind": "movie",
                  "premiere": "2020-01-01"
                }
              ],
              "keys": [
                {
                  "id": "11111111111111111111111111111111",
                  "keyParts": {
                    "undated": 0,
                    "date": "2020-01-01",
                    "groupId": "11111111111111111111111111111111",
                    "season": null,
                    "episode": null,
                    "id": "11111111111111111111111111111111"
                  }
                }
              ],
              "order": [
                "11111111111111111111111111111111"
              ],
              "runs": [
                [
                  "11111111111111111111111111111111"
                ]
              ]
            },
            {
              "name": "runs, empty pool",
              "mode": "chronological",
              "blockLength": 3,
              "items": [],
              "keys": [],
              "order": [],
              "runs": []
            },
            {
              "name": "runs, chronological pool of 101, blockLength 100 (runs of 100 and 1)",
              "mode": "chronological",
              "blockLength": 100,
              "items": [
                {
                  "id": "a7a6d3b21ecb97ece699e345278de898",
                  "kind": "movie",
                  "premiere": "2015-01-01"
                },
                {
                  "id": "b5145f14d921c666172b3485e05703ad",
                  "kind": "movie",
                  "premiere": "2015-01-02"
                },
                {
                  "id": "a92d4e8a72a6344c6bc265fe18354c64",
                  "kind": "movie",
                  "premiere": "2015-01-03"
                },
                {
                  "id": "82770a37d755ceeb4e04e2c49ec8dad0",
                  "kind": "movie",
                  "premiere": "2015-01-04"
                },
                {
                  "id": "a6b897d8d30eac80e8cb9afac767b109",
                  "kind": "movie",
                  "premiere": "2015-01-05"
                },
                {
                  "id": "ed3e2b670029c22b209c26f8211bd185",
                  "kind": "movie",
                  "premiere": "2015-01-06"
                },
                {
                  "id": "96519dd7d49df4ae38e7c4b2e028a229",
                  "kind": "movie",
                  "premiere": "2015-01-07"
                },
                {
                  "id": "e65f073cd2135179c9a246f21baecb68",
                  "kind": "movie",
                  "premiere": "2015-01-08"
                },
                {
                  "id": "d3a8d03c36640b2f36491d4aa365d567",
                  "kind": "movie",
                  "premiere": "2015-01-09"
                },
                {
                  "id": "dda4f3b0b9e43fd108400fcc2ffa9314",
                  "kind": "movie",
                  "premiere": "2015-01-10"
                },
                {
                  "id": "64b1e3956840282786e6b7951c608613",
                  "kind": "movie",
                  "premiere": "2015-01-11"
                },
                {
                  "id": "eaaf4fbac1d1689223ad6bad6f5c8c81",
                  "kind": "movie",
                  "premiere": "2015-01-12"
                },
                {
                  "id": "8eaff63463a306f79c52a3eb4fcdc34d",
                  "kind": "movie",
                  "premiere": "2015-01-13"
                },
                {
                  "id": "70ea2dfd1920444c84dcc1c7b6773c56",
                  "kind": "movie",
                  "premiere": "2015-01-14"
                },
                {
                  "id": "45f7d13d91b9c52faf30118ddcc3ca47",
                  "kind": "movie",
                  "premiere": "2015-01-15"
                },
                {
                  "id": "6931b9e1122a75b26bb5ba8e4c5a049f",
                  "kind": "movie",
                  "premiere": "2015-01-16"
                },
                {
                  "id": "2eb67620ec65912fa3f6365bd15b8991",
                  "kind": "movie",
                  "premiere": "2015-01-17"
                },
                {
                  "id": "5fa4f116a892767a0f56e2e9b5aa2e5b",
                  "kind": "movie",
                  "premiere": "2015-01-18"
                },
                {
                  "id": "f0afa51599d0c810bb5fe8e953e15117",
                  "kind": "movie",
                  "premiere": "2015-01-19"
                },
                {
                  "id": "b7646777da99a215beab1149a28edcde",
                  "kind": "movie",
                  "premiere": "2015-01-20"
                },
                {
                  "id": "e9966f076d4f365c71f1f1d4ce980385",
                  "kind": "movie",
                  "premiere": "2015-01-21"
                },
                {
                  "id": "3cd2167a91a41946679737c796055769",
                  "kind": "movie",
                  "premiere": "2015-01-22"
                },
                {
                  "id": "437d563c56393be55564a7f784a78404",
                  "kind": "movie",
                  "premiere": "2015-01-23"
                },
                {
                  "id": "6ba8674fe24001fbb6986e3027ef2bf4",
                  "kind": "movie",
                  "premiere": "2015-01-24"
                },
                {
                  "id": "e1efefb5d14f69ead257429b2b5ffe4c",
                  "kind": "movie",
                  "premiere": "2015-01-25"
                },
                {
                  "id": "7e5423c80b38522d4997cf2cbecdfc4e",
                  "kind": "movie",
                  "premiere": "2015-01-26"
                },
                {
                  "id": "31d6e6002586bf15da2e5ee5a7779c01",
                  "kind": "movie",
                  "premiere": "2015-01-27"
                },
                {
                  "id": "338c507146283fb46602d201e324653f",
                  "kind": "movie",
                  "premiere": "2015-01-28"
                },
                {
                  "id": "73293223506027241e09f8a031519da4",
                  "kind": "movie",
                  "premiere": "2015-01-01"
                },
                {
                  "id": "ffdd8d3d3394cb38bebd1e51f35aee1b",
                  "kind": "movie",
                  "premiere": "2015-01-02"
                },
                {
                  "id": "18c55f6e6338e7c2e834d79d53fa4a45",
                  "kind": "movie",
                  "premiere": "2015-01-03"
                },
                {
                  "id": "0bb399b747cc503d8c4a24f5d94dda5d",
                  "kind": "movie",
                  "premiere": "2015-01-04"
                },
                {
                  "id": "97bd3790f9bd2a07d2c055787d9dfed8",
                  "kind": "movie",
                  "premiere": "2015-01-05"
                },
                {
                  "id": "7ce5b91ab44fc0ef31e087fbe3719033",
                  "kind": "movie",
                  "premiere": "2015-01-06"
                },
                {
                  "id": "7a7e59c35e2eb833c1b3282747fd4b1e",
                  "kind": "movie",
                  "premiere": "2015-01-07"
                },
                {
                  "id": "6b65d4a13883a66f7834ae378256d230",
                  "kind": "movie",
                  "premiere": "2015-01-08"
                },
                {
                  "id": "95641ffe56985d44ea0cf4c47365be7a",
                  "kind": "movie",
                  "premiere": "2015-01-09"
                },
                {
                  "id": "a1e60f6349a2794019749953a2fee1c4",
                  "kind": "movie",
                  "premiere": "2015-01-10"
                },
                {
                  "id": "ec963c514a97da2c28fea379cec620f5",
                  "kind": "movie",
                  "premiere": "2015-01-11"
                },
                {
                  "id": "6fcacb1ac9c332bdd4054b16fc69553b",
                  "kind": "movie",
                  "premiere": "2015-01-12"
                },
                {
                  "id": "e5fe80d24cace961fc0bf5d96fe78fdd",
                  "kind": "movie",
                  "premiere": "2015-01-13"
                },
                {
                  "id": "dd6d1caa0058aa2e369172bb8b08c845",
                  "kind": "movie",
                  "premiere": "2015-01-14"
                },
                {
                  "id": "c2220f5b4c2ca0f3c4b191830d022100",
                  "kind": "movie",
                  "premiere": "2015-01-15"
                },
                {
                  "id": "7de1334446061d637d19b6dd75f2ed57",
                  "kind": "movie",
                  "premiere": "2015-01-16"
                },
                {
                  "id": "94866d0599587e9e28cc5f8bb174ede4",
                  "kind": "movie",
                  "premiere": "2015-01-17"
                },
                {
                  "id": "90f506bb95a34ba816ecd10e03caed1d",
                  "kind": "movie",
                  "premiere": "2015-01-18"
                },
                {
                  "id": "b64bf615672484c77be98ccf039a0d67",
                  "kind": "movie",
                  "premiere": "2015-01-19"
                },
                {
                  "id": "01b969167aa6183b5778459bd2fcd10e",
                  "kind": "movie",
                  "premiere": "2015-01-20"
                },
                {
                  "id": "d9d25157778b9ce55318c937b13750c8",
                  "kind": "movie",
                  "premiere": "2015-01-21"
                },
                {
                  "id": "fdf673c62dd578782fb54c54d1eb0392",
                  "kind": "movie",
                  "premiere": "2015-01-22"
                },
                {
                  "id": "e525d2a3b2002e14953ddf9a1edc63d8",
                  "kind": "movie",
                  "premiere": "2015-01-23"
                },
                {
                  "id": "3841a405dd589bbae2e1275b61e743f7",
                  "kind": "movie",
                  "premiere": "2015-01-24"
                },
                {
                  "id": "3b55ac3c0aae92a68e2382c336e58251",
                  "kind": "movie",
                  "premiere": "2015-01-25"
                },
                {
                  "id": "7bbf20d6e01a99bc3a3593531d1f1b6c",
                  "kind": "movie",
                  "premiere": "2015-01-26"
                },
                {
                  "id": "c8762d996c1022282531983c5789d1f5",
                  "kind": "movie",
                  "premiere": "2015-01-27"
                },
                {
                  "id": "92235be7866e4acfce1da0c2af0dc5bf",
                  "kind": "movie",
                  "premiere": "2015-01-28"
                },
                {
                  "id": "5ad12dadec8778e5519302a2895c7074",
                  "kind": "movie",
                  "premiere": "2015-01-01"
                },
                {
                  "id": "d50dea4ca03db06f43b8b8a7175a72f1",
                  "kind": "movie",
                  "premiere": "2015-01-02"
                },
                {
                  "id": "0a507dda9b4a30d7514333d15cecb873",
                  "kind": "movie",
                  "premiere": "2015-01-03"
                },
                {
                  "id": "9583baf49c874fa402da1170236b0050",
                  "kind": "movie",
                  "premiere": "2015-01-04"
                },
                {
                  "id": "e95f1c4efcf85dee4020ff7f99c86b29",
                  "kind": "movie",
                  "premiere": "2015-01-05"
                },
                {
                  "id": "261292722d18549af2c58d69399523cc",
                  "kind": "movie",
                  "premiere": "2015-01-06"
                },
                {
                  "id": "bc77ecd519de60d369aa8d70d6650dbf",
                  "kind": "movie",
                  "premiere": "2015-01-07"
                },
                {
                  "id": "685737c9b6f865c99ea31916dd7e77f8",
                  "kind": "movie",
                  "premiere": "2015-01-08"
                },
                {
                  "id": "659f330f59cc9a066b94ce7b49bfeb8d",
                  "kind": "movie",
                  "premiere": "2015-01-09"
                },
                {
                  "id": "38061ed9d9f2585bd8f23ed1f19ffc17",
                  "kind": "movie",
                  "premiere": "2015-01-10"
                },
                {
                  "id": "956f09e9c07263301177bb475beab984",
                  "kind": "movie",
                  "premiere": "2015-01-11"
                },
                {
                  "id": "d5e8080c347fc23ebd6b0eeb724d8fc5",
                  "kind": "movie",
                  "premiere": "2015-01-12"
                },
                {
                  "id": "a3794f67e57df11f9abaf1dba30149ad",
                  "kind": "movie",
                  "premiere": "2015-01-13"
                },
                {
                  "id": "b17a340c3a5895d712bfab3c7edfaa9e",
                  "kind": "movie",
                  "premiere": "2015-01-14"
                },
                {
                  "id": "83b0215daa9b95f306ceea96cea1a944",
                  "kind": "movie",
                  "premiere": "2015-01-15"
                },
                {
                  "id": "a7051bf2a606e501b75b0d60f786d26e",
                  "kind": "movie",
                  "premiere": "2015-01-16"
                },
                {
                  "id": "d2a431d80e6b09f078a3c7d8246009be",
                  "kind": "movie",
                  "premiere": "2015-01-17"
                },
                {
                  "id": "d11252be6d410fee826ef1defd16bbba",
                  "kind": "movie",
                  "premiere": "2015-01-18"
                },
                {
                  "id": "db94fb7640bd1dbc7abf3b20fc86410c",
                  "kind": "movie",
                  "premiere": "2015-01-19"
                },
                {
                  "id": "14a225bbb5dd99049d6e054ada21c403",
                  "kind": "movie",
                  "premiere": "2015-01-20"
                },
                {
                  "id": "ee31096823382137bbf8dc2457bcc018",
                  "kind": "movie",
                  "premiere": "2015-01-21"
                },
                {
                  "id": "4bd23401597b2d590e417631ed90172f",
                  "kind": "movie",
                  "premiere": "2015-01-22"
                },
                {
                  "id": "49cd7458cdff943c7b2502ec026da1a2",
                  "kind": "movie",
                  "premiere": "2015-01-23"
                },
                {
                  "id": "07371586c7125a5958faa9d2d700a93f",
                  "kind": "movie",
                  "premiere": "2015-01-24"
                },
                {
                  "id": "09d23cac7313fca57979df25bd4f10d4",
                  "kind": "movie",
                  "premiere": "2015-01-25"
                },
                {
                  "id": "bb2d0a0b7690a450cf394db9f845b0f3",
                  "kind": "movie",
                  "premiere": "2015-01-26"
                },
                {
                  "id": "eeee0ca92f91934cb3fc2e960a56f2cf",
                  "kind": "movie",
                  "premiere": "2015-01-27"
                },
                {
                  "id": "d6dc865df6486a06208b42f6e844a318",
                  "kind": "movie",
                  "premiere": "2015-01-28"
                },
                {
                  "id": "e31426da764b25bc470880c299f81358",
                  "kind": "movie",
                  "premiere": "2015-01-01"
                },
                {
                  "id": "6f908775bd364a7383bcc2094807eb72",
                  "kind": "movie",
                  "premiere": "2015-01-02"
                },
                {
                  "id": "b159b47e6ac4c148bb92e8d4415f662e",
                  "kind": "movie",
                  "premiere": "2015-01-03"
                },
                {
                  "id": "f01616546daea5e0da450cb4bcbc54d6",
                  "kind": "movie",
                  "premiere": "2015-01-04"
                },
                {
                  "id": "051dc5efd40e38c32323c7b410832cfb",
                  "kind": "movie",
                  "premiere": "2015-01-05"
                },
                {
                  "id": "96570f9b2052731651ba453a9f7e2505",
                  "kind": "movie",
                  "premiere": "2015-01-06"
                },
                {
                  "id": "4d325b188124ae9f15353a06d1bb4115",
                  "kind": "movie",
                  "premiere": "2015-01-07"
                },
                {
                  "id": "acdb3efac93e949053972300e6720583",
                  "kind": "movie",
                  "premiere": "2015-01-08"
                },
                {
                  "id": "46edcb5b405e3762aa0e6e930dc17b52",
                  "kind": "movie",
                  "premiere": "2015-01-09"
                },
                {
                  "id": "dfcae640d9bd835d1771efb35f3e694e",
                  "kind": "movie",
                  "premiere": "2015-01-10"
                },
                {
                  "id": "880c6705e21c7951e44797e49855393d",
                  "kind": "movie",
                  "premiere": "2015-01-11"
                },
                {
                  "id": "992c7dca7e1e574e24c57413a431115d",
                  "kind": "movie",
                  "premiere": "2015-01-12"
                },
                {
                  "id": "ad5aeb052cc5afbe9e54f17d3c924c05",
                  "kind": "movie",
                  "premiere": "2015-01-13"
                },
                {
                  "id": "6fb98b706861f3c1185419954d604ec0",
                  "kind": "movie",
                  "premiere": "2015-01-14"
                },
                {
                  "id": "fbbda0b4e35743841a9883a46b0125f5",
                  "kind": "movie",
                  "premiere": "2015-01-15"
                },
                {
                  "id": "c348d1d5530d13dbc0b325c1bb7b39d9",
                  "kind": "movie",
                  "premiere": "2015-01-16"
                },
                {
                  "id": "a6b78848d88d860cacf7d909efad1b1a",
                  "kind": "movie",
                  "premiere": "2015-01-17"
                }
              ],
              "keys": [
                {
                  "id": "a7a6d3b21ecb97ece699e345278de898",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-01",
                    "groupId": "a7a6d3b21ecb97ece699e345278de898",
                    "season": null,
                    "episode": null,
                    "id": "a7a6d3b21ecb97ece699e345278de898"
                  }
                },
                {
                  "id": "b5145f14d921c666172b3485e05703ad",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-02",
                    "groupId": "b5145f14d921c666172b3485e05703ad",
                    "season": null,
                    "episode": null,
                    "id": "b5145f14d921c666172b3485e05703ad"
                  }
                },
                {
                  "id": "a92d4e8a72a6344c6bc265fe18354c64",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-03",
                    "groupId": "a92d4e8a72a6344c6bc265fe18354c64",
                    "season": null,
                    "episode": null,
                    "id": "a92d4e8a72a6344c6bc265fe18354c64"
                  }
                },
                {
                  "id": "82770a37d755ceeb4e04e2c49ec8dad0",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-04",
                    "groupId": "82770a37d755ceeb4e04e2c49ec8dad0",
                    "season": null,
                    "episode": null,
                    "id": "82770a37d755ceeb4e04e2c49ec8dad0"
                  }
                },
                {
                  "id": "a6b897d8d30eac80e8cb9afac767b109",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-05",
                    "groupId": "a6b897d8d30eac80e8cb9afac767b109",
                    "season": null,
                    "episode": null,
                    "id": "a6b897d8d30eac80e8cb9afac767b109"
                  }
                },
                {
                  "id": "ed3e2b670029c22b209c26f8211bd185",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-06",
                    "groupId": "ed3e2b670029c22b209c26f8211bd185",
                    "season": null,
                    "episode": null,
                    "id": "ed3e2b670029c22b209c26f8211bd185"
                  }
                },
                {
                  "id": "96519dd7d49df4ae38e7c4b2e028a229",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-07",
                    "groupId": "96519dd7d49df4ae38e7c4b2e028a229",
                    "season": null,
                    "episode": null,
                    "id": "96519dd7d49df4ae38e7c4b2e028a229"
                  }
                },
                {
                  "id": "e65f073cd2135179c9a246f21baecb68",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-08",
                    "groupId": "e65f073cd2135179c9a246f21baecb68",
                    "season": null,
                    "episode": null,
                    "id": "e65f073cd2135179c9a246f21baecb68"
                  }
                },
                {
                  "id": "d3a8d03c36640b2f36491d4aa365d567",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-09",
                    "groupId": "d3a8d03c36640b2f36491d4aa365d567",
                    "season": null,
                    "episode": null,
                    "id": "d3a8d03c36640b2f36491d4aa365d567"
                  }
                },
                {
                  "id": "dda4f3b0b9e43fd108400fcc2ffa9314",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-10",
                    "groupId": "dda4f3b0b9e43fd108400fcc2ffa9314",
                    "season": null,
                    "episode": null,
                    "id": "dda4f3b0b9e43fd108400fcc2ffa9314"
                  }
                },
                {
                  "id": "64b1e3956840282786e6b7951c608613",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-11",
                    "groupId": "64b1e3956840282786e6b7951c608613",
                    "season": null,
                    "episode": null,
                    "id": "64b1e3956840282786e6b7951c608613"
                  }
                },
                {
                  "id": "eaaf4fbac1d1689223ad6bad6f5c8c81",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-12",
                    "groupId": "eaaf4fbac1d1689223ad6bad6f5c8c81",
                    "season": null,
                    "episode": null,
                    "id": "eaaf4fbac1d1689223ad6bad6f5c8c81"
                  }
                },
                {
                  "id": "8eaff63463a306f79c52a3eb4fcdc34d",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-13",
                    "groupId": "8eaff63463a306f79c52a3eb4fcdc34d",
                    "season": null,
                    "episode": null,
                    "id": "8eaff63463a306f79c52a3eb4fcdc34d"
                  }
                },
                {
                  "id": "70ea2dfd1920444c84dcc1c7b6773c56",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-14",
                    "groupId": "70ea2dfd1920444c84dcc1c7b6773c56",
                    "season": null,
                    "episode": null,
                    "id": "70ea2dfd1920444c84dcc1c7b6773c56"
                  }
                },
                {
                  "id": "45f7d13d91b9c52faf30118ddcc3ca47",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-15",
                    "groupId": "45f7d13d91b9c52faf30118ddcc3ca47",
                    "season": null,
                    "episode": null,
                    "id": "45f7d13d91b9c52faf30118ddcc3ca47"
                  }
                },
                {
                  "id": "6931b9e1122a75b26bb5ba8e4c5a049f",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-16",
                    "groupId": "6931b9e1122a75b26bb5ba8e4c5a049f",
                    "season": null,
                    "episode": null,
                    "id": "6931b9e1122a75b26bb5ba8e4c5a049f"
                  }
                },
                {
                  "id": "2eb67620ec65912fa3f6365bd15b8991",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-17",
                    "groupId": "2eb67620ec65912fa3f6365bd15b8991",
                    "season": null,
                    "episode": null,
                    "id": "2eb67620ec65912fa3f6365bd15b8991"
                  }
                },
                {
                  "id": "5fa4f116a892767a0f56e2e9b5aa2e5b",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-18",
                    "groupId": "5fa4f116a892767a0f56e2e9b5aa2e5b",
                    "season": null,
                    "episode": null,
                    "id": "5fa4f116a892767a0f56e2e9b5aa2e5b"
                  }
                },
                {
                  "id": "f0afa51599d0c810bb5fe8e953e15117",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-19",
                    "groupId": "f0afa51599d0c810bb5fe8e953e15117",
                    "season": null,
                    "episode": null,
                    "id": "f0afa51599d0c810bb5fe8e953e15117"
                  }
                },
                {
                  "id": "b7646777da99a215beab1149a28edcde",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-20",
                    "groupId": "b7646777da99a215beab1149a28edcde",
                    "season": null,
                    "episode": null,
                    "id": "b7646777da99a215beab1149a28edcde"
                  }
                },
                {
                  "id": "e9966f076d4f365c71f1f1d4ce980385",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-21",
                    "groupId": "e9966f076d4f365c71f1f1d4ce980385",
                    "season": null,
                    "episode": null,
                    "id": "e9966f076d4f365c71f1f1d4ce980385"
                  }
                },
                {
                  "id": "3cd2167a91a41946679737c796055769",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-22",
                    "groupId": "3cd2167a91a41946679737c796055769",
                    "season": null,
                    "episode": null,
                    "id": "3cd2167a91a41946679737c796055769"
                  }
                },
                {
                  "id": "437d563c56393be55564a7f784a78404",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-23",
                    "groupId": "437d563c56393be55564a7f784a78404",
                    "season": null,
                    "episode": null,
                    "id": "437d563c56393be55564a7f784a78404"
                  }
                },
                {
                  "id": "6ba8674fe24001fbb6986e3027ef2bf4",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-24",
                    "groupId": "6ba8674fe24001fbb6986e3027ef2bf4",
                    "season": null,
                    "episode": null,
                    "id": "6ba8674fe24001fbb6986e3027ef2bf4"
                  }
                },
                {
                  "id": "e1efefb5d14f69ead257429b2b5ffe4c",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-25",
                    "groupId": "e1efefb5d14f69ead257429b2b5ffe4c",
                    "season": null,
                    "episode": null,
                    "id": "e1efefb5d14f69ead257429b2b5ffe4c"
                  }
                },
                {
                  "id": "7e5423c80b38522d4997cf2cbecdfc4e",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-26",
                    "groupId": "7e5423c80b38522d4997cf2cbecdfc4e",
                    "season": null,
                    "episode": null,
                    "id": "7e5423c80b38522d4997cf2cbecdfc4e"
                  }
                },
                {
                  "id": "31d6e6002586bf15da2e5ee5a7779c01",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-27",
                    "groupId": "31d6e6002586bf15da2e5ee5a7779c01",
                    "season": null,
                    "episode": null,
                    "id": "31d6e6002586bf15da2e5ee5a7779c01"
                  }
                },
                {
                  "id": "338c507146283fb46602d201e324653f",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-28",
                    "groupId": "338c507146283fb46602d201e324653f",
                    "season": null,
                    "episode": null,
                    "id": "338c507146283fb46602d201e324653f"
                  }
                },
                {
                  "id": "73293223506027241e09f8a031519da4",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-01",
                    "groupId": "73293223506027241e09f8a031519da4",
                    "season": null,
                    "episode": null,
                    "id": "73293223506027241e09f8a031519da4"
                  }
                },
                {
                  "id": "ffdd8d3d3394cb38bebd1e51f35aee1b",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-02",
                    "groupId": "ffdd8d3d3394cb38bebd1e51f35aee1b",
                    "season": null,
                    "episode": null,
                    "id": "ffdd8d3d3394cb38bebd1e51f35aee1b"
                  }
                },
                {
                  "id": "18c55f6e6338e7c2e834d79d53fa4a45",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-03",
                    "groupId": "18c55f6e6338e7c2e834d79d53fa4a45",
                    "season": null,
                    "episode": null,
                    "id": "18c55f6e6338e7c2e834d79d53fa4a45"
                  }
                },
                {
                  "id": "0bb399b747cc503d8c4a24f5d94dda5d",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-04",
                    "groupId": "0bb399b747cc503d8c4a24f5d94dda5d",
                    "season": null,
                    "episode": null,
                    "id": "0bb399b747cc503d8c4a24f5d94dda5d"
                  }
                },
                {
                  "id": "97bd3790f9bd2a07d2c055787d9dfed8",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-05",
                    "groupId": "97bd3790f9bd2a07d2c055787d9dfed8",
                    "season": null,
                    "episode": null,
                    "id": "97bd3790f9bd2a07d2c055787d9dfed8"
                  }
                },
                {
                  "id": "7ce5b91ab44fc0ef31e087fbe3719033",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-06",
                    "groupId": "7ce5b91ab44fc0ef31e087fbe3719033",
                    "season": null,
                    "episode": null,
                    "id": "7ce5b91ab44fc0ef31e087fbe3719033"
                  }
                },
                {
                  "id": "7a7e59c35e2eb833c1b3282747fd4b1e",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-07",
                    "groupId": "7a7e59c35e2eb833c1b3282747fd4b1e",
                    "season": null,
                    "episode": null,
                    "id": "7a7e59c35e2eb833c1b3282747fd4b1e"
                  }
                },
                {
                  "id": "6b65d4a13883a66f7834ae378256d230",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-08",
                    "groupId": "6b65d4a13883a66f7834ae378256d230",
                    "season": null,
                    "episode": null,
                    "id": "6b65d4a13883a66f7834ae378256d230"
                  }
                },
                {
                  "id": "95641ffe56985d44ea0cf4c47365be7a",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-09",
                    "groupId": "95641ffe56985d44ea0cf4c47365be7a",
                    "season": null,
                    "episode": null,
                    "id": "95641ffe56985d44ea0cf4c47365be7a"
                  }
                },
                {
                  "id": "a1e60f6349a2794019749953a2fee1c4",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-10",
                    "groupId": "a1e60f6349a2794019749953a2fee1c4",
                    "season": null,
                    "episode": null,
                    "id": "a1e60f6349a2794019749953a2fee1c4"
                  }
                },
                {
                  "id": "ec963c514a97da2c28fea379cec620f5",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-11",
                    "groupId": "ec963c514a97da2c28fea379cec620f5",
                    "season": null,
                    "episode": null,
                    "id": "ec963c514a97da2c28fea379cec620f5"
                  }
                },
                {
                  "id": "6fcacb1ac9c332bdd4054b16fc69553b",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-12",
                    "groupId": "6fcacb1ac9c332bdd4054b16fc69553b",
                    "season": null,
                    "episode": null,
                    "id": "6fcacb1ac9c332bdd4054b16fc69553b"
                  }
                },
                {
                  "id": "e5fe80d24cace961fc0bf5d96fe78fdd",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-13",
                    "groupId": "e5fe80d24cace961fc0bf5d96fe78fdd",
                    "season": null,
                    "episode": null,
                    "id": "e5fe80d24cace961fc0bf5d96fe78fdd"
                  }
                },
                {
                  "id": "dd6d1caa0058aa2e369172bb8b08c845",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-14",
                    "groupId": "dd6d1caa0058aa2e369172bb8b08c845",
                    "season": null,
                    "episode": null,
                    "id": "dd6d1caa0058aa2e369172bb8b08c845"
                  }
                },
                {
                  "id": "c2220f5b4c2ca0f3c4b191830d022100",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-15",
                    "groupId": "c2220f5b4c2ca0f3c4b191830d022100",
                    "season": null,
                    "episode": null,
                    "id": "c2220f5b4c2ca0f3c4b191830d022100"
                  }
                },
                {
                  "id": "7de1334446061d637d19b6dd75f2ed57",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-16",
                    "groupId": "7de1334446061d637d19b6dd75f2ed57",
                    "season": null,
                    "episode": null,
                    "id": "7de1334446061d637d19b6dd75f2ed57"
                  }
                },
                {
                  "id": "94866d0599587e9e28cc5f8bb174ede4",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-17",
                    "groupId": "94866d0599587e9e28cc5f8bb174ede4",
                    "season": null,
                    "episode": null,
                    "id": "94866d0599587e9e28cc5f8bb174ede4"
                  }
                },
                {
                  "id": "90f506bb95a34ba816ecd10e03caed1d",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-18",
                    "groupId": "90f506bb95a34ba816ecd10e03caed1d",
                    "season": null,
                    "episode": null,
                    "id": "90f506bb95a34ba816ecd10e03caed1d"
                  }
                },
                {
                  "id": "b64bf615672484c77be98ccf039a0d67",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-19",
                    "groupId": "b64bf615672484c77be98ccf039a0d67",
                    "season": null,
                    "episode": null,
                    "id": "b64bf615672484c77be98ccf039a0d67"
                  }
                },
                {
                  "id": "01b969167aa6183b5778459bd2fcd10e",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-20",
                    "groupId": "01b969167aa6183b5778459bd2fcd10e",
                    "season": null,
                    "episode": null,
                    "id": "01b969167aa6183b5778459bd2fcd10e"
                  }
                },
                {
                  "id": "d9d25157778b9ce55318c937b13750c8",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-21",
                    "groupId": "d9d25157778b9ce55318c937b13750c8",
                    "season": null,
                    "episode": null,
                    "id": "d9d25157778b9ce55318c937b13750c8"
                  }
                },
                {
                  "id": "fdf673c62dd578782fb54c54d1eb0392",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-22",
                    "groupId": "fdf673c62dd578782fb54c54d1eb0392",
                    "season": null,
                    "episode": null,
                    "id": "fdf673c62dd578782fb54c54d1eb0392"
                  }
                },
                {
                  "id": "e525d2a3b2002e14953ddf9a1edc63d8",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-23",
                    "groupId": "e525d2a3b2002e14953ddf9a1edc63d8",
                    "season": null,
                    "episode": null,
                    "id": "e525d2a3b2002e14953ddf9a1edc63d8"
                  }
                },
                {
                  "id": "3841a405dd589bbae2e1275b61e743f7",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-24",
                    "groupId": "3841a405dd589bbae2e1275b61e743f7",
                    "season": null,
                    "episode": null,
                    "id": "3841a405dd589bbae2e1275b61e743f7"
                  }
                },
                {
                  "id": "3b55ac3c0aae92a68e2382c336e58251",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-25",
                    "groupId": "3b55ac3c0aae92a68e2382c336e58251",
                    "season": null,
                    "episode": null,
                    "id": "3b55ac3c0aae92a68e2382c336e58251"
                  }
                },
                {
                  "id": "7bbf20d6e01a99bc3a3593531d1f1b6c",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-26",
                    "groupId": "7bbf20d6e01a99bc3a3593531d1f1b6c",
                    "season": null,
                    "episode": null,
                    "id": "7bbf20d6e01a99bc3a3593531d1f1b6c"
                  }
                },
                {
                  "id": "c8762d996c1022282531983c5789d1f5",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-27",
                    "groupId": "c8762d996c1022282531983c5789d1f5",
                    "season": null,
                    "episode": null,
                    "id": "c8762d996c1022282531983c5789d1f5"
                  }
                },
                {
                  "id": "92235be7866e4acfce1da0c2af0dc5bf",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-28",
                    "groupId": "92235be7866e4acfce1da0c2af0dc5bf",
                    "season": null,
                    "episode": null,
                    "id": "92235be7866e4acfce1da0c2af0dc5bf"
                  }
                },
                {
                  "id": "5ad12dadec8778e5519302a2895c7074",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-01",
                    "groupId": "5ad12dadec8778e5519302a2895c7074",
                    "season": null,
                    "episode": null,
                    "id": "5ad12dadec8778e5519302a2895c7074"
                  }
                },
                {
                  "id": "d50dea4ca03db06f43b8b8a7175a72f1",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-02",
                    "groupId": "d50dea4ca03db06f43b8b8a7175a72f1",
                    "season": null,
                    "episode": null,
                    "id": "d50dea4ca03db06f43b8b8a7175a72f1"
                  }
                },
                {
                  "id": "0a507dda9b4a30d7514333d15cecb873",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-03",
                    "groupId": "0a507dda9b4a30d7514333d15cecb873",
                    "season": null,
                    "episode": null,
                    "id": "0a507dda9b4a30d7514333d15cecb873"
                  }
                },
                {
                  "id": "9583baf49c874fa402da1170236b0050",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-04",
                    "groupId": "9583baf49c874fa402da1170236b0050",
                    "season": null,
                    "episode": null,
                    "id": "9583baf49c874fa402da1170236b0050"
                  }
                },
                {
                  "id": "e95f1c4efcf85dee4020ff7f99c86b29",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-05",
                    "groupId": "e95f1c4efcf85dee4020ff7f99c86b29",
                    "season": null,
                    "episode": null,
                    "id": "e95f1c4efcf85dee4020ff7f99c86b29"
                  }
                },
                {
                  "id": "261292722d18549af2c58d69399523cc",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-06",
                    "groupId": "261292722d18549af2c58d69399523cc",
                    "season": null,
                    "episode": null,
                    "id": "261292722d18549af2c58d69399523cc"
                  }
                },
                {
                  "id": "bc77ecd519de60d369aa8d70d6650dbf",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-07",
                    "groupId": "bc77ecd519de60d369aa8d70d6650dbf",
                    "season": null,
                    "episode": null,
                    "id": "bc77ecd519de60d369aa8d70d6650dbf"
                  }
                },
                {
                  "id": "685737c9b6f865c99ea31916dd7e77f8",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-08",
                    "groupId": "685737c9b6f865c99ea31916dd7e77f8",
                    "season": null,
                    "episode": null,
                    "id": "685737c9b6f865c99ea31916dd7e77f8"
                  }
                },
                {
                  "id": "659f330f59cc9a066b94ce7b49bfeb8d",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-09",
                    "groupId": "659f330f59cc9a066b94ce7b49bfeb8d",
                    "season": null,
                    "episode": null,
                    "id": "659f330f59cc9a066b94ce7b49bfeb8d"
                  }
                },
                {
                  "id": "38061ed9d9f2585bd8f23ed1f19ffc17",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-10",
                    "groupId": "38061ed9d9f2585bd8f23ed1f19ffc17",
                    "season": null,
                    "episode": null,
                    "id": "38061ed9d9f2585bd8f23ed1f19ffc17"
                  }
                },
                {
                  "id": "956f09e9c07263301177bb475beab984",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-11",
                    "groupId": "956f09e9c07263301177bb475beab984",
                    "season": null,
                    "episode": null,
                    "id": "956f09e9c07263301177bb475beab984"
                  }
                },
                {
                  "id": "d5e8080c347fc23ebd6b0eeb724d8fc5",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-12",
                    "groupId": "d5e8080c347fc23ebd6b0eeb724d8fc5",
                    "season": null,
                    "episode": null,
                    "id": "d5e8080c347fc23ebd6b0eeb724d8fc5"
                  }
                },
                {
                  "id": "a3794f67e57df11f9abaf1dba30149ad",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-13",
                    "groupId": "a3794f67e57df11f9abaf1dba30149ad",
                    "season": null,
                    "episode": null,
                    "id": "a3794f67e57df11f9abaf1dba30149ad"
                  }
                },
                {
                  "id": "b17a340c3a5895d712bfab3c7edfaa9e",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-14",
                    "groupId": "b17a340c3a5895d712bfab3c7edfaa9e",
                    "season": null,
                    "episode": null,
                    "id": "b17a340c3a5895d712bfab3c7edfaa9e"
                  }
                },
                {
                  "id": "83b0215daa9b95f306ceea96cea1a944",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-15",
                    "groupId": "83b0215daa9b95f306ceea96cea1a944",
                    "season": null,
                    "episode": null,
                    "id": "83b0215daa9b95f306ceea96cea1a944"
                  }
                },
                {
                  "id": "a7051bf2a606e501b75b0d60f786d26e",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-16",
                    "groupId": "a7051bf2a606e501b75b0d60f786d26e",
                    "season": null,
                    "episode": null,
                    "id": "a7051bf2a606e501b75b0d60f786d26e"
                  }
                },
                {
                  "id": "d2a431d80e6b09f078a3c7d8246009be",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-17",
                    "groupId": "d2a431d80e6b09f078a3c7d8246009be",
                    "season": null,
                    "episode": null,
                    "id": "d2a431d80e6b09f078a3c7d8246009be"
                  }
                },
                {
                  "id": "d11252be6d410fee826ef1defd16bbba",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-18",
                    "groupId": "d11252be6d410fee826ef1defd16bbba",
                    "season": null,
                    "episode": null,
                    "id": "d11252be6d410fee826ef1defd16bbba"
                  }
                },
                {
                  "id": "db94fb7640bd1dbc7abf3b20fc86410c",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-19",
                    "groupId": "db94fb7640bd1dbc7abf3b20fc86410c",
                    "season": null,
                    "episode": null,
                    "id": "db94fb7640bd1dbc7abf3b20fc86410c"
                  }
                },
                {
                  "id": "14a225bbb5dd99049d6e054ada21c403",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-20",
                    "groupId": "14a225bbb5dd99049d6e054ada21c403",
                    "season": null,
                    "episode": null,
                    "id": "14a225bbb5dd99049d6e054ada21c403"
                  }
                },
                {
                  "id": "ee31096823382137bbf8dc2457bcc018",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-21",
                    "groupId": "ee31096823382137bbf8dc2457bcc018",
                    "season": null,
                    "episode": null,
                    "id": "ee31096823382137bbf8dc2457bcc018"
                  }
                },
                {
                  "id": "4bd23401597b2d590e417631ed90172f",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-22",
                    "groupId": "4bd23401597b2d590e417631ed90172f",
                    "season": null,
                    "episode": null,
                    "id": "4bd23401597b2d590e417631ed90172f"
                  }
                },
                {
                  "id": "49cd7458cdff943c7b2502ec026da1a2",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-23",
                    "groupId": "49cd7458cdff943c7b2502ec026da1a2",
                    "season": null,
                    "episode": null,
                    "id": "49cd7458cdff943c7b2502ec026da1a2"
                  }
                },
                {
                  "id": "07371586c7125a5958faa9d2d700a93f",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-24",
                    "groupId": "07371586c7125a5958faa9d2d700a93f",
                    "season": null,
                    "episode": null,
                    "id": "07371586c7125a5958faa9d2d700a93f"
                  }
                },
                {
                  "id": "09d23cac7313fca57979df25bd4f10d4",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-25",
                    "groupId": "09d23cac7313fca57979df25bd4f10d4",
                    "season": null,
                    "episode": null,
                    "id": "09d23cac7313fca57979df25bd4f10d4"
                  }
                },
                {
                  "id": "bb2d0a0b7690a450cf394db9f845b0f3",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-26",
                    "groupId": "bb2d0a0b7690a450cf394db9f845b0f3",
                    "season": null,
                    "episode": null,
                    "id": "bb2d0a0b7690a450cf394db9f845b0f3"
                  }
                },
                {
                  "id": "eeee0ca92f91934cb3fc2e960a56f2cf",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-27",
                    "groupId": "eeee0ca92f91934cb3fc2e960a56f2cf",
                    "season": null,
                    "episode": null,
                    "id": "eeee0ca92f91934cb3fc2e960a56f2cf"
                  }
                },
                {
                  "id": "d6dc865df6486a06208b42f6e844a318",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-28",
                    "groupId": "d6dc865df6486a06208b42f6e844a318",
                    "season": null,
                    "episode": null,
                    "id": "d6dc865df6486a06208b42f6e844a318"
                  }
                },
                {
                  "id": "e31426da764b25bc470880c299f81358",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-01",
                    "groupId": "e31426da764b25bc470880c299f81358",
                    "season": null,
                    "episode": null,
                    "id": "e31426da764b25bc470880c299f81358"
                  }
                },
                {
                  "id": "6f908775bd364a7383bcc2094807eb72",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-02",
                    "groupId": "6f908775bd364a7383bcc2094807eb72",
                    "season": null,
                    "episode": null,
                    "id": "6f908775bd364a7383bcc2094807eb72"
                  }
                },
                {
                  "id": "b159b47e6ac4c148bb92e8d4415f662e",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-03",
                    "groupId": "b159b47e6ac4c148bb92e8d4415f662e",
                    "season": null,
                    "episode": null,
                    "id": "b159b47e6ac4c148bb92e8d4415f662e"
                  }
                },
                {
                  "id": "f01616546daea5e0da450cb4bcbc54d6",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-04",
                    "groupId": "f01616546daea5e0da450cb4bcbc54d6",
                    "season": null,
                    "episode": null,
                    "id": "f01616546daea5e0da450cb4bcbc54d6"
                  }
                },
                {
                  "id": "051dc5efd40e38c32323c7b410832cfb",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-05",
                    "groupId": "051dc5efd40e38c32323c7b410832cfb",
                    "season": null,
                    "episode": null,
                    "id": "051dc5efd40e38c32323c7b410832cfb"
                  }
                },
                {
                  "id": "96570f9b2052731651ba453a9f7e2505",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-06",
                    "groupId": "96570f9b2052731651ba453a9f7e2505",
                    "season": null,
                    "episode": null,
                    "id": "96570f9b2052731651ba453a9f7e2505"
                  }
                },
                {
                  "id": "4d325b188124ae9f15353a06d1bb4115",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-07",
                    "groupId": "4d325b188124ae9f15353a06d1bb4115",
                    "season": null,
                    "episode": null,
                    "id": "4d325b188124ae9f15353a06d1bb4115"
                  }
                },
                {
                  "id": "acdb3efac93e949053972300e6720583",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-08",
                    "groupId": "acdb3efac93e949053972300e6720583",
                    "season": null,
                    "episode": null,
                    "id": "acdb3efac93e949053972300e6720583"
                  }
                },
                {
                  "id": "46edcb5b405e3762aa0e6e930dc17b52",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-09",
                    "groupId": "46edcb5b405e3762aa0e6e930dc17b52",
                    "season": null,
                    "episode": null,
                    "id": "46edcb5b405e3762aa0e6e930dc17b52"
                  }
                },
                {
                  "id": "dfcae640d9bd835d1771efb35f3e694e",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-10",
                    "groupId": "dfcae640d9bd835d1771efb35f3e694e",
                    "season": null,
                    "episode": null,
                    "id": "dfcae640d9bd835d1771efb35f3e694e"
                  }
                },
                {
                  "id": "880c6705e21c7951e44797e49855393d",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-11",
                    "groupId": "880c6705e21c7951e44797e49855393d",
                    "season": null,
                    "episode": null,
                    "id": "880c6705e21c7951e44797e49855393d"
                  }
                },
                {
                  "id": "992c7dca7e1e574e24c57413a431115d",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-12",
                    "groupId": "992c7dca7e1e574e24c57413a431115d",
                    "season": null,
                    "episode": null,
                    "id": "992c7dca7e1e574e24c57413a431115d"
                  }
                },
                {
                  "id": "ad5aeb052cc5afbe9e54f17d3c924c05",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-13",
                    "groupId": "ad5aeb052cc5afbe9e54f17d3c924c05",
                    "season": null,
                    "episode": null,
                    "id": "ad5aeb052cc5afbe9e54f17d3c924c05"
                  }
                },
                {
                  "id": "6fb98b706861f3c1185419954d604ec0",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-14",
                    "groupId": "6fb98b706861f3c1185419954d604ec0",
                    "season": null,
                    "episode": null,
                    "id": "6fb98b706861f3c1185419954d604ec0"
                  }
                },
                {
                  "id": "fbbda0b4e35743841a9883a46b0125f5",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-15",
                    "groupId": "fbbda0b4e35743841a9883a46b0125f5",
                    "season": null,
                    "episode": null,
                    "id": "fbbda0b4e35743841a9883a46b0125f5"
                  }
                },
                {
                  "id": "c348d1d5530d13dbc0b325c1bb7b39d9",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-16",
                    "groupId": "c348d1d5530d13dbc0b325c1bb7b39d9",
                    "season": null,
                    "episode": null,
                    "id": "c348d1d5530d13dbc0b325c1bb7b39d9"
                  }
                },
                {
                  "id": "a6b78848d88d860cacf7d909efad1b1a",
                  "keyParts": {
                    "undated": 0,
                    "date": "2015-01-17",
                    "groupId": "a6b78848d88d860cacf7d909efad1b1a",
                    "season": null,
                    "episode": null,
                    "id": "a6b78848d88d860cacf7d909efad1b1a"
                  }
                }
              ],
              "order": [
                "5ad12dadec8778e5519302a2895c7074",
                "73293223506027241e09f8a031519da4",
                "a7a6d3b21ecb97ece699e345278de898",
                "e31426da764b25bc470880c299f81358",
                "6f908775bd364a7383bcc2094807eb72",
                "b5145f14d921c666172b3485e05703ad",
                "d50dea4ca03db06f43b8b8a7175a72f1",
                "ffdd8d3d3394cb38bebd1e51f35aee1b",
                "0a507dda9b4a30d7514333d15cecb873",
                "18c55f6e6338e7c2e834d79d53fa4a45",
                "a92d4e8a72a6344c6bc265fe18354c64",
                "b159b47e6ac4c148bb92e8d4415f662e",
                "0bb399b747cc503d8c4a24f5d94dda5d",
                "82770a37d755ceeb4e04e2c49ec8dad0",
                "9583baf49c874fa402da1170236b0050",
                "f01616546daea5e0da450cb4bcbc54d6",
                "051dc5efd40e38c32323c7b410832cfb",
                "97bd3790f9bd2a07d2c055787d9dfed8",
                "a6b897d8d30eac80e8cb9afac767b109",
                "e95f1c4efcf85dee4020ff7f99c86b29",
                "261292722d18549af2c58d69399523cc",
                "7ce5b91ab44fc0ef31e087fbe3719033",
                "96570f9b2052731651ba453a9f7e2505",
                "ed3e2b670029c22b209c26f8211bd185",
                "4d325b188124ae9f15353a06d1bb4115",
                "7a7e59c35e2eb833c1b3282747fd4b1e",
                "96519dd7d49df4ae38e7c4b2e028a229",
                "bc77ecd519de60d369aa8d70d6650dbf",
                "685737c9b6f865c99ea31916dd7e77f8",
                "6b65d4a13883a66f7834ae378256d230",
                "acdb3efac93e949053972300e6720583",
                "e65f073cd2135179c9a246f21baecb68",
                "46edcb5b405e3762aa0e6e930dc17b52",
                "659f330f59cc9a066b94ce7b49bfeb8d",
                "95641ffe56985d44ea0cf4c47365be7a",
                "d3a8d03c36640b2f36491d4aa365d567",
                "38061ed9d9f2585bd8f23ed1f19ffc17",
                "a1e60f6349a2794019749953a2fee1c4",
                "dda4f3b0b9e43fd108400fcc2ffa9314",
                "dfcae640d9bd835d1771efb35f3e694e",
                "64b1e3956840282786e6b7951c608613",
                "880c6705e21c7951e44797e49855393d",
                "956f09e9c07263301177bb475beab984",
                "ec963c514a97da2c28fea379cec620f5",
                "6fcacb1ac9c332bdd4054b16fc69553b",
                "992c7dca7e1e574e24c57413a431115d",
                "d5e8080c347fc23ebd6b0eeb724d8fc5",
                "eaaf4fbac1d1689223ad6bad6f5c8c81",
                "8eaff63463a306f79c52a3eb4fcdc34d",
                "a3794f67e57df11f9abaf1dba30149ad",
                "ad5aeb052cc5afbe9e54f17d3c924c05",
                "e5fe80d24cace961fc0bf5d96fe78fdd",
                "6fb98b706861f3c1185419954d604ec0",
                "70ea2dfd1920444c84dcc1c7b6773c56",
                "b17a340c3a5895d712bfab3c7edfaa9e",
                "dd6d1caa0058aa2e369172bb8b08c845",
                "45f7d13d91b9c52faf30118ddcc3ca47",
                "83b0215daa9b95f306ceea96cea1a944",
                "c2220f5b4c2ca0f3c4b191830d022100",
                "fbbda0b4e35743841a9883a46b0125f5",
                "6931b9e1122a75b26bb5ba8e4c5a049f",
                "7de1334446061d637d19b6dd75f2ed57",
                "a7051bf2a606e501b75b0d60f786d26e",
                "c348d1d5530d13dbc0b325c1bb7b39d9",
                "2eb67620ec65912fa3f6365bd15b8991",
                "94866d0599587e9e28cc5f8bb174ede4",
                "a6b78848d88d860cacf7d909efad1b1a",
                "d2a431d80e6b09f078a3c7d8246009be",
                "5fa4f116a892767a0f56e2e9b5aa2e5b",
                "90f506bb95a34ba816ecd10e03caed1d",
                "d11252be6d410fee826ef1defd16bbba",
                "b64bf615672484c77be98ccf039a0d67",
                "db94fb7640bd1dbc7abf3b20fc86410c",
                "f0afa51599d0c810bb5fe8e953e15117",
                "01b969167aa6183b5778459bd2fcd10e",
                "14a225bbb5dd99049d6e054ada21c403",
                "b7646777da99a215beab1149a28edcde",
                "d9d25157778b9ce55318c937b13750c8",
                "e9966f076d4f365c71f1f1d4ce980385",
                "ee31096823382137bbf8dc2457bcc018",
                "3cd2167a91a41946679737c796055769",
                "4bd23401597b2d590e417631ed90172f",
                "fdf673c62dd578782fb54c54d1eb0392",
                "437d563c56393be55564a7f784a78404",
                "49cd7458cdff943c7b2502ec026da1a2",
                "e525d2a3b2002e14953ddf9a1edc63d8",
                "07371586c7125a5958faa9d2d700a93f",
                "3841a405dd589bbae2e1275b61e743f7",
                "6ba8674fe24001fbb6986e3027ef2bf4",
                "09d23cac7313fca57979df25bd4f10d4",
                "3b55ac3c0aae92a68e2382c336e58251",
                "e1efefb5d14f69ead257429b2b5ffe4c",
                "7bbf20d6e01a99bc3a3593531d1f1b6c",
                "7e5423c80b38522d4997cf2cbecdfc4e",
                "bb2d0a0b7690a450cf394db9f845b0f3",
                "31d6e6002586bf15da2e5ee5a7779c01",
                "c8762d996c1022282531983c5789d1f5",
                "eeee0ca92f91934cb3fc2e960a56f2cf",
                "338c507146283fb46602d201e324653f",
                "92235be7866e4acfce1da0c2af0dc5bf",
                "d6dc865df6486a06208b42f6e844a318"
              ],
              "runs": [
                [
                  "5ad12dadec8778e5519302a2895c7074",
                  "73293223506027241e09f8a031519da4",
                  "a7a6d3b21ecb97ece699e345278de898",
                  "e31426da764b25bc470880c299f81358",
                  "6f908775bd364a7383bcc2094807eb72",
                  "b5145f14d921c666172b3485e05703ad",
                  "d50dea4ca03db06f43b8b8a7175a72f1",
                  "ffdd8d3d3394cb38bebd1e51f35aee1b",
                  "0a507dda9b4a30d7514333d15cecb873",
                  "18c55f6e6338e7c2e834d79d53fa4a45",
                  "a92d4e8a72a6344c6bc265fe18354c64",
                  "b159b47e6ac4c148bb92e8d4415f662e",
                  "0bb399b747cc503d8c4a24f5d94dda5d",
                  "82770a37d755ceeb4e04e2c49ec8dad0",
                  "9583baf49c874fa402da1170236b0050",
                  "f01616546daea5e0da450cb4bcbc54d6",
                  "051dc5efd40e38c32323c7b410832cfb",
                  "97bd3790f9bd2a07d2c055787d9dfed8",
                  "a6b897d8d30eac80e8cb9afac767b109",
                  "e95f1c4efcf85dee4020ff7f99c86b29",
                  "261292722d18549af2c58d69399523cc",
                  "7ce5b91ab44fc0ef31e087fbe3719033",
                  "96570f9b2052731651ba453a9f7e2505",
                  "ed3e2b670029c22b209c26f8211bd185",
                  "4d325b188124ae9f15353a06d1bb4115",
                  "7a7e59c35e2eb833c1b3282747fd4b1e",
                  "96519dd7d49df4ae38e7c4b2e028a229",
                  "bc77ecd519de60d369aa8d70d6650dbf",
                  "685737c9b6f865c99ea31916dd7e77f8",
                  "6b65d4a13883a66f7834ae378256d230",
                  "acdb3efac93e949053972300e6720583",
                  "e65f073cd2135179c9a246f21baecb68",
                  "46edcb5b405e3762aa0e6e930dc17b52",
                  "659f330f59cc9a066b94ce7b49bfeb8d",
                  "95641ffe56985d44ea0cf4c47365be7a",
                  "d3a8d03c36640b2f36491d4aa365d567",
                  "38061ed9d9f2585bd8f23ed1f19ffc17",
                  "a1e60f6349a2794019749953a2fee1c4",
                  "dda4f3b0b9e43fd108400fcc2ffa9314",
                  "dfcae640d9bd835d1771efb35f3e694e",
                  "64b1e3956840282786e6b7951c608613",
                  "880c6705e21c7951e44797e49855393d",
                  "956f09e9c07263301177bb475beab984",
                  "ec963c514a97da2c28fea379cec620f5",
                  "6fcacb1ac9c332bdd4054b16fc69553b",
                  "992c7dca7e1e574e24c57413a431115d",
                  "d5e8080c347fc23ebd6b0eeb724d8fc5",
                  "eaaf4fbac1d1689223ad6bad6f5c8c81",
                  "8eaff63463a306f79c52a3eb4fcdc34d",
                  "a3794f67e57df11f9abaf1dba30149ad",
                  "ad5aeb052cc5afbe9e54f17d3c924c05",
                  "e5fe80d24cace961fc0bf5d96fe78fdd",
                  "6fb98b706861f3c1185419954d604ec0",
                  "70ea2dfd1920444c84dcc1c7b6773c56",
                  "b17a340c3a5895d712bfab3c7edfaa9e",
                  "dd6d1caa0058aa2e369172bb8b08c845",
                  "45f7d13d91b9c52faf30118ddcc3ca47",
                  "83b0215daa9b95f306ceea96cea1a944",
                  "c2220f5b4c2ca0f3c4b191830d022100",
                  "fbbda0b4e35743841a9883a46b0125f5",
                  "6931b9e1122a75b26bb5ba8e4c5a049f",
                  "7de1334446061d637d19b6dd75f2ed57",
                  "a7051bf2a606e501b75b0d60f786d26e",
                  "c348d1d5530d13dbc0b325c1bb7b39d9",
                  "2eb67620ec65912fa3f6365bd15b8991",
                  "94866d0599587e9e28cc5f8bb174ede4",
                  "a6b78848d88d860cacf7d909efad1b1a",
                  "d2a431d80e6b09f078a3c7d8246009be",
                  "5fa4f116a892767a0f56e2e9b5aa2e5b",
                  "90f506bb95a34ba816ecd10e03caed1d",
                  "d11252be6d410fee826ef1defd16bbba",
                  "b64bf615672484c77be98ccf039a0d67",
                  "db94fb7640bd1dbc7abf3b20fc86410c",
                  "f0afa51599d0c810bb5fe8e953e15117",
                  "01b969167aa6183b5778459bd2fcd10e",
                  "14a225bbb5dd99049d6e054ada21c403",
                  "b7646777da99a215beab1149a28edcde",
                  "d9d25157778b9ce55318c937b13750c8",
                  "e9966f076d4f365c71f1f1d4ce980385",
                  "ee31096823382137bbf8dc2457bcc018",
                  "3cd2167a91a41946679737c796055769",
                  "4bd23401597b2d590e417631ed90172f",
                  "fdf673c62dd578782fb54c54d1eb0392",
                  "437d563c56393be55564a7f784a78404",
                  "49cd7458cdff943c7b2502ec026da1a2",
                  "e525d2a3b2002e14953ddf9a1edc63d8",
                  "07371586c7125a5958faa9d2d700a93f",
                  "3841a405dd589bbae2e1275b61e743f7",
                  "6ba8674fe24001fbb6986e3027ef2bf4",
                  "09d23cac7313fca57979df25bd4f10d4",
                  "3b55ac3c0aae92a68e2382c336e58251",
                  "e1efefb5d14f69ead257429b2b5ffe4c",
                  "7bbf20d6e01a99bc3a3593531d1f1b6c",
                  "7e5423c80b38522d4997cf2cbecdfc4e",
                  "bb2d0a0b7690a450cf394db9f845b0f3",
                  "31d6e6002586bf15da2e5ee5a7779c01",
                  "c8762d996c1022282531983c5789d1f5",
                  "eeee0ca92f91934cb3fc2e960a56f2cf",
                  "338c507146283fb46602d201e324653f",
                  "92235be7866e4acfce1da0c2af0dc5bf"
                ],
                [
                  "d6dc865df6486a06208b42f6e844a318"
                ]
              ]
            }
          ]
        }
        """.trimIndent() + "\n"
}
