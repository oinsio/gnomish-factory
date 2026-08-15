# Change: add-sandbox-colima-vm

## Why

Changes A/B isolate gnome execution in a Docker container, but two limits
remain on a developer machine. First, the container boundary is software:
on Linux all boxes share the host kernel, and on macOS all boxes share one
Docker VM — the task↔task boundary is a residual risk change A explicitly
accepted (threat #21). Second, dynamic Docker inside the box
(Testcontainers-style integration tests) is honestly unsupported in
container mode — on a Mac there is no safe path at all (Docker-strategy
ladder, step 2). This change is ladder step C: a local per-task virtual
machine adapter (Colima) for the same `TaskExecutionEnvironment` port. The
security boundary moves to the VM: hardware isolation per task, a real
Docker daemon legally inside (its own kernel — ladder step 3), egress
enforced by a host-side packet filter in front of the same guard. The
trigger is "the operator needs a hardware boundary locally" — in-box
Docker is the frequent special case, not the only reason. The
`colima-agent-sandbox.md` exploration (2026-07) closed the research
questions; threat-registry items addressed: #21 (hardware task↔task
boundary), #24/#25 (Docker inside without socket passthrough or
privileged), #43 (silent isolation degradation, the vz→QEMU class).

## What Changes

- **ADDED**: `colima-vm` adapter of the `TaskExecutionEnvironment` port:
  one VM per environment, created with hardware virtualization, destroyed
  as a mandatory lifecycle step; exec and harvest travel over SSH (the
  port contract is already transport-agnostic); passport declares a
  hardware task↔task boundary and in-VM Docker support.
- **ADDED**: VM isolation invariants, enforced and self-checked: no host
  filesystem mounts (Colima mounts the home directory writable by
  default), no SSH agent forwarding, no guest→host port forwarding,
  expected virtualization backend (silent QEMU fallback refused).
- **ADDED**: host-side egress layer 2 for the VM adapter: a packet filter
  (pf on macOS, nftables on Linux) default-drops everything from the VM —
  internet and the host itself — except the guard endpoints; DNS resolves
  only through the guard; filter rules are installed and removed with the
  VM lifecycle.
- **ADDED**: in-VM Docker for gnome workloads: daemon egress via the
  guard (daemon-level proxy config), image pulls via a pull-through
  registry mirror instead of pushing gigabyte blobs through the proxy.
- **MODIFIED**: sandbox provisioning — the VM instance is provisioned
  from the factory-baked template (CA and proxy configs for all runtime
  trust stores, Docker daemon config); `.gnomish/setup.sh` runs during VM
  provisioning, before any gnome process; no snapshot of a gnome-touched
  VM (change-A invariant holds; VM golden images are deferred).
- **MODIFIED**: sandbox egress self-check gains VM-adapter probes: host
  filesystem invisible, mount table empty, backend matches, direct DNS to
  an external resolver fails.
- **REMOVED**: nothing; container and host adapters are unchanged, the
  VM adapter is one more operator-bindable option.

## Capabilities

### New Capabilities

- `vm-environment`: the Colima VM adapter — VM lifecycle and isolation
  invariants, SSH exec/harvest transport, host-side packet filter, in-VM
  Docker, VM provisioning, adapter passport, orphan cleanup.

### Modified Capabilities

- `sandbox-egress`: layer 2 gains a host-packet-filter realization for
  VM-class adapters (block internet *and* host, guard-only exceptions);
  DNS-only-via-guard becomes an explicit probe; self-check extended with
  isolation-mechanism probes for VMs.
- `sandbox-provisioning`: setup.sh executes inside the VM provisioning
  phase for VM-bound projects; the snapshot cache stays container-only —
  VM environments provision per instance until golden images arrive.

## Goals

- G1: a task bound to the VM adapter runs behind a hardware boundary:
  escape to the host or to a neighboring task requires a hypervisor
  escape, not a container escape.
- G2: dynamic Docker (Testcontainers, compose) works inside the box with
  no socket passthrough, no privileged mode, and no third-party service —
  and its egress still obeys the guard allowlist.
- G3: the egress guarantee is enforced outside the guest: nothing running
  inside the VM — root included — can widen its own network reach.
- G4: the adapter is a pure port implementation: no change to the port
  contract, the engine, or the other adapters; binding is the same
  operator mechanism as change A.
- G5: isolation degradation is loud: every invariant the design relies on
  (mounts, backend, DNS, filter) is probed at startup and refuses the
  task fail-closed on mismatch.

