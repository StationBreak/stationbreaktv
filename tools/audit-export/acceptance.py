# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""The app's own acceptance rules, written again for the exporter (see FORMAT.md, "Acceptance rules").

Two stages, each mirroring the code the app runs on the same data:

1. `held_row` / `series_row` / `collection_row`: a server answer as the library cache holds it (data:jellyfin's
   `toMediaItem`, `toSeriesInfo`, `studiosOf`, `createdAtOf`, `premiereDateOf`, `duration`).
2. `build_snapshot`: the rows as the rule snapshot has them (data:library's `ScheduleInputs.read`, `RuleItemMapper`
   and `RuleInputs`).

The extras filter is not here: the audit applies the app's own `ExtrasFilter`, as the planner does, so a snapshot
is the one `ruleSnapshot` reads, before it. Standard library only.
"""

import re

MAX_TIME_MS = 1 << 52
TICKS_PER_MS = 10_000
MAX_STUDIOS = 16
MAX_STUDIO_NAME = 256
# The file's own bounds (FORMAT.md): the reader refuses a longer text or list, so the exporter keeps within them.
MAX_TEXT = 1024
MAX_LIST = 1024
LONG_MAX = (1 << 63) - 1
LONG_MIN = -(1 << 63)

ID_RE = re.compile(r"^[0-9a-f]{32}$")
INT_MIN = -(1 << 31)
INT_MAX = (1 << 31) - 1


def u16len(text):
    """The length of [text] in UTF-16 code units, as Kotlin counts a String (the file's bounds are in these)."""
    return len(text.encode("utf-16-le", "surrogatepass")) // 2


class Counts(dict):
    """Counters by name: what was dropped or cut, and why. Numbers only: never a title or a name."""

    def bump(self, name):
        self[name] = self.get(name, 0) + 1


# --- CanonicalId.parse ---------------------------------------------------------------------------------------------


def canonical_id(raw):
    """`CanonicalId.parseOrNull`: braces and hyphens removed, lower case, exactly 32 hex digits; else None."""
    if not isinstance(raw, str):
        return None
    text = raw
    if len(text) >= 2 and text[0] == "{" and text[-1] == "}":
        text = text[1:-1]
    digits = text.replace("-", "").lower()
    return digits if ID_RE.match(digits) else None


# --- dates and times -----------------------------------------------------------------------------------------------

_MOMENT = re.compile(
    r"^(?P<year>[+-]\d{4,10}|\d{4})-(?P<month>\d{2})-(?P<day>\d{2})"
    r"[Tt](?P<hour>\d{2}):(?P<minute>\d{2})(?::(?P<second>\d{2})(?:\.(?P<fraction>\d{0,9}))?)?"
    r"(?P<offset>[Zz]|[+-]\d{2}:\d{2}(?::\d{2})?)?$"
)
MAX_OFFSET_SECONDS = 18 * 3600


def _days_from_civil(year, month, day):
    # Howard Hinnant's algorithm: days since 1970-01-01 of a proleptic Gregorian date.
    y = year - (month <= 2)
    era = y // 400
    yoe = y - era * 400
    doy = (153 * (month + (-3 if month > 2 else 9)) + 2) // 5 + day - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


def _civil_from_days(days):
    z = days + 719468
    era = z // 146097
    doe = z - era * 146097
    yoe = (doe - doe // 1460 + doe // 36524 - doe // 146096) // 365
    y = yoe + era * 400
    doy = doe - (365 * yoe + yoe // 4 - yoe // 100)
    mp = (5 * doy + 2) // 153
    d = doy - (153 * mp + 2) // 5 + 1
    m = mp + (3 if mp < 10 else -9)
    return y + (m <= 2), m, d


def _is_leap(year):
    return year % 4 == 0 and (year % 100 != 0 or year % 400 == 0)


def _days_in_month(year, month):
    return 29 if month == 2 and _is_leap(year) else (31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]


def _moment(text):
    """The instant of `text` as (epoch seconds, nanoseconds), read as `DateTimeFormatter.ISO_DATE_TIME` reads it (a
    time with no offset is UTC), or None for anything that is not a valid date and time."""
    if not isinstance(text, str):
        return None
    m = _MOMENT.match(text)
    if m is None:
        return None
    year = int(m["year"])
    month, day, hour, minute = int(m["month"]), int(m["day"]), int(m["hour"]), int(m["minute"])
    second = int(m["second"] or 0)
    valid = (
        abs(year) <= 999_999_999
        and 1 <= month <= 12
        and 1 <= day <= _days_in_month(year, month)
        and hour <= 23
        and minute <= 59
        and second <= 59
    )
    offset = _offset_seconds(m["offset"])
    if not valid or offset is None:
        return None
    fraction = (m["fraction"] or "").ljust(9, "0")
    seconds = _days_from_civil(year, month, day) * 86400 + hour * 3600 + minute * 60 + second - offset
    return seconds, int(fraction)


def _offset_seconds(text):
    if text is None or text in ("Z", "z"):
        return 0
    sign = -1 if text[0] == "-" else 1
    parts = [int(p) for p in text[1:].split(":")]
    hours, minutes = parts[0], parts[1]
    seconds = parts[2] if len(parts) == 3 else 0
    total = hours * 3600 + minutes * 60 + seconds
    if minutes > 59 or seconds > 59 or total > MAX_OFFSET_SECONDS:
        return None
    return sign * total


def created_at_ms(text):
    """`createdAtOf`: the creation moment in whole milliseconds (the seconds floored first), or None."""
    moment = _moment(text)
    if moment is None:
        return None
    seconds, nanos = moment
    ms = seconds * 1000 + nanos // 1_000_000
    return ms if LONG_MIN <= seconds * 1000 <= LONG_MAX and LONG_MIN <= ms <= LONG_MAX else None


def premiere_date(text):
    """`premiereDateOf`: the UTC calendar date `YYYY-MM-DD` of the premiere, its year 1 to 9999, or None."""
    moment = _moment(text)
    if moment is None:
        return None
    year, month, day = _civil_from_days(moment[0] // 86400)
    return f"{year:04d}-{month:02d}-{day:02d}" if 1 <= year <= 9999 else None


def runtime_ms(ticks):
    """`duration`: `floorDiv(ticks, 10000)` milliseconds; a missing, zero or negative value is no runtime."""
    return ticks // TICKS_PER_MS if isinstance(ticks, int) and not isinstance(ticks, bool) and ticks > 0 else None


# --- names ---------------------------------------------------------------------------------------------------------

# `cleanStudioName` (core:model NameFold.kt): the same fixed tables.
_STRIPPED = [
    (0x0000, 0x001F), (0x007F, 0x009F), (0x00AD, 0x00AD), (0x0600, 0x0605), (0x061C, 0x061C), (0x06DD, 0x06DD),
    (0x070F, 0x070F), (0x0890, 0x0891), (0x08E2, 0x08E2), (0x180E, 0x180E), (0x200B, 0x200B), (0x200E, 0x200F),
    (0x202A, 0x202E), (0x2060, 0x2064), (0x2066, 0x206F), (0xFEFF, 0xFEFF), (0xFFF9, 0xFFFB), (0x110BD, 0x110BD),
    (0x110CD, 0x110CD), (0x13430, 0x1343F), (0x1BCA0, 0x1BCA3), (0x1D173, 0x1D17A), (0xE0001, 0xE0001),
    (0xE0020, 0xE007F),
]  # fmt: skip
_TRIMMED = [
    (0x0020, 0x0020), (0x00A0, 0x00A0), (0x1680, 0x1680), (0x2000, 0x200A), (0x202F, 0x202F), (0x205F, 0x205F),
    (0x3000, 0x3000),
]  # fmt: skip
_SPACED = {0x09, 0x0D, 0x0A, 0x2028, 0x2029}


def _in(table, cp):
    return any(lo <= cp <= hi for lo, hi in table)


def clean_studio_name(text):
    """`cleanStudioName`: controls and format characters removed, lone surrogates removed, tab, CR, LF, U+2028 and
    U+2029 made a space, and the ends trimmed of Unicode space separators."""
    out = []
    for ch in text:
        cp = ord(ch)
        if 0xD800 <= cp <= 0xDFFF:
            continue  # JSON text can carry a lone surrogate; Kotlin's loop drops a lone half and keeps no pair here
        if cp in _SPACED:
            out.append(" ")
        elif not (0x20 <= cp <= 0x7E) and _in(_STRIPPED, cp):
            continue
        else:
            out.append(ch)
    start, end = 0, len(out)
    while start < end and _in(_TRIMMED, ord(out[start])):
        start += 1
    while end > start and _in(_TRIMMED, ord(out[end - 1])):
        end -= 1
    return "".join(out[start:end])


def studios_of(raw, counts):
    """`studiosOf`: the studio names in the server's order, a null entry or one with no name dropped, a name over 256
    characters dropped, each cleaned and a name with nothing left dropped, at most 16 kept."""
    names = []
    for studio in raw or []:
        if studio is not None and not isinstance(studio, dict):
            raise BadAnswer("a studio is not an object")
        text = studio.get("Name") if studio is not None else None
        if text is not None and not isinstance(text, str):
            raise BadAnswer("a studio has a field of the wrong type")
        if text is None or u16len(text) > MAX_STUDIO_NAME:
            continue
        name = clean_studio_name(text)
        if name:
            names.append(name)
            if len(names) == MAX_STUDIOS:
                break
    return names


# --- stage 1: a server answer as the cache holds it ----------------------------------------------------------------

ITEM_KINDS = {"Movie": "Movie", "Series": "Series", "Season": "Season", "Episode": "Episode", "Video": "Video",
              "MusicVideo": "MusicVideo", "Folder": "Folder", "CollectionFolder": "Folder", "UserView": "Folder"}  # fmt: skip
MEMBER_KINDS = {"Movie": "movie", "Episode": "episode", "Series": "series", "Season": "season"}


class BadAnswer(Exception):
    """The server's answer is not what the app would accept: the app's decoder is strict about types, and fails the page."""


def _typed(dto, key, kinds, what):
    value = dto.get(key)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, kinds):
        raise BadAnswer(f"{what} has a field of the wrong type")
    return value


def _text_list(dto, key, counts, what):
    """A list of text as the cache holds it. An entry over the file's bound is dropped (counted), never cut."""
    value = dto.get(key)
    if value is None:
        return []
    if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
        raise BadAnswer(f"{what} has a field of the wrong type")
    kept = [v for v in value if u16len(v) <= MAX_TEXT]
    if len(kept) != len(value):
        counts.bump("text_entries_over_the_bound")
    return kept[:MAX_LIST]


def _name(dto, counts):
    name = _typed(dto, "Name", str, "an item") or ""
    if len(name) > MAX_TEXT // 2 and u16len(name) > MAX_TEXT:
        counts.bump("names_cut_to_the_bound")
        name = _cut(name)
    return name


def _cut(text):
    """The longest prefix of [text] within MAX_TEXT UTF-16 units, found in one pass over at most MAX_TEXT characters
    (never through a surrogate pair)."""
    units = 0
    for index, ch in enumerate(text[:MAX_TEXT]):
        units += 2 if ord(ch) > 0xFFFF else 1
        if units > MAX_TEXT:
            return text[:index]
    return text[:MAX_TEXT]


def _rating(dto, counts):
    rating = _typed(dto, "OfficialRating", str, "an item")
    if rating is not None and u16len(rating) > MAX_TEXT:
        counts.bump("ratings_over_the_bound")
        return None
    return rating


def held_row(dto, library_id, counts):
    """One item of an `Items` page as the cache holds it (`HeldRow`): fields as received, times worked out as
    `toMediaItem` works them out. [library_id] is the library the item was listed under, as the server sent it."""
    if not isinstance(dto, dict):
        raise BadAnswer("an item is not an object")
    item_id = _typed(dto, "Id", str, "an item")
    if item_id is None:
        raise BadAnswer("an item has no ID")
    kind = ITEM_KINDS.get(_typed(dto, "Type", str, "an item") or "", "Other")
    return {
        "itemId": item_id,
        "libraryId": library_id,
        "kind": kind,
        "seriesId": _typed(dto, "SeriesId", str, "an item"),
        "seasonId": _typed(dto, "SeasonId", str, "an item"),
        "seasonNumber": _int(dto, "ParentIndexNumber"),
        "episodeNumber": _int(dto, "IndexNumber"),
        "runtimeMs": runtime_ms(_typed(dto, "RunTimeTicks", int, "an item")),
        "createdAtMs": created_at_ms(_typed(dto, "DateCreated", str, "an item")),
        "premiereDate": premiere_date(_typed(dto, "PremiereDate", str, "an item")),
        "productionYear": _int(dto, "ProductionYear"),
        "deletedAtMs": None,
        "seenFromMs": 0,
        "officialRating": _rating(dto, counts),
        "genres": _text_list(dto, "Genres", counts, "an item"),
        "tags": _text_list(dto, "Tags", counts, "an item"),
        "studios": studios_of(_studio_list(dto, "an item"), counts),
        "name": _name(dto, counts),
    }


def _int(dto, key):
    value = dto.get(key)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or not INT_MIN <= value <= INT_MAX:
        raise BadAnswer("an item has a field of the wrong type")
    return value


def _studio_list(dto, what):
    value = dto.get("Studios")
    if value is not None and not isinstance(value, list):
        raise BadAnswer(f"{what} has a field of the wrong type")
    return value


def series_row(dto, counts):
    """One series of a `Series` page (`SeriesInfo`, as `HeldSeries` holds it)."""
    if not isinstance(dto, dict):
        raise BadAnswer("a series is not an object")
    series_id = _typed(dto, "Id", str, "a series")
    if series_id is None:
        raise BadAnswer("a series has no ID")
    return {
        "seriesId": series_id,
        "name": _name(dto, counts),
        "officialRating": _rating(dto, counts),
        "genres": _text_list(dto, "Genres", counts, "a series"),
        "tags": _text_list(dto, "Tags", counts, "a series"),
        "studios": studios_of(_studio_list(dto, "a series"), counts),
    }


def collection_row(dto, members, counts):
    """One BoxSet and its direct members (`HeldCollection`): a member's kind is what the cache keeps of its type."""
    if not isinstance(dto, dict):
        raise BadAnswer("a collection is not an object")
    collection_id = _typed(dto, "Id", str, "a collection")
    if collection_id is None:
        raise BadAnswer("a collection has no ID")
    kept = []
    for member in members:
        if not isinstance(member, dict) or not isinstance(member.get("Id"), str):
            raise BadAnswer("a collection member has no ID")
        kind = MEMBER_KINDS.get(member.get("Type") if isinstance(member.get("Type"), str) else "")
        if kind is not None:
            kept.append({"memberId": member["Id"], "kind": kind})
    return {"collectionId": collection_id, "name": _name(dto, counts), "members": kept}


# --- stage 2: the rows as the rule snapshot has them ---------------------------------------------------------------

_DATE = re.compile(r"^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")


def _schedule_problem(kind, premiere, year, series_id, season, episode):
    """`ScheduleItem.problem`: the first thing wrong with these fields, in the order the checks run, or None."""
    if premiere is not None and not (_DATE.match(premiere) and not premiere.startswith("0000-")):
        return "PremiereDateMalformed"
    if year is not None and not 1 <= year <= 9999:
        return "ProductionYearOutOfRange"
    if season is not None and season < 0:
        return "SeasonNegative"
    if episode is not None and episode < 0:
        return "EpisodeNegative"
    if kind == "Movie" and (series_id is not None or season is not None or episode is not None):
        return "MovieHasSeriesData"
    return None


def _drop_reason(row):
    """`ScheduleInputs.convert`: why the as-of read leaves this row out, or None. The checks run in this order."""
    if row["kind"] not in ("Movie", "Episode"):
        return "KindNotMovieOrEpisode"
    if canonical_id(row["itemId"]) is None:
        return "IdNotCanonical"
    if canonical_id(row["libraryId"]) is None:
        return "LibraryIdNotCanonical"
    if row["seriesId"] is not None and canonical_id(row["seriesId"]) is None:
        return "SeriesIdNotCanonical"
    if (row["runtimeMs"] or 0) <= 0:
        return "RuntimeMissing"
    if row["createdAtMs"] is None:
        return "CreatedAtMissing"
    if any(t is not None and not -MAX_TIME_MS <= t <= MAX_TIME_MS for t in (row["createdAtMs"], row["deletedAtMs"])):
        return "TimeOutOfRange"
    series_id = canonical_id(row["seriesId"]) if row["seriesId"] is not None else None
    return _schedule_problem(
        row["kind"], row["premiereDate"], row["productionYear"], series_id, row["seasonNumber"], row["episodeNumber"]
    )


def build_snapshot(rows, series_rows, collection_rows, libraries, cutoff_ms, collections_synced):
    """The `audit-snapshot` (a dict) for these rows, and the counts of what `ScheduleInputs.read` left out.

    `ScheduleInputs.read`: each row is converted or dropped by its first reason, the first row of an ID wins, and the
    items are in canonical-ID order. `RuleItemMapper`: a season ID that is not canonical reads as no season.
    `RuleInputs.series`: the series that an episode among the items points to, as held, in ID order, the first held
    row of an ID winning. `RuleInputs.collections`: every collection held, with its members in four sets by kind,
    in canonical-ID order, a member that is not a canonical ID left out.
    """
    counts = Counts()
    items = {}
    for row in rows:
        reason = _drop_reason(row)
        if reason is not None:
            counts.bump(reason)
            continue
        item_id = canonical_id(row["itemId"])
        if item_id in items:
            counts.bump("DuplicateId")
            continue
        items[item_id] = _item(item_id, row)
    ordered = [items[k] for k in sorted(items)]
    return {
        "format": "audit-snapshot",
        "version": 1,
        "cutoffMs": cutoff_ms,
        "collectionsSynced": collections_synced,
        "libraries": sorted({i for i in (canonical_id(x) for x in libraries) if i is not None}),
        "items": ordered,
        "series": _series(ordered, series_rows),
        "collections": _collections(collection_rows),
    }, counts


def _item(item_id, row):
    series_id = canonical_id(row["seriesId"]) if row["seriesId"] is not None else None
    season_id = canonical_id(row["seasonId"]) if row["seasonId"] is not None else None
    return {
        "id": item_id,
        "libraryId": canonical_id(row["libraryId"]),
        "seasonId": season_id,
        "kind": row["kind"],
        "runtimeMs": row["runtimeMs"],
        "createdAtMs": row["createdAtMs"],
        "deletedAtMs": row["deletedAtMs"],
        "premiereDate": row["premiereDate"],
        "productionYear": row["productionYear"],
        "seriesId": series_id,
        "seasonNumber": row["seasonNumber"],
        "episodeNumber": row["episodeNumber"],
        "officialRating": row["officialRating"],
        "genres": row["genres"],
        "tags": row["tags"],
        "studios": row["studios"],
        "name": row["name"],
    }


def _series(items, held):
    by_id = {}
    for row in held:
        series_id = canonical_id(row["seriesId"])
        if series_id is not None and series_id not in by_id:
            by_id[series_id] = row
    wanted = sorted({i["seriesId"] for i in items if i["kind"] == "Episode" and i["seriesId"] in by_id})
    return [
        {
            "id": series_id,
            "officialRating": by_id[series_id]["officialRating"],
            "genres": by_id[series_id]["genres"],
            "tags": by_id[series_id]["tags"],
            "studios": by_id[series_id]["studios"],
            "name": by_id[series_id]["name"],
        }
        for series_id in wanted
    ]


def _collections(held):
    seen = {}
    for collection in held:
        collection_id = canonical_id(collection["collectionId"])
        if collection_id is not None and collection_id not in seen:
            seen[collection_id] = collection
    out = []
    for collection_id in sorted(seen):
        collection = seen[collection_id]
        sets = {"movie": set(), "episode": set(), "series": set(), "season": set()}
        for member in collection["members"]:
            member_id = canonical_id(member["memberId"])
            if member_id is not None:
                sets[member["kind"]].add(member_id)
        out.append(
            {
                "id": collection_id,
                "movies": sorted(sets["movie"]),
                "episodes": sorted(sets["episode"]),
                "series": sorted(sets["series"]),
                "seasons": sorted(sets["season"]),
                "name": collection["name"],
            }
        )
    return out
