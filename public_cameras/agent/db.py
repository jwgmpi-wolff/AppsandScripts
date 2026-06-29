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
            stream_url    TEXT,
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
    # Rebuild FTS5 index for any cameras that were stored before the FTS table existed
    try:
        cam_count = cur.execute("SELECT COUNT(*) FROM cameras").fetchone()[0]
        fts_count = cur.execute("SELECT COUNT(*) FROM cameras_fts").fetchone()[0]
        if cam_count > 0 and fts_count < cam_count:
            logger.info("Rebuilding FTS5 index (%d cameras, %d indexed)", cam_count, fts_count)
            cur.execute("INSERT INTO cameras_fts(cameras_fts) VALUES('rebuild')")
            conn.commit()
    except Exception as _fts_exc:
        logger.warning("FTS5 rebuild skipped: %s", _fts_exc)
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
            logger.debug("upsert skip: no image_url")
            continue
        primary_url = cam.get("image_url") or cam.get("stream_url") or cam.get("url") or image_url
        if not _is_direct_media_url(primary_url):
            logger.debug("upsert skip: not direct media url: %s", primary_url)
            continue
        try:
            cur.execute(
                """
                INSERT INTO cameras
                    (title, url, image_url, stream_url, feed_type, location, country, state, city,
                     latitude, longitude, site_name, description, tags, source, keywords,
                     status, discovered_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(image_url) DO UPDATE SET
                    title        = excluded.title,
                    stream_url   = excluded.stream_url,
                    feed_type    = excluded.feed_type,
                    status       = 'unknown',
                    last_checked = NULL
                """,
                (
                    cam.get("title", ""),
                    primary_url,
                    image_url,
                    cam.get("stream_url", ""),
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
                    "unknown",
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


def _build_fts_query(query: str) -> str:
    """Convert a raw user query into an FTS5 MATCH expression.

    Each whitespace-separated token is sanitized and double-quoted so FTS5
    matches exact tokens (no accidental prefix expansion).  Multiple tokens
    are separated by a space which FTS5 treats as an implicit AND – every
    token must appear in the indexed metadata columns for a row to match.

    url and image_url are intentionally NOT indexed, so path segments,
    camera IDs, or CDN domain names never cause spurious hits.
    """
    tokens = [t for t in re.split(r"\s+", query.strip()) if t]
    safe: list[str] = []
    for t in tokens:
        # Strip chars that break FTS5 query syntax
        clean = re.sub(r'["()*^:+\-]', "", t).strip()
        if clean:
            safe.append(f'"{clean}"')
    return " ".join(safe)   # space == implicit AND in FTS5


def search_cameras(query: str = "", page: int = 1, per_page: int = 50,
                   feed_type: str = "", state: str = "", source: str = "",
                   include_offline: bool = False) -> tuple[list, int]:
    """Full-text search + filter cameras. Returns (rows, total_count).

    Text matching uses the FTS5 virtual table which indexes only metadata
    columns: title, location, city, state, country, site_name, description,
    tags, source, keywords.  The url and image_url columns are intentionally
    excluded so that path tokens, camera IDs, or CDN hostnames in media
    endpoint URLs never produce false keyword matches.

    By default excludes cameras whose status='offline'.
    """
    conn = get_conn()
    cur = conn.cursor()

    filter_params: list = []
    filter_wheres: list[str] = []

    if feed_type:
        filter_wheres.append("feed_type = ?")
        filter_params.append(feed_type)
    if state:
        filter_wheres.append("state = ?")
        filter_params.append(state)
    if source:
        filter_wheres.append("source LIKE ?")
        filter_params.append(f"%{source}%")
    if not include_offline:
        filter_wheres.append("status != 'offline'")

    _SELECT_COLS = (
        "id, title, url, image_url, stream_url, feed_type, "
        "location, country, state, city, latitude, longitude, "
        "site_name, description, tags, source, keywords, "
        "status, last_checked, discovered_at"
    )

    def _execute(fts_expr: str | None) -> tuple[list, int]:
        all_wheres = list(filter_wheres)
        all_params = list(filter_params)
        if fts_expr:
            all_wheres.insert(0, "id IN (SELECT rowid FROM cameras_fts WHERE cameras_fts MATCH ?)")
            all_params.insert(0, fts_expr)
        where_clause = ("WHERE " + " AND ".join(all_wheres)) if all_wheres else ""
        cur.execute(f"SELECT COUNT(*) FROM cameras {where_clause}", all_params)
        total = cur.fetchone()[0]
        offset = (page - 1) * per_page
        cur.execute(
            f"SELECT {_SELECT_COLS} FROM cameras {where_clause}"
            " ORDER BY discovered_at DESC LIMIT ? OFFSET ?",
            all_params + [per_page, offset],
        )
        return [dict(r) for r in cur.fetchall()], total

    rows: list = []
    total: int = 0

    if query:
        fts_expr = _build_fts_query(query)
        if fts_expr:
            try:
                rows, total = _execute(fts_expr)
            except Exception as exc:
                # FTS5 raised a syntax error (e.g. special-char query) –
                # fall back to LIKE on metadata-only fields, still no url/image_url
                logger.warning("FTS5 query failed (%s), using LIKE fallback", exc)
                fb_wheres = list(filter_wheres)
                fb_params = list(filter_params)
                meta_blob = (
                    "lower(coalesce(title,'') || ' ' || coalesce(location,'') || ' ' ||"
                    " coalesce(city,'') || ' ' || coalesce(state,'') || ' ' ||"
                    " coalesce(country,'') || ' ' || coalesce(site_name,'') || ' ' ||"
                    " coalesce(description,'') || ' ' || coalesce(tags,'') || ' ' ||"
                    " coalesce(source,'') || ' ' || coalesce(keywords,''))"
                )
                for tok in [t for t in re.split(r"\s+", query.strip().lower()) if t]:
                    fb_wheres.append(f"{meta_blob} LIKE ?")
                    fb_params.append(f"%{tok}%")
                where_clause = ("WHERE " + " AND ".join(fb_wheres)) if fb_wheres else ""
                cur.execute(f"SELECT COUNT(*) FROM cameras {where_clause}", fb_params)
                total = cur.fetchone()[0]
                offset = (page - 1) * per_page
                cur.execute(
                    f"SELECT {_SELECT_COLS} FROM cameras {where_clause}"
                    " ORDER BY discovered_at DESC LIMIT ? OFFSET ?",
                    fb_params + [per_page, offset],
                )
                rows = [dict(r) for r in cur.fetchall()]
        else:
            rows, total = _execute(None)
    else:
        rows, total = _execute(None)

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
    # Return searches with result counts, ordered by recency
    # Filter out "(all)" queries (empty keyword searches)
    cur.execute(
        "SELECT query, result_count, created_at FROM searches WHERE query != '(all)' ORDER BY created_at DESC LIMIT ?",
        (limit,)
    )
    rows = [dict(r) for r in cur.fetchall()]
    conn.close()
    return rows
