# scrcpy-enh

An enhanced build based on [scrcpy v4.1](https://github.com/Genymobile/scrcpy) for **PC ↔ Android** screen mirroring. Adds **ABR adaptive bitrate/framerate, live status overlay, image clipboard sync, uhid keyboard, and automatic USB/WiFi switching** on top of the official version.

> **v1.0 released** — [Download from Releases](https://github.com/kinewe/scrcpy-enh/releases) (zip includes all runtime libraries; extract and run on Windows 10/11, no installation needed)
>
> [中文文档](README.md)

---

## ✨ Features

| Feature | Description |
|---|---|
| 🔄 **Auto switching** | Double-click to start: USB connected → wired; unplug → auto WiFi in 5s; re-plug → back to wired |
| ⚡ **ABR adaptive** | Bitrate + framerate adapt dynamically: degrades under load to stay responsive, restores quickly when load clears |
| 📊 **Status overlay** | Top-right live display: actual/target fps + bitrate + USB/WIFI (Alt+drag to move, Ctrl+F to toggle) |
| 🎹 **uhid keyboard** | PC keyboard is recognized as a physical keyboard by the phone — type Chinese directly (layout auto-switches to English on focus to avoid IME interference, auto-restores on blur) |
| 📋 **Image clipboard sync** | Copy an image on PC → auto-synced to phone clipboard → long-press paste in WeChat/QQ (merged scrcpy PR #6676) |
| 🖼️ **Ctrl+G save to gallery** | Save the clipboard image to the phone gallery with one key |
| 📐 **Dynamic specs** | Auto-reads device resolution/refresh rate on plug-in and assigns optimal streaming specs |

---

## 🚀 Quick Start

1. **Download** the latest zip from [Releases](https://github.com/kinewe/scrcpy-enh/releases) and extract it
2. **Phone setup**: enable Developer Options → USB Debugging (Xiaomi/Redmi also need "USB debugging (Security settings)")
3. **Start**: double-click `手机投屏.bat` (scrcpy-launch.bat)
4. Allow the USB debugging prompt on the phone → mirroring starts

---

## 📖 Usage Guide

### Connection & auto switching

| Scenario | Behavior |
|---|---|
| USB plugged in | Wired (high-quality specs) |
| USB unplugged | Auto-switch to WiFi in 5s (requires config) |
| USB re-plugged | Auto-switch back to wired |
| Q or close window | Exit |

**WiFi config** (optional): create `config.txt` in the extracted folder with the phone's wireless debugging address, e.g. `192.168.31.100:5555`.

### Dynamic specs

| Connection | Max resolution | Max fps | Bitrate |
|---|---|---|---|
| USB wired | 2560 (dynamic by device) | 120 | 15-80M dynamic |
| WiFi wireless | 1920 | 60 | 15M |

### Status overlay

```
10/60fps 15M USB
↑actual/target ↑bitrate ↑mode
```

- **Alt + left-drag**: move the overlay
- **Ctrl+F**: show/hide

Note: "actual fps" = frames the phone currently sends (low fps on still screens is normal system power saving); "target" = current encoding ceiling.

### Keyboard shortcuts

| Key | Action |
|---|---|
| `Ctrl+F` | Toggle status overlay |
| `Ctrl+G` | Save clipboard image to phone gallery |
| `Ctrl+V` | Paste PC clipboard to phone |
| `Alt + drag` | Move overlay |
| `F11` | Fullscreen |
| `Q` | Quit |

### Image clipboard

1. Copy any image on the PC (`Ctrl+C`)
2. It syncs to the phone clipboard automatically
3. Long-press → paste in WeChat/QQ
4. `Ctrl+G` saves the original image to the phone gallery

### uhid keyboard & IME

When the mirroring window has focus, the PC keyboard types directly into the phone:
- English layout is forced on focus (prevents IME from intercepting keys)
- Your original input method is restored on blur/exit

---

## 🤔 FAQ

**Q: Why does the framerate change (120 → 60 → 30)?**
A: ABR adapts dynamically. Still screens are downclocked by the system to save power; complex animations trigger graceful degradation to stay smooth; full speed is restored when load clears. It's a feature, not a bug.

**Q: The IME switched to English while mirroring?**
A: Normal. The uhid keyboard forces English layout on focus; it **auto-restores** your input method when the window loses focus or exits.

**Q: Why is wireless less sharp than wired?**
A: Wireless specs (1920/60/15M) are lower than wired (2560/120/dynamic) due to WiFi stability. Use USB for the best quality.

**Q: Device not found / no response?**
A: Check USB debugging is enabled and authorized on the phone; for wireless, confirm same WiFi and correct address.

**Q: How is this different from official scrcpy?**
A: This build adds ABR adaptation, status overlay, USB/WiFi auto-switching, image clipboard (upstream PR #6676), Ctrl+G gallery save, and automatic IME layout switching. Command-line options remain compatible with the official version.

---

## 🔨 Building from source (Windows)

### Requirements
- MSYS2 (mingw64: gcc, meson, ninja, SDL3, ffmpeg)
- JDK 17 + Gradle (server build)
- Android SDK (server dependency)

### Steps
```bash
# 1. Server (Java)
cd server
export JAVA_HOME=<jdk17 path>
gradle --no-daemon assemble        # output: server/build/outputs/apk/debug/server-debug.apk

# 2. Client (C)
mkdir build && cd build
meson setup .. -Dprebuilt_server=../server/build/outputs/apk/debug/server-debug.apk
ninja                              # output: app/scrcpy.exe

# 3. Package the run folder
# scrcpy.exe + scrcpy-server + MSYS2 runtime DLLs + adb.exe + 手机投屏.bat
```

### Project layout
```
app/          Client (C, SDL3 + ffmpeg)
server/       Server (Java, MediaCodec encoding + ABR core)
packaging/    Launch script (手机投屏.bat)
```

---

## 📄 License

This project is a derivative of scrcpy, licensed under the [Apache License 2.0](LICENSE). The enhancement code (ABR, overlay, auto-switching, image clipboard) is licensed under Apache 2.0 as well. Image clipboard support is merged from upstream PR [#6676](https://github.com/Genymobile/scrcpy/pull/6676).

---

## 🙏 Credits

- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) — the excellent open-source mirroring tool
- All testers and contributors
