# Camera Bridge — Azure IoT Edge USB/UVC Camera Gateway

Owner-authorized edge gateway that reads a standards-based USB/UVC camera feed
and bridges telemetry, health events, and interactive commands to Azure IoT Hub.

**This project does not modify, flash, or replace YI camera firmware, bootloaders,
or vendor software. It reads only from interfaces the camera exposes as a standard
USB video device.**

---

## Supported camera paths

| Path | Requirement |
|------|-------------|
| **USB/UVC** | Camera enumerates as a `/dev/video*` or DirectShow device; verified by OpenCV |
| **RTSP/ONVIF** | Camera advertises an RTSP URL; point `CAMERA_DEVICE_PATH` at that URL |
| **Unsupported** | If neither applies, select a camera that exposes a standard interface |

---

## Prerequisites

| Tool | Version |
|------|---------|
| Python | 3.11 + |
| pip / uv | latest |
| Azure IoT Hub | with a registered device or IoT Edge device identity |
| Docker | 24 + (for container/Edge deployment) |
| Azure IoT Edge runtime | 1.5 + (Edge module deployment only) |

Linux only — for the RTSP stream server:
```
sudo apt-get install gstreamer1.0-tools gstreamer1.0-plugins-good \
  gstreamer1.0-rtsp gir1.2-gst-rtsp-server-1.0 python3-gi python3-gst-1.0
```

---

## Detect attached cameras

**Windows (PowerShell):**
```powershell
.\scripts\detect-usb-camera.ps1
```

**Linux:**
```bash
chmod +x scripts/detect-usb-camera.sh
./scripts/detect-usb-camera.sh
```

Confirm the device is recognized by OpenCV:
```bash
python -m camera_bridge.main --detect-only
```

---

## Local run (no container)

```bash
# 1. Create virtual environment and install dependencies
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/macOS:
source .venv/bin/activate

pip install -r requirements.txt

# 2. Copy and populate the environment file
cp .env.example .env
# Edit .env – add IOTHUB_DEVICE_CONNECTION_STRING and CAMERA_DEVICE_PATH

# 3. Run
python -m camera_bridge.main
```

---

## Run tests

```bash
$env:PYTHONPATH = "src"   # Windows PowerShell
# or
export PYTHONPATH=src      # Linux/macOS

python -m pytest -q
```

---

## Docker build and run

```bash
# Build
docker build -t camera-bridge:latest .

# Run (Linux – pass camera device)
docker run --rm \
  --device /dev/video0 \
  --env-file .env \
  -v /var/camera-bridge/snapshots:/data/snapshots \
  camera-bridge:latest
```

> **Windows:** USB/UVC pass-through to Linux containers requires WSL 2 with
> `usbipd-win`. DirectShow is not available inside Linux containers.
> Use the native Python run path on Windows for development.

---

## Azure IoT Edge deployment

### 1. Build and push the module image

```bash
# TODO: replace with your ACR endpoint
ACR=<yourregistry>.azurecr.io
docker build -t $ACR/camera-bridge:1.0 .
docker push $ACR/camera-bridge:1.0
```

### 2. Set the MODULES variable and deploy

```bash
az iot edge set-modules \
  --hub-name <your-iothub> \
  --device-id <your-edge-device> \
  --content deployment/deployment.template.json
```

Replace `${MODULES.camera-bridge}` in `deployment.template.json` with your
pushed image tag before deploying.

### 3. Pass secrets as Edge module environment variables

In the Azure portal → IoT Hub → IoT Edge → your device → camera-bridge module
→ Environment Variables, add `STREAM_USERNAME` and `STREAM_PASSWORD`.
Do **not** put credentials in `deployment.template.json`.

---

## Direct methods

Call these from the Azure portal, `az iot hub invoke-module-method`, or an
upstream service.

| Method | Payload | Response |
|--------|---------|----------|
| `startStream` | `{}` | `{"streaming": true, "endpoint": {...}}` |
| `stopStream` | `{}` | `{"streaming": false}` |
| `captureSnapshot` | `{}` | `{"path": "...", "timestamp": "..."}` |
| `getDeviceInfo` | `{}` | camera capabilities JSON |
| `setStreamProfile` | `{"width": 1280, "height": 720, "fps": 15}` | applied profile |

---

## Device twin desired properties

```json
{
  "desired": {
    "streamEnabled": false,
    "streamProfile": { "width": 1280, "height": 720, "fps": 15 }
  }
}
```

The module reports a `configurationStatus` reported property after every patch.

---

## Telemetry events sent to IoT Hub

| `event` field | Trigger |
|---------------|---------|
| `cameraHealth` | Every 30 s |
| `snapshotCaptured` | After `captureSnapshot` |
| `directMethodAudit` | After every direct method call |

Raw video frames are **never** sent to IoT Hub. Use the RTSP stream endpoint
for live video consumption on a trusted local network.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `No compatible UVC/OpenCV video interface` | Run `--detect-only`; confirm device appears in OS device list |
| `GStreamer RTSP bindings are unavailable` | Install `gir1.2-gst-rtsp-server-1.0` and `python3-gi` |
| `RTSP streaming requires username and password` | Set `STREAM_USERNAME` and `STREAM_PASSWORD` |
| IoT Hub connection timeout | Verify `IOTHUB_DEVICE_CONNECTION_STRING` or Edge module identity |
| `Device does not expose an OpenCV-compatible video interface` | Camera is not a UVC device; use RTSP/ONVIF URL instead |
| Snapshot write fails | Confirm `SNAPSHOT_DIRECTORY` is writable inside container |
