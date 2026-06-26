# Public Camera Intelligence Agent

Discovers, stores, and browses publicly accessible webcam feeds — no login required.

## Features

- **200+ cameras on first search** — seed list pre-loaded with FAA, NPS, EarthCam, WSDOT, and more
- **Live discovery** from AlertWildfire, Caltrans, state DOT systems, and web scrapers
- **Keyword search** — filter by location, site name, tags, country, state
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

Then open **http://localhost:5000**

## Usage

1. Click **Search** to run a full discovery (loads 1000+ cameras the first time)
2. Optionally type a keyword (e.g. `alaska`, `traffic`, `wildfire`, `beach`) before clicking Search
3. Use the **Filter DB** bar to search saved cameras by keyword, state, or feed type
4. Enable **Auto-refresh images** to get live snapshots every 30 s
5. Click any camera thumbnail to open it fullscreen with metadata
6. Toggle **Table View** to see all metadata in a sortable table

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
| `status` | `unknown` / `online` / `offline` |
| `discovered_at` | ISO timestamp |

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
