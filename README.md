# Rayo Light Control 🚲💡

[![Android Version](https://img.shields.io/badge/Android-6.0%20to%2017%20(API%2023--37)-brightgreen.svg)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Cloud Free](https://img.shields.io/badge/Cloud-100%25%20Offline-success.svg)](#)
[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-Donate-orange.svg)](https://buymeacoffee.com/02eOWKzm5W)

> **Standalone, 100% offline Android controller for the Hauteworks Rayo Smart Bicycle Tail Light.**  
> *No cloud login, no server connection, no tracking – pure Bluetooth Low Energy (BLE) control.*

---

## 📖 Background & Problem Statement

The **Rayo Smart Tail Light** was an innovative, crowdfunded bicycle rear light developed by **Hauteworks Inc.**, featuring three high-output LEDs with engineered TIR optics, accelerometer-based brake light detection, theft alerts, and custom flashing modes.

Unfortunately, the manufacturer ceased operations and permanently shut down their backend authentication servers. Because both official Android apps (*Rayo* and *SmartGrid*) enforced strict online account logins before communicating with the device, the light was rendered essentially unconfigurable and semi-bricked for thousands of cyclists worldwide.

**Rayo Light Control** was built from the ground up via clean BLE reverse engineering to revive this fantastic piece of hardware. It connects directly to the light over BLE, requiring zero registration, zero accounts, and zero internet connection.

---

## ✨ Features

- **⚡ Dedicated Power Controls:** Instant **LIGHT ON** and **LIGHT OFF** toggle commands.
- **🔄 4 Hardware Mode Profiles:** Switch seamlessly between Profile 1, 2, 3, and 4; live sync with on-device settings.
- **🚨 3-LED Independent Pattern Configuration:**
  - **Top LED (Focus Lens):** Focused beam for long-distance visibility.
  - **Middle LED (Wide Angle Lens):** Diffused wide illumination.
  - **Bottom LED (Wide Angle Lens):** Diffused downward/wide illumination.
  - Available patterns: *Constant Light*, *Gentle Glow (Pulse)*, *Blink*, *Wave (Surge)*, *Strobe / Burst*, and *Off (Turned Off)*.
- **🛑 Smart Brake Detection:** Enable/disable accelerometer brake lighting with 3 sensitivity levels (*Low*, *Medium*, *High*).
- **🔒 Anti-Theft & Motion Alarm:** Arm the internal motion sensor; the app triggers an instant audio-visual alert dialog if someone tampers with your bike.
- **🔋 Battery Level Readout:** Standard BLE Battery Service (0x180F) integration with live percentage display.
- **📊 Real-time BLE Packet Logger:** Built-in hex diagnostic log showing transmitted (`TX`) and received (`RX`) packets.
- **🌐 Bilingual UI:** Automatically adapts to device language:
  - 🇩🇪 **German** (`de`)
  - 🇬🇧 **English** (`en` - default)
- **📱 Modern Android Support:** Targets **Android 14, 15, 16 & 17 (targetSdkVersion 37)** with modern runtime permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` with `neverForLocation`) and edge-to-edge support. No legacy OS warnings!

---

## 🔬 Hardware Architecture & Reverse Engineering Notes

During protocol analysis and packet inspection of the Rayo hardware, key architectural details were discovered:

### 1. Master Brightness vs. Individual LED Brightness
- **Rayo Hardware (Firmware < 30):**  
  The original Rayo uses a shared analog/PWM driver circuit for all three LEDs. Global brightness is governed by Byte 1 (`0..100%`). The three LEDs accept pattern IDs `0..5` in Bytes 5, 6, and 7.
- **CLIQ / Farina Hardware (Firmware >= 30):**  
  Later hardware revisions introduced independent PWM drivers where each LED can have its brightness encoded in the upper 5 bits: `(brightness_31 << 3) | pattern_id`.  
  *Note:* Sending composite values on original Rayo hardware causes firmware fallback to static continuous light. Rayo Light Control properly handles both modes.

### 2. Micro-USB Interface
The onboard Micro-USB port only connects power lines (`VBUS` and `GND`) for battery charging. There are no USB data pins (`D+` / `D-`) wired to the microcontroller. All communication occurs strictly via Bluetooth Low Energy.

### 3. Firmware Updates (DFU)
The device implements the **Nordic Semiconductor Secure DFU** bootloader (Service `0xFE59`). Flashing custom firmware requires cryptographic signing with Hauteworks' private ECDSA key. To protect user hardware against permanent bricking, DFU should not be initiated without verified signed packages.

---

## 📡 Reverse-Engineered BLE Protocol Specification

The device communicates via the **Nordic UART Service (NUS)**:
- **Service UUID:** `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- **RX Characteristic (Write / Write No Response):** `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- **TX Characteristic (Notify):** `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
- **Battery Service:** `0000180F-0000-1000-8000-00805F9B34FB` / Characteristic: `00002A19-0000-1000-8000-00805F9B34FB`

### Packet Structure
All commands sent to the `RX` characteristic follow this byte frame:
`[Length, CommandID, Payload...]`

| Command | Name | Payload Structure | Description |
|---|---|---|---|
| `0x10` | **Power & Theft** | `[0x02, 0x10, LightOn (0/1), TheftAlarm (0/1)]` | Turns light on/off and enables anti-theft |
| `0x11` | **Status Query** | `[0x00, 0x11]` | Requests device status (returns `0x30`) |
| `0x20` | **Query Profile** | `[0x01, 0x20, ProfileIndex (0..3)]` | Queries settings for a specific profile (returns `0x20`) |
| `0x21` | **Save Profile** | `[0x08, 0x21, ProfileIndex, MasterBri, BrakeActive, BrakeSens, Sync, TopLED, MidLED, BotLED]` | Configures pattern, brightness, and brake settings |
| `0x22` | **Activate Profile** | `[0x01, 0x22, ProfileIndex (0..3)]` | Switches active profile on the device |

---

## 🚀 Getting Started

### Download APK
Grab the pre-built APK from the [Releases](https://github.com/) section (`Rayo_Control_v1.1.0.apk`) and install it on your Android device.

### Connecting to Your Light
1. Turn on your Rayo light (press the physical power button).
2. Enable Bluetooth on your smartphone.
3. Open **Rayo Control** and grant Bluetooth permissions.
4. Tap **"Scan & Connect Rayo"**. The app will detect your light and connect within 1–2 seconds.

---

## 🛠️ Building from Source

### Option 1: Android Studio (Recommended)
1. Clone this repository:
   ```bash
   git clone https://github.com/<your-username>/rayo-light-control.git
   ```
2. Open the project folder in Android Studio.
3. Build and run on your device or emulator.

### Option 2: Standalone CLI Build (Python + Android SDK Tools)
A zero-dependency build script is included:
```bash
python build_new_apk.py
```
*(Requires `aapt2`, `javac`, and `d8`)*

---

## ☕ Support & Community

If this project helped you bring your Rayo tail light back to life, feel free to support:

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-Donate-yellow?style=for-the-badge&logo=buy-me-a-coffee)](https://buymeacoffee.com/02eOWKzm5W)

---

## 📜 License & Disclaimer

- **License:** Distributed under the [MIT License](LICENSE).
- **Disclaimer:** This project is an independent community open-source effort. It is not affiliated with, endorsed by, or associated with Hauteworks Inc. All product names, trademarks, and registered trademarks are property of their respective owners.
