import json
import logging
import os
import re
import sqlite3
from pathlib import Path

import requests
from bs4 import BeautifulSoup
from flask import Flask, jsonify, render_template, request
from urllib.parse import urlparse
import csv
import io
import datetime
import time

# optional background worker (RQ)
try:
    import redis
    from rq import Queue
    RQ_AVAILABLE = True
    redis_conn = redis.Redis.from_url(os.getenv('REDIS_URL', 'redis://localhost:6379'))
    queue = Queue(connection=redis_conn)
except Exception:
    RQ_AVAILABLE = False
    queue = None

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
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS webcam_tasks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            scan_id INTEGER,
            job_id TEXT,
            status TEXT DEFAULT 'queued',
            progress INTEGER DEFAULT 0,
            total INTEGER DEFAULT 0,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
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


def find_webcam_urls_from_query(query: str, max_results: int = 8):
    """Run provider searches for a free-text query and return candidate URLs
    that look like webcams, livestreams, or camera endpoints.
    """
    results, errors = fetch_results("all", query, max_results)
    candidates = []
    seen = set()

    webcam_indicators = ("camera", "webcam", "stream", "mjpeg", "m3u8", "live", "ipcam", "snapshot")
    for item in results:
        url = item.get("url")
        title = (item.get("title") or "").lower()
        snippet = (item.get("snippet") or "").lower()
        if not url:
            continue
        if url in seen:
            continue

        # prefer URLs that have explicit video/stream extensions
        if any(ext in url.lower() for ext in (".m3u8", ".mjpeg", ".mp4", ".mjpg")):
            candidates.append(url)
            seen.add(url)
            continue

        # next prefer pages whose title/snippet mention webcams/stream
        text = " ".join([title, snippet, url.lower()])
        if any(k in text for k in webcam_indicators):
            candidates.append(url)
            seen.add(url)
            continue

        # otherwise skip noisy results

    return candidates


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


def perform_scan_job(scan_id, urls, timeout=8, task_id=None):
    """Background worker job: checks each URL and writes to DB, updates task progress."""
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    total = len(urls)
    if task_id:
        cur.execute("UPDATE webcam_tasks SET total = ?, status = ?, progress = 0 WHERE id = ?", (total, 'running', task_id))
        conn.commit()

    i = 0
    for u in urls:
        i += 1
        ok, status, ctype, note = check_url(u, timeout=timeout)
        if ok:
            cur.execute(
                "INSERT INTO webcam_success(scan_id, url, status_code, content_type, note) VALUES(?, ?, ?, ?, ?)",
                (scan_id, u, status or 0, ctype or "", note or ""),
            )
        else:
            cur.execute(
                "INSERT INTO webcam_failure(scan_id, url, error) VALUES(?, ?, ?)",
                (scan_id, u, note),
            )

        if task_id:
            cur.execute("UPDATE webcam_tasks SET progress = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", (i, task_id))
        conn.commit()

    if task_id:
        cur.execute("UPDATE webcam_tasks SET status = ?, progress = total, updated_at = CURRENT_TIMESTAMP WHERE id = ?", ('completed', task_id))
        conn.commit()
    conn.close()


def enqueue_scan_job(scan_id, urls, timeout=8):
    conn = get_conn()
    cur = conn.cursor()
    # create task record
    cur.execute("INSERT INTO webcam_tasks(scan_id, status, progress, total) VALUES(?, ?, ?, ?)", (scan_id, 'queued', 0, len(urls)))
    task_id = cur.lastrowid
    conn.commit()
    conn.close()

    if RQ_AVAILABLE and queue:
        # enqueue background job and store job id
        job = queue.enqueue(perform_scan_job, scan_id, urls, timeout, task_id)
        conn = get_conn()
        conn.execute("UPDATE webcam_tasks SET job_id = ? WHERE id = ?", (job.get_id(), task_id))
        conn.commit()
        conn.close()
        return {'task_id': task_id, 'job_id': job.get_id()}
    else:
        # Fall back to a thread-based background run
        import threading

        def run():
            perform_scan_job(scan_id, urls, timeout=timeout, task_id=task_id)

        t = threading.Thread(target=run, daemon=True)
        t.start()
        return {'task_id': task_id, 'job_id': None}


