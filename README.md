# Advanced Video Player (Android)

A feature-rich Android video player inspired by VLC, built with **Media3 (ExoPlayer)**. Deep FFprobe analysis is optional (see below).

---

## Features (VLC-like)

### Playback
- Play local video / audio files (MP4, MKV, TS, M2TS, AVI, etc.)
- Play / Pause / Seek
- Double-tap left/right → ±10 seconds
- Horizontal swipe → seek
- Vertical swipe → brightness feedback
- Playback speed: 0.25x → 3.0x
- Repeat modes: Off / One / All
- Aspect ratio modes: Fit / Fill / Zoom / Stretch

### Tracks
- **Audio track selection** (with codec & channel info)
- **Subtitle track selection** (including Off)

### Metadata & Special Formats
- Stream info (title, resolution, video/audio codec, duration, bitrate, container, HDR) from three independent sources: ExoPlayer, Android MediaExtractor, optional FFprobe
- Automatic detection + logo badges for:
  - Dolby Vision (with profile)
  - Dolby Atmos
  - Dolby TrueHD
  - DTS / DTS-HD / **DTS:X**
  - HDR10 / HLG
  - AV1 / HEVC
- Side panel with full media information

### UI
- Modern dark Material 3 interface
- Auto-hiding controls
- Gesture feedback overlays
- Open files from other apps

---

## Important Limitations (same as VLC on Android)

| Feature                        | Status                          |
|--------------------------------|---------------------------------|
| DTS:X / TrueHD **detection**   | Excellent                       |
| DTS:X / TrueHD **playback**    | Not possible on most phones     |
| Bit-perfect output             | Not possible on Android         |
| 24-bit lossless detection      | Yes                             |
| 24-bit lossless playback       | Partial (device dependent)      |

---

## Requirements

- Android Studio Ladybug (2024.2+) or newer
- JDK 17
- Device / emulator with API 26+

---

## How to Build

Open the `AdvancedVideoPlayer` folder in Android Studio (Ladybug+, JDK 17) and run. There is no Gradle
wrapper in the project: Android Studio creates one, or run `gradle wrapper` once, then
`./gradlew assembleDebug` and `./gradlew testDebugUnitTest`.

---

## Build from GitHub

1. Create an empty repository on GitHub and push this folder to it:
   ```bash
   cd AdvancedVideoPlayer
   git init && git add . && git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
2. Open the repo's **Actions** tab. The *Android CI* workflow (`.github/workflows/android.yml`) runs on every
   push / pull request: unit tests → debug APK → release APK. Download the APKs from the run's
   **Artifacts → AdvancedVideoPlayer-apks**.
3. **Release with a download link:** `git tag v1.0.0 && git push origin v1.0.0` – the workflow attaches the
   APKs to a GitHub Release automatically.
4. **Optional – properly signed release APK.** Create a keystore
   (`keytool -genkey -v -keystore release.jks -alias avp -keyalg RSA -keysize 2048 -validity 10000`), then add
   repository secrets (*Settings → Secrets and variables → Actions*):
   `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
   Without them the release APK is signed with the debug key (installs fine, not for Play Store).
5. Run it by hand any time: **Actions → Android CI → Run workflow**.

Local build: `./gradlew assembleDebug` (APK in `app/build/outputs/apk/debug/`), tests: `./gradlew testDebugUnitTest`.

---

## Full file access

On first start tap **Grant full file access** (Android 11+: opens the *All files access* switch for the app;
below 11: normal storage permission). **Browse All Files** then shows internal storage, SD cards and USB drives
and opens any file (mkv, ts, m2ts, dts …) by path; tick *All files* to also see non-media files. The normal
system picker accepts every type too, and the app opens `file://`, `content://`, http(s) and rtsp links from other apps.
This permission is for sideloaded builds; Google Play requires a special declaration for it.

---

## DTS / DTS-HD MA / DTS:X

The core inside DTS-HD MA / DTS:X is **lossy** (DTS 5.1, ~0.75–1.5 Mbps); the lossless part is the
extension. Android does **not** guarantee a DTS decoder: many phones (Pixel, several others) have none, so the core
is not playable everywhere by default.

