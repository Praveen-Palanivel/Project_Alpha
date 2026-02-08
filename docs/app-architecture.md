# LocalStream App Architecture (Agent B - Task B1)

## 1. Scope and Objectives

This document defines application architecture for:
- Android app in `/android` (Kotlin)
- Windows desktop app in `/windows` (Flutter)

Primary UX objective: make high-speed local transfer feel immediate, reliable, and measurable.

The architecture assumes Agent A provides discovery and transfer core APIs through platform-specific adapters.

## 2. Product Principles for "Ultra High Speed"

- Speed-first UI: always show live transfer speed (MB/s), not only percent complete.
- Low-friction flow: select file -> pick device -> send in three actions.
- Fast feedback: immediate state transition on every user action.
- Parallel-ready model: UI supports one active transfer + queued jobs, expandable later.
- Recoverability: clear retry/resume affordances for transient LAN errors.

## 3. Shared Domain Model

Use equivalent models on both platforms.

### 3.1 Entities

- `PeerDevice`
  - `id: String`
  - `displayName: String`
  - `ipAddress: String`
  - `connectionType: String` (e.g., "QUIC")
  - `lastSeenEpochMs: Long`

- `TransferJob`
  - `jobId: String`
  - `fileName: String`
  - `filePath: String`
  - `totalBytes: Long`
  - `sentBytes: Long`
  - `speedBytesPerSec: Long`
  - `etaSeconds: Long`
  - `status: TransferStatus`
  - `direction: TransferDirection` (`SEND` | `RECEIVE`)
  - `peerId: String`
  - `errorCode: String?`
  - `errorMessage: String?`

### 3.2 State Enums

- `TransferStatus`
  - `IDLE`
  - `PREPARING`
  - `DISCOVERING`
  - `CONNECTING`
  - `TRANSFERRING`
  - `PAUSED`
  - `RETRYING`
  - `COMPLETED`
  - `FAILED`
  - `CANCELLED`

## 4. Android App Architecture (/android)

### 4.1 Pattern

- UI: Jetpack Compose
- State: MVVM (`ViewModel` + `StateFlow`)
- Data/Integration: Repository + Core Adapter
- Background: Foreground service + WorkManager for retry/resume hooks

### 4.2 Android Screen Map

1. `HomeScreen`
   - Actions: Send, Receive
   - Shows active transfer card with speed snapshot

2. `FilePickerScreen` (or launcher flow)
   - Launch system picker
   - Validates file and size

3. `DeviceDiscoveryScreen`
   - Live device list with latency badge and protocol badge (`QUIC`)
   - Manual IP entry fallback

4. `TransferScreen`
   - Progress bar
   - Live MB/s
   - ETA
   - Byte counters
   - Cancel and Retry actions

5. `TransferHistoryScreen` (lightweight, local cache)
   - Completed/failed jobs
   - "Send again" shortcut

### 4.3 Android Navigation Flow

- Send path:
  - `Home -> FilePicker -> DeviceDiscovery -> Transfer`
- Receive path:
  - `Home -> DeviceDiscovery (listen mode) -> Transfer`
- Completion:
  - `Transfer -> History` or back to `Home`

### 4.4 Android Core Integration Points

- `DiscoveryRepository`
  - Starts/stops discovery stream
  - Emits `PeerDevice` list updates

- `TransferRepository`
  - `startSend(filePath, peerIp)`
  - `startReceive(savePath)`
  - `cancel(jobId)`
  - `retry(jobId)`
  - Emits `TransferJob` updates at high frequency (target 4-10 updates/sec UI-throttled)

- `CoreAdapter`
  - Thin bridge over Agent A API
  - Maps core callbacks to domain models
  - Converts bytes/sec to MB/s presentation value

### 4.5 Android Background Handling Assumptions

- Active transfer runs in a Foreground Service with persistent notification.
- Notification shows:
  - file name
  - percent
  - speed MB/s
  - cancel action
