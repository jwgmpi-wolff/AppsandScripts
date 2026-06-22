"""AlertWildfire public fire-lookout camera API.

No authentication required.  Returns 200–600+ fire-watch cameras with
direct refreshable image URLs across the western USA.

API: https://data.alertwildfire.org/api/firehawks-rv3/latest  (GeoJSON)
"""
import logging
import httpx

logger = logging.getLogger(__name__)

_API_URL = "https://data.alertwildfire.org/api/firehawks-rv3/latest"
_IMG_TMPL = "https://ts1.alertwildfire.org/still/{cam_id}/?size=full&quality=medium"
_CAM_PAGE = "https://www.alertwildfire.org/camera/{cam_id}"


async def fetch_cameras(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(_API_URL, timeout=30)
        resp.raise_for_status()
        data = resp.json()
    except Exception as exc:
        logger.error("AlertWildfire fetch failed: %s", exc)
        return cameras

    for feature in data.get("features", []):
        props = feature.get("properties", {}) or {}
        coords = (feature.get("geometry") or {}).get("coordinates") or [None, None]

        cam_id  = str(props.get("id") or props.get("name") or "").strip()
        name    = str(props.get("name") or props.get("unit") or cam_id).strip()
        unit    = str(props.get("unit") or "").strip()
        state   = str(props.get("state") or "").strip()
        network = str(props.get("network") or "").strip()

        # Some entries have an explicit imageUrl; others we derive from the cam_id
        img_url = (
            props.get("imageUrl")
            or props.get("image_url")
            or props.get("img")
            or (
                _IMG_TMPL.format(cam_id=cam_id.lower().replace(" ", "-"))
                if cam_id else ""
            )
        )
        page_url = _CAM_PAGE.format(cam_id=cam_id) if cam_id else img_url

        if not img_url:
            continue

        lat = coords[1] if len(coords) > 1 and coords[1] else None
        lon = coords[0] if len(coords) > 0 and coords[0] else None

        cameras.append(
            {
                "title":       name or f"Fire Camera {cam_id}",
                "url":         page_url,
                "image_url":   img_url,
                "feed_type":   "image",
                "location":    f"{unit}, {state}".strip(", "),
                "country":     "USA",
                "state":       state,
                "city":        unit,
                "latitude":    lat,
                "longitude":   lon,
                "site_name":   "AlertWildfire",
                "description": f"Public wildfire lookout camera. Network: {network}. Unit: {unit}.",
                "tags":        f"wildfire,fire,lookout,public,outdoors,{state.lower()},{network.lower()}",
                "source":      "alertwildfire.org",
                "keywords":    f"{name} {unit} {state} wildfire fire lookout {network}",
            }
        )

    logger.info("AlertWildfire: collected %d cameras", len(cameras))
    return cameras
