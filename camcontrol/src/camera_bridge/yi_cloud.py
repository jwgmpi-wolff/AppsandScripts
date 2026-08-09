"""
YI Cloud API client — authenticates with us.laikuai.com and pulls camera streams
without requiring the YI app at runtime.

Architecture:
  1. Login once with YI account credentials → get access token
  2. Get camera device ID from device list
  3. Use device ID to get streaming URL / P2P token
  4. Feed stream into the camera gateway

Security: credentials are NEVER stored to disk or git.
           Pass via env vars: YI_EMAIL, YI_PASSWORD (or provide at runtime).
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
import subprocess
import time
from typing import Any

LOGGER = logging.getLogger(__name__)

# YI Cloud API endpoints to try in order
_ENDPOINTS = [
    "https://us.laikuai.com",
    "https://ys.laikuai.com",
]
APP_ID = "com.yi.android"

_session: dict[str, Any] = {}


# ── Low-level HTTP helper ─────────────────────────────────────────────────────
# Python's ssl stack sends SNI which YI's AWS Global Accelerator rejects.
# curl uses a different TLS stack and handles it correctly.

_CURL = shutil.which("curl")

_BASE_HEADERS = [
    "Content-Type: application/json",
    "Accept: application/json",
    "User-Agent: YIHome/5.3 (Android; SDK 30)",
]


def _curl_request(method: str, url: str, body: dict | None = None,
                  token: str | None = None) -> dict:
    """HTTP via curl subprocess — bypasses Python TLS SNI issue entirely."""
    if not _CURL:
        return {"error": "curl not found on PATH"}
    cmd = [_CURL, "-sk", "-X", method, "--max-time", "20"]
    for h in _BASE_HEADERS:
        cmd += ["-H", h]
    if token:
        cmd += ["-H", f"Authorization: Bearer {token}"]
    if body:
        cmd += ["-d", json.dumps(body, separators=(",", ":"))]
    cmd.append(url)
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=25)
        raw = result.stdout.strip()
        if not raw:
            err = result.stderr.strip()[:300]
            LOGGER.error("curl empty response: %s", err)
            return {"error": f"curl: {err}"}
        parsed = json.loads(raw)
        # Normalise: treat non-zero YI error codes as errors
        code = parsed.get("code", parsed.get("status", 0))
        if code not in (0, 200, None, ""):
            return {"error": code, "message": parsed.get("msg", parsed.get("message", raw[:200]))}
        return parsed
    except json.JSONDecodeError:
        LOGGER.error("curl non-JSON: %s", result.stdout[:300])
        return {"error": "non-json", "raw": result.stdout[:300]}
    except Exception as exc:
        LOGGER.error("curl request failed: %s", exc)
        return {"error": str(exc)}


def _request(method: str, path: str, body: dict | None = None,
             token: str | None = None, base_url: str = _ENDPOINTS[0]) -> dict:
    return _curl_request(method, f"{base_url}{path}", body=body, token=token)


# ── Auth ─────────────────────────────────────────────────────────────────────

# YI uses Google Sign-In. The app exchanges a Google id_token for a YI session token.
_GOOGLE_LOGIN_PATHS = [
    "/v1/user/google_signin",
    "/v1/oauth/google",
    "/v1/user/social_login",
    "/v2/user/google_login",
    "/v1/user/login",
]


def login_google(google_id_token: str) -> dict[str, Any]:
    """Exchange a Google id_token for a YI cloud session token."""
    last_err = "unknown"
    bodies = [
        {"id_token": google_id_token, "app_id": APP_ID},
        {"token": google_id_token, "type": "google", "app_id": APP_ID},
        {"google_token": google_id_token, "app_id": APP_ID},
        {"grant_type": "google", "id_token": google_id_token},
        {"access_token": google_id_token, "provider": "google"},
    ]
    for base in _ENDPOINTS:
        for path in _GOOGLE_LOGIN_PATHS:
            for body in bodies:
                resp = _request("POST", path, body, base_url=base)
                if "error" not in resp:
                    data = resp.get("data", resp)
                    token = (data.get("token") or data.get("access_token")
                             or data.get("sessionId") or data.get("session_token"))
                    user_id = data.get("user_id") or data.get("uid") or data.get("userId")
                    if token:
                        _session.update({"token": token, "user_id": user_id,
                                         "login_at": time.time(), "base": base})
                        LOGGER.info("YI Cloud Google login OK via %s%s", base, path)
                        return {"ok": True, "token": token, "user_id": user_id}
                    last_err = f"No token: {base}{path}: {str(resp)[:200]}"
                else:
                    last_err = f"{base}{path}: {resp.get('message', resp.get('error', ''))}"
    return {"ok": False, "error": last_err}


def login(email: str, password: str) -> dict[str, Any]:
    """Try password login (legacy) — most YI accounts now require Google auth."""
    pw_variants = [
        hashlib.md5(password.encode()).hexdigest(),
        password,
        hashlib.sha256(password.encode()).hexdigest(),
    ]
    last_err = "unknown"
    paths = ["/v1/user/signin", "/v2/user/login", "/v1/account/login"]

    for base in _ENDPOINTS:
        for pw in pw_variants:
            for path in paths:
                for body in [
                    {"username": email, "password": pw, "app_id": APP_ID},
                    {"email": email, "password": pw, "app_id": APP_ID},
                    {"account": email, "password": pw},
                ]:
                    resp = _request("POST", path, body, base_url=base)
                    if "error" not in resp:
                        data = resp.get("data", resp)
                        token = (data.get("token") or data.get("access_token")
                                 or data.get("sessionId"))
                        user_id = data.get("user_id") or data.get("uid") or data.get("userId")
                        if token:
                            _session.update({"token": token, "user_id": user_id,
                                             "login_at": time.time(), "base": base})
                            LOGGER.info("YI Cloud login OK via %s%s user=%s", base, path, user_id)
                            return {"ok": True, "token": token, "user_id": user_id}
                        last_err = f"No token in response from {base}{path}: {str(resp)[:200]}"
                    else:
                        last_err = f"{base}{path}: {resp.get('message', resp.get('error', ''))}"

    LOGGER.error("All login attempts failed. Last: %s", last_err)
    return {"ok": False, "error": last_err}


def _token() -> str | None:
    return _session.get("token")


def _base() -> str:
    return _session.get("base", _ENDPOINTS[0])


# ── Devices ──────────────────────────────────────────────────────────────────

def get_devices() -> list[dict]:
    """Return list of cameras registered to the account."""
    t = _token()
    if not t:
        return []
    resp = _request("GET", "/v1/user/devices", token=t, base_url=_base())
    devices = resp.get("data", resp) if "error" not in resp else []
    if isinstance(devices, list):
        return devices
    # Sometimes wrapped in a dict
    return devices.get("devices", devices.get("device_list", []))


def find_camera(mac: str | None = None, ip: str | None = None) -> dict | None:
    """Find camera by MAC address or IP. Returns device dict or None."""
    for dev in get_devices():
        dev_mac = (dev.get("mac") or "").replace(":", "").lower()
        if mac and dev_mac == mac.replace(":", "").lower():
            return dev
        if ip and dev.get("ip") == ip:
            return dev
    return None


# ── Streaming ────────────────────────────────────────────────────────────────

def get_stream_url(device_id: str, channel: int = 0) -> dict:
    """Get live stream URL for a camera. Returns RTSP or HLS URL if available."""
    t = _token()
    if not t:
        return {"error": "Not authenticated"}

    # Try direct stream URL first
    resp = _request("GET", f"/v1/device/stream?device_id={device_id}&channel={channel}", token=t)
    if "error" not in resp:
        data = resp.get("data", resp)
        url = data.get("url") or data.get("rtsp_url") or data.get("hls_url") or data.get("stream_url")
        if url:
            return {"ok": True, "url": url, "device_id": device_id}

    # Try P2P token endpoint
    resp2 = _request("POST", "/v1/device/p2p_token", {"device_id": device_id, "channel": channel}, token=t)
    if "error" not in resp2:
        data2 = resp2.get("data", resp2)
        return {"ok": True, "p2p_token": data2.get("token"), "did": data2.get("did"), "raw": data2}

    return {"ok": False, "raw_stream": resp, "raw_p2p": resp2}


def get_snapshot(device_id: str) -> bytes | None:
    """Get a JPEG snapshot from the camera via YI cloud."""
    t = _token()
    if not t:
        return None
    resp = _request("GET", f"/v1/device/snapshot?device_id={device_id}", token=t)
    if "error" in resp:
        return None
    url = (resp.get("data") or resp).get("url")
    if not url:
        return None
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            return r.read()
    except Exception as exc:
        LOGGER.error("Snapshot download failed: %s", exc)
        return None


# ── Convenience ───────────────────────────────────────────────────────────────

def status() -> dict:
    if not _session.get("token"):
        return {"authenticated": False}
    age = int(time.time() - _session.get("login_at", 0))
    return {"authenticated": True, "user_id": _session.get("user_id"), "token_age_s": age}


def login_from_env() -> dict:
    """Login using YI_EMAIL and YI_PASSWORD environment variables."""
    email = os.environ.get("YI_EMAIL", "")
    password = os.environ.get("YI_PASSWORD", "")
    if not email or not password:
        return {"ok": False, "error": "YI_EMAIL and YI_PASSWORD not set"}
    return login(email, password)


def get_google_token_url() -> str:
    """Return the URL the user should visit to get their Google id_token for YI."""
    # YI Home Android app uses this Google OAuth client for sign-in.
    # The user visits this URL, signs in with Google, then copies the id_token
    # from the resulting URL fragment or the token field shown.
    client_id = os.environ.get(
        "YI_GOOGLE_CLIENT_ID",
        "870314384488-qr2m6qnkdcqia2e8l8sdmf7p0tqhj1ll.apps.googleusercontent.com",
    )
    redirect = "http://localhost:8765/oauth/callback"
    scope = "openid email profile"
    return (
        f"https://accounts.google.com/o/oauth2/v2/auth"
        f"?client_id={client_id}"
        f"&redirect_uri={redirect}"
        f"&response_type=id_token"
        f"&scope={scope.replace(' ', '+')}"
        f"&nonce=camcontrol"
    )


def capture_google_token_via_browser(timeout: int = 120) -> dict:
    """Open browser for Google OAuth and capture the id_token via local callback."""
    import urllib.parse
    import webbrowser
    from http.server import BaseHTTPRequestHandler, HTTPServer

    captured: dict = {}

    class _Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass  # suppress access logs

        def do_GET(self):
            # Google returns token in fragment (#id_token=...) which never reaches server.
            # Serve a tiny page that reads the fragment and POSTs it back.
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(b"""<html><body>
<script>
var p=new URLSearchParams(location.hash.slice(1));
var t=p.get('id_token');
if(t){fetch('/token',{method:'POST',body:t}).then(()=>{document.body.innerHTML='<h2>Token captured! You can close this tab.</h2>'});}
else{document.body.innerHTML='<h2>No token found. Try again.</h2>';}
</script><p>Capturing token...</p></body></html>""")

        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            token = self.rfile.read(length).decode()
            captured["id_token"] = token
            self.send_response(200)
            self.end_headers()

    server = HTTPServer(("localhost", 8765), _Handler)
    server.timeout = 2
    url = get_google_token_url()
    webbrowser.open(url)
    LOGGER.info("Opened browser for Google OAuth. Waiting for callback...")
    deadline = time.time() + timeout
    while time.time() < deadline and "id_token" not in captured:
        server.handle_request()
    server.server_close()
    if "id_token" not in captured:
        return {"ok": False, "error": "Timed out waiting for Google OAuth callback"}
    return login_google(captured["id_token"])