- If app process is recreated, ViewModel restores active job from repository cache.
- WorkManager can schedule retry for recoverable failures (e.g., peer briefly unavailable).

### 4.6 Android Permission Strategy

- Storage/media permissions per Android API level
- Nearby devices/network permissions as required by discovery transport
- Runtime permission gate before entering send/receive flow
- If denied, show single-action remediation sheet to app settings

## 5. Windows App Architecture (/windows)

### 5.1 Pattern

- UI: Flutter (desktop)
- State: `ChangeNotifier` or `Riverpod` (recommended: Riverpod for predictable state graph)
- Data/Integration: Repository + Platform bridge to Agent A core library
- Background behavior: system tray + window-independent transfer controller

### 5.2 Windows Screen/Surface Map

1. `DashboardView`
   - Send/Receive primary actions
   - Drag-and-drop target zone
   - Active transfer summary

2. `FileSelectionDialog`
   - Native dialog for one/many files

3. `DeviceDiscoveryPanel`
   - Nearby devices list
   - Manual IP entry
   - Last-seen freshness indicator

4. `TransferView`
   - Detailed queue list
   - Per-item progress + MB/s + ETA
   - Cancel/retry actions

5. `HistoryView`
   - Recent jobs with status filters

6. `SystemTrayMenu`
   - Show/hide window
   - Current transfer status
   - Pause/cancel active transfer
   - Exit

### 5.3 Windows Navigation Flow

- Send path:
  - `Dashboard -> FileSelectionDialog -> DeviceDiscoveryPanel -> TransferView`
- Receive path:
  - `Dashboard -> DeviceDiscoveryPanel (listen mode) -> TransferView`
- Background path:
  - `TransferView -> Minimize to tray` (transfer continues)

### 5.4 Windows Core Integration Points

- `DiscoveryRepository`
  - Maintains stream of discovered peers
  - Debounces duplicate broadcasts

- `TransferRepository`
  - Starts send/receive jobs
  - Maintains queue and active job pointer
  - Exposes progress stream to UI

- `CoreBridge`
  - FFI/plugin boundary wrapping Agent A APIs
  - Converts callback stream to Dart stream
  - Normalizes error codes/messages

### 5.5 Windows Background Handling Assumptions

- Transfer engine is decoupled from visible route/widget.
- Minimizing or closing window to tray does not stop transfer.
- Tray notifications for:
  - transfer start
  - transfer success
  - transfer failure with retry shortcut

## 6. Speed Visualization and Performance UX Spec

Applies to both apps.

- Primary metric: current throughput in MB/s
- Secondary metrics: average MB/s, ETA, transferred bytes
- Progress refresh:
  - core callback may be high-frequency
  - UI updates throttled to ~200ms for smoothness without jank
- Visual treatment:
  - "Speed pill" always visible during transfer
  - Spike-safe smoothing window (e.g., moving average over 1-2 seconds)
- Completion feedback:
  - total time
  - average throughput

## 7. Error Handling Contract (UI-Level)

- Recoverable errors:
  - peer temporarily unreachable
  - network switch
  - local file lock
  - Action: Retry/Resume CTA shown inline

- Non-recoverable errors:
  - permission denied
  - invalid destination path
  - checksum mismatch requiring restart
  - Action: Clear message + "Start New Transfer"

- Every failure state must include:
  - plain-language message
  - machine error code
  - next action button

## 8. Logging and Telemetry (Local-Only)

- Local debug logs only (no cloud dependency)
- Per job:
  - start/end timestamps
  - total bytes
  - average speed
  - terminal status
- Optional developer panel in debug builds for transfer diagnostics

## 9. Implementation Hand-off to B2/B3

This architecture is considered complete for B1 when:
- Android scaffold can implement these screens/routes with placeholder data.
- Windows scaffold can implement the dashboard/discovery/transfer surfaces.
- Both apps can display mock live speed updates before real core wiring.

Next tasks:
- B2: Android scaffold from sections 4 + 6 + 7
- B3: Windows scaffold from sections 5 + 6 + 7
