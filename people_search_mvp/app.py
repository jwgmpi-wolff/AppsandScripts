import json
import logging
import os
import re
import socket
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from bs4 import BeautifulSoup
from flask import Flask, abort, jsonify, redirect, render_template, request
from urllib.parse import urlparse
import csv
import io
import datetime
import time
from html import unescape
from urllib.parse import quote_plus

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
    result_columns = {
        row[1] for row in cur.execute("PRAGMA table_info(search_results)").fetchall()
    }
    if "record_json" not in result_columns:
        cur.execute("ALTER TABLE search_results ADD COLUMN record_json TEXT")
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

def safe_request(
    url: str,
    *,
    params: dict,
    timeout: int = 20,
    source_name: str = "web",
    method: str = "get",
):
    try:
        logger.info("[%s] Request start url=%s params=%s", source_name, url, params)
        request_kwargs = {"params": params} if method == "get" else {"data": params}
        resp = requests.request(
            method,
            url,
            headers=DEFAULT_HEADERS,
            timeout=timeout,
            **request_kwargs,
        )
        logger.info("[%s] Response status=%s final_url=%s", source_name, resp.status_code, resp.url)
        resp.raise_for_status()
        return resp, None
    except requests.RequestException as ex:
        logger.exception("[%s] Request failed: %s", source_name, ex)
        return None, str(ex)

def fetch_duckduckgo(query: str, max_results: int = 8):
    resp, err = safe_request(
        "https://lite.duckduckgo.com/lite/",
        params={"q": query, "kl": "us-en"},
        timeout=20,
        source_name="duckduckgo",
        method="post",
    )
    if err:
        return {"results": [], "error": err, "source": "duckduckgo"}

    soup = BeautifulSoup(resp.text, "html.parser")
    items = []
    seen = set()

    for a in soup.select("a.result-link"):
        title = unescape(a.get_text(" ", strip=True))
        href = a.get("href")
        if not href or not href.startswith(("http://", "https://")):
            continue
        if href in seen:
            continue

        result_row = a.find_parent("tr")
        snippet_row = result_row.find_next_sibling("tr") if result_row else None
        snippet_node = snippet_row.select_one("td.result-snippet") if snippet_row else None
        snippet = (
            unescape(snippet_node.get_text(" ", strip=True))
            if snippet_node else "DuckDuckGo result"
        )

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


def fetch_wikipedia(query: str, max_results: int = 8):
    resp, err = safe_request(
        "https://en.wikipedia.org/w/api.php",
        params={
            "action": "query",
            "list": "search",
            "srsearch": query,
            "srlimit": max_results,
            "format": "json",
            "utf8": 1,
        },
        timeout=20,
        source_name="wikipedia",
    )
    if err:
        return {"results": [], "error": err, "source": "wikipedia"}

    try:
        payload = resp.json()
    except ValueError as ex:
        logger.exception("[wikipedia] JSON decode failed: %s", ex)
        return {"results": [], "error": "Invalid JSON from Wikipedia", "source": "wikipedia"}

    items = []
    seen = set()
    for entry in payload.get("query", {}).get("search", []):
        title = (entry.get("title") or "").strip()
        if not title:
            continue

        url = "https://en.wikipedia.org/wiki/" + title.replace(" ", "_")
        if url in seen:
            continue

        snippet_html = entry.get("snippet") or ""
        snippet_text = BeautifulSoup(snippet_html, "html.parser").get_text(" ", strip=True)

        items.append(
            {
                "title": title,
                "url": url,
                "snippet": snippet_text or "Wikipedia result",
                "source": "wikipedia",
            }
        )
        seen.add(url)

        if len(items) >= max_results:
            break

    logger.info("[wikipedia] Parsed results=%s", len(items))
    return {"results": items, "error": None, "source": "wikipedia"}

PROVIDERS = {
    "duckduckgo": fetch_duckduckgo,
    "brave": fetch_brave,
    "wikipedia": fetch_wikipedia,
}

