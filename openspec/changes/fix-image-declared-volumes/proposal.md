# Proposal: fix-image-declared-volumes

## Why

The container adapter runs operator-supplied images — the egress guard
(`factory.sandbox.guard-image`, default `mitmproxy/mitmproxy`) and the task box
(`factory.sandbox.image`) — and honours every `VOLUME` their Dockerfiles declare.
For each declared path with no explicit mount, Docker silently creates an
**anonymous volume**: a hex-named object with no factory label. It escapes the
`sandbox-lifecycle` ownership model entirely (no sweep row, no reaper, no
`dispose()` step), and it survives the container it was created for. Reproduced
on 2026-09-12: every guard start leaks one anonymous volume; three bootstrap E2E
specs left four behind. The same latent path exists for any box image declaring
a `VOLUME`, and `add-sandbox-hardening`'s provisioning snapshots (FR13) inherit
whatever their base image declares — so the class must be closed before that
change lands.

This is a defect against a stable requirement, not a test-hygiene issue:
`sandbox-lifecycle` states *"No factory object SHALL ever exist without these
labels"*, and `execution-environment` states a fresh box holds *"only image
content plus the branch state"*.

## What Changes

- **ADDED**: image-declared volumes are overridden at container creation — the
  adapter inspects the image's declared volume paths and mounts a bounded
  `tmpfs` over every path it does not already mount explicitly, so the factory
  never causes an anonymous volume to exist. One owner component computes the
  override set; every `docker run` builder (guard, main box, judge box,
  verification box) takes it as a typed parameter.
- **ADDED**: a fixture image declaring a `VOLUME` joins the Docker-gated spec
  layer, and an end-to-end spec asserts that materialize → dispose leaves the
  daemon's anonymous-volume set unchanged.
- **ADDED**: the materialize anchor log line names the overridden paths, so an
  operator sees which image paths were made ephemeral.
- **ADDED** glossary term: **declared volume**.

Nothing is REMOVED; no port signature on the public surface changes. The seed
clone helper (`run --rm`) is unaffected and exempt by construction: Docker
removes a `--rm` container's anonymous volumes itself.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `execution-environment`: adds the requirement that image-declared volumes
  are overridden and the factory never creates an anonymous Docker volume.
  Delivered as an **ADDED** requirement, deliberately not a MODIFIED one:
  `add-sandbox-hardening` already carries a MODIFIED delta of the *Container
  adapter* requirement, and a second MODIFIED over the same requirement would
  be replace-only at sync time (`delta-specs.md`). Sequencing: this change is
  synced **before** `add-sandbox-hardening`; the hardening design must note
  that the guard's mitmproxy confdir is ephemeral and its factory CA arrives
  through the config mount, not through the image's declared path.

## Goals

- G1: after this change, no `docker run` issued by the container adapter can
  create an anonymous volume, for any image the operator configures.
- G2: the mechanism is image-agnostic — no path literal from any image appears
  in production code.
- G3: the override is visible to the operator in the existing lifecycle anchor
  line, not a new log stream.

## Non-Goals

- NG1: managing or reaping anonymous volumes that already exist on a host —
  they carry no factory label, so the factory cannot tell its own from a
  neighbour project's; the operator prunes those by hand.
- NG2: giving image-declared paths durable storage (named per-path volumes).
  The factory's only durable working state is the task volume and the branch;
  a path some Dockerfile author declared is not a consumer of persistence. If a
  consumer ever appears (a guard CA under `add-sandbox-hardening`), it becomes
  an explicit, named, labelled object in that change.
- NG3: a memory limit for the guard container. The guard has none today; this
  change bounds only the tmpfs it adds. A guard memory cap is a separate
  hardening item.
- NG4: rejecting images that declare `VOLUME` (fail-closed preflight). The
  default guard image declares one; refusing it would break the default
  configuration.
- NG5: `docker rm -v` as a cleanup net. It would delete data the factory does
  not know it has, and it hides the very signal (an untracked object) this
  change exists to surface.

## Users & Scenarios

