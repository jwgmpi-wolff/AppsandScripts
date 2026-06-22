"""Scrape publicly accessible webcam directory sites.

Targets:
  - webcamtaxi.com  – large public webcam directory
  - EarthCam        – public listing pages
  - SkylineWebcams  – public listing pages
  - Roundshot       – panoramic public cams
"""
from __future__ import annotations

import asyncio
import logging
import re
from urllib.parse import urljoin

import httpx
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Webcam Taxi
# ---------------------------------------------------------------------------
_WCAMTAXI_URLS = [
    "https://www.webcamtaxi.com/en/usa/",
    "https://www.webcamtaxi.com/en/canada/",
    "https://www.webcamtaxi.com/en/europe/",
]


async def _scrape_webcamtaxi_page(client: httpx.AsyncClient, url: str) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(url, timeout=15)
        if resp.status_code != 200:
            return cameras
        soup = BeautifulSoup(resp.text, "html.parser")
        region = url.rstrip("/").split("/")[-1].capitalize()

        for article in soup.select(".webcam-item, .cam-item, article.post, .taxonomy-cam"):
            a_tag = article.find("a", href=True)
            img   = article.find("img")
            title_el = article.find(["h2", "h3", "h4", ".entry-title"])

            if not a_tag:
                continue

            href  = a_tag["href"]
            title = (title_el.get_text(strip=True) if title_el else a_tag.get_text(strip=True)) or "Webcam"
            img_src = ""
            if img:
                img_src = img.get("data-src") or img.get("src") or ""
            if not img_src and "webcamtaxi" in href:
                # derive thumbnail from page path
                img_src = href

            cameras.append({
                "title":       title,
                "url":         urljoin("https://www.webcamtaxi.com", a_tag["href"]),
                "image_url":   img_src if img_src.startswith("http") else "",
                "feed_type":   "embed",
                "location":    region,
                "country":     region,
                "state":       "",
                "city":        "",
                "latitude":    None,
                "longitude":   None,
                "site_name":   "WebcamTaxi",
                "description": f"Public webcam via WebcamTaxi – {region}",
                "tags":        f"webcamtaxi,public,{region.lower()}",
                "source":      "webcamtaxi.com",
                "keywords":    f"webcam {title} {region} public",
            })
    except Exception as exc:
        logger.debug("WebcamTaxi scrape %s failed: %s", url, exc)
    return cameras


# ---------------------------------------------------------------------------
# EarthCam public listing
# ---------------------------------------------------------------------------
_EARTHCAM_PAGES = [
    "https://www.earthcam.com/usa/",
    "https://www.earthcam.com/usa/newyork/",
    "https://www.earthcam.com/usa/california/",
    "https://www.earthcam.com/usa/florida/",
    "https://www.earthcam.com/usa/illinois/",
    "https://www.earthcam.com/usa/texas/",
    "https://www.earthcam.com/world/",
]

_EC_IMG_RE = re.compile(r"https://static\.earthcam\.com/tools/wl/[^\"'\s]+latest\.jpg", re.I)
_EC_THUMB_RE = re.compile(r"https://(?:static|www)\.earthcam\.com/[^\"'\s]+(?:thumb|small|latest)\.jpg", re.I)