SOCIAL_MEDIA_DOMAINS = {
    "linkedin": "linkedin.com",
    "facebook": "facebook.com",
    "x": "x.com",
    "instagram": "instagram.com",
    "tiktok": "tiktok.com",
    "github": "github.com",
    "youtube": "youtube.com",
    "reddit": "reddit.com",
}

PUBLIC_RECORD_TERMS = {
    "county_records": '"county public records"',
    "assessor": '"county assessor" OR "property records"',
    "clerk_court": '"county clerk" OR "court records"',
    "recorder": '"county recorder" OR "deed records"',
    "sheriff": '"county sheriff"',
}

ENTITY_QUERY_TERMS = {
    "public": '"public profile"',
    "org": '"organization"',
    "news": '"news" OR "media"',
}


def build_search_query(query: str, filters: dict) -> str:
    base_query = query.strip()
    person_name = " ".join(
        value for value in (
            filters.get("first_name", "").strip(),
            filters.get("last_name", "").strip(),
        ) if value
    )
    entity = filters.get("entity", "")
    if entity == "public" and base_query and not person_name and not (
        base_query.startswith('"') and base_query.endswith('"')
    ):
        base_query = f'"{base_query}"'
    parts = [f'"{person_name}"'] if person_name else []
    if base_query:
        parts.append(base_query)
    if entity in ENTITY_QUERY_TERMS and entity != "public":
        parts.append(f"({ENTITY_QUERY_TERMS[entity]})")

    locations = [
        value for value in (
            filters.get("city", ""),
            filters.get("county", ""),
            filters.get("state", ""),
            filters.get("country", ""),
        ) if value
    ]
    parts.extend(locations)

    public_record_type = filters.get("public_record_type", "")
    if public_record_type in PUBLIC_RECORD_TERMS:
        parts.append(f"({PUBLIC_RECORD_TERMS[public_record_type]})")
    if filters.get("source") == "public_records":
        parts.append('(site:.gov OR site:.us) "public records"')

    social_media_site = filters.get("social_media_site", "")
    if social_media_site in SOCIAL_MEDIA_DOMAINS:
        parts.append(f"site:{SOCIAL_MEDIA_DOMAINS[social_media_site]}")
    elif filters.get("source") == "social_media":
        domains = " OR ".join(f"site:{domain}" for domain in SOCIAL_MEDIA_DOMAINS.values())
        parts.append(f"({domains})")

    return " ".join(parts)


def result_name_key(item: dict, sort_by: str):
    title = re.split(r"\s[-|:]\s", item.get("title") or "", maxsplit=1)[0]
    words = re.findall(r"[A-Za-z][A-Za-z'.-]*", title)
    if not words:
        return ("", "", (item.get("title") or "").casefold())
    first_name = words[0].casefold()
    last_name = words[-1].casefold() if len(words) > 1 else first_name
    primary, secondary = (last_name, first_name) if sort_by == "last_name" else (first_name, last_name)
    return (primary, secondary, (item.get("title") or "").casefold())


def sort_search_results(results: list, sort_by: str) -> list:
    if sort_by in {"first_name", "last_name"}:
        return sorted(results, key=lambda item: result_name_key(item, sort_by))
    return sorted(results, key=lambda item: item["score"], reverse=True)


def _record_value(value) -> str:
    if isinstance(value, dict):
        ordered_keys = ("streetAddress", "addressLocality", "addressRegion", "postalCode", "addressCountry")
        address = ", ".join(str(value[key]).strip() for key in ordered_keys if value.get(key))
        if address:
            return address
        for key in ("name", "title", "jobTitle", "description"):
            if value.get(key):
                return _record_value(value[key])
        return ""
    if isinstance(value, list):
        return ", ".join(_record_value(item) for item in value if _record_value(item))
    return re.sub(r"\s+", " ", unescape(str(value or ""))).strip()


