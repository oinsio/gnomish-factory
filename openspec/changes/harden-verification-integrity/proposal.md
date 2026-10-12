# Proposal: harden-verification-integrity

## Why

The law commit protects `.gnomish/` — manifests, instructions, judge criteria — and threat
#22 of `docs/sandbox-threat-registry.md` is recorded as closed by it. The code check of
2026-09-27 showed the closure is partial: a `command` check is law, but it runs `sh -c` over
the gnome's tree, so everything it invokes — `gradlew`, build scripts, tests, coverage
thresholds, mutation config, snapshots — is gnome-editable, and `gradlew → exit 0` passes.
Running the check in a fresh box does not help: the fresh box is built from the attempt
commit the gnome wrote. Repo-local agent policy (`.claude/settings.json` and its equivalents
in Cursor, OpenCode, Amp, Gemini, Codex) can widen the gnome's own permissions from attempt 1
onward. And every finding is blocking, so a pipeline cannot report without failing. This
change seals verification against the tree it verifies, in three mechanisms and one attribute.

## What Changes

- **ADDED**: **sealed verification assets**: the frozen law tree is materialized read-only into
  the box at a factory-owned path (`GNOMISH_LAW_DIR`) for every round and check; a verdict
  check's scripts, thresholds and hidden tests referenced from there come from the law commit,
  never from the working copy.
- **ADDED**: a **`protected:` section** in the law (globs: tests, build scripts, thresholds,
  snapshots, spec files) and a built-in verdict executor **`diff-scope`** that fails an attempt
  touching a protected path unless the stage declares it may, caps the diff size and refuses
  deletions of protected files; the list of protected files an attempt changed is handed to
  every `verdict` request as delimited data.
- **ADDED**: **repo-local policy isolation** for agent executors: the runner points the
  executor's declared config-directory variable at a factory-owned directory and passes its
  declared repo-policy-disabling flags on every round; an agent executor whose describe declares
  neither is refused at load unless the law exempts it by name.
- **ADDED**: a `severity: blocking | advisory` attribute on checks; advisory findings reach the
  tracker and the attempt record, burn no attempt and never fail the stage.
- **MODIFIED**: threat registry: #22 reworded to its real closure; new rows for gnome-writable
  verification assets and repo-local policy widening; host mode's shared-`.git` exposure (#6)
  recorded as an explicit host-mode limitation.

## Goals

- G1: a check's scripts and thresholds cannot be edited by the gnome whose work they grade.
- G2: an attempt that edits a protected path is caught by a deterministic check before any judge.
- G3: no repo-local file read by an agent CLI can widen the gnome's policy.
- G4: a pipeline can report observations without failing the stage.

## Non-Goals

- NG1: sealing the build tool itself (`gradlew` as a binary) — the law may ship its own wrapper
  under `GNOMISH_LAW_DIR` and protect the repo's; replacing the build system is out of scope.
- NG2: hidden tests as a factory feature beyond "a path under the law tree" — a test runner
  that reads them is pipeline content.
- NG3: host-mode isolation of refs (shared `.git`) — recorded as a limitation, not fixed.
- NG4: baseline comparisons (`baseline: <law commit>` for perf or coverage deltas).
- NG5: judge reducers beyond majority and the `abstain` state.

## Users & Scenarios

- U1 — Pipeline author: moves `verify/coverage-threshold.txt` and `verify/run-tests.sh` under
  `.gnomish/verify/`; the check runs `$GNOMISH_LAW_DIR/verify/run-tests.sh`; the gnome's edits
  to its copy change nothing.
- U2 — Pipeline author: declares `protected: [src/test/**, build.gradle, gradle/**]`; an attempt
  that relaxes a test fails `diff-scope` with the path list before the judge runs.
- U3 — Operator: sees advisory findings (lint warnings) on the task without a burned attempt.
- U4 — Security reviewer: reads threat #22 and sees exactly what the law closes and what the
  protected list and sealed assets close.

