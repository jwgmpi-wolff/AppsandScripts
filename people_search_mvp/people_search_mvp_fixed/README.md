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