def extract_record_from_html(html: str, item: dict) -> dict:
    soup = BeautifulSoup(html or "", "html.parser")
    fields = []
    seen_labels = set()

    def add_field(label, value):
        clean_label = re.sub(r"\s+", " ", str(label or "")).strip().rstrip(":")
        clean_value = _record_value(value)
        normalized_label = clean_label.casefold()
        if clean_label and clean_value and normalized_label not in seen_labels:
            fields.append({"label": clean_label[:80], "value": clean_value[:500]})
            seen_labels.add(normalized_label)

    structured_record = None
    for script in soup.select('script[type="application/ld+json"]'):
        try:
            payload = json.loads(script.string or script.get_text() or "")
        except (TypeError, ValueError):
            continue
        candidates = payload if isinstance(payload, list) else [payload]
        for candidate in candidates:
            if isinstance(candidate, dict) and isinstance(candidate.get("@graph"), list):
                candidates.extend(candidate["@graph"])
            if not isinstance(candidate, dict):
                continue
            record_type = candidate.get("@type")
            record_types = record_type if isinstance(record_type, list) else [record_type]
            if any(value in {"Person", "Organization", "NewsArticle", "Article", "GovernmentOrganization"} for value in record_types):
                structured_record = candidate
                break
        if structured_record:
            break

    if structured_record:
        for key, label in (
            ("jobTitle", "Occupation"),
            ("hasOccupation", "Occupation"),
            ("worksFor", "Organization"),
            ("address", "Address"),
            ("telephone", "Phone"),
            ("email", "Email"),
            ("birthDate", "Birth date"),
            ("datePublished", "Published"),
        ):
            add_field(label, structured_record.get(key))

    snippet = _record_value(item.get("snippet"))
    explicit_patterns = (
        ("Occupation", r"\b(?:occupation|profession|job title)\b\s*(?:is|:|-)?\s*([^.;|·]{2,100})"),
        ("Organization", r"\b(?:employer|works (?:at|for)|employed (?:at|by)|experience)\b\s*(?:is|:|-)?\s*([^.;|·]{2,100})"),
        ("Employment status", r"\b(?:employment|working) status\b\s*(?:is|:|-)?\s*([^.;|·]{2,80})"),
        ("Source of income", r"\b(?:source of income|income source)\b\s*(?:is|:|-)?\s*([^.;|·]{2,100})"),
        ("Relationship status (third-party)", r"\b(?:relationship|marital status)\b\s*(?:is|:|-)?\s*([^.;|·]{2,40})"),
        ("Relationship status (third-party)", r"\b(?:is|is now)\s+(married|single|divorced|widowed)\b"),
        ("Associates (third-party)", r"\b(?:associates(?:\s*&\s*neighbors)?|personal network)[^.;:]*?(?:include|:)\s*([^.;]{2,300})"),
    )
    for label, pattern in explicit_patterns:
        match = re.search(pattern, snippet, flags=re.IGNORECASE)
        if match:
            add_field(label, match.group(1))

    currency = r"\$[\d,]+(?:\.\d+)?(?:\s*(?:-|–|—|to)\s*\$?[\d,]+(?:\.\d+)?)?(?:\s*(?:thousand|million|billion|[KMB]))?"
    net_worth_match = re.search(
        rf"(?:estimated\s+)?net\s+worth\s*(?:is|of|:|-)?\s*(?:greater\s+than\s+)?({currency})|({currency})\s+(?:estimated\s+)?net\s+worth",
        snippet,
        flags=re.IGNORECASE,
    )
    if net_worth_match:
        add_field("Estimated net worth (third-party)", net_worth_match.group(1) or net_worth_match.group(2))

    income_match = re.search(
        rf"\b(?:annual\s+)?income\s*(?:is|of|:|-)?\s*({currency})|\bmakes?\s+(?:between\s+)?({currency})\s+(?:a|per)\s+year\b",
        snippet,
        flags=re.IGNORECASE,
    )
    if income_match:
        add_field("Annual income (third-party)", income_match.group(1) or income_match.group(2))

    for row in soup.select("table tr"):
        cells = row.find_all(["th", "td"], recursive=False)
        if len(cells) >= 2:
            add_field(cells[0].get_text(" ", strip=True), cells[1].get_text(" ", strip=True))
        if len(fields) >= 16:
            break

    if len(fields) < 16:
        for term in soup.select("dl dt"):
            description = term.find_next_sibling("dd")
            if description:
                add_field(term.get_text(" ", strip=True), description.get_text(" ", strip=True))
            if len(fields) >= 16:
                break

    description = ""
    if structured_record:
        description = _record_value(structured_record.get("description"))
    if not description:
        meta = soup.select_one('meta[name="description"], meta[property="og:description"]')
        description = _record_value(meta.get("content")) if meta else ""
    if not description:
        paragraphs = [node.get_text(" ", strip=True) for node in soup.select("main p, article p, body p")]
        description = " ".join(value for value in paragraphs if len(value) >= 30)[:1000]

    name = _record_value(structured_record.get("name")) if structured_record else ""
    if not name:
        name = re.split(r"\s[-|:]\s", item.get("title") or "", maxsplit=1)[0].strip()

    return {
        "name": _record_value(name or item.get("title")) or "Public record",
        "summary": _record_value(description or item.get("snippet")) or "No record summary was published.",
        "fields": fields,
        "source_name": urlparse(item.get("url") or "").netloc,
        "extraction": "page" if description or fields else "search_excerpt",
    }


