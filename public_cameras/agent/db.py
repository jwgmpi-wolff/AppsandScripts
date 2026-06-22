"""Database layer for the public cameras agent."""
import sqlite3
import json
import logging
import re
from pathlib import Path
from datetime import datetime
from urllib.parse import urlparse

logger = logging.getLogger(__name__)

BASE_DIR = Path(__file__).resolve().parent.parent
DB_PATH = BASE_DIR / "cameras.db"

_MEDIA_SUFFIXES = (
    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".tif", ".tiff",
    ".mp4", ".m3u8", ".ts", ".mjpeg", ".mjpg", ".webm", ".avi", ".mov",
)


def _is_direct_media_url(url: str) -> bool:
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


def get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    conn = get_conn()
    cur = conn.cursor()

    # Main cameras table with full metadata
    cur.executescript(
        """
        CREATE TABLE IF NOT EXISTS cameras (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            title         TEXT,
            url           TEXT,
            image_url     TEXT,
            feed_type     TEXT DEFAULT 'image',
            location      TEXT,
            country       TEXT DEFAULT 'USA',
            state         TEXT,
            city          TEXT,
            latitude      REAL,
            longitude     REAL,
            site_name     TEXT,
            description   TEXT,
            tags          TEXT,
            source        TEXT,
            keywords      TEXT,
            status        TEXT DEFAULT 'unknown',
            last_checked  TEXT,
            discovered_at TEXT DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(image_url)
        );

        CREATE INDEX IF NOT EXISTS idx_cameras_source   ON cameras(source);
        CREATE INDEX IF NOT EXISTS idx_cameras_state    ON cameras(state);
        CREATE INDEX IF NOT EXISTS idx_cameras_country  ON cameras(country);
        CREATE INDEX IF NOT EXISTS idx_cameras_site     ON cameras(site_name);
        CREATE INDEX IF NOT EXISTS idx_cameras_feed     ON cameras(feed_type);

        CREATE VIRTUAL TABLE IF NOT EXISTS cameras_fts
            USING fts5(
                title, location, city, state, country,
                site_name, description, tags, source, keywords,
                content='cameras', content_rowid='id'
            );

        CREATE TABLE IF NOT EXISTS searches (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            query        TEXT,
            result_count INTEGER DEFAULT 0,
            sources_used TEXT,
            created_at   TEXT DEFAULT CURRENT_TIMESTAMP
        );
        """
    )
    conn.commit()
    conn.close()
    logger.info("DB initialised at %s", DB_PATH)


def upsert_cameras(cameras: list[dict]) -> int:
    """Insert cameras; skip duplicates based on image_url. Returns inserted count."""
    if not cameras:
        return 0
    conn = get_conn()
    cur = conn.cursor()
    inserted = 0
    for cam in cameras:
        image_url = cam.get("image_url") or cam.get("url") or ""
        if not image_url:
            continue
        primary_url = cam.get("image_url") or cam.get("stream_url") or cam.get("url") or image_url
        if not _is_direct_media_url(primary_url):
            continue
        try:
            cur.execute(
                """
                INSERT INTO cameras
                    (title, url, image_url, feed_type, location, country, state, city,
                     latitude, longitude, site_name, description, tags, source, keywords,
                     status, discovered_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'unknown',?)
                ON CONFLICT(image_url) DO UPDATE SET
                    title        = excluded.title,
                    status       = 'unknown',
                    last_checked = NULL
                """,
                (
                    cam.get("title", ""),
                    primary_url,
                    image_url,
                    cam.get("feed_type", "image"),
                    cam.get("location", ""),
                    cam.get("country", "USA"),
                    cam.get("state", ""),
                    cam.get("city", ""),
                    cam.get("latitude"),
                    cam.get("longitude"),
                    cam.get("site_name", ""),
                    cam.get("description", ""),
                    cam.get("tags", ""),
                    cam.get("source", ""),
                    cam.get("keywords", ""),
                    datetime.utcnow().isoformat(),
                ),
            )
            if cur.rowcount:
                inserted += 1
                # sync FTS
                rowid = cur.lastrowid
                cur.execute(
                    "INSERT OR REPLACE INTO cameras_fts(rowid, title, location, city, state, country, site_name, description, tags, source, keywords) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        rowid,
                        cam.get("title", ""),
                        cam.get("location", ""),
                        cam.get("city", ""),
                        cam.get("state", ""),
                        cam.get("country", "USA"),
                        cam.get("site_name", ""),
                        cam.get("description", ""),
                        cam.get("tags", ""),
                        cam.get("source", ""),
                        cam.get("keywords", ""),
                    ),
                )
        except Exception as e:
            logger.debug("upsert skip: %s", e)
    conn.commit()
    conn.close()
    return inserted


