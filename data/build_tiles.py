#!/usr/bin/env python3
"""
Builds the Loo & Water data tiles from OpenStreetMap extracts.

Input (in WORK_DIR, produced by the workflow with `osmium tags-filter`):
  filtered-*.osm.pbf    toilets and water points (plus nodes their outlines need)
  containers-*.osm.pbf  buildings and shopping-centre areas, used to drop indoor places
  toiletmap.csv         optional: National Public Toilet Map export (data.gov.au), merged
                        in for opening hours, closures and missing toilets (see toiletmap.py)

Output: <out>/tiles/<latIdx>_<lonIdx>.json  (0.1 degree grid, ~11 km tall)
        <out>/index.json, <out>/index.html, <out>/.nojekyll

Each tile:  {"v": 1, "p": [[lat, lon, "tw", name, opening_hours, fee, type], ...]}
  - the 3rd field holds category codes: "t" toilet, "w" drinking water
    (a toilet block tagged drinking_water=yes is both: "tw")
  - name / opening_hours are null when untagged; fee is 1 or 0
  - type is the Toilet Map facility type code (see toiletmap.TYPE_CODES), "" if unlisted

The tile key MUST match the extension: floor(lat * 10) and floor(lon * 10).

Usage: python build_tiles.py OUT_DIR WORK_DIR
"""
import datetime
import json
import math
import re
import sys
from pathlib import Path

import osmium

import toiletmap

GRID_PER_DEGREE = 10  # 0.1 degree tiles
MAX_TILE_BYTES = 90_000  # Karoo's HTTP bridge caps responses at 100 KB

BLOCKED_ACCESS = re.compile(r"^(private|no|customers|permit|delivery|employees|staff)$")
DRINKING_FOUNTAINS = {"drinking", "bubbler", "bottle_refill"}

# ---------------------------------------------------------------- indoor rules
# Places you'd have to walk into a building for (no way to watch the bike) are dropped.
#
# 1. Tagged as indoor on the place itself.
INDOOR_TAG_VALUES = {"yes", "room", "area", "corridor", "level"}
INDOOR_LOCATIONS = {"indoor", "inside", "underground"}
# 2. Inside a shopping-centre area (shop=mall), whatever its size.
# 3. Inside a building outline of at least this size. Smaller buildings are usually
#    the toilet block or kiosk itself, which you reach from outside.
MIN_INDOOR_BUILDING_M2 = 200
#    Building types that are open or are the facility itself, never "indoor":
OPEN_BUILDING_TYPES = {
    "toilets", "roof", "shelter", "pavilion", "kiosk", "hut", "shed", "service",
    "carport", "bandstand", "ruins", "no",
}
# Toilet Map types the rider can switch on in the app even though they're indoors
# (off by default). These skip the building test so the switch actually works.
SWITCHABLE_INDOOR_TYPES = {"shopping", "food", "airport"}
# Point lookup grid for the containment test (~200 m cells).
CELL_DEG = 0.002


def categories(tags: dict) -> str:
    """Category codes for an OSM object, or "" if it's not a public toilet / water point."""
    if tags.get("disused") == "yes" or tags.get("abandoned") == "yes":
        return ""
    if BLOCKED_ACCESS.match(tags.get("access", "")):
        return ""

    amenity = tags.get("amenity")
    drinking = tags.get("drinking_water")
    codes = ""

    if amenity == "toilets":
        codes += "t"

    is_water = (
        (amenity == "drinking_water" and drinking != "no")
        or (amenity in ("water_point", "fountain", "toilets") and drinking == "yes")
        or (tags.get("man_made") == "water_tap" and drinking == "yes")
        or tags.get("fountain") in DRINKING_FOUNTAINS
    )
    if is_water:
        codes += "w"
    return codes


def indoor_by_tags(tags: dict) -> bool:
    if tags.get("indoor") in INDOOR_TAG_VALUES:
        return True
    if tags.get("location") in INDOOR_LOCATIONS:
        return True
    level = tags.get("level", "").strip()
    return bool(level) and level != "0"


def tile_key(lat: float, lon: float) -> str:
    return f"{math.floor(lat * GRID_PER_DEGREE)}_{math.floor(lon * GRID_PER_DEGREE)}"


