// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.ordering

/** Kotlin copies of `docs/rules-format/vectors/` for the primitives (`VectorFilesInSyncTest` fails if they differ from the files). */
internal object VectorFiles {
    val splitmix64: String =
        """
        {
          "description": "splitmix64(x): z = x + 0x9E3779B97F4A7C15; z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9; z = (z ^ (z >> 27)) * 0x94D049BB133111EB; return z ^ (z >> 31), all wrapping modulo 2^64. Every 64-bit value is an object: dec (unsigned decimal string), hex (16 lower-case digits, no prefix), hi and lo (its high and low 32 bits as 8 lower-case hex digits), so a port without a native 64-bit integer can check each step.",
          "source": "Computed with an independent Python reference and cross-checked against a C port of Vigna's splitmix64.c. The inputs 0, 0x9E3779B97F4A7C15, 2*gamma and 3*gamma give the four published first outputs of SplitMix64 seeded with 0.",
          "vectors": [
            {
              "input": {
                "dec": "0",
                "hex": "0000000000000000",
                "hi": "00000000",
                "lo": "00000000"
              },
              "output": {
                "dec": "16294208416658607535",
                "hex": "e220a8397b1dcdaf",
                "hi": "e220a839",
                "lo": "7b1dcdaf"
              }
            },
            {
              "input": {
                "dec": "1",
                "hex": "0000000000000001",
                "hi": "00000000",
                "lo": "00000001"
              },
              "output": {
                "dec": "10451216379200822465",
                "hex": "910a2dec89025cc1",
                "hi": "910a2dec",
                "lo": "89025cc1"
              }
            },
            {
              "input": {
                "dec": "2",
                "hex": "0000000000000002",
                "hi": "00000000",
                "lo": "00000002"
              },
              "output": {
                "dec": "10905525725756348110",
                "hex": "975835de1c9756ce",
                "hi": "975835de",
                "lo": "1c9756ce"
              }
            },
            {
              "input": {
                "dec": "11400714819323198485",
                "hex": "9e3779b97f4a7c15",
                "hi": "9e3779b9",
                "lo": "7f4a7c15"
              },
              "output": {
                "dec": "7960286522194355700",
                "hex": "6e789e6aa1b965f4",
                "hi": "6e789e6a",
                "lo": "a1b965f4"
              }
            },
            {
              "input": {
                "dec": "4354685564936845354",
                "hex": "3c6ef372fe94f82a",
                "hi": "3c6ef372",
                "lo": "fe94f82a"
              },
              "output": {
                "dec": "487617019471545679",
                "hex": "06c45d188009454f",
                "hi": "06c45d18",
                "lo": "8009454f"
              }
            },
            {
              "input": {
                "dec": "15755400384260043839",
                "hex": "daa66d2c7ddf743f",
                "hi": "daa66d2c",
                "lo": "7ddf743f"
              },
              "output": {
                "dec": "17909611376780542444",
                "hex": "f88bb8a8724c81ec",
                "hi": "f88bb8a8",
                "lo": "724c81ec"
              }
            },
            {
              "input": {
                "dec": "16294208416658607535",
                "hex": "e220a8397b1dcdaf",
                "hi": "e220a839",
                "lo": "7b1dcdaf"
              },
              "output": {
                "dec": "12035550249420947055",
                "hex": "a706dd2f4d197e6f",
                "hi": "a706dd2f",
                "lo": "4d197e6f"
              }
            },
            {
              "input": {
                "dec": "18446744073709551615",
                "hex": "ffffffffffffffff",
                "hi": "ffffffff",
                "lo": "ffffffff"
              },
              "output": {
                "dec": "16490336266968443936",
                "hex": "e4d971771b652c20",
                "hi": "e4d97177",
                "lo": "1b652c20"
              }
            },
            {
              "input": {
                "dec": "9223372036854775808",
                "hex": "8000000000000000",
                "hi": "80000000",
                "lo": "00000000"
              },
              "output": {
                "dec": "5196802822362493915",
                "hex": "481ec0a212a9f3db",
                "hi": "481ec0a2",
                "lo": "12a9f3db"
              }
            },
            {
              "input": {
                "dec": "9223372036854775807",
                "hex": "7fffffffffffffff",
                "hi": "7fffffff",
                "lo": "ffffffff"
              },
              "output": {
                "dec": "3055647633038352039",
                "hex": "2a67d7552e039ea7",
                "hi": "2a67d755",
                "lo": "2e039ea7"
              }
            },
            {
              "input": {
                "dec": "4294967295",
                "hex": "00000000ffffffff",
                "hi": "00000000",
                "lo": "ffffffff"
              },
              "output": {
                "dec": "8336509955162079680",
                "hex": "73b13ba2aff181c0",
                "hi": "73b13ba2",
                "lo": "aff181c0"
              }
            },
            {
              "input": {
                "dec": "4294967296",
                "hex": "0000000100000000",
                "hi": "00000001",
                "lo": "00000000"
              },
              "output": {
                "dec": "14135772400868000056",
                "hex": "c42c5a1aa3820138",
                "hi": "c42c5a1a",
                "lo": "a3820138"
              }
            },
            {
              "input": {
                "dec": "81985529216486895",
                "hex": "0123456789abcdef",
                "hi": "01234567",
                "lo": "89abcdef"
              },
              "output": {
                "dec": "1547611027431991965",
                "hex": "157a3807a48faa9d",
                "hi": "157a3807",
                "lo": "a48faa9d"
              }
            },
            {
              "input": {
                "dec": "16045690984503098046",
                "hex": "deadbeefcafebabe",
                "hi": "deadbeef",
                "lo": "cafebabe"
              },
              "output": {
                "dec": "972095092378118610",
                "hex": "0d7d93560d1929d2",
                "hi": "0d7d9356",
                "lo": "0d1929d2"
              }
            }
          ],
          "chain": {
            "description": "Start from 0 and feed each output back in: out[k+1] = splitmix64(out[k]).",
            "start": {
              "dec": "0",
              "hex": "0000000000000000",
              "hi": "00000000",
              "lo": "00000000"
            },
            "outputs": [
              {
                "dec": "16294208416658607535",
                "hex": "e220a8397b1dcdaf",
                "hi": "e220a839",
                "lo": "7b1dcdaf"
              },
              {
                "dec": "12035550249420947055",
                "hex": "a706dd2f4d197e6f",
                "hi": "a706dd2f",
                "lo": "4d197e6f"
              },
              {
                "dec": "2558736989570252433",
                "hex": "238275bc38fcbe91",
                "hi": "238275bc",
                "lo": "38fcbe91"
              },
              {
                "dec": "2391539541053276776",
                "hex": "2130748aaac80268",
                "hi": "2130748a",
                "lo": "aac80268"
              },
              {
                "dec": "8695987549771912286",
                "hex": "78ae5a9a6b5fd45e",
                "hi": "78ae5a9a",
                "lo": "6b5fd45e"
              },
              {
                "dec": "14687217854757187732",
                "hex": "cbd37ad29b93b094",
                "hi": "cbd37ad2",
                "lo": "9b93b094"
              }
            ]
          }
        }
        """.trimIndent() + "\n"

