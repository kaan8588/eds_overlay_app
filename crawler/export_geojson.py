"""
GeoJSON / Android-Ready JSON Exporter

Reads raw scraped data and normalizes it into:
  1. Standard GeoJSON (for visualization in QGIS, Mapbox, etc.)
  2. Flat eds_data.json (for direct import into the Android app's assets)

Usage:
  python export_geojson.py --input raw_data.json --output-dir ./output
"""

import argparse
import json
from pathlib import Path


# ─── Field Mapping ───────────────────────────────────────────────────

# Common field name variations across different data sources.
# Add mappings here as you discover the actual field names from the EGM API.
LAT_KEYS = ("lat", "latitude", "Lat", "Latitude", "enlem", "Enlem", "y", "Y")
LNG_KEYS = ("lng", "longitude", "Lng", "Longitude", "lon", "Lon", "boylam", "Boylam", "x", "X")
DIR_KEYS = ("direction", "bearing", "heading", "yon", "angle", "Aci", "aci")
SPEED_KEYS = ("speedLimit", "speed_limit", "limit", "hiz", "Hiz", "hizLimiti", "HizLimiti")
TYPE_KEYS = ("type", "tip", "Tip", "cameraType", "tur", "Tur")
DESC_KEYS = ("Aciklama", "aciklama", "description", "Description")
UPDATED_KEYS = ("lastUpdated", "updatedAt", "guncellemeTarihi", "GuncellemeTarihi")


def find_value(obj: dict, keys: tuple, default=None):
    """Try multiple key names to find a value in a dict."""
    for key in keys:
        if key in obj:
            return obj[key]
    return default


def to_float(value, default=None):
    """Safely parse float values, including decimal commas."""
    if value is None:
        return default
    if isinstance(value, str):
        value = value.strip().replace(",", ".")
    try:
        return float(value)
    except (ValueError, TypeError):
        return default


# ─── Normalization ───────────────────────────────────────────────────

def normalize_entry(raw: dict) -> dict | None:
    """
    Converts a raw scraped entry into the app-standard format.
    Returns None if lat/lng are missing.
    """
    lat = find_value(raw, LAT_KEYS)
    lng = find_value(raw, LNG_KEYS)

    if lat is None or lng is None:
        return None

    lat = to_float(lat)
    lng = to_float(lng)
    if lat is None or lng is None:
        return None

    direction = find_value(raw, DIR_KEYS, -1)
    speed_limit = find_value(raw, SPEED_KEYS, 0)
    cam_type = find_value(raw, TYPE_KEYS, "EDS")
    updated_at = find_value(raw, UPDATED_KEYS, "")
    description = find_value(raw, DESC_KEYS, "")

    direction = to_float(direction, -1.0)

    try:
        speed_limit = int(speed_limit)
    except (ValueError, TypeError):
        speed_limit = 0

    if not cam_type:
        cam_type = "EDS"

    if isinstance(updated_at, str):
        updated_at = updated_at.strip()
    else:
        updated_at = str(updated_at) if updated_at else ""

    if not updated_at:
        updated_at = "2026-03-28"

    return {
        "lat": lat,
        "lng": lng,
        "direction": direction,
        "speedLimit": speed_limit,
        "type": str(cam_type) if cam_type else "EDS",
        "lastUpdated": updated_at,
        "description": str(description) if description else "",
    }


def normalize_all(raw_data: list[dict]) -> list[dict]:
    """Normalize all raw entries, filtering out invalid ones."""
    results = []
    skipped = 0
    for entry in raw_data:
        normalized = normalize_entry(entry)
        if normalized:
            results.append(normalized)
        else:
            skipped += 1

    if skipped > 0:
        print(f"⚠  Skipped {skipped} entries with missing/invalid coordinates")

    return results


# ─── Export ──────────────────────────────────────────────────────────

def to_geojson(points: list[dict]) -> dict:
    """Convert normalized points to standard GeoJSON FeatureCollection."""
    features = []
    for p in points:
        features.append({
            "type": "Feature",
            "geometry": {
                "type": "Point",
                "coordinates": [p["lng"], p["lat"]]  # GeoJSON = [lng, lat]
            },
            "properties": {
                "direction": p["direction"],
                "speedLimit": p["speedLimit"],
                "type": p["type"],
            }
        })
    return {
        "type": "FeatureCollection",
        "features": features,
    }


def main():
    parser = argparse.ArgumentParser(description="EDS Data Normalizer & Exporter")
    parser.add_argument("--input", required=True, help="Path to raw_data.json")
    parser.add_argument("--output-dir", default="./output", help="Output directory")
    args = parser.parse_args()

    input_path = Path(args.input)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    raw_data = json.loads(input_path.read_text(encoding="utf-8"))
    if not isinstance(raw_data, list):
        print(f"Error: Expected a JSON array, got {type(raw_data).__name__}")
        return

    print(f"Loaded {len(raw_data)} raw entries")

    normalized = normalize_all(raw_data)
    print(f"Normalized {len(normalized)} valid entries")

    if not normalized:
        print("No valid entries to export.")
        return

    # Export flat JSON (for Android assets/eds_data.json)
    flat_path = output_dir / "eds_data.json"
    flat_path.write_text(
        json.dumps(normalized, indent=2, ensure_ascii=False),
        encoding="utf-8"
    )
    print(f"✓ Flat JSON → {flat_path}")

    # Export GeoJSON (for GIS tools)
    geojson_path = output_dir / "eds_points.geojson"
    geojson_path.write_text(
        json.dumps(to_geojson(normalized), indent=2, ensure_ascii=False),
        encoding="utf-8"
    )
    print(f"✓ GeoJSON   → {geojson_path}")


if __name__ == "__main__":
    main()
