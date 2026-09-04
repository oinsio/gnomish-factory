# Design: polish-sandbox-forensics

## Context

See proposal.md — Why. Three operator-forensics gaps in container mode, driven by FR1–FR3
and NFR-R1/R2, NFR-O1, NFR-C1 of this change's proposal. Current state, verified in code:

- `Supervision`'s javadoc documents exit 137 only as a forced terminate; the container
  adapter never consults `.State.OOMKilled`, although
  `DockerCommands.inspectContainerState` already reads `.State.Running` and
  `.State.FinishedAt` and is consumed in exactly one place
  (`ContainerTaskExecutionEnvironment.materialize`, which hands the output to
  `ContainerMaterializer.reattach`, whose `startsWith("true")` prefix check parses it).
- Failure messages in `ContainerMaterializer.management` and `EgressGuard`
  (`GuardUnavailableException` sites) carry the environment key; the concrete object names
  are derivable via the package-private `FactoryDockerLabels.containerName`/`guardName`
  but never rendered. `ContainerEnvironmentKeeper.stopKeeping` logs nothing on success.
- A failed `EnvironmentSelfCheck` throws `SelfCheckFailedException` out of
  `SelfCheckedEnvironment.materialize`. The round box then simply falls out of the lease
  (`EnvironmentLease` assigns `current` only after materialize succeeds).
  `SandboxCheckEnvironmentSource` disposes on any materialize failure (catches
  `RuntimeException` → `dispose()`); `FreshJudgeEnvironments` does **not** — it runs
  `disposeCurrent()` *before* materialize and assigns `current` only on success, so a box
  whose materialize failed is never tracked and nothing ever disposes it. The
  `sandbox-egress` spec pins "the environment is disposed" for the degraded-egress
  scenario.

## Goals / Non-Goals

Design-level boundaries beyond the proposal's scope:

- Keep all three mechanisms inside the `sandbox/docker` package where the Docker
  vocabulary already lives; the only edits outside it are the fresh-box dispose sites that
  would otherwise destroy the kept evidence.
- No new configuration properties, no new public port surface on
  `TaskExecutionEnvironment`.

## Decisions

**D1 — OOM annotation lives at the container exec seam, read lazily on exit 137.**
`DockerCommands.inspectContainerState` is extended to
`{{.State.Running}} {{.State.FinishedAt}} {{.State.OOMKilled}}` — appending keeps the
reattach branch's `startsWith("true")` parse untouched, and the self-check/lifecycle
readers use their own separate commands (`DockerLifecycleCommands.inspectContainerTiming`
is documented as deliberately separate and is not changed). The container adapter wraps
the `ExecHandle` it returns from `exec()`: when `waitForExit()` observes exit code 137 —
`Wait.Exited` deliberately carries no exit code, so `waitForExit()` is the one seam that
surfaces it — the wrapper runs the inspect best-effort and logs one WARN, headed by a new
`OperatorEvent` catalog code per the `harden-logging-observability` log contract, naming
the container and "likely container OOM" when `OOMKilled=true` (FR1, NFR-O1). The wrapper
changes no exit code, no `Wait` outcome, and no classification (NFR-R1); an inspect
failure logs nothing extra.
*Rationale:* the exec seam is the one point every in-box process passes through — agent
rounds, checks, probes — so the annotation covers them all without touching the engine's
failure classes. *Alternative rejected:* annotating in the engine/agent adapter where exit
codes are classified per consumer — that would scatter Docker-specific inspection across
modules that are deliberately runtime-agnostic, and host mode has no OOM state to read.

**D2 — Names are rendered where they are known, not exported.**
`FactoryDockerLabels` stays package-private. `ContainerMaterializer.management` and the
`EgressGuard` failure sites embed `containerName(key)` / `guardName(key)` in their
messages; `ContainerEnvironmentKeeper.stopKeeping` logs one INFO naming the kept container
(FR2, UX1). `ContainerRunTermination` (bootstrap) needs no edit: the keep notice reaches
the operator from the keeper's own log line, keeping the bootstrap layer free of Docker
naming. *Rationale:* the name derivation is one static call at each failure site; a public
name-exposing accessor would leak adapter vocabulary upward for no consumer.
*Alternative rejected:* exposing the container name on the environment handle or in
exceptions as a structured field — more surface than three log/message edits justify for a
polish change.