def collect(path: str, places: dict, stats: dict) -> None:
    """Adds matching nodes and ways (as their centre) from one OSM file into `places`."""
    for obj in osmium.FileProcessor(path).with_locations():
        if obj.is_relation():
            continue
        tags = {t.k: t.v for t in obj.tags}
        if not tags:
            continue
        codes = categories(tags)
        if not codes:
            continue
        if indoor_by_tags(tags):
            stats["indoor_tagged"] += 1
            continue

        if obj.is_node():
            if not obj.location.valid():
                continue
            lat, lon = obj.location.lat, obj.location.lon
            key = f"n{obj.id}"
        else:  # way: average of its node positions
            pts = [(n.lat, n.lon) for n in obj.nodes if n.location.valid()]
            if len(pts) > 1 and pts[0] == pts[-1]:
                pts = pts[:-1]  # closed outline repeats its first point
            if not pts:
                continue
            lat = sum(p[0] for p in pts) / len(pts)
            lon = sum(p[1] for p in pts) / len(pts)
            key = f"w{obj.id}"

        fee = tags.get("fee")
        places[key] = [
            round(lat, 6),
            round(lon, 6),
            codes,
            tags.get("name"),
            tags.get("opening_hours"),
            1 if fee and fee != "no" else 0,
            "",
        ]


# ---------------------------------------------------------------- containment

def ring_coords(ring) -> list:
    return [(n.lon, n.lat) for n in ring if n.location.valid()]


def point_in_ring(x: float, y: float, ring: list) -> bool:
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def ring_area_m2(ring: list) -> float:
    if len(ring) < 3:
        return 0.0
    lat0 = math.radians(sum(p[1] for p in ring) / len(ring))
    kx = 111_320.0 * math.cos(lat0)
    ky = 110_540.0
    s = 0.0
    for i in range(len(ring)):
        x1, y1 = ring[i - 1]
        x2, y2 = ring[i]
        s += (x1 * kx) * (y2 * ky) - (x2 * kx) * (y1 * ky)
    return abs(s) / 2


def is_indoor_container(tags: dict, area_m2: float) -> bool:
    if tags.get("shop") == "mall":
        return True
    building = tags.get("building")
    if not building or building in OPEN_BUILDING_TYPES:
        return False
    if tags.get("amenity") == "toilets":
        return False  # the toilet block itself
    return area_m2 >= MIN_INDOOR_BUILDING_M2


def drop_indoor(path: str, places: dict, stats: dict) -> None:
    """Removes places that sit inside a shopping centre or a sizeable building."""
    grid: dict = {}
    for key, row in places.items():
        if row[6] in SWITCHABLE_INDOOR_TYPES:
            continue
        cell = (math.floor(row[1] / CELL_DEG), math.floor(row[0] / CELL_DEG))
        grid.setdefault(cell, []).append(key)
    if not grid:
        return

    area_filter = osmium.filter.EntityFilter(osmium.osm.AREA)
    for area in osmium.FileProcessor(path).with_areas().with_filter(area_filter):
        tags = area.tags
        if "building" not in tags and tags.get("shop") != "mall":
            continue
        for outer in area.outer_rings():
            ring = ring_coords(outer)
            if len(ring) < 3:
                continue
            xs = [p[0] for p in ring]
            ys = [p[1] for p in ring]
            x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
            if x1 - x0 > 0.05 or y1 - y0 > 0.05:
                continue  # nothing indoor is 5 km across; skip odd giant outlines
            candidates = [
                k
                for cx in range(math.floor(x0 / CELL_DEG), math.floor(x1 / CELL_DEG) + 1)
                for cy in range(math.floor(y0 / CELL_DEG), math.floor(y1 / CELL_DEG) + 1)
                for k in grid.get((cx, cy), ())
                if k in places
            ]
            if not candidates:
                continue

            own_way = f"w{area.orig_id()}" if area.from_way() else None
            tag_dict = {t.k: t.v for t in tags}
            if not is_indoor_container(tag_dict, ring_area_m2(ring)):
                continue
            holes = [ring_coords(inner) for inner in area.inner_rings(outer)]
            for k in candidates:
                if k == own_way:
                    continue
                lat, lon = places[k][0], places[k][1]
                if not (x0 <= lon <= x1 and y0 <= lat <= y1):
                    continue
                if point_in_ring(lon, lat, ring) and not any(point_in_ring(lon, lat, h) for h in holes):
                    del places[k]
                    stats["inside_building"] += 1


# ---------------------------------------------------------------- output