## Requirements

### Functional

- FR1: For every round and every check the factory SHALL materialize the frozen law tree into
  the environment at a factory-owned, read-only path exposed as `GNOMISH_LAW_DIR`; content
  comes from the law commit through the law source, never from the working copy.
- FR2: The law MAY declare `protected:` globs; the loader SHALL validate them lexically.
- FR3: The built-in `diff-scope` verdict executor SHALL compare the attempt commit with the task
  base, SHALL fail with one finding per protected path touched unless the stage declares
  `allows-protected`, SHALL fail on deletion of a protected file, and SHALL fail when the diff
  exceeds the declared size cap.
- FR4: Every `verdict` request SHALL carry, as delimited data, the list of protected paths the
  attempt changed, so a judge grades with that knowledge.
- FR5: For an agent executor the runner SHALL set the describe-declared config-directory variable
  to a factory-owned directory and pass the declared repo-policy-disabling flags on every round;
  an agent executor declaring neither SHALL be a located load error unless exempted by name
  in the law with a reason.
- FR6: A check MAY declare `severity: advisory`; its findings SHALL be recorded and published but
  SHALL NOT fail the stage nor burn an attempt; `blocking` is the default.
- FR7: `docs/sandbox-threat-registry.md` SHALL reword #22, add rows for gnome-writable
  verification assets and repo-local policy widening, and record the host-mode limitation on #6.

### Non-Functional Reliability

- NFR-R1: Materialization is idempotent per environment; a re-run finds the same content.
- NFR-R2: `diff-scope` reads the factory clone's objects, not the box; it needs no box at all.

### Non-Functional Observability

- NFR-O1: `diff-scope` findings name the path, the glob that protected it and the change kind;
  advisory findings are tagged `advisory` in the ledger and the tracker.

### Non-Functional Security

- NFR-S1: `GNOMISH_LAW_DIR` is outside the working copy and read-only for the task user; a write
  attempt fails; in host mode it is a separate directory the gnome's command cannot modify.
- NFR-S2: The factory-owned agent config directory contains only factory-written content; no
  working-copy file is read as agent policy.

### Non-Functional Cost

- NFR-C1: Materialization copies only the law tree (bounded by the law size cap); `diff-scope`
  adds no model call.

## Operator Experience Criteria

- UX1: A `diff-scope` failure reads "protected path changed: <path> (by <glob>)" per path.
- UX2: An advisory check's result shows as a note on the task, visibly distinct from a failure.

## Success Metrics

- M1: A `gradlew → exit 0` attempt fails `diff-scope` when `gradlew` is protected (spec).
- M2: An attempt that edits `.claude/settings.json` runs with the same effective policy as one
  that does not (spec with the fake agent recording its argv and config dir).
- M3: The threat registry's #22 row names the three closing mechanisms.

## Open Questions

- Q1: Default `protected:` set when the law declares none (none, or a conservative default).

## Capabilities

### New Capabilities
- `verification/sealed-assets`: law-tree materialization, protected paths, `diff-scope`.

### Modified Capabilities
- `verification/verification-hardening`: repo-local policy isolation; judge receives the
  protected-change list.
- `pipeline-config`: `protected:`, `allows-protected`, `severity`, executor isolation exemption.
- `stage-engine`: advisory severity handling.

## Impact

- `adapters/law` + `sandbox/core` seam (`putFile`) for materialization; new `diff-scope` built-in
  over `gitobjects`; agent round runner (config dir and flags); `docs/sandbox-threat-registry.md`.
- Sequencing: after `extract-agent-round-runner` (describe fields, runner) and
  `migrate-verify-to-executor-contract` (verdict executors); independent of `add-sandbox-hardening`
  except both touch the law surface list (coordinate the `pipeline-config` delta order).
- Sequenced after `tighten-spec-contract`: its deltas carry the `**Implements**:` lines and the
  500-character requirement bodies that change introduces; it has no MODIFIED block to layer.