To play the DTS core of the *same* track on every device, add the software FFmpeg decoder:
`gradle.properties` now sets `media3Version=1.9.0` and `ffmpegDecoderVersion=1.9.0+1`
(`org.jellyfin.media3:media3-ffmpeg-decoder`). If Gradle cannot find that exact version, correct or clear the value. ExoPlayer then
uses it automatically as the fallback after the device's own decoders. Without it, a track the device cannot
decode is skipped by ExoPlayer's track selection (another audio track plays, or the video is silent) and a
banner explains it; the app never silently swaps tracks while the DTS track is decodable. DTS:X in MP4/TS
(`audio/vnd.dts.uhd`) may not be handled by the FFmpeg decoder; in MKV it is stored as DTS and decodes as core.

---

## Reliability design

| Problem | Handling |
|---|---|
| Streams that fail to open / crash | Every player callback is guarded. Errors map to a clear message (`PlaybackFailure`) with Retry. HLS / DASH / Smooth / RTSP / progressive are detected by URL, Content-Type and first bytes; a wrong guess is retried as the other types, and the *first* error is reported. |
| Unsupported codec | Pre-checked against device decoders. If only audio or only video is unsupported the other half plays with a warning; only "nothing playable" fails. Decoder fallback is enabled. |
| Endless spinner | Spinner = ExoPlayer really in BUFFERING and no error. Watchdog: 25 s to start, 30 s per re-buffer, then a timeout error. Network errors retry 3x with back-off. |
| Metadata | Never blocks playback. Sources run separately with own timeouts; the UI shows what is available. No video is copied to cache any more. |
| Logo popup | Logos resolved by normalised file name (works with `Dolby-Atmos-Logo.png`, `DTS_HD.png` …), text badge when no file. One effect controls visibility; shows for the audio track actually playing. |
| Cleanup | Player, timers, coroutines, listeners released on dispose; temp files swept at start/exit. |

Network streams are not probed a second time (some providers allow one connection); ExoPlayer's own
track info is used for them. Plain `http://` is allowed (`usesCleartextTraffic`) because many streams need it.

### Optional FFprobe
`com.arthenica:ffmpeg-kit-full:6.0-2` was removed from Maven Central, so it is no longer a dependency.
`FfprobeSource` loads FFmpegKit by reflection: add any FFmpegKit AAR with the `com.arthenica.ffmpegkit`
package (e.g. `implementation(files("libs/ffmpeg-kit.aar"))`) and Dolby Vision profile / HDR10+ / Atmos-in-TrueHD
detection from file headers switches on automatically.

---

## Project Structure

```
app/src/main/java/com/advancedvideoplayer/
├── MainActivity.kt
├── player/
│   ├── PlayerManager.kt          ← ExoPlayer config, buffering watchdog, recovery
│   ├── PlaybackFailure.kt        ← error code → message mapping
│   ├── StreamTypeDetector.kt     ← pure URL / header / body format detection
│   └── StreamSniffer.kt          ← one short network probe for ambiguous URLs
├── data/
│   ├── MediaInfo.kt              ← data classes + SpecialFormat
│   ├── FormatDetector.kt         ← Atmos / DTS:X / DV / HDR logic (pure)
│   ├── CodecNames.kt             ← friendly codec / resolution / bitrate labels
│   ├── PlatformProbe.kt          ← MediaExtractor / MediaMetadataRetriever
│   ├── FfprobeSource.kt, FfprobeParser.kt  ← optional FFprobe
│   ├── StreamInfo.kt             ← merge of all sources → summary + logos
│   ├── StreamInfoCoordinator.kt  ← runs probes independently
│   └── TempFiles.kt, Blocking.kt, UriInfo.kt
└── ui/
    ├── PlayerScreen.kt           ← UI, error overlay, info panel
    ├── FormatLogos.kt            ← logo lookup + badge
    └── theme/Theme.kt
```

Unit tests: `app/src/test` (format detection, stream sniffing, error mapping, ffprobe parsing, merging).

---

## Adding Custom Logos

Put PNG files in:
```
app/src/main/assets/logos/
├── dts_x.png
├── dolby_atmos.png
├── truehd.png
├── dolby_vision.png
└── ...
```

Then the badges can be switched from colored text to real images.

---

Enjoy!