@app.route("/scan_webcams", methods=["POST"])
def scan_webcams():
    urls_text = request.form.get("urls", "")
    replacements = request.form.get("replacements", "")
    try:
        timeout = int(request.form.get("timeout", 8))
    except Exception:
        timeout = 8

    raw_items = expand_patterns(urls_text, replacements)
    if not raw_items:
        return jsonify({"error": "Provide one or more URLs, patterns, or keyword queries."}), 400

    # Build a list of real http(s) URLs. If an item looks like a plain keyword
    # (no scheme and no dot), treat it as a search query and expand to candidate
    # webcam/stream URLs using search providers.
    urls = []
    for it in raw_items:
        it = it.strip()
        if not it:
            continue
        if it.lower().startswith(("http://", "https://")):
            urls.append(it)
            continue
        # bare domain without scheme -> add http://
        if re.match(r"^[\w\-]+\.[\w\.-]+", it):
            urls.append("http://" + it)
            continue

        # otherwise treat as keyword search
        candidates = find_webcam_urls_from_query(it, max_results=8)
        for c in candidates:
            urls.append(c)

    if not urls:
        return jsonify({"error": "No candidate URLs found from provided inputs."}), 400

    # create scan row
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("INSERT INTO webcam_scans DEFAULT VALUES")
    scan_id = cur.lastrowid
    conn.commit()
    conn.close()

    # background requested?
    if request.form.get('background') in ('1', 'true', 'yes', 'on'):
        job = enqueue_scan_job(scan_id, urls, timeout=timeout)
        return jsonify({"scan_id": scan_id, "background": True, "task": job})

    results = {"success": [], "failure": []}
    conn = get_conn()
    cur = conn.cursor()
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

    return jsonify({"scan_id": scan_id, "results": results, "count": len(urls), "background": False})


@app.route('/enqueue_scan', methods=['POST'])
def enqueue_scan():
    urls_text = request.form.get('urls', '')
    replacements = request.form.get('replacements', '')
    try:
        timeout = int(request.form.get('timeout', 8))
    except Exception:
        timeout = 8
    raw_items = expand_patterns(urls_text, replacements)
    if not raw_items:
        return jsonify({'error': 'Provide one or more URLs, patterns, or keyword queries.'}), 400

    urls = []
    for it in raw_items:
        it = it.strip()
        if not it:
            continue
        if it.lower().startswith(("http://", "https://")):
            urls.append(it)
            continue
        if re.match(r"^[\w\-]+\.[\w\.-]+", it):
            urls.append("http://" + it)
            continue
        candidates = find_webcam_urls_from_query(it, max_results=8)
        for c in candidates:
            urls.append(c)

    if not urls:
        return jsonify({'error': 'No candidate URLs found from provided inputs.'}), 400
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("INSERT INTO webcam_scans DEFAULT VALUES")
    scan_id = cur.lastrowid
    conn.commit()
    conn.close()

    job = enqueue_scan_job(scan_id, urls, timeout=timeout)
    return jsonify({'scan_id': scan_id, 'task': job, 'rq_available': RQ_AVAILABLE})


@app.route('/scan_status')
def scan_status():
    job_id = request.args.get('job_id')
    task_id = request.args.get('task_id')
    conn = get_conn()
    cur = conn.cursor()
    if task_id:
        row = cur.execute('SELECT id, scan_id, job_id, status, progress, total, created_at, updated_at FROM webcam_tasks WHERE id = ?', (task_id,)).fetchone()
        conn.close()
        return jsonify(dict(row) if row else {})
    if job_id:
        row = cur.execute('SELECT id, scan_id, job_id, status, progress, total, created_at, updated_at FROM webcam_tasks WHERE job_id = ?', (job_id,)).fetchone()
        conn.close()
        return jsonify(dict(row) if row else {})
    conn.close()
    return jsonify({})


@app.route('/export_webcams')
def export_webcams():
    which = request.args.get('which', 'success')
    conn = get_conn()
    if which == 'failure':
        rows = conn.execute('SELECT url,error,created_at FROM webcam_failure ORDER BY id DESC').fetchall()
        headers = ['url', 'error', 'created_at']
    else:
        rows = conn.execute('SELECT url,status_code,content_type,note,created_at FROM webcam_success ORDER BY id DESC').fetchall()
        headers = ['url', 'status_code', 'content_type', 'note', 'created_at']
    conn.close()

    si = io.StringIO()
    cw = csv.writer(si)
    cw.writerow(headers)
    for r in rows:
        cw.writerow([r[h] for h in headers])

    output = si.getvalue()
    return (output, 200, {
        'Content-Type': 'text/csv',
        'Content-Disposition': f'attachment; filename="webcam_{which}_{datetime.datetime.utcnow().isoformat()}.csv"'
    })


