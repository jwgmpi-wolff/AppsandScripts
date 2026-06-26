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
