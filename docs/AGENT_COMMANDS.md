# 🎮 LOCALSTREAM — AGENT COMMANDS QUICK REFERENCE

> **Copy-paste these commands into your AI chatbox to control agents**

---

## ⚡ QUICK START COMMANDS

### Start Agent A (Core Systems)
```
You are Agent A for LocalStream. Read docs/Project_Plan.md and docs/AGENT_A_INSTRUCTIONS.md. Pull latest from dev branch. Find your next uncompleted task and begin work. Focus on ULTRA HIGH SPEED TRANSFER as the primary goal. Create a PR when done.
```

### Start Agent B (Applications)
```
You are Agent B for LocalStream. Read docs/Project_Plan.md and docs/AGENT_B_INSTRUCTIONS.md. Pull latest from dev branch. Find your next uncompleted task and begin work. Build apps that showcase ULTRA HIGH SPEED TRANSFER. Create a PR when done.
```

---

## ⏹️ STOP ALL AGENTS

```
STOP. Save your current state. Document what you were working on. Do not make any more changes until you receive a START command.
```

---

## 📊 STATUS CHECK

### Agent A Status
```
You are Agent A. Report your current status: What task are you working on? What is completed? What is blocked? What's next?
```

### Agent B Status
```
You are Agent B. Report your current status: What task are you working on? What is completed? What is blocked? What's next?
```

---

## 🎯 PROJECT GOALS REMINDER

When starting any agent, they work toward these goals:

| Goal | Description | Owner |
|------|-------------|-------|
| **QUIC-Powered Ultra High Speed** | QUIC protocol, >100 MB/s, 0-RTT | Agent A |
| **Android Application** | Kotlin native app + cronet | Agent B |
| **Windows Application** | Flutter desktop app + quiche | Agent B |

---

## 📂 TERRITORY MAP

| Agent | Owns | Cannot Touch |
|-------|------|--------------|
| Agent A | `/core`, `/discovery` | `/android`, `/windows` |
| Agent B | `/android`, `/windows` | `/core`, `/discovery` |

---

## 🔧 SPECIFIC TASK COMMANDS

### Agent A Tasks

**Protocol Design:**
```
You are Agent A. Design the QUIC-based transfer protocol for maximum speed. Document in docs/protocol.md. Include stream design, 0-RTT handshake, parallel streams. Target: >100 MB/s on gigabit network.
```

**Discovery:**
```
You are Agent A. Implement device discovery in /discovery. Use UDP broadcast. Detection must complete in <2 seconds.
```

**Core Sender:**
```
You are Agent A. Implement the QUIC file sender in /core. Use multiplexed streams for parallel chunk transfer. Support progress callbacks.
```

**Core Receiver:**
```
You are Agent A. Implement the QUIC file receiver in /core. Support connection migration for resume. Validate with checksums.
```

**Performance Tuning:**
```
You are Agent A. Optimize QUIC transfer performance. Tune stream count and chunk sizes. Target: Saturate available bandwidth.
```

---

### Agent B Tasks

**App Architecture:**
```
You are Agent B. Design the app architecture. Document in docs/app-architecture.md. Make it optimized for showing FAST transfers.
```

**Android Scaffold:**
```
You are Agent B. Create the Android app scaffold in /android. Set up project structure, file picker, permissions.
```

**Windows Scaffold:**
```
You are Agent B. Create the Windows app scaffold in /windows. Set up Flutter project, main window, file dialogs.
```

**Android Integration:**
```
You are Agent B. Wire the Android app to the core library. Display progress, speed, and status in real-time.
```

**Windows Integration:**
```
You are Agent B. Wire the Windows app to the core library. Display progress and speed. Add system tray support.
```

**UX Polish:**
```
You are Agent B. Polish the user experience on both apps. Show speed indicators (MB/s). Make users feel the SPEED.
```

---

## 🔄 WORKFLOW COMMANDS

### Resume Work
```
You are Agent [A/B]. Continue your previous work on LocalStream. Check your open PRs and any pending tasks. Resume where you left off.
```

### Create PR
```
You are Agent [A/B]. Your work is complete. Create a pull request to dev branch. Include a clear description of changes and testing done.
```

### Review PR
```
You are Agent [A/B]. Review the open PR from the other agent. Check for issues. Approve if good or request changes if needed.
```

---

## 🚨 EMERGENCY COMMANDS

### Hard Stop
```
STOP IMMEDIATELY. Do not commit anything. Do not push anything. Wait for further instructions.
```

### Rollback
```
You are Agent [A/B]. Your last change caused issues. Revert your last commit. Explain what went wrong.
```

### Sync with Dev
```
You are Agent [A/B]. Pull the latest dev branch. Resolve any merge conflicts. Report what changed.
```

---

## 📋 COMMAND FLOW EXAMPLE

1. **Start session:** Use Quick Start command
2. **Check progress:** Use Status command
3. **Assign specific work:** Use Task-specific command
4. **End session:** Use Stop command
5. **Resume next day:** Use Resume Work command

---

## 💡 TIPS

- Always start agents with the full context (read docs first)
- One agent = one chat session (don't mix agents)
- Use STATUS command before starting to see where things are
- STOP command saves state - agents remember where they were
- Be specific about which task when you want focused work

---

**END OF QUICK REFERENCE**
