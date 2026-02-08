---
description: Agent A workflow for LocalStream development - how to work on tasks
---

# Agent A LocalStream Workflow

## Before EACH Task (CRITICAL - Stay in Sync)
// turbo-all
1. Fetch and pull dev: `git fetch origin; git checkout dev; git pull origin dev`
2. Checkout agent-A: `git checkout agent-A`
3. Merge dev into agent-A: `git merge dev`
4. Resolve any conflicts if needed

**This must be done before starting EVERY new task to stay in sync with Agent B.**

## During Work
// turbo-all
1. Make small, focused commits
2. Commit after completing each logical unit of work
3. Push to agent-A branch frequently: `git push origin agent-A`

## After EACH Task Completion
// turbo-all
1. Commit all changes
2. Push to agent-A: `git push origin agent-A`
3. Pull from dev again before next task: `git fetch origin; git checkout dev; git pull origin dev`
4. Merge into agent-A: `git checkout agent-A; git merge dev`

## PR Strategy
**DO NOT create a PR for every small task.**

- **Minor changes** (docs, single files, incremental work): Just push to `agent-A` and continue working
- **Major milestones only** (completing a full milestone, M1/M2/etc): Create PR to `dev`
- **Blocking dependencies**: Only create PR early if Agent B is blocked waiting

## Task Flow
1. **SYNC: Pull from dev, merge into agent-A**
2. Identify next uncompleted task from Project_Plan.md
3. Implement the task in `/core` or `/discovery` (Agent A territory)
4. Commit with descriptive message
5. Push to agent-A branch
6. **SYNC AGAIN before next task**
7. Create PR to dev only at milestone completion

## Commit Message Format
```
Task [ID]: [Brief description]

- [Detail 1]
- [Detail 2]
```
