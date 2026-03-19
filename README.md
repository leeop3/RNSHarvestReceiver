# RNS Harvest Receiver — Android Application

A native Android application that acts as a centralised offline-first receiver
for oil-palm harvest reports transmitted over the **Reticulum Network Stack
(RNS)** using the **LXMF** messaging format.

Field harvesters send CSV reports via RNS from their devices; this app receives
them through an **RNode** radio (connected over Bluetooth Classic) and stores
them locally in SQLite, with a clean supervisor-facing UI.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Protocol Stack](#protocol-stack)
3. [Data Flow](#data-flow)
4. [Project Structure](#project-structure)
5. [Module Reference](#module-reference)
6. [Building the App](#building-the-app)
7. [Setting Up the RNode](#setting-up-the-rnode)
8. [CSV Message Format](#csv-message-format)
9. [Deduplication Logic](#deduplication-logic)
10. [UI Reference](#ui-reference)
11. [Testing](#testing)
12. [Extending the App](#extending-the-app)
13. [Known Limitations & TODOs](#known-limitations--todos)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     Android Application                         │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │               UI Layer (Fragments + ViewModels)          │  │
│  │   ┌─────────────────────┐  ┌──────────────────────────┐ │  │
│  │   │  IncomingDataFragment│  │HarvesterSummaryFragment  │ │  │
│  │   │  (all records table) │  │(daily aggregates per     │ │  │
│  │   │                      │  │ harvester, efficiency %) │ │  │
│  │   └─────────────────────┘  └──────────────────────────┘ │  │
│  │                    MainViewModel                          │  │
│  └──────────────────────────────────────────────────────────┘  │
│                           │  LiveData                          │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │               Data Layer                                 │  │
│  │   HarvestRepository  ←→  HarvestDao  ←→  Room (SQLite)  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                           ▲                                    │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │            RNSReceiverService (Foreground)               │  │
│  │                                                          │  │
│  │   Frame ──► RnsFrameDecoder ──► LxmfMessageParser        │  │
│  │                                       │                  │  │
│  │                               CsvParser.parsePayload()   │  │
│  └──────────────────────────────────────────────────────────┘  │
│                           ▲                                    │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │          BluetoothRNodeManager                           │  │
│  │   RFCOMM SPP socket ──► KissFramer.extractFrames()       │  │
│  └──────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬──────────────────────────────────┘
                   Bluetooth Classic (SPP / RFCOMM)
                               │
                         ┌─────┴──────┐
                         │   RNode    │  (LoRa radio hardware)
                         └─────┬──────┘
                               │  LoRa / RNS
                    ┌──────────┴──────────┐
                    │  Field Harvesters   │
                    │  (RNS + LXMF sender │
                    │   devices)          │
                    └─────────────────────┘
```

---

## Protocol Stack

### Layer 1 — Bluetooth Classic SPP

The RNode exposes a standard **Serial Port Profile (SPP)** over Bluetooth
Classic. The app connects using RFCOMM to UUID `00001101-0000-1000-8000-00805F9B34FB`.

### Layer 2 — KISS Framing

RNode uses **KISS** (Keep It Simple, Stupid) framing for the serial link —
the same standard used by packet radio TNCs. Each RNS packet is wrapped in:

```
0xC0  0x00  [escaped data bytes]  0xC0
FEND  CMD=DATA                    FEND
```

Special bytes in data are escaped:
- `0xC0` → `0xDB 0xDC`
- `0xDB` → `0xDB 0xDD`

**Implementation:** `RnsFrameDecoder.KissFramer`

### Layer 3 — RNS Packet

Inside each KISS frame is an RNS packet with a 2-byte header:

```
Byte 0: [ifac_flag:1][header_type:1][context:2][propagation:2][dest_type:1][unused:1]
Byte 1: [packet_type:4][hops:4]
```

Followed by:
- Destination hash (10 bytes)
- Optional transport ID (10 bytes, if header_type=1 or propagation=TRANSPORT)
- Context byte
- Payload data

**Implementation:** `RnsFrameDecoder`

### Layer 4 — LXMF Message

The RNS data payload contains an LXMF message:

```
[destination hash: 16 bytes]
[source hash:      16 bytes]
[Ed25519 signature: 64 bytes]
[msgpack-encoded fields...]
```

The msgpack payload is a fixarray of 4 elements:
- `[0]` timestamp (float64)
- `[1]` title (binary/string)
- `[2]` content (binary/string)  ← **CSV harvest data lives here**
- `[3]` fields (map)

**Implementation:** `LxmfMessageParser`

### Layer 5 — CSV Harvest Data

The LXMF `content` field contains the CSV harvest report(s).

**Implementation:** `CsvParser`

---

## Data Flow

```
RNode (LoRa radio)
    │  Bluetooth SPP stream (raw bytes)
    ▼
BluetoothRNodeManager.readerJob
    │  Reads into 4KB buffer, accumulates bytes
    ▼
KissFramer.extractFrames()
    │  Emits ByteArray per complete KISS frame
    ▼  (via SharedFlow<ByteArray>)
RNSReceiverService.processFrame()
    │
    ├─► RnsFrameDecoder.decode()      — strips RNS header
    │       │
    │       ▼
    │   LxmfMessageParser.parse()     — decodes LXMF
    │       │
    │       ▼
    │   CsvParser.parsePayload()      — parses CSV lines → HarvestRecord list
    │
    └─► (fallback) processRawData()   — treats raw bytes as plain CSV text
            │
            ▼
        CsvParser.parsePayload()
            │
            ▼
HarvestRepository.insertRecord()
    │  Deduplication: checks (externalId) AND (harvesterId, timestamp)
    │  Room IGNORE conflict strategy as second safety net
    ▼
HarvestDatabase (SQLite)
    │
    ▼  LiveData
MainViewModel ──► Fragments ──► RecyclerView adapters
```

---

## Project Structure

```
RNSHarvestReceiver/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/harvest/rns/
│   │   │   │   ├── HarvestApplication.kt          # App class, DB warm-up
│   │   │   │   ├── data/
│   │   │   │   │   ├── db/
│   │   │   │   │   │   ├── HarvestDatabase.kt     # Room database singleton
│   │   │   │   │   │   └── HarvestDao.kt          # All SQL queries + aggregations
│   │   │   │   │   ├── model/
│   │   │   │   │   │   ├── HarvestRecord.kt       # Room @Entity (CSV schema)
│   │   │   │   │   │   ├── HarvesterSummary.kt    # Aggregation result
│   │   │   │   │   │   └── ConnectionStatus.kt    # Sealed class for BT state
│   │   │   │   │   └── repository/
│   │   │   │   │       └── HarvestRepository.kt   # Insert w/ dedup, LiveData streams
│   │   │   │   ├── network/
│   │   │   │   │   ├── RNSReceiverService.kt      # Foreground service, pipeline orchestrator
│   │   │   │   │   ├── BootReceiver.kt            # Auto-restart on device boot
│   │   │   │   │   ├── bluetooth/
│   │   │   │   │   │   └── BluetoothRNodeManager.kt # SPP connection + KISS accumulation
│   │   │   │   │   ├── rns/
│   │   │   │   │   │   └── RnsFrameDecoder.kt     # RNS packet decoder + KissFramer
│   │   │   │   │   └── lxmf/
│   │   │   │   │       └── LxmfMessageParser.kt   # LXMF binary parser + msgpack reader
│   │   │   │   ├── ui/
│   │   │   │   │   ├── main/
│   │   │   │   │   │   ├── MainActivity.kt        # Host: toolbar, tabs, BT picker, service bind
│   │   │   │   │   │   ├── MainViewModel.kt       # Shared ViewModel, LiveData façade
│   │   │   │   │   │   ├── MainPagerAdapter.kt    # ViewPager2 tab adapter
│   │   │   │   │   │   └── BluetoothDevicePickerActivity.kt
│   │   │   │   │   ├── incoming/
│   │   │   │   │   │   ├── IncomingDataFragment.kt   # Tab 1: all records
│   │   │   │   │   │   └── IncomingRecordsAdapter.kt # RecyclerView + filter
│   │   │   │   │   └── summary/
│   │   │   │   │       ├── HarvesterSummaryFragment.kt # Tab 2: daily summaries
│   │   │   │   │       └── SummaryAdapter.kt           # Per-harvester card
│   │   │   │   └── utils/
│   │   │   │       ├── CsvParser.kt               # CSV → HarvestRecord parser
│   │   │   │       ├── DateUtils.kt               # Timestamp parsing helpers
│   │   │   │       └── Extensions.kt              # Kotlin extensions (View, Context…)
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml
│   │   │   │   │   ├── fragment_incoming_data.xml
│   │   │   │   │   ├── fragment_harvester_summary.xml
│   │   │   │   │   ├── item_harvest_record.xml
│   │   │   │   │   └── item_harvester_summary.xml
│   │   │   │   ├── values/
│   │   │   │   │   ├── colors.xml
│   │   │   │   │   ├── strings.xml
│   │   │   │   │   └── themes.xml
│   │   │   │   ├── drawable/
│   │   │   │   │   ├── circle_indicator.xml
│   │   │   │   │   ├── rank_badge_bg.xml
│   │   │   │   │   ├── badge_outline_bg.xml
│   │   │   │   │   └── spinner_bg.xml
│   │   │   │   ├── menu/
│   │   │   │   │   └── main_menu.xml
│   │   │   │   └── xml/
│   │   │   │       ├── backup_rules.xml
│   │   │   │       └── data_extraction_rules.xml
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   │       └── java/com/harvest/rns/
│   │           ├── CsvParserTest.kt
│   │           ├── LxmfParserTest.kt
│   │           └── KissFramerTest.kt
│   ├── build.gradle
│   └── proguard-rules.pro
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## Module Reference

### `HarvestRecord` (data/model)

Room entity mapping the CSV schema. Key fields:

| Field         | Type    | Source             | Notes                                  |
|---------------|---------|--------------------|----------------------------------------|
| `localId`     | Long    | Auto-generated     | Internal primary key                   |
| `externalId`  | String  | CSV field `id`     | Unique index — dedup key               |
| `harvesterId` | String  | CSV                | Indexed for group-by queries           |
| `blockId`     | String  | CSV                |                                        |
| `ripeBunches` | Int     | CSV                |                                        |
| `emptyBunches`| Int     | CSV                |                                        |
| `latitude`    | Double  | CSV                |                                        |
| `longitude`   | Double  | CSV                |                                        |
| `timestamp`   | String  | CSV                | Stored as-received string              |
| `reportDate`  | String  | Extracted          | `yyyy-MM-dd` — used for date grouping  |
| `photoFile`   | String  | CSV                | Filename reference only                |
| `receivedAt`  | Long    | App                | Epoch millis — when app received it    |
| `rawCsv`      | String  | App                | Original CSV line for audit            |

### `HarvestDao` (data/db)

Key queries:

```kotlin
// All records, newest first
fun getAllRecords(): LiveData<List<HarvestRecord>>

// Per-date summaries for Harvester Summary tab
fun getSummaryByDate(date: String): LiveData<List<HarvesterSummary>>

// Duplicate check used before insert
suspend fun isDuplicate(externalId, harvesterId, timestamp): Int

// Deduplicated insert via IGNORE conflict strategy
suspend fun insert(record): Long  // returns -1 if duplicate
```

### `CsvParser` (utils)

- `parsePayload(rawPayload: String): List<HarvestRecord>` — multi-line
- `parseLine(line: String): HarvestRecord?` — single line

Accepts payloads with or without header rows. Handles quoted fields.
Supports multiple timestamp formats. Falls back to today's date if timestamp
cannot be parsed.

### `LxmfMessageParser` (network/lxmf)

- `parse(rawBytes: ByteArray): LxmfMessage?`

Handles the full LXMF binary structure (96-byte header + msgpack body).
Falls back to treating the payload as raw UTF-8 text when msgpack decoding
fails — useful for simple senders that skip the LXMF envelope.

### `RnsFrameDecoder` (network/rns)

- `decode(raw: ByteArray): RnsPacket?` — RNS packet decoder
- `KissFramer.extractFrames(buffer: ByteArray): List<ByteArray>` — serial framer
- `KissFramer.encodeFrame(data: ByteArray): ByteArray` — for sending to RNode

### `BluetoothRNodeManager` (network/bluetooth)

- Connects to a `BluetoothDevice` via RFCOMM SPP
- Exponential back-off reconnection (up to 5 attempts, 5-second delay)
- Emits raw KISS frames via `SharedFlow<ByteArray>`
- Clears byte accumulator on reconnect to avoid corrupted frames
- `BtState` sealed class: `Idle`, `Connecting`, `Connected`, `Reconnecting`, `Error`

### `RNSReceiverService` (network)

- Foreground service with persistent notification
- Binds to `BluetoothRNodeManager` output
- Runs `processFrame()` pipeline coroutine on `Dispatchers.IO`
- Exposes singleton `StateFlow` counters: `messageCount`, `duplicateCount`, `serviceStatus`
- Handles `ACTION_CONNECT` / `ACTION_DISCONNECT` intents from `MainActivity`
- Survives app close; restarts on device boot via `BootReceiver`

---

## Building the App

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- JDK 17
- A physical Android device running Android 8.0+ (API 26+)
  - *Bluetooth Classic cannot be tested in the emulator*

### Steps

```bash
# Clone the repository
git clone <repo-url>
cd RNSHarvestReceiver

# Open in Android Studio, or build from the command line:
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run unit tests
./gradlew test
```

The debug APK is output to:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Setting Up the RNode

1. **Obtain an RNode** — available from https://unsigned.io/rnode or build one
   from the open-source hardware design.

2. **Install RNode firmware** using the `rnodeconf` tool:
   ```bash
   pip install rns
   rnodeconf --autoinstall
   ```

3. **Pair the RNode** with the Android device:
   - Go to Android *Settings → Bluetooth*
   - Scan for devices and pair with the RNode (it appears as a Bluetooth serial device)
   - No PIN is usually required

4. **In the app**, tap the antenna icon in the toolbar → **Connect RNode** →
   select the paired device from the list.

5. The status bar at the top of the screen turns **green** when connected.

### RNode LoRa Configuration

RNode defaults are typically fine for short-range plantation use. To adjust
frequency/bandwidth/spreading factor, use `rnodeconf`:

```bash
# Example: 915 MHz, 250 kHz BW, SF7, TXPWR 17 dBm
rnodeconf /dev/ttyUSB0 -f 915000000 -b 250000 -s 7 -t 17
```

The RNS layer handles all the framing and acknowledgement automatically.

---

## CSV Message Format

Harvesters transmit reports in the following CSV schema:

```
id,harvester_id,block_id,ripe_bunches,empty_bunches,latitude,longitude,timestamp,photo_file
```

### Example

```csv
REC-20250115-001,HRV-A01,BLK-03,24,3,1.554320,110.345210,2025-01-15T08:30:00,IMG_001.jpg
REC-20250115-002,HRV-B02,BLK-07,18,5,1.600120,110.412350,2025-01-15T09:15:00,
```

### Accepted Timestamp Formats

| Format                        | Example                     |
|-------------------------------|-----------------------------|
| ISO 8601 (preferred)          | `2025-01-15T08:30:00`       |
| ISO 8601 with timezone        | `2025-01-15T08:30:00+0800`  |
| ISO 8601 with milliseconds    | `2025-01-15T08:30:00.000`   |
| Space-separated               | `2025-01-15 08:30:00`       |
| Short space-separated         | `2025-01-15 08:30`          |
| Day/Month/Year                | `15/01/2025 08:30:00`       |

### Sending from a Harvester Device

A minimal Python sender using RNS + LXMF:

```python
import RNS
import LXMF
import time

# Initialise Reticulum
rns = RNS.Reticulum()

# Create an LXMF delivery router
router = LXMF.LXMRouter(storagepath="./lxmf_storage")
source = router.register_delivery_identity(RNS.Identity())

# Destination: the receiver app's announced address
dest_hash = bytes.fromhex("YOUR_RECEIVER_DESTINATION_HASH")
dest = RNS.Destination(
    RNS.Identity.recall(dest_hash),
    RNS.Destination.OUT,
    RNS.Destination.SINGLE,
    "lxmf", "delivery"
)

# Build CSV content
csv_content = "REC-001,HRV-A01,BLK-03,24,3,1.554320,110.345210,2025-01-15T08:30:00,photo.jpg"

# Create and send LXMF message
msg = LXMF.LXMessage(dest, source, csv_content, title="Harvest Report")
msg.desired_method = LXMF.LXMessage.DIRECT
router.handle_outbound(msg)
time.sleep(5)  # wait for delivery
```

---

## Deduplication Logic

Duplicate detection happens at **two levels**:

### Level 1 — Pre-insert check (HarvestRepository)

```kotlin
val dupeCount = dao.isDuplicate(
    externalId  = record.externalId,
    harvesterId = record.harvesterId,
    timestamp   = record.timestamp
)
if (dupeCount > 0) return InsertResult.Duplicate
```

A record is considered a duplicate if **either**:
- Its `externalId` matches an existing record, **or**
- Its (`harvesterId`, `timestamp`) pair matches an existing record

This handles cases where the sender uses a different ID format between
retransmissions but the content is the same.

### Level 2 — Room constraint (HarvestDao)

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insert(record: HarvestRecord): Long
```

The schema has two unique indices:
- `UNIQUE(externalId)`
- `UNIQUE(harvesterId, timestamp)`

If Level 1 misses a duplicate (race condition), Room silently ignores the
insert and returns `-1`.

---

## UI Reference

### Status Bar (top of screen)

```
● [green/amber/grey/red]  Connected: RNode-A1E2     Received: 47   Dupes ignored: 3
```

| Element              | Meaning                                          |
|----------------------|--------------------------------------------------|
| Coloured dot         | Connection state (green=connected, grey=idle…)   |
| Status text          | Current BT/service state                        |
| Received count       | Total records stored since service start        |
| Dupes ignored        | Deduplicated records dropped                    |

### Tab 1 — Incoming Data

A scrollable table of all received records, newest first.

**Columns:** Harvester ID · Block ID · Ripe · Empty · Location · Timestamp

- **Search box** — filters by harvester ID, block ID, record ID, or timestamp
- Ripe bunch count is **colour-coded**: green (≥20), amber (10–19), red (<10)
- 📷 indicator shown when `photo_file` is non-empty

### Tab 2 — Harvester Summary

Per-harvester aggregated daily totals.

- **Date spinner** — switch between available report dates
- **Summary banner** — total ripe / empty / reports / active harvesters for the day
- **Per-harvester card:**
  - Rank badge (by list order)
  - Harvester ID
  - Ripe / Empty / Total bunch counts
  - Efficiency bar = `ripe / total × 100%`
    - Green ≥ 80%, Amber 60–79%, Red < 60%
  - Report count badge

---

## Testing

### Unit Tests

Run with:
```bash
./gradlew test
```

| Test Class        | Covers                                               |
|-------------------|------------------------------------------------------|
| `CsvParserTest`   | Single line, multi-line, header skip, malformed data |
| `LxmfParserTest`  | Header extraction, raw-text fallback, hash format    |
| `KissFramerTest`  | Encode/decode round-trip, escape, multi-frame stream |

### Manual Integration Test

To test without a physical RNode, you can send a raw KISS-framed CSV directly
over a virtual serial port or by temporarily modifying `RNSReceiverService` to
inject test data:

```kotlin
// In RNSReceiverService.onCreate(), for development only:
serviceScope.launch {
    delay(2000)
    val testCsv = "REC-TEST-001,HRV-A01,BLK-03,24,3,1.5543,110.3452,${
        java.time.LocalDateTime.now()
    },test.jpg"
    processCsvContent(testCsv)
}
```

---

## Extending the App

### Add Export to CSV / Excel

In `HarvestRepository`, add:
```kotlin
suspend fun exportToCsv(date: String): File { ... }
```

Call from a new menu item in `MainActivity`.

### Add Map View

Use **OSMDroid** (offline-capable) to plot harvest locations:
```gradle
implementation 'org.osmdroid:osmdroid-android:6.1.17'
```
Create a `MapFragment` and feed it `allRecords` coordinates.

### Add Push Announcements (RNS)

To let the app announce itself on the RNS network (so harvesters can
auto-discover it), add the RNS announce logic to the service using the
full RNS stack via JNI or a companion process.

### Change Database Schema

Increment the Room database version and provide a `Migration`:
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE harvest_records ADD COLUMN supervisorNote TEXT NOT NULL DEFAULT ''")
    }
}
```

---

## Known Limitations & TODOs

| Item | Status | Notes |
|------|--------|-------|
| Full RNS stack integration | 🟡 Partial | RNS frame decoding is implemented; full RNS announce/link establishment requires the Python RNS daemon or a JNI port |
| LXMF encryption validation | 🟡 Partial | Signature bytes are parsed but Ed25519 signature verification is not enforced — add `tink` or `bouncycastle` for production |
| Photo file retrieval | ❌ TODO | `photo_file` field is stored as a filename string; fetching the actual image via RNS resource would need a separate implementation |
| Multi-hop RNS routing | ❌ TODO | The app only receives directly-reachable messages; relay through mesh nodes needs the full RNS stack |
| RNode configuration UI | ❌ TODO | LoRa parameters (frequency, SF, BW) should be configurable from inside the app via KISS config commands |
| Data export (CSV/PDF) | ❌ TODO | Add export via `ShareCompat.IntentBuilder` |
| Offline map view | ❌ TODO | Plot harvest locations using OSMDroid with pre-loaded tiles |
| Authentication | ❌ TODO | No harvester authentication — any device on the RNS network can submit records |
| Battery optimisation | 🟡 Partial | Foreground service prevents Doze killing the connection; add `PowerManager.WakeLock` for SPP if needed |

---

## Licence

MIT — see LICENSE file.
