// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * The Kotlin copy of `docs/rules-format/vectors/studio-fold-2.json` (`VectorFilesInSyncTest` fails if it differs from
 * the file). A test in commonTest cannot read files, because linuxX64Test cannot.
 */
@Suppress("LargeClass") // Data only: one vector file.
internal object StudioFold2VectorFile {
    val json: String =
        """
        {
          "description": "Studio fold 2 (docs/adr/0026-studio-fold-2.md, The sets and The pass): read the name by code point with 'last kept' empty and 'gap' off; a lone surrogate is removed; a code point in R = S u B u D is removed, and sets gap on if it is in G; U+200C or U+200D is removed if last kept is empty, last kept is in L, or gap is on, else kept; any other code point is kept as its ASCII fold (A to Z become a to z). A keep sets last kept and sets gap off. No trim, no collapse, no normalisation. 'key' is fold 2 of 'input'; 'fold1' is studio fold 1 (docs/rules-format.md, Studio names: clean, then fold) of 'input', given so that a port can check fold2(fold1(x)) = fold2(x). Every vector also satisfies fold2(key) = key. 'sets' lists S, B, D (its three parts), G and L as inclusive code point ranges in hexadecimal. Whitespace, control and format characters are written as JSON escapes (such as \\u00a0 for a no-break space) so that they can be seen; this file is all ASCII, and a supplementary code point is written as its UTF-16 surrogate pair.",
          "source": "Computed with an independent Python reference (scripts/rules-format/studio_fold_2_vectors.py) written from ADR-0026 alone; it checks list 2 of D against Unicode 15.1.0 DerivedCoreProperties.txt (Default_Ignorable_Code_Point, less U+200C and U+200D) before writing.",
          "sets": {
            "S": [
              [
                "0020",
                "0020"
              ],
              [
                "00A0",
                "00A0"
              ],
              [
                "1680",
                "1680"
              ],
              [
                "2000",
                "200A"
              ],
              [
                "202F",
                "202F"
              ],
              [
                "205F",
                "205F"
              ],
              [
                "3000",
                "3000"
              ]
            ],
            "B": [
              [
                "0009",
                "000D"
              ],
              [
                "001C",
                "001F"
              ],
              [
                "0085",
                "0085"
              ],
              [
                "2028",
                "2029"
              ],
              [
                "115F",
                "1160"
              ],
              [
                "2800",
                "2800"
              ],
              [
                "3164",
                "3164"
              ],
              [
                "FFA0",
                "FFA0"
              ]
            ],
            "D": {
              "fold1Dropped": [
                [
                  "0000",
                  "001F"
                ],
                [
                  "007F",
                  "009F"
                ],
                [
                  "00AD",
                  "00AD"
                ],
                [
                  "0600",
                  "0605"
                ],
                [
                  "061C",
                  "061C"
                ],
                [
                  "06DD",
                  "06DD"
                ],
                [
                  "070F",
                  "070F"
                ],
                [
                  "0890",
                  "0891"
                ],
                [
                  "08E2",
                  "08E2"
                ],
                [
                  "180E",
                  "180E"
                ],
                [
                  "200B",
                  "200B"
                ],
                [
                  "200E",
                  "200F"
                ],
                [
                  "202A",
                  "202E"
                ],
                [
                  "2060",
                  "2064"
                ],
                [
                  "2066",
                  "206F"
                ],
                [
                  "FEFF",
                  "FEFF"
                ],
                [
                  "FFF9",
                  "FFFB"
                ],
                [
                  "110BD",
                  "110BD"
                ],
                [
                  "110CD",
                  "110CD"
                ],
                [
                  "13430",
                  "1343F"
                ],
                [
                  "1BCA0",
                  "1BCA3"
                ],
                [
                  "1D173",
                  "1D17A"
                ],
                [
                  "E0001",
                  "E0001"
                ],
                [
                  "E0020",
                  "E007F"
                ]
              ],
              "defaultIgnorable": [
                [
                  "00AD",
                  "00AD"
                ],
                [
                  "034F",
                  "034F"
                ],
                [
                  "061C",
                  "061C"
                ],
                [
                  "115F",
                  "1160"
                ],
                [
                  "17B4",
                  "17B5"
                ],
                [
                  "180B",
                  "180D"
                ],
                [
                  "180E",
                  "180E"
                ],
                [
                  "180F",
                  "180F"
                ],
                [
                  "200B",
                  "200B"
                ],
                [
                  "200E",
                  "200F"
                ],
                [
                  "202A",
                  "202E"
                ],
                [
                  "2060",
                  "2064"
                ],
                [
                  "2065",
                  "2065"
                ],
                [
                  "2066",
                  "206F"
                ],
                [
                  "3164",
                  "3164"
                ],
                [
                  "FE00",
                  "FE0F"
                ],
                [
                  "FEFF",
                  "FEFF"
                ],
                [
                  "FFA0",
                  "FFA0"
                ],
                [
                  "FFF0",
                  "FFF8"
                ],
                [
                  "1BCA0",
                  "1BCA3"
                ],
                [
                  "1D173",
                  "1D17A"
                ],
                [
                  "E0000",
                  "E0000"
                ],
                [
                  "E0001",
                  "E0001"
                ],
                [
                  "E0002",
                  "E001F"
                ],
                [
                  "E0020",
                  "E007F"
                ],
                [
                  "E0080",
                  "E00FF"
                ],
                [
                  "E0100",
                  "E01EF"
                ],
                [
                  "E01F0",
                  "E0FFF"
                ]
              ],
              "extra": [
                [
                  "2800",
                  "2800"
                ]
              ]
            },
            "G": [
              [
                "0020",
                "0020"
              ],
              [
                "00A0",
                "00A0"
              ],
              [
                "1680",
                "1680"
              ],
              [
                "2000",
                "200A"
              ],
              [
                "202F",
                "202F"
              ],
              [
                "205F",
                "205F"
              ],
              [
                "3000",
                "3000"
              ],
              [
                "0009",
                "000A"
              ],
              [
                "000D",
                "000D"
              ],
              [
                "2028",
                "2029"
              ],
              [
                "115F",
                "1160"
              ],
              [
                "2800",
                "2800"
              ],
              [
                "3164",
                "3164"
              ],
              [
                "FFA0",
                "FFA0"
              ]
            ],
            "L": [
              [
                "0000",
                "024F"
              ],
              [
                "0300",
                "036F"
              ]
            ]
          },
          "vectors": [
            {
              "name": "plain ASCII is folded",
              "input": "Walt Disney",
              "fold1": "walt disney",
              "key": "waltdisney"
            },
            {
              "name": "only A to Z fold",
              "input": "Studi\u00d6 \u212a",
              "fold1": "studi\u00d6 \u212a",
              "key": "studi\u00d6\u212a"
            },
            {
              "name": "no space at all",
              "input": "WaltDisney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "two spaces",
              "input": "Walt  Disney",
              "fold1": "walt  disney",
              "key": "waltdisney"
            },
            {
              "name": "empty",
              "input": "",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+0020 alone",
              "input": " ",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+0020 at the start",
              "input": " Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+0020 at the end",
              "input": "Disney ",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+0020 between two words",
              "input": "Walt Disney",
              "fold1": "walt disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+00A0 alone",
              "input": "\u00a0",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+00A0 at the start",
              "input": "\u00a0Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+00A0 at the end",
              "input": "Disney\u00a0",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+00A0 between two words",
              "input": "Walt\u00a0Disney",
              "fold1": "walt\u00a0disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+1680 alone",
              "input": "\u1680",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+1680 at the start",
              "input": "\u1680Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+1680 at the end",
              "input": "Disney\u1680",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+1680 between two words",
              "input": "Walt\u1680Disney",
              "fold1": "walt\u1680disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2000 alone",
              "input": "\u2000",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2000 at the start",
              "input": "\u2000Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2000 at the end",
              "input": "Disney\u2000",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2000 between two words",
              "input": "Walt\u2000Disney",
              "fold1": "walt\u2000disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2001 alone",
              "input": "\u2001",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2001 at the start",
              "input": "\u2001Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2001 at the end",
              "input": "Disney\u2001",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2001 between two words",
              "input": "Walt\u2001Disney",
              "fold1": "walt\u2001disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2002 alone",
              "input": "\u2002",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2002 at the start",
              "input": "\u2002Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2002 at the end",
              "input": "Disney\u2002",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2002 between two words",
              "input": "Walt\u2002Disney",
              "fold1": "walt\u2002disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2003 alone",
              "input": "\u2003",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2003 at the start",
              "input": "\u2003Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2003 at the end",
              "input": "Disney\u2003",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2003 between two words",
              "input": "Walt\u2003Disney",
              "fold1": "walt\u2003disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2004 alone",
              "input": "\u2004",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2004 at the start",
              "input": "\u2004Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2004 at the end",
              "input": "Disney\u2004",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2004 between two words",
              "input": "Walt\u2004Disney",
              "fold1": "walt\u2004disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2005 alone",
              "input": "\u2005",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2005 at the start",
              "input": "\u2005Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2005 at the end",
              "input": "Disney\u2005",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2005 between two words",
              "input": "Walt\u2005Disney",
              "fold1": "walt\u2005disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2006 alone",
              "input": "\u2006",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2006 at the start",
              "input": "\u2006Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2006 at the end",
              "input": "Disney\u2006",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2006 between two words",
              "input": "Walt\u2006Disney",
              "fold1": "walt\u2006disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2007 alone",
              "input": "\u2007",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2007 at the start",
              "input": "\u2007Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2007 at the end",
              "input": "Disney\u2007",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2007 between two words",
              "input": "Walt\u2007Disney",
              "fold1": "walt\u2007disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2008 alone",
              "input": "\u2008",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2008 at the start",
              "input": "\u2008Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2008 at the end",
              "input": "Disney\u2008",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2008 between two words",
              "input": "Walt\u2008Disney",
              "fold1": "walt\u2008disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+2009 alone",
              "input": "\u2009",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+2009 at the start",
              "input": "\u2009Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2009 at the end",
              "input": "Disney\u2009",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+2009 between two words",
              "input": "Walt\u2009Disney",
              "fold1": "walt\u2009disney",
              "key": "waltdisney"
            },
            {
              "name": "S U+200A alone",
              "input": "\u200a",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+200A at the start",
              "input": "\u200aDisney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+200A at the end",
              "input": "Disney\u200a",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+200A between two words",
              "input": "Walt\u200aDisney",
              "fold1": "walt\u200adisney",
              "key": "waltdisney"
            },
            {
              "name": "S U+202F alone",
              "input": "\u202f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+202F at the start",
              "input": "\u202fDisney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+202F at the end",
              "input": "Disney\u202f",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+202F between two words",
              "input": "Walt\u202fDisney",
              "fold1": "walt\u202fdisney",
              "key": "waltdisney"
            },
            {
              "name": "S U+205F alone",
              "input": "\u205f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+205F at the start",
              "input": "\u205fDisney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+205F at the end",
              "input": "Disney\u205f",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+205F between two words",
              "input": "Walt\u205fDisney",
              "fold1": "walt\u205fdisney",
              "key": "waltdisney"
            },
            {
              "name": "S U+3000 alone",
              "input": "\u3000",
              "fold1": "",
              "key": ""
            },
            {
              "name": "S U+3000 at the start",
              "input": "\u3000Disney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+3000 at the end",
              "input": "Disney\u3000",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "S U+3000 between two words",
              "input": "Walt\u3000Disney",
              "fold1": "walt\u3000disney",
              "key": "waltdisney"
            },
            {
              "name": "B U+0009 alone",
              "input": "\t",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+0009 between two words",
              "input": "Walt\tDisney",
              "fold1": "walt disney",
              "key": "waltdisney"
            },
            {
              "name": "B U+000A alone",
              "input": "\n",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+000A between two words",
              "input": "Walt\nDisney",
              "fold1": "walt disney",
              "key": "waltdisney"
            },
            {
              "name": "B U+000B alone",
              "input": "\u000b",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+000B between two words",
              "input": "Walt\u000bDisney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "B U+000C alone",
              "input": "\f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+000C between two words",
              "input": "Walt\fDisney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "B U+000D alone",
              "input": "\r",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+000D between two words",
              "input": "Walt\rDisney",
              "fold1": "walt disney",
              "key": "waltdisney"
            },
            {
              "name": "B U+001C alone",
              "input": "\u001c",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+001C between two words",
              "input": "Walt\u001cDisney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "B U+001D alone",
              "input": "\u001d",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+001D between two words",
              "input": "Walt\u001dDisney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "B U+001E alone",
              "input": "\u001e",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+001E between two words",
              "input": "Walt\u001eDisney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "B U+001F alone",
              "input": "\u001f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+001F between two words",
              "input": "Walt\u001fDisney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "B U+0085 alone",
              "input": "\u0085",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+0085 between two words",
              "input": "Walt\u0085Disney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "B U+2028 alone",
              "input": "\u2028",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+2028 between two words",
              "input": "Walt\u2028Disney",
              "fold1": "walt disney",
              "key": "waltdisney"
            },
            {
              "name": "B U+2029 alone",
              "input": "\u2029",
              "fold1": "",
              "key": ""
            },
            {
              "name": "B U+2029 between two words",
              "input": "Walt\u2029Disney",
              "fold1": "walt disney",
              "key": "waltdisney"
            },
            {
              "name": "B U+115F alone",
              "input": "\u115f",
              "fold1": "\u115f",
              "key": ""
            },
            {
              "name": "B U+115F between two words",
              "input": "Walt\u115fDisney",
              "fold1": "walt\u115fdisney",
              "key": "waltdisney"
            },
            {
              "name": "B U+1160 alone",
              "input": "\u1160",
              "fold1": "\u1160",
              "key": ""
            },
            {
              "name": "B U+1160 between two words",
              "input": "Walt\u1160Disney",
              "fold1": "walt\u1160disney",
              "key": "waltdisney"
            },
            {
              "name": "B U+2800 alone",
              "input": "\u2800",
              "fold1": "\u2800",
              "key": ""
            },
            {
              "name": "B U+2800 between two words",
              "input": "Walt\u2800Disney",
              "fold1": "walt\u2800disney",
              "key": "waltdisney"
            },
            {
              "name": "B U+3164 alone",
              "input": "\u3164",
              "fold1": "\u3164",
              "key": ""
            },
            {
              "name": "B U+3164 between two words",
              "input": "Walt\u3164Disney",
              "fold1": "walt\u3164disney",
              "key": "waltdisney"
            },
            {
              "name": "B U+FFA0 alone",
              "input": "\uffa0",
              "fold1": "\uffa0",
              "key": ""
            },
            {
              "name": "B U+FFA0 between two words",
              "input": "Walt\uffa0Disney",
              "fold1": "walt\uffa0disney",
              "key": "waltdisney"
            },
            {
              "name": "D1 U+0000 alone",
              "input": "\u0000",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+0000 inside a word",
              "input": "Dis\u0000ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+000F alone",
              "input": "\u000f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+000F inside a word",
              "input": "Dis\u000fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+001F alone",
              "input": "\u001f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+001F inside a word",
              "input": "Dis\u001fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+007F alone",
              "input": "\u007f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+007F inside a word",
              "input": "Dis\u007fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+008F alone",
              "input": "\u008f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+008F inside a word",
              "input": "Dis\u008fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+009F alone",
              "input": "\u009f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+009F inside a word",
              "input": "Dis\u009fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+00AD alone",
              "input": "\u00ad",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+00AD inside a word",
              "input": "Dis\u00adney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+0600 alone",
              "input": "\u0600",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+0600 inside a word",
              "input": "Dis\u0600ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+0602 alone",
              "input": "\u0602",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+0602 inside a word",
              "input": "Dis\u0602ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+0605 alone",
              "input": "\u0605",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+0605 inside a word",
              "input": "Dis\u0605ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+061C alone",
              "input": "\u061c",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+061C inside a word",
              "input": "Dis\u061cney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+06DD alone",
              "input": "\u06dd",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+06DD inside a word",
              "input": "Dis\u06ddney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+070F alone",
              "input": "\u070f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+070F inside a word",
              "input": "Dis\u070fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+0890 alone",
              "input": "\u0890",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+0890 inside a word",
              "input": "Dis\u0890ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+0891 alone",
              "input": "\u0891",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+0891 inside a word",
              "input": "Dis\u0891ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+08E2 alone",
              "input": "\u08e2",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+08E2 inside a word",
              "input": "Dis\u08e2ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+180E alone",
              "input": "\u180e",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+180E inside a word",
              "input": "Dis\u180eney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+200B alone",
              "input": "\u200b",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+200B inside a word",
              "input": "Dis\u200bney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+200E alone",
              "input": "\u200e",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+200E inside a word",
              "input": "Dis\u200eney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+200F alone",
              "input": "\u200f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+200F inside a word",
              "input": "Dis\u200fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+202A alone",
              "input": "\u202a",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+202A inside a word",
              "input": "Dis\u202aney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+202C alone",
              "input": "\u202c",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+202C inside a word",
              "input": "Dis\u202cney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+202E alone",
              "input": "\u202e",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+202E inside a word",
              "input": "Dis\u202eney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+2060 alone",
              "input": "\u2060",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+2060 inside a word",
              "input": "Dis\u2060ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+2062 alone",
              "input": "\u2062",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+2062 inside a word",
              "input": "Dis\u2062ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+2064 alone",
              "input": "\u2064",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+2064 inside a word",
              "input": "Dis\u2064ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+2066 alone",
              "input": "\u2066",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+2066 inside a word",
              "input": "Dis\u2066ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+206A alone",
              "input": "\u206a",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+206A inside a word",
              "input": "Dis\u206aney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+206F alone",
              "input": "\u206f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+206F inside a word",
              "input": "Dis\u206fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+FEFF alone",
              "input": "\ufeff",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+FEFF inside a word",
              "input": "Dis\ufeffney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+FFF9 alone",
              "input": "\ufff9",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+FFF9 inside a word",
              "input": "Dis\ufff9ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+FFFA alone",
              "input": "\ufffa",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+FFFA inside a word",
              "input": "Dis\ufffaney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+FFFB alone",
              "input": "\ufffb",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+FFFB inside a word",
              "input": "Dis\ufffbney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+110BD alone",
              "input": "\ud804\udcbd",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+110BD inside a word",
              "input": "Dis\ud804\udcbdney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+110CD alone",
              "input": "\ud804\udccd",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+110CD inside a word",
              "input": "Dis\ud804\udccdney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+13430 alone",
              "input": "\ud80d\udc30",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+13430 inside a word",
              "input": "Dis\ud80d\udc30ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+13437 alone",
              "input": "\ud80d\udc37",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+13437 inside a word",
              "input": "Dis\ud80d\udc37ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+1343F alone",
              "input": "\ud80d\udc3f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+1343F inside a word",
              "input": "Dis\ud80d\udc3fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+1BCA0 alone",
              "input": "\ud82f\udca0",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+1BCA0 inside a word",
              "input": "Dis\ud82f\udca0ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+1BCA1 alone",
              "input": "\ud82f\udca1",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+1BCA1 inside a word",
              "input": "Dis\ud82f\udca1ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+1BCA3 alone",
              "input": "\ud82f\udca3",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+1BCA3 inside a word",
              "input": "Dis\ud82f\udca3ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+1D173 alone",
              "input": "\ud834\udd73",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+1D173 inside a word",
              "input": "Dis\ud834\udd73ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+1D176 alone",
              "input": "\ud834\udd76",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+1D176 inside a word",
              "input": "Dis\ud834\udd76ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+1D17A alone",
              "input": "\ud834\udd7a",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+1D17A inside a word",
              "input": "Dis\ud834\udd7aney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+E0001 alone",
              "input": "\udb40\udc01",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+E0001 inside a word",
              "input": "Dis\udb40\udc01ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+E0020 alone",
              "input": "\udb40\udc20",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+E0020 inside a word",
              "input": "Dis\udb40\udc20ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+E004F alone",
              "input": "\udb40\udc4f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+E004F inside a word",
              "input": "Dis\udb40\udc4fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D1 U+E007F alone",
              "input": "\udb40\udc7f",
              "fold1": "",
              "key": ""
            },
            {
              "name": "D1 U+E007F inside a word",
              "input": "Dis\udb40\udc7fney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "D2 U+034F alone",
              "input": "\u034f",
              "fold1": "\u034f",
              "key": ""
            },
            {
              "name": "D2 U+034F inside a word",
              "input": "Dis\u034fney",
              "fold1": "dis\u034fney",
              "key": "disney"
            },
            {
              "name": "D2 U+115F alone",
              "input": "\u115f",
              "fold1": "\u115f",
              "key": ""
            },
            {
              "name": "D2 U+115F inside a word",
              "input": "Dis\u115fney",
              "fold1": "dis\u115fney",
              "key": "disney"
            },
            {
              "name": "D2 U+1160 alone",
              "input": "\u1160",
              "fold1": "\u1160",
              "key": ""
            },
            {
              "name": "D2 U+1160 inside a word",
              "input": "Dis\u1160ney",
              "fold1": "dis\u1160ney",
              "key": "disney"
            },
            {
              "name": "D2 U+17B4 alone",
              "input": "\u17b4",
              "fold1": "\u17b4",
              "key": ""
            },
            {
              "name": "D2 U+17B4 inside a word",
              "input": "Dis\u17b4ney",
              "fold1": "dis\u17b4ney",
              "key": "disney"
            },
            {
              "name": "D2 U+17B5 alone",
              "input": "\u17b5",
              "fold1": "\u17b5",
              "key": ""
            },
            {
              "name": "D2 U+17B5 inside a word",
              "input": "Dis\u17b5ney",
              "fold1": "dis\u17b5ney",
              "key": "disney"
            },
            {
              "name": "D2 U+180B alone",
              "input": "\u180b",
              "fold1": "\u180b",
              "key": ""
            },
            {
              "name": "D2 U+180B inside a word",
              "input": "Dis\u180bney",
              "fold1": "dis\u180bney",
              "key": "disney"
            },
            {
              "name": "D2 U+180C alone",
              "input": "\u180c",
              "fold1": "\u180c",
              "key": ""
            },
            {
              "name": "D2 U+180C inside a word",
              "input": "Dis\u180cney",
              "fold1": "dis\u180cney",
              "key": "disney"
            },
            {
              "name": "D2 U+180D alone",
              "input": "\u180d",
              "fold1": "\u180d",
              "key": ""
            },
            {
              "name": "D2 U+180D inside a word",
              "input": "Dis\u180dney",
              "fold1": "dis\u180dney",
              "key": "disney"
            },
            {
              "name": "D2 U+180F alone",
              "input": "\u180f",
              "fold1": "\u180f",
              "key": ""
            },
            {
              "name": "D2 U+180F inside a word",
              "input": "Dis\u180fney",
              "fold1": "dis\u180fney",
              "key": "disney"
            },
            {
              "name": "D2 U+2065 alone",
              "input": "\u2065",
              "fold1": "\u2065",
              "key": ""
            },
            {
              "name": "D2 U+2065 inside a word",
              "input": "Dis\u2065ney",
              "fold1": "dis\u2065ney",
              "key": "disney"
            },
            {
              "name": "D2 U+3164 alone",
              "input": "\u3164",
              "fold1": "\u3164",
              "key": ""
            },
            {
              "name": "D2 U+3164 inside a word",
              "input": "Dis\u3164ney",
              "fold1": "dis\u3164ney",
              "key": "disney"
            },
            {
              "name": "D2 U+FE00 alone",
              "input": "\ufe00",
              "fold1": "\ufe00",
              "key": ""
            },
            {
              "name": "D2 U+FE00 inside a word",
              "input": "Dis\ufe00ney",
              "fold1": "dis\ufe00ney",
              "key": "disney"
            },
            {
              "name": "D2 U+FE07 alone",
              "input": "\ufe07",
              "fold1": "\ufe07",
              "key": ""
            },
            {
              "name": "D2 U+FE07 inside a word",
              "input": "Dis\ufe07ney",
              "fold1": "dis\ufe07ney",
              "key": "disney"
            },
            {
              "name": "D2 U+FE0F alone",
              "input": "\ufe0f",
              "fold1": "\ufe0f",
              "key": ""
            },
            {
              "name": "D2 U+FE0F inside a word",
              "input": "Dis\ufe0fney",
              "fold1": "dis\ufe0fney",
              "key": "disney"
            },
            {
              "name": "D2 U+FFA0 alone",
              "input": "\uffa0",
              "fold1": "\uffa0",
              "key": ""
            },
            {
              "name": "D2 U+FFA0 inside a word",
              "input": "Dis\uffa0ney",
              "fold1": "dis\uffa0ney",
              "key": "disney"
            },
            {
              "name": "D2 U+FFF0 alone",
              "input": "\ufff0",
              "fold1": "\ufff0",
              "key": ""
            },
            {
              "name": "D2 U+FFF0 inside a word",
              "input": "Dis\ufff0ney",
              "fold1": "dis\ufff0ney",
              "key": "disney"
            },
            {
              "name": "D2 U+FFF4 alone",
              "input": "\ufff4",
              "fold1": "\ufff4",
              "key": ""
            },
            {
              "name": "D2 U+FFF4 inside a word",
              "input": "Dis\ufff4ney",
              "fold1": "dis\ufff4ney",
              "key": "disney"
            },
            {
              "name": "D2 U+FFF8 alone",
              "input": "\ufff8",
              "fold1": "\ufff8",
              "key": ""
            },
            {
              "name": "D2 U+FFF8 inside a word",
              "input": "Dis\ufff8ney",
              "fold1": "dis\ufff8ney",
              "key": "disney"
            },
            {
              "name": "D2 U+E0000 alone",
              "input": "\udb40\udc00",
              "fold1": "\udb40\udc00",
              "key": ""
            },
            {
              "name": "D2 U+E0000 inside a word",
              "input": "Dis\udb40\udc00ney",
              "fold1": "dis\udb40\udc00ney",
              "key": "disney"
            },
            {
              "name": "D2 U+E0002 alone",
              "input": "\udb40\udc02",
              "fold1": "\udb40\udc02",
              "key": ""
            },
            {
              "name": "D2 U+E0002 inside a word",
              "input": "Dis\udb40\udc02ney",
              "fold1": "dis\udb40\udc02ney",
              "key": "disney"
            },
            {
              "name": "D2 U+E0010 alone",
              "input": "\udb40\udc10",
              "fold1": "\udb40\udc10",
              "key": ""
            },
            {
              "name": "D2 U+E0010 inside a word",
              "input": "Dis\udb40\udc10ney",
              "fold1": "dis\udb40\udc10ney",
              "key": "disney"
            },
            {
              "name": "D2 U+E001F alone",
              "input": "\udb40\udc1f",
              "fold1": "\udb40\udc1f",
              "key": ""
            },
            {
              "name": "D2 U+E001F inside a word",
              "input": "Dis\udb40\udc1fney",
              "fold1": "dis\udb40\udc1fney",
              "key": "disney"
            },
            {
              "name": "D2 U+E0080 alone",
              "input": "\udb40\udc80",
              "fold1": "\udb40\udc80",
              "key": ""
            },
            {
              "name": "D2 U+E0080 inside a word",
              "input": "Dis\udb40\udc80ney",
              "fold1": "dis\udb40\udc80ney",
              "key": "disney"
            },
            {
              "name": "D2 U+E00BF alone",
              "input": "\udb40\udcbf",
              "fold1": "\udb40\udcbf",
              "key": ""
            },
            {
              "name": "D2 U+E00BF inside a word",
              "input": "Dis\udb40\udcbfney",
              "fold1": "dis\udb40\udcbfney",
              "key": "disney"
            },
            {
              "name": "D2 U+E00FF alone",
              "input": "\udb40\udcff",
              "fold1": "\udb40\udcff",
              "key": ""
            },
            {
              "name": "D2 U+E00FF inside a word",
              "input": "Dis\udb40\udcffney",
              "fold1": "dis\udb40\udcffney",
              "key": "disney"
            },
            {
              "name": "D2 U+E0100 alone",
              "input": "\udb40\udd00",
              "fold1": "\udb40\udd00",
              "key": ""
            },
            {
              "name": "D2 U+E0100 inside a word",
              "input": "Dis\udb40\udd00ney",
              "fold1": "dis\udb40\udd00ney",
              "key": "disney"
            },
            {
              "name": "D2 U+E0177 alone",
              "input": "\udb40\udd77",
              "fold1": "\udb40\udd77",
              "key": ""
            },
            {
              "name": "D2 U+E0177 inside a word",
              "input": "Dis\udb40\udd77ney",
              "fold1": "dis\udb40\udd77ney",
              "key": "disney"
            },
            {
              "name": "D2 U+E01EF alone",
              "input": "\udb40\uddef",
              "fold1": "\udb40\uddef",
              "key": ""
            },
            {
              "name": "D2 U+E01EF inside a word",
              "input": "Dis\udb40\uddefney",
              "fold1": "dis\udb40\uddefney",
              "key": "disney"
            },
            {
              "name": "D2 U+E01F0 alone",
              "input": "\udb40\uddf0",
              "fold1": "\udb40\uddf0",
              "key": ""
            },
            {
              "name": "D2 U+E01F0 inside a word",
              "input": "Dis\udb40\uddf0ney",
              "fold1": "dis\udb40\uddf0ney",
              "key": "disney"
            },
            {
              "name": "D2 U+E08F7 alone",
              "input": "\udb42\udcf7",
              "fold1": "\udb42\udcf7",
              "key": ""
            },
            {
              "name": "D2 U+E08F7 inside a word",
              "input": "Dis\udb42\udcf7ney",
              "fold1": "dis\udb42\udcf7ney",
              "key": "disney"
            },
            {
              "name": "D2 U+E0FFF alone",
              "input": "\udb43\udfff",
              "fold1": "\udb43\udfff",
              "key": ""
            },
            {
              "name": "D2 U+E0FFF inside a word",
              "input": "Dis\udb43\udfffney",
              "fold1": "dis\udb43\udfffney",
              "key": "disney"
            },
            {
              "name": "D3 U+2800 alone",
              "input": "\u2800",
              "fold1": "\u2800",
              "key": ""
            },
            {
              "name": "D3 U+2800 inside a word",
              "input": "Dis\u2800ney",
              "fold1": "dis\u2800ney",
              "key": "disney"
            },
            {
              "name": "ZWJ inside a Latin word",
              "input": "Dis\u200dney",
              "fold1": "dis\u200dney",
              "key": "disney"
            },
            {
              "name": "ZWJ at the start",
              "input": "\u200dDisney",
              "fold1": "\u200ddisney",
              "key": "disney"
            },
            {
              "name": "ZWJ alone",
              "input": "\u200d",
              "fold1": "\u200d",
              "key": ""
            },
            {
              "name": "ZWJ after a space",
              "input": "Walt \u200dDisney",
              "fold1": "walt \u200ddisney",
              "key": "waltdisney"
            },
            {
              "name": "ZWJ between two spaces",
              "input": "Walt \u200d Disney",
              "fold1": "walt \u200d disney",
              "key": "waltdisney"
            },
            {
              "name": "ZWJ after a dropped code point",
              "input": "s\u034f\u200d",
              "fold1": "s\u034f\u200d",
              "key": "s"
            },
            {
              "name": "ZWJ after U+0301",
              "input": "e\u0301\u200d",
              "fold1": "e\u0301\u200d",
              "key": "e\u0301"
            },
            {
              "name": "ZWJ after U+036F (last of L)",
              "input": "e\u036f\u200d",
              "fold1": "e\u036f\u200d",
              "key": "e\u036f"
            },
            {
              "name": "ZWJ after U+0370 (first after L)",
              "input": "\u0370\u200d",
              "fold1": "\u0370\u200d",
              "key": "\u0370\u200d"
            },
            {
              "name": "ZWJ after U+024F (last of Latin Extended-B)",
              "input": "\u024f\u200d",
              "fold1": "\u024f\u200d",
              "key": "\u024f"
            },
            {
              "name": "ZWJ after U+0250 (first after it)",
              "input": "\u0250\u200d",
              "fold1": "\u0250\u200d",
              "key": "\u0250\u200d"
            },
            {
              "name": "ZWJ after a non-Latin letter",
              "input": "\u6771\u200d\u6620",
              "fold1": "\u6771\u200d\u6620",
              "key": "\u6771\u200d\u6620"
            },
            {
              "name": "ZWJ at the end after a non-Latin letter",
              "input": "\u6771\u6620\u200d",
              "fold1": "\u6771\u6620\u200d",
              "key": "\u6771\u6620\u200d"
            },
            {
              "name": "ZWJ after a non-Latin letter and U+3000",
              "input": "\u6771\u6620\u3000\u200dAnimation",
              "fold1": "\u6771\u6620\u3000\u200danimation",
              "key": "\u6771\u6620animation"
            },
            {
              "name": "ZWJ after a non-Latin letter and U+000B (not in G)",
              "input": "\u6771\u000b\u200dx",
              "fold1": "\u6771\u200dx",
              "key": "\u6771\u200dx"
            },
            {
              "name": "ZWNJ inside a Latin word",
              "input": "Dis\u200cney",
              "fold1": "dis\u200cney",
              "key": "disney"
            },
            {
              "name": "ZWNJ at the start",
              "input": "\u200cDisney",
              "fold1": "\u200cdisney",
              "key": "disney"
            },
            {
              "name": "ZWNJ alone",
              "input": "\u200c",
              "fold1": "\u200c",
              "key": ""
            },
            {
              "name": "ZWNJ after a space",
              "input": "Walt \u200cDisney",
              "fold1": "walt \u200cdisney",
              "key": "waltdisney"
            },
            {
              "name": "ZWNJ between two spaces",
              "input": "Walt \u200c Disney",
              "fold1": "walt \u200c disney",
              "key": "waltdisney"
            },
            {
              "name": "ZWNJ after a dropped code point",
              "input": "s\u034f\u200c",
              "fold1": "s\u034f\u200c",
              "key": "s"
            },
            {
              "name": "ZWNJ after U+0301",
              "input": "e\u0301\u200c",
              "fold1": "e\u0301\u200c",
              "key": "e\u0301"
            },
            {
              "name": "ZWNJ after U+036F (last of L)",
              "input": "e\u036f\u200c",
              "fold1": "e\u036f\u200c",
              "key": "e\u036f"
            },
            {
              "name": "ZWNJ after U+0370 (first after L)",
              "input": "\u0370\u200c",
              "fold1": "\u0370\u200c",
              "key": "\u0370\u200c"
            },
            {
              "name": "ZWNJ after U+024F (last of Latin Extended-B)",
              "input": "\u024f\u200c",
              "fold1": "\u024f\u200c",
              "key": "\u024f"
            },
            {
              "name": "ZWNJ after U+0250 (first after it)",
              "input": "\u0250\u200c",
              "fold1": "\u0250\u200c",
              "key": "\u0250\u200c"
            },
            {
              "name": "ZWNJ after a non-Latin letter",
              "input": "\u6771\u200c\u6620",
              "fold1": "\u6771\u200c\u6620",
              "key": "\u6771\u200c\u6620"
            },
            {
              "name": "ZWNJ at the end after a non-Latin letter",
              "input": "\u6771\u6620\u200c",
              "fold1": "\u6771\u6620\u200c",
              "key": "\u6771\u6620\u200c"
            },
            {
              "name": "ZWNJ after a non-Latin letter and U+3000",
              "input": "\u6771\u6620\u3000\u200cAnimation",
              "fold1": "\u6771\u6620\u3000\u200canimation",
              "key": "\u6771\u6620animation"
            },
            {
              "name": "ZWNJ after a non-Latin letter and U+000B (not in G)",
              "input": "\u6771\u000b\u200cx",
              "fold1": "\u6771\u200cx",
              "key": "\u6771\u200cx"
            },
            {
              "name": "a run of joiners inside a Latin word",
              "input": "Dis\u200d\u200c\u200dney",
              "fold1": "dis\u200d\u200c\u200dney",
              "key": "disney"
            },
            {
              "name": "a run of joiners after a non-Latin letter",
              "input": "\u6771\u200d\u200c\u200d\u6620",
              "fold1": "\u6771\u200d\u200c\u200d\u6620",
              "key": "\u6771\u200d\u200c\u200d\u6620"
            },
            {
              "name": "walk: Walt U+2009 U+200D Disney",
              "input": "Walt\u2009\u200dDisney",
              "fold1": "walt\u2009\u200ddisney",
              "key": "waltdisney"
            },
            {
              "name": "walk: U+3000 U+200D Walt Disney",
              "input": "\u3000\u200dWalt Disney",
              "fold1": "\u200dwalt disney",
              "key": "waltdisney"
            },
            {
              "name": "walk: Walt U+3000 U+200D Disney",
              "input": "Walt\u3000\u200dDisney",
              "fold1": "walt\u3000\u200ddisney",
              "key": "waltdisney"
            },
            {
              "name": "walk: Walt U+3164 Disney",
              "input": "Walt\u3164Disney",
              "fold1": "walt\u3164disney",
              "key": "waltdisney"
            },
            {
              "name": "walk: Walt U+000B Disney",
              "input": "Walt\u000bDisney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "walk: Walt U+0085 Disney",
              "input": "Walt\u0085Disney",
              "fold1": "waltdisney",
              "key": "waltdisney"
            },
            {
              "name": "walk: Dis U+E0002 ney",
              "input": "Dis\udb40\udc02ney",
              "fold1": "dis\udb40\udc02ney",
              "key": "disney"
            },
            {
              "name": "walk: Pix U+2065 ar",
              "input": "Pix\u2065ar",
              "fold1": "pix\u2065ar",
              "key": "pixar"
            },
            {
              "name": "walk: Pathe U+0301 U+200D",
              "input": "Pathe\u0301\u200d",
              "fold1": "pathe\u0301\u200d",
              "key": "pathe\u0301"
            },
            {
              "name": "walk: Dis U+200D U+200C U+200D ney",
              "input": "Dis\u200d\u200c\u200dney",
              "fold1": "dis\u200d\u200c\u200dney",
              "key": "disney"
            },
            {
              "name": "walk: Pixars U+034F U+200D",
              "input": "Pixars\u034f\u200d",
              "fold1": "pixars\u034f\u200d",
              "key": "pixars"
            },
            {
              "name": "walk: Devanagari U+0915 U+094D U+200D U+0937",
              "input": "\u0915\u094d\u200d\u0937",
              "fold1": "\u0915\u094d\u200d\u0937",
              "key": "\u0915\u094d\u200d\u0937"
            },
            {
              "name": "walk: U+6771 U+6620 U+3000 U+200D Animation",
              "input": "\u6771\u6620\u3000\u200dAnimation",
              "fold1": "\u6771\u6620\u3000\u200danimation",
              "key": "\u6771\u6620animation"
            },
            {
              "name": "walk: U+6771 U+000B U+200D x",
              "input": "\u6771\u000b\u200dx",
              "fold1": "\u6771\u200dx",
              "key": "\u6771\u200dx"
            },
            {
              "name": "walk: U+6771 U+6620 U+200D (trailing)",
              "input": "\u6771\u6620\u200d",
              "fold1": "\u6771\u6620\u200d",
              "key": "\u6771\u6620\u200d"
            },
            {
              "name": "fold 1 trims, fold 2 removes the joiner: U+3000 U+200D Disney",
              "input": "\u3000\u200dDisney",
              "fold1": "\u200ddisney",
              "key": "disney"
            },
            {
              "name": "empty key: U+3164",
              "input": "\u3164",
              "fold1": "\u3164",
              "key": ""
            },
            {
              "name": "empty key: U+2800 U+3164",
              "input": "\u2800\u3164",
              "fold1": "\u2800\u3164",
              "key": ""
            },
            {
              "name": "empty key: spaces and joiners",
              "input": " \u200d\u3000\u200c ",
              "fold1": "\u200d\u3000\u200c",
              "key": ""
            },
            {
              "name": "a lone high surrogate inside a word",
              "input": "Dis\ud800ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "a lone low surrogate inside a word",
              "input": "Dis\udc00ney",
              "fold1": "disney",
              "key": "disney"
            },
            {
              "name": "a lone surrogate alone",
              "input": "\udbff",
              "fold1": "",
              "key": ""
            },
            {
              "name": "a pair is kept",
              "input": "x\ud83c\udfacY",
              "fold1": "x\ud83c\udfacy",
              "key": "x\ud83c\udfacy"
            },
            {
              "name": "a joiner after a supplementary letter is kept",
              "input": "\ud83c\udfac\u200d",
              "fold1": "\ud83c\udfac\u200d",
              "key": "\ud83c\udfac\u200d"
            },
            {
              "name": "BabyTV",
              "input": "BabyTV",
              "fold1": "babytv",
              "key": "babytv"
            },
            {
              "name": "Baby TV",
              "input": "Baby TV",
              "fold1": "baby tv",
              "key": "babytv"
            }
          ]
        }
        """.trimIndent() + "\n"
}
