# LOCALSTREAM — AUTONOMOUS PROJECT PLAN

> **Single Source of Truth**

---

## 🎯 CORE GOALS

> **These are the PRIMARY objectives all agents must work toward:**

### Goal 1: ULTRA HIGH SPEED TRANSFER (QUIC-POWERED)
- **QUIC protocol** as the primary transport layer
- Achieve maximum possible transfer speeds over local Wi-Fi
- Target: Saturate gigabit network bandwidth
- Zero unnecessary overhead or delays
- QUIC multiplexed streams for parallel chunk transfer
- Built-in resume capability via QUIC connection migration
- 0-RTT connection establishment for instant transfers

### Goal 2: ANDROID APPLICATION
- Native Android app (Kotlin)
- Clean, intuitive UI for file selection and transfer
- Full permission handling (storage, Wi-Fi)
- Background transfer support
- Progress display and notifications

### Goal 3: WINDOWS APPLICATION  
- Windows desktop app (Flutter)
- Native file dialogs and drag-drop support
- System tray integration
- Multi-file queue management
- Cross-platform protocol compatibility

---

## 0. PROJECT DEFINITION

### Objective

Build a local, high-speed, offline file sharing application supporting:

- Android ↔ Android
- Android ↔ Windows
- Windows ↔ Windows

### Non-Goals

- ❌ No macOS
- ❌ No internet servers
- ❌ No cloud
- ❌ No accounts
- ❌ No encryption beyond basic session safety (for now)

---

## 1. PROJECT GOVERNANCE

### Branching Strategy

| Branch    | Purpose                  | Protected |
|-----------|--------------------------|-----------|
| `agent-A` | Core & Networking        | No        |
| `agent-B` | Apps & UI                | No        |
| `dev`     | Integration              | ✅ Yes    |
| `main`    | Releases (optional)      | ✅ Yes    |

### Enforcement Rules

- ✅ CODEOWNERS enforced
- ✅ PRs required for `dev` and `main`
- ✅ 1 approval minimum
- ✅ No bypassing rules

---

## 2. AGENT ROLES

> ⚠️ **IMMUTABLE UNTIL EXPLICIT CHANGE**

### 🧠 Agent A — Core Systems

**Owns:**
```
/core
/discovery
```

**Accountabilities:**
- Transfer protocol
- Discovery mechanism
- Performance optimization
- Cross-platform compatibility

**Forbidden:**
- ❌ Editing `/android`
- ❌ Editing `/windows`

---

### 🎨 Agent B — Applications

**Owns:**
```
/android
/windows
```

**Accountabilities:**
- UI implementation
- File picking
- Permissions handling
- User experience

**Forbidden:**
- ❌ Editing `/core`
- ❌ Editing `/discovery`

---

## 3. TECH STACK (LOCKED)

| Component   | Technology                          |
|-------------|-------------------------------------|
| Networking  | **QUIC** over local Wi-Fi           |
| Fallback    | TCP (if QUIC unavailable)           |
| Discovery   | UDP broadcast (IPv4) + Manual IP    |
| Android     | Kotlin + cronet/quiche              |
| Windows     | Flutter + quiche/msquic             |
| Protocol    | Language-agnostic (documented)      |

### Why QUIC?
- **Multiplexed streams** — parallel chunk transfers without head-of-line blocking
- **0-RTT handshake** — instant connection, no TCP slow start
- **Built-in encryption** — TLS 1.3 by default
- **Connection migration** — seamless resume if network changes
- **Congestion control** — optimized for high throughput

---

## 4. MILESTONES OVERVIEW

| Milestone | Description              | Owner   | Status |
|-----------|--------------------------|---------|--------|
| M1        | Protocol & discovery spec| Agent A | ⬜     |
| M2        | App scaffolds            | Agent B | ⬜     |
| M3        | Same-platform transfer   | A + B   | ⬜     |
| M4        | Cross-platform transfer  | A + B   | ⬜     |
| M5        | Stability & polish       | A + B   | ⬜     |

