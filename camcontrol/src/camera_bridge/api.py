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
        allow_methods=["GET", "POST"],
        allow_headers=["*"],
    )

    @app.get("/api/health")
    async def health() -> dict[str, Any]:
        return {
            "status": "ok",
            "streaming": _bridge.stream.running if _bridge else False,
            "recording": _bridge.capture.recording if _bridge else False,
        }

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
        try:
            import asyncio

            raw = await asyncio.to_thread(_bridge.capture.read_frame)
            _, buf = cv2.imencode(".jpg", raw, [cv2.IMWRITE_JPEG_QUALITY, 75])
            return Response(content=buf.tobytes(), media_type="image/jpeg")
        except Exception as exc:
            raise HTTPException(503, str(exc)) from exc

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
