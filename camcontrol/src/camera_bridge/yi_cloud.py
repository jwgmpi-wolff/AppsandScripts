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