def enrich_result_with_record(item: dict) -> dict:
    if item.get("source") == "local-fixture":
        return {**item, "record": extract_record_from_html("", item)}

    response, error = safe_request(
        item.get("url") or "",
        params={},
        timeout=10,
        source_name="record-extractor",
    )
    if error or response is None:
        record = extract_record_from_html("", item)
        record["extraction"] = "search_excerpt"
        return {**item, "record": record, "record_error": error}

    content_type = response.headers.get("Content-Type", "").lower()
    html = response.text if "html" in content_type or not content_type else ""
    return {**item, "record": extract_record_from_html(html, item)}


def enrich_search_results(results: list) -> list:
    if not results:
        return []
    with ThreadPoolExecutor(max_workers=min(6, len(results))) as executor:
        return list(executor.map(enrich_result_with_record, results))


def resolve_provider_names(source: str):
    normalized = (source or "all").strip().lower()

    # UI-level source scopes currently map to the same public providers.
    if normalized in {"all", "all_source_scopes", "web", "news", "public_records", "social_media"}:
        return list(PROVIDERS.keys())

    # Direct provider targeting is still supported for advanced callers.
    if normalized in PROVIDERS:
        return [normalized]

    return []


def build_provider_query_plan(source: str, query: str):
    normalized = (source or "all").strip().lower()
    provider_names = resolve_provider_names(normalized)
    if normalized not in {"all", "all_source_scopes"}:
        return [(provider_name, query, "selected_scope") for provider_name in provider_names]

    scoped_queries = [
        ("general_web", query),
        ("public_records", f'{query} (site:.gov OR site:.us) "public records"'),
        ("news", f'{query} ("news" OR "media")'),
    ]
    scoped_queries.extend(
        (f"social_{site}", f"{query} site:{domain}")
        for site, domain in SOCIAL_MEDIA_DOMAINS.items()
    )

    plan = []
    for provider_name in provider_names:
        provider_queries = scoped_queries if provider_name != "wikipedia" else scoped_queries[:1]
        plan.extend(
            (provider_name, scoped_query, scope_name)
            for scope_name, scoped_query in provider_queries
        )
    return plan

