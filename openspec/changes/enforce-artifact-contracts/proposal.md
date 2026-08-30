# Change: enforce-artifact-contracts

## Why

Stage manifest `inputs:`/`outputs:` declarations are documentation plus DAG lint and nothing
more. Load-time graph validation exists (`ArtifactGraphRule`: unique output ids, the
earlier-producer rule), but at runtime `outputs:` has zero consumers, and `inputs:` renders
only as symbolic prompt text ("internal: produced by plan-doc") in `BriefingSections`.
`ArtifactOutput` is a `record(String id)` — no path, so nothing is checkable. This violates
the project's own IDEF0 stage rule: "Input and Output must be **machine-verifiable**"
(`.claude/rules/stage-description.md`). Today a stage can pass verify while its declared
output never materialized, and the failure surfaces stages later as a confusing quality
failure blamed on the wrong gnome. Canon reference: Tekton validates declared params and
workspaces before a run starts, as a distinct failure class from task failure — the same
separation this change introduces.

## What Changes

- **MODIFIED** (pipeline-config): artifact output declarations in `stage.yaml` accept an
  optional `path` — a file path or glob, relative to the working copy root. Absent path is
  a legitimate documentation+DAG-lint mode, not a degradation; no warning is emitted.
  Declared paths are lexically validated at load time (relative, normalized, traversal-safe,
  valid glob) without reading any working copy.
- **MODIFIED** (stage-engine): two read-only artifact contract gates. Producer gate — after
  a stage's verify passes, every path-declaring output of that stage must resolve to at
  least one existing file. Consumer gate — before a stage's first round in a run, every
  internal input whose producer declares a path must resolve. A missing artifact escalates
  through the existing `CannotExecute` report (factory/pipeline broken — dirty resume,
  manifest error), never a quality failure; no attempt is burned.
- **MODIFIED** (agent-executor): the briefing's input-artifacts section renders the declared
  path when the producer declares one — prompt enrichment falls out for free.
- No schema version bump: the field is additive and optional (see design D6).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `pipeline-config`: artifact output declarations gain an optional, lexically validated
  `path`; the artifact-reference requirement's model grows accordingly.
- `stage-engine`: producer/consumer artifact contract gates; the `CannotExecute` escalation
  report generalizes to cover a missing declared artifact (still: no attempt burned).
- `agent-executor`: the shared briefing renderer shows declared artifact paths.

## Goals

- **G1** — declared stage outputs and inputs become machine-verifiable whenever the manifest
  author opts in with a `path`, closing the IDEF0 "machine-verifiable Input/Output" gap.
- **G2** — a missing declared artifact is attributed to the factory/pipeline, never to the
  gnome: it burns no attempt and cannot masquerade as a quality failure.
- **G3** — the gnome's briefing names the real paths of its input artifacts instead of
  symbolic producer ids.

## Non-Goals

- **NG1** — no content validation (checksums, schemas, "is the artifact good"): existence
  only. Content quality remains the verify chain's job.
- **NG2** — no `path` on `source` inputs: they arrive with the working copy and stay
  symbolic. Only outputs declare paths; internal inputs inherit the producer's.
- **NG3** — no artifact transport or staging between workspaces; artifacts live in the task
  working copy / task branch as today.
- **NG4** — no deriving stage order or the DAG from paths; `pipeline.yaml` order stays the
  single source of truth.
- **NG5** — no new escalation report kind and no tracker-rendering changes beyond the cause
  text (see design D3).
- **NG6** — no warning, lint, or nag for path-less declarations: documentation mode is a
  supported mode, not technical debt.

## Users & Scenarios

- **U1** — pipeline author: declares `outputs: [{ id: plan-doc, path: docs/plan.md }]` and
  gets a hard guarantee that the pipeline halts with a factory-fault escalation, not a
  gnome-blamed retry, when the artifact is missing.
- **U2** — operator: reads an escalation naming the stage, artifact id, and path that went
  missing, and knows immediately the pipeline/factory is broken (dirty resume, manifest
  error) rather than the gnome's work being poor.
