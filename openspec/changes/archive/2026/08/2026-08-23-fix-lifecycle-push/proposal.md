# Proposal: fix-lifecycle-push

## Why

The factory never pushes the commits that `TaskLifecycleStore` writes — task started, resume decision, terminal outcomes, cleanup, tracker-write-confirmed. Push exists only as a round-boundary decorator (`PushBestEffortAttemptPersistence`) plus two ad-hoc caller-side pushes in container termination, so a task's final commits (`task completed`, `cleanup`) stay local forever unless a human pushes by hand. This violates the existing spec scenario "Cleanup works after dispose" (git-task-persistence: "the outcome and cleanup commits are still created factory-side **and pushed**") and breaks the dual-write ordering the park protocol depends on: the tracker announces a park to every instance while origin still lacks the park commit and its `pendingTrackerWrite` marker, so reconcile-on-resume from another machine reads stale state. The gap was masked by the operator's `push.default = matching` sending factory branches along with manual pushes.

## What Changes

- **MODIFIED** `git-task-persistence` — three tiers of replication, all in one change:
  - Tier 1 (edge): every lifecycle commit is followed by a best-effort push, in both host and sandboxed mode, before the lifecycle call returns to its caller — restoring the replicate-before-signal ordering for parks.
  - Tier 2 (level): at natural task touchpoints (resume start, terminal boundary), the factory compares the local branch tip against the origin tip and pushes best-effort when origin is behind — converting missed edges into eventually-delivered state.
  - Tier 3 (fence): before a park's terminal tracker write (Escalated/Paused), the factory verifies the branch is delivered to origin — verify, push, bounded retry — so the tracker never signals a park whose commit origin lacks; a persistent delivery failure never blocks the park itself, it is surfaced instead.
- Push mechanics consolidation: the duplicated `remote get-url origin` check (3 copies + 1 variant) and the inline `push origin branch:branch` command (3 copies) collapse into one shared core; the two caller-side lifecycle pushes in container termination are absorbed by tier 1 and removed.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `git-task-persistence`: the "Best-effort push" requirement widens from round commits to every lifecycle commit; new requirements for level-based origin reconciliation and the pre-park delivery fence; the "Cleanup works after dispose" scenario becomes implemented behavior instead of a declaration.

## Goals

- G1: The spec's own push promise holds — a completed task's outcome and cleanup commits reach origin without human involvement.
- G2: Origin becomes a valid reconcile source for cross-instance resume: a parked task's `task.json` (outcome, escalation report, `pendingTrackerWrite` marker) is on origin before the tracker announces the park.
- G3: Push mechanics exist exactly once — one origin check, one refspec push, one remote-tip reader — shared by every push path.

## Non-Goals

- NG1: Recording container-mode parks (Escalated/Paused) as lifecycle commits — the task-5.2 scope note of add-serve-sandbox-lifecycle stands; where no commit exists, there is nothing to push, and the escalation report already reaches origin via the round's state-commit push.
- NG2: A periodic push daemon or background reconciliation loop — tier 2 runs only at touchpoints where the task is already in hand.
- NG3: Any change to the `gitobjects` module — it stays hermetic and network-free by design.
- NG4: Force-push, history repair, or non-fast-forward recovery — the existing never-force discipline is untouched.
- NG5: An "origin behind by N" column in `gnomish list` — observability beyond WARN logs and the park-report note is deferred.
- NG6: Making push load-bearing for `Completed`/`Aborted` outcomes — the fence guards only marker-bearing parks; everything else stays best-effort.

## Users & Scenarios

- U1: An operator whose factory completed a task opens the PR on GitHub and sees the cleanup commit at the tip — no manual `git push`, no `.gnomish-task/` residue.
- U2: A second factory instance picks up a task the first instance parked; fetching origin yields the park commit with the escalation report and pending marker, so reconcile-on-resume works from any machine.
- U3: An operator running purely locally (no `origin` remote) sees no new warnings and no behavior change — every new push point is a silent no-op, exactly like the existing round push.

## Requirements

### Functional

