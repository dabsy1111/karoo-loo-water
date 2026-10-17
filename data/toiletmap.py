"""
Merges the National Public Toilet Map (toiletmap.gov.au, data.gov.au, CC BY 3.0 AU)
into the OpenStreetMap places.

For each OSM toilet, the nearest Toilet Map facility within MATCH_RADIUS_M is taken
as the same toilet. The Toilet Map is maintained by councils and is usually better on
opening hours, so its hours replace OSM's, and its facility type is recorded so the
rider can switch types on and off on the Karoo. Facilities marked closed or key-only
are removed. Toilet Map facilities with no OSM match are added as new toilets.

Hours are converted to OSM `opening_hours` syntax so the Karoo app evaluates them
the same way as OSM data:
    "OPEN: 24 hours"                      -> "24/7"
    "OPEN: Daylight hours"                -> "sunrise-sunset"
    "OPEN: Mon-Wed,Fri 9am-5:30pm, ..."   -> "Mo-We,Fr 09:00-17:30, ..."
    "OPEN: Variable hours" / "Venue hours" -> unknown (None)
    "Currently closed"                    -> facility dropped
"""
import csv
import math
import re

MATCH_RADIUS_M = 50
# Short codes for the Toilet Map's FacilityType, stored in every tile row so the Karoo
# app can let the rider switch types on and off. Must match FacilityType.kt in the app.
TYPE_CODES = {
    "park or reserve": "park",
    "beach": "beach",
    "sporting facility": "sport",
    "swimming pool": "pool",
    "community building": "community",
    "car park": "carpark",
    "rest area": "rest",
    "service station": "servo",
    "train station": "train",
    "bus station": "bus",
    "jetty": "jetty",
    "camping ground": "camp",
    "caravan park": "caravan",
    "cemetery": "cemetery",
    "shopping centre": "shopping",
    "food outlet": "food",
    "airport": "airport",
    "other": "other",
}
# The two datasets often place the same toilet 50-150 m apart. Within this wider radius
# an OSM toilet with no hours borrows the Toilet Map's, and the Toilet Map facility is
# not added again as a duplicate.
NEARBY_RADIUS_M = 150
GRID_DEG = 0.001  # ~100 m cells for matching

DAYS = {"mon": "Mo", "tue": "Tu", "wed": "We", "thu": "Th", "fri": "Fr", "sat": "Sa", "sun": "Su"}
MONTHS = {m: m.capitalize() for m in
          ("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")}

_TIME = r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)"
_SPAN = re.compile(rf"^{_TIME}\s*-\s*{_TIME}$", re.IGNORECASE)
_DAY = r"(?:mon|tue|wed|thu|fri|sat|sun)"
_DAY_SEL = re.compile(rf"^{_DAY}(?:\s*-\s*{_DAY})?(?:\s*,\s*{_DAY}(?:\s*-\s*{_DAY})?)*$", re.IGNORECASE)
_MON = r"(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"
_MON_SEL = re.compile(rf"^{_MON}(?:\s*-\s*{_MON})?(?:\s*,\s*{_MON}(?:\s*-\s*{_MON})?)*$", re.IGNORECASE)
# A rule starts where a comma is followed by a day or month name (commas inside
# "Mon-Wed,Fri" are followed by a day too, so split only when a time came before).
_RULE_SPLIT = re.compile(rf",\s*(?=(?:{_DAY}|{_MON})\b)", re.IGNORECASE)


def _clock(h: str, m: str | None, ampm: str, is_end: bool) -> str | None:
    hour, minute = int(h), int(m or 0)
    if not (1 <= hour <= 12 and 0 <= minute <= 59):
        return None
    ampm = ampm.lower()
    if ampm == "am":
        hour = 0 if hour == 12 else hour
    else:
        hour = 12 if hour == 12 else hour + 12
    if is_end and hour == 0 and minute == 0:
        return "24:00"  # "12am" as a closing time means midnight
    return f"{hour:02d}:{minute:02d}"


def _convert_span(text: str) -> str | None:
    m = _SPAN.match(text.strip())
    if not m:
        return None
    start = _clock(m.group(1), m.group(2), m.group(3), is_end=False)
    end = _clock(m.group(4), m.group(5), m.group(6), is_end=True)
    if not start or not end:
        return None
    return f"{start}-{end}"


def _convert_selector(text: str, names: dict) -> str:
    out = text
    for k, v in names.items():
        out = re.sub(rf"\b{k}\b", v, out, flags=re.IGNORECASE)
    return re.sub(r"\s+", "", out)


def _convert_rule(rule: str) -> str | None:
    words = rule.strip().split()
    if not words:
        return None
    span = _convert_span(words[-1]) if len(words) >= 1 else None
    if span is None:
        return None
    sel = words[:-1]
    parts = []
    if sel and _MON_SEL.match(sel[0]):
        parts.append(_convert_selector(sel.pop(0), MONTHS))
    if sel and _DAY_SEL.match(" ".join(sel)):
        parts.append(_convert_selector(" ".join(sel), DAYS))
        sel = []
    if sel:
        return None
    parts.append(span)
    return " ".join(parts)


