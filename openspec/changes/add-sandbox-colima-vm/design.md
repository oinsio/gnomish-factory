# Design: add-sandbox-colima-vm

## Context

Driven by FR1–FR11, NFR-S1/S2, NFR-R2 of the proposal. The
`colima-agent-sandbox.md` exploration (2026-07) settled the hard
questions empirically; this design fixes its findings into decisions.
Change A provides the opaque port (materialize / exec / harvest /
dispose, transport-agnostic), the mitmdump guard, the self-check
framework, and git-transport resume/salvage — the VM adapter is a third
implementation, not new machinery. Change B's gateway and interception
sit on the host and apply unchanged.

## Goals / Non-Goals

Design goals: put every enforcement point outside the guest, so nothing
running inside the VM — root included — can weaken its own cage; keep
the adapter a pure port implementation. Non-goals: golden images,
alternative VM runtimes, cloud execution (proposal NG1–NG3).

## Decisions

### D1. The security boundary is the VM; in-guest controls are second echelon
The gnome legitimately holds root-equivalent power inside the VM: it
needs the Docker daemon, and Docker-socket access equals root in the VM
(create a non-internal network, mount anything, `--network host`).
Therefore no in-guest construct — internal Docker networks, unprivileged
users, in-guest nftables — counts as a barrier; they remain as
defense-in-depth only. Everything load-bearing lives on the host: the
packet filter, the guard, the registry mirror, the SSH transport.
Every other decision follows from this one. Alternative rejected:
enforcing egress inside the guest (change-A style internal network) —
trivially undone by anyone holding the in-VM Docker daemon.
<!-- implements FR6, FR7 of add-sandbox-colima-vm -->

### D2. Colima on the vz backend, hostile-defaults inverted, verified
Colima (Lima wrapper, Docker-ready out of the box) with: `--vm-type vz`
(hardware virtualization), `--mount=none` (Colima mounts the home
directory **writable** by default — with it, reading `~/.ssh`,
`~/.aws/*`, and the Lima private key is a tenant escape with no
exploit), `forwardAgent: false` (else the gnome pushes with the
operator's keys), `--port-forwarder=none` (removes the guest→host
direction), DNS pointed only at the guard. One VM per environment;
destruction is a protocol step. Because Colima **silently falls back to
QEMU** when vz is unavailable — and QEMU's default mount type exposes
the host filesystem over SFTP — the adapter verifies the backend and
mount table after start and refuses on mismatch (the threat-#43 class).
Alternatives rejected: plain Lima (same engine, but Colima ships the
Docker-ready provisioning we need); Apple container / Tart / Matchlock
(deferred list with revisit triggers, proposal Q5).
<!-- implements FR2 of add-sandbox-colima-vm -->

### D3. Host packet filter: operator-installed anchor, factory-populated
Layer 2 is pf (macOS) / nftables (Linux) on the host: default-drop
everything from the VM's address — internet **and** host (an open
Remote Login or any local service is otherwise reachable) — except the
guard proxy and registry-mirror endpoints. Rule management needs root,
the factory must not run as root, so the split is: a one-time documented
operator setup installs an empty factory-owned anchor/table wired into
the host firewall config; at VM start the factory populates it via a
narrowly-scoped privileged helper (sudoers entry limited to loading
that one anchor), substituting the empirically discovered VM interface
and address (interface names — `bridge100`, `vmenet*` — vary by macOS
version and mode, so they are resolved at runtime, never hardcoded).
Populated rules are verified before the first round (FR8); dispose and
startup cleanup empty the anchor. Alternatives rejected: running the
factory with root privileges (violates least privilege for the whole
process); per-VM NAT via vmnet (not yet a stable platform mechanism —
deferred list). <!-- implements FR6, NFR-R2 of add-sandbox-colima-vm -->

### D4. Two egress paths: guard for traffic, pull-through mirror for image blobs
Same guard as changes A/B (mitmdump on the host) for API, git, and
package-manager traffic — allowlist, structured deny logs, and (with B)
interception all apply unchanged. Two change-A/B guard guarantees are
re-affirmed for the VM path explicitly, because here the guard sits on
the host next to real credentials: logs stay metadata-only — request
and response bodies are never written (with B's interception they would
contain the very tokens the guard injects); and secret injection stays
guard-side — the VM holds only a sentinel or virtual key, never a real
credential (NFR-S1/S2). Docker image layers do not go through
the proxy body path: the daemon is pointed at a pull-through registry
mirror on the host (a `registry:2`-class container), the second and last
endpoint the packet filter allows. Rationale: a Python proxy is the
wrong transport for gigabyte blobs, and the daemon — not the client —
performs pulls, so client-side `HTTP_PROXY` would not catch them anyway;
the daemon gets explicit proxy *and* mirror configuration during
provisioning. The mirror is parameterized config (change-A registry
seam) and is superseded by the artifact depot change (proposal NG4/Q3).
Alternative rejected: routing pulls through mitmdump (memory pressure,
streaming failure modes). <!-- implements FR7 of add-sandbox-colima-vm -->

### D5. SSH is the exec and harvest transport, multiplexed, host-initiated
All factory→VM operations ride SSH using parameters from
`colima ssh-config` (stable across VM recreation): `exec` runs rounds
and checks with streamed output and exit codes; harvest is
`git fetch ssh://…` with the factory-fixed refspec, fast-forward-only,
`--no-recurse-submodules`. Connection multiplexing (`ControlMaster`/
`ControlPersist`) makes polling cheap. Branch-tip observation defaults
to polled fetch (refetching an unchanged tip ≈ no-op); if live feedback
is ever needed, the escalation path is `rev-parse` over SSH, then
`inotifywait` on `.git/logs/HEAD` — watching `logs/HEAD`, not
`refs/heads/*`, because refs can silently move into `packed-refs` — but
any event source inside the VM only *wakes* the factory; fetch frequency
stays factory-rate-limited (a hostile guest must not command a fetch
storm). The direction is always host→guest; the guest holds no key that
reaches anything. <!-- implements FR3, FR5 of add-sandbox-colima-vm -->

