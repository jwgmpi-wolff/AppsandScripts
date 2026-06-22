"""Async image URL validator.

After cameras are inserted, validates each image_url with a HEAD (or GET)
request and sets status = 'online' | 'offline' in the DB.
Runs concurrently with a configurable semaphore limit.
"""
from __future__ import annotations

import asyncio
import logging
import sqlite3
from datetime import datetime

import httpx

from .db import DB_PATH

logger = logging.getLogger(__name__)

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0 Safari/537.36"
    ),
}

_IMAGE_CONTENT_TYPES = (
    "image/", "video/", "application/octet-stream",
    "application/vnd.apple.mpegurl", "application/x-mpegurl",
)


async def _check_url(client: httpx.AsyncClient, sem: asyncio.Semaphore,
                     cam_id: int, image_url: str) -> tuple[int, str]:
    """Return (cam_id, 'online'|'offline')."""
    async with sem:
        try:
            r = await client.head(image_url, timeout=8, follow_redirects=True)
            ct = r.headers.get("content-type", "").lower()
            if r.status_code == 200 and any(ct.startswith(t) for t in _IMAGE_CONTENT_TYPES):
                return cam_id, "online"
            if r.status_code == 200:
                # Content-type not image — try a small GET to confirm
                r2 = await client.get(image_url, timeout=8, follow_redirects=True)
                ct2 = r2.headers.get("content-type", "").lower()
                if r2.status_code == 200 and any(ct2.startswith(t) for t in _IMAGE_CONTENT_TYPES):
                    return cam_id, "online"
            return cam_id, "offline"
        except Exception:
            return cam_id, "offline"


async def _validate_batch(rows: list[tuple[int, str]], concurrency: int = 40) -> dict[int, str]:
    sem = asyncio.Semaphore(concurrency)
    limits = httpx.Limits(max_connections=concurrency, max_keepalive_connections=20)
    timeout = httpx.Timeout(10.0, connect=5.0)
    results: dict[int, str] = {}

    async with httpx.AsyncClient(headers=_HEADERS, limits=limits, timeout=timeout) as client:
        tasks = [_check_url(client, sem, cam_id, url) for cam_id, url in rows]
        for coro in asyncio.as_completed(tasks):
            cam_id, status = await coro
            results[cam_id] = status

    return results


def _write_statuses(results: dict[int, str]):
    if not results:
        return
    now = datetime.utcnow().isoformat()
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.executemany(
        "UPDATE cameras SET status = ?, last_checked = ? WHERE id = ?",
        [(status, now, cam_id) for cam_id, status in results.items()],
    )
    conn.commit()
    conn.close()


def validate_cameras(limit: int = 500):
    """Validate up to `limit` cameras whose status is 'unknown'.

    Runs synchronously (blocks) — call from the crawler after upsert.
    """
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.execute(
        "SELECT id, image_url FROM cameras WHERE status = 'unknown' AND image_url != '' LIMIT ?",
        (limit,),
    )
    rows = cur.fetchall()
    conn.close()

    if not rows:
        logger.info("Validator: no unknown cameras to check")
        return

    logger.info("Validator: checking %d image URLs …", len(rows))

    try:
        results = asyncio.run(_validate_batch(rows))
    except RuntimeError:
        loop = asyncio.new_event_loop()
        results = loop.run_until_complete(_validate_batch(rows))
        loop.close()

    online  = sum(1 for s in results.values() if s == "online")
    offline = sum(1 for s in results.values() if s == "offline")
    _write_statuses(results)
    logger.info("Validator: %d online, %d offline", online, offline)
