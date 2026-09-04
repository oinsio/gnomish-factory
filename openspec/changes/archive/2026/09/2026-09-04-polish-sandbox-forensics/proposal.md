# Proposal: polish-sandbox-forensics

## Why

When a sandboxed run fails, the operator's first minutes are spent reconstructing context
the factory already had: an exit code 137 is reported bare, although the container runtime
knows whether the box was OOM-killed; error messages carry the environment key but not the
ready-to-paste container name `docker logs` needs; and a failed environment self-check —
the one failure whose whole point is "something about this image/runtime/allowlist is
wrong" — destroys the very box that holds the evidence. Three small forensics improvements
in the sandbox module close these gaps. (Provenance: inspired by review of the vigilante
project and its preserve-on-failure lifecycle.)

## What Changes

- **MODIFIED**: exit-137 classification of in-box processes is annotated with the
  container's `.State.OOMKilled` runtime metadata — "likely container OOM" instead of a
  bare "forced terminate" exit code (`DockerCommands.inspectContainerState` already reads
  `.State.*`; it is extended to include `OOMKilled`)
- **MODIFIED**: operator-facing container failure messages embed the ready-to-paste
  container name (today they carry only the environment key the name is derivable from via
  `FactoryDockerLabels.containerName`)
- **MODIFIED**: a failed `EnvironmentSelfCheck` stops and keeps the box (the same keep
  semantics as park/abort — `ContainerRunTermination.keepStopped`,
  `ContainerEnvironmentKeeper`) instead of leaving it to disposal, so the operator can
  inspect why the self-check failed; retention stays bounded by the existing
  `sandbox-lifecycle` sweep — no new sweep logic

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `execution-environment`: exit-137 exec classifications are annotated with the
  container's OOM state; operator-facing container failures name the container
  ready-to-paste
- `sandbox-egress`: the mandatory fail-closed self-check keeps the failed box (stopped)
  for inspection instead of disposing it; the rejection itself is unchanged

## Goals

- **G1** — an operator diagnosing a failed sandboxed run can start `docker logs` /
  `docker cp` directly from the error message, with no name derivation by hand
- **G2** — an in-box process killed by the container's memory limit is reported as a
  likely OOM, not as an anonymous exit 137
- **G3** — a failed self-check leaves inspectable evidence: the box survives, stopped,
  under the existing bounded-retention sweep policy

## Non-Goals

- **NG1** — no new sweep logic, no decision-matrix change, no new verdict category: kept
  self-check boxes are governed entirely by the existing `sandbox-lifecycle` policy
- **NG2** — no host-mode changes: the host adapter has no container, no guard, and no
  self-check, so none of the three improvements applies to it
- **NG3** — no exit-code semantics change: 137 still classifies exactly as today
  (termination handling, attempt accounting, failure classes unchanged); only the
  operator-facing annotation is added
- **NG4** — no metrics, dashboards, or tracker-report format changes

## Users & Scenarios

- **U1** — the operator of a factory installation whose sandboxed round or check died
  with exit 137: reads "likely container OOM" and raises `factory.sandbox` memory limits
  instead of bisecting the build
- **U2** — the operator whose container materialize / egress guard / run failed: copies
  the container name straight out of the error message into `docker logs` / `docker cp`
- **U3** — the operator whose environment self-check failed (bad image, wrong runtime,
  broken allowlist): inspects the kept box from inside (`docker exec` on a restarted copy,
  `docker logs`, `docker cp`) instead of reproducing the failure blind

## Requirements

### Functional

- **FR1** — when an in-box process run through the container adapter exits with code 137,
  the adapter SHALL read the container's `.State.OOMKilled` via `docker inspect` and
  annotate the classification with "likely container OOM" when it is `true`;
  `DockerCommands.inspectContainerState` is extended to carry `OOMKilled` alongside
  `Running` and `FinishedAt`
- **FR2** — operator-facing failure messages about a task container or its egress guard
  (container materialize failures, guard start failures, the keep-path notice) SHALL embed
  the concrete Docker object name (`gnomish-box-<key>` / `gnomish-guard-<key>`), not only
  the environment key
- **FR3** — when the mandatory environment self-check fails in the container adapter,
  the factory SHALL stop and keep the checked box (container stopped; container, volume,
  and network retained) instead of disposing it, and the operator-facing keep notice in
  the factory log SHALL name the kept container (the rejection exception is unchanged);
  the failure classification (infrastructure, no attempt burned) is unchanged

### Non-Functional Reliability

- **NFR-R1** — the OOM inspect and the keep-stop are best-effort forensics: a failed
  `docker inspect` or a failed stop SHALL never mask, replace, or reclassify the original
  failure, and SHALL never fail an otherwise healthy path
- **NFR-R2** — every state the keep-on-failed-self-check path can freeze in (kill before
  the stop, kill after it) SHALL be a state the existing `sandbox-lifecycle` sweep already
  converges; the change introduces no new frozen shape and no new recovery owner

### Non-Functional Observability

- **NFR-O1** — the OOM annotation and the kept-box notice (with the container name) SHALL
  appear in the factory log at the failure site, so the forensic pointer is in the same
  place the operator reads the failure

### Non-Functional Security

- **NFR-S1** — annotations and enriched messages SHALL carry only Docker object names
  (derived from the sanitized environment key) and runtime state metadata — never
  credentials, environment values, or request content

### Non-Functional Cost

- **NFR-C1** — kept self-check boxes SHALL remain inside the existing bounded-retention
  envelope: the aged reaper (and, for role boxes, the immediate-disposal matrix row)
  bounds their lifetime with no configuration additions

## Operator Experience Criteria

- **UX1** — a container-related failure message contains a name the operator can paste
  into `docker logs <name>` / `docker cp <name>:...` without consulting any mapping
- **UX2** — an exit-137 failure whose container reports `OOMKilled=true` tells the
  operator "likely container OOM" at the point the exit code is reported
- **UX3** — a failed self-check tells the operator the box was kept and what it is called

## Success Metrics

- **M1** — for an exec killed by the memory limit, the log line reporting exit 137
  carries the OOM annotation (asserted by spec against a fake docker seam)
- **M2** — 100% of the touched failure sites (materializer, guard, keep path, self-check)
  emit the concrete container/guard name — verified by the specs of each site
- **M3** — after a self-check failure, the box's container still exists in the stopped
  state and is enumerated by the sweep universe (asserted by spec; matrix behavior pinned
  by existing `sandbox-lifecycle` specs)

## Open Questions

- **Q1** — `.State.OOMKilled` is set by the runtime when the cgroup OOM killer fires; on
  some runtime/cgroup combinations an OOM kill of an exec'd child may leave it `false`.
  The annotation is deliberately "likely" and one-directional (only added when `true`);
  is a second heuristic (e.g. dmesg) worth it? Current answer: no — out of scope for a
  polish change.
