"""FastAPI REST + MJPEG frame API consumed by the Flutter management UI."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

import cv2
from fastapi import Depends, FastAPI, Header, HTTPException, Response
from fastapi.middleware.cors import CORSMiddleware

if TYPE_CHECKING:
    from .main import CameraBridge

LOGGER = logging.getLogger(__name__)

_bridge: "CameraBridge | None" = None


def _require_key(x_api_key: str | None = Header(default=None)) -> None:
    key = _bridge.settings.api_key if _bridge else None
    if key and x_api_key != key:
        raise HTTPException(401, "Invalid or missing X-API-Key header")


def create_app(bridge: "CameraBridge") -> FastAPI:
    global _bridge
    _bridge = bridge

    if not bridge.settings.api_key:
        LOGGER.warning(
            "API_KEY is not set; management API is unprotected on the local network",
            extra={"event": "api_no_auth"},
        )

    app = FastAPI(title="Camera Bridge", version="1.0", docs_url="/api/docs")
    app.add_middleware(
        CORSMiddleware,
        # TODO: restrict allow_origins to known UI origins before public deployment
        allow_origins=["*"],
        allow_methods=["GET", "POST", "PUT"],
        allow_headers=["*"],
    )

    @app.get("/api/health")
    async def health() -> dict[str, Any]:
        return {
            "status": "ok",
            "streaming": _bridge.stream.running if _bridge else False,
            "recording": _bridge.capture.recording if _bridge else False,
            "rtspUrl": _bridge.settings.effective_camera_source if _bridge else None,
        }

    @app.put("/api/camera/rtsp-url", dependencies=[Depends(_require_key)])
    async def set_rtsp_url(body: dict[str, Any]) -> dict[str, Any]:
        """Hot-swap the RTSP URL without restarting the process."""
        if not _bridge:
            raise HTTPException(503, "Bridge not initialised")
        url = body.get("url", "").strip()
        if not url.startswith(("rtsp://", "rtsps://", "0", "/dev/")):
            raise HTTPException(400, "url must be an RTSP URL or device path")
        _bridge.capture.close()
        _bridge.capture.device_path = url
        _bridge.stream.device_path = url
        import os
        os.environ["RTSP_URL"] = url
        return {"rtspUrl": url, "status": "applied"}

    @app.get("/api/camera/rtsp-probe")
    async def rtsp_probe(ip: str, port: int = 554, path: str = "/ch0_0.h264") -> dict[str, Any]:
        """Quick reachability check for an RTSP URL — no auth bypass, no brute-force."""
        import asyncio
        url = f"rtsp://{ip}:{port}{path}"
        try:
            def _check() -> bool:
                cap = cv2.VideoCapture(url, cv2.CAP_ANY)
                # 5-second open timeout instead of the 30-second default
                cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 5000)
                cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000)
                opened = cap.isOpened()
                cap.release()
                return opened
            opened = await asyncio.wait_for(asyncio.to_thread(_check), timeout=7.0)
            return {"url": url, "reachable": opened}
        except Exception as exc:
            return {"url": url, "reachable": False, "error": str(exc)}

    @app.get("/api/device", dependencies=[Depends(_require_key)])
    async def device_info() -> dict[str, Any]:
        if not _bridge:
            raise HTTPException(503, "Bridge not initialised")
        return _bridge.device.to_dict()

    @app.post("/api/stream/start", dependencies=[Depends(_require_key)])
    async def start_stream() -> dict[str, Any]:
        if not _bridge:
            raise HTTPException(503)
        return await _bridge.start_stream({})

    @app.post("/api/stream/stop", dependencies=[Depends(_require_key)])
    async def stop_stream() -> dict[str, Any]:
        if not _bridge:
            raise HTTPException(503)
        return await _bridge.stop_stream({})

    @app.post("/api/snapshot", dependencies=[Depends(_require_key)])
    async def snapshot() -> dict[str, Any]:
        if not _bridge:
            raise HTTPException(503)
        return await _bridge.capture_snapshot({})

    @app.get("/api/stream/frame")
    async def frame(x_api_key: str | None = Header(default=None)) -> Response:
        """Single JPEG frame; polled by Flutter live-view at ~10 fps."""
        _require_key(x_api_key)
        if not _bridge:
            raise HTTPException(503, "Bridge not initialised")
        src = _bridge.settings.effective_camera_source
        # Block PC webcam (index 0) — require an explicit RTSP URL
        if not src.startswith(("rtsp://", "rtsps://", "/dev/")):
            raise HTTPException(503, "No RTSP camera configured. Use WiFi Camera Setup.")
        try:
            import asyncio

            raw = await asyncio.to_thread(_bridge.capture.read_frame)
            _, buf = cv2.imencode(".jpg", raw, [cv2.IMWRITE_JPEG_QUALITY, 75])
            return Response(content=buf.tobytes(), media_type="image/jpeg")
        except Exception as exc:
            raise HTTPException(503, str(exc)) from exc

    @app.get("/api/camera/onvif-probe")
    async def onvif_probe(ip: str, port: int = 8000) -> dict[str, Any]:
        """ONVIF GetCapabilities SOAP probe — returns RTSP port hint if found."""
        import re, urllib.request
        soap = (
            '<?xml version="1.0"?>'
            '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">'
            '<s:Body><GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl">'
            "<Category>All</Category></GetCapabilities></s:Body></s:Envelope>"
        )
        hdrs = {"Content-Type": "application/soap+xml; charset=utf-8",
                "SOAPAction": '"http://www.onvif.org/ver10/device/wsdl/GetCapabilities"'}
        try:
            req = urllib.request.Request(
                f"http://{ip}:{port}/onvif/device_service",
                data=soap.encode(), headers=hdrs, method="POST"
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                body = resp.read(4096).decode(errors="replace")
            m = re.search(r"(\d{1,5})</(?:[^:]+:)?RtspPort>", body, re.IGNORECASE)
            rtsp_port = int(m.group(1)) if m else None
            return {"ip": ip, "port": port, "onvif": True, "rtspPort": rtsp_port, "snippet": body[:600]}
        except Exception as exc:
            return {"ip": ip, "port": port, "onvif": False, "error": str(exc)}

    @app.get("/api/camera/qr-connect")
    async def qr_connect_image(camera_ip: str = "10.0.0.161", rtsp_port: int = 554) -> Response:
        """Return a PNG QR code encoding the gateway + camera connection JSON."""
        import io, json, socket
        try:
            import qrcode
        except ImportError:
            raise HTTPException(503, "qrcode package not installed")

        def _local_ip() -> str:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.connect(("10.0.0.1", 1))
                return s.getsockname()[0]
            finally:
                s.close()

        gw_ip = _local_ip()
        payload = json.dumps(
            {
                "gateway": f"http://{gw_ip}:8080",
                "camera": camera_ip,
                "cameraPort": 8000,
                "model": "YI-YHS3017",
                "rtsp": f"rtsp://{camera_ip}/ch0_0.h264",
            },
            separators=(",", ":"),
        )
        qr = qrcode.QRCode(
            error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=10, border=4
        )
        qr.add_data(payload)
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return Response(content=buf.getvalue(), media_type="image/png")

    @app.post("/api/camera/register-qr")
    async def register_qr(body: dict[str, Any]) -> dict[str, Any]:
        """Parse a YI camera QR string and locate the camera on the local subnet."""
        import asyncio, base64, ipaddress, socket
        from urllib.parse import parse_qs

        raw = body.get("qr", "").strip()
        if not raw:
            raise HTTPException(400, "qr field required")

        # Parse b=...&s=base64(ssid)&p=obfuscated_password
        params = dict(kv.split("=", 1) for kv in raw.split("&") if "=" in kv)
        try:
            ssid = base64.b64decode(params["s"] + "==").decode("utf-8", errors="replace")
        except Exception:
            ssid = "(unreadable)"

        hint_ip: str | None = body.get("ip")  # optional caller-supplied IP

        def _tcp_open(ip: str, port: int, timeout: float = 0.8) -> bool:
            s = socket.socket()
            s.settimeout(timeout)
            ok = s.connect_ex((ip, port)) == 0
            s.close()
            return ok

        # If caller supplied an IP hint, trust it; otherwise scan /24 for port 8000
        found_ip: str | None = None
        if hint_ip:
            found_ip = hint_ip if _tcp_open(hint_ip, 8000) else None
        else:
            try:
                local_ip = socket.gethostbyname(socket.gethostname())
                net = ipaddress.IPv4Interface(f"{local_ip}/24").network
                candidates = [str(h) for h in net.hosts()]
            except Exception:
                candidates = [f"10.0.0.{i}" for i in range(1, 255)]

            async def _scan_one(ip: str) -> str | None:
                ok = await asyncio.to_thread(_tcp_open, ip, 8000, 0.6)
                return ip if ok else None

            results = await asyncio.gather(*(_scan_one(ip) for ip in candidates))
            hits = [r for r in results if r]
            found_ip = hits[0] if hits else None

        registration = {
            "ssid": ssid,
            "cameraIp": found_ip,
            "port": 8000,
            "rtspCandidates": (
                [
                    f"rtsp://{found_ip}/ch0_0.h264",
                    f"rtsp://{found_ip}/ch0_1.h264",
                    f"rtsp://{found_ip}/ch0_0.h264",  # alias
                ]
                if found_ip
                else []
            ),
            "note": (
                "Camera found on local network."
                if found_ip
                else "Camera not found — ensure it is on the same subnet."
            ),
        }

        # Hot-apply first RTSP candidate if bridge is available
        if found_ip and _bridge:
            candidate = registration["rtspCandidates"][0]
            _bridge.capture.device_path = candidate
            _bridge.stream.device_path = candidate
            import os
            os.environ["RTSP_URL"] = candidate
            registration["rtspApplied"] = candidate

        return registration

    @app.post("/api/recording/start", dependencies=[Depends(_require_key)])
    async def start_recording() -> dict[str, Any]:
        if not _bridge:
            raise HTTPException(503)
        return await _bridge.start_recording({})

    @app.post("/api/recording/stop", dependencies=[Depends(_require_key)])
    async def stop_recording() -> dict[str, Any]:
        if not _bridge:
            raise HTTPException(503)
        return await _bridge.stop_recording({})

    @app.get("/api/recordings", dependencies=[Depends(_require_key)])
    async def list_recordings() -> dict[str, Any]:
        if not _bridge:
            raise HTTPException(503)
        directory = _bridge.settings.snapshot_directory
        files = sorted(
            directory.glob("recording-*.avi"),
            key=lambda p: p.stat().st_mtime,
            reverse=True,
        )
        return {
            "recordings": [
                {"name": f.name, "bytes": f.stat().st_size} for f in files[:50]
            ]
        }

    return app
