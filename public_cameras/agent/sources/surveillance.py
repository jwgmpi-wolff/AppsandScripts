"""Multi-city street & traffic surveillance camera sources.

Covers major US city traffic management systems and additional state DOTs
not handled by dotcams.py (which covers WY / CO / OR / NV / MN / UT).

All APIs are publicly accessible without authentication.

Sources
-------
- NYC Traffic Management Center  (webcams.nyctmc.org)
- Chicago CDOT                   (city open data + camera image API)
- Maryland SHA                   (chart.maryland.gov)
- Houston TranStar / TxDOT       (houstontranstar.org)
- Virginia DOT (511VA)           (511virginia.org)
- Pennsylvania DOT (511PA)       (web scrape)
- Georgia DOT (511GA)            (511ga.org API)
- Florida DOT (FL511)            (fl511.com API)
- Michigan DOT (MI Drive)        (mdotjboss.state.mi.us)
- Missouri DOT (MoDOT)           (traveler.modot.org)
"""
from __future__ import annotations

import asyncio
import logging
import re
from urllib.parse import urljoin

import httpx
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)


def _sf(val) -> float | None:
    try:
        return float(val)
    except (TypeError, ValueError):
        return None


def _cam(*, title, url, image_url, location, state, city="",
         lat=None, lon=None, site_name, description, tags, source, keywords,
         country="USA", feed_type="image") -> dict:
    return {
        "title": title, "url": url, "image_url": image_url,
        "feed_type": feed_type, "location": location,
        "country": country, "state": state, "city": city,
        "latitude": lat, "longitude": lon,
        "site_name": site_name, "description": description,
        "tags": tags, "source": source, "keywords": keywords,
    }


# ---------------------------------------------------------------------------
# NYC Traffic Management Center
# ---------------------------------------------------------------------------
_NYC_API  = "https://webcams.nyctmc.org/api/cameras"
_NYC_IMG  = "https://webcams.nyctmc.org/api/cameras/{cid}/image"
_NYC_PAGE = "https://webcams.nyctmc.org/"


