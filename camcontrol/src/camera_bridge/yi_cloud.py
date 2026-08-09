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
import urllib.request
import urllib.parse
from typing import Any

LOGGER = logging.getLogger(__name__)

# YI Cloud API base — US region
BASE_URL = "https://us.laikuai.com"
APP_ID = "com.yi.android"
APP_KEY = "24a6bbb0-3b1c-4f5e-8c4d-1b2d4c9f1234"  # public client key from app

_session: dict[str, Any] = {}


# ── Low-level HTTP helper ────────────────────────────────────────────────────

def _request(method: str, path: str, body: dict | None = None, token: str | None = None) -> dict:
    url = f"{BASE_URL}{path}"
    data = json.dumps(body).encode() if body else None
    headers: dict[str, str] = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        body_text = e.read().decode(errors="replace")
        LOGGER.error("YI API %s %s → %d: %s", method, path, e.code, body_text[:500])
        return {"error": e.code, "message": body_text[:200]}
    except Exception as exc:
        LOGGER.error("YI API request failed: %s", exc)
        return {"error": str(exc)}


# ── Auth ─────────────────────────────────────────────────────────────────────

def login(email: str, password: str) -> dict[str, Any]:
    """Authenticate with YI cloud. Returns session dict with token and device list."""
    pw_hash = hashlib.md5(password.encode()).hexdigest()
    resp = _request("POST", "/v1/user/signin", {
        "username": email,
        "password": pw_hash,
        "app_id": APP_ID,
    })
    if "error" in resp:
        return {"ok": False, "error": resp.get("message", resp.get("error"))}

    data = resp.get("data", resp)
    token = data.get("token") or data.get("access_token")
    user_id = data.get("user_id") or data.get("uid")

    if not token:
        LOGGER.error("Login response missing token: %s", resp)
        return {"ok": False, "error": "No token in login response", "raw": resp}

    _session.update({"token": token, "user_id": user_id, "login_at": time.time()})
    LOGGER.info("YI Cloud login OK. user_id=%s", user_id)
    return {"ok": True, "token": token, "user_id": user_id}


def _token() -> str | None:
    return _session.get("token")


# ── Devices ──────────────────────────────────────────────────────────────────

def get_devices() -> list[dict]:
    """Return list of cameras registered to the account."""
    t = _token()
    if not t:
        return []
    resp = _request("GET", "/v1/user/devices", token=t)
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