---

## 5. DETAILED TASK BREAKDOWN

### MILESTONE 1 — FOUNDATIONS

#### Task A1 — Define Transfer Protocol

| Field      | Value                    |
|------------|--------------------------|
| Agent      | A                        |
| Output     | `docs/protocol.md`       |
| Depends On | None                     |
| Status     | ⬜ Not Started           |

**Must Specify:**
- [ ] QUIC stream design (control stream + data streams)
- [ ] Handshake sequence over QUIC
- [ ] Metadata fields (name, size, checksum)
- [ ] Chunk size (default: 4MB for QUIC)
- [ ] Parallel stream count optimization
- [ ] Resume capability (QUIC connection migration)
- [ ] Error codes
- [ ] Fallback to TCP behavior

**Done When:**
> Another agent can implement sender/receiver from doc alone

---

#### Task A2 — Define Discovery Mechanism

| Field      | Value                    |
|------------|--------------------------|
| Agent      | A                        |
| Output     | `docs/discovery.md`      |
| Depends On | None                     |
| Status     | ⬜ Not Started           |

**Must Specify:**
- [ ] UDP broadcast port
- [ ] Broadcast payload format
- [ ] Device naming rules
- [ ] Collision handling
- [ ] Timeout behavior

---

#### Task B1 — App Architecture Draft

| Field      | Value                       |
|------------|-----------------------------|
| Agent      | B                           |
| Output     | `docs/app-architecture.md`  |
| Depends On | None                        |
| Status     | ⬜ Not Started              |

**Must Specify:**
- [ ] Screens
- [ ] Navigation flow
- [ ] Where core logic plugs in
- [ ] Background handling assumptions

---

### MILESTONE 2 — SCAFFOLDS

#### Task B2 — Android Scaffold

| Field      | Value                    |
|------------|--------------------------|
| Agent      | B                        |
| Directory  | `/android`               |
| Depends On | B1                       |
| Status     | ⬜ Not Started           |

**Must Include:**
- [ ] Empty app structure
- [ ] File picker integration
- [ ] Permission handling
- [ ] Placeholder send/receive buttons

> ⚠️ No real networking yet.

---

#### Task B3 — Windows Scaffold

| Field      | Value                    |
|------------|--------------------------|
| Agent      | B                        |
| Directory  | `/windows`               |
| Depends On | B1                       |
| Status     | ⬜ Not Started           |

**Must Include:**
- [ ] App window
- [ ] File selection dialog
- [ ] Placeholder transfer screen

---

### MILESTONE 3 — SAME PLATFORM TRANSFER

#### Task A3 — Core Sender

| Field      | Value                    |
|------------|--------------------------|
| Agent      | A                        |
| Directory  | `/core`                  |
| Depends On | A1                       |
| Status     | ⬜ Not Started           |

**Must Implement:**
- [ ] File read operations
- [ ] Chunk send logic
- [ ] Progress callbacks

---

#### Task A4 — Core Receiver

| Field      | Value                    |
|------------|--------------------------|
| Agent      | A                        |
| Directory  | `/core`                  |
| Depends On | A1                       |
| Status     | ⬜ Not Started           |

**Must Implement:**
- [ ] File write operations
- [ ] Resume logic
- [ ] Checksum validation

---

#### Task B4 — Android ↔ Android Wiring

| Field      | Value                    |
|------------|--------------------------|
| Agent      | B                        |
| Directory  | `/android`               |
| Depends On | A3, A4, B2               |
| Status     | ⬜ Not Started           |

**Must:**
- [ ] Connect UI → core
- [ ] Show progress
- [ ] Handle failure gracefully

---

#### Task B5 — Windows ↔ Windows Wiring

| Field      | Value                    |
|------------|--------------------------|
| Agent      | B                        |
| Directory  | `/windows`               |
| Depends On | A3, A4, B3               |
| Status     | ⬜ Not Started           |