- **U3** — gnome: its briefing says "internal: produced by plan-doc at docs/plan.md" and it
  writes/reads the right file without guessing.

## Requirements

### Functional

- **FR1** — the artifact output declaration accepts an optional `path`: a single file path
  or a glob, interpreted relative to the working copy root. A declaration without `path`
  keeps exactly today's behavior (DAG lint + symbolic briefing), with no warning.
- **FR2** — the loader lexically validates every declared `path`: it must be relative, in
  normalized form (no `.` or `..` segments), not absolute, and syntactically a valid glob.
  Violations are located `ConfigError`s naming the stage manifest and output id. The loader
  never reads a working copy (none exists at load time) — validation is lexical only, the
  same class of check as external-check pin paths.
- **FR3** — producer gate: after a stage's verify chain passes, and before the engine
  executes any later stage, every path-declaring output of the passed stage must resolve to
  at least one existing file in the task's working-copy state. A glob resolves when it
  matches at least one file.
- **FR4** — consumer gate: before the first round of a stage in any run — fresh entry or
  resume — every `internal` input of that stage whose producer output declares a `path`
  must resolve the same way. Inputs whose producer declares no path, and `source` inputs,
  are never gated.
- **FR5** — a gate failure escalates as `Escalated(CannotExecute)` with a cause naming the
  stage, the artifact id, the declared path, and which gate fired. It is never a quality
  failure: no stage attempt is burned, and no verify feedback is generated from it.
- **FR6** — the briefing's input-artifacts section renders the producer's declared path
  beside the producer id when one is declared, and stays byte-identical to today's output
  when none is.
- **FR7** — the gates observe the working copy through an engine port; each execution mode's
  adapter answers from where that mode can genuinely see the round's files (host worktree
  vs. harvested/box state — design D4), while the glob-matching rule itself has exactly one
  implementation in the domain.

### Non-Functional Reliability

- **NFR-R1** — both gates are read-only and idempotent: running a gate twice equals running
  it once, and a gate adds no durable step to any transition (no new kill windows). The
  consumer gate is the convergence backstop: whatever state a crash or dirty resume froze,
  the next run's consumer gate re-detects the missing artifact and re-escalates identically.

### Non-Functional Observability

- **NFR-O1** — a gate failure logs at ERROR with the (taskId, stage) key and the same
  stage/artifact-id/path detail the escalation cause carries, so tracker report and log
  line agree.

### Non-Functional Security

- **NFR-S1** — a declared path can never cause a read outside the working copy root:
  rejected lexically at load (FR2), and the probe resolves matches strictly within the
  root. Symlinked escapes resolve to "not visible" rather than following the link out.

### Non-Functional Cost

- **NFR-C1** — the gates make no model calls and no network calls; cost impact is nil.

### Non-Functional Performance

- **NFR-P1** — one working-copy listing per gate invocation, matched in-process against all
  claims — never one filesystem/git call per declared artifact.

## Operator Experience Criteria

- **UX1** — the escalation the operator reads is self-attributing: it states that the
  pipeline/factory is broken (naming the likely causes: dirty resume, manifest error) and
  never phrases the miss as the gnome's failure.
- **UX2** — a pipeline with no declared paths behaves observably exactly as before this
  change: same outcomes, same prompts, same logs, no new output of any kind.

## Success Metrics

- **M1** — reference-pipeline spec: with a declared path and the artifact deleted between
  stages, the run ends `Escalated(CannotExecute)` with `attemptsUsed` unchanged and the
  cause naming stage, id, and path — asserted for both gates.
- **M2** — briefing spec: rendered output is byte-identical to the pre-change rendering for
  path-less inputs, and carries the path for declaring producers.
- **M3** — quality gates stay green: PIT mutation score at target and `check` passes in
  every touched module.

## Open Questions

- **Q1** — should a glob declaring an *expected cardinality* (exactly one file vs. at least
  one) ever be needed? Out of scope now: "at least one match" is the contract; revisit if a
  real pipeline needs exact-arity artifacts.
