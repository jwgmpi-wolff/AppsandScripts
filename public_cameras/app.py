"""Public Cameras Agent – Flask application."""
from __future__ import annotations

import logging
import os
from pathlib import Path

import httpx
from flask import Flask, Response, jsonify, render_template, request

from agent.db import get_conn, init_db, search_cameras, get_stats, get_recent_searches
from agent.crawler import run_search

BASE_DIR = Path(__file__).resolve().parent

app = Flask(
    __name__,
    template_folder=str(BASE_DIR / "templates"),
    static_folder=str(BASE_DIR / "static"),
)

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("cameras-agent")

_PROXY_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0 Safari/537.36"
    ),
}


@app.before_request
def _ensure_db():
    pass  # DB is initialised at startup; this is a no-op guard.


# ---------------------------------------------------------------------------
# Pages
# ---------------------------------------------------------------------------

@app.route("/")
def index():
    return render_template("index.html")


# ---------------------------------------------------------------------------
# API
# ---------------------------------------------------------------------------

@app.route("/api/search", methods=["POST"])
def api_search():
    """Trigger a full camera discovery search.

    Body JSON: { "keyword": "optional filter string" }
    """
    data = request.get_json(silent=True) or {}
    keyword = str(data.get("keyword") or "").strip()
    try:
        result = run_search(keyword=keyword)
        return jsonify({"ok": True, **result})
    except Exception as exc:
        logger.exception("Search failed")
        return jsonify({"ok": False, "error": str(exc)}), 500


@app.route("/api/cameras")
def api_cameras():
    """Query saved cameras from DB.

    Query params:
      q         – keyword / FTS query
      page      – page number (default 1)
      per_page  – results per page (default 50, max 200)
      feed_type – filter by feed type
      state     – filter by state code
      source    – filter by source site
    """
    q         = request.args.get("q", "").strip()
    page      = max(1, int(request.args.get("page", 1)))
    per_page  = min(1000, max(10, int(request.args.get("per_page", 50))))
    feed_type = request.args.get("feed_type", "").strip()
    state     = request.args.get("state", "").strip()
    source    = request.args.get("source", "").strip()

    include_offline = request.args.get("include_offline", "0") == "1"

    rows, total = search_cameras(
        query=q, page=page, per_page=per_page,
        feed_type=feed_type, state=state, source=source,
        include_offline=include_offline,
    )
    return jsonify({
        "total": total,
        "page": page,
        "per_page": per_page,
        "cameras": rows,
    })


@app.route("/api/stats")
def api_stats():
    return jsonify(get_stats())


@app.route("/api/camera/<int:camera_id>/media", methods=["GET", "HEAD"])
def api_camera_media(camera_id: int):
    """Proxy a camera image/feed through localhost to avoid client-side CDN/network issues."""
    conn = get_conn()
    row = conn.execute(
        "SELECT image_url, url FROM cameras WHERE id = ?",
        (camera_id,),
    ).fetchone()
    conn.close()

    if not row:
        return jsonify({"ok": False, "error": "camera_not_found"}), 404

    target_url = (row["image_url"] or row["url"] or "").strip()
    if not target_url:
        return jsonify({"ok": False, "error": "camera_url_missing"}), 404

    try:
        method = "HEAD" if request.method == "HEAD" else "GET"
        with httpx.Client(headers=_PROXY_HEADERS, timeout=15.0, follow_redirects=True) as client:
            upstream = client.request(method, target_url)

        if upstream.status_code != 200:
            return jsonify({
                "ok": False,
                "error": "upstream_failed",
                "status_code": upstream.status_code,
            }), 502

        content_type = upstream.headers.get("content-type", "application/octet-stream")
        cache_control = "no-store"
        headers = {"Cache-Control": cache_control}
        if request.method == "HEAD":
            return Response(status=200, headers=headers, content_type=content_type)
        return Response(upstream.content, status=200, headers=headers, content_type=content_type)
    except Exception as exc:
        logger.debug("Proxy failed for camera_id=%s url=%s: %s", camera_id, target_url, exc)
        return jsonify({"ok": False, "error": "proxy_exception"}), 502


@app.route("/api/searches")
def api_searches():
    return jsonify(get_recent_searches())


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    init_db()
    port = int(os.getenv("PORT", 7000))
    debug = os.getenv("FLASK_DEBUG", "0") == "1"
    logger.info("Starting Public Cameras Agent on port %d", port)
    app.run(host="0.0.0.0", port=port, debug=debug)
