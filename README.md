# EnvisionTools — Android BLE Companion App

EnvisionTools is an Android application that communicates with **Envision** smart-optics devices over Bluetooth Low Energy (BLE). It is the mobile equivalent of the Python `envision_send_command.py` host tool and implements the same TLV wire protocol.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Protocol](#protocol)
3. [Features](#features)
4. [Standard Initialisation Flow](#standard-initialisation-flow)
5. [Screen Reference](#screen-reference)
6. [JSON File Formats](#json-file-formats)
7. [BLE Characteristics](#ble-characteristics)
8. [Message ID Reference](#message-id-reference)
9. [Building the App](#building-the-app)
10. [Permissions](#permissions)
11. [Project Structure](#project-structure)

---

## Architecture

The app follows the **MVVM** pattern with Jetpack Compose:

```
MainActivity
  └── NavHost
        ├── ScanScreen  (scan & connect)
        └── DeviceScreen
              ├── Commands Tab  (all BLE commands)
              └── Files Tab     (file system browser)

EnvisionViewModel          ← single ViewModel, survives config changes
  └── EnvisionBleManager   ← GATT connection, send/receive coroutines

ble/
  ├── EnvisionProtocol.kt  ← frame builder / parser, UUIDs, message IDs
  ├── EnvisionCommands.kt  ← suspend functions for every command
  └── EnvisionBleManager.kt ← BluetoothGatt wrapper, StateFlow, coroutines
```

---

## Protocol

Communication uses a **TLV (Type–Length–Value)** binary framing:

```
[0xAA][0x55][CMD: 2B LE][LEN: 2B LE][PAYLOAD: 0-512B][CRC8]
```

| Field  | Size   | Description                               |
|--------|--------|-------------------------------------------|
| SYNC   | 2 B    | Fixed `0xAA 0x55`                         |
| CMD    | 2 B    | Command/message ID, little-endian         |
| LEN    | 2 B    | Payload length in bytes, little-endian    |
| PAYLOAD| 0–512 B| Command-specific data                     |
| CRC8   | 1 B    | `(−(CMD_L + CMD_H + LEN_L + LEN_H + Σ payload)) & 0xFF` |

Large command payloads (landscape coordinates, file transfers) are split into multiple frames with a maximum of **480 bytes** of points per coordinate chunk.

---

## Features

### Core commands (Commands tab)

| Section | Action | Python equivalent |
|---------|--------|-------------------|
| Data & Sync | **Flush** — erase device landscape & POI data | `send_flush` |
| Data & Sync | **Sync Time** — sends UNIX epoch as `uint32 LE` | `send_time` |
| GPS Position | **Send Position** — lat/lon as two `float32 LE` | `send_position --lat … --lon …` |
| Target (GoTo) | **Send Target** — azimuth/altitude as `float32 LE` | `send_target -x … -y …` |
| Landscape & POI | **Pick Landscape File** — open a landscape JSON | `send_landscape -f …` |
| Landscape & POI | **Send Landscape** — uploads all lines with progress bar | |
| Landscape & POI | **Pick POI File** — open a POI JSON | `send_poi -f …` |
| Landscape & POI | **Send POI** — uploads all entries with progress bar | |
| Full Flow | **Run Full Flow** — one-tap sequential pipeline (see below) | chain of commands |
| Query Device | **Brightness** — reads `uint16` brightness value | `get_brightness` |
| Query Device | **Config** — reads user config (68 bytes), shows device name | `get_user_config` |
| Query Device | **Calibration** — reads calibration blob (98 bytes) | `get_calibration` |
| Query Device | **WMM Field** — reads magnetic field vector | `get_wmm_field` |
| Stage 1–6 | Start/Stop testbench measurement stages | `start_stage_*` / `stop_stage_*` |
| Maintenance | **Format Partition** — erases the QSPI FatFS partition | `format_partition` |

### File system (Files tab)

| Action | Description |
|--------|-------------|
| Browse | Lists files and directories on the device flash |
| Refresh | Re-queries the current directory |
| Delete | Removes a single file (with confirmation icon) |

---

## Standard Initialisation Flow

This is the canonical sequence to set up an Envision device for a new observation session, matching the Python workflow:

```
1. Flush          ← erase old landscape / POI data
2. Sync Time      ← set device clock to current UNIX time
3. Send Position  ← tell the device its GPS coordinates
4. Send Landscape ← upload silhouette line data (JSON)
5. Send POI       ← upload points-of-interest (JSON)
```

### Using the app

1. Enter **Latitude** and **Longitude** in the *GPS Position* fields.
2. Tap **Pick Landscape File** and select a landscape JSON.
3. Tap **Pick POI File** and select a POI JSON.
4. Tap **Run Full Flow** — the app runs all five steps automatically with status updates.

All five steps can also be run individually from their respective sections in the Commands tab.

---

## Screen Reference

### Scan Screen

- Tap **Scan** to start a BLE scan filtered to device names starting with `ENVISION`.
- Tap a discovered device card to connect.
- A spinner indicates an active scan; the app navigates automatically to the Device screen on successful connection.

### Device Screen — Commands Tab

```
Device: <name>   Brightness: <value>   WMM: N=… E=… D=… µT

── Data & Sync ──────────────────────────────────────────────
  [ Flush ]             [ Sync Time ]

── GPS Position ─────────────────────────────────────────────
  Latitude: [_________]   Longitude: [_________]
  [ Send Position ]

── Target (GoTo) ────────────────────────────────────────────
  Azimuth (x): [_____]  Altitude (y): [_____]
  [ Send Target ]

── Landscape & POI ──────────────────────────────────────────
  [ Pick Landscape File: silhouettes_…json ]  [ Send ]
  ████████████████░░░░ 42 / 97 lines

  [ Pick POI File: poi_….json ]              [ Send ]
  ████░░░░░░░░░░░░░░░░ 8 / 42 entries

── Full Initialisation Flow ─────────────────────────────────
  Flush → Sync Time → Send Position → Landscape → POI
  Uses lat/lon from GPS section and the files picked above.
  [ Run Full Flow ]

── Query Device ─────────────────────────────────────────────
  [ Brightness ]  [ Config ]
  [ Calibration ] [ WMM Field ]

── Stage 1/3/4/5/6 ──────────────────────────────────────────
  [ Start ]  [ Stop ]  (per stage)

── Maintenance ──────────────────────────────────────────────
  [ Format Partition ]   ← destructive, red button
```

### Device Screen — Files Tab

Browseable directory listing of the device's QSPI flash.  
Each file entry shows its size and a delete button.

---

## JSON File Formats

### Landscape — V2 (recommended)

```json
[
  {
    "lineIndex": 0,
    "azMin": 0.0,
    "azMax": 90.0,
    "points": [
      { "azimuth": 0.0, "altitude": 12.5 },
      { "azimuth": 1.0, "altitude": 13.1 }
    ]
  }
]
```

### Landscape — Legacy (auto-detected)

```json
[
  {
    "points": [
      { "azimuth": 0.0, "altitude": 12.5 },
      { "azimuth": 1.0, "altitude": 13.1 }
    ]
  }
]
```

### Landscape — Original legacy (`silhouettelines`)

```json
{
  "silhouettelines": [
    { "azialtdist": [[0.0, 12.5], [1.0, 13.1]] }
  ]
}
```

All three formats are auto-detected and parsed by the app.

### POI (Points of Interest)

```json
[
  {
    "sectorIndex": 2,
    "azimut": 45.3,
    "altitude": 8.7,
    "importance": 1.0,
    "elevation": 1912.0,
    "distance": 14200.0,
    "name": "Mont Ventoux"
  }
]
```

---

## BLE Characteristics

| Role       | UUID                                   | Direction          |
|------------|----------------------------------------|--------------------|
| TX (write) | `75568951-b1a7-47d5-af5c-4d7ed8188f74`| Android → Device   |
| RX (notify)| `1afcc197-0425-4b24-807d-0a3516c86e36`| Device → Android   |
| Boot       | `def01d15-4baa-465d-99a6-69a4054e9c91`| Bootloader mode    |

The app requests an MTU of **512 bytes** at connect time to reduce fragmentation overhead.

---

## Message ID Reference

| ID  | Constant              | Direction    | Description                     |
|-----|-----------------------|--------------|---------------------------------|
| 32  | `MSG_CMD_DESC`        | → Device     | Landscape line descriptor       |
| 33  | `MSG_CMD_COORD`       | → Device     | Landscape coordinate chunk      |
| 34  | `MSG_CMD_POI`         | → Device     | Point of interest entry         |
| 35  | `MSG_CMD_TIME`        | → Device     | UNIX time sync                  |
| 36  | `MSG_CMD_FLUSH`       | → Device     | Flush start (0x00) / end (0x01) |
| 37  | `MSG_CMD_TARGET`      | → Device     | GoTo target azimuth/altitude    |
| 53  | `MSG_GPS_POSITION`    | → Device     | GPS lat/lon position            |
| 60  | `MSG_START_STAGE_ONE` | → Device     | Testbench stage 1 start         |
| 61  | `MSG_STOP_STAGE_ONE`  | → Device     | Testbench stage 1 stop          |
| 80  | `MSG_START_STAGE_FOUR`| → Device     | Testbench stage 4 start         |
| 85  | `MSG_GET_BRIGHTNESS`  | → Device     | Request brightness value        |
| 86  | `MSG_BRIGHTNESS_RESP` | ← Device     | Brightness uint16 response      |
| 110 | `MSG_START_STAGE_FIVE`| → Device     | Testbench stage 5 start         |
| 119 | `MSG_GET_CALIBRATION` | → Device     | Request calibration data        |
| 120 | `MSG_CALIBRATION_RESP`| ← Device     | Calibration blob (98 bytes)     |
| 121 | `MSG_GET_USER_CONFIG` | → Device     | Request user configuration      |
| 122 | `MSG_USER_CONFIG_RESP`| ← Device     | User config blob (68 bytes)     |
| 123 | `MSG_GET_WMM_FIELD`   | → Device     | Request WMM magnetic field      |
| 124 | `MSG_WMM_FIELD_RESP`  | ← Device     | WMM field vector (17 bytes)     |
| 130 | `MSG_START_STAGE_SIX` | → Device     | Testbench stage 6 start         |
| 132 | `MSG_STAGE_SIX_ACK`   | ← Device     | Stage 6 / format ACK            |
| 133 | `MSG_FORMAT_PARTITION`| → Device     | Erase QSPI FatFS partition      |
| 140–149 | `MSG_FILE_*`     | ↔ Device     | File system operations          |

---

## Building the App

**Requirements**

- Android Studio Hedgehog or later
- Android Gradle Plugin 9.0+
- Kotlin 2.0+
- Min SDK 29 (Android 10) — required for `BluetoothDevice.TRANSPORT_LE`
- Target SDK 36

**Steps**

```bash
git clone <repo-url>
cd EnvisionTools
# Open in Android Studio and sync Gradle, or:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | SDK | Purpose |
|------------|-----|---------|
| `BLUETOOTH_SCAN` | API 31+ | Discover nearby BLE devices |
| `BLUETOOTH_CONNECT` | API 31+ | Connect and communicate with a device |
| `ACCESS_FINE_LOCATION` | API ≤ 30 | Required for BLE scan on older Android |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` | API ≤ 30 | Legacy BLE access |

All permissions are requested at app start via `ActivityResultContracts.RequestMultiplePermissions`. Landscape and POI JSON files are opened through the system file picker (Storage Access Framework) — no storage permission is needed.

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/envisiontools/
│   ├── MainActivity.kt                  # Entry point, navigation host
│   ├── ble/
│   │   ├── EnvisionProtocol.kt          # Frame builder/parser, constants
│   │   ├── EnvisionBleManager.kt        # GATT wrapper, coroutines, flows
│   │   └── EnvisionCommands.kt          # Suspend functions for all commands
│   ├── ui/
│   │   ├── ScanScreen.kt               # BLE scan & device selection UI
│   │   ├── DeviceScreen.kt             # Commands + Files tabs UI
│   │   └── theme/                      # Material 3 theme
│   └── viewmodel/
│       └── EnvisionViewModel.kt         # State, JSON parsing, command orchestration
└── res/
    └── ...
```