    val fnv1a64: String =
        """
        {
          "description": "fnv1a64(bytes): h = 0xcbf29ce484222325; for each byte b: h = (h ^ b) * 0x100000001b3 (wrapping). Every 64-bit value is an object: dec (unsigned decimal string), hex (16 lower-case digits, no prefix), hi and lo (its high and low 32 bits as 8 lower-case hex digits), so a port without a native 64-bit integer can check each step.",
          "source": "Computed with an independent Python reference and cross-checked against a C port. The empty string, a, foo and foobar match the published FNV-1a 64 test values.",
          "vectors": [
            {
              "name": "empty",
              "bytesHex": "",
              "output": {
                "dec": "14695981039346656037",
                "hex": "cbf29ce484222325",
                "hi": "cbf29ce4",
                "lo": "84222325"
              }
            },
            {
              "name": "a",
              "bytesHex": "61",
              "output": {
                "dec": "12638187200555641996",
                "hex": "af63dc4c8601ec8c",
                "hi": "af63dc4c",
                "lo": "8601ec8c"
              }
            },
            {
              "name": "foo",
              "bytesHex": "666f6f",
              "output": {
                "dec": "15902901984413996407",
                "hex": "dcb27518fed9d577",
                "hi": "dcb27518",
                "lo": "fed9d577"
              }
            },
            {
              "name": "foobar",
              "bytesHex": "666f6f626172",
              "output": {
                "dec": "9625390261332436968",
                "hex": "85944171f73967e8",
                "hi": "85944171",
                "lo": "f73967e8"
              }
            },
            {
              "name": "byte 0x00",
              "bytesHex": "00",
              "output": {
                "dec": "12638153115695167455",
                "hex": "af63bd4c8601b7df",
                "hi": "af63bd4c",
                "lo": "8601b7df"
              }
            },
            {
              "name": "byte 0xff",
              "bytesHex": "ff",
              "output": {
                "dec": "12638352127299873646",
                "hex": "af64724c8602eb6e",
                "hi": "af64724c",
                "lo": "8602eb6e"
              }
            },
            {
              "name": "canonical id 11..1",
              "bytesHex": "3131313131313131313131313131313131313131313131313131313131313131",
              "output": {
                "dec": "16872389951895316613",
                "hex": "ea26c5447a972885",
                "hi": "ea26c544",
                "lo": "7a972885"
              }
            },
            {
              "name": "canonical id 0..01",
              "bytesHex": "3030303030303030303030303030303030303030303030303030303030303031",
              "output": {
                "dec": "8935916118950687730",
                "hex": "7c02c05d9cbe33f2",
                "hi": "7c02c05d",
                "lo": "9cbe33f2"
              }
            },
            {
              "name": "canonical id a..a",
              "bytesHex": "6161616161616161616161616161616161616161616161616161616161616161",
              "output": {
                "dec": "5210854943071644549",
                "hex": "4850acf661dfab85",
                "hi": "4850acf6",
                "lo": "61dfab85"
              }
            },
            {
              "name": "utf-8 genre Animé",
              "bytesHex": "416e696dc3a9",
              "output": {
                "dec": "15083902137316481856",
                "hex": "d154c91371811b40",
                "hi": "d154c913",
                "lo": "71811b40"
              }
            },
            {
              "name": "utf-8 genre 科幻",
              "bytesHex": "e7a791e5b9bb",
              "output": {
                "dec": "15237165279010863489",
                "hex": "d37549175f5bb981",
                "hi": "d3754917",
                "lo": "5f5bb981"
              }
            }
          ]
        }
        """.trimIndent() + "\n"