**D3 — Keep-on-failed-self-check is enforced at the single self-check site, and
dispose-on-failure sites learn to step aside.** `SelfCheckedEnvironment.materialize`
catches `SelfCheckFailedException`, stops the box via `ContainerEnvironmentKeeper`
(best-effort: a failed stop is logged as a WARN under its own new `OperatorEvent` catalog
code and never masks the probe failure — the original exception is always rethrown,
NFR-R1), logs the kept container's name, and rethrows (FR3, UX3). The log notice at the
failure site is the operator-facing carrier of the container name (NFR-O1); the rethrown
exception stays untouched. Because `SelfCheckedEnvironment` wraps every role (round,
judge, verification) by construction, this is the one point that covers all self-checked
boxes. `SandboxCheckEnvironmentSource` skips its dispose-on-materialize-failure when the
failure is a `SelfCheckFailedException` (a public type it can name), so the kept evidence
survives; every other materialize failure still disposes as today. `FreshJudgeEnvironments`
needs no code edit — a failed materialize is never assigned to `current` and nothing
disposes it — but the keep-on-failed-self-check behavior there is pinned by spec so a
future refactor cannot silently start disposing the evidence. The round-box path needs no
edit either: `EnvironmentLease` never registers a failed materialize, and nothing else
disposes it.
*Rationale:* one producer of keep semantics, zero mode twins. *Alternative rejected:*
keeping only the round box (no adapters-module edits) — it forfeits exactly the fresh-box
failures (same image, same allowlist) the forensics are for, for the price of one
one-line-condition edit and one pinning spec.

**D4 — Sync surfaces: none — this change adds no parallel implementation and touches no
declared pair.** Verified: `grep -rn "Kept in sync with"` over the touched files
(`DockerCommands`, `ContainerMaterializer`, `EgressGuard`, `ContainerEnvironmentKeeper`,
`SelfCheckedEnvironment`, `ContainerTaskExecutionEnvironment`,
`SandboxCheckEnvironmentSource`, `FreshJudgeEnvironments`) returns nothing, and none of
them appears in the `manual-sync-pairs.md` registry. No execution-medium twin arises: the
host adapter has no container, no guard, and no self-check, so no host-side mirror of any
of the three behaviors exists to keep in step. *Alternative rejected:* declaring a pair
with the host adapter's error reporting — there is no shared invariant to declare.

**D5 — Crash consistency: the keep transition introduces no new shape.** The rejected-box
path has two durable steps: the box exists running (from materialize) and the box is
stopped (the keep). Kill windows: (1) after materialize, before the stop — freezes a
running box whose task holds no live work; (2) after the stop — the target state. Both
classify to existing shapes of the `sandbox-lifecycle` capability's matrix: an unowned
running main box is stopped by the sweep (stopped-orphan), an unowned stopped one is left
to the aged reaper (disposed-aged), and unowned role boxes (`-j`/`-v`) are disposed as
reconstructible. Recovery owner is the existing sweep in every window; the keep-stop is
idempotent (stopping a stopped or absent container is a no-op by the keeper's contract);
constructive-before-destructive holds (nothing is removed at all). The kept box is
enumerated by the sweep universe because its ownership labels are stamped atomically at
creation (`FactoryDockerLabels.ownershipLabelArgs` on `run`), not at keep time — pinned by
the delta scenario "Kept self-check box is governed by the existing sweep" (NFR-R2).

## Risks / Trade-offs

- [`OOMKilled` is runtime-dependent: on some runtime/cgroup-v2 combinations an OOM kill of
  an exec'd child may leave the container's flag `false`] → the annotation is
  one-directional and worded "likely"; a missing annotation is the status quo, never a
  wrong claim (Q1 of the proposal).
- [A kept role box (`-j`/`-v`) is disposed by the existing matrix as soon as its claim
  goes stale — a shorter forensic window than the main box's 7-day reaper] → accepted:
  bounded retention is the point (NFR-C1, NG1), and the operator watching a failing task
  inspects within the claim's lifetime; the failure log still holds the probe name and
  container name after disposal.
- [Kept boxes from repeated self-check failures accumulate until the reaper runs] → the
  keys are deterministic per task and role, so retries reuse (reattach) the same objects
  rather than multiplying them; the population is bounded by task count, as for park/abort
  keeps today.
- [The active change `add-sandbox-hardening` also carries a MODIFIED delta of the
  `sandbox-egress` requirement "Mandatory fail-closed self-check", written against the
  pre-keep text — whichever change archives second would silently overwrite the other's
  additions] → ordering is fixed: this change archives first; `add-sandbox-hardening`
  rebases its MODIFIED text on the then-current stable requirement (keep semantics
  included) before its own archive. Recorded on both sides.

## Migration Plan

No deployment or data migration: log/message enrichment and a lifecycle policy tweak,
rolled out with the build. Rollback = revert; kept self-check boxes left behind by a
rolled-back build are ordinary kept environments the sweep already reaps.
