"""Browser-based camera crawler using Microsoft Playwright.

Handles sites that gate public camera content behind:
  - GDPR / cookie-consent banners     ("Accept all", "I agree", "OK")
  - Terms-of-service click-through screens
  - Cookie walls (set a session cookie, then redirect to content)
  - JavaScript-rendered camera listings (React / Vue / Angular apps)

Playwright is an *optional* dependency.  If it is not installed this
module returns empty lists silently so the rest of the crawler is
unaffected.  Install it with:

    pip install playwright
    playwright install chromium --with-deps

Targeted sites (publicly accessible – auth bypassed only via consent)
-----------------------------------------------------------------------
- 511NY.org         – New York State DOT cameras (JS map, consent gate)
- SkylineWebcams    – Extra pages that require GDPR acceptance
- EarthCam          – Cookie-consent gated camera listing pages
- Windy.com webcams – GDPR consent before webcam map renders
- OpenRailCam.com   – Cookie consent before live rail cameras
- MetroCams.net     – UK traffic cameras behind GDPR banner
- SeattleTraffic    – WSDOT JS viewer page
"""
from __future__ import annotations

import asyncio
import logging
import re
from typing import Any
from urllib.parse import urljoin

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Availability guard
# ---------------------------------------------------------------------------
try:
    from playwright.async_api import async_playwright, Page, Browser
    _PLAYWRIGHT_OK = True
except ImportError:
    _PLAYWRIGHT_OK = False
    logger.info(
        "Playwright not installed – browser-based crawler disabled. "
        "Run: pip install playwright && playwright install chromium --with-deps"
    )


# ---------------------------------------------------------------------------
# Common consent / cookie-banner selectors and text fragments
# Ordered from most-specific to least-specific to avoid false clicks.
# ---------------------------------------------------------------------------
_CONSENT_SELECTORS = [
    # OneTrust (very common on .com / .gov sites)
    "#onetrust-accept-btn-handler",
    ".onetrust-accept-btn-handler",
    # Cookiebot
    "#CybotCookiebotDialogBodyButtonAccept",
    # Generic IDs
    "#accept-cookies", "#cookie-accept", "#gdpr-accept",
    "#accept-all", "#acceptAll", "#accept_all",
    "#cookies-accept", "#btn-accept-cookies",
    # Generic classes
    ".accept-cookies", ".cookie-accept-all", ".consent-accept",
    ".gdpr-accept", ".cookie-btn-accept",
    # Aria labels
    "[aria-label='Accept cookies']",
    "[aria-label='Accept all cookies']",
    "[data-action='accept']",
    # Generic buttons / links whose text matches common accept phrases
    "button:has-text('Accept all')",
    "button:has-text('Accept All')",
    "button:has-text('Accept cookies')",
    "button:has-text('I accept')",
    "button:has-text('I Accept')",
    "button:has-text('I Agree')",
    "button:has-text('Agree')",
    "button:has-text('Allow all')",
    "button:has-text('Allow All')",
    "button:has-text('OK')",
    "button:has-text('Got it')",
    "button:has-text('Continue')",
    "a:has-text('Accept all')",
    "a:has-text('I Agree')",
    "a:has-text('I Accept')",
]

# Selectors for camera image elements we want to extract
_IMG_SELECTORS = [
    "img[src*='cam']",
    "img[src*='camera']",
    "img[src*='cctv']",
    "img[src*='traffic']",
    "img[src*='snapshot']",
    "img[src*='latest']",
    "img[src*='.jpg']",
    "img[src*='.jpeg']",
    "img[src*='.png']",
    "img[src*='.mjpeg']",
]

_MEDIA_RE = re.compile(
    r'https?://[^\s"\'<>]+?'
    r'(?:cam(?:era)?s?|cctv|snapshot|latest|traffic|webcam|image)'
    r'[^\s"\'<>]*?\.(?:jpg|jpeg|png|gif|webp|mjpg|mjpeg|m3u8)',
    re.I,
)

_STREAM_RE = re.compile(
    r'https?://[^\s"\'<>]+\.(?:m3u8|mjpeg|mjpg|mp4|webm|ts)(?:[?#][^\s"\'<>]*)?',
    re.I,
)


async def _dismiss_consent(page: "Page") -> bool:
    """Try every known consent selector; return True if something was clicked."""
    for sel in _CONSENT_SELECTORS:
        try:
            el = page.locator(sel).first
            if await el.is_visible(timeout=1_500):
                await el.click(timeout=3_000)
                await page.wait_for_timeout(800)
                logger.debug("Consent dismissed via: %s", sel)
                return True
        except Exception:
            continue
    return False