    val canonicalId: String =
        """
        {
          "description": "Item IDs. normalised: what a client does with an ID from the server (remove hyphens, remove one surrounding pair of braces, lower-case; null if anything other than 32 hex characters is left). strict: what a rules-file reader accepts (the input unchanged if it is already 32 lower-case hex characters, else null; nothing is repaired). Placeholder IDs only.",
          "vectors": [
            {
              "input": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "normalised": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "strict": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            },
            {
              "input": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
              "normalised": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "strict": null
            },
            {
              "input": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "normalised": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "strict": null
            },
            {
              "input": "{AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA}",
              "normalised": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "strict": null
            },
            {
              "input": "{aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}",
              "normalised": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "strict": null
            },
            {
              "input": "0123456789abcdef0123456789abcdef",
              "normalised": "0123456789abcdef0123456789abcdef",
              "strict": "0123456789abcdef0123456789abcdef"
            },
            {
              "input": "0123456789ABCDEF0123456789ABCDEF",
              "normalised": "0123456789abcdef0123456789abcdef",
              "strict": null
            },
            {
              "input": "01234567-89ab-cdef-0123-456789abcdef",
              "normalised": "0123456789abcdef0123456789abcdef",
              "strict": null
            },
            {
              "input": "0123456789ABCdef0123456789abCDEF",
              "normalised": "0123456789abcdef0123456789abcdef",
              "strict": null
            },
            {
              "input": "00000000000000000000000000000001",
              "normalised": "00000000000000000000000000000001",
              "strict": "00000000000000000000000000000001"
            },
            {
              "input": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "normalised": null,
              "strict": null
            },
            {
              "input": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "normalised": null,
              "strict": null
            },
            {
              "input": "",
              "normalised": null,
              "strict": null
            },
            {
              "input": "gggggggggggggggggggggggggggggggg",
              "normalised": null,
              "strict": null
            },
            {
              "input": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaag",
              "normalised": null,
              "strict": null
            },
            {
              "input": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ",
              "normalised": null,
              "strict": null
            },
            {
              "input": " aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "normalised": null,
              "strict": null
            },
            {
              "input": "{aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}",
              "normalised": null,
              "strict": null
            },
            {
              "input": "(aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)",
              "normalised": null,
              "strict": null
            },
            {
              "input": "--------------------------------",
              "normalised": null,
              "strict": null
            },
            {
              "input": "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "normalised": null,
              "strict": null
            }
          ]
        }
        """.trimIndent() + "\n"

    /** The copies by file name, for the in-sync test. */
    val files: Map<String, String> =
        mapOf(
            "splitmix64.json" to splitmix64,
            "fnv1a64.json" to fnv1a64,
            "canonical-id.json" to canonicalId,
        )
}
