"""FAA Aviation Weather Camera system.

All FAA aviation cameras are publicly accessible, no authentication needed.
Alaska alone has 200+ cameras at known URL patterns.

Image URL: https://avcams.faa.gov/camimages/{ICAO}/latest.jpg
Camera list: https://avcams.faa.gov/
"""
from __future__ import annotations
import asyncio
import logging
import re
from bs4 import BeautifulSoup
import httpx

logger = logging.getLogger(__name__)

# Known FAA camera ICAO codes – Alaska (AK) has the most extensive network
_ALASKA_ICAO = [
    "PAAK", "PAAP", "PAAQ", "PABE", "PABI", "PABL", "PACV", "PADB",
    "PADF", "PADL", "PADU", "PAED", "PAEH", "PAEM", "PAEN", "PAFA",
    "PAFB", "PAFE", "PAFM", "PAGM", "PAGQ", "PAGS", "PAHD", "PAHG",
    "PAHO", "PAI1", "PAJN", "PAKK", "PAKN", "PAKP", "PAKT", "PAKV",
    "PAKW", "PALG", "PALR", "PAMD", "PAMH", "PAMK", "PANC", "PANI",
    "PANN", "PANO", "PANR", "PANT", "PAOH", "PAOM", "PAOR", "PAOT",
    "PAPB", "PAPC", "PAPE", "PAPG", "PAQT", "PARK", "PARO", "PARS",
    "PARY", "PASC", "PASD", "PASG", "PASH", "PASI", "PASK", "PAST",
    "PASV", "PASY", "PATA", "PATC", "PATE", "PATI", "PATK", "PATL",
    "PAUM", "PAUN", "PAUT", "PAVA", "PAVE", "PAWI", "PAWM", "PAWN",
    "PAWR", "PAXD", "PAYA", "PAYH", "PAYK", "PAYL", "PAZA", "PAZK",
    "PAMR", "PANV", "PAIL", "PAIM", "PAFR", "PADQ", "PAIL",
]

# Lower-48 + Hawaii FAA cameras
_CONUS_ICAO = [
    "PAJN", "KBWI", "KSEA", "KORD", "KLAX", "KDEN", "KDFW", "KIAH",
    "KJFK", "KLGA", "KEWR", "KBOS", "KSFO", "KMIA", "KATL", "KPHX",
    "KLAS", "KDTW", "KMSP", "KSTL", "KTPA", "KPDX", "KSLC", "KSAN",
    "KMDW", "KCLE", "KCMH", "KMKE", "KPIT", "KBUF",
]

_CAM_BASE     = "https://avcams.faa.gov"
_IMG_PATTERN  = "https://avcams.faa.gov/camimages/{icao}/latest.jpg"
_PAGE_PATTERN = "https://avcams.faa.gov/station/{icao}/"