## Non-Goals

- NG1: Apple `container`, Tart, Matchlock, and other VM runtimes — future
  alternative adapters of the same port; Colima is the one built now.
- NG2: VM golden images / copy-on-write templates (DiskImageKit-class)
  and per-VM NAT (vmnet) — deferred until the platform provides them;
  cold provisioning cost is accepted and documented.
- NG3: cloud/k8s executor (change D) and GHA executor (change E).
- NG4: artifact depot — separate change; the registry pull-through mirror
  here is an image-blob transport detail, superseded by the depot when it
  lands.
- NG5: multi-tenant SaaS hardening — this is still a single-operator
  local machine; the VM raises the boundary, not the trust model.

## Users & Scenarios

- U1: operator whose project runs Testcontainers-based integration tests
  binds the heavy test stage to the VM adapter; the tests get a real
  in-VM Docker daemon while lighter stages stay on the container adapter.
- U2: operator wanting hardware isolation locally (e.g., tasks from a
  wider circle than the core team) binds all stages to the VM adapter and
  accepts slower environment startup.
- U3: a gnome — or a compromised build script running as root in the VM —
  attempts a direct connection, a DNS lookup against an external
  resolver, or an SSH hop to the host; every path dies at the host packet
  filter and appears as a denial finding.
- U4: any factory instance resumes a VM-bound task interrupted mid-round:
  the branch state is harvested over SSH and a fresh VM is materialized
  from the branch alone.

## Requirements

### Functional

- FR1: a `colima-vm` adapter SHALL implement the `TaskExecutionEnvironment`
  port with one VM per environment; `dispose()` SHALL destroy the VM (and
  its host-side filter rules) as one idempotent operation; VM destruction
  is a mandatory protocol step, not scheduled cleanup.
- FR2: the adapter SHALL create VMs with: no host filesystem mounts, SSH
  agent forwarding disabled, guest→host port forwarding disabled, DNS
  pointed only at the guard, and the hardware virtualization backend
  expected for the platform; operator-configured CPU/memory/disk limits
  SHALL apply per VM.
- FR3: `exec` SHALL run commands inside the VM over SSH with streamed
  stdout/stderr and exit codes through the existing port contract; SSH
  connections SHALL be multiplexed so round execution and polling do not
  pay per-call handshakes.
