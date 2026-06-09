## Public Search Intelligence Prototype (Patched)

This folder contains a low-cost, local prototype for:
- a search UI with navigation and public-link browsing
- SQLite-backed result storage
- simple relevance-learning weights that improve future ranking
- source selection across multiple external providers
- structured error logging and fetch diagnostics

Important note:
This prototype intentionally avoids invasive personal-data collection and facial-recognition features. It is designed for public, consent-based research and public-source discovery only.

### What changed
- Added provider routing for `duckduckgo`, `brave`, and `all`
- Added request/response logging with exception details
- Added `fetch_errors` and `diagnostics` to the `/search` API response
- Added safer snippet parsing and result de-duplication by URL
- Added defensive validation for `max_results`

### Run
- python -m venv .venv
- .venv\Scripts\activate
- pip install -r requirements.txt
- python app.py

Open [Local app](http://localhost:5000)

### Example requests

PowerShell / curl style form POST:

```bash
curl -X POST http://localhost:5000/search       -d "query=azure ai architecture"       -d "source=all"       -d "max_results=5"
```

Supported values for `source`:
- `all`
- `duckduckgo`
- `brave`

### Webcam / Stream Scanner

From the UI at `/` use the "Webcam / Stream Scanner" panel.
- Paste one URL per line, or a pattern like `https://site.example/cam{1-50}` to expand numeric ranges.
- Use `*` in a pattern and provide replacements (comma or newline separated) to expand values.
- Click "Run scan" — successful checks are stored in the `webcam_success` table, failures in `webcam_failure`.
- Use the "Search saved links" controls to query saved successes or failures, and select rows to open them in new tabs.
- "Clear most recent scan" deletes the last scan and its associated results.

Databases: `search_intelligence.db` (contains search results plus scanner tables `webcam_scans`, `webcam_success`, `webcam_failure`).

Background worker (optional)
- This project supports background scanning via RQ + Redis. To use it, install Redis and the Python requirements (`redis`, `rq`).
- Start a worker from the project folder:

```powershell
# start redis (platform dependent)
redis-server
# in another shell, start an RQ worker
rq worker
```

If Redis/RQ is unavailable the app falls back to a threaded background runner, but RQ provides more reliability and persistence.

CSV import/export
- Use the UI controls under the "Webcam / Stream Scanner" panel to export saved successes/failures as CSV.
- Use the CSV import control to upload a CSV with a header containing `url` and optionally `status_code,content_type,note` for successes or `url,error` for failures.
