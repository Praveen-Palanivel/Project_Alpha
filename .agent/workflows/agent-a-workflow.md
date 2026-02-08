---
description: Agent A workflow for LocalStream development - how to work on tasks
---

# Agent A LocalStream Workflow

## Before Starting Work
1. Pull latest from dev branch: `git fetch origin; git checkout dev; git pull origin dev`
2. Checkout agent-A branch: `git checkout agent-A`
3. Merge dev into agent-A: `git merge dev`

## During Work
// turbo-all
1. Make small, focused commits
2. Commit after completing each logical unit of work
3. Push to agent-A branch frequently: `git push origin agent-A`

## PR Strategy
**DO NOT create a PR for every small task.**

- **Minor changes** (docs, single files, incremental work): Just push to `agent-A` and continue working
- **Major milestones only** (completing a full milestone, M1/M2/etc): Create PR to `dev`
- **Blocking dependencies**: Only create PR early if Agent B is blocked waiting

## Task Flow
1. Identify next uncompleted task from Project_Plan.md
2. Implement the task in `/core` or `/discovery` (Agent A territory)
3. Commit with descriptive message
4. Push to agent-A branch
5. **Continue immediately to next task** (don't wait for PR approval)
6. Create PR to dev only at milestone completion

## Commit Message Format
```
Task [ID]: [Brief description]

- [Detail 1]
- [Detail 2]
```

Example:
```
Task A1: Add QUIC transfer protocol specification

- Define stream architecture
- Specify message formats
- Document resume capability
```