def convert_hours(value: str) -> tuple[str, str | None]:
    """Returns (status, osm_hours). status is 'open', 'closed' or 'unknown'."""
    v = (value or "").strip()
    low = v.lower()
    if low.startswith("currently closed"):
        return "closed", None
    if not low.startswith("open:"):
        return "unknown", None
    body = v[5:].strip()
    lb = body.lower()
    if lb == "24 hours":
        return "open", "24/7"
    if lb == "daylight hours":
        return "open", "sunrise-sunset"
    if lb in ("variable hours", "venue hours", ""):
        return "unknown", None

    # Split into rules: on ", " before a day/month name, but only after a time.
    rules, start = [], 0
    for m in _RULE_SPLIT.finditer(body):
        left = body[start:m.start()]
        if re.search(r"(am|pm)\s*$", left, re.IGNORECASE):
            rules.append(left)
            start = m.end()
    rules.append(body[start:])

    converted = [_convert_rule(r) for r in rules]
    if any(c is None for c in converted):
        return "unknown", None
    # Rules in the Toilet Map cover separate days/months, so join them as additive
    # OSM rules (", ") rather than overriding ones ("; ").
    return "open", ", ".join(converted)


def _truthy(s: str) -> bool:
    return (s or "").strip().lower() == "true"


def _dist_m(lat1, lon1, lat2, lon2) -> float:
    kx = 111_320.0 * math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot((lon2 - lon1) * kx, (lat2 - lat1) * 110_540.0)


def load(path: str) -> list[dict]:
    facilities = []
    with open(path, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            try:
                lat, lon = float(row["Latitude"]), float(row["Longitude"])
            except (KeyError, ValueError):
                continue
            if not (-45 < lat < -9 and 112 < lon < 155):
                continue
            status, hours = convert_hours(row.get("OpeningHours", ""))
            ftype = (row.get("FacilityType") or "").strip().lower()
            facilities.append({
                "id": row.get("FacilityID", ""),
                "lat": lat,
                "lon": lon,
                "name": (row.get("Name") or "").strip() or None,
                "status": status,
                "hours": hours,
                "type": TYPE_CODES.get(ftype, "other"),
                # Never useful to a passing cyclist, whatever the rider's type settings.
                "excluded": (
                    status == "closed"
                    or _truthy(row.get("KeyRequired"))
                    or _truthy(row.get("MLAK24"))
                ),
                "water": _truthy(row.get("DrinkingWater")),
                "fee": _truthy(row.get("PaymentRequired")),
            })
    return facilities


def _grid(points) -> dict:
    grid: dict = {}
    for i, (lat, lon) in enumerate(points):
        grid.setdefault((math.floor(lat / GRID_DEG), math.floor(lon / GRID_DEG)), []).append(i)
    return grid


def _nearest(grid: dict, points: list, lat: float, lon: float, radius_m: float, ok=lambda i: True):
    reach = math.ceil(radius_m / 100) + 1
    cy, cx = math.floor(lat / GRID_DEG), math.floor(lon / GRID_DEG)
    best, best_d = None, radius_m
    for dy in range(-reach, reach + 1):
        for dx in range(-reach, reach + 1):
            for i in grid.get((cy + dy, cx + dx), ()):
                if not ok(i):
                    continue
                d = _dist_m(lat, lon, points[i][0], points[i][1])
                if d <= best_d:
                    best, best_d = i, d
    return best


def merge(places: dict, path: str, stats: dict) -> None:
    """Applies Toilet Map data to `places` (the dict built from OSM) in place."""
    facilities = load(path)
    fpoints = [(f["lat"], f["lon"]) for f in facilities]
    fgrid = _grid(fpoints)

    osm_toilets = [(row[0], row[1]) for row in places.values() if "t" in row[2]]
    ogrid = _grid(osm_toilets)

    matched: set = set()
    for key in list(places):
        row = places[key]
        if "t" not in row[2]:
            continue
        lat, lon = row[0], row[1]
        best = _nearest(fgrid, fpoints, lat, lon, MATCH_RADIUS_M)
        if best is None:
            # No close match: if OSM has no hours, borrow them from a nearby facility.
            if not row[4]:
                near = _nearest(
                    fgrid, fpoints, lat, lon, NEARBY_RADIUS_M,
                    ok=lambda i: not facilities[i]["excluded"] and facilities[i]["hours"] is not None,
                )
                if near is not None:
                    row[4] = facilities[near]["hours"]
                    stats["tm_hours_applied"] += 1
            continue
        f = facilities[best]
        matched.add(best)
        stats["tm_matched"] += 1
        if f["excluded"]:
            del places[key]
            stats["tm_removed"] += 1
            continue
        row[6] = f["type"]
        if f["hours"]:
            row[4] = f["hours"]
            stats["tm_hours_applied"] += 1
        if f["water"] and "w" not in row[2]:
            row[2] += "w"
        if f["fee"]:
            row[5] = 1
        if not row[3]:
            row[3] = f["name"]

    for i, f in enumerate(facilities):
        if i in matched or f["excluded"]:
            continue
        # Probably the same toilet as an OSM one placed a little differently.
        if _nearest(ogrid, osm_toilets, f["lat"], f["lon"], NEARBY_RADIUS_M) is not None:
            continue
        places[f"tm{f['id']}"] = [
            round(f["lat"], 6),
            round(f["lon"], 6),
            "tw" if f["water"] else "t",
            f["name"],
            f["hours"],
            1 if f["fee"] else 0,
            f["type"],
        ]
        stats["tm_added"] += 1
