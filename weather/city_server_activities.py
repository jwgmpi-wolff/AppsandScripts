# city_server.py
import os
import sys
import logging
from typing import TypedDict, Optional, List, Literal, Dict, Any
import httpx
from fastmcp import FastMCP

# ---- Logging (STDIO-safe): write logs to stderr, never stdout ----
logging.basicConfig(stream=sys.stderr, level=logging.INFO)
log = logging.getLogger("city_mcp")

mcp = FastMCP("city")

# -----------------------------
# Typed results
# -----------------------------
class WeatherResult(TypedDict):
    city: str
    temperature_f: float
    condition: str

class Activity(TypedDict, total=False):
    name: str
    category: str
    url: Optional[str]
    source: str
    address: Optional[str]
    when: Optional[str]

class ActivitiesResult(TypedDict):
    city: str
    category: str
    activities: List[Activity]

class PlanResult(TypedDict):
    city: str
    weather: WeatherResult
    category: str
    activities: List[Activity]


# -----------------------------
# Provider helpers (reused by tools + aggregator)
# -----------------------------
def _require_env(name: str) -> str:
    v = os.environ.get(name)
    if not v:
        raise RuntimeError(f"{name} not set")
    return v

def _http() -> httpx.Client:
    # single shared sync client per request scope is fine; tools are short-lived under stdio
    return httpx.Client(timeout=15.0)

def _normalize_category(cat: str) -> str:
    return (cat or "things_to_do").strip().lower()


# -----------------------------
# Weather (OpenWeather)
# -----------------------------
@mcp.tool
def get_weather(city: str) -> WeatherResult:
    """Current weather for a city (OpenWeatherMap). Requires OPENWEATHER_API_KEY."""
    api_key = _require_env("OPENWEATHER_API_KEY")

    # OpenWeather endpoint (standard)
    url = "https://api.openweathermap.org/data/2.5/weather"
    with _http() as client:
        resp = client.get(url, params={"q": city, "appid": api_key, "units": "imperial"})
        resp.raise_for_status()
        data = resp.json()

    return {
        "city": data.get("name") or city,
        "temperature_f": float(data["main"]["temp"]),
        "condition": str(data["weather"][0]["main"]),
    }


# -----------------------------
# Ticketmaster events
# -----------------------------
@mcp.tool
def get_ticketmaster_events(
    city: str,
    classification: str = "music",
    limit: int = 10
) -> List[Activity]:
    """
    Ticketmaster Discovery events for a city.
    Requires TICKETMASTER_API_KEY.
    """
    api_key = _require_env("TICKETMASTER_API_KEY")

    # Ticketmaster Discovery API events endpoint
    # https://app.ticketmaster.com/discovery/v2/events.json?apikey={apikey} ... 【5-eea373】
    url = "https://app.ticketmaster.com/discovery/v2/events.json"

    params = {
        "apikey": api_key,
        "city": city,
        "classificationName": classification,
        "size": max(1, min(int(limit), 50)),
        "sort": "date,asc",
    }

    with _http() as client:
        resp = client.get(url, params=params)
        resp.raise_for_status()
        data = resp.json()

    events = (data.get("_embedded") or {}).get("events") or []
    out: List[Activity] = []

    for e in events[:limit]:
        name = e.get("name") or "Event"
        url_ = e.get("url")
        dates = (e.get("dates") or {}).get("start") or {}
        when = dates.get("localDate") or dates.get("dateTime")
        out.append({
            "name": name,
            "category": classification,
            "url": url_,
            "source": "ticketmaster",
            "when": when,
        })

    return out


# -----------------------------
# Yelp places (food / things-to-do)
# -----------------------------
@mcp.tool
def get_yelp_places(
    city: str,
    term: str = "things to do",
    categories: Optional[str] = None,
    limit: int = 10
) -> List[Activity]:
    """
    Yelp Places / Business Search.
    Requires YELP_API_KEY (Bearer token).
    """
    api_key = _require_env("YELP_API_KEY")

    # Yelp business search endpoint:
    # GET https://api.yelp.com/v3/businesses/search 【6-1518c7】【7-598d3f】
    url = "https://api.yelp.com/v3/businesses/search"
    params: Dict[str, Any] = {
        "location": city,
        "term": term,
        "limit": max(1, min(int(limit), 50)),
    }
    if categories:
        params["categories"] = categories

    headers = {"Authorization": f"Bearer {api_key}"}

    with _http() as client:
        resp = client.get(url, params=params, headers=headers)
        resp.raise_for_status()
        data = resp.json()

    businesses = data.get("businesses") or []
    out: List[Activity] = []
    for b in businesses[:limit]:
        out.append({
            "name": b.get("name") or "Place",
            "category": categories or term,
            "url": b.get("url"),
            "source": "yelp",
            "address": ", ".join((b.get("location") or {}).get("display_address") or []) or None,
        })
    return out


# -----------------------------
# OpenTripMap POIs (tourist attractions)
# -----------------------------
@mcp.tool
def get_opentripmap_pois(
    city: str,
    kinds: str = "interesting_places",
    radius_m: int = 5000,
    limit: int = 10,
    lang: str = "en"
) -> List[Activity]:
    """
    OpenTripMap POIs around city center.
    Requires OPENTRIPMAP_API_KEY.
    """
    api_key = _require_env("OPENTRIPMAP_API_KEY")

    # OpenTripMap docs show /places/geoname and /places/radius with apikey 【8-4a1703】
    base = f"https://api.opentripmap.com/0.1/{lang}/places"

    with _http() as client:
        # Step 1: resolve city -> lat/lon
        geo = client.get(f"{base}/geoname", params={"name": city, "apikey": api_key})
        geo.raise_for_status()
        g = geo.json()
        lat = g.get("lat")
        lon = g.get("lon")
        if lat is None or lon is None:
            return []

        # Step 2: fetch POIs in radius
        pois = client.get(
            f"{base}/radius",
            params={
                "radius": int(radius_m),
                "lat": lat,
                "lon": lon,
                "kinds": kinds,
                "limit": max(1, min(int(limit), 50)),
                "format": "json",
                "apikey": api_key,
            },
        )
        pois.raise_for_status()
        items = pois.json() or []

    out: List[Activity] = []
    for it in items[:limit]:
        out.append({
            "name": it.get("name") or "POI",
            "category": kinds,
            "url": None,
            "source": "opentripmap",
        })
    return out


# -----------------------------
# Aggregator: leverage the other tools/providers
# -----------------------------
@mcp.tool
def get_city_activities(
    city: str,
    category: str = "things_to_do",
    limit: int = 10,
    include_sources: str = "ticketmaster,yelp,opentripmap"
) -> ActivitiesResult:
    """
    Aggregated activities for a city, leveraging provider tools.
    include_sources comma-list: ticketmaster,yelp,opentripmap
    """
    cat = _normalize_category(category)
    sources = {s.strip().lower() for s in include_sources.split(",") if s.strip()}
    activities: List[Activity] = []