- FR1: After every lifecycle commit a mode records — task started, resume decision, every terminal outcome, the `Completed` cleanup commit, and, in host mode only, the tracker-write-confirmed commit — the adapter SHALL push the task branch to origin best-effort, in both host mode (`GitTaskRepository`) and sandboxed mode (`GitObjectsTaskRepository`), with the same never-throw, never-force, exact-refspec discipline as the round push. Sandboxed mode has no `confirmTerminalWrite` and records no tracker-write-confirmed commit (the same scope note NG1 stands on). One push after `recordOutcome(Completed)` covers both the outcome and cleanup commits.
- FR2: The lifecycle push SHALL complete (succeed, fail with WARN, or no-op without origin) before the lifecycle call returns, so a caller that proceeds to a tracker write does so after the replication attempt — never before it.
- FR3: At resume start and at the terminal boundary of a run, the factory SHALL compare the local task-branch tip with the origin tip and push best-effort when origin is behind — except at a terminal boundary that parks the task, where the FR4 fence supersedes the comparison over the same unchanged tip. The local tip is supplied by the caller from its mode-native reader; the comparison costs one `ls-remote` and never blocks the run on failure.
- FR4: Before the terminal tracker write of a park (Escalated/Paused) in host mode, the factory SHALL verify the branch tip is delivered to origin — remote-tip ancestry check, then push with one bounded re-attempt — reusing the same delivery protocol external checks use for attempt commits. With no origin configured the fence is a silent no-op.
- FR5: A fence that exhausts its re-attempts SHALL NOT block or fail the park: the tracker write proceeds, the park report carries a note that origin is behind, and the `pendingTrackerWrite` marker stays governed by the existing confirm protocol.
- FR6: The two caller-side lifecycle pushes in container termination SHALL be removed in the same change that introduces tier 1, so exactly one code path owns the push-after-lifecycle-commit rule.

### Non-Functional Reliability

- NFR-R1: Every push remains idempotent and fast-forward-only; repeating a push after a crash or on a tier-2 touchpoint is always safe. No push failure ever aborts a task or loses a recorded outcome.
- NFR-R2: The fence's re-attempt count is bounded and small (mirroring the terminal tracker write's bounded retry); fence failure degrades to tier-1 semantics, never to a hung or abandoned claim.

### Non-Functional Observability

- NFR-O1: Every failed push logs one WARN carrying task, branch, and trigger context (which lifecycle event or touchpoint); a tier-2 catch-up push logs the fact that origin was behind. A fence exhaustion is visible both in the log and in the park report the human reads.

### Non-Functional Security

- NFR-S1: Push stays the factory-side adapter's monopoly: no push machinery, credentials, or remote address enters a task environment or the gnome's instructions; `gitobjects` gains no network operation. The existing push safety rules (exact refspec, never force) apply to every new push point.
- NFR-S2: No credential embedded in the `origin` URL ever leaves the git seam. Git's captured stderr — the text every new push point logs on failure and the delivery paths put in the detail a tracker publishes — SHALL be stripped of URL userinfo before any caller receives it, so a clone configured with `https://<token>@host/...` cannot leak that token into an operator log or a tracker comment.

### Non-Functional Cost

- NFR-C1: Tier 2 adds at most one `ls-remote` round-trip per touchpoint per task — no polling, no timers, no per-round overhead beyond the pushes that already run.

## Operator Experience Criteria

- UX1: After a factory-completed task, the operator sees the branch on the remote in its final form (cleanup at tip) without touching git; the PR diff no longer shows `.gnomish-task/` files.
- UX2: A parked task's report on the tracker reflects replication truth: when origin could not be brought up to date, the report says so in one line the human can act on.
- UX3: Local-only runs stay silent: no new warnings, prompts, or configuration.

## Success Metrics

- M1: E2E (bare-repo origin): a task driven to `Completed` in host mode and in sandboxed mode ends with the origin tip equal to the local branch tip, with zero manual pushes.
- M2: Ordering test: for a host-mode park, the origin branch already carries the park commit (outcome + pending marker) at the moment the tracker's terminal write is invoked.
- M3: `grep` over production sources finds exactly one construction site of each remote primitive — the push command, the origin-presence check, and the `ls-remote` tip read (the shared core) — and zero pushes in `bootstrap` termination paths.

## Open Questions

- Q1: Should the tier-2 comparison also run at claim time for a task another instance last touched (fresh claim of a returned task), or is resume-start coverage sufficient? Default: resume-start only; claim paths that funnel into resume inherit it.
