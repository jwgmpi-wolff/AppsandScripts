"""
Web search-based camera discovery.

Uses Bing Web Search API to find live camera feeds, validates candidates,
and extracts stream URLs from discovered pages.
"""
import asyncio
import httpx
import logging
from urllib.parse import urljoin, urlparse
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)

# Search queries optimized for camera discovery
SEARCH_QUERIES = [
    "live webcam stream",
    "live camera feed online",
    "public live streaming camera",
    "live traffic camera",
    "live weather camera",
    "live city camera feed",
    "live scenic view camera",
    "public surveillance camera live",
    "live streaming webcam network",
    "live ip camera broadcast"
]

# Known camera site patterns to prioritize
CAMERA_DOMAINS = {
    "earthcam.com": True,
    "explore.org": True,
    "insecam.org": True,
    "skylinewebcams.com": True,
    "webcams.org": True,
    "cctv.com": True,
    "youtube.com": True,
    "twitch.tv": True,
    "livestream.com": True,
    "ustream.tv": True,
}

async def _fetch_search_results(query: str, client: httpx.AsyncClient, bing_key: str) -> list[str]:
    """
    Fetch search results from Bing Web Search API.
    Returns list of URLs from search results.
    """
    urls = []
    
    try:
        resp = await client.get(
            "https://api.bing.microsoft.com/v7.0/search",
            headers={"Ocp-Apim-Subscription-Key": bing_key},
            params={"q": query, "count": 50},
            timeout=10.0
        )
        if resp.status_code == 200:
            data = resp.json()
            for result in data.get("webPages", {}).get("value", []):
                url = result.get("url")
                if url:
                    urls.append(url)
        else:
            logger.warning(f"Bing API error: {resp.status_code}")
    
    except Exception as e:
        logger.warning(f"Search error for '{query}': {e}")
    
    return urls


async def _validate_camera_url(url: str, client: httpx.AsyncClient) -> dict | None:
    """
    Validate if a URL is likely a camera feed or camera page.
    Returns dict with camera info if valid, None otherwise.
    """
    if not url:
        return None
    
    try:
        # Fetch page with HEAD first for quick check
        head = await client.head(url, timeout=5.0, follow_redirects=True)
        if head.status_code != 200:
            return None
        
        content_type = head.headers.get("content-type", "").lower()
        
        # Check if it's a direct image/video stream
        if "image" in content_type or "video" in content_type:
            return {
                "url": url,
                "image_url": url,
                "stream_url": url,
                "title": f"Stream from {urlparse(url).netloc}",
                "feed_type": _classify_stream_type(url),
                "site_name": urlparse(url).netloc,
                "source": "web_search"
            }
        
        # If HTML page, fetch and parse for embedded streams/camera info
        if "text/html" in content_type:
            resp = await client.get(url, timeout=10.0, follow_redirects=True)
            if resp.status_code != 200:
                return None
            
            soup = BeautifulSoup(resp.text, "html.parser")
            
            # Look for meta tags with image/video
            og_image = soup.find("meta", property="og:image")
            og_video = soup.find("meta", property="og:video")
            
            title = soup.title.string if soup.title else f"Webcam from {urlparse(url).netloc}"
            
            # Extract image/video from og tags
            if og_image and og_image.get("content"):
                stream_url = og_image.get("content")
                # Make absolute URL
                stream_url = urljoin(url, stream_url)
                return {
                    "url": url,
                    "image_url": stream_url,
                    "stream_url": stream_url,
                    "title": title,
                    "feed_type": _classify_stream_type(stream_url),
                    "site_name": urlparse(url).netloc,
                    "source": "web_search"
                }
            
            if og_video and og_video.get("content"):
                stream_url = og_video.get("content")
                stream_url = urljoin(url, stream_url)
                return {
                    "url": url,
                    "image_url": stream_url,
                    "stream_url": stream_url,
                    "title": title,
                    "feed_type": _classify_stream_type(stream_url),
                    "site_name": urlparse(url).netloc,
                    "source": "web_search"
                }
            
            # Look for img/video elements in page
            img_tags = soup.find_all("img", limit=10)
            for img in img_tags:
                img_src = img.get("src")
                if img_src:
                    # Check if it looks like a camera image (has timestamp, live, etc)
                    if any(kw in img_src.lower() for kw in ["camera", "stream", "live", "webcam"]):
                        img_url = urljoin(url, img_src)
                        return {
                            "url": url,
                            "image_url": img_url,
                            "stream_url": img_url,
                            "title": title,
                            "feed_type": _classify_stream_type(img_url),
                            "site_name": urlparse(url).netloc,
                            "source": "web_search"
                        }
    
    except Exception as e:
        logger.debug(f"Validation error for {url}: {e}")
    
    return None


def _classify_stream_type(url: str) -> str:
    """Classify stream type from URL pattern."""
    url_lower = url.lower()
    
    if ".m3u8" in url_lower or ".m3u" in url_lower:
        return "hls"
    elif ".mp4" in url_lower:
        return "mp4"
    elif ".webm" in url_lower:
        return "webm"
    elif ".mjpeg" in url_lower or "/mjpeg" in url_lower:
        return "mjpeg"
    elif ".jpg" in url_lower or ".jpeg" in url_lower or ".png" in url_lower:
        return "image"
    elif "youtube.com" in url_lower or "youtu.be" in url_lower:
        return "youtube"
    elif "twitch.tv" in url_lower:
        return "twitch"
    else:
        return "image"  # default


async def fetch_cameras(client: httpx.AsyncClient) -> list[dict]:
    """
    Fetch camera feeds from web search results.
    
    Strategy:
    1. Search for camera-related queries
    2. Validate each result as a camera page/feed
    3. Extract stream URLs and metadata
    4. Return deduplicated camera list
    
    Note: Requires BING_SEARCH_API_KEY environment variable to be set.
    Without it, returns empty list.
    """
    import os
    
    bing_key = os.getenv("BING_SEARCH_API_KEY")
    if not bing_key:
        logger.info("Web search disabled: BING_SEARCH_API_KEY not set")
        return []
    
    logger.info("Starting web search camera discovery...")
    
    cameras = []
    seen_urls = set()
    
    # Try multiple search queries
    for query in SEARCH_QUERIES[:3]:  # Limit to 3 queries to avoid rate limiting
        logger.info(f"  Searching: {query}")
        
        search_urls = await _fetch_search_results(query, client, bing_key)
        logger.debug(f"  Found {len(search_urls)} search results")
        
        # Validate each search result
        for url in search_urls:
            if url in seen_urls:
                continue
            
            seen_urls.add(url)
            
            try:
                cam_info = await _validate_camera_url(url, client)
                if cam_info:
                    cameras.append(cam_info)
                    logger.debug(f"    ✓ Valid camera: {cam_info['title']}")
            except Exception as e:
                logger.debug(f"    ✗ Error validating {url}: {e}")
            
            # Rate limiting: small delay between requests
            await asyncio.sleep(0.3)
    
    logger.info(f"Web search discovery complete: {len(cameras)} cameras found")
    return cameras
