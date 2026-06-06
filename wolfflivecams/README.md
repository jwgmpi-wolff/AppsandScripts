Wolff Live Cams - Live Stream Finder

Local MVP to search for live webcams, live streams, and broadcasts.

Run:

1. Create virtualenv and install dependencies:

```
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

2. Start server:

```
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

3. Open UI: http://localhost:8000/

Notes: This MVP crawls provided seed URLs and extracts links that look like live streams (m3u8, iframe embeds, URLs containing live/stream). It stores successful and failed connection tests in separate SQLite DB files (`successes.db`, `failures.db`).