async def _fetch_nyc(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(_NYC_API, timeout=10)
        data = resp.json()
        if isinstance(data, dict):
            data = (data.get("cameras") or data.get("data") or
                    next(iter(data.values()), []))
        for entry in (data if isinstance(data, list) else []):
            if not isinstance(entry, dict):
                continue
            cid  = str(entry.get("id") or entry.get("cameraId") or entry.get("uuid") or "")
            name = (entry.get("name") or entry.get("description") or
                    entry.get("roadName") or f"NYC Cam {cid}")
            lat  = _sf(entry.get("lat") or entry.get("latitude"))
            lon  = _sf(entry.get("lon") or entry.get("longitude"))
            img  = entry.get("imageUrl") or entry.get("url") or ""
            if not img and cid:
                img = _NYC_IMG.format(cid=cid)
            
            # Try to construct MJPEG stream URL if camera ID exists
            stream_url = entry.get("streamUrl") or entry.get("stream_url") or ""
            if not stream_url and cid:
                # NYC TMC might have MJPEG streams at predictable endpoints
                stream_url = f"https://webcams.nyctmc.org/api/cameras/{cid}/stream.mjpeg"
            
            borough = entry.get("borough") or entry.get("location") or "NYC"
            cam_dict = _cam(
                title=f"NYC TMC: {name}",
                url=_NYC_PAGE, image_url=img,
                location=f"{name}, {borough}, NY",
                state="NY", city=borough, lat=lat, lon=lon,
                site_name="NYC Traffic Management Center",
                description=f"NYC DOT traffic surveillance camera – {name}, {borough}.",
                tags="traffic,nyc,new york,dot,surveillance,public,city,street,cctv,security",
                source="webcams.nyctmc.org",
                keywords=f"cctv security nyc new york city traffic surveillance street camera {name} {borough} dot tmc",
            )
            # Add inferred stream URL for detection
            if stream_url:
                cam_dict["stream_url"] = stream_url
            cameras.append(cam_dict)
    except Exception as exc:
        logger.debug("NYC TMC fetch failed: %s", exc)
    logger.info("NYC TMC: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Chicago CDOT (city open data + image API)
# ---------------------------------------------------------------------------
_CHI_API    = "https://data.cityofchicago.org/resource/ggws-77ih.json"
_CHI_PARAMS = {"$limit": 500, "$order": "camera_num ASC"}
_CHI_PAGE   = "https://www.chicago.gov/city/en/depts/cdot/traffic_cams.html"
_CHI_IMG    = "https://www.chicago.gov/dam/city/depts/cdot/ATSAC/cameraimages/cam{cid}.jpg"


async def _fetch_chicago(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(_CHI_API, params=_CHI_PARAMS, timeout=10)
        for entry in resp.json() if isinstance(resp.json(), list) else []:
            if not isinstance(entry, dict):
                continue
            cid  = str(entry.get("camera_num") or entry.get("id") or "")
            name = (entry.get("address") or entry.get("intersection") or
                    f"Chicago Cam {cid}")
            loc  = entry.get("location") or {}
            lat  = _sf(entry.get("latitude") or
                        (loc.get("coordinates") or [None, None])[1])
            lon  = _sf(entry.get("longitude") or
                        (loc.get("coordinates") or [None])[0])
            img  = entry.get("camera_url") or ""
            if not img and cid:
                img = _CHI_IMG.format(cid=cid)
            
            # Try to construct MJPEG stream URL
            stream_url = entry.get("streamUrl") or entry.get("stream_url") or ""
            if not stream_url and cid:
                stream_url = f"https://www.chicago.gov/dam/city/depts/cdot/ATSAC/camerastreamcctv/cam{cid}.mjpeg"
            
            cam_dict = _cam(
                title=f"Chicago DOT: {name}",
                url=_CHI_PAGE, image_url=img,
                location=f"{name}, Chicago, IL",
                state="IL", city="Chicago", lat=lat, lon=lon,
                site_name="Chicago CDOT",
                description=f"Chicago DOT traffic camera – {name}.",
                tags="traffic,chicago,illinois,cdot,dot,surveillance,public,city,street,cctv,security",
                source="data.cityofchicago.org",
                keywords=f"cctv security chicago illinois traffic surveillance street camera {name} cdot city",
            )
            if stream_url:
                cam_dict["stream_url"] = stream_url
            cameras.append(cam_dict)
    except Exception as exc:
        logger.debug("Chicago DOT fetch failed: %s", exc)
    logger.info("Chicago: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Maryland SHA (CHART)
# ---------------------------------------------------------------------------
_MD_API  = "https://chart.maryland.gov/data/cctvcameras.json"
_MD_PAGE = "https://chart.maryland.gov/"


async def _fetch_maryland(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    try:
        resp = await client.get(_MD_API, timeout=10)
        data = resp.json()
        entries = (data if isinstance(data, list)
                   else data.get("cameras") or data.get("data") or [])
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            cid  = str(entry.get("cameraId") or entry.get("id") or "")
            name = (entry.get("label") or entry.get("name") or
                    entry.get("description") or f"MD Cam {cid}")
            lat  = _sf(entry.get("latitude") or entry.get("lat"))
            lon  = _sf(entry.get("longitude") or entry.get("lon"))
            img  = (entry.get("imageUrl") or entry.get("url") or
                    entry.get("snapshotUrl") or "")
            if not img and cid:
                img = f"https://chart.maryland.gov/cameras/camera-{cid}.jpg"
            
            # Try to construct MJPEG stream URL
            stream_url = entry.get("streamUrl") or entry.get("stream_url") or ""
            if not stream_url and cid:
                stream_url = f"https://chart.maryland.gov/cameras/camera-{cid}.mjpeg"
            
            cam_dict = _cam(
                title=f"Maryland SHA: {name}",
                url=_MD_PAGE, image_url=img,
                location=f"{name}, MD",
                state="MD", lat=lat, lon=lon,
                site_name="Maryland SHA CHART",
                description=f"Maryland State Highway Administration CHART camera – {name}.",
                tags="traffic,maryland,sha,chart,dot,public,surveillance,cctv,security",
                source="chart.maryland.gov",
                keywords=f"cctv security maryland traffic surveillance camera {name} sha chart dot",
            )
            if stream_url:
                cam_dict["stream_url"] = stream_url
            cameras.append(cam_dict)
    except Exception as exc:
        logger.debug("Maryland SHA fetch failed: %s", exc)
    logger.info("Maryland SHA: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Houston TranStar / TxDOT
# ---------------------------------------------------------------------------
_HOU_API   = "https://traffic.houstontranstar.org/api/cameras"
_HOU_ALT   = "https://houstontranstar.org/map/"
_HOU_IMG   = "https://traffic.houstontranstar.org/layers/trafficImageDisplay.ashx?cameraId={cid}"
_HOU_PAGE  = "https://houstontranstar.org/cameras/"


async def _fetch_houston(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    for url in (_HOU_API, "https://houstontranstar.org/api/cctv"):
        try:
            resp = await client.get(url, timeout=8)
            if resp.status_code != 200:
                continue
            data = resp.json()
            entries = (data if isinstance(data, list)
                       else data.get("cameras") or data.get("data") or [])
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                cid  = str(entry.get("id") or entry.get("cameraId") or "")
                name = (entry.get("name") or entry.get("description") or
                        entry.get("label") or f"Houston Cam {cid}")
                lat  = _sf(entry.get("lat") or entry.get("latitude"))
                lon  = _sf(entry.get("lon") or entry.get("longitude"))
                img  = entry.get("imageUrl") or entry.get("url") or ""
                if not img and cid:
                    img = _HOU_IMG.format(cid=cid)
                
                # Try to construct MJPEG stream URL
                stream_url = entry.get("streamUrl") or entry.get("stream_url") or ""
                if not stream_url and cid:
                    stream_url = f"https://traffic.houstontranstar.org/layers/trafficImageDisplayHD.ashx?cameraId={cid}&stream=true"
                
                cam_dict = _cam(
                    title=f"TxDOT Houston: {name}",
                    url=_HOU_PAGE, image_url=img,
                    location=f"{name}, Houston, TX",
                    state="TX", city="Houston", lat=lat, lon=lon,
                    site_name="Houston TranStar",
                    description=f"Houston TranStar / TxDOT traffic camera – {name}.",
                    tags="traffic,texas,txdot,houston,dot,public,surveillance,street,cctv,security",
                    source="houstontranstar.org",
                    keywords=f"cctv security texas houston traffic surveillance camera {name} txdot transtar",
                )
                if stream_url:
                    cam_dict["stream_url"] = stream_url
                cameras.append(cam_dict)
            if cameras:
                break
        except Exception as exc:
            logger.debug("Houston TranStar %s failed: %s", url, exc)
    logger.info("Houston TranStar: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Virginia DOT (511VA)
# ---------------------------------------------------------------------------
_VA_APIS = [
    "https://www.511virginia.org/CamerasREST/getcameras.aspx",
    "https://api.511virginia.org/cameras",
    "https://va511.com/api/cameras",
]
_VA_PAGE = "https://www.511virginia.org/"


async def _fetch_511va(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    for url in _VA_APIS:
        try:
            resp = await client.get(url, timeout=8,
                                    headers={"Accept": "application/json"})
            if resp.status_code != 200:
                continue
            data = resp.json()
            entries = (data if isinstance(data, list)
                       else data.get("cameras") or data.get("features") or data.get("data") or [])
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                props = entry.get("properties") or entry
                cid   = str(props.get("id") or props.get("cameraId") or "")
                name  = (props.get("name") or props.get("description") or
                         props.get("label") or f"VDOT Cam {cid}")
                coords = (entry.get("geometry") or {}).get("coordinates") or []
                lat    = _sf(coords[1] if len(coords) > 1 else props.get("latitude"))
                lon    = _sf(coords[0] if coords else props.get("longitude"))
                img    = props.get("imageUrl") or props.get("url") or ""
                cameras.append(_cam(
                    title=f"VDOT: {name}",
                    url=_VA_PAGE, image_url=img,
                    location=f"{name}, VA",
                    state="VA", lat=lat, lon=lon,
                    site_name="Virginia DOT 511VA",
                    description=f"Virginia DOT 511VA traffic camera – {name}.",
                    tags="traffic,virginia,vdot,dot,511va,public,surveillance,cctv,security",
                    source="511virginia.org",
                    keywords=f"cctv security virginia traffic surveillance camera {name} vdot 511va dot",
                ))
            if cameras:
                break
        except Exception as exc:
            logger.debug("511VA %s failed: %s", url, exc)
    logger.info("511VA: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Pennsylvania DOT (511PA) – scrape public camera listing
# ---------------------------------------------------------------------------
_PA_PAGES = [
    "https://www.511pa.com/cameras",
    "https://www.511pa.com/",
]
_PA_IMG_RE = re.compile(r"https?://[^\"'\s]+/images/cams/[^\"'\s]+\.jpg", re.I)
_PA_PAGE   = "https://www.511pa.com/"


async def _fetch_511pa(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    for url in _PA_PAGES:
        try:
            # 511PA has a public JSON endpoint
            json_url = url.rstrip("/") + "/api/cameras"
            try:
                resp = await client.get(json_url, timeout=15)
                if resp.status_code == 200:
                    data = resp.json()
                    entries = (data if isinstance(data, list)
                               else data.get("cameras") or data.get("data") or [])
                    for entry in entries:
                        if not isinstance(entry, dict):
                            continue
                        cid  = str(entry.get("id") or entry.get("cameraId") or "")
                        name = entry.get("name") or entry.get("description") or f"PennDOT Cam {cid}"
                        lat  = _sf(entry.get("latitude") or entry.get("lat"))
                        lon  = _sf(entry.get("longitude") or entry.get("lon"))
                        img  = entry.get("imageUrl") or entry.get("url") or ""
                        if not img and cid:
                            img = f"https://www.511pa.com/images/cams/{cid}.jpg"
                        cameras.append(_cam(
                            title=f"PennDOT: {name}",
                            url=_PA_PAGE, image_url=img,
                            location=f"{name}, PA",
                            state="PA", lat=lat, lon=lon,
                            site_name="Pennsylvania DOT 511PA",
                            description=f"Pennsylvania DOT 511PA traffic camera – {name}.",
                            tags="traffic,pennsylvania,penndot,dot,511pa,public,surveillance,cctv,security",
                            source="511pa.com",
                            keywords=f"cctv security pennsylvania traffic surveillance camera {name} penndot 511pa dot",
                        ))
                    if cameras:
                        return cameras
            except Exception:
                pass

            # Fall back to HTML scrape for image URLs
            resp = await client.get(url, timeout=8)
            if resp.status_code != 200:
                continue
            for img_url in _PA_IMG_RE.findall(resp.text):
                cid = re.search(r"cams/([^./]+)", img_url)
                cid = cid.group(1) if cid else "unknown"
                cameras.append(_cam(
                    title=f"PennDOT: Camera {cid}",
                    url=_PA_PAGE, image_url=img_url,
                    location=f"Pennsylvania", state="PA",
                    site_name="Pennsylvania DOT 511PA",
                    description=f"Pennsylvania DOT traffic camera.",
                    tags="traffic,pennsylvania,penndot,dot,511pa,public,surveillance,cctv,security",
                    source="511pa.com",
                    keywords=f"cctv security pennsylvania traffic camera penndot 511pa dot",
                ))
            if cameras:
                break
        except Exception as exc:
            logger.debug("511PA %s failed: %s", url, exc)
    logger.info("511PA: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Georgia DOT (511GA)
# ---------------------------------------------------------------------------
_GA_APIS = [
    "https://511ga.org/api/cameras",
    "https://511ga.org/api/v2/cameras",
    "https://navigator.dot.ga.gov/api/cameras",
]
_GA_PAGE = "https://511ga.org/"


async def _fetch_511ga(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    for url in _GA_APIS:
        try:
            resp = await client.get(url, timeout=8,
                                    headers={"Accept": "application/json"})
            if resp.status_code != 200:
                continue
            data = resp.json()
            entries = (data if isinstance(data, list)
                       else data.get("cameras") or data.get("features") or data.get("data") or [])
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                props = entry.get("properties") or entry
                cid   = str(props.get("id") or props.get("cameraId") or "")
                name  = props.get("name") or props.get("description") or f"GDOT Cam {cid}"
                coords = (entry.get("geometry") or {}).get("coordinates") or []
                lat    = _sf(coords[1] if len(coords) > 1 else props.get("latitude"))
                lon    = _sf(coords[0] if coords else props.get("longitude"))
                img    = props.get("imageUrl") or props.get("url") or ""
                cameras.append(_cam(
                    title=f"GDOT: {name}",
                    url=_GA_PAGE, image_url=img,
                    location=f"{name}, GA",
                    state="GA", lat=lat, lon=lon,
                    site_name="Georgia DOT 511GA",
                    description=f"Georgia DOT 511GA traffic camera – {name}.",
                    tags="traffic,georgia,gdot,dot,511ga,public,surveillance,cctv,security",
                    source="511ga.org",
                    keywords=f"cctv security georgia traffic surveillance camera {name} gdot 511ga dot",
                ))
            if cameras:
                break
        except Exception as exc:
            logger.debug("511GA %s failed: %s", url, exc)
    logger.info("511GA: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Florida DOT (FL511)
# ---------------------------------------------------------------------------
_FL_APIS = [
    "https://fl511.com/api/cameras",
    "https://www.fdot.gov/api/cameras",
    "https://fl511.com/api/v2/cameras",
]
_FL_PAGE = "https://fl511.com/"


async def _fetch_fl511(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    for url in _FL_APIS:
        try:
            resp = await client.get(url, timeout=8,
                                    headers={"Accept": "application/json"})
            if resp.status_code != 200:
                continue
            data = resp.json()
            entries = (data if isinstance(data, list)
                       else data.get("cameras") or data.get("features") or data.get("data") or [])
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                props = entry.get("properties") or entry
                cid   = str(props.get("id") or props.get("cameraId") or "")
                name  = props.get("name") or props.get("description") or f"FDOT Cam {cid}"
                coords = (entry.get("geometry") or {}).get("coordinates") or []
                lat    = _sf(coords[1] if len(coords) > 1 else props.get("latitude"))
                lon    = _sf(coords[0] if coords else props.get("longitude"))
                img    = props.get("imageUrl") or props.get("url") or ""
                cameras.append(_cam(
                    title=f"FDOT: {name}",
                    url=_FL_PAGE, image_url=img,
                    location=f"{name}, FL",
                    state="FL", lat=lat, lon=lon,
                    site_name="Florida DOT FL511",
                    description=f"Florida DOT FL511 traffic camera – {name}.",
                    tags="traffic,florida,fdot,dot,fl511,public,surveillance,cctv,security",
                    source="fl511.com",
                    keywords=f"cctv security florida traffic surveillance camera {name} fdot fl511 dot",
                ))
            if cameras:
                break
        except Exception as exc:
            logger.debug("FL511 %s failed: %s", url, exc)
    logger.info("FL511: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Michigan DOT (MI Drive)
# ---------------------------------------------------------------------------
_MI_APIS = [
    "http://mdotjboss.state.mi.us/webapi/cameras",
    "https://www.michigan.gov/mdot/api/cameras",
    "https://mdotjboss.state.mi.us/MiDrive/cameras",
]
_MI_PAGE = "https://mdotjboss.state.mi.us/MiDrive/"


async def _fetch_midrive(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    for url in _MI_APIS:
        try:
            resp = await client.get(url, timeout=8,
                                    headers={"Accept": "application/json"})
            if resp.status_code != 200:
                continue
            data = resp.json()
            entries = (data if isinstance(data, list)
                       else data.get("cameras") or data.get("data") or [])
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                cid  = str(entry.get("id") or entry.get("cameraId") or "")
                name = entry.get("name") or entry.get("description") or f"MDOT Cam {cid}"
                lat  = _sf(entry.get("latitude") or entry.get("lat"))
                lon  = _sf(entry.get("longitude") or entry.get("lon"))
                img  = entry.get("imageUrl") or entry.get("url") or ""
                cameras.append(_cam(
                    title=f"MDOT: {name}",
                    url=_MI_PAGE, image_url=img,
                    location=f"{name}, MI",
                    state="MI", lat=lat, lon=lon,
                    site_name="Michigan DOT MI Drive",
                    description=f"Michigan DOT MI Drive traffic camera – {name}.",
                    tags="traffic,michigan,mdot,dot,midrive,public,surveillance,cctv,security",
                    source="mdotjboss.state.mi.us",
                    keywords=f"cctv security michigan traffic surveillance camera {name} mdot midrive dot",
                ))
            if cameras:
                break
        except Exception as exc:
            logger.debug("MI Drive %s failed: %s", url, exc)
    logger.info("MI Drive: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Missouri DOT (MoDOT Traveler)
# ---------------------------------------------------------------------------
_MO_APIS = [
    "https://traveler.modot.org/api/cameras",
    "https://services.modot.mo.gov/api/cameras",
]
_MO_PAGE = "https://traveler.modot.org/"


async def _fetch_modot(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    for url in _MO_APIS:
        try:
            resp = await client.get(url, timeout=8,
                                    headers={"Accept": "application/json"})
            if resp.status_code != 200:
                continue
            data = resp.json()
            entries = (data if isinstance(data, list)
                       else data.get("cameras") or data.get("data") or [])
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                cid  = str(entry.get("id") or entry.get("cameraId") or "")
                name = entry.get("name") or entry.get("description") or f"MoDOT Cam {cid}"
                lat  = _sf(entry.get("latitude") or entry.get("lat"))
                lon  = _sf(entry.get("longitude") or entry.get("lon"))
                img  = entry.get("imageUrl") or entry.get("url") or ""
                cameras.append(_cam(
                    title=f"MoDOT: {name}",
                    url=_MO_PAGE, image_url=img,
                    location=f"{name}, MO",
                    state="MO", lat=lat, lon=lon,
                    site_name="Missouri DOT",
                    description=f"Missouri DOT traffic camera – {name}.",
                    tags="traffic,missouri,modot,dot,public,surveillance,cctv,security",
                    source="traveler.modot.org",
                    keywords=f"cctv security missouri traffic surveillance camera {name} modot dot",
                ))
            if cameras:
                break
        except Exception as exc:
            logger.debug("MoDOT %s failed: %s", url, exc)
    logger.info("MoDOT: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# New Jersey DOT (511NJ)
# ---------------------------------------------------------------------------
_NJ_APIS = [
    "https://www.511nj.org/travelerapi/cameras",
    "https://511nj.org/api/cameras",
]
_NJ_PAGE = "https://www.511nj.org/"


async def _fetch_511nj(client: httpx.AsyncClient) -> list[dict]:
    cameras: list[dict] = []
    for url in _NJ_APIS:
        try:
            resp = await client.get(url, timeout=6,
                                    headers={"Accept": "application/json"})
            if resp.status_code != 200:
                continue
            data = resp.json()
            entries = (data if isinstance(data, list)
                       else data.get("cameras") or data.get("features") or data.get("data") or [])
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                props = entry.get("properties") or entry
                cid   = str(props.get("id") or props.get("cameraId") or "")
                name  = props.get("name") or props.get("description") or f"NJDOT Cam {cid}"
                coords = (entry.get("geometry") or {}).get("coordinates") or []
                lat    = _sf(coords[1] if len(coords) > 1 else props.get("latitude"))
                lon    = _sf(coords[0] if coords else props.get("longitude"))
                img    = props.get("imageUrl") or props.get("url") or ""
                cameras.append(_cam(
                    title=f"NJDOT: {name}",
                    url=_NJ_PAGE, image_url=img,
                    location=f"{name}, NJ",
                    state="NJ", lat=lat, lon=lon,
                    site_name="New Jersey DOT 511NJ",
                    description=f"New Jersey DOT 511NJ traffic camera – {name}.",
                    tags="traffic,new jersey,njdot,dot,511nj,public,surveillance,cctv,security",
                    source="511nj.org",
                    keywords=f"cctv security new jersey traffic surveillance camera {name} njdot 511nj dot",
                ))
            if cameras:
                break
        except Exception as exc:
            logger.debug("511NJ %s failed: %s", url, exc)
    logger.info("511NJ: %d cameras", len(cameras))
    return cameras


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

async def fetch_cameras(client: httpx.AsyncClient) -> list[dict]:
    results = await asyncio.gather(
        _fetch_nyc(client),
        _fetch_chicago(client),
        _fetch_maryland(client),
        _fetch_houston(client),
        _fetch_511va(client),
        _fetch_511pa(client),
        _fetch_511ga(client),
        _fetch_fl511(client),
        _fetch_midrive(client),
        _fetch_modot(client),
        _fetch_511nj(client),
        return_exceptions=True,
    )
    cameras: list[dict] = []
    for r in results:
        if isinstance(r, list):
            cameras.extend(r)
    return cameras
