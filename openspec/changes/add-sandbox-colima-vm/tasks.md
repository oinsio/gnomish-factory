# Tasks: add-sandbox-colima-vm

Order follows the migration plan (design): adapter core first against the
existing contract suite, then git transport, then the host filter and
self-check, then in-VM Docker, provisioning and docs last. Requires
change A (`add-sandbox-core`) implemented; composes with change B.
VM-touching specs are Colima-gated (skip when Colima is absent), the
same pattern as Docker-gated specs.

## 1. Spikes and tool re-verification

- [ ] 1.1 Re-verify the landscape: Colima/Lima state, vz on current macOS, Apple container + socktainer, DiskImageKit, vmnet; record verdicts against the deferred-list triggers (Q5, D2)
- [ ] 1.2 Privileged-filter spike on macOS + one Linux distro: operator-installed anchor + scoped sudoers helper; fix the exact shape (Q1, D3, FR6)
- [ ] 1.3 Linux parity decision: Colima vs plain Lima, nftables rule shape (Q4, D3)

## 2. Adapter core

- [ ] 2.1 Factory config surface: `colima-vm` adapter id, VM sizing (CPU/memory/disk), filter parameters, mirror endpoint (FR1, FR2, UX1)
- [ ] 2.2 VM lifecycle: create with inverted defaults (no mounts, no agent forwarding, no port forwarder, guard-only DNS, vz), destroy idempotent incl. filter cleanup (FR1, FR2, D2)
- [ ] 2.3 Post-start config verification: backend, mount table — refuse and destroy on mismatch (FR2, D2, UX2)
- [ ] 2.4 SSH exec with multiplexing: streamed output + exit codes through the port; params from `colima ssh-config` (FR3, D5)
- [ ] 2.5 Port-level contract suite green on the VM adapter (Colima-gated) (M1)

## 3. Git transport

- [ ] 3.1 Seeding: push refs from the factory clone over SSH into a bare seed repo, in-VM working clone with agent identity, `gc.auto 0`; no remote address or credential in the guest (FR4, D6)
- [ ] 3.2 Harvest over SSH: fixed refspec, fast-forward-only, `--no-recurse-submodules`; rewritten-history refusal spec (FR5, D5)
- [ ] 3.3 Tip observation: polled fetch with factory-side rate limit; document the rev-parse/inotify escalation path without ceding rate control (FR5, D5)
- [ ] 3.4 Resume/salvage E2E in VM mode: second instance resumes mid-round task from the branch alone (FR1, M4)

## 4. Host filter, egress wiring, self-check

- [ ] 4.1 Operator setup script + docs: firewall anchor/table installation, scoped sudoers entry (per 1.2 verdict) (FR6, D3, UX1)
- [ ] 4.2 Runtime rule population: interface/address discovery, populate on VM start, empty on dispose; crash-safe cleanup at startup (FR6, FR11, NFR-R2, D3)
- [ ] 4.3 Guard wiring for VM environments: proxy endpoints as the only allowed destinations; denials into the existing findings funnel (FR6, NFR-O1)
- [ ] 4.4 VM self-check probes: backend, mounts, host-FS probe, direct DNS, guest→host SSH, filter populated, `docker info` when required; failure = infrastructure failure (FR8, D8)
- [ ] 4.5 Specs: filter lifecycle, self-check verdict matrix, orphan reclaim of VMs + stale rules (FR11, M5)

## 5. In-VM Docker

- [ ] 5.1 Pull-through registry mirror on the host: single parameterized service, filter allows it as the second endpoint; depot-seam note (FR7, D4, Q3)
- [ ] 5.2 Daemon provisioning in the VM: proxy drop-in, registry mirror config, restart (FR7, D4)
- [ ] 5.3 Passport + reconciliation: `docker: true` needs route to this adapter; container-adapter binding of such a stage refuses fail-closed (FR9, D9)
- [ ] 5.4 E2E: Testcontainers check passes inside the VM with direct egress blocked (M2, M3)

## 6. Provisioning and docs

- [ ] 6.1 Provisioning steps on the stock image: CA into system/JVM/Node/Python trust stores, Gradle/Maven/wrapper proxy configs, daemon config (FR10, D7)
- [ ] 6.2 `.gnomish/setup.sh` execution inside the VM during provisioning, before any gnome process; no VM snapshot anywhere (FR10, D7)
- [ ] 6.3 E2E assertion set: host FS invisible, no host credential material, direct DNS and egress fail, guest→host SSH dropped (M3, NFR-S1)
- [ ] 6.4 Docs: prerequisites, one-time filter setup, cost table (startup latency, disk), when to prefer the container adapter, host-mounted-file caveat (no host mounts exist — test fixtures and init scripts must live in the repository) (UX1, UX4)
