# Proposal: wire-host-mid-round-push

## Why

The `git-task-persistence` "Best-effort push" requirement already mandates the host half of
the mid-round push: *"The live loop SHALL notice a moved branch tip — host: after tool events
(a gnome commit) … and push best-effort mid-round"*, with the scenario "Gnome commit triggers
a push". The implementing class `MidRoundPushListener` (FR11 of add-git-workflow) exists, is
fully spec-covered and mutation-gated — but a `/check-issue` verification (2026-09-01) proved
it has **never been constructed in production**: task 3.9 of add-git-workflow shipped the
listener body only, and the wiring its own javadoc defers to "section 4" never landed. Host
git-mode rounds run with the default no-op `roundListener()`, so a gnome commit made through a
Bash tool mid-round reaches origin only at the round boundary. Until then the commit is
invisible to other instances and to the operator watching origin, and a factory crash
mid-round delays cross-instance resume on the freshest work (local durability is unaffected).

## What Changes

- **MODIFIED**: git-mode host runs compose a per-round `MidRoundPushListener` into the
  agent-cli live loop's listener chain, through the same `RoundEnvironmentSource.roundListener()`
  seam sandbox mode already uses for `MidRoundHarvestListener`.
- **MODIFIED**: the wiring plumbing this needs — a seam exposing the host round source and a
  git-mode-only attachment point in the run assembly (details in design.md).
- **MODIFIED**: `MidRoundPushListener`'s stale javadoc pointer ("section 4's job") is replaced
  with the actual wiring point.
- No behavior change in in-place (legacy) host runs or in container runs.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None — `skip_specs: true`. The `git-task-persistence` spec already states the required
behavior (requirement "Best-effort push", scenario "Gnome commit triggers a push"; push
preconditions in "Push safety rules"); this change brings the implementation into compliance
with the existing requirement, so writing a delta would restate the main spec unchanged.

## Goals

- G1 — a gnome commit landing mid-round in a git-mode host run is pushed best-effort to
  origin when the next tool event is observed, closing the host half of the spec's mid-round
  push mandate.
- G2 — in-place host runs and container runs behave exactly as before: no listener is wired
  there, no new log lines, no new git invocations.

## Non-Goals

- NG1 — no change to push semantics: `BestEffortPush`, its ancestry precondition, refspec, and
  never-`--force` rules stay verbatim (spec "Push safety rules").
- NG2 — no event-driven tip watching (`.git/logs/HEAD` inotify or similar); the per-tool-event
  poll the listener already implements is the mechanism.
- NG3 — no changes to `MidRoundPushListener`'s observation logic itself (FR13 of
  harden-logging-observability governs it and is already implemented and spec-covered).
- NG4 — no spec deltas (see Capabilities) and no changes to the sandbox harvest path.

## Users & Scenarios

- U1 — an operator running `gnomish run --mode git` (or a host-mode `gnomish take`) watches the
  task branch on origin advance as the gnome commits, instead of only at round boundaries.
- U2 — after a factory crash mid-round, another instance resuming the task finds the pushed
  mid-round commits on origin instead of only the last round-boundary state.

## Requirements

### Functional

- FR1 — git-mode host executor rounds construct one fresh `MidRoundPushListener` per round
  (its documented lifecycle) from the round's own facts — worktree root, taskId, stage,
  attempt, branch — and join it to the live loop's listener composition.
- FR2 — the listener joins via the existing `RoundEnvironmentSource.roundListener()` seam; no
  second listener-wiring mechanism is introduced.
- FR3 — the wiring is conditional on the run being a **git-mode host** run: in-place host runs
  and container runs get exactly the previous composition.
- FR4 — `MidRoundPushListener`'s javadoc names the real wiring point instead of the dead
  "section 4's job" reference.

### Non-Functional Reliability

- NFR-R1 — the wiring preserves the listener contract: `onProgress` never throws into the live
  loop, a failed tip resolution or failed push never fails the round, and the round-boundary
  push remains the authoritative delivery (mid-round push stays purely best-effort).

### Non-Functional Observability

- NFR-O1 — the tip-poll failure streak stays edge-suppressed: the `RepeatSuppressor` handed to
  the per-round listeners is shared across the task's rounds (the same one-fault-per-task
  rationale `SandboxRoundEnvironmentSource` records for FR4 of harden-logging-observability),
  and WARN context carries taskId, stage, round, branch as the listener already does.

### Non-Functional Performance

- NFR-P1 — cost per tool event is the listener's one `rev-parse`; no additional git
  invocations are added by the wiring itself.

### Non-Functional Security

- NFR-S1 — push stays the factory's monopoly on the factory side of the boundary: the wiring
  adds no credential exposure, no prompt content, and no push from inside any task
  environment (spec "Push safety rules" untouched).

## Operator Experience Criteria

- UX1 — a healthy git-mode host run still produces zero WARN/ERROR console output; the only
  observable difference is the origin branch advancing mid-round.

## Success Metrics

- M1 — an integration spec on a local bare remote proves: gnome commit mid-round → next
  progress event → branch tip on the remote equals the new commit, before the round closes.
- M2 — an in-place-mode spec (or existing suite) proves no push is attempted and no listener
  is constructed outside git mode.
- M3 — every affected module's `check` stays green with the 100% mutation gate.

## Open Questions

- Q1 — none open; the seam and attachment point are settled in design.md (D1–D4).

## Impact

- `adapters/git` — new small decorator/source composing the listener per round.
- `adapters/agent` — a public seam for the host round source (today package-private, built
  inside `CliStageExecutor`).
- `application` — the git-mode-only attachment point on `RunAssembly` (a decorator-as-value
  mirroring how container runners attach `SandboxRunPieces`) and a fourth `TaskGit` component
  carrying the decoration to the host git control flows.
- `bootstrap` — composition of the two adapters; `ExecutorAdapterSelector` consumes the piece.
- Javadoc of `MidRoundPushListener`; the manual-sync-pairs registry is unaffected (design.md
  records the sync-surfaces decision).
