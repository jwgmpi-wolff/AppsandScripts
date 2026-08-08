"""Thread-safe OpenCV camera capture operations."""

from __future__ import annotations

import platform
import threading
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import cv2


class CameraCaptureError(RuntimeError):
    pass


class CameraCapture:
    def __init__(self, device_path: str) -> None:
        self.device_path = device_path
        self._capture: cv2.VideoCapture | None = None
        self._lock = threading.RLock()

    def open(self) -> None:
        with self._lock:
            if self._capture and self._capture.isOpened():
                return
            source: str | int = (
                int(self.device_path) if self.device_path.isdigit() else self.device_path
            )
            backend = {
                "Linux": cv2.CAP_V4L2,
                "Windows": cv2.CAP_DSHOW,
            }.get(platform.system(), cv2.CAP_ANY)
            capture = cv2.VideoCapture(source, backend)
            if not capture.isOpened():
                capture.release()
                raise CameraCaptureError(
                    f"Unable to open supported video interface {self.device_path}"
                )
            self._capture = capture

    def close(self) -> None:
        with self._lock:
            if self._capture:
                self._capture.release()
                self._capture = None

    def read_frame(self) -> Any:
        with self._lock:
            self.open()
            assert self._capture is not None
            success, frame = self._capture.read()
            if not success or frame is None:
                raise CameraCaptureError("Camera returned no frame")
            return frame

    def capture_snapshot(self, directory: Path) -> tuple[Path, datetime]:
        frame = self.read_frame()
        timestamp = datetime.now(UTC)
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"snapshot-{timestamp.strftime('%Y%m%dT%H%M%S%fZ')}.jpg"
        if not cv2.imwrite(str(path), frame):
            raise CameraCaptureError(f"Failed to write snapshot to {path}")
        return path, timestamp

    def set_profile(self, width: int, height: int, fps: float) -> dict[str, float | int]:
        if width <= 0 or height <= 0 or fps <= 0:
            raise ValueError("width, height, and fps must be positive")
        with self._lock:
            self.open()
            assert self._capture is not None
            self._capture.set(cv2.CAP_PROP_FRAME_WIDTH, width)
            self._capture.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
            self._capture.set(cv2.CAP_PROP_FPS, fps)
            actual = {
                "width": int(self._capture.get(cv2.CAP_PROP_FRAME_WIDTH)),
                "height": int(self._capture.get(cv2.CAP_PROP_FRAME_HEIGHT)),
                "fps": float(self._capture.get(cv2.CAP_PROP_FPS)),
            }
            if actual["width"] != width or actual["height"] != height:
                raise CameraCaptureError(
                    f"Requested profile is unsupported; device selected {actual}"
                )
            return actual

    def __enter__(self) -> "CameraCapture":
        self.open()
        return self

    def __exit__(self, *_: object) -> None:
        self.close()