**Must:**
- [ ] Connect UI → core
- [ ] Show progress
- [ ] Handle failure gracefully

---

### MILESTONE 4 — CROSS PLATFORM

#### Task A5 — Compatibility Layer

| Field      | Value                    |
|------------|--------------------------|
| Agent      | A                        |
| Directory  | `/core`                  |
| Depends On | A3, A4                   |
| Status     | ⬜ Not Started           |

**Must Ensure:**
- [ ] Endianness consistency
- [ ] File path normalization
- [ ] Platform-agnostic protocol use

---

#### Task B6 — Android ↔ Windows UI Flow

| Field      | Value                    |
|------------|--------------------------|
| Agent      | B                        |
| Directories| `/android`, `/windows`   |
| Depends On | A5, B4, B5               |
| Status     | ⬜ Not Started           |

**Must:**
- [ ] Discover cross-platform peer
- [ ] Initiate transfer
- [ ] Display correct status

---

### MILESTONE 5 — POLISH

#### Task A6 — Performance Tuning

| Field      | Value                    |
|------------|--------------------------|
| Agent      | A                        |
| Directory  | `/core`                  |
| Depends On | A5                       |
| Status     | ⬜ Not Started           |

**Focus:**
- [ ] QUIC stream tuning (optimal parallel stream count)
- [ ] Chunk size optimization (4MB, 8MB, 16MB)
- [ ] 0-RTT optimization
- [ ] Congestion control tuning for LAN
- [ ] Error recovery

---

#### Task B7 — UX Polish

| Field      | Value                    |
|------------|--------------------------|
| Agent      | B                        |
| Directories| `/android`, `/windows`   |
| Depends On | B6                       |
| Status     | ⬜ Not Started           |

**Focus:**
- [ ] Progress clarity
- [ ] Cancel / retry
- [ ] Clear success state

---

## 6. TASK DEPENDENCY GRAPH

```
M1 (Foundation)
├── A1 (Protocol)
│   ├── A3 (Sender)
│   └── A4 (Receiver)
├── A2 (Discovery)
└── B1 (Architecture)
    ├── B2 (Android Scaffold)
    └── B3 (Windows Scaffold)

M3 (Same Platform)
├── A3 + A4 + B2 → B4 (Android Wiring)
└── A3 + A4 + B3 → B5 (Windows Wiring)

M4 (Cross Platform)
├── A3 + A4 → A5 (Compatibility)
└── A5 + B4 + B5 → B6 (Cross UI)

M5 (Polish)
├── A5 → A6 (Performance)
└── B6 → B7 (UX)
```

---

## 7. AUTONOMOUS WORK RULES

1. ✅ Agents pull `dev` before starting work
2. ✅ One task = one PR
3. ✅ Small commits only
4. ✅ No cross-ownership edits
5. ✅ If blocked → document in PR & wait
6. ✅ Role branch only: Agent A works on `agent-A`, Agent B works on `agent-B` (no per-task branches)

---

## 8. STOP CONDITIONS

> Agents **MUST STOP** if:

- ⚠️ Protocol unclear
- ⚠️ Cross-ownership dependency unresolved
- 🛑 Human says **STOP**

---

## 9. CHANGE CONDITIONS

> **Only the human may:**
- Change roles
- Change tech stack
- Change scope

Agents adapt immediately upon change.

---

## 10. SUCCESS DEFINITION

Project is **SUCCESSFUL** when:

- [ ] Large files (>5GB) transfer locally
- [ ] No internet required
- [ ] Stable UI on both platforms
- [ ] Cross-platform verified (Android ↔ Windows)

---

## 11. OPERATIONAL COMMANDS

| Command          | Action                         |
|------------------|--------------------------------|
| `START`          | Begin next uncompleted task    |
| `STOP`           | Pause all work                 |
| `CHANGE <thing>` | Modify this plan               |
| `STATUS`         | Report current progress        |

---

**END OF PLAN**