def search_cameras(query: str = "", page: int = 1, per_page: int = 50,
                   feed_type: str = "", state: str = "", source: str = "",
                   include_offline: bool = False) -> tuple[list, int]:
    """Full-text search + filter cameras. Returns (rows, total_count).
    By default excludes cameras whose status='offline'.
    """
    conn = get_conn()
    cur = conn.cursor()

    params: list = []
    wheres: list[str] = []

    if query:
        tokens = [token for token in re.split(r"\s+", query.strip().lower()) if token]
        if tokens:
            search_blob = (
                "lower(coalesce(title,'') || ' ' || coalesce(url,'') || ' ' || "
                "coalesce(image_url,'') || ' ' || coalesce(location,'') || ' ' || "
                "coalesce(city,'') || ' ' || coalesce(state,'') || ' ' || "
                "coalesce(country,'') || ' ' || coalesce(site_name,'') || ' ' || "
                "coalesce(description,'') || ' ' || coalesce(tags,'') || ' ' || "
                "coalesce(source,'') || ' ' || coalesce(keywords,''))"
            )
            for token in tokens:
                wheres.append(f"{search_blob} LIKE ?")
                params.append(f"%{token}%")
    if feed_type:
        wheres.append("feed_type = ?")
        params.append(feed_type)
    if state:
        wheres.append("state = ?")
        params.append(state)
    if source:
        wheres.append("source LIKE ?")
        params.append(f"%{source}%")

    if not include_offline:
        wheres.append("status != 'offline'")

    where_clause = ("WHERE " + " AND ".join(wheres)) if wheres else ""

    count_sql = f"SELECT COUNT(*) FROM cameras {where_clause}"
    cur.execute(count_sql, params)
    total = cur.fetchone()[0]

    offset = (page - 1) * per_page
    data_sql = f"""
        SELECT id, title, url, image_url, feed_type,
               location, country, state, city, latitude, longitude,
               site_name, description, tags, source, keywords,
               status, last_checked, discovered_at
        FROM cameras {where_clause}
        ORDER BY discovered_at DESC
        LIMIT ? OFFSET ?
    """
    cur.execute(data_sql, params + [per_page, offset])
    rows = [dict(r) for r in cur.fetchall()]
    conn.close()
    return rows, total


def get_stats() -> dict:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM cameras")
    total = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM cameras WHERE status='online'")
    online = cur.fetchone()[0]
    cur.execute("SELECT source, COUNT(*) cnt FROM cameras GROUP BY source ORDER BY cnt DESC LIMIT 10")
    by_source = [{"source": r[0], "count": r[1]} for r in cur.fetchall()]
    cur.execute("SELECT state, COUNT(*) cnt FROM cameras WHERE state != '' GROUP BY state ORDER BY cnt DESC LIMIT 15")
    by_state = [{"state": r[0], "count": r[1]} for r in cur.fetchall()]
    cur.execute("SELECT feed_type, COUNT(*) cnt FROM cameras GROUP BY feed_type")
    by_type = [{"feed_type": r[0], "count": r[1]} for r in cur.fetchall()]
    conn.close()
    return {"total": total, "online": online, "by_source": by_source,
            "by_state": by_state, "by_type": by_type}


def log_search(query: str, count: int, sources: list[str]):
    conn = get_conn()
    conn.execute(
        "INSERT INTO searches (query, result_count, sources_used, created_at) VALUES (?,?,?,?)",
        (query, count, json.dumps(sources), datetime.utcnow().isoformat()),
    )
    conn.commit()
    conn.close()


def get_recent_searches(limit: int = 20) -> list:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM searches ORDER BY created_at DESC LIMIT ?", (limit,))
    rows = [dict(r) for r in cur.fetchall()]
    conn.close()
    return rows
