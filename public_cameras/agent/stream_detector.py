"""Stream URL detection and classification."""
import re
from urllib.parse import urljoin, urlparse


def detect_stream_url(camera_dict: dict, html_content: str = "") -> dict:
    """
    Detect streaming URLs (HLS, MJPEG, RTMP) from camera data and HTML.
    Returns dict with detected streams: {stream_url, stream_type}
    """
    result = {"stream_url": None, "stream_type": None}
    
    # Priority 1: Explicit stream_url if provided
    if camera_dict.get("stream_url"):
        stream_url = camera_dict["stream_url"]
        result["stream_url"] = stream_url
        result["stream_type"] = classify_stream(stream_url)
        return result
    
    # Priority 2: Parse HTML for embedded stream URLs
    if html_content:
        # Look for HLS/M3U8 URLs
        m3u8_urls = re.findall(r'https?://[^\s"\'<>]*\.m3u8(?:[?#][^\s"\'<>]*)?', html_content, re.I)
        if m3u8_urls:
            result["stream_url"] = m3u8_urls[0]
            result["stream_type"] = "hls"
            return result
        
        # Look for MJPEG stream URLs
        mjpeg_urls = re.findall(r'https?://[^\s"\'<>]*\.(?:mjpeg|mjpg)(?:[?#][^\s"\'<>]*)?', html_content, re.I)
        if mjpeg_urls:
            result["stream_url"] = mjpeg_urls[0]
            result["stream_type"] = "mjpeg"
            return result
        
        # Look for MP4/WebM streams
        video_urls = re.findall(r'https?://[^\s"\'<>]*\.(?:mp4|webm|ts)(?:[?#][^\s"\'<>]*)?', html_content, re.I)
        if video_urls:
            result["stream_url"] = video_urls[0]
            result["stream_type"] = "mp4"
            return result
        
        # Look for RTMP URLs
        rtmp_urls = re.findall(r'rtmp(?:e)?://[^\s"\'<>]+', html_content, re.I)
        if rtmp_urls:
            result["stream_url"] = rtmp_urls[0]
            result["stream_type"] = "rtmp"
            return result
    
    # Priority 3: Infer from image_url if it looks like a stream endpoint
    image_url = camera_dict.get("image_url", "")
    if image_url:
        if _is_stream_like(image_url):
            result["stream_url"] = image_url
            result["stream_type"] = classify_stream(image_url)
            return result
    
    return result


def classify_stream(url: str) -> str:
    """Classify a URL as a stream type."""
    url_lower = url.lower()
    
    if ".m3u8" in url_lower or "hls" in url_lower:
        return "hls"
    if ".mjpeg" in url_lower or ".mjpg" in url_lower or "mjpeg" in url_lower:
        return "mjpeg"
    if ".mp4" in url_lower:
        return "mp4"
    if ".ts" in url_lower:
        return "mp2t"
    if ".webm" in url_lower:
        return "webm"
    if url_lower.startswith("rtmp"):
        return "rtmp"
    
    return None


def _is_stream_like(url: str) -> bool:
    """Check if a URL looks like a continuous stream endpoint."""
    url_lower = url.lower()
    stream_patterns = [
        r"\.mjpe?g($|[?#])",
        r"\.m3u8($|[?#])",
        r"stream",
        r"live",
        r"video",
        r"/axis-cgi/",
        r"/cgi-bin/",
        r"/mjpegstream",
        r"/videofeed",
    ]
    return any(re.search(p, url_lower) for p in stream_patterns)


def inject_stream_urls(cameras: list[dict], source_html_map: dict = None) -> list[dict]:
    """
    Inject stream_url and update feed_type for cameras based on content analysis.
    source_html_map: dict mapping camera index to HTML content for parsing
    """
    if source_html_map is None:
        source_html_map = {}
    
    for i, cam in enumerate(cameras):
        html = source_html_map.get(i, "")
        stream_info = detect_stream_url(cam, html)
        
        if stream_info["stream_url"]:
            cam["stream_url"] = stream_info["stream_url"]
            # Update feed_type if it's a recognized stream
            if stream_info["stream_type"]:
                cam["feed_type"] = stream_info["stream_type"]
    
    return cameras
