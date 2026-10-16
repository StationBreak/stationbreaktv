# Channel rules format

Draft. This block fixes the field names; the rest of the document follows in later commits.

```json
{
  "formatVersion": 1,
  "name": "Saturday Morning Cartoons",
  "seed": "12345678901234567890",
  "sources": [{ "type": "genre", "name": "Animation" }],
  "exclusions": [{ "type": "series", "id": "00000000000000000000000000000001" }],
  "maxRating": "TV-Y7",
  "ordering": { "mode": "SHUFFLE" },
  "timing": {
    "blockLength": 2,
    "alignToGrid": true,
    "epochHours": 24,
    "broadcastDay": { "utcOffsetMinutes": -300, "start": "06:00" }
  },
  "filler": { "mode": "between-every-N", "every": 3, "source": { "type": "tag", "name": "Station IDs" } }
}
```