FICTIONAL_PROFILE_RESULTS = {
    "avery stonebridge": [
        {
            "title": "Avery Stonebridge - Public Portfolio",
            "url": "https://profiles.example.com/avery-stonebridge",
            "snippet": "Fictional profile card for demo search behavior.",
            "source": "local-fixture",
        },
        {
            "title": "Avery Stonebridge - Conference Speaker Bio",
            "url": "https://events.example.com/speakers/avery-stonebridge",
            "snippet": "Speaker bio and session highlights.",
            "source": "local-fixture",
        },
    ],
    "milo hartwell": [
        {
            "title": "Milo Hartwell - Public Profile",
            "url": "https://profiles.example.com/milo-hartwell",
            "snippet": "Fictional profile used for local demo scenarios.",
            "source": "local-fixture",
        },
    ],
    "nora whitlock": [
        {
            "title": "Nora Whitlock - Portfolio",
            "url": "https://profiles.example.com/nora-whitlock",
            "snippet": "Portfolio and verified public links.",
            "source": "local-fixture",
        },
    ],
    "declan rivers": [
        {
            "title": "Declan Rivers - Public Activity",
            "url": "https://profiles.example.com/declan-rivers",
            "snippet": "Activity feed for demo testing.",
            "source": "local-fixture",
        },
    ],
    "lena marlowe": [
        {
            "title": "Lena Marlowe - Public Profile",
            "url": "https://profiles.example.com/lena-marlowe",
            "snippet": "Fictional person record for prototype demos.",
            "source": "local-fixture",
        },
    ],
    "theo blackwood": [
        {
            "title": "Theo Blackwood - Community Contributions",
            "url": "https://profiles.example.com/theo-blackwood",
            "snippet": "Public contributions and profile summary.",
            "source": "local-fixture",
        },
    ],
    "iris wrenford": [
        {
            "title": "Iris Wrenford - Public Directory Entry",
            "url": "https://profiles.example.com/iris-wrenford",
            "snippet": "Directory listing for fictional test identity.",
            "source": "local-fixture",
        },
    ],
    "jasper holloway": [
        {
            "title": "Jasper Holloway - Public Portfolio",
            "url": "https://profiles.example.com/jasper-holloway",
            "snippet": "Profile page used by local fixture fallback.",
            "source": "local-fixture",
        },
    ],
    "elara finch": [
        {
            "title": "Elara Finch - Public Bio",
            "url": "https://profiles.example.com/elara-finch",
            "snippet": "Bio page for fictional demo identity.",
            "source": "local-fixture",
        },
    ],
    "callum frost": [
        {
            "title": "Callum Frost - Public Records Overview",
            "url": "https://profiles.example.com/callum-frost",
            "snippet": "Overview of sample public records.",
            "source": "local-fixture",
        },
    ],
}

def fetch_results(source: str, query: str, max_results: int):
    source = (source or "all").lower()
    aggregated = []
    errors = []
    seen_urls = set()

    provider_names = resolve_provider_names(source)
    query_plan = build_provider_query_plan(source, query)

    if not provider_names:
        warning = {
            "source": source,
            "error": "Unknown source scope. Supported values: all_source_scopes, all, web, news, duckduckgo, brave",
        }
        logger.warning("[router] %s", warning["error"])
        errors.append(warning)
        return aggregated, errors

    for provider_name, provider_query, scope_name in query_plan:
        provider_func = PROVIDERS.get(provider_name)
        if not provider_func:
            warning = {
                "source": provider_name,
                "error": f"Unknown source '{provider_name}'. Supported values: all, duckduckgo, brave",
            }
            logger.warning("[router] %s", warning["error"])
            errors.append(warning)
            continue

        provider_response = provider_func(provider_query, max_results)
        if provider_response.get("error"):
            errors.append({
                "source": provider_name,
                "scope": scope_name,
                "error": provider_response["error"],
            })

        for item in provider_response.get("results", []):
            url = item.get("url")
            if not url or url in seen_urls:
                continue
            aggregated.append(item)
            seen_urls.add(url)

    logger.info(
        "[router] Aggregated unique results=%s errors=%s provider_queries=%s",
        len(aggregated),
        len(errors),
        len(query_plan),
    )
    return aggregated, errors


def enrich_person_search_excerpts(person_name: str, results: list, max_results: int):
    if not person_name or not results:
        return results, None

    detail_query = f'"{person_name}" net worth income employment'
    detail_response = fetch_duckduckgo(detail_query, max_results)
    if detail_response.get("error"):
        return results, detail_response["error"]

    by_url = {item.get("url"): item for item in results if item.get("url")}
    for detail in detail_response.get("results", []):
        url = detail.get("url")
        if not url:
            continue
        existing = by_url.get(url)
        if existing:
            if len(detail.get("snippet") or "") > len(existing.get("snippet") or ""):
                existing["snippet"] = detail["snippet"]
            continue
        if len(results) < max_results:
            results.append(detail)
            by_url[url] = detail

    return results, None