# Airport name lookup (subset)
_AK_NAMES: dict[str, tuple[str, str, float, float]] = {
    # (city, city_full, lat, lon)
    "PANC": ("Anchorage",     "Ted Stevens Int'l, AK",      61.1745, -149.9963),
    "PAFA": ("Fairbanks",     "Fairbanks Int'l, AK",         64.8154, -147.8564),
    "PAJN": ("Juneau",        "Juneau Int'l, AK",            58.3549, -134.5765),
    "PAKT": ("Ketchikan",     "Ketchikan, AK",               55.3556, -131.7137),
    "PAEN": ("Kenai",         "Kenai Municipal, AK",         60.5731, -151.2439),
    "PADQ": ("Kodiak",        "Kodiak, AK",                  57.7497, -152.4938),
    "PASY": ("Shemya",        "Shemya / Eareckson, AK",      52.7124, 174.1135),
    "PABE": ("Bethel",        "Bethel, AK",                  60.7798, -161.8380),
    "PAOM": ("Nome",          "Nome, AK",                    64.5120, -165.4454),
    "PABI": ("Delta Junction","Big Delta, AK",                64.0035, -145.7218),
    "PAMR": ("Anchorage",     "Merrill Field, AK",           61.2135, -149.8438),
    "PAHO": ("Homer",         "Homer, AK",                   59.6456, -151.4770),
    "PAWD": ("Seward",        "Seward, AK",                  60.1249, -149.4163),
    "PADU": ("Unalaska",      "Unalaska/Dutch Harbor, AK",  53.9008, -166.5440),
    "PAOT": ("Kotzebue",      "Kotzebue/Wien, AK",          66.8847, -162.5990),
    "PAKN": ("King Salmon",   "King Salmon, AK",             58.6808, -156.6490),
    "PAPH": ("Port Heiden",   "Port Heiden, AK",             56.9569, -158.6234),
    "PAVE": ("Venetie",       "Venetie, AK",                 67.0087, -146.3658),
    "PASC": ("Deadhorse",     "Deadhorse/Prudhoe Bay, AK",   70.1947, -148.4653),
    "PAII": ("Iliamna",       "Iliamna, AK",                 59.7542, -154.9108),
    "PAUN": ("Unalakleet",    "Unalakleet, AK",              63.8884, -160.7988),
    "PAQT": ("Nuiqsut",       "Nuiqsut, AK",                 70.2100, -150.9928),
    "PAKP": ("Anaktuvuk",     "Anaktuvuk Pass, AK",          68.1337, -151.7433),
}


async def _scrape_faa_camera_list(client: httpx.AsyncClient) -> list[str]:
    """Try to scrape the FAA camera listing page for ICAO codes."""
    icao_codes: list[str] = []
    try:
        resp = await client.get(f"{_CAM_BASE}/camlist.htm", timeout=15)
        if resp.status_code != 200:
            resp = await client.get(f"{_CAM_BASE}/", timeout=15)
        soup = BeautifulSoup(resp.text, "html.parser")
        # Look for links or text containing ICAO patterns (PA**, K***, etc.)
        for text in soup.stripped_strings:
            codes = re.findall(r"\b(PA[A-Z0-9]{2,3}|K[A-Z]{3})\b", text.upper())
            icao_codes.extend(codes)
        # Also from href patterns
        for a in soup.find_all("a", href=re.compile(r"[Pp][Aa][A-Z0-9]{2,3}|[Kk][A-Z]{3}")):
            m = re.search(r"(PA[A-Z0-9]{2,3}|K[A-Z]{3})", a["href"].upper())
            if m:
                icao_codes.append(m.group(1))
    except Exception as exc:
        logger.debug("FAA list scrape failed: %s", exc)
    return list(dict.fromkeys(icao_codes))


def _make_camera(icao: str) -> dict:
    icao = icao.upper()
    info = _AK_NAMES.get(icao)
    city     = info[0] if info else icao
    loc_full = info[1] if info else f"{icao}, AK"
    lat      = info[2] if info else None
    lon      = info[3] if info else None
    state    = "AK" if icao.startswith("PA") else "USA"
    return {
        "title":       f"FAA Aviation Cam – {loc_full}",
        "url":         _PAGE_PATTERN.format(icao=icao),
        "image_url":   _IMG_PATTERN.format(icao=icao),
        "feed_type":   "image",
        "location":    loc_full,
        "country":     "USA",
        "state":       state,
        "city":        city,
        "latitude":    lat,
        "longitude":   lon,
        "site_name":   "FAA Aviation Weather Cameras",
        "description": f"FAA publicly accessible aviation weather camera at {loc_full}.",
        "tags":        f"faa,aviation,weather,airport,public,alaska,{state.lower()}",
        "source":      "avcams.faa.gov",
        "keywords":    f"faa aviation weather camera {loc_full} {icao} airport alaska",
    }


async def fetch_cameras(client: httpx.AsyncClient) -> list[dict]:
    # Use known ICAO codes + any discovered from scraping
    scraped = await _scrape_faa_camera_list(client)
    all_icao = list(dict.fromkeys(_ALASKA_ICAO + _CONUS_ICAO + scraped))
    cameras = [_make_camera(icao) for icao in all_icao]
    logger.info("FAA: built %d camera entries", len(cameras))
    return cameras