async def _scrape_earthcam_page(client: httpx.AsyncClient, url: str) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(url, timeout=15)
        if resp.status_code != 200:
            return cameras
        soup = BeautifulSoup(resp.text, "html.parser")
        region = url.rstrip("/").split("/")[-1].replace("-", " ").capitalize() or "USA"

        for cam_div in soup.select(".cam-item, .camera-item, .cam_item, .cam, article"):
            a_tag = cam_div.find("a", href=True)
            img   = cam_div.find("img")
            title_el = cam_div.find(["h2", "h3", "h4", ".cam-title"])

            if not a_tag or "earthcam.com" not in a_tag.get("href", a_tag.get_text()):
                pass
            href = urljoin("https://www.earthcam.com", a_tag["href"]) if a_tag else url
            title = (title_el.get_text(strip=True) if title_el else
                     (img.get("alt") if img else "")) or f"EarthCam {region}"
            img_src = ""
            if img:
                img_src = img.get("data-src") or img.get("src") or ""
            # Look for known EarthCam image patterns in raw HTML
            wl_matches = _EC_IMG_RE.findall(resp.text)
            if wl_matches:
                img_src = img_src or wl_matches[0]

            if not img_src or "earthcam.com" not in img_src:
                continue

            cameras.append({
                "title":       title or "EarthCam Webcam",
                "url":         href,
                "image_url":   img_src,
                "feed_type":   "image",
                "location":    region,
                "country":     "USA",
                "state":       "",
                "city":        region,
                "latitude":    None,
                "longitude":   None,
                "site_name":   "EarthCam",
                "description": f"EarthCam public webcam – {title or region}",
                "tags":        f"earthcam,public,tourism,{region.lower()}",
                "source":      "earthcam.com",
                "keywords":    f"earthcam {title} {region} webcam tourism public",
            })
    except Exception as exc:
        logger.debug("EarthCam scrape %s failed: %s", url, exc)
    return cameras


# ---------------------------------------------------------------------------
# SkylineWebcams listing
# ---------------------------------------------------------------------------
_SKY_PAGES = [
    "https://www.skylinewebcams.com/en/webcam/usa.html",
    "https://www.skylinewebcams.com/en/webcam/canada.html",
    "https://www.skylinewebcams.com/en/webcam/italia.html",
    "https://www.skylinewebcams.com/en/webcam/espana.html",
]
_SKY_IMG_RE = re.compile(r"https://embed\.skylinewebcams\.com/img/(\d+)\.jpg", re.I)


async def _scrape_skyline_page(client: httpx.AsyncClient, url: str) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(url, timeout=15)
        if resp.status_code != 200:
            return cameras
        soup = BeautifulSoup(resp.text, "html.parser")
        country = url.rstrip("/").split("/")[-1].replace(".html", "").capitalize()

        for a in soup.select("a[href*='/webcam/']"):
            href = urljoin("https://www.skylinewebcams.com", a["href"])
            img = a.find("img") or a.parent.find("img") if a.parent else None
            img_src = ""
            if img:
                img_src = img.get("data-original") or img.get("data-src") or img.get("src") or ""
            # Check for skyline embed image pattern
            m = _SKY_IMG_RE.search(resp.text)
            title_el = a.find(["span", "div", "p"])
            title = (title_el.get_text(strip=True) if title_el else a.get_text(strip=True)) or f"SkylineWebcams {country}"

            if img_src and "skylinewebcams.com" in img_src:
                cameras.append({
                    "title":       title,
                    "url":         href,
                    "image_url":   img_src,
                    "feed_type":   "image",
                    "location":    country,
                    "country":     country,
                    "state":       "",
                    "city":        country,
                    "latitude":    None,
                    "longitude":   None,
                    "site_name":   "SkylineWebcams",
                    "description": f"SkylineWebcams public cam – {title}",
                    "tags":        f"skylinewebcams,public,{country.lower()},tourism",
                    "source":      "skylinewebcams.com",
                    "keywords":    f"skylinewebcams {title} {country} public webcam",
                })
    except Exception as exc:
        logger.debug("SkylineWebcams scrape %s failed: %s", url, exc)
    return cameras


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

async def fetch_cameras(client: httpx.AsyncClient) -> list[dict]:
    tasks = (
        [_scrape_webcamtaxi_page(client, u) for u in _WCAMTAXI_URLS]
        + [_scrape_earthcam_page(client, u) for u in _EARTHCAM_PAGES]
        + [_scrape_skyline_page(client, u) for u in _SKY_PAGES]
    )
    results = await asyncio.gather(*tasks, return_exceptions=True)
    cameras: list[dict] = []
    for r in results:
        if isinstance(r, list):
            cameras.extend(r)
    logger.info("Aggregators: collected %d cameras", len(cameras))
    return cameras