def get_fictional_fallback_results(query: str, max_results: int):
    key = (query or "").strip().lower()
    seeded = FICTIONAL_PROFILE_RESULTS.get(key, [])
    return seeded[:max_results]


def get_generic_fallback_results(query: str, max_results: int):
    q = (query or "").strip()
    if not q:
        return []

    q_plus = quote_plus(q)
    q_underscore = quote_plus(q).replace("+", "_")
    options = [
        {
            "title": f"Search '{q}' on Wikipedia",
            "url": f"https://en.wikipedia.org/wiki/Special:Search?search={q_plus}",
            "snippet": "Reliable fallback entry when live public providers return no direct results.",
            "source": "fallback",
        },
        {
            "title": f"Search '{q}' on DuckDuckGo",
            "url": f"https://duckduckgo.com/?q={q_plus}",
            "snippet": "Direct search URL for quick manual verification.",
            "source": "fallback",
        },
        {
            "title": f"Likely profile page for '{q}' on Wikipedia",
            "url": f"https://en.wikipedia.org/wiki/{q_underscore}",
            "snippet": "Generated profile candidate link.",
            "source": "fallback",
        },
    ]
    return options[:max_results]

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


@app.route("/health")
def health():
    return jsonify({"status": "ok"})


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
    payload = request.get_json(silent=True) or {}

    def _field(name: str, default=""):
        form_val = request.form.get(name)
        if form_val is not None:
            return form_val
        return payload.get(name, default)

    query = str(_field("query", "")).strip()
    try:
        max_results = int(_field("max_results", 25) or 25)
    except ValueError:
        max_results = 25

    filters = {
        "entity": str(_field("entity", _field("entity_type", "public")) or "public"),
        "source": str(_field("source", "all") or "all").lower(),
        "max_results": max(1, min(max_results, 100)),
        "first_name": str(_field("first_name", "") or "").strip(),
        "last_name": str(_field("last_name", "") or "").strip(),
        "country": str(_field("country", "") or "").strip(),
        "state": str(_field("state", "") or "").strip(),
        "county": str(_field("county", "") or "").strip(),
        "city": str(_field("city", "") or "").strip(),
        "public_record_type": str(_field("public_record_type", "") or "").strip().lower(),
        "social_media_site": str(_field("social_media_site", "") or "").strip().lower(),
        "sort_by": str(_field("sort_by", "relevance") or "relevance").strip().lower(),
    }

    person_name = " ".join(
        value for value in (filters["first_name"], filters["last_name"]) if value
    )
    if not query and not person_name:
        return jsonify({"error": "Provide a query, first name, or last name."}), 400

    search_label = query or person_name

    effective_query = build_search_query(query, filters)
    query_terms = [
        t for t in re.findall(r"[A-Za-z0-9]+", effective_query.lower()) if len(t) > 2
    ]
    learning_weights = load_learning_weights()
    candidate_results, fetch_errors = fetch_results(
        filters["source"],
        effective_query,
        filters["max_results"],
    )

    if person_name and "duckduckgo" in resolve_provider_names(filters["source"]):
        candidate_results, detail_error = enrich_person_search_excerpts(
            person_name,
            candidate_results,
            filters["max_results"],
        )
        if detail_error:
            fetch_errors.append({"source": "duckduckgo-details", "error": detail_error})

    if not candidate_results:
        fallback_results = get_fictional_fallback_results(search_label, filters["max_results"])
        if fallback_results:
            logger.info("[search] Using local fictional fallback for query=%r", search_label)
            candidate_results = fallback_results

    scored = []
    for item in candidate_results:
        score = score_result(item, query_terms, learning_weights)
        scored.append({**item, "score": score})

    scored_sorted = sort_search_results(scored, filters["sort_by"])[:filters["max_results"]]
    scored_sorted = enrich_search_results(scored_sorted)
    result_count = len(scored_sorted)

    if result_count == 0:
        logger.warning(
            "[search] No results query=%r source=%s errors=%s",
            search_label,
            filters["source"],
            fetch_errors,
        )

    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO searches(query, filters, result_count) VALUES(?, ?, ?)",
        (search_label, json.dumps(filters), result_count),
    )
    search_id = cur.lastrowid

    for item in scored_sorted:
        cur.execute(
            """
            INSERT INTO search_results(
                search_id, title, url, snippet, source, score, record_json
            )
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """,
            (
                search_id,
                item["title"],
                item["url"],
                item["snippet"],
                item["source"],
                item["score"],
                json.dumps(item.get("record") or {}),
            ),
        )
        item["record_path"] = f"/records/{cur.lastrowid}"

    conn.commit()
    conn.close()

    update_learning_weights(query_terms, [item["score"] for item in scored_sorted])

    response_results = [
        {key: value for key, value in item.items() if key != "url"}
        for item in scored_sorted
    ]
    response = {
        "query": search_label,
        "effective_query": effective_query,
        "filters": filters,
        "results": response_results,
        "search_id": search_id,
        "learning_terms": load_learning_weights(),
        "fetch_errors": fetch_errors,
        "diagnostics": {
            "provider_count": len(resolve_provider_names(filters["source"])),
            "provider_query_count": len(build_provider_query_plan(filters["source"], effective_query)),
            "searched_providers": resolve_provider_names(filters["source"]),
            "searched_scopes": sorted({
                scope_name
                for _, _, scope_name in build_provider_query_plan(filters["source"], effective_query)
            }),
            "requested_source": filters["source"],
            "result_count": result_count,
        },
    }
    return jsonify(response)