async def _extract_media_urls(page: "Page") -> list[str]:
    """Pull direct-media URLs from a rendered page via DOM + source scan."""
    urls: set[str] = set()

    # 1. src attributes of <img> tags
    for sel in _IMG_SELECTORS:
        try:
            elements = await page.query_selector_all(sel)
            for el in elements:
                src = await el.get_attribute("src") or await el.get_attribute("data-src") or ""
                if src and src.startswith("http"):
                    urls.add(src)
        except Exception:
            pass

    # 2. Regex scan of full page source (catches JS-embedded URLs)
    try:
        html = await page.content()
        for m in _MEDIA_RE.findall(html):
            urls.add(m)
        for m in _STREAM_RE.findall(html):
            urls.add(m)
    except Exception:
        pass

    return list(urls)


def _url_to_cam(url: str, page_url: str, site_name: str,
                state: str = "", tags: str = "", source: str = "") -> dict:
    """Build a minimal camera dict from a discovered media URL."""
    slug = re.sub(r'[^a-z0-9]+', ' ', url.lower()).strip()
    return {
        "title":       f"{site_name}: {url.split('/')[-1][:60]}",
        "url":         page_url,
        "image_url":   url,
        "feed_type":   "mjpeg" if re.search(r'\.mjpe?g', url, re.I) else
                       "hls"   if url.endswith(".m3u8") else "image",
        "location":    site_name,
        "country":     "USA" if state else "",
        "state":       state,
        "city":        "",
        "latitude":    None,
        "longitude":   None,
        "site_name":   site_name,
        "description": f"Public camera discovered via {site_name}.",
        "tags":        tags or f"public,webcam,{site_name.lower().replace(' ', ',')}",
        "source":      source or re.sub(r'https?://(www\.)?', '', page_url).split('/')[0],
        "keywords":    f"public webcam camera {site_name} {slug[:80]}",
    }


# ---------------------------------------------------------------------------
# Per-site crawl routines
# ---------------------------------------------------------------------------

_TARGET_SITES = [
    # (page_url, state, site_name, wait_for_selector)
    ("https://511ny.org/cameras", "NY", "511NY",
     ".camera-item, img[src*='camera'], img[src*='cam']"),
    ("https://www.skylinewebcams.com/en/webcam/usa/california.html",
     "CA", "SkylineWebcams CA",
     "img[src*='skylinewebcams'], a[href*='/webcam/']"),
    ("https://www.skylinewebcams.com/en/webcam/usa/new-york.html",
     "NY", "SkylineWebcams NY",
     "img[src*='skylinewebcams'], a[href*='/webcam/']"),
    ("https://openrailcam.com/",
     "", "OpenRailCam",
     "img[src*='cam'], video"),
    ("https://www.earthcam.com/usa/newyork/timessquare/",
     "NY", "EarthCam Times Square",
     "img[src*='earthcam']"),
]


async def _crawl_site(browser: "Browser", page_url: str, state: str,
                      site_name: str, wait_sel: str) -> list[dict]:
    cameras: list[dict] = []
    page = None
    try:
        page = await browser.new_page(
            user_agent=(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/124.0 Safari/537.36"
            )
        )
        await page.set_extra_http_headers({"Accept-Language": "en-US,en;q=0.9"})
        await page.goto(page_url, wait_until="domcontentloaded", timeout=25_000)

        # Dismiss any consent / cookie banner
        dismissed = await _dismiss_consent(page)
        if dismissed:
            # Give the page a moment to re-render after accepting consent
            await page.wait_for_timeout(1_500)

        # Wait for meaningful content
        try:
            await page.wait_for_selector(wait_sel, timeout=8_000)
        except Exception:
            pass  # Continue even if selector never appears

        media_urls = await _extract_media_urls(page)
        for url in media_urls:
            cameras.append(_url_to_cam(url, page_url, site_name, state))

        logger.info("Browser crawl %s: %d media URLs", site_name, len(cameras))
    except Exception as exc:
        logger.debug("Browser crawl %s failed: %s", site_name, exc)
    finally:
        if page:
            try:
                await page.close()
            except Exception:
                pass
    return cameras


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

async def fetch_cameras_browser() -> list[dict]:
    """Crawl consent-gated camera sites using a headless Chromium browser.

    Returns an empty list if Playwright is not installed or all sites fail.
    """
    if not _PLAYWRIGHT_OK:
        return []

    cameras: list[dict] = []
    try:
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(
                headless=True,
                args=["--no-sandbox", "--disable-dev-shm-usage",
                      "--disable-blink-features=AutomationControlled"],
            )
            tasks = [
                _crawl_site(browser, url, state, name, sel)
                for url, state, name, sel in _TARGET_SITES
            ]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            await browser.close()

        for r in results:
            if isinstance(r, list):
                cameras.extend(r)

    except Exception as exc:
        logger.warning("Browser crawler failed: %s", exc)

    logger.info("Browser crawler total: %d cameras", len(cameras))
    return cameras
