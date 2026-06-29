"""Main crawl orchestrator.

Runs all camera sources in parallel with asyncio, deduplicates, saves to DB.
Returns >= 200 cameras on first run without any retry logic.
"""
from __future__ import annotations

import asyncio
import logging
import re
import time
from typing import Any
from urllib.parse import urlparse

import httpx

from .db import upsert_cameras, log_search
from .validator import validate_cameras
from .stream_detector import detect_stream_url
from .sources import alertwildfire, caltrans, dotcams, aggregators, faa, surveillance, web_search, youtube
from .sources import browser as browser_crawler
from .sources.seeds import get_seeds

logger = logging.getLogger(__name__)

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0 Safari/537.36"
    ),
    "Accept-Language": "en-US,en;q=0.9",
}

_MEDIA_SUFFIXES = (
    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".tif", ".tiff",
    ".mp4", ".m3u8", ".ts", ".mjpeg", ".mjpg", ".webm", ".avi", ".mov",
)


def _looks_direct_media(url: str) -> bool:
    if not url:
        return False
    u = url.strip().lower()
    if not (u.startswith("http://") or u.startswith("https://")):
        return False

    parsed = urlparse(u)
    path = parsed.path or ""
    query = parsed.query or ""

    if any(path.endswith(ext) for ext in _MEDIA_SUFFIXES):
        return True

    hints = ("/camimages/", "/captures/", "/webcam/", "snapshot", "latest", "image")
    return any(h in path for h in hints) or any(h in query for h in hints)


def _normalize_camera(cam: dict) -> dict | None:
    direct = cam.get("image_url") or cam.get("stream_url") or cam.get("url") or ""
    if not _looks_direct_media(direct):
        return None

    normalized = dict(cam)
    normalized["image_url"] = direct
    normalized["url"] = direct
    
    # Detect stream URLs (HLS, MJPEG, MP4, etc.)
    stream_info = detect_stream_url(normalized)
    if stream_info.get("stream_url"):
        normalized["stream_url"] = stream_info["stream_url"]
        # Update feed_type if a recognized stream was detected
        if stream_info.get("stream_type"):
            normalized["feed_type"] = stream_info["stream_type"]
    
    return normalized


def _dedupe(cameras: list[dict]) -> list[dict]:
    seen: set[str] = set()
    out: list[dict] = []
    for cam in cameras:
        key = (cam.get("image_url") or cam.get("stream_url") or cam.get("url") or "").strip()
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(cam)
    return out


def _normalize_and_filter(cameras: list[dict]) -> list[dict]:
    out: list[dict] = []
    for cam in cameras:
        normalized = _normalize_camera(cam)
        if normalized:
            out.append(normalized)
    return out


def _apply_keyword_filter(cameras: list[dict], keyword: str) -> list[dict]:
    if not keyword:
        return cameras
    tokens = [token for token in re.split(r"\s+", keyword.strip().lower()) if token]
    if not tokens:
        return cameras
    
    # Synonym map for security/camera terms
    _SYNONYMS = {
        "security": ["security", "surveillance", "monitor", "cctv"],
        "surveillance": ["surveillance", "security", "monitor", "cctv"],
        "monitor": ["monitor", "surveillance", "camera", "cctv"],
        "cctv": ["cctv", "surveillance", "security", "camera"],
        "traffic": ["traffic", "road", "highway", "street", "dot", "transportation"],
        "wildfire": ["wildfire", "fire", "smoke", "burn"],
        "volcano": ["volcano", "volcanic", "lava", "crater"],
    }
    
    # url and image_url are intentionally excluded
    _MATCH_FIELDS = (
        "title", "location", "city", "state",
        "country", "site_name", "description", "tags", "source", "keywords",
    )
    
    result = []
    for camera in cameras:
        metadata = " ".join(str(camera.get(f) or "") for f in _MATCH_FIELDS).lower()
        
        # For each token, check if it or its synonyms appear in metadata
        all_match = True
        for token in tokens:
            synonyms = _SYNONYMS.get(token, [token])
            # Check if any synonym appears as a word boundary match
            token_found = any(
                re.search(r'\b' + re.escape(syn) + r'\b', metadata)
                for syn in synonyms
            )
            if not token_found:
                all_match = False
                break
        
        if all_match:
            result.append(camera)
    
    return result


async def _run_all_sources(client: httpx.AsyncClient) -> dict[str, list[dict]]:
    """Fire every source concurrently and collect results by source name."""
    tasks = {
        "alertwildfire": alertwildfire.fetch_cameras(client),
        "caltrans":      caltrans.fetch_cameras(client),
        "dotcams":       dotcams.fetch_cameras(client),
        "faa":           faa.fetch_cameras(client),
        "aggregators":   aggregators.fetch_cameras(client),
        "surveillance":  surveillance.fetch_cameras(client),
        "web_search":    web_search.fetch_cameras(client),
        "youtube":       youtube.fetch_cameras(client),
    }
    gathered = await asyncio.gather(*tasks.values(), return_exceptions=True)
    results = {name: (r if isinstance(r, list) else []) for name, r in zip(tasks, gathered)}

    # Browser crawler disabled by default (can be slow/fragile)
    # Uncomment below to enable Playwright-based consent-gate bypassing
    # try:
    #     browser_cams = await browser_crawler.fetch_cameras_browser()
    #     results["browser"] = browser_cams
    # except Exception as exc:
    #     logger.debug("Browser crawler skipped: %s", exc)
    #     results["browser"] = []

    return results


def run_search(keyword: str = "") -> dict[str, Any]:
    """
    Entry point called by the Flask API.
    Discovers cameras from all sources, saves to DB, returns summary.
    """
    t0 = time.time()
    logger.info("Starting camera search  keyword=%r", keyword)

    # Always include the curated seed list immediately
    seeds = get_seeds()
    logger.info("Seeds loaded: %d", len(seeds))

    # Run async sources
    async def _gather():
        limits = httpx.Limits(max_connections=40, max_keepalive_connections=20)
        timeout = httpx.Timeout(30.0, connect=10.0)
        async with httpx.AsyncClient(headers=_HEADERS, limits=limits, timeout=timeout) as client:
            return await _run_all_sources(client)

    try:
        source_results = asyncio.run(_gather())
    except RuntimeError:
        # If already inside an event loop (e.g. during testing), use a new loop
        loop = asyncio.new_event_loop()
        source_results = loop.run_until_complete(_gather())
        loop.close()

    # Aggregate everything
    all_cameras: list[dict] = list(seeds)
    sources_used = ["seeds"]
    for source_name, cams in source_results.items():
        all_cameras.extend(cams)
        sources_used.append(source_name)
        logger.info("  %-16s → %d cameras", source_name, len(cams))

    all_cameras = _normalize_and_filter(all_cameras)
    all_cameras = _dedupe(all_cameras)
    logger.info("Total after dedup: %d", len(all_cameras))

    # Apply keyword filter if supplied (we still save all to DB)
    upsert_cameras(all_cameras)
    log_search(keyword or "(all)", len(all_cameras), sources_used)

    # Validate image URLs for newly inserted / unknown cameras
    validate_cameras(limit=5000)

    filtered = _apply_keyword_filter(all_cameras, keyword) if keyword else all_cameras
    elapsed = round(time.time() - t0, 2)

    logger.info("Search complete in %.2fs – %d total, %d after filter", elapsed, len(all_cameras), len(filtered))
    return {
        "total": len(all_cameras),
        "filtered": len(filtered),
        "sources": sources_used,
        "elapsed_s": elapsed,
        "keyword": keyword,
        "cameras": filtered[:500],   # cap JSON response size
    }
