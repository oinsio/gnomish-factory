# Change: add-sandbox-cloud-executor

## Why

The local adapters (container, Colima VM) cap parallelism at one
machine's hardware, and the Д7 trigger — task sources the operator does
not trust — calls for VM-grade isolation at a scale a laptop cannot
provide. Change A fixed the port contract as host-agnostic (git
transport and streams, no shared filesystem) precisely so that a cloud
adapter would be an adapter, not a redesign; the standing operator
decision requires the factory and its gnomes to run both locally and
in k8s. This change is ladder step D: a k8s-first cloud executor —
pod-per-environment under default-deny NetworkPolicy, with the cloud's
own hazards closed: the metadata endpoint as an SSRF credential leak
(threat #27 of `docs/sandbox-threat-registry.md`), a durable volume quietly becoming a second source of truth
(#28), data residency as a new trust point (#29), and image pulls done
by the node outside pod network policy (#30). Static secrets disappear
from remote infrastructure via a Vault-class secrets adapter with OIDC
bootstrap. Triggers for building: not enough local hardware for N
slots; Д7; factory as a service. This change also owns Docker-ladder
step 1 (neighbor service stacks) end-to-end: one filtered declaration,
realized both as pods in the task namespace here and as factory-run
containers in the task's internal network for the local container
adapter — the declaration, its filter, and their tests exist once.

## What Changes

- **ADDED**: `k8s` adapter of the `TaskExecutionEnvironment` port:
  namespace-per-task, pod-per-environment from the same OCI images,
  exec via the Kubernetes exec API, harvest over git transport, dispose
  = namespace teardown; ResourceQuota/Limits per task; the
  `factory.sandbox.runtime` knob maps to RuntimeClass (gVisor/Kata) for
  node-kernel isolation.
- **ADDED**: cloud egress: NetworkPolicy default-deny as layer 2 with an
  in-cluster guard (same mitmproxy) as the only route; mandatory block
  of the cloud metadata endpoint; self-check extended with a metadata
  probe; kubelet-side image pulls governed by cluster registry policy.
- **ADDED**: PVC warm-resume cache under the invariant "volume is cache,
  never truth" — deletable at any moment without correctness loss.
- **ADDED**: the k8s Docker ladder for in-box Docker needs: neighbor
  service stacks as pods in the task namespace under default-deny;
  kubedock as the first dynamic-Testcontainers option; sysbox/Kata
  RuntimeClass documented; privileged DinD and node CRI-socket exposure
  forbidden everywhere.
- **ADDED**: neighbor service stacks as a shared mechanism (ladder
  step 1, both forms): a stage declares a static service stack in the
  pipeline law; the factory validates it fail-closed (no privileged, no
  host mounts outside the workspace, no published ports, no socket
  mounts) and realizes it as pods in the task namespace (k8s) or as
  factory-run containers in the task's internal network (local
  container adapter).
- **ADDED**: Vault-class `SecretsProvider` adapter (OpenBao) and OIDC
  bootstrap: remote executors obtain short-lived credentials; no static
  factory secret is provisioned into cloud infrastructure.
- **MODIFIED**: sandbox provisioning — base and snapshot images are
  published to and resolved from a cluster-reachable registry.
- **MODIFIED**: sandbox egress — metadata-endpoint block and
  registry-policy requirements join the layer-2 family.
- **REMOVED**: nothing; local adapters are untouched.

## Capabilities

### New Capabilities

- `cloud-environment`: the k8s adapter — namespace/pod lifecycle, exec
  and harvest over cluster APIs, NetworkPolicy egress, PVC cache
  semantics, RuntimeClass, in-cluster Docker ladder, passport,
  cost-critical orphan cleanup.
- `secrets-provider`: the Vault-class adapter and OIDC federation for
  remote executors — short-lived credentials, no static secrets outside
  the operator's trust boundary.

### Modified Capabilities

- `sandbox-egress`: metadata-endpoint block as a mandatory layer-2 rule
  for cloud environments; self-check metadata probe; node-level image
  pulls under cluster registry policy.
- `sandbox-provisioning`: image distribution via a cluster-reachable
  registry for cloud-bound projects.
- `execution-environment`: stage `Mechanism` gains the neighbor-stack
  declaration (tighten-only, law-sourced); the container adapter gains
  the local realization — factory-run service containers in the task's
  internal network.

## Goals

- G1: a task bound to the k8s adapter runs in its own namespace behind
  default-deny NetworkPolicy, with the guard as its only egress route
  and the metadata endpoint unreachable.
- G2: parallelism scales with the cluster, not the operator's machine;
  any factory instance — local or in-cluster — resumes any cloud task
  from the branch alone.
- G3: no static secret lives in the cloud: pods bootstrap short-lived
  credentials via OIDC; losing a node or a namespace leaks nothing
  durable.
- G4: cost is bounded and observable: quotas per task, and orphaned
  cloud resources are found and destroyed automatically — an orphan is
  a bill, not just clutter.
- G5: the port and engine are untouched, and the only local-adapter
  change is the container adapter's neighbor-stack realization; k8s is
  a binding choice per stage like any other adapter.
- G6: Docker-ladder step 1 has one owner: the same filtered
  neighbor-stack declaration works on the container adapter and in
  k8s, and the box never talks to any Docker API to get its services.

## Non-Goals

- NG1: raw cloud-VM adapters (EC2-class per provider) — k8s is the one
  API over all clouds; raw VMs only if a concrete need appears later.
- NG2: operating the cluster itself (provisioning, upgrades, node
  pools) — the operator brings a cluster; docs state the required
  features (NetworkPolicy enforcement, RBAC, optionally RuntimeClass).
- NG3: GHA executor — change E, independent of this one.
- NG4: artifact depot — separate change; here only the registry-policy
  seam and the in-cluster mirror expectation.
- NG5: multi-tenant SaaS productization (per-tenant billing, tenant
  isolation guarantees beyond namespace boundaries).
- NG6: docker-compose compatibility — the neighbor-stack declaration
  is a narrow positive subset (service name, image, env, exposed
  ports), not a compose file fed to a compose engine; anything outside
  the subset is refused, not emulated.

## Users & Scenarios

- U1: operator whose machine caps at two parallel tasks binds heavy
  stages to the k8s adapter and runs ten, with per-task quotas.
- U2: operator serving less-trusted task sources binds them to the k8s
  adapter with a hardened RuntimeClass (gVisor/Kata) — VM-grade
  isolation per pod without owning hypervisors.
- U3: an injected gnome tries `http://169.254.169.254/` from inside its
  pod; the request dies in NetworkPolicy and appears as a denial
  finding.
- U4: a node is drained mid-round; the task's PVC vanishes with it; the
  factory resumes the task on another node from the branch alone —
  slower (cold cache), never incorrect.
- U5: a factory crash leaves namespaces behind; the next startup (any
  instance) reclaims them and the operator sees the reclaim in the log
  before it shows up on an invoice.
- U6: a repo declares a Postgres neighbor for its integration stage;
  the same declaration gives the check `db:5432` as a factory-run
  container in the task's internal network locally and as a pod in the
  task namespace on k8s — no repo change when the binding moves.

## Requirements

### Functional

- FR1: a `k8s` adapter SHALL implement the `TaskExecutionEnvironment`
  port: one namespace per task, one pod per environment created from
  the resolved OCI image, exec via the Kubernetes exec API with
  streamed output and exit codes, harvest over git transport with the
  factory-fixed refspec semantics, `dispose()` = teardown of the
  environment's pod and, at task end, the namespace as one idempotent
  operation.
- FR2: every task namespace SHALL carry default-deny NetworkPolicy with
  the in-cluster guard as the only permitted egress; the cloud metadata
  endpoint SHALL be explicitly unreachable; DNS SHALL resolve only via
  the guard.
- FR3: the environment startup self-check SHALL additionally prove for
  cloud environments: direct egress fails, the metadata endpoint is
  unreachable, the guard path works, and the pod's RuntimeClass matches
  the binding's declaration; any failure is an infrastructure failure
  preventing task start.
- FR4: operator-configured ResourceQuota/LimitRange SHALL bound each
  task namespace (CPU, memory, storage, object counts); the
  `factory.sandbox.runtime` knob SHALL map to RuntimeClass per binding.
- FR5: a PVC MAY cache the working clone and dependencies for warm
  resume; deleting any volume at any moment SHALL never lose task
  correctness — durable state remains tracker + task branch only.
- FR6: in-box Docker needs SHALL be served by the k8s ladder: neighbor
  stacks as pods in the task namespace under the same NetworkPolicy;
  dynamic Testcontainers via kubedock (pods created in the task
  namespace under its policies and quotas); sysbox/Kata RuntimeClass as
  documented escalations; privileged DinD sidecars and mounting node
  CRI/Docker sockets SHALL be refused always.
- FR7: the adapter passport SHALL declare: task↔task boundary =
  namespace + node kernel (upgradeable to VM-grade via RuntimeClass),
  in-box Docker via kubedock/neighbors, egress NetworkPolicy-enforced,
  cost profile (per-task quota, cluster billing); reconciliation SHALL
  route stage needs accordingly, fail-closed.
- FR8: base and snapshot images for cloud-bound projects SHALL be
  published to and resolved from a cluster-reachable registry; node
  image pulls SHALL be constrained by cluster registry policy
  (mirror/allowlist), documented as an operator checklist item.
- FR9: a Vault-class `SecretsProvider` adapter (OpenBao) SHALL
  implement the existing port; remote executors SHALL bootstrap
  short-lived credentials via OIDC federation; no static factory secret
  SHALL be provisioned into cluster resources (no long-lived tokens in
  Secrets objects).
- FR10: startup SHALL find and destroy orphaned factory-labeled
  namespaces, pods, and volumes cluster-wide; reclaim events SHALL be
  logged with the resources' age.
- FR11: the factory SHALL operate with a namespaced RBAC role limited
  to what the adapter needs (namespaces, pods, exec, PVCs, policies);
  cluster-admin SHALL NOT be required.
- FR12: a stage `Mechanism` MAY declare a static neighbor-service
  stack (service name, image, env, exposed ports); the declaration is
  pipeline law read from the factory clone and SHALL be validated
  fail-closed as untrusted input: privileged mode, host mounts outside
  the working copy, published ports, socket mounts, and capability
  additions SHALL be refused before any environment starts.
- FR13: for container-bound stages the factory SHALL realize the
  declared stack as factory-labeled containers joined to the task's
  internal network, started before the stage and disposed with the
  environment segment; the box SHALL reach services only by declared
  name; service containers SHALL be subject to operator resource
  limits and the change-A orphan cleanup, and their images SHALL
  resolve through the same registry parameters as the environment
  image.

### Non-Functional

- NFR-S1: pods SHALL hold no cloud-account credential: metadata access
  blocked, no cloud IAM roles bound to task pods, secrets only as
  short-lived OIDC-issued values; guard and factory config unreachable
  from task namespaces.
- NFR-S2: the choice to move task data (code, prompts, findings) into a
  cloud SHALL be an explicit operator decision, documented as a trust
  and jurisdiction point in the adapter passport.
- NFR-R1: cluster API and guard outages SHALL be infrastructure
  failures (retries, no stage attempt burned); node loss mid-round is
  recoverable via resume from the branch.
- NFR-R2: disposal and orphan cleanup SHALL be idempotent and
  crash-safe across instances; two instances cleaning concurrently
  SHALL NOT interfere with live tasks.
- NFR-O1: NetworkPolicy denials and guard denials SHALL land in the
  same findings funnel; namespace lifecycle, quota exhaustion, and
  orphan reclaim SHALL be logged.
- NFR-P1: pod startup overhead SHALL stay small against round duration;
  warm PVC reuse within a task SHALL avoid repeated clones and
  dependency downloads.
- NFR-C1: every factory-created cloud resource SHALL be labeled and
  bounded by quota; orphan reclaim SHALL run on every startup — an
  unreclaimed orphan is a direct monetary cost.

## Operator Experience Criteria

- UX1: enabling k8s is factory config plus documented cluster
  prerequisites (kubeconfig/RBAC manifest shipped, NetworkPolicy
  enforcement verified, registry endpoint); no target-repo changes.
- UX2: a refusal names the failed probe or unmet need ("metadata
  endpoint reachable", "RuntimeClass gvisor absent", "quota missing"),
  not a generic cloud error.
- UX3: the task report distinguishes guard denials, NetworkPolicy
  drops, and quota kills at a glance.
- UX4: docs carry the cloud checklist: metadata block verification,
  registry policy, RuntimeClass options with their trade-offs, the
  data-residency statement, and the "volume is cache" rule.

## Success Metrics

- M1: the k8s adapter passes the same port-level contract spec suite as
  the other adapters (against a disposable local cluster).
- M2: E2E: metadata-endpoint probe and direct egress fail from a task
  pod while allowlisted traffic via the in-cluster guard passes.
- M3: E2E: a task's PVC is deleted mid-task; the task resumes from the
  branch and completes correctly.
- M4: E2E: a Testcontainers-style check passes via kubedock inside the
  task namespace under default-deny NetworkPolicy.
- M5: E2E: orphaned namespaces from a killed instance are reclaimed on
  the next startup, and a second instance resumes the interrupted task.
- M6: E2E assertion: no static secret exists in any cluster resource of
  a task; credentials observed in pods are short-lived.
- M7: E2E (local): a check reaches its declared neighbor service by
  name inside the task's internal network while direct egress stays
  blocked; a declaration with privileged mode, a host mount, or a
  published port is refused fail-closed naming the forbidden
  mechanism.

## Open Questions

- Q1: in-cluster guard topology — one guard per task namespace vs a
  shared guard namespace with per-task policy selectors; measure both
  against policy-isolation and cost.
- Q2: disposable cluster for CI/E2E — k3s/kind via Testcontainers, and
  whether NetworkPolicy enforcement there is faithful enough for the
  egress specs (else gate on a real cluster).
- Q3: kubedock maturity re-check at implementation start (API coverage,
  bind-mount gaps) — the explore ladder carries a review-before-build
  note.
- Q4: managed egress filtering (AWS Network Firewall / GCP Secure Web
  Proxy) as an operator alternative to the in-cluster guard — document
  as option or leave out entirely.
- Q5: OIDC issuer/trust wiring for a factory running outside the
  cluster vs inside it — resolve with the SecretsProvider spike.
- Q6: exact neighbor-declaration subset — whether real stacks (e.g.
  Supabase-class) need healthchecks/start-order fields or the minimal
  subset suffices; decide against concrete stacks during tasks.

## Impact

- New adapter package (k8s client via the existing HTTP stack or
  official client — design decision); local adapters untouched except
  the container adapter, which gains the neighbor-stack realization
  (FR13); stage-manifest schema extends for the declaration (FR12).
- New operator prerequisites for cloud mode: a cluster with
  NetworkPolicy enforcement, RBAC manifest, cluster-reachable registry.
- New services in-cluster: the guard (same mitmproxy image), optionally
  kubedock per task namespace.
- SecretsProvider gains its second adapter (OpenBao) and OIDC flows.
- Factory config surface: kubeconfig/context, namespace prefix, quotas,
  RuntimeClass bindings, registry endpoint.
- Depends on change A (port, guard, self-check, git transport); composes
  with change B (gateway/virtual keys); independent of changes C and E.
