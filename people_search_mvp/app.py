import json
import logging
import os
import re
import sqlite3
from pathlib import Path

import requests
from bs4 import BeautifulSoup
from flask import Flask, jsonify, render_template, request

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "search_intelligence.db"
TEMPLATE_DIR = BASE_DIR / "templates"
STATIC_DIR = BASE_DIR / "static"

app = Flask(
    __name__,
    template_folder=str(TEMPLATE_DIR),
    static_folder=str(STATIC_DIR),
)

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("search-intelligence")

DEFAULT_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0 Safari/537.36"
    ),
    "Accept-Language": "en-US,en;q=0.9",
}

def init_db():
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS searches (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            query TEXT NOT NULL,
            filters TEXT,
            result_count INTEGER DEFAULT 0,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS search_results (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            search_id INTEGER NOT NULL,
            title TEXT,
            url TEXT,
            snippet TEXT,
            source TEXT,
            score REAL DEFAULT 0,
            FOREIGN KEY(search_id) REFERENCES searches(id)
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS learning_terms (
            term TEXT PRIMARY KEY,
            weight REAL DEFAULT 1.0
        )
        """
    )
    # Tables for webcam/stream scanner results
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS webcam_scans (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS webcam_success (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            scan_id INTEGER,
            url TEXT,
            status_code INTEGER,
            content_type TEXT,
            note TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY(scan_id) REFERENCES webcam_scans(id)
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS webcam_failure (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            scan_id INTEGER,
            url TEXT,
            error TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY(scan_id) REFERENCES webcam_scans(id)
        )
        """
    )
    conn.commit()
    conn.close()

init_db()

def get_conn():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def safe_request(url: str, *, params: dict, timeout: int = 20, source_name: str = "web"):
    try:
        logger.info("[%s] Request start url=%s params=%s", source_name, url, params)
        resp = requests.get(url, headers=DEFAULT_HEADERS, params=params, timeout=timeout)
        logger.info("[%s] Response status=%s final_url=%s", source_name, resp.status_code, resp.url)
        resp.raise_for_status()
        return resp, None
    except requests.RequestException as ex:
        logger.exception("[%s] Request failed: %s", source_name, ex)
        return None, str(ex)

def fetch_duckduckgo(query: str, max_results: int = 8):
    resp, err = safe_request(
        "https://duckduckgo.com/html/",
        params={"q": query, "kl": "us-en"},
        timeout=20,
        source_name="duckduckgo",
    )
    if err:
        return {"results": [], "error": err, "source": "duckduckgo"}

    soup = BeautifulSoup(resp.text, "html.parser")
    items = []
    seen = set()

    for result in soup.select("div.result"):
        a = result.select_one("a.result__a")
        if not a:
            continue

        title = a.get_text(" ", strip=True)
        href = a.get("href")
        if not href or not href.startswith(("http://", "https://")):
            continue
        if href in seen:
            continue

        snippet_node = result.select_one("a.result__snippet, div.result__snippet")
        snippet = snippet_node.get_text(" ", strip=True) if snippet_node else "DuckDuckGo result"

        items.append({
            "title": title,
            "url": href,
            "snippet": snippet,
            "source": "duckduckgo",
        })
        seen.add(href)

        if len(items) >= max_results:
            break

    logger.info("[duckduckgo] Parsed results=%s", len(items))
    return {"results": items, "error": None, "source": "duckduckgo"}

def fetch_brave(query: str, max_results: int = 8):
    resp, err = safe_request(
        "https://search.brave.com/search",
        params={"q": query},
        timeout=20,
        source_name="brave",
    )
    if err:
        return {"results": [], "error": err, "source": "brave"}

    soup = BeautifulSoup(resp.text, "html.parser")
    items = []
    seen = set()

    for a in soup.find_all("a", href=True):
        href = a["href"]
        title = " ".join(a.get_text(" ", strip=True).split())

        if not href.startswith("http"):
            continue
        if "brave.com" in href:
            continue
        if len(title) < 5:
            continue
        if href in seen:
            continue

        items.append({
            "title": title,
            "url": href,
            "snippet": "Brave public result",
            "source": "brave",
        })
        seen.add(href)

        if len(items) >= max_results:
            break

    logger.info("[brave] Parsed results=%s", len(items))
    return {"results": items, "error": None, "source": "brave"}

PROVIDERS = {
    "duckduckgo": fetch_duckduckgo,
    "brave": fetch_brave,
}

def fetch_results(source: str, query: str, max_results: int):
    source = (source or "all").lower()
    aggregated = []
    errors = []
    seen_urls = set()

    provider_names = list(PROVIDERS.keys()) if source == "all" else [source]

    for provider_name in provider_names:
        provider_func = PROVIDERS.get(provider_name)
        if not provider_func:
            warning = {
                "source": provider_name,
                "error": f"Unknown source '{provider_name}'. Supported values: all, duckduckgo, brave",
            }
            logger.warning("[router] %s", warning["error"])
            errors.append(warning)
            continue

        provider_response = provider_func(query, max_results)
        if provider_response.get("error"):
            errors.append({
                "source": provider_name,
                "error": provider_response["error"],
            })

        for item in provider_response.get("results", []):
            url = item.get("url")
            if not url or url in seen_urls:
                continue
            aggregated.append(item)
            seen_urls.add(url)

    logger.info("[router] Aggregated unique results=%s errors=%s", len(aggregated), len(errors))
    return aggregated, errors

def score_result(result, query_terms, learning_weights):
    title = (result.get("title") or "").lower()
    snippet = (result.get("snippet") or "").lower()
    url = (result.get("url") or "").lower()
    text = " ".join([title, snippet, url])
    score = 0.0

    for term in query_terms:
        if term in text:
            score += 1.5

    for term, weight in learning_weights.items():
        if term in text:
            score += weight

    if len(query_terms) == 1:
        score += 0.2

    return round(score, 2)

def load_learning_weights():
    conn = get_conn()
    rows = conn.execute("SELECT term, weight FROM learning_terms").fetchall()
    conn.close()
    return {row["term"]: row["weight"] for row in rows}


def expand_patterns(input_text: str, replacements_text: str = ""):
    """Accepts either a newline-separated list of URLs, or a single pattern using
    numeric range like https://example.com/cam{1-10} or a '*' placeholder with
    replacement values (comma or newline separated) provided in replacements_text."""
    items = []
    for line in input_text.splitlines():
        s = line.strip()
        if not s:
            continue
        # numeric range {start-end}
        m = re.search(r"\{(\d+)-(\d+)\}", s)
        if m:
            start = int(m.group(1))
            end = int(m.group(2))
            for i in range(start, end + 1):
                items.append(re.sub(r"\{\d+-\d+\}", str(i), s))
            continue

        if "*" in s and replacements_text:
            reps = [r.strip() for r in re.split(r"[\n,]+", replacements_text) if r.strip()]
            for r in reps:
                items.append(s.replace("*", r))
            continue

        items.append(s)

    return items


def check_url(url: str, timeout: int = 8):
    try:
        resp = requests.get(url, headers=DEFAULT_HEADERS, timeout=timeout, stream=False)
        status = resp.status_code
        ctype = resp.headers.get("Content-Type", "").lower()

        # heuristics for stream/camera
        is_video = False
        note = ""
        if any(ext in url.lower() for ext in (".m3u8", ".mjpeg", ".mp4", ".mjpg")):
            is_video = True
            note = "url-extension"
        if ctype.startswith("video") or "mpegurl" in ctype or "m3u8" in ctype:
            is_video = True
            note = note or f"content-type:{ctype}"

        text = resp.text.lower()[:4096]
        if not is_video and any(k in text for k in ("camera", "webcam", "stream", "mjpeg", "live")):
            is_video = True
            note = note or "html-indicator"

        if status >= 400:
            return False, status, ctype, f"http_{status}"

        return is_video, status, ctype, note
    except requests.RequestException as ex:
        return False, None, None, str(ex)


@app.route("/scan_webcams", methods=["POST"])
def scan_webcams():
    urls_text = request.form.get("urls", "")
    replacements = request.form.get("replacements", "")
    try:
        timeout = int(request.form.get("timeout", 8))
    except Exception:
        timeout = 8

    urls = expand_patterns(urls_text, replacements)
    if not urls:
        return jsonify({"error": "Provide one or more URLs or a pattern."}), 400

    conn = get_conn()
    cur = conn.cursor()
    cur.execute("INSERT INTO webcam_scans DEFAULT VALUES")
    scan_id = cur.lastrowid

    results = {"success": [], "failure": []}
    for u in urls:
        ok, status, ctype, note = check_url(u, timeout=timeout)
        if ok:
            cur.execute(
                "INSERT INTO webcam_success(scan_id, url, status_code, content_type, note) VALUES(?, ?, ?, ?, ?)",
                (scan_id, u, status or 0, ctype or "", note or ""),
            )
            results["success"].append({"url": u, "status": status, "content_type": ctype, "note": note})
        else:
            cur.execute(
                "INSERT INTO webcam_failure(scan_id, url, error) VALUES(?, ?, ?)",
                (scan_id, u, note),
            )
            results["failure"].append({"url": u, "error": note})

    conn.commit()
    conn.close()

    return jsonify({"scan_id": scan_id, "results": results, "count": len(urls)})


@app.route("/saved_webcams")
def saved_webcams():
    q = request.args.get("q", "").strip().lower()
    which = request.args.get("which", "success")
    conn = get_conn()
    if which == "failure":
        rows = conn.execute("SELECT * FROM webcam_failure WHERE lower(url) LIKE ? ORDER BY id DESC LIMIT 200", (f"%{q}%",)).fetchall()
        data = [dict(r) for r in rows]
    else:
        rows = conn.execute("SELECT * FROM webcam_success WHERE lower(url) LIKE ? ORDER BY id DESC LIMIT 200", (f"%{q}%",)).fetchall()
        data = [dict(r) for r in rows]
    conn.close()
    return jsonify(data)


@app.route("/clear_recent_scan", methods=["POST"])
def clear_recent_scan():
    # delete the most recent scan and its results
    conn = get_conn()
    cur = conn.cursor()
    row = cur.execute("SELECT id FROM webcam_scans ORDER BY id DESC LIMIT 1").fetchone()
    if not row:
        conn.close()
        return jsonify({"deleted": 0})
    scan_id = row[0]
    cur.execute("DELETE FROM webcam_success WHERE scan_id = ?", (scan_id,))
    cur.execute("DELETE FROM webcam_failure WHERE scan_id = ?", (scan_id,))
    cur.execute("DELETE FROM webcam_scans WHERE id = ?", (scan_id,))
    conn.commit()
    conn.close()
    return jsonify({"deleted": 1, "scan_id": scan_id})

def update_learning_weights(query_terms, scores):
    conn = get_conn()
    for term in query_terms:
        existing = conn.execute(
            "SELECT weight FROM learning_terms WHERE term = ?",
            (term,),
        ).fetchone()
        current = existing["weight"] if existing else 1.0
        new_weight = current + 0.15 * (sum(scores) / max(len(scores), 1))
        conn.execute(
            """
            INSERT INTO learning_terms(term, weight)
            VALUES(?, ?)
            ON CONFLICT(term)
            DO UPDATE SET weight = excluded.weight
            """,
            (term, new_weight),
        )
    conn.commit()
    conn.close()

@app.route("/")
def index():
    return render_template("index.html")

@app.route("/search", methods=["POST"])
def search():
    query = request.form.get("query", "").strip()
    try:
        max_results = int(request.form.get("max_results", 8) or 8)
    except ValueError:
        max_results = 8

    filters = {
        "entity": request.form.get("entity", "public"),
        "source": request.form.get("source", "all").lower(),
        "max_results": max(1, min(max_results, 25)),
    }

    if not query:
        return jsonify({"error": "Provide at least one search term."}), 400

    query_terms = [
        t for t in re.findall(r"[A-Za-z0-9]+", query.lower()) if len(t) > 2
    ]
    learning_weights = load_learning_weights()
    candidate_results, fetch_errors = fetch_results(
        filters["source"],
        query,
        filters["max_results"],
    )

    scored = []
    for item in candidate_results:
        score = score_result(item, query_terms, learning_weights)
        scored.append({**item, "score": score})

    scored_sorted = sorted(scored, key=lambda x: x["score"], reverse=True)
    result_count = len(scored_sorted)

    if result_count == 0:
        logger.warning(
            "[search] No results query=%r source=%s errors=%s",
            query,
            filters["source"],
            fetch_errors,
        )

    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO searches(query, filters, result_count) VALUES(?, ?, ?)",
        (query, json.dumps(filters), result_count),
    )
    search_id = cur.lastrowid

    for item in scored_sorted:
        cur.execute(
            """
            INSERT INTO search_results(search_id, title, url, snippet, source, score)
            VALUES(?, ?, ?, ?, ?, ?)
            """,
            (
                search_id,
                item["title"],
                item["url"],
                item["snippet"],
                item["source"],
                item["score"],
            ),
        )

    conn.commit()
    conn.close()

    update_learning_weights(query_terms, [item["score"] for item in scored_sorted])

    response = {
        "query": query,
        "filters": filters,
        "results": scored_sorted,
        "search_id": search_id,
        "learning_terms": load_learning_weights(),
        "fetch_errors": fetch_errors,
        "diagnostics": {
            "provider_count": 2,
            "requested_source": filters["source"],
            "result_count": result_count,
        },
    }
    return jsonify(response)

@app.route("/history")
def history():
    conn = get_conn()
    searches = conn.execute("SELECT * FROM searches ORDER BY id DESC LIMIT 10").fetchall()
    conn.close()
    return jsonify([dict(row) for row in searches])

if __name__ == "__main__":
    logger.info("Starting Search Intelligence app")
    app.run(debug=True, host="0.0.0.0", port=int(os.getenv("PORT", 5000)))