@app.route('/import_webcams', methods=['POST'])
def import_webcams():
    which = request.args.get('which', 'success')
    f = request.files.get('file')
    if not f:
        return jsonify({'error': 'file required'}), 400
    stream = io.StringIO(f.stream.read().decode('utf-8'))
    reader = csv.DictReader(stream)
    conn = get_conn()
    cur = conn.cursor()
    count = 0
    for row in reader:
        url = row.get('url') or row.get('URL')
        if not url:
            continue
        if which == 'failure':
            error = row.get('error') or ''
            cur.execute('INSERT INTO webcam_failure(scan_id, url, error) VALUES(?, ?, ?)', (None, url, error))
        else:
            status_code = row.get('status_code') or row.get('status') or 0
            content_type = row.get('content_type') or ''
            note = row.get('note') or ''
            cur.execute('INSERT INTO webcam_success(scan_id, url, status_code, content_type, note) VALUES(?, ?, ?, ?, ?)', (None, url, status_code, content_type, note))
        count += 1
    conn.commit()
    conn.close()
    return jsonify({'imported': count})


@app.route("/saved_webcams")
def saved_webcams():
    q = request.args.get('q', '').strip().lower()
    which = request.args.get('which', 'success')
    page = max(1, int(request.args.get('page', 1)))
    per_page = min(200, max(5, int(request.args.get('per_page', 50))))
    content_type = request.args.get('content_type', '').strip().lower()
    date_from = request.args.get('date_from')
    date_to = request.args.get('date_to')

    params = []
    where_clauses = []
    if q:
        where_clauses.append('lower(url) LIKE ?')
        params.append(f'%{q}%')
    if content_type:
        where_clauses.append('lower(content_type) LIKE ?')
        params.append(f'%{content_type}%')
    if date_from:
        where_clauses.append('date(created_at) >= date(?)')
        params.append(date_from)
    if date_to:
        where_clauses.append('date(created_at) <= date(?)')
        params.append(date_to)

    where_sql = (' WHERE ' + ' AND '.join(where_clauses)) if where_clauses else ''
    offset = (page - 1) * per_page

    conn = get_conn()
    if which == 'failure':
        sql = f"SELECT * FROM webcam_failure {where_sql} ORDER BY id DESC LIMIT ? OFFSET ?"
    else:
        sql = f"SELECT * FROM webcam_success {where_sql} ORDER BY id DESC LIMIT ? OFFSET ?"

    rows = conn.execute(sql, (*params, per_page, offset)).fetchall()
    data = [dict(r) for r in rows]
    # count total
    count_sql = f"SELECT COUNT(1) as cnt FROM {'webcam_failure' if which=='failure' else 'webcam_success'} {where_sql}"
    total = conn.execute(count_sql, params).fetchone()['cnt']
    conn.close()
    return jsonify({'items': data, 'page': page, 'per_page': per_page, 'total': total})


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


def display_title_for_url(u: str):
    try:
        p = urlparse(u)
        title = p.netloc
        if p.path and p.path != "/":
            title += p.path
        return title
    except Exception:
        return u


@app.route('/webcams_page')
def webcams_page():
    """Render a simple public-facing page of saved webcams filtered by `q`.
    Example: /webcams_page?q=everett
    """
    q = (request.args.get('q') or '').strip().lower()
    limit = min(200, max(10, int(request.args.get('limit') or 50)))

    conn = get_conn()
    cur = conn.cursor()
    params = []
    where = ''
    if q:
        where = "WHERE lower(url) LIKE ? OR lower(note) LIKE ? OR lower(content_type) LIKE ?"
        params = [f'%{q}%', f'%{q}%', f'%{q}%']

    sql = f"SELECT url, status_code, content_type, note, created_at FROM webcam_success {where} ORDER BY id DESC LIMIT ?"
    rows = cur.execute(sql, (*params, limit)).fetchall()
    conn.close()

    items = []
    for r in rows:
        url = r['url']
        ctype = r['content_type'] or ''
        note = r['note'] or ''
        is_video = any(ext in url.lower() for ext in ('.m3u8', '.mjpeg', '.mp4', '.mjpg')) or ctype.startswith('video')
        items.append({
            'url': url,
            'title': display_title_for_url(url),
            'content_type': ctype,
            'note': note,
            'is_video': is_video,
        })

    page_title = f"Webcams matching '{q}'" if q else 'Webcams'
    return render_template('webcams_page.html', title=page_title, items=items, query=q)

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