def write_tiles(out: Path, places: dict, stats: dict) -> dict:
    tiles: dict[str, list] = {}
    for row in places.values():
        tiles.setdefault(tile_key(row[0], row[1]), []).append(row)

    tile_dir = out / "tiles"
    tile_dir.mkdir(parents=True, exist_ok=True)
    trimmed = 0
    for key, rows in tiles.items():
        rows.sort(key=lambda r: (r[0], r[1]))
        text = json.dumps({"v": 1, "p": rows}, separators=(",", ":"), ensure_ascii=False)
        if len(text.encode()) > MAX_TILE_BYTES:
            # Very dense tile: drop names to fit under Karoo's limit.
            text = json.dumps(
                {"v": 1, "p": [[r[0], r[1], r[2], None, r[4], r[5], r[6]] for r in rows]},
                separators=(",", ":"),
                ensure_ascii=False,
            )
            trimmed += 1
        (tile_dir / f"{key}.json").write_text(text, encoding="utf-8")

    toilets = sum(1 for r in places.values() if "t" in r[2])
    water = sum(1 for r in places.values() if "w" in r[2])
    largest = max((len((tile_dir / f"{k}.json").read_bytes()) for k in tiles), default=0)
    now = datetime.datetime.now(datetime.timezone.utc)
    type_counts: dict = {}
    for r in places.values():
        if "t" in r[2]:
            type_counts[r[6] or "unlisted"] = type_counts.get(r[6] or "unlisted", 0) + 1
    return {
        "v": 1,
        "built": now.isoformat(timespec="seconds"),
        "builtEpoch": int(now.timestamp()),
        "toiletMapSource": stats.get("tm_source", "not used"),
        "types": dict(sorted(type_counts.items())),
        "gridPerDegree": GRID_PER_DEGREE,
        "tiles": len(tiles),
        "toilets": toilets,
        "water": water,
        "excludedIndoorTagged": stats["indoor_tagged"],
        "excludedInsideBuildings": stats["inside_building"],
        "toiletMapMatched": stats["tm_matched"],
        "toiletMapHoursApplied": stats["tm_hours_applied"],
        "toiletMapRemoved": stats["tm_removed"],
        "toiletMapAdded": stats["tm_added"],
        "largestTileBytes": largest,
        "tilesWithNamesDropped": trimmed,
    }


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    work = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)

    stats = {
        "indoor_tagged": 0, "inside_building": 0,
        "tm_matched": 0, "tm_removed": 0, "tm_hours_applied": 0, "tm_added": 0,
    }
    places: dict = {}
    for path in sorted(work.glob("filtered-*.osm.pbf")) + sorted(work.glob("filtered-*.osm")):
        before = len(places)
        collect(str(path), places, stats)
        print(f"{path.name}: {len(places) - before} places", flush=True)

    tm_csv = work / "toiletmap.csv"
    tm_note = work / "toiletmap.source.txt"
    if tm_note.exists():
        stats["tm_source"] = tm_note.read_text(encoding="utf-8").strip()
    if tm_csv.exists():
        toiletmap.merge(places, str(tm_csv), stats)
        print(
            f"Toilet Map: matched {stats['tm_matched']}, hours applied {stats['tm_hours_applied']}, "
            f"removed {stats['tm_removed']}, added {stats['tm_added']}",
            flush=True,
        )
    else:
        print("No toiletmap.csv; using OpenStreetMap only", flush=True)

    for path in sorted(work.glob("containers-*.osm.pbf")) + sorted(work.glob("containers-*.osm")):
        before = len(places)
        drop_indoor(str(path), places, stats)
        print(f"{path.name}: dropped {before - len(places)} indoor places", flush=True)

    index = write_tiles(out, places, stats)
    (out / "index.json").write_text(json.dumps(index, indent=2), encoding="utf-8")
    (out / ".nojekyll").write_text("", encoding="utf-8")
    (out / "index.html").write_text(
        "<!doctype html><meta charset=utf-8><title>Loo &amp; Water data</title>"
        "<body style='font-family:sans-serif;max-width:40em;margin:2em auto'>"
        "<h1>Loo &amp; Water data</h1>"
        f"<p>Built {index['built']}: {index['toilets']} toilets, {index['water']} water points "
        f"in {index['tiles']} tiles.</p>"
        f"<p>Left out as indoors: {index['excludedIndoorTagged']} tagged indoor, "
        f"{index['excludedInsideBuildings']} inside shopping centres or buildings.</p>"
        f"<p>National Public Toilet Map: {index['toiletMapHoursApplied']} opening hours applied, "
        f"{index['toiletMapRemoved']} closed / key-only / indoor toilets removed, "
        f"{index['toiletMapAdded']} toilets added. Source: {index['toiletMapSource']}.</p>"
        "<p>Data &copy; OpenStreetMap contributors, ODbL. "
        "Includes National Public Toilet Map data (Australian Government, CC BY 3.0 AU).</p>",
        encoding="utf-8",
    )
    print(json.dumps(index, indent=2))
    if index["largestTileBytes"] > MAX_TILE_BYTES:
        sys.exit("A tile is still over the size limit; use a finer grid.")


if __name__ == "__main__":
    main()
