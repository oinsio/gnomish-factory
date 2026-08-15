# Design: add-sandbox-cloud-executor

## Context

Driven by FR1–FR13, NFR-S1/S2, NFR-C1 of the proposal. Change A's port
contract was made host-agnostic for exactly this adapter (operator
decision 2026-07-23: no shared-FS assumption, git transport and streams
only), so the engine needs no changes and the only local-adapter touch
is the container adapter's neighbor-stack realization (D10). The explore
sessions (2026-07-23) settled the k8s-first direction, the Docker
ladder in k8s, the metadata-endpoint hazard, and the "volume is cache"
invariant; this design fixes the remaining structure.

## Goals / Non-Goals

Design goals: everything the local adapters enforce by construction
must have a k8s-native equivalent enforced by the cluster, not by
in-pod convention; cloud resources are always labeled, quota-bounded,
and reclaimable. Non-goals: raw cloud-VM adapters, cluster operations,
GHA (changes/decisions listed in proposal NG1–NG3).

## Decisions

### D1. k8s-first: one API over all clouds, pod-per-environment
The adapter targets the Kubernetes API, not any provider's VM API:
managed k8s exists everywhere the operator might go (including VK), and
namespace/pod/NetworkPolicy/Quota/RuntimeClass map one-to-one onto the
constructs changes A/C built by hand. Environment = pod; task =
namespace: the namespace is the isolation and cleanup unit (policies,
quotas, neighbor pods, kubedock all scope to it), the pod is the
lifecycle unit matching environment segments. Exec = Kubernetes exec
API (streamed, exit codes — the port's native shape); harvest = git
transport into the factory clone, transport details private to the
adapter. Alternative rejected: raw VM adapters per cloud (N APIs, no
policy primitives, everything hand-built again).
<!-- implements FR1 of add-sandbox-cloud-executor -->

### D2. Layer 2 is NetworkPolicy; the guard moves into the cluster
Default-deny NetworkPolicy on every task namespace is the cloud
realization of "no route out": egress permitted only to the guard, DNS
included; the metadata endpoint (169.254.169.254) is denied explicitly
— a deceived gnome doing SSRF against it would otherwise hold the
node's cloud identity (threat #27), the classic cloud-agent failure.
The guard is the same mitmproxy image run in-cluster; allowlist and
(with change B) interception config are unchanged. Cluster registry
policy covers the one path NetworkPolicy cannot: image pulls are
performed by the kubelet on the node, outside pod policy (threat #30)
— an operator checklist item with a documented mirror/allowlist
recipe, not something the factory can enforce itself. Guard topology
(per-namespace vs shared namespace + selectors) is Q1 — start
per-namespace (strictest, simplest reasoning), collapse to shared if
cost demands. Alternative rejected: relying on SecurityGroups/NACLs
(IP-only, no domain semantics) or paid managed L7 filters as the
default (provider-specific; kept as documented option, Q4).
<!-- implements FR2, FR8 of add-sandbox-cloud-executor -->

### D3. Volume is cache, never truth
A PVC per task may hold the clone and dependency caches so resume on
the same cluster is warm, but every durable fact lives in tracker +
task branch (change A invariant, unchanged): the adapter must survive
`kubectl delete pvc` at any instant with only a latency penalty. This
kills the tempting failure mode where cloud volumes quietly become a
second source of truth and the factory stops being stateless (threat
#28). Consequence: dispose deletes the PVC by default; keeping it is a
warm-resume optimization knob, and orphan cleanup treats aged PVCs as
garbage. <!-- implements FR5 of add-sandbox-cloud-executor -->

### D4. RuntimeClass is the isolation dial, per binding
`factory.sandbox.runtime` (change A knob) maps to RuntimeClass:
default runc, gVisor/Kata where the operator provisioned them —
per-binding, so an untrusted-source project runs VM-grade (Kata) while
trusted ones stay on runc, on the same cluster. The self-check asserts
the pod actually got the declared RuntimeClass (the vz→QEMU silent
degradation class, threat #43). Node-kernel sharing under runc is the
honestly-documented residual, mirroring the container adapter's
passport. <!-- implements FR3, FR4 of add-sandbox-cloud-executor -->

### D5. Docker ladder in k8s: neighbors → kubedock → RuntimeClass
No Docker on nodes (containerd), and the two classic CI answers are
forbidden everywhere: privileged DinD sidecars (dissolves isolation,
banned by PodSecurity restricted) and mounting the node's CRI socket
(hands over the node). The ladder: (1) neighbor service stacks =
plain pods in the task namespace under its policy and quota — better
than the local variant; (2) dynamic Testcontainers via kubedock: a
Docker-API emulator whose "containers" are pods in the same namespace
— DOCKER_HOST is transparent to Testcontainers, and everything it
creates obeys the namespace's NetworkPolicy and quota; its API gaps
(exotic features, bind-mounts) are documented; (3) real dockerd-in-pod
when the operator controls node pools — sysbox RuntimeClass as the
shared-kernel intermediate step, Kata RuntimeClass as the ladder's real
answer and ceiling: the pod gets its own microVM kernel, so dockerd
inside is legal without weakening anyone else's isolation.
Testcontainers Cloud stays rejected (third party outside the
guard). <!-- implements FR6 of add-sandbox-cloud-executor -->

### D6. Secrets: OpenBao adapter + OIDC, nothing static in the cluster
The `SecretsProvider` port (change A, D12) gains its Vault-class
adapter (OpenBao — open fork; Vault itself is BUSL) and the bootstrap
flow that makes cloud mode safe: in-cluster workloads authenticate to
OpenBao via their k8s/OIDC identity and receive short-lived
credentials; a factory outside the cluster uses its own OIDC trust
(Q5). Task pods themselves receive only what changes A/B already
allow — the virtual AI key or sentinel — via env at creation; no
long-lived token is ever written into a Secrets object. The gateway
remains the budget authority (change B); OpenBao stores the keys that
bootstrap it. <!-- implements FR9, NFR-S1 of add-sandbox-cloud-executor -->

### D7. RBAC: one namespaced role, no cluster-admin
The factory's service account gets a shipped RBAC manifest scoped to
its needs: create/delete namespaces with the factory label, and within
them pods, exec, PVCs, NetworkPolicies, quotas; read-only on nodes for
diagnostics. Orphan cleanup lists by factory labels cluster-wide but
touches only labeled resources. Rationale: the factory is itself a
long-lived credential holder — its blast radius must be bounded the
same way it bounds its gnomes.
<!-- implements FR10, FR11, NFR-R2 of add-sandbox-cloud-executor -->

### D8. Orphan cleanup is a cost control, not hygiene
Every created resource carries factory labels (project, task,
instance, created-at). Startup reclaim destroys labeled resources
whose task is no longer live — same protocol as change A, but the
stakes change: an orphaned namespace bills by the hour. Reclaim logs
age and count so the operator sees the money saved/lost; concurrent
instances coordinate exactly as they do for task claims (tracker is
the lock authority, not the cluster).
<!-- implements FR10, NFR-C1 of add-sandbox-cloud-executor -->

### D9. E2E on a disposable in-CI cluster, honesty about policy fidelity
Contract and E2E suites run against a disposable local cluster
(k3s-class via Testcontainers). NetworkPolicy enforcement fidelity
there decides Q2: if the local CNI enforces default-deny faithfully,
egress specs run in CI; if not, egress E2E is gated to a real cluster
profile and CI covers lifecycle/resume/quota. Either way the
self-check (FR3) proves enforcement on every real deployment — CI
fidelity is a testing concern, not a production safety dependency.
<!-- implements M1, M2 of add-sandbox-cloud-executor -->

### D10. Neighbor stacks: one filtered declaration, two realizations
Ladder step 1 is owned here end-to-end. The stack is declared in the
stage `Mechanism` as a narrow positive subset (service name, image,
env, exposed ports — Q6 may add healthcheck/start-order), loaded as
pipeline law from the factory clone (change A D14 semantics) and
validated fail-closed: no privileged, no host mounts outside the
workspace, no published ports, no socket mounts, no capability adds.
Locally the factory runs each service as a labeled container joined to
the task's `--internal` network — same lifecycle, limits, and orphan
sweep as the environment; in k8s the same declaration renders as pods
in the task namespace (D5 step 1). The box never talks to any Docker
API: services are factory-run, reachable only by declared name.
*Alternative rejected:* feeding the repo's docker-compose file to a
compose engine — full compose is a large untrusted surface (build
contexts, profiles, host mounts), and sanitizing it credibly means
reimplementing most of it; a positive subset is smaller than a compose
filter. *Alternative rejected:* shipping the local form as its own
change — two changes would each own half of one mechanism (declaration
vs realization) and drift.
<!-- implements FR12, FR13 of add-sandbox-cloud-executor -->

## Risks / Trade-offs

- [CNI differences: NetworkPolicy is enforced by the cluster's CNI,
  fidelity varies] → self-check proves the actual guarantees per
  deployment; docs list verified CNIs; fail-closed on probe failure.
- [kubedock API gaps break exotic Testcontainers usage] → documented
  ladder: escalate to sysbox/Kata RuntimeClass; Q3 re-check.
- [Per-namespace guard multiplies pods] → Q1 measures; collapse to a
  shared guard namespace with policy selectors if cost demands.
- [Cloud data residency is a real trust change] → explicit passport
  statement + docs (NFR-S2); operator decides per project.
- [Cluster API rate limits under many parallel tasks] → the factory
  already rate-limits polls host-side; k8s client gets the same
  Resilience4j treatment as every other integration.
- [OIDC wiring differs per cluster flavor] → env/file adapter remains
  the fallback everywhere; OpenBao path documented for the main
  flavors (Q5 spike).
- [Neighbor-declaration subset too narrow for a real stack] → Q6
  validates against concrete stacks; the documented escalation is
  kubedock (dynamic) or the VM adapter, never subset loosening toward
  raw compose.

## Migration Plan

1. k8s client + namespace/pod lifecycle + exec against the contract
   suite on a disposable cluster.
2. Git transport (seed/harvest) + PVC cache + resume E2E; volume-kill
   E2E (M3).
3. NetworkPolicy + in-cluster guard + metadata block + self-check
   probes (M2).
4. Docker ladder: shared neighbor declaration + both realizations
   (local M7, k8s), kubedock (M4); RuntimeClass dial.
5. OpenBao adapter + OIDC bootstrap (M6); RBAC manifest; orphan
   reclaim (M5); docs and cloud checklist.
   Rollback at any point: unbind the adapter; local bindings are
   untouched throughout.

## Open Questions

- Q1 (proposal): guard topology — resolve in step 3 with cost data.
- Q2 (proposal): CI cluster NetworkPolicy fidelity — resolve in step 1.
- Q3 (proposal): kubedock state — re-verify at step 4 start.
- Q5 (proposal): OIDC trust for out-of-cluster factories — spike at
  step 5; env/file adapter is the interim.