### D6. Seeding: git-over-SSH push from the factory clone
Materialization pushes the needed refs from the factory's local clone
over the SSH transport into a bare seed repository inside the VM, then
clones the working copy locally inside the VM (agent identity,
`gc.auto 0`, no remote address or credential anywhere in the guest).
This satisfies "no network, no keys" by construction: the only channel
is the factory's own host→guest SSH session. Resume is identical with a
different starting ref — the same branch-state-only contract as change
A. Alternatives rejected: baking the repo into the VM image (image
becomes per-project and per-task — kills template reuse); `git bundle`
file transfer (works, but adds a temp-file protocol for no gain over a
direct push). <!-- implements FR4, FR6 of add-sandbox-colima-vm -->

### D7. Provisioning: scripted steps on the stock image, setup.sh inside, no VM snapshot
The VM is provisioned at instance start by factory-owned steps on the
stock Colima image: bake the factory CA into every trust store the
toolchain consults (system store, JVM `cacerts` via keytool — the JVM
ignores proxy env vars and trust env vars both —, `NODE_EXTRA_CA_CERTS`,
Python bundle vars), write proxy config for proxy-ignoring tools
(`gradle.properties`, `GRADLE_OPTS` for the wrapper, `settings.xml`),
and configure the Docker daemon (proxy drop-in + registry mirror).
`.gnomish/setup.sh` (change-B law surface) then runs inside the VM under
the same egress policy, before any gnome process. There is no VM
snapshot: the port stays snapshot-free, the change-B `docker commit`
cache does not apply to VMs, and golden images are deferred (proposal
NG2) — the cold cost is paid per environment segment and documented.
Alternative rejected: pre-built custom disk image (a second image build
pipeline for one adapter; revisit together with golden images).
<!-- implements FR10 of add-sandbox-colima-vm -->

### D8. Self-check mirrors every invariant this design relies on
Before the first round, in addition to the change-A probes (direct
egress fails / non-allowlisted denied / allowlisted passes), the VM
self-check asserts: backend is the expected hardware one (not QEMU
fallback), mount table contains no host-share mounts
(virtiofs/9p/sshfs), a host filesystem probe (`/Users`-class) fails,
direct DNS to an external resolver fails (the Lima host resolver would
otherwise proxy DNS around the guard — a documented exfiltration
channel), an SSH attempt from guest to host is dropped, the filter
anchor is populated, and — when the stage requires Docker — `docker
info` succeeds in-VM. Any mismatch: destroy, report infrastructure
failure, do not start. This is the same fail-closed pattern as change A
D5. <!-- implements FR8, NFR-R1 of add-sandbox-colima-vm -->

### D9. Passport and binding: docker-needing stages route here naturally
The adapter passport declares hardware task↔task boundary, in-VM Docker,
host-enforced egress, and its cost profile (startup latency, disk).
Change-A reconciliation then does the routing: a stage declaring
`docker: true` binds only to adapters whose passport carries it — today
host (no isolation) or `colima-vm`. Segment lifecycle, freshness knobs,
and reuse semantics are inherited unchanged.
<!-- implements FR9 of add-sandbox-colima-vm -->

## Risks / Trade-offs

- [VM interface naming on macOS is unstable across versions/modes] →
  runtime discovery + FR8 verification; never hardcoded.
- [vz unavailable (old macOS, nested virt)] → hard refusal with a clear
  message; QEMU fallback is forbidden, not degraded to.
- [One-time privileged filter setup is operator friction] → copy-paste
  documented script; the recurring path needs only the scoped sudoers
  entry (Q1 confirms the exact shape).
- [VM cold start is tens of seconds to minutes] → paid per segment;
  container adapter stays the default recommendation (UX4); golden
  images are the deferred fix.
- [Colima/Lima project health, bus factor] → the opaque port keeps
  Apple container/Tart/Matchlock as adapter swaps (deferred list with
  revisit triggers).
- [SSH host keys regenerate with each VM] → connection params and keys
  come from `colima ssh-config` per VM; no global known_hosts trust.
- [Registry mirror is one more host service] → single container,
  parameterized endpoint, superseded by the depot change (Q3).
- [Filter rules leak if the factory dies mid-task] → anchor is
  factory-owned and emptied by startup cleanup (NFR-R2, M5).

## Migration Plan

1. Adapter skeleton: VM lifecycle + config invariants + SSH exec;
   port-level contract suite green (Colima-gated).
2. Seeding, harvest, tip observation; resume/salvage E2E in VM mode.
3. Host filter (anchor + helper), guard wiring, DNS policy, full
   self-check.
4. In-VM Docker: daemon proxy/mirror provisioning; Testcontainers E2E
   (M2).
5. Provisioning polish (CA/trust stores, setup.sh), orphan cleanup,
   docs (prereqs, UX4 cost table).
   Rollback at any point: unbind the adapter — container/host bindings
   are untouched throughout.

## Open Questions

- Q1 (proposal): exact privileged-helper shape (sudoers-scoped `pfctl`
  load vs tiny setuid-free daemon) — resolve with a spike on macOS and
  one Linux distro.
- Q2 (proposal): where provisioning cost lands per segment vs per task —
  measure in step 1 and revisit if segments churn VMs too often.
- Q4 (proposal): Linux parity (Colima vs plain Lima; nftables shape) —
  resolve during step 3.
