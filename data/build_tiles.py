#!/usr/bin/env python3
"""
Builds the Loo & Water data tiles from OpenStreetMap extracts.

Input:  one or more .osm.pbf (or .osm) files, already narrowed with `osmium tags-filter`
Output: <out>/tiles/<latIdx>_<lonIdx>.json  (0.1 degree grid, ~11 km tall)
        <out>/index.json, <out>/index.html, <out>/.nojekyll

Each tile:  {"v": 1, "p": [[lat, lon, "tw", name, opening_hours, fee], ...]}
  - the 3rd field holds category codes: "t" toilet, "w" drinking water
    (a toilet block tagged drinking_water=yes is both: "tw")
  - name / opening_hours are null when untagged; fee is 1 or 0

The tile key MUST match the extension: floor(lat * 10) and floor(lon * 10).

Usage: python build_tiles.py OUT_DIR file1.osm.pbf [file2.osm.pbf ...]
"""
import datetime
import json
import math
import re
import sys
from pathlib import Path

import osmium

GRID_PER_DEGREE = 10  # 0.1 degree tiles
MAX_TILE_BYTES = 90_000  # Karoo's HTTP bridge caps responses at 100 KB

BLOCKED_ACCESS = re.compile(r"^(private|no|customers|permit|delivery|employees|staff)$")
DRINKING_FOUNTAINS = {"drinking", "bubbler", "bottle_refill"}


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


def tile_key(lat: float, lon: float) -> str:
    return f"{math.floor(lat * GRID_PER_DEGREE)}_{math.floor(lon * GRID_PER_DEGREE)}"


def collect(path: str, places: dict) -> None:
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
        ]


def write_tiles(out: Path, places: dict) -> dict:
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
                {"v": 1, "p": [[r[0], r[1], r[2], None, r[4], r[5]] for r in rows]},
                separators=(",", ":"),
                ensure_ascii=False,
            )
            trimmed += 1
        (tile_dir / f"{key}.json").write_text(text, encoding="utf-8")

    toilets = sum(1 for r in places.values() if "t" in r[2])
    water = sum(1 for r in places.values() if "w" in r[2])
    largest = max((len((tile_dir / f"{k}.json").read_bytes()) for k in tiles), default=0)
    return {
        "v": 1,
        "built": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "gridPerDegree": GRID_PER_DEGREE,
        "tiles": len(tiles),
        "toilets": toilets,
        "water": water,
        "largestTileBytes": largest,
        "tilesWithNamesDropped": trimmed,
    }


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)

    places: dict = {}
    for path in sys.argv[2:]:
        before = len(places)
        collect(path, places)
        print(f"{path}: {len(places) - before} places")

    index = write_tiles(out, places)
    (out / "index.json").write_text(json.dumps(index, indent=2), encoding="utf-8")
    (out / ".nojekyll").write_text("", encoding="utf-8")
    (out / "index.html").write_text(
        "<!doctype html><meta charset=utf-8><title>Loo &amp; Water data</title>"
        "<body style='font-family:sans-serif;max-width:40em;margin:2em auto'>"
        "<h1>Loo &amp; Water data</h1>"
        f"<p>Built {index['built']}: {index['toilets']} toilets, {index['water']} water points "
        f"in {index['tiles']} tiles.</p>"
        "<p>Data &copy; OpenStreetMap contributors, ODbL.</p>",
        encoding="utf-8",
    )
    print(json.dumps(index, indent=2))
    if index["largestTileBytes"] > MAX_TILE_BYTES:
        sys.exit("A tile is still over the size limit; use a finer grid.")


if __name__ == "__main__":
    main()
