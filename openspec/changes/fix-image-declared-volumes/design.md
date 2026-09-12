# Design: fix-image-declared-volumes

## Context

See proposal.md — Why. The container adapter has three `docker run` builders in
`sandbox/docker` (`GuardCommands.runGuard`, `DockerCommands.runContainer`,
`DockerSeedCloneCommand.seedClone`) and two call sites that issue them
(`EgressGuard.create`, `ContainerMaterializer.create` — the latter serves the
main, judge and verification boxes alike through `ContainerEnvironmentBuilder`).
Each builder is a pure argv function verified by `DockerCommandsSpec` without a
daemon; the call sites run the argv through `DockerCli` under the bounded
management-command deadline. Docker offers no runtime flag to ignore an image's
`VOLUME` declarations (moby/moby#43190 is open since 2022); the only recognised
way is to occupy each declared path with an explicit mount. Kubernetes' own
experience (kubernetes/kubernetes#128500) is that declared paths left to the
runtime also escape the container's storage accounting — the same quota bypass
the box's opt-in `--storage-opt size=` has today.

Driven by FR1–FR6, NFR-R1, NFR-O1 of the proposal.

## Goals / Non-Goals

**Goals:**
- One owner computes the override set from the image; the argv builders are
  path-blind (FR3, G2).
- The mechanism reaches every factory container through the type system, not
  through a convention (`implementation.md`, item 3).

**Non-Goals:**
- Caching `image inspect` results across environments. One inspect per
  container start is within the existing management-command budget (NFR-C1);
  a cache is an optimisation with its own invalidation story (image re-pull)
  and is not needed to meet the goals.
- Any change to the sweep or reaper: no new object exists for them to see.

## Decisions

**D1 — Override with `tmpfs`, not with named volumes.** Every declared path the
factory does not mount explicitly gets `--mount type=tmpfs,destination=<path>,
tmpfs-size=<bound>`. *Rationale:* nothing in the factory consumes those paths;
the durable state of an environment is the task volume and the branch, and the
`execution-environment` freshness requirement says a fresh box holds only image
content plus branch state. `tmpfs` creates no Docker object, so the ownership
model, the sweep matrix and the crash-consistency shape set are all unchanged;
the memory it uses is charged to the container's own cgroup, so for the box it
sits under the existing `--memory` limit (and closes the disk-quota bypass a
volume-backed path has). *Alternative rejected — a named, labelled volume per
declared path:* honest to the "name your volumes" practice and already
reapable by label, but it makes the object count per environment variable,
adds N `volume create` steps (and kill windows) before the container, and
persists across stop/start data that no factory consumer asked for. If a
consumer appears — the guard's factory CA under `add-sandbox-hardening` — that
change introduces the named object explicitly for that path alone.

**D2 — One owner: `DeclaredVolumeOverrides`.** A small package-private class in
`sandbox/docker` with one static entry point: given the `DockerCli`, the image
reference and the set of destinations the factory mounts explicitly, it runs
`docker image inspect -f '{{json .Config.Volumes}}' <image>`, parses the JSON
object's keys, subtracts the explicit destinations, and returns an immutable
value type `DeclaredVolumeOverrides` holding the ordered path list and the
size bound; the value renders itself as argv fragments. Parsing needs no JSON
library beyond Jackson, which the module already has. *Rationale:* the
proposal's FR3 requires a single computation and a typed carrier; a value type
is what makes the "old way" (a bare `List<String>` of extra mounts, or an
absent parameter) uncompilable. *Alternative rejected — compute inline in each
call site:* two implementations of one rule at day one, a sync pair by
construction (`manual-sync-pairs.md`).

**D3 — The argv builders take the value; the call sites obtain it.**
`runGuard` and `runContainer` gain a `DeclaredVolumeOverrides` parameter and
append its fragments after their own explicit mounts. `EgressGuard.create` and
`ContainerMaterializer.create` obtain the value from the owner immediately
before building the run argv, passing their explicit destinations
(`GuardCommands.CONFIG_MOUNT`; `ContainerTaskExecutionEnvironment.WORKING_COPY`).
`runContainer` then has eight parameters, over the seven-parameter limit of
`process-invariants.md`; it therefore takes a `ContainerRunSpec` parameter
object (key, image, runtime, limits, disk-quota flag, working copy, ownership,
overrides) in the same task — the limit says a gate lands with the refactor
that brings offenders under it, and this change would otherwise add one.
*Rationale:* the builders stay pure and daemon-free for `DockerCommandsSpec`;
the daemon call happens at the call site, where the outage policy already
lives. *Alternative rejected — have the builder call the daemon:* breaks the
"pure argv builder" seam every existing spec relies on.

**D4 — Read failure fails the materialize.** A non-zero `image inspect` or an
unparseable answer throws through the existing management-failure path
(`IllegalStateException` naming the container, for the box; `GuardUnavailable-
Exception` for the guard) and so classifies as the runtime-outage
infrastructure failure. *Rationale:* NFR-R1 — an empty set on failure would
silently reintroduce the leak; an image that cannot be inspected cannot be run
either, so nothing is lost by failing here. *Alternative rejected — log and
continue with no overrides:* the failure mode `implementation.md` exists to
prevent (green build, wrong behaviour).

**D5 — Size bound is a constant, not a knob.** `tmpfs-size=64m` for every
override, on both guard and box. *Rationale:* the guard has no memory limit,
so this constant is what caps it (Q1 of the proposal); mitmproxy's generated
CA set is 24 KB. For the box, 64 MB per declared path sits well under the
2 GB default memory limit and is not a cache a build could usefully fill; a
tool that needs more at a declared path is a tool that needs the content in
the image or under the working copy (UX2 documents this). *Alternative
rejected — operator knob under `factory.sandbox`:* a knob for a value nobody
should tune invites tuning; revisit only if a real image needs it.

**D6 — Seed helper stays exempt.** `seedClone` runs with `--rm`; Docker removes
a `--rm` container's anonymous volumes with the container. The override would
be redundant there and would cost an inspect per seed. Recorded as the one
exemption in the single-owner table; a spec pins that `seedClone`'s argv still
begins `run --rm`.

**D7 — Observability rides the existing anchor.** The "container environment
{} created" INFO line in `ContainerMaterializer` gains the overridden path
list (rendered as `declared volumes made ephemeral: [/a, /b]` or `none`); the
guard logs the same at DEBUG in `EgressGuard.create`. No `[GFnnn]` code — INFO
and DEBUG carry none (`logging.md`). *Alternative rejected — a separate INFO
line:* two anchors for one lifecycle event.

**Sync surfaces: none** — this change adds no parallel implementation and
touches no declared pair. (Host mode has no images and no volumes; the
override is container-only by nature, not by omission.)

**Single-owner mechanisms:**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `DeclaredVolumeOverrides.resolve(docker, image, explicitDestinations)` | `DeclaredVolumeOverrides` (immutable value; ordered paths + bound; renders argv) | `EgressGuard.create` → `GuardCommands.runGuard`; `ContainerMaterializer.create` → `DockerCommands.runContainer` (serves `<key>`, `<key>-j`, `<key>-v` through `ContainerEnvironmentBuilder`) | The previous signatures `runGuard(key, image, configDir, ownership)` and the 7-argument `runContainer(...)` are deleted — both now require the value; no builder accepts extra mounts as raw strings. Exemption: `DockerSeedCloneCommand.seedClone` (`--rm`, D6). | Parameter type (a call site without the value does not compile); `DockerCommandsSpec` asserts the fragments appear in both builders' argv and that `seedClone` still starts with `run --rm`; M2's grep (`/home/mitmproxy`, `.mitmproxy`) as a build-independent check in `tasks.md`; the Docker-gated identity spec of FR5 on the real daemon |

Identity claim: "the set of paths overridden equals the set of paths the
image declares minus the explicit mounts" — pinned on the real medium by the
FR5 spec (fixture image declaring two paths, one of which is the working
copy) rather than by the unit spec alone.

## Risks / Trade-offs

- [tmpfs content is lost on stop → start (keep, then resume)] → acceptable by
  D1: declared paths hold no factory state; the resume re-materializes tool
  state from the image. Stated in the operator guide (UX2).
- [An image relies on a declared path for large persistent data] → the 64 MB
  bound makes this fail visibly (ENOSPC inside the box) instead of silently
  leaking; the guide names the two alternatives (bake in, or under the working
  copy).
- [`image inspect` adds a daemon round-trip per container start] → bounded by
  the existing management deadline; measured cost is milliseconds against a
  container start that takes seconds. NFR-C1.
- [Docker changes the `Config.Volumes` JSON shape] → it is a stable API field
  (a JSON object keyed by path, `null` when absent); the parser treats `null`
  and `{}` alike and anything else as unparseable (D4, fail closed).
- [`add-sandbox-hardening` moves the guard's CA into the image `ca/` seam] →
  unaffected: the CA the guard *serves* arrives through the config mount; its
  `~/.mitmproxy` confdir stays ephemeral. Hardening's design gets one sentence
  saying so (proposal, Modified Capabilities).

## Migration Plan

No data migration: the change affects only newly created containers. Anonymous
volumes already on a host predate the change and are the operator's to prune
(NG1); the operator guide gains the one-liner to list them
(`docker volume ls -qf dangling=true`) with the warning that the list may
include other projects' volumes. Rollback is a revert; nothing durable was
written in the new shape.
