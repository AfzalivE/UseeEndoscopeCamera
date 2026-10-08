# Endoscope Camera for Android

An Android app for the cheap "Geek szitman supercamera" USB endoscopes (sold with the *Usee Plus* app).
It is a Kotlin port of [jmz3/EndoscopeCamera](https://github.com/jmz3/EndoscopeCamera), which talks to the camera with libusb on Linux.

These cameras are **not UVC cameras**. They use a vendor protocol (`com.useeplus.protocol`) on two bulk USB interfaces, so normal UVC libraries cannot open them. This app speaks that protocol directly through the Android USB host API.

## Features

- Live preview (MJPEG frames, 640×480 on tested models) with FPS readout.
- **Photo**: saves the original JPEG to `Pictures/Endoscope`. With rotation, mirror or zoom on, it saves what you see.
- **Video**: records H.264 MP4 to `Movies/Endoscope`.
- **Hardware button** on the endoscope: short press takes a photo, long press (1 s) switches the lens, same as the original PoC.
- **Lens switch** for dual-lens models (`cam_num` 0 and 1 in the protocol).
- Rotate (90° steps), mirror, flip and digital zoom (slider or pinch, up to 4×).
- **Wi-Fi stream**: a TCP server on port 9000 that uses the repository's *Supercamera Stream Protocol v1*, so `scripts/stream_receiver.py` and `scripts/tcp_stream_to_ros.py` from the original repository can show the feed on a computer:
  ```bash
  python3 scripts/stream_receiver.py --host <phone-ip> --port 9000
  ```
  The phone IP shows on screen. Each lens is sent as its own `source_id`.
- Opens automatically when the endoscope is plugged in (USB attach intent filter).

## Supported devices

| Vendor ID | Product ID | Name |
|-----------|------------|------|
| `0x2ce3`  | `0x3828`   | Geek szitman supercamera |
| `0x0329`  | `0x2022`   | Geek szitman supercamera |

Connect the camera with a USB OTG adapter. The phone must support USB host mode. Android 10 (API 29) or later.

## Build

Open the project in Android Studio, or run:

```bash
./gradlew assembleDebug
```

## How it works

1. Claim interface 0 and interface 1 (alternate setting 1), clear halt on EP1.
2. Send `FF 55 FF 55 EE 10` on EP2 OUT and `BB AA 05 00 00` on EP1 OUT to start the stream.
3. Read 1024-byte bulk packets from EP1 IN. Each packet has a 5-byte USB header (`AA BB`, cid 7/11, length) and a 7-byte camera header (frame id, lens number, button bit, g-sensor). The payload is a chunk of a JPEG.
4. A change in frame id ends the current JPEG.

The code for these steps is in `SupercameraConnection.kt` and `UppParser.kt`.

## Code map

| File | Purpose |
|------|---------|
| `SupercameraConnection.kt` | USB setup, start sequence and read thread |
| `UppParser.kt` | Rebuilds JPEG frames from protocol packets |
| `FrameProcessor.kt` | Decodes the newest frame and feeds preview and recorder |
| `PreviewView.kt` | Draws frames with the current rotation, mirror and zoom |
| `VideoRecorder.kt` | MediaCodec + MediaMuxer MP4 recording |
| `StreamServer.kt` | Wi-Fi TCP stream (GSVC protocol) |
| `MediaStoreSaver.kt` | Saves photos and videos to shared storage |
| `MainActivity.kt` | UI, USB permission and attach/detach handling |
