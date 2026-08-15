# Change: add-artifact-depot

## Why

With changes A/B the box's allowlist still contains public package
registries — and each of them is an open door with two known failure
modes. First, an allowlisted registry is an exfiltration channel: data
can be encoded into the request paths the gnome sends (threat #40), and
the guard cannot tell a legitimate dependency lookup from a covert
upload of secrets one path segment at a time. Second, upstream packages
arrive with zero delay: a freshly-poisoned version (the Nx
"s1ngularity" class, threat #41) reaches the build the hour it is
published, while such compromises are typically caught within days.
This change puts a factory-owned artifact depot (Nexus-class proxy
registry) between the boxes and the world: the gnome talks only to the
depot, the depot talks upstream. The box allowlist collapses to two
addresses — gateway and depot. The git host is deliberately not a
third: the box's clone is seeded from the factory's local clone and
results leave via factory-side harvest, so the box needs no git-server
access at all — the "foreign repository on an allowed git host" door
(threat #15) closes for the box entirely and remains only a
factory-side concern. Path-encoded exfiltration dies because
the attacker never controls what the depot requests upstream and never
sees its logs; a version cooldown holds fresh packages back; and
private-registry credentials move to the depot's upstream config, so
setup-phase secrets die by construction (strengthening the threat-#11
closure). The depot also subsumes the change-C docker pull-through
mirror. Change A already parameterized registry addresses in baked
configs — enabling the depot is a config change, as designed.

## What Changes

- **ADDED**: a factory-owned artifact depot proxying the ecosystems the
  toolchain needs (Maven/Gradle, npm, PyPI, Docker images, OS packages
  (apt), raw/generic downloads), with local caching; boxes resolve all
  dependencies through it.
- **ADDED**: allowlist collapse — with the depot enabled, direct
  registry hosts leave the box allowlist; the guard enforces it (a
  rewritten `build.gradle` pointing upstream hits a denial, recorded as
  a finding).
- **ADDED**: intake policy at the depot: version cooldown (only
  versions older than a configurable age are served), quarantine/CVE
  blocking aligned with the CI OSV gate, operator-managed exceptions.
- **ADDED**: per-task download journal — every artifact a task fetched,
  attached to the task report, with anomaly signaling.
- **ADDED**: private upstream registries as depot upstream config —
  credentials live only at the depot; no registry credential ever
  enters a box or the provisioning phase.
- **MODIFIED**: sandbox egress — the two-address box allowlist
  (gateway + depot) becomes the documented default; direct-registry
  denials are expected findings.
- **MODIFIED**: sandbox provisioning — baked build configs point at the
  depot via the existing parameters; the change-C pull-through mirror
  role is served by the depot where the depot is deployed.
- **REMOVED**: nothing; without the depot enabled, the change-A/B
  allowlist behavior is unchanged.

## Capabilities

### New Capabilities

- `artifact-depot`: the depot service contract — ecosystem proxying and
  caching, cooldown and quarantine policy, per-task download journal,
  upstream ownership and credentials, admin-plane isolation,
  fail-closed availability semantics.

### Modified Capabilities

- `sandbox-egress`: allowlist collapse to gateway + depot when the
  depot is enabled; direct-registry attempts as recorded denials;
  self-check probe for depot reachability.
- `sandbox-provisioning`: baked registry parameters resolve to the
  depot; provisioning (including `setup.sh` downloads) flows through
  the depot under the same policy; no registry secret in any phase.

## Goals

- G1: the gnome cannot speak to any upstream registry: every dependency
  byte enters through the depot, and what the depot requests upstream
  is never attacker-controlled.
- G2: a freshly-published package version cannot reach a build until it
  has survived the cooldown window; known-bad versions are blocked at
  the depot.
- G3: no registry credential — public or private — exists in any box or
  provisioning phase; private upstreams are a depot-side concern.
- G4: the operator sees exactly what every task downloaded and is
  alerted to anomalies; a denied direct-registry attempt is a visible
  signal, not silence.
- G5: enabling the depot is configuration only: the guard allowlist and
  the parameterized registry addresses from change A switch together;
  no engine, port, or adapter changes.

## Non-Goals

- NG1: hosting the factory's own build artifacts (publish/deploy
  repositories) — the depot is a read path for dependencies.
- NG2: reachability from GHA-hosted runners — change E's passport
  honestly declares direct registries; wiring runners to an
  operator-exposed depot is not built.
- NG3: building CVE scanning — the depot consumes block decisions
  (quarantine lists, the CI OSV gate's data); it does not replace the
  CI security gates.
- NG4: air-gapped/offline operation and license-policy enforcement —
  out of scope.

## Users & Scenarios

- U1: operator enables the depot: factory config switches the guard
  allowlist and the baked registry parameters together; the next task's
  builds resolve through the depot with no target-repo changes.
- U2: an injected gnome rewrites the build script to exfiltrate secrets
  encoded in request paths to a registry host; the request dies at the
  guard (host not allowlisted) and the attempt is a finding in the task
  report.
- U3: a dependency's brand-new version is compromised upstream; the
  gnome's build asks for it during the cooldown window and gets a
  distinct "version too new" refusal; the build uses the previous
  version or the task escalates with a clear message.
- U4: a project needs a niche registry; the repo declares the need, the
  operator adds it as a depot upstream (with the same cooldown), and no
  box allowlist entry is created.
- U5: reviewer scans the task report: the download journal lists every
  artifact the task pulled; a package never seen in this project is
  flagged.

## Requirements

### Functional

- FR1: the factory SHALL integrate an artifact depot proxying, at
  minimum: Maven artifacts, Gradle plugins, npm packages, PyPI
  packages, Docker images, OS packages (apt), and raw/generic
  downloads; the depot caches what it serves.
- FR2: with the depot enabled, box egress SHALL be limited to the
  gateway and the depot: direct registry hosts SHALL be absent from the
  box allowlist, requests to them SHALL be denied by the guard and
  recorded as findings; the environment self-check SHALL prove the
  depot is reachable and a direct registry is not.
- FR3: the depot SHALL serve a package version only after it is older
  than the operator-configured cooldown age (configurable per
  ecosystem); a cooldown refusal SHALL be a distinct, identifiable
  error naming the artifact, its age, and the policy.
- FR4: the depot SHALL block artifacts on an operator-managed
  quarantine list and versions matching configured vulnerability-block
  data (aligned with the CI OSV gate); operator-approved exceptions
  SHALL be possible per artifact/version.
- FR5: the factory SHALL attach a per-task download journal (artifact
  coordinates fetched during the task) to the task report; artifacts
  outside the project's established baseline SHALL be flagged as an
  anomaly signal.
- FR6: credentials for private upstream registries SHALL exist only in
  depot upstream configuration; no registry credential SHALL enter any
  box, any provisioning phase, or any baked config.
- FR7: adding or changing a depot upstream SHALL be an operator action
  on the depot; a repo MAY declare the need in `.gnomish/` but SHALL
  NOT be able to introduce an upstream or a box allowlist entry.
- FR8: baked build configs (Gradle/Maven/npm/pip, Docker daemon mirror)
  SHALL resolve to the depot through the change-A registry parameters;
  these configs are convenience — enforcement SHALL remain at the
  guard.
- FR9: where the depot is deployed, Docker image pulls of sandbox
  workloads SHALL flow through its registry proxy, superseding the
  change-C pull-through mirror role.
- FR10: depot unavailability SHALL be an infrastructure failure
  (retries, no stage attempt burned); the factory SHALL never fall back
  to direct upstream access.
- FR11: the depot admin plane SHALL be unreachable from boxes; the
  box-reachable endpoint SHALL serve resolution only.

### Non-Functional

- NFR-S1: depot selection and hardening SHALL apply the threat-#31
  maturity criteria: no default admin credentials in operation,
  fail-closed behavior, active maintenance; admin credentials come via
  the `SecretsProvider` port.
- NFR-S2: depot logs SHALL NOT be reachable from boxes; upstream
  request logs are operator-only (they reveal resolution behavior an
  attacker could tune against).
- NFR-R1: the depot cache SHALL survive depot restarts; cache loss
  SHALL cost re-download time, never correctness.
- NFR-R2: cooldown and quarantine policy SHALL be enforced at serve
  time, so policy changes apply to already-cached artifacts.
- NFR-O1: cooldown refusals, quarantine blocks, and direct-registry
  denials SHALL be distinguishable in the task report (a too-new
  dependency reads differently from an exfiltration attempt); depot
  cache/storage health SHALL be observable to the operator.
- NFR-P1: cache hits SHALL add negligible latency to builds; the
  first-download penalty is paid once per artifact, not per task.
- NFR-C1: depot storage SHALL be bounded by operator-configured cleanup
  policies (age/size-based cache eviction).

## Operator Experience Criteria

- UX1: enabling the depot is factory config plus a documented compose
  recipe; the allowlist and baked parameters switch together — no
  half-enabled state where configs point at the depot but the allowlist
  still permits upstream.
- UX2: a build failing on cooldown reads as "artifact X version Y is Z
  days old, policy requires N" — the operator immediately knows whether
  to wait, pin an older version, or grant an exception.
- UX3: the download journal is readable at review speed: coordinates
  grouped by ecosystem, anomalies on top.
- UX4: docs cover: the upstream-addition flow ("repo asks, operator
  allows"), cooldown tuning per ecosystem, the exception process, and
  the honest note that GHA-bound stages bypass the depot (passport
  fact from change E).

## Success Metrics

- M1: E2E: a full pipeline build resolves Maven/Gradle and npm
  dependencies through the depot while direct registry hosts are
  blocked; the same build with a build-script rewrite pointing at a
  direct registry fails with a recorded denial finding.
- M2: E2E: a package version younger than the cooldown is refused with
  the distinct cooldown error; a version older than the window is
  served.
- M3: E2E assertion: a private upstream requires credentials configured
  at the depot, and no registry credential is observable in any box
  env, filesystem, or provisioning phase.
- M4: E2E: a task's report contains its download journal, and an
  artifact absent from the project baseline is flagged.
- M5: E2E: a sandbox workload's Docker image pull resolves through the
  depot's registry proxy with direct registry egress blocked.
- M6: contract test: depot outage during a check surfaces as an
  infrastructure failure without burning a stage attempt and without
  any direct-upstream fallback.

## Open Questions

- Q1: depot product — Sonatype Nexus (all needed ecosystems in one;
  Artifactory OSS lacks npm) vs per-ecosystem lightweights
  (verdaccio/devpi/registry:2); re-verify licensing/edition state and
  maturity criteria at implementation start.
- Q2: cooldown defaults per ecosystem (npm's compromise cadence differs
  from Maven Central's) and whether lockfile-pinned exact versions get
  a different policy.
- Q3: per-task journal mechanics — derive from guard access logs (paths
  carry coordinates; no depot-side attribution needed) vs depot-side
  per-task tokens; and how the project baseline for anomaly flagging is
  established.
- Q4: OSV alignment mechanics — feed the CI gate's data into depot
  block rules vs a depot-native vulnerability feature; keep NG3's
  boundary either way.
- Q5: depot placement per executor — host service for local adapters
  (container/VM), cluster service or mirror for k8s (change D) — one
  deployment or per-site instances.

## Impact

- One new always-on operator service (the depot) with persistent cache
  storage; compose recipe shipped.
- Guard allowlist config and change-A registry parameters gain the
  coordinated depot switch; no engine, port, or adapter changes.
- Change C's pull-through mirror becomes redundant where the depot is
  deployed; change E's passport (direct registries) is unchanged.
- Depends on change A (guard, parameterized registry configs,
  SecretsProvider); strengthens changes B (two-address allowlist) and
  C/D (image-pull path); independent of their implementation order
  beyond A.
