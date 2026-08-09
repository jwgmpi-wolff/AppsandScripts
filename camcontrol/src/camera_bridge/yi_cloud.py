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
import time
import warnings
from typing import Any

LOGGER = logging.getLogger(__name__)

# YI Cloud API endpoints to try in order
_ENDPOINTS = [
    "https://us.laikuai.com",
    "https://ys.laikuai.com",
]
APP_ID = "com.yi.android"

_session: dict[str, Any] = {}


# ── Low-level HTTP helper (uses requests with SSL quirk handling) ─────────────

def _get_session():
    """Return a requests.Session configured to handle YI's TLS quirks."""
    try:
        import requests
        from requests.adapters import HTTPAdapter
        import urllib3
        urllib3.disable_warnings()
        s = requests.Session()
        s.verify = False          # YI servers have SNI issues with standard TLS
        s.headers.update({
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "YIHome/5.3 (Android; SDK 30)",
        })
        return s
    except ImportError:
        return None


def _request(method: str, path: str, body: dict | None = None,
             token: str | None = None, base_url: str = _ENDPOINTS[0]) -> dict:
    sess = _get_session()
    url = f"{base_url}{path}"
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        if sess:
            fn = sess.post if method == "POST" else sess.get
            r = fn(url, json=body, headers=headers, timeout=15)
            if r.ok:
                return r.json()
            LOGGER.error("YI API %s %s → %d: %s", method, path, r.status_code, r.text[:500])
            return {"error": r.status_code, "message": r.text[:300]}
        # Fallback: urllib with SSL context
        import urllib.request, ssl
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        data = json.dumps(body).encode() if body else None
        hdr = {"Content-Type": "application/json", "Accept": "application/json",
               "User-Agent": "YIHome/5.3"}
        if token:
            hdr["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(url, data=data, headers=hdr, method=method)
        with urllib.request.urlopen(req, timeout=15, context=ctx) as r:
            return json.loads(r.read())
    except Exception as exc:
        LOGGER.error("YI API request failed: %s", exc)
        return {"error": str(exc)}


# ── Auth ─────────────────────────────────────────────────────────────────────

def login(email: str, password: str) -> dict[str, Any]:
    """Authenticate with YI cloud. Tries multiple formats and endpoints."""
    # Try MD5 hash first (most common for YI), then plain, then SHA256
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