- FR4: materialization SHALL seed the repository into the VM without
  network access or credentials (source: the factory's local clone) and
  create the working clone with `--no-hardlinks`, `gc.auto` off, and
  agent-only identity; no remote address or key SHALL exist inside the VM.
- FR5: harvest SHALL fetch the task branch from the VM over SSH with the
  factory-fixed refspec, fast-forward-only, `--no-recurse-submodules`;
  branch-tip observation SHALL poll from the factory side with
  factory-owned rate limiting (an in-VM event source can never force
  fetch frequency).
- FR6: a host-side packet filter SHALL default-drop all traffic from the
  VM — to the internet and to the host itself — except the guard
  endpoints (proxy, registry mirror); rules SHALL be installed before the
  first round and removed at dispose; a VM whose filter cannot be
  verified SHALL NOT run a task.
- FR7: the VM SHALL run a Docker daemon usable by gnome processes; the
  daemon's egress SHALL go through the guard via daemon-level proxy
  configuration, and image pulls SHALL go through a pull-through registry
  mirror rather than the proxy body path.
- FR8: the environment startup self-check SHALL additionally prove for VM
  environments: the host filesystem is not visible, the mount table
  contains no host-share mounts, the virtualization backend matches the
  expected one, direct DNS to an external resolver fails, and — when the
  stage requires in-box Docker — the in-VM daemon is functional; any
  failure is an infrastructure failure preventing task start.
- FR9: the adapter passport SHALL declare: hardware task↔task boundary,
  in-VM Docker supported, egress control host-enforced; needs/passport
  reconciliation from change A SHALL route `docker: true` stage needs to
  this adapter's capabilities.
- FR10: VM provisioning SHALL bake the factory CA into every runtime
  trust store used by the toolchain (system, JVM, Node, Python), bake
  guard proxy configuration for proxy-ignoring tools (Gradle/JVM,
  wrapper, Maven), and configure the Docker daemon (proxy, mirror);
  `.gnomish/setup.sh`, when present, SHALL run during provisioning before
  any gnome process, under the same egress policy.
- FR11: startup SHALL find and destroy orphaned factory VMs and stale
  filter rules by factory-owned naming/labels (mirror of change A orphan
  cleanup).

### Non-Functional

- NFR-S1: no host secret SHALL be reachable from the VM: no home-directory
  mounts (closes `~/.ssh`, cloud credentials, and the Lima/Colima keys
  that would open neighboring sandboxes), no agent forwarding, no
  host-bound services reachable through the filter.
- NFR-S2: the change-A secret rules hold unchanged inside the VM: env by
  positive allowlist, virtual key or sentinel only, guard and factory
  config unreachable from inside.
- NFR-R1: VM boot, SSH, or filter failures SHALL be infrastructure
  failures (retries, no stage attempt burned); a VM that fails its
  self-check is destroyed and reported, never reused.
- NFR-R2: disposal and orphan cleanup SHALL be idempotent and crash-safe
  including host filter state: a factory killed at any point leaves no VM
  and no filter rule a restart cannot reclaim.
- NFR-O1: filter drops and guard denials SHALL land in the same findings
  funnel as change A denials; VM lifecycle events (create, self-check
  verdicts, destroy, orphan reclaim) SHALL be logged.
- NFR-P1: VM cold start (boot + provisioning) SHALL be paid at most once
  per environment segment and documented honestly; polling overhead SHALL
  stay negligible via SSH multiplexing; the container adapter remains the
  default recommendation for latency-sensitive setups.
- NFR-C1: no new cost surface: AI-channel budgets are unchanged (change
  B); VM resource ceilings bound host resource consumption per task.

## Operator Experience Criteria

- UX1: enabling the VM adapter is factory config only: bind stages to
  `colima-vm`, set VM sizing — no target-repo changes; prerequisites
  (Colima installed, one-time host-filter setup) are documented with copy-
  paste commands.
- UX2: a refused task names the exact failed invariant ("home directory
  is mounted", "backend fell back to QEMU", "filter rules absent") — not
  a generic sandbox error.
- UX3: the operator can tell filter drops from guard denials in the task
  report, and both from infrastructure outages, at a glance.
- UX4: docs state plainly what the VM adapter costs (startup latency,
  disk per VM, one-time privileged filter setup), when to prefer the
  container adapter, and that tests relying on files mounted from the
  host break under the no-mount VM — fixtures and init scripts must
  live in the repository.

## Success Metrics

- M1: the VM adapter passes the same port-level contract spec suite as
  the host and container adapters.
- M2: E2E: a Testcontainers-based check (e.g., Postgres) passes inside a
  VM whose direct egress is blocked — proving mirror, daemon proxy, and
  allowlist together.
- M3: E2E: direct DNS (`dig @8.8.8.8`-class) and direct egress from the
  VM fail; host filesystem is invisible (`/Users`-class probe); an SSH
  attempt from the VM to the host is dropped.
- M4: E2E: a VM-bound task interrupted mid-round is resumed by a second
  factory instance from the branch alone.
- M5: E2E: after `dispose()` and after a simulated factory crash plus
  restart, no VM, volume, or filter rule of the task remains.

## Open Questions

- Q1: privileged host-filter management — pf/nftables rules need root;
  choose between a one-time operator-installed anchor that the factory
  populates and a sudo-invoked helper (design decision, UX1-sensitive).
- Q2: VM provisioning mechanics — provision steps over the stock Colima
  image at instance start vs a pre-built custom image; where the
  provisioning cost lands and what `snapshot-max-age` means for VMs
  (golden images deferred, NG2).
- Q3: registry pull-through mirror — minimal own service now vs waiting
  for the artifact depot change to subsume it (NG4 seam).
- Q4: Linux host parity — Colima on Linux vs plain Lima/QEMU and the
  nftables rule shape; verify at implementation start.
- Q5: re-verify the tool landscape at implementation start (Colima/Lima
  state, Apple container + socktainer maturity, DiskImageKit, vmnet) —
  the deferred list carries revisit triggers.

## Impact

- New adapter package alongside the container adapter; the environment
  port, engine, and existing adapters are untouched (G4).
- New host prerequisites for VM mode: Colima (+ hardware virtualization),
  a one-time packet-filter setup, disk headroom per VM.
- Guard (mitmproxy) is reused as-is on the host; one new lightweight
  service (registry pull-through mirror) until the depot change.
- Factory config surface: adapter binding id, VM sizing, filter
  parameters, provisioning knobs.
- Depends on change A (port, guard, self-check framework, git-transport
  resume/salvage); composes with change B (gateway/virtual keys and
  interception apply unchanged — the guard sits on the host either way).