@app.route("/records/<int:result_id>")
def record_detail(result_id: int):
    conn = get_conn()
    row = conn.execute(
        "SELECT id, title, snippet, source, record_json FROM search_results WHERE id = ?",
        (result_id,),
    ).fetchone()
    conn.close()
    if row is None:
        abort(404)

    try:
        record = json.loads(row["record_json"] or "{}")
    except (TypeError, ValueError):
        record = {}
    record.setdefault("name", row["title"] or "Public record")
    record.setdefault("summary", row["snippet"] or "No record summary was published.")
    record.setdefault("fields", [])
    record.setdefault("source_name", row["source"] or "Public source")
    return render_template("record_detail.html", record=record, result_id=result_id)


@app.route("/records/<int:result_id>/source")
def record_source(result_id: int):
    conn = get_conn()
    row = conn.execute(
        "SELECT url FROM search_results WHERE id = ?",
        (result_id,),
    ).fetchone()
    conn.close()
    if row is None:
        abort(404)

    source_url = row["url"] or ""
    if urlparse(source_url).scheme not in {"http", "https"}:
        abort(404)
    return redirect(source_url)

@app.route("/history")
def history():
    conn = get_conn()
    searches = conn.execute("SELECT * FROM searches ORDER BY id DESC LIMIT 10").fetchall()
    conn.close()
    return jsonify([dict(row) for row in searches])

if __name__ == "__main__":
    def _is_port_available(port: int) -> bool:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            return sock.connect_ex(("127.0.0.1", port)) != 0

    def _pick_available_port(preferred_port: int, max_offset: int = 50) -> int:
        for candidate in range(preferred_port, preferred_port + max_offset + 1):
            if _is_port_available(candidate):
                return candidate
        return preferred_port

    logger.info("Starting Search Intelligence app")
    # Default to non-debug for unattended runs (for example, startup tasks).
    debug_mode = os.getenv("APP_DEBUG", "0").strip().lower() in {"1", "true", "yes", "on"}
    preferred_port = int(os.getenv("PORT", 5000))
    auto_port_enabled = os.getenv("APP_AUTO_PORT", "1").strip().lower() in {"1", "true", "yes", "on"}
    # In Azure/App Service, keep the platform-provided port strict.
    if os.getenv("WEBSITE_INSTANCE_ID"):
        auto_port_enabled = False

    selected_port = _pick_available_port(preferred_port) if auto_port_enabled else preferred_port
    if selected_port != preferred_port:
        logger.warning("Port %s is busy. Using next available port %s.", preferred_port, selected_port)

    app.run(debug=debug_mode, host="0.0.0.0", port=selected_port)
