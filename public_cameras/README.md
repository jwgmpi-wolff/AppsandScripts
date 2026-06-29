# Public Camera Intelligence Agent

Discovers, stores, and browses publicly accessible webcam feeds — no login required.

## Features

- **2300+ cameras discoverable** — 200+ curated seeds + live crawl from 7 public camera sources
- **Street surveillance & traffic** — NYC TMC, Chicago CDOT, 11 state DOT systems (CA, CO, OR, NV, MN, UT, WY, VA, PA, GA, FL, MI, MO, NJ)
- **Consent-gate bypass (optional)** — Playwright browser automation to auto-accept GDPR/cookie/ToS banners
- **Live discovery** from AlertWildfire, Caltrans, state DOT systems, city traffic APIs, and web scrapers
- **Accurate keyword search** — FTS5 full-text search on metadata-only (no URL false positives from path tokens)
- **Offline camera tracking** — Status pills (● LIVE / ✕ OFFLINE / ? UNVERIFIED) with visual dimming for offline feeds
- **SQLite database** with full-text search and rich metadata (location, lat/lon, source, tags, timestamps)
- **Grid + Table views** with directly clickable camera image links
- **Auto-refresh** — refreshes all image thumbnails every 30 seconds
- **Fullscreen modal** with image refresh and direct feed link
- **Reference theme** copied from `people_search_mvp`

## Quick Start

```cmd
run.cmd
```

Or with PowerShell:

```ps1
.\start.ps1
```

### Use Outside VS Code

From a normal Command Prompt in the project folder:

```cmd
activate.cmd
```

Or open a ready-to-use PowerShell window (execution policy bypassed for that shell only):

```cmd
open-shell.cmd
```

### Auto-Start On Windows Login

Install startup entry:

```cmd
startup-install.cmd
```

Remove startup entry:

```cmd
startup-uninstall.cmd
```

When installed, Windows launches this app on sign-in in the background using `startup-run.cmd`, and output is written to `server.log` and `server.err`.

Then open **http://localhost:7000**

## Usage

1. Click **Search** to run a full discovery (crawls 2300+ cameras in ~35 seconds)
2. Optionally type a keyword (e.g. `alaska`, `traffic`, `wildfire`, `beach`, `seattle`) before clicking Search
3. Use the **Filter DB** bar to search saved cameras by keyword, state, or feed type
4. Enable **Show offline & unverified** (default ON) to see all cameras, including offline ones (marked with ✕ pill)
5. Enable **Auto-refresh images** to get live snapshots every 30 s
6. Click any camera thumbnail to open it fullscreen with metadata
7. Toggle **Table View** to see all metadata in a sortable table

### Keyword Search Notes

- Search only matches against metadata: title, location, city, state, country, site_name, description, tags, source, keywords
- Does NOT search URL/image_url, so path tokens like `captures`, `.jpg`, or camera IDs won't trigger false matches
- Examples: `traffic`, `seattle`, `wildfire`, `beach`, `mountain`, `nyc`

## Camera Sources

### Curated Seeds (200+ entries)

- USGS volcanoes (Kīlauea, Mauna Loa, Mt. Rainier, Mt. St. Helens)
- NOAA weather & buoy cams  
- FAA aviation weather (150+ Alaska + 25 CONUS airports)
- EarthCam landmarks (Times Square, Brooklyn Bridge, Eiffel Tower, Tokyo Shibuya, etc.)
- SkylineWebcams (100+ tourism cameras)
- Washington State DOT WSDOT traffic cams
- Street surveillance (NYC TMC, Chicago CDOT, DC, Las Vegas, New Orleans, Boston, San Francisco)

### Live API Crawlers

- **Caltrans** – California DOT (400+ traffic cameras)
- **DOT State Systems** – Wyoming, Colorado, Oregon, Nevada, Minnesota, Utah traffic cameras  
- **Surveillance & City Traffic** – NYC TMC, Chicago CDOT, Maryland SHA, Houston TranStar, 511VA, 511PA, 511GA, FL511, MI Drive, MoDOT, 511NJ (11 city/state DOT systems)
- **FAA Aviation** – Live airport weather cams from 150+ locations
- **AlertWildfire** – Wildfire monitoring cameras (CA, NV, OR, WA)
- **EarthCam / SkylineWebcams / Roundshot** – Webcam directory aggregators

### Browser Crawler (Optional)

- **Playwright headless browser** – Auto-accept GDPR/cookie/ToS consent banners, extract images from JS-rendered pages
- Targets: 511NY, SkylineWebcams, EarthCam, OpenRailCam (disabled by default; slow but useful for consent-gated sites)
- Install with: `pip install playwright && playwright install chromium --with-deps`
- Enable in code by uncommenting browser crawler task in `agent/crawler.py`

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/search` | Run discovery search `{"keyword":"optional"}` |
| GET | `/api/cameras` | Query DB `?q=&state=&feed_type=&page=&per_page=` |
| GET | `/api/stats` | DB statistics |
| GET | `/api/searches` | Recent search history |

## Database

`cameras.db` (SQLite) — columns:

| Column | Description |
|--------|-------------|
| `title` | Camera name |
| `url` | Camera page link |
| `image_url` | Direct image/snapshot URL |
| `feed_type` | `image`, `mjpeg`, `hls`, `embed` |
| `location` | Human-readable location |
| `country` / `state` / `city` | Geographic metadata |
| `latitude` / `longitude` | Coordinates |
| `site_name` | Hosting organisation |
| `description` | Description |
| `tags` | Comma-separated tags |
| `source` | Source domain |
| `keywords` | Full-text search keywords |
| `status` | `unknown` / `online` / `offline` — validated by periodic HEAD request |
| `discovered_at` | ISO timestamp when camera was first discovered |

Full-text search is powered by SQLite FTS5.

## Camera Sources

| Source | Type | Count |
|--------|------|-------|
| FAA Aviation Weather Cameras | Public API (Alaska + CONUS) | 120+ |
| AlertWildfire | Public API | 200–600+ (when available) |
| Caltrans (CA DOT) | Public API | 400+ (when available) |
| EarthCam | Web scrape | varies |
| SkylineWebcams | Web scrape | varies |
| WebcamTaxi | Web scrape | varies |
| Curated seeds | Pre-validated | 50+ |
| WSDOT | Direct snapshot URLs | 15+ |
| NPS National Parks | Direct snapshot URLs | 6+ |

## Project Structure

```
public_cameras/
├── app.py                  # Flask application
├── agent/
│   ├── crawler.py          # Orchestrator
│   ├── db.py               # SQLite layer
│   └── sources/
│       ├── seeds.py        # 179+ pre-validated camera entries
│       ├── faa.py          # FAA Aviation Weather Cameras
│       ├── alertwildfire.py# AlertWildfire public API
│       ├── caltrans.py     # Caltrans CA DOT
│       ├── dotcams.py      # State DOT systems (WY/CO/OR/UT/MN/NV)
│       └── aggregators.py  # EarthCam/SkylineWebcams/WebcamTaxi scrapers
├── templates/index.html
├── static/
│   ├── style.css
│   └── app.js
├── requirements.txt
├── run.cmd
└── start.ps1
```
