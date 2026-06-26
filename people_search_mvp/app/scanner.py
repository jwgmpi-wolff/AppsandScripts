import asyncio
import re
from typing import List, Optional, Tuple

import httpx
from bs4 import BeautifulSoup


LIVE_PATTERNS = [
    re.compile(r"\.m3u8", re.I),
    re.compile(r"/(live|stream|embed)/", re.I),
    re.compile(r"twitch\.tv", re.I),
    re.compile(r"youtube\.com/watch", re.I),
    re.compile(r"facebook\.com/.*/live", re.I),
]


async def fetch_html(client: httpx.AsyncClient, url: str, timeout: int = 10) -> Tuple[str, int]:
    try:
        r = await client.get(url, timeout=timeout, follow_redirects=True)
        return r.text, r.status_code
    except Exception as e:
        return "", 0


def extract_links(html: str, base: Optional[str] = None) -> List[str]:
    out = []
    soup = BeautifulSoup(html, "html.parser")
    for tag in soup.find_all(["a", "iframe", "source", "video"]):
        for attr in ("href", "src", "data-src", "data"):
            val = tag.get(attr)
            if val:
                out.append(val)
    # also search raw for .m3u8 and rtsp
    out += re.findall(r"https?://[\w\-./?=&%]+\.m3u8", html)
    return list(dict.fromkeys(out))


def matches_live(url: str, pattern: Optional[str] = None) -> bool:
    if pattern:
        # allow simple wildcard * => .* matching
        regex = re.escape(pattern).replace(r"\*", ".*")
        if re.search(regex, url, re.I):
            return True
    for p in LIVE_PATTERNS:
        if p.search(url):
            return True
    # also check 'live' in path
    if re.search(r"\blive\b", url, re.I):
        return True
    return False


async def find_live_links(seeds: List[str], pattern: Optional[str] = None, concurrency: int = 10) -> List[dict]:
    results = []
    seen = set()
    sem = asyncio.Semaphore(concurrency)

    async with httpx.AsyncClient(headers={"User-Agent": "live-finder/0.1"}) as client:

        async def work(url: str):
            async with sem:
                html, status = await fetch_html(client, url)
                if not html:
                    return
                links = extract_links(html, base=url)
                for l in links:
                    if l.startswith("//"):
                        l = "https:" + l
                    if l.startswith("/"):
                        # naive absolute
                        l = url.rstrip("/") + l
                    if l in seen:
                        continue
                    if matches_live(l, pattern):
                        seen.add(l)
                        results.append({"url": l, "source": url})

        tasks = [asyncio.create_task(work(s)) for s in seeds]
        await asyncio.gather(*tasks)

    return results


async def test_url(url: str, timeout: int = 10) -> Tuple[bool, int, str]:
    try:
        async with httpx.AsyncClient(headers={"User-Agent": "live-finder/0.1"}) as client:
            r = await client.head(url, timeout=timeout, follow_redirects=True)
            status = r.status_code
            ct = r.headers.get("content-type", "")
            if status == 200 and ("video" in ct or ".m3u8" in url or "application/vnd.apple.mpegurl" in ct):
                return True, status, ct
            # fallback to GET for pages
            r2 = await client.get(url, timeout=timeout, follow_redirects=True)
            status2 = r2.status_code
            html = r2.text
            if ".m3u8" in html or "<video" in html or "iframe" in html:
                return True, status2, "page"
            return False, status2, "no-evidence"
    except Exception as e:
        return False, 0, str(e)
