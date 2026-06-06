import asyncio
import os
import re
import sqlite3
from datetime import datetime
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from .scanner import find_live_links, test_url

ROOT = os.path.dirname(os.path.dirname(__file__))
SUCCESS_DB = os.path.join(ROOT, "successes.db")
FAIL_DB = os.path.join(ROOT, "failures.db")


def init_db():
    for path, create_sql in [
        (
            SUCCESS_DB,
            """
        CREATE TABLE IF NOT EXISTS saved_links (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            url TEXT UNIQUE,
            title TEXT,
            meta TEXT,
            added_at TEXT
        )
        """,
        ),
        (
            FAIL_DB,
            """
        CREATE TABLE IF NOT EXISTS failed_links (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            url TEXT,
            status INTEGER,
            reason TEXT,
            tried_at TEXT
        )
        """,
        ),
    ]:
        conn = sqlite3.connect(path)
        conn.executescript(create_sql)
        conn.commit()
        conn.close()


app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class ScanRequest(BaseModel):
    seeds: List[str]
    pattern: Optional[str] = None
    max_concurrency: Optional[int] = 10


class TestRequest(BaseModel):
    url: str


class SaveRequest(BaseModel):
    url: str
    title: Optional[str] = None
    meta: Optional[str] = None


@app.on_event("startup")
async def startup_event():
    init_db()
    app.state.recent_results = []


@app.post("/scan")
async def scan(req: ScanRequest):
    results = await find_live_links(req.seeds, pattern=req.pattern, concurrency=req.max_concurrency)
    # store in-memory recent results
    app.state.recent_results = results
    return {"count": len(results), "results": results}


@app.get("/results/recent")
async def recent_results():
    return app.state.recent_results


@app.post("/test")
async def test(req: TestRequest):
    ok, status, reason = await test_url(req.url)
    tried_at = datetime.utcnow().isoformat()
    if ok:
        conn = sqlite3.connect(SUCCESS_DB)
        conn.execute(
            "INSERT OR IGNORE INTO saved_links (url, title, meta, added_at) VALUES (?, ?, ?, ?)",
            (req.url, None, None, tried_at),
        )
        conn.commit()
        conn.close()
    else:
        conn = sqlite3.connect(FAIL_DB)
        conn.execute(
            "INSERT INTO failed_links (url, status, reason, tried_at) VALUES (?, ?, ?, ?)",
            (req.url, status, reason, tried_at),
        )
        conn.commit()
        conn.close()
    return {"ok": ok, "status": status, "reason": reason}


@app.post("/save")
async def save(req: SaveRequest):
    conn = sqlite3.connect(SUCCESS_DB)
    conn.execute(
        "INSERT OR IGNORE INTO saved_links (url, title, meta, added_at) VALUES (?, ?, ?, ?)",
        (req.url, req.title, req.meta, datetime.utcnow().isoformat()),
    )
    conn.commit()
    conn.close()
    return {"saved": True}


@app.get("/saved")
async def saved(query: Optional[str] = None):
    conn = sqlite3.connect(SUCCESS_DB)
    cur = conn.cursor()
    if query:
        q = f"%{query}%"
        cur.execute("SELECT id, url, title, meta, added_at FROM saved_links WHERE url LIKE ? OR title LIKE ? OR meta LIKE ? ORDER BY added_at DESC", (q, q, q))
    else:
        cur.execute("SELECT id, url, title, meta, added_at FROM saved_links ORDER BY added_at DESC")
    rows = cur.fetchall()
    conn.close()
    keys = [dict(id=r[0], url=r[1], title=r[2], meta=r[3], added_at=r[4]) for r in rows]
    return keys


@app.post("/clear_recent")
async def clear_recent():
    app.state.recent_results = []
    return {"cleared": True}


# serve static UI
app.mount("/static", StaticFiles(directory=os.path.join(ROOT, "static")), name="static")

@app.get("/")
async def index():
    index_path = os.path.join(ROOT, "static", "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path, media_type="text/html")
    raise HTTPException(status_code=404, detail="Index not found")
