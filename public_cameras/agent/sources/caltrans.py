"""Caltrans (California DOT) publicly accessible traffic cameras.

No API key required.  Caltrans publishes live camera snapshots and their
metadata as open data.  There are 12 districts; each may yield 30-80
cameras – total ~400-600 cameras state-wide.

Snapshot URLs follow the pattern:
  https://cwwp2.dot.ca.gov/data/d{district}/cc/img/{id}.jpg
Metadata JSON per district:
  https://cwwp2.dot.ca.gov/data/d{district}/cc/ccTVData.json
"""
import asyncio
import logging
import re

import httpx

logger = logging.getLogger(__name__)

_DISTRICTS = list(range(1, 13))          # 1-12
_META_URL  = "https://cwwp2.dot.ca.gov/data/d{d}/cc/ccTVData.json"
_IMG_URL   = "https://cwwp2.dot.ca.gov/data/d{d}/cc/img/{cam_id}.jpg"
_PAGE_URL  = "https://quickmap.dot.ca.gov/"

_CA_CITIES = {
    "d1": "Eureka", "d2": "Redding", "d3": "Marysville",
    "d4": "Oakland", "d5": "San Luis Obispo", "d6": "Fresno",
    "d7": "Los Angeles", "d8": "San Bernardino", "d9": "Bishop",
    "d10": "Stockton", "d11": "San Diego", "d12": "Irvine",
}


async def _fetch_district(client: httpx.AsyncClient, district: int) -> list[dict]:
    url = _META_URL.format(d=district)
    cameras: list[dict] = []
    try:
        resp = await client.get(url, timeout=20)
        if resp.status_code != 200:
            return cameras
        data = resp.json()
    except Exception as exc:
        logger.debug("Caltrans d%d fetch failed: %s", district, exc)
        return cameras

    # The JSON may be wrapped in a "data" or "CameraLocation" key
    entries = (
        data.get("data")
        or data.get("CameraLocation")
        or (data if isinstance(data, list) else [])
    )
    if isinstance(entries, dict):
        entries = list(entries.values())

    for entry in entries:
        if not isinstance(entry, dict):
            continue
        cam_id = str(
            entry.get("id") or entry.get("ID") or entry.get("cctv_id") or ""
        ).strip()
        if not cam_id:
            continue

        name = (
            entry.get("location") or entry.get("name") or entry.get("abbrev") or f"Caltrans D{district} Cam {cam_id}"
        )
        city = entry.get("city") or _CA_CITIES.get(f"d{district}", "California")
        lat  = _safe_float(entry.get("latitude")  or entry.get("lat"))
        lon  = _safe_float(entry.get("longitude") or entry.get("lon"))

        img_url = _IMG_URL.format(d=district, cam_id=cam_id)

        cameras.append(
            {
                "title":       f"Caltrans D{district}: {name}",
                "url":         _PAGE_URL,
                "image_url":   img_url,
                "feed_type":   "image",
                "location":    f"{name}, {city}, CA",
                "country":     "USA",
                "state":       "CA",
                "city":        city,
                "latitude":    lat,
                "longitude":   lon,
                "site_name":   f"Caltrans District {district}",
                "description": f"California DOT traffic camera – {name}, District {district}.",
                "tags":        f"traffic,caltrans,california,dot,public,ca,district{district}",
                "source":      "cwwp2.dot.ca.gov",
                "keywords":    f"caltrans california traffic camera {name} {city} district {district}",
            }
        )
    return cameras


def _safe_float(val) -> float | None:
    try:
        return float(val)
    except (TypeError, ValueError):
        return None


async def fetch_cameras(client: httpx.AsyncClient) -> list[dict]:
    tasks = [_fetch_district(client, d) for d in _DISTRICTS]
    results = await asyncio.gather(*tasks, return_exceptions=True)
    cameras: list[dict] = []
    for r in results:
        if isinstance(r, list):
            cameras.extend(r)
    logger.info("Caltrans: collected %d cameras", len(cameras))
    return cameras
