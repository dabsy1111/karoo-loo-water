#!/usr/bin/env python3
"""
Downloads the newest National Public Toilet Map CSV from data.gov.au to OUT_PATH.

The export's file name changes every month (toiletmapexport_YYMMDD_*.csv), so this asks
the data.gov.au catalogue API for the dataset's resources and picks the newest CSV.
If anything goes wrong, it falls back to the copy committed in data/toiletmap.csv.

Usage: python fetch_toiletmap.py OUT_PATH
"""
import csv
import io
import json
import shutil
import sys
import urllib.request
from pathlib import Path

DATASET_ID = "553b3049-2b8b-46a2-95e6-640d7986a8c1"
API = f"https://data.gov.au/data/api/3/action/package_show?id={DATASET_ID}"
FALLBACK = Path(__file__).parent / "toiletmap.csv"
UA = {"User-Agent": "KarooLooWater-data-build (GitHub Actions)"}


def get(url: str) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
        return r.read()


def looks_valid(data: bytes) -> bool:
    text = data.decode("utf-8-sig", errors="replace")
    reader = csv.DictReader(io.StringIO(text))
    fields = reader.fieldnames or []
    if not {"Latitude", "Longitude", "OpeningHours", "FacilityType"} <= set(fields):
        return False
    return sum(1 for _ in reader) > 10_000


def main() -> None:
    out = Path(sys.argv[1])
    out.parent.mkdir(parents=True, exist_ok=True)
    try:
        meta = json.loads(get(API))
        resources = [
            r for r in meta["result"]["resources"]
            if (r.get("format") or "").lower() == "csv" or r.get("url", "").lower().endswith(".csv")
        ]
        resources.sort(key=lambda r: r.get("last_modified") or r.get("created") or "", reverse=True)
        for r in resources:
            data = get(r["url"])
            if looks_valid(data):
                out.write_bytes(data)
                print(f"Downloaded {r['url']} ({len(data) // 1024} KB)")
                return
        raise RuntimeError("no valid CSV among the dataset's resources")
    except Exception as e:  # noqa: BLE001 - any failure falls back to the committed copy
        print(f"::warning::Couldn't fetch the latest Toilet Map export ({e}); using the committed copy")
        if FALLBACK.exists():
            shutil.copy(FALLBACK, out)
        else:
            print("::warning::No committed data/toiletmap.csv either; building from OpenStreetMap only")


if __name__ == "__main__":
    main()
