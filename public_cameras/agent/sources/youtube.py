"""
YouTube live camera discovery using yt-dlp.

Searches YouTube for live camera feeds, extracts stream metadata,
and returns camera entries suitable for the crawler.
"""
import asyncio
import logging
import json
import subprocess
from typing import Optional

logger = logging.getLogger(__name__)

# YouTube search queries that find live camera streams
YOUTUBE_SEARCH_QUERIES = [
    "live webcam",
    "live camera stream",
    "live traffic camera",
    "live city camera",
    "live scenic view",
    "live weather camera",
    "live surveillance feed",
    "live nature camera",
    "live animal camera",
    "leavenworth live cam",
    "live ski camera",
    "live beach camera",
    "live mountain camera",
    "live downtown camera",
    "live park camera",
]


def _run_yt_dlp(command: list) -> Optional[dict]:
    """Run yt-dlp command and return JSON output."""
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=30.0
        )
        if result.returncode == 0 and result.stdout:
            return json.loads(result.stdout)
    except (subprocess.TimeoutExpired, json.JSONDecodeError, FileNotFoundError) as e:
        logger.debug(f"yt-dlp error: {e}")
    
    return None


def _extract_youtube_url_from_search(channel_id: str) -> str:
    """Build YouTube URL from channel ID."""
    if channel_id.startswith("UC"):  # Channel ID
        return f"https://www.youtube.com/channel/{channel_id}/live"
    elif channel_id.startswith("@"):  # Handle
        return f"https://www.youtube.com/{channel_id}/live"
    else:
        return f"https://www.youtube.com/watch?v={channel_id}"


def _extract_youtube_url(youtube_url: str) -> Optional[str]:
    """
    Extract fresh HLS stream URL from a YouTube video/channel URL.
    
    YouTube live HLS URLs are time-limited, so this re-extracts a fresh one.
    """
    try:
        cmd = [
            "yt-dlp",
            "--dump-json",
            "--quiet",
            "--no-warnings",
            "-f", "best",
            youtube_url,
        ]
        
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=30.0
        )
        
        if result.returncode != 0 or not result.stdout:
            logger.debug(f"yt-dlp extraction failed for {youtube_url}")
            return None
        
        info = json.loads(result.stdout)
        
        # Look for HLS format (YouTube live streams use format_id "91" or similar)
        formats = info.get("formats", [])
        for fmt in formats:
            fmt_id = fmt.get("format_id", "")
            # Check for live HLS formats (usually "91" for best quality)
            if fmt_id in ("91", "92", "93", "hls-1", "hls") or "hls" in fmt_id.lower():
                url = fmt.get("url")
                if url and "manifest.googlevideo.com" in url:
                    return url
        
        # Fallback to any format with a URL
        for fmt in formats:
            url = fmt.get("url")
            if url and "manifest.googlevideo.com" in url:
                return url
        
        # If still no stream, try the first format's URL
        if formats:
            return formats[0].get("url")
        
        return None
    
    except Exception as e:
        logger.debug(f"Error extracting YouTube URL: {e}")
        return None


def _parse_youtube_info(info: dict) -> Optional[dict]:
    """
    Parse yt-dlp info dict and extract camera metadata.
    
    Returns dict with camera info or None if not a valid camera stream.
    """
    try:
        # Check if it's a live stream
        is_live = info.get("is_live", False)
        was_live = info.get("was_live", False)
        
        if not (is_live or was_live):
            return None
        
        title = info.get("title", "YouTube Live")
        channel = info.get("channel", "Unknown Channel")
        uploader = info.get("uploader", "")
        description = info.get("description", "")
        
        # Try to extract stream URL
        stream_url = None
        formats = info.get("formats", [])
        
        # Look for HLS format (most common for live)
        for fmt in formats:
            if fmt.get("format_id") == "hls-1":
                stream_url = fmt.get("url")
                break
        
        # Fallback to any video URL
        if not stream_url and formats:
            stream_url = formats[0].get("url")
        
        # If still no stream, try the url_transparent
        if not stream_url:
            stream_url = info.get("url")
        
        if not stream_url:
            logger.debug(f"No stream URL found for {title}")
            return None
        
        # Try to extract thumbnail
        thumbnail = info.get("thumbnail")
        if not thumbnail and info.get("thumbnails"):
            thumbnail = info["thumbnails"][-1].get("url")
        
        return {
            "url": f"https://www.youtube.com/watch?v={info.get('id', 'unknown')}",
            "title": title,
            "image_url": thumbnail or "",
            "stream_url": stream_url,
            "feed_type": "hls",  # YouTube live streams are HLS
            "site_name": "YouTube",
            "source": "youtube",
            "location": f"YouTube - {channel}",
            "description": description[:200] if description else f"Live: {channel}",
            "tags": "youtube,live,stream,camera",
        }
    
    except Exception as e:
        logger.debug(f"Error parsing YouTube info: {e}")
        return None


async def fetch_cameras(client) -> list[dict]:
    """
    Fetch live camera feeds from YouTube.
    
    Uses yt-dlp to search for and extract live camera streams.
    Requires yt-dlp to be installed: pip install yt-dlp
    """
    cameras = []
    seen_urls = set()
    
    try:
        # Check if yt-dlp is available
        result = subprocess.run(
            ["yt-dlp", "--version"],
            capture_output=True,
            timeout=5.0
        )
        if result.returncode != 0:
            logger.info("yt-dlp not installed, skipping YouTube discovery")
            return []
    except (FileNotFoundError, subprocess.TimeoutExpired):
        logger.info("yt-dlp not found, skipping YouTube discovery")
        return []
    
    logger.info("Starting YouTube live camera discovery...")
    
    # Try each search query
    for query in YOUTUBE_SEARCH_QUERIES[:6]:  # Search up to 6 queries
        logger.info(f"  Searching YouTube: {query}")
        
        try:
            # Search YouTube using yt-dlp
            cmd = [
                "yt-dlp",
                "--dump-json",
                "--quiet",
                "--no-warnings",
                "-f", "best",
                f"ytsearch{10}:{query}",  # Search YouTube for up to 10 results per query
            ]
            
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=30.0
            )
            
            if result.returncode != 0:
                logger.debug(f"YouTube search failed for '{query}'")
                continue
            
            # Parse results (yt-dlp outputs one JSON object per line)
            for line in result.stdout.strip().split("\n"):
                if not line.strip():
                    continue
                
                try:
                    info = json.loads(line)
                    video_id = info.get("id")
                    
                    if video_id in seen_urls:
                        continue
                    
                    seen_urls.add(video_id)
                    
                    # Check if it's a live/camera stream
                    cam_info = _parse_youtube_info(info)
                    if cam_info:
                        cameras.append(cam_info)
                        logger.debug(f"    ✓ Found: {cam_info['title'][:60]}")
                    
                except json.JSONDecodeError:
                    continue
        
        except (subprocess.TimeoutExpired, Exception) as e:
            logger.debug(f"Error searching YouTube for '{query}': {e}")
            continue
        
        # Small delay between searches
        await asyncio.sleep(0.5)
    
    logger.info(f"YouTube discovery complete: {len(cameras)} live streams found")
    return cameras
