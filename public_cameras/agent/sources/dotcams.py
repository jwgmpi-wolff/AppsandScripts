"""State DOT traffic cameras – no API key required.

Sources covered:
  • Wyoming DOT  (WYDOT 511)
  • Colorado DOT (COTRIP)
  • Oregon DOT   (TripCheck)
  • Nevada DOT   (NV511)
  • Minnesota DOT (511MN)
  • Utah DOT     (UDOT)
"""
import asyncio
import json
import logging
import re
import xml.etree.ElementTree as ET

import httpx
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Wyoming DOT  – public XML feed
# ---------------------------------------------------------------------------
_WYDOT_URL = "https://www.wyoroad.info/iticket/xml/wy511_travelerinfo.xml"


async def _fetch_wydot(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(_WYDOT_URL, timeout=20)
        root = ET.fromstring(resp.text)
        ns = {"ns": root.tag.split("}")[0].lstrip("{") if "}" in root.tag else ""}
        prefix = "{" + ns["ns"] + "}" if ns["ns"] else ""

        for cam in root.iter(f"{prefix}cctv"):
            cam_id = cam.findtext(f"{prefix}id") or cam.get("id") or ""
            name   = cam.findtext(f"{prefix}name") or f"WYDOT Cam {cam_id}"
            lat    = _safe_float(cam.findtext(f"{prefix}latitude"))
            lon    = _safe_float(cam.findtext(f"{prefix}longitude"))
            img    = cam.findtext(f"{prefix}imageUrl") or cam.findtext(f"{prefix}url") or ""
            if not img and cam_id:
                img = f"https://www.wyoroad.info/images/cameras/{cam_id}.jpg"

            cameras.append({
                "title":       f"WYDOT: {name}",
                "url":         "https://www.wyoroad.info",
                "image_url":   img,
                "feed_type":   "image",
                "location":    name,
                "country":     "USA",
                "state":       "WY",
                "city":        "",
                "latitude":    lat,
                "longitude":   lon,
                "site_name":   "Wyoming DOT",
                "description": f"Wyoming DOT traffic camera – {name}.",
                "tags":        "traffic,wydot,wyoming,dot,public",
                "source":      "wyoroad.info",
                "keywords":    f"wyoming traffic camera {name} wydot",
            })
    except Exception as exc:
        logger.debug("WYDOT fetch failed: %s", exc)
    logger.info("WYDOT: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Colorado DOT (COTRIP) – public JSON API
# ---------------------------------------------------------------------------
_COTRIP_URL = "https://cotrip.org/speed/getCameras.do"


async def _fetch_cotrip(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(_COTRIP_URL, timeout=20)
        data = resp.json()
        entries = data if isinstance(data, list) else (data.get("cameras") or data.get("data") or [])
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            cam_id = str(entry.get("id") or entry.get("cameraId") or "")
            name   = entry.get("name") or entry.get("location") or f"CDOT Cam {cam_id}"
            lat    = _safe_float(entry.get("latitude") or entry.get("lat"))
            lon    = _safe_float(entry.get("longitude") or entry.get("lon"))
            img    = entry.get("imageUrl") or entry.get("url") or entry.get("staticUrl") or ""
            if not img and cam_id:
                img = f"https://cotrip.org/images/cameras/{cam_id}.jpg"
            cameras.append({
                "title":       f"CDOT: {name}",
                "url":         "https://cotrip.org",
                "image_url":   img,
                "feed_type":   "image",
                "location":    name,
                "country":     "USA",
                "state":       "CO",
                "city":        "",
                "latitude":    lat,
                "longitude":   lon,
                "site_name":   "Colorado DOT",
                "description": f"Colorado DOT traffic camera – {name}.",
                "tags":        "traffic,cdot,colorado,dot,public",
                "source":      "cotrip.org",
                "keywords":    f"colorado traffic camera {name} cdot cotrip",
            })
    except Exception as exc:
        logger.debug("COTRIP fetch failed: %s", exc)
    logger.info("COTRIP: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Oregon DOT (TripCheck) – public JSON API
# ---------------------------------------------------------------------------
_TRIPCHECK_URL = "https://www.tripcheck.com/tripcheck/Cameras"


async def _fetch_tripcheck(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(_TRIPCHECK_URL, timeout=20,
                                headers={"Accept": "application/json"})
        data = resp.json()
        entries = data if isinstance(data, list) else (data.get("cameras") or data.get("data") or [])
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            cam_id = str(entry.get("id") or entry.get("CameraID") or "")
            name   = entry.get("name") or entry.get("Description") or f"ODOT Cam {cam_id}"
            lat    = _safe_float(entry.get("lat") or entry.get("latitude"))
            lon    = _safe_float(entry.get("lon") or entry.get("longitude"))
            img    = entry.get("imageUrl") or entry.get("url") or ""
            if not img and cam_id:
                img = f"https://www.tripcheck.com/Photos/{cam_id}_medium.jpg"
            cameras.append({
                "title":       f"ODOT: {name}",
                "url":         "https://www.tripcheck.com",
                "image_url":   img,
                "feed_type":   "image",
                "location":    name,
                "country":     "USA",
                "state":       "OR",
                "city":        "",
                "latitude":    lat,
                "longitude":   lon,
                "site_name":   "Oregon DOT",
                "description": f"Oregon DOT TripCheck camera – {name}.",
                "tags":        "traffic,odot,oregon,dot,public,tripcheck",
                "source":      "tripcheck.com",
                "keywords":    f"oregon traffic camera {name} odot tripcheck",
            })
    except Exception as exc:
        logger.debug("TripCheck fetch failed: %s", exc)
    logger.info("TripCheck: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Utah DOT (UDOT) – public JSON API
# ---------------------------------------------------------------------------
_UDOT_URL = "https://www.udottraffic.utah.gov/1.0/udotcamera"


async def _fetch_udot(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(_UDOT_URL, timeout=20,
                                headers={"Accept": "application/json"})
        data = resp.json()
        entries = data if isinstance(data, list) else (data.get("data") or [])
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            cam_id = str(entry.get("CameraID") or entry.get("id") or "")
            name   = entry.get("Description") or entry.get("name") or f"UDOT Cam {cam_id}"
            lat    = _safe_float(entry.get("Latitude") or entry.get("lat"))
            lon    = _safe_float(entry.get("Longitude") or entry.get("lon"))
            img    = entry.get("url") or entry.get("imageUrl") or ""
            if not img and cam_id:
                img = f"https://www.udottraffic.utah.gov/images/cameras/{cam_id}.jpg"
            cameras.append({
                "title":       f"UDOT: {name}",
                "url":         "https://www.udottraffic.utah.gov",
                "image_url":   img,
                "feed_type":   "image",
                "location":    name,
                "country":     "USA",
                "state":       "UT",
                "city":        "",
                "latitude":    lat,
                "longitude":   lon,
                "site_name":   "Utah DOT",
                "description": f"Utah DOT traffic camera – {name}.",
                "tags":        "traffic,udot,utah,dot,public",
                "source":      "udottraffic.utah.gov",
                "keywords":    f"utah traffic camera {name} udot",
            })
    except Exception as exc:
        logger.debug("UDOT fetch failed: %s", exc)
    logger.info("UDOT: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Minnesota DOT (MnDOT) – public JSON API
# ---------------------------------------------------------------------------
_MNDOT_URL = "https://511mn.org/api/graphql"
_MNDOT_IMG = "http://images.dot.state.mn.us/cameras/{cam_id}.jpg"


async def _fetch_mndot(client: httpx.AsyncClient) -> list[dict]:
    """MnDOT camera images are published at a known URL pattern."""
    cameras: list[dict] = []
    # MnDOT camera IDs follow range 800-900; try to scrape their public list
    try:
        resp = await client.get(
            "https://511mn.org/cameras",
            timeout=20,
            headers={"Accept": "text/html"},
        )
        soup = BeautifulSoup(resp.text, "html.parser")
        for a in soup.find_all("a", href=re.compile(r"/cameras/\d+")):
            cam_id = re.search(r"/cameras/(\d+)", a["href"])
            if not cam_id:
                continue
            cid = cam_id.group(1)
            title = a.get_text(strip=True) or f"MnDOT Camera {cid}"
            cameras.append({
                "title":       f"MnDOT: {title}",
                "url":         f"https://511mn.org/cameras/{cid}",
                "image_url":   _MNDOT_IMG.format(cam_id=cid),
                "feed_type":   "image",
                "location":    title,
                "country":     "USA",
                "state":       "MN",
                "city":        "",
                "latitude":    None,
                "longitude":   None,
                "site_name":   "Minnesota DOT",
                "description": f"Minnesota DOT traffic camera – {title}.",
                "tags":        "traffic,mndot,minnesota,dot,public",
                "source":      "511mn.org",
                "keywords":    f"minnesota traffic camera {title} mndot 511mn",
            })
    except Exception as exc:
        logger.debug("MnDOT fetch failed: %s", exc)
    logger.info("MnDOT: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Nevada DOT (NV511)
# ---------------------------------------------------------------------------
_NV511_URL = "https://nvroads.com/api/cameras"


async def _fetch_nv511(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(_NV511_URL, timeout=20)
        data = resp.json()
        entries = data if isinstance(data, list) else (data.get("features") or data.get("data") or [])
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            props = entry.get("properties") or entry
            cam_id = str(props.get("id") or props.get("cameraId") or "")
            name   = props.get("name") or props.get("description") or f"NDOT Cam {cam_id}"
            coords = (entry.get("geometry") or {}).get("coordinates") or []
            lat    = _safe_float(coords[1] if len(coords) > 1 else props.get("latitude"))
            lon    = _safe_float(coords[0] if coords else props.get("longitude"))
            img    = props.get("imageUrl") or props.get("url") or ""
            if not img and cam_id:
                img = f"https://nvroads.com/cameras/camera-images/{cam_id}.jpg"
            cameras.append({
                "title":       f"NDOT: {name}",
                "url":         "https://nvroads.com",
                "image_url":   img,
                "feed_type":   "image",
                "location":    name,
                "country":     "USA",
                "state":       "NV",
                "city":        "",
                "latitude":    lat,
                "longitude":   lon,
                "site_name":   "Nevada DOT",
                "description": f"Nevada DOT traffic camera – {name}.",
                "tags":        "traffic,ndot,nevada,dot,public",
                "source":      "nvroads.com",
                "keywords":    f"nevada traffic camera {name} ndot nv511",
            })
    except Exception as exc:
        logger.debug("NV511 fetch failed: %s", exc)
    logger.info("NV511: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

async def fetch_cameras(client: httpx.AsyncClient) -> list[dict]:
    results = await asyncio.gather(
        _fetch_wydot(client),
        _fetch_cotrip(client),
        _fetch_tripcheck(client),
        _fetch_udot(client),
        _fetch_mndot(client),
        _fetch_nv511(client),
        return_exceptions=True,
    )
    cameras: list[dict] = []
    for r in results:
        if isinstance(r, list):
            cameras.extend(r)
    return cameras


def _safe_float(val) -> float | None:
    try:
        return float(val)
    except (TypeError, ValueError):
        return None
