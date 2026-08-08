"""
Production camera bridge for any confirmed RTSP stream.
Works with any camera exposing a standard RTSP endpoint on your local network
(native vendor stream, ONVIF camera, etc.).

DOES NOT:
- Flash firmware
- Modify camera software
- Bypass authentication
- Use default credentials

Run:
    pip install azure-iot-device opencv-python-headless python-dotenv
    cp .env.example .env   # edit with your values
    python camera_bridge.py
"""

from __future__ import annotations

import json
import logging
import os
import signal
import sys
import time
from pathlib import Path

import cv2
from azure.iot.device import IoTHubDeviceClient, Message, MethodResponse
from dotenv import load_dotenv

load_dotenv()
logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format="%(asctime)s [%(levelname)s] %(name)s – %(message)s",
)
LOGGER = logging.getLogger("camera_bridge")

# ── Configuration (all from environment – never hardcoded) ────────────────────
IOTHUB_CONNECTION_STRING = os.getenv("IOTHUB_DEVICE_CONNECTION_STRING")
RTSP_URL = os.getenv("RTSP_URL")                     # e.g. rtsp://192.168.1.x:554/ch0_0.264
SNAPSHOT_DIR = Path(os.getenv("SNAPSHOT_DIR", "./data/snapshots"))
TELEMETRY_INTERVAL_S = int(os.getenv("TELEMETRY_INTERVAL_SECONDS", "30"))


class CameraBridge:
    def __init__(self) -> None:
        if not RTSP_URL:
            LOGGER.error("RTSP_URL is not configured. Run camera discovery first.")
            sys.exit(1)
        self.rtsp_url = RTSP_URL
        self._streaming = False
        self._client: IoTHubDeviceClient | None = None
        self._shutdown = False

    # ── IoT Hub connection ────────────────────────────────────────────────────

    def connect(self) -> None:
        if not IOTHUB_CONNECTION_STRING:
            LOGGER.warning("IOTHUB_DEVICE_CONNECTION_STRING not set; telemetry disabled")
            return
        self._client = IoTHubDeviceClient.create_from_connection_string(
            IOTHUB_CONNECTION_STRING
        )
        self._client.connect()
        self._client.on_method_request_received = self._dispatch_method
        LOGGER.info("Connected to Azure IoT Hub")

    # ── Direct method dispatcher ──────────────────────────────────────────────

    def _dispatch_method(self, request: object) -> None:
        name: str = request.name  # type: ignore[attr-defined]
        LOGGER.info("Direct method: %s", name)
        handlers = {
            "startStream": self._cmd_start_stream,
            "stopStream": self._cmd_stop_stream,
            "captureSnapshot": self._cmd_snapshot,
            "getDeviceStatus": self._cmd_status,
            "setRecordingDestination": self._cmd_set_recording_dest,
            "setStreamDestination": self._cmd_set_stream_dest,
        }
        handler = handlers.get(name)
        if handler is None:
            self._reply(request, 404, {"error": f"Unknown method: {name}"})
            return
        try:
            result = handler(getattr(request, "payload", None) or {})
            self._reply(request, 200, result)
        except ValueError as exc:
            self._reply(request, 400, {"error": str(exc)})
        except Exception as exc:
            LOGGER.exception("Method %s failed", name)
            self._reply(request, 500, {"error": "Command failed; check gateway logs"})

    def _reply(self, request: object, status: int, payload: dict) -> None:
        if self._client:
            resp = MethodResponse.create_from_method_request(request, status, payload)  # type: ignore[arg-type]
            self._client.send_method_response(resp)

    # ── Command handlers ──────────────────────────────────────────────────────

    def _cmd_start_stream(self, _payload: dict) -> dict:
        self._streaming = True
        LOGGER.info("Stream activated for %s", _redact(self.rtsp_url))
        return {"streaming": True, "rtspEndpoint": _redact(self.rtsp_url)}

    def _cmd_stop_stream(self, _payload: dict) -> dict:
        self._streaming = False
        return {"streaming": False}

    def _cmd_snapshot(self, _payload: dict) -> dict:
        cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_ANY)
        ok, frame = cap.read()
        cap.release()
        if not ok or frame is None:
            raise ValueError("Camera did not return a frame")
        SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)
        ts = int(time.time())
        path = SNAPSHOT_DIR / f"snapshot-{ts}.jpg"
        cv2.imwrite(str(path), frame)
        LOGGER.info("Snapshot saved: %s", path)
        return {"path": str(path), "timestamp": ts}

    def _cmd_status(self, _payload: dict) -> dict:
        cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_ANY)
        reachable = cap.isOpened()
        cap.release()
        return {
            "deviceId": "YHS.3017",
            "cameraReachable": reachable,
            "streaming": self._streaming,
            "rtspEndpoint": _redact(self.rtsp_url),
            "timestamp": int(time.time()),
        }

    def _cmd_set_recording_dest(self, payload: dict) -> dict:
        dest = payload.get("destination")
        if dest not in {"local", "nas", "azure"}:
            raise ValueError("destination must be local, nas, or azure")
        LOGGER.info("Recording destination set to: %s", dest)
        return {"recordingDestination": dest}

    def _cmd_set_stream_dest(self, payload: dict) -> dict:
        dest = payload.get("destination")
        LOGGER.info("Stream destination set to: %s", dest)
        return {"streamDestination": dest}

    # ── Telemetry loop ────────────────────────────────────────────────────────

    def run_telemetry_loop(self) -> None:
        LOGGER.info("Starting telemetry loop every %d s", TELEMETRY_INTERVAL_S)
        while not self._shutdown:
            cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_ANY)
            online = cap.isOpened()
            cap.release()

            payload = {
                "deviceId": "YHS.3017_Outdoor",
                "cameraOnline": online,
                "streaming": self._streaming,
                "rtspEndpoint": _redact(self.rtsp_url),
                "timestamp": int(time.time()),
            }
            LOGGER.info("Telemetry → Azure: online=%s streaming=%s", online, self._streaming)
            if self._client:
                msg = Message(json.dumps(payload))
                msg.content_type = "application/json"
                msg.content_encoding = "utf-8"
                self._client.send_message(msg)

            time.sleep(TELEMETRY_INTERVAL_S)

    def shutdown(self) -> None:
        self._shutdown = True
        if self._client:
            self._client.disconnect()


def _redact(url: str) -> str:
    import re
    return re.sub(r"://([^:@]+):([^@]+)@", r"://\1:***@", url)


if __name__ == "__main__":
    bridge = CameraBridge()
    signal.signal(signal.SIGINT, lambda *_: bridge.shutdown())
    signal.signal(signal.SIGTERM, lambda *_: bridge.shutdown())
    bridge.connect()
    bridge.run_telemetry_loop()
