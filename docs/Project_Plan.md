LOCALSTREAM — AUTONOMOUS PROJECT PLAN

(Single Source of Truth)

0. PROJECT DEFINITION
Objective

Build a local, high-speed, offline file sharing application supporting:

Android ↔ Android

Android ↔ Windows

Windows ↔ Windows

Non-Goals

No macOS

No internet servers

No cloud

No accounts

No encryption beyond basic session safety (for now)

1. PROJECT GOVERNANCE
Branching

agent-A → Core & Networking

agent-B → Apps & UI

dev → Integration (protected)

main → Releases (optional, later)

Enforcement

CODEOWNERS enforced

PRs required

1 approval minimum

No bypassing rules

2. AGENT ROLES (IMMUTABLE UNTIL CHANGE)
🧠 Agent A — Core Systems

Owns directories

/core
/discovery


Accountabilities

Transfer protocol

Discovery

Performance

Cross-platform compatibility

Forbidden

Editing /android

Editing /windows

🎨 Agent B — Applications

Owns directories

/android
/windows


Accountabilities

UI

File picking

Permissions

User experience

Forbidden

Editing /core

Editing /discovery

3. TECH STACK (LOCKED)
Networking

Primary: TCP over local Wi-Fi (initial)

Future: QUIC upgrade

Discovery

UDP broadcast (IPv4)

Manual IP fallback

Languages

Core logic: language-agnostic protocol (documented)

Android: Kotlin

Windows: Flutter (Windows target)

4. MILESTONES OVERVIEW
Milestone	Description	Owner
M1	Protocol & discovery spec	Agent A
M2	App scaffolds	Agent B
M3	Same-platform transfer	A + B
M4	Cross-platform transfer	A + B
M5	Stability & polish	A + B
5. DETAILED TASK BREAKDOWN
MILESTONE 1 — FOUNDATIONS
Task A1 — Define Transfer Protocol

Agent: A
Output: docs/protocol.md

Must specify:

Handshake sequence

Metadata fields (name, size, checksum)

Chunk size (default: 1MB)

Resume capability (offset-based)

Error codes

Done when:

Another agent can implement sender/receiver from doc alone

Task A2 — Define Discovery Mechanism

Agent: A
Output: docs/discovery.md

Must specify:

UDP broadcast port

Broadcast payload format

Device naming rules

Collision handling

Timeout behavior

Task B1 — App Architecture Draft

Agent: B
Output: docs/app-architecture.md

Must specify:

Screens

Navigation flow

Where core logic plugs in

Background handling assumptions

MILESTONE 2 — SCAFFOLDS
Task B2 — Android Scaffold

Agent: B
Directory: /android

Must include:

Empty app

File picker

Permission handling

Placeholder send/receive buttons

No real networking yet.

Task B3 — Windows Scaffold

Agent: B
Directory: /windows

Must include:

App window

File selection

Placeholder transfer screen

MILESTONE 3 — SAME PLATFORM TRANSFER
Task A3 — Core Sender

Agent: A
Directory: /core

Must implement:

File read

Chunk send

Progress callbacks

Task A4 — Core Receiver

Agent: A

Must implement:

File write

Resume logic

Checksum validation

Task B4 — Android ↔ Android Wiring

Agent: B

Must:

Connect UI → core

Show progress

Handle failure gracefully

Task B5 — Windows ↔ Windows Wiring

Agent: B

Same expectations as Android.

MILESTONE 4 — CROSS PLATFORM
Task A5 — Compatibility Layer

Agent: A

Ensure:

Endianness consistency

File path normalization

Platform-agnostic protocol use

Task B6 — Android ↔ Windows UI Flow

Agent: B

Must:

Discover Windows peer

Initiate transfer

Display correct status

MILESTONE 5 — POLISH
Task A6 — Performance Tuning

Agent: A

Focus:

Larger chunks

Parallel streams (optional)

Error recovery

Task B7 — UX Polish

Agent: B

Focus:

Progress clarity

Cancel / retry

Clear success state

6. AUTONOMOUS WORK RULES

Agents pull dev before starting work

One task = one PR

Small commits only

No cross-ownership edits

If blocked → document & wait

7. STOP CONDITIONS

Agents must STOP if:

Protocol unclear

Cross-ownership dependency unresolved

You say STOP

8. CHANGE CONDITIONS

Only you may:

Change roles

Change tech

Change scope

Agents adapt immediately.

9. SUCCESS DEFINITION

Project is successful when:

Large files (>5GB) transfer locally

No internet

Stable UI

Cross-platform verified

10. OPERATIONAL COMMANDS (YOU)

START → Begin next uncompleted task

STOP → Pause all work

CHANGE <thing> → Modify constitution

END OF PLAN.