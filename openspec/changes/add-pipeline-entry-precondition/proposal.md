# Proposal: add-pipeline-entry-precondition

## Why

When a gnome takes a task on a project whose baseline is already broken — red tests,
failing build — every stage attempt burns against someone else's breakage, and the
resulting escalation blames the gnome's work instead of the project owner. Merge
queues solved this long ago by attributing a red baseline to the base, not the
candidate; setup-phase validation (Codex, Jules) and pre-run rejection (Tekton) apply
the same principle. The factory needs the equivalent: verify the baseline is green
BEFORE the first agent round, and route a red baseline to the project owner.

## What Changes

- **ADDED**: an **entry precondition** — an optional, pipeline-level command declared
  in `.gnomish/` that the factory runs after task creation and before the first
  attempt of the first stage, in a fresh box materialized at the task's baseline
  commit. Absent declaration → the step is skipped silently (pipelines are universal:
  fix-the-build, TDD-red, or documentation pipelines legitimately declare none).
- **ADDED**: a third failure routing for a red baseline — not a quality failure (the
  gnome never ran, no attempt is burned), not an infrastructure failure (retry cannot
  fix the owner's breakage): the task escalates through the existing `CannotVerify`
  channel with a distinguishable cause "baseline red at SHA X" and bounded command
  output, returning the task to the project owner.
- **ADDED**: a durable precondition verdict cached in `state.json`, keyed by baseline
  SHA + environment image identity — paid once per baseline per task, resume-safe.
- **MODIFIED**: `pipeline-config` loads and validates the optional declaration;
  `git-task-persistence` carries the cached verdict additively under contract v1.
- Glossary gains the term **entry precondition**.
- The stage-engine contract is deliberately NOT modified: its classification table
  hard-codes `command` exit ≠ 0 → Fail (quality), which is exactly why this step is
  an engine-owned pre-run step, not a verify check (see design D1).

## Capabilities

### New Capabilities

- `pipeline-entry-precondition`: the engine-owned entry step — position in the task
  lifecycle, fresh-box no-mutation execution, failure classification, verdict
  caching, and crash consistency.

### Modified Capabilities

- `pipeline-config`: the pipeline-level `entry-precondition` declaration — optional
  section, load-time validation, silent skip when absent.
- `git-task-persistence`: `state.json` gains the cached precondition verdict
  (additive under contract v1), with a named single writer for the new field.

## Goals

- **G1** — a broken target-project baseline burns zero gnome stage attempts and
  never produces an escalation attributed to the gnome's work.
- **G2** — the precondition verdict is paid at most once per (task, baseline SHA,
  image identity): resumes and crash recoveries reuse the durable verdict.
- **G3** — pipelines that declare no entry precondition observe zero behavior change
  and zero added cost, with no warning noise.

## Non-Goals

- **NG1** — per-test baseline snapshots for delta-gating ("only fail on tests the
  gnome broke"). Deferred future work; the entry verdict is binary green/red.
- **NG2** — a cross-task baseline verdict cache shared between tasks or instances.
  The verdict lives in the task's own `state.json`; a factory-global cache is future
  work (Q1).
- **NG3** — a sixth escalation kind. The closed five-kind set is reused, not
  extended (design D3).
- **NG4** — fixing the red baseline. That is a job for a fix-the-build pipeline —
  which legitimately declares no entry precondition.
- **NG5** — modeling the precondition as a verify check or a stage. It is an
  engine-owned step outside the stage/check vocabulary.

## Users & Scenarios

- **U1 — project owner**: their project's baseline broke; a taken task comes back
  promptly as "baseline red at SHA X" with the failing command's output, instead of
  a confusing gnome-failure report after exhausted attempts.
- **U2 — factory operator**: sees in logs and the tracker report that the task never
  reached the first round and why; pays for one short probe instead of N burned
  agent rounds.
- **U3 — pipeline author**: adds one command line to the pipeline declaration to arm
  the precondition; omits it for pipelines that must start red.

## Requirements

### Functional

- **FR1** — `.gnomish/` SHALL accept an optional pipeline-level `entry-precondition`
  declaration (a command, with an execution timeout). An absent declaration is valid
  and skips the step silently; a declared command is validated at load time under
  the existing located-`ConfigError` aggregation. The declaration is part of the
  pipeline definition content that `add-pipeline-routing` pins at first claim.
- **FR2** — for a pipeline that declares one, the factory SHALL run the entry
  precondition after the task is created on the branch (task branch and initial
  state file exist) and before the first attempt of the first stage — in both host
  and container modes, on fresh claims and on resumes that land before the first
  round.
- **FR3** — the precondition SHALL execute in a fresh execution environment
  materialized at the task's baseline commit through the existing
  `TaskExecutionEnvironment` fresh-box model: materialize at the factory-chosen pin,
  exec, read the exit code, dispose WITHOUT harvest. Results are discarded; the task
  branch cannot be mutated by construction. The environment is the same
  image/binding as work stages, so hermeticity guarantees transfer.
- **FR4** — a red baseline (command exit ≠ 0, excluding the environment-failure exit
  codes of FR5) SHALL escalate through the existing `CannotVerify` escalation kind
  with a distinguishable cause naming the baseline SHA and carrying bounded command
  output. No stage attempt is burned; no round is recorded; the task parks for the
  project owner via the existing escalation protocol. A probe timeout classifies as
  a red baseline.
- **FR5** — environment breakage during the probe — materialize failure, runtime
  outage, command not found / not executable (exit 126/127) — SHALL classify as an
  infrastructure failure through the existing channels (retry policy, cannot-execute
  escalation). The precondition command's ordinary exit code speaks only about the
  baseline, never about the environment.
- **FR6** — a completed probe's verdict SHALL be recorded durably in `state.json`,
  keyed by baseline SHA + environment image identity, before the environment is
  disposed and before the run proceeds or parks. A recorded green verdict matching
  the current key SHALL skip the probe on any later visit; a key mismatch re-runs it.
- **FR7** — the glossary SHALL gain the term **entry precondition** (the
  machine-checked precondition of the pipeline's entry, per the IDEF0 stage
  description model), added in this change.

### Non-Functional — Reliability

- **NFR-R1** — the step SHALL be crash-consistent per the crash-consistency rule:
  kill windows enumerated, each frozen state classifying to a shape the
  `task-branch-contract` capability owns, one recovery owner, idempotent convergent
  recovery (re-running the probe is always safe — it mutates nothing), kill-point
  specs included.

### Non-Functional — Observability

- **NFR-O1** — the probe's start, verdict, key, and duration SHALL be logged; the
  escalation report SHALL carry the baseline SHA and a bounded output tail
  sufficient for the owner to act without factory access.

### Non-Functional — Security

- **NFR-S1** — the probe command SHALL run under exactly the isolation of work
  stages: same environment port, same image/binding, same layered env allowlist; no
  new privileges, no harvest path out of the probe box.

### Non-Functional — Cost

- **NFR-C1** — at most one probe execution per (task, baseline SHA, image identity);
  zero added executions, environments, or log noise for pipelines with no
  declaration.

### Non-Functional — Performance

- **NFR-P1** — the probe SHALL be bounded by its declared timeout plus the existing
  subprocess kill margin; it can never hang a take indefinitely.

## Operator Experience Criteria

- **UX1** — the tracker report for a red baseline reads as an owner-actionable
  message — "baseline red at SHA X" plus the failing command output — clearly
  distinct from gnome quality failures and from infrastructure failures.
- **UX2** — a pipeline without the declaration produces no new log lines, warnings,
  or tracker traffic; operators of such projects cannot tell the feature exists.

## Success Metrics

- **M1** — kill-point and escalation specs prove: a red baseline ends the task with
  zero stage attempts used, zero recorded rounds, and a `CannotVerify` park naming
  the baseline SHA.
- **M2** — a resume after a recorded green verdict with an unchanged key performs
  zero probe executions (asserted by spec).
- **M3** — the full existing spec suite for pipelines without a declaration passes
  unchanged (skip path is behaviorally invisible).

## Open Questions

- **Q1** — cross-task verdict sharing: when many tasks fork the same baseline SHA,
  a factory-level cache could pay the probe once per baseline globally. Deferred
  until the per-task verdict proves the model.
- **Q2** — should the probe timeout's classification (red baseline by default)
  become configurable per declaration, mirroring external checks' `timeoutClass`?
  Deferred until a real pipeline needs it.

## Impact

- **Modules**: `application` (both fresh-claim recipes and both resume runners —
  host/container declared pairs; see design D6), `adapters` (a baseline probe beside
  the existing fresh-box check environment source), `adapters/git` (state DTO +
  mapper, additive), `domain` config model (declaration type), `docs/glossary.md`.
- **Coordination**: `add-pipeline-routing` (the declaration is part of the pinned
  per-pipeline definition and its content hash; `state.json`/`task.json` fields land
  in the same region), `add-stage-iteration` and `harden-task-branch-contract`
  (additive `state.json` fields under contract v1 — apply order decides version-gate
  sequencing), the manual-sync-pairs registry (fresh-claim recipe rows change at
  both ends).
- **No new dependencies**; no tracker or wire-format extension (the escalation kind
  set is unchanged).