- U1: An operator runs the factory with the default guard image on a
  long-lived host. Before: one orphaned anonymous volume per environment
  accumulates indefinitely. After: none is created.
- U2: An operator's project image derives from a base with a `VOLUME` for a
  package cache. Before: the cache silently lives outside the box's disk quota
  and outlives the box. After: the path is a bounded tmpfs inside the box's
  memory limit and is gone with the box; the materialize log line names it.
- U3: A developer runs the Docker-gated spec suites repeatedly. Before:
  `docker volume ls` grows by one hash per E2E environment. After: the set of
  anonymous volumes on the daemon is unchanged by a run.

## Requirements

### Functional

- FR1: Before every `docker run` of a factory container (guard, main box,
  judge box, verification box), the adapter SHALL obtain the image's declared
  volume paths from the runtime (`docker image inspect`, `Config.Volumes`).
- FR2: For every declared path not already the destination of an explicit
  factory mount for that container, the adapter SHALL add a `tmpfs` mount at
  that path with an explicit size bound; declared paths the factory already
  mounts explicitly (the working copy, the guard config directory) SHALL not
  be overridden.
- FR3: One component SHALL own the computation of the override set and expose
  it as a typed value; the `run` argv builders for the guard and for the task
  container SHALL take that value as a parameter and SHALL contain no path
  literal of any image.
- FR4: The seed-clone helper (`run --rm`) SHALL remain outside the mechanism,
  and the design SHALL record it as the one exemption with its reason.
- FR5: A Docker-gated spec SHALL materialize an environment from a fixture
  image declaring a `VOLUME`, run a round that writes into the declared path,
  dispose, and assert the daemon's anonymous-volume set is unchanged by the
  whole sequence; the same assertion SHALL hold for the guard's default image.
- FR6: The `docs/glossary.md` Sandbox section SHALL define **declared volume**.

### Non-Functional Reliability

- NFR-R1: A failed or malformed `image inspect` SHALL fail the materialize as
  an infrastructure failure through the existing "runtime outage" path — never
  proceed with an empty override set, which would recreate the leak silently.
- NFR-R2: The override is a pure function of the image's declared paths and
  the container's explicit mounts; re-running materialize on the same image
  produces the same argv.

### Non-Functional Observability

- NFR-O1: The existing INFO lifecycle anchor for environment creation SHALL
  name the overridden paths (empty list stated as such), and the guard's
  creation SHALL do the same at DEBUG. No new WARN/ERROR code is introduced.

### Non-Functional Security

- NFR-S1: The override introduces no host path into any container: `tmpfs` is
  memory-backed inside the container's own cgroup. NFR-S2 of `add-sandbox-core`
  (the box has no route to the guard's filesystem) is untouched.

### Non-Functional Cost

- NFR-C1: The added runtime cost per container start is one `image inspect`
  under the existing bounded-management-command deadline; no extra Docker
  object is created.

## Operator Experience Criteria

- UX1: The operator sees, in the same "container environment … created" line
  they already read, which image paths were made ephemeral — and needs no new
  configuration knob to get the fix.
- UX2: An operator whose image relies on a declared path for something that
  must persist gets a clear statement in `docs/` that the factory makes such
  paths ephemeral, with the alternatives (bake the content into the image, or
  keep it under the working copy).

## Success Metrics

- M1: `docker volume ls -qf dangling=true | wc -l` is identical before and
  after a full run of the Docker-gated suites in `:adapters:git` and
  `:bootstrap` (measured 2026-09-12 before the change: +4 after three specs).
- M2: `grep -rn "/home/mitmproxy\|\.mitmproxy" sandbox/docker/src/main` returns
  no hit.
- M3: Every `docker run` builder in `sandbox/docker` (`runGuard`,
  `runContainer`) takes the typed override value; the count of `"run"`
  builders without it is 1 — the exempted seed helper.

## Open Questions

- Q1: tmpfs size bound for the guard — the guard has no `--memory` limit, so
  the bound is what caps it. Resolved in design: a fixed constant sized for a
  generated CA set, not a knob.
