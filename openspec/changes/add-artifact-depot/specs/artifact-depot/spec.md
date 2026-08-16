# artifact-depot

## ADDED Requirements

### Requirement: Depot proxies every dependency ecosystem the toolchain uses
A factory-owned artifact depot SHALL proxy and cache, at minimum:
Maven artifacts, Gradle plugins, npm packages, PyPI packages, Docker
images, OS packages (apt), and raw/generic downloads. Boxes SHALL
resolve all dependencies through the depot; upstream traffic is
initiated by the depot only. <!-- implements FR1 of add-artifact-depot -->

#### Scenario: The attacker never speaks upstream
- **WHEN** a build inside a box resolves a dependency
- **THEN** the box's request goes to the depot, and any upstream fetch is the depot's own request — its content and logs outside the box's reach

### Requirement: Version cooldown at serve time
The depot SHALL serve a package version only once it is older than the
operator-configured cooldown age (configurable per ecosystem),
enforced at serve time so policy changes apply to cached artifacts. A
cooldown refusal SHALL be a distinct error naming the artifact, its
age, and the required age. Operator-approved exceptions SHALL be
possible per artifact version.
<!-- implements FR3 of add-artifact-depot -->

#### Scenario: A freshly-poisoned release cannot reach a build
- **WHEN** a build requests a version published yesterday while the cooldown window is fourteen days
- **THEN** the depot refuses with the distinct cooldown error, and the build either uses an older version or the task escalates with a message the operator can act on

#### Scenario: Tightening policy covers the cache
- **WHEN** the operator lengthens the cooldown window after a version is already cached
- **THEN** subsequent requests for versions inside the new window are refused despite the cache

### Requirement: Quarantine and vulnerability blocks
The depot SHALL refuse artifacts on the operator-managed quarantine
list and versions matching configured vulnerability-block data aligned
with the CI OSV gate; refusals SHALL be distinguishable from cooldown
refusals; per-version operator exceptions SHALL be possible.
<!-- implements FR4 of add-artifact-depot -->

#### Scenario: A known-bad version is stopped at the door
- **WHEN** a build requests a version present in the configured block data
- **THEN** the depot refuses with a block-specific error and the event is recorded

### Requirement: Per-task download journal with anomaly signal
The factory SHALL attach to each task's report a journal of the
artifact coordinates the task fetched; coordinates outside the
project's established baseline SHALL be flagged as anomalies. The
journal is reporting through the findings funnel, not enforcement.
<!-- implements FR5 of add-artifact-depot -->

#### Scenario: An unusual dependency stands out at review
- **WHEN** a task fetches a package this project has never used
- **THEN** the task report's journal flags it above the routine coordinates

### Requirement: Upstreams are operator-owned; credentials never leave the depot
Adding or changing a depot upstream SHALL be an operator action; a
repo MAY declare the need but SHALL NOT introduce an upstream or an
allowlist entry. Credentials for private upstreams SHALL exist only in
depot configuration (via the `SecretsProvider` port) and SHALL never
enter any box, provisioning phase, or baked config.
<!-- implements FR6, FR7 of add-artifact-depot -->

#### Scenario: A private registry without a secret in the box
- **WHEN** a project depends on a private registry configured as a depot upstream
- **THEN** builds resolve its packages through the depot while no registry credential is observable anywhere in the box or provisioning phase

#### Scenario: The repo cannot grant itself an upstream
- **WHEN** a gnome branch adds an upstream declaration or registry config pointing elsewhere
- **THEN** no upstream changes and requests outside the depot die at the guard as recorded denials

### Requirement: Fail-closed availability, disposable cache
Depot unavailability SHALL be an infrastructure failure (check-level
retries, no stage attempt burned) and SHALL never trigger fallback to
direct upstream access. The cache SHALL survive restarts; its loss
costs re-downloads, never correctness; storage SHALL be bounded by
operator-configured cleanup policies.
<!-- implements FR10, NFR-R1, NFR-C1 of add-artifact-depot -->

#### Scenario: An outage does not open the fence
- **WHEN** the depot is down while a check needs a dependency
- **THEN** the check surfaces an infrastructure failure, no stage attempt is burned, and no request goes upstream directly

### Requirement: Admin plane unreachable from boxes
The depot's admin interface SHALL be unreachable from boxes; the
box-reachable endpoint SHALL serve artifact resolution only; depot
logs and configuration are operator-side only.
<!-- implements FR11, NFR-S2 of add-artifact-depot -->

#### Scenario: The newest rule-holder is outside the cage
- **WHEN** a process inside a box probes the depot endpoint for admin APIs
- **THEN** only the resolution surface responds; administration requires the operator-side plane with credentials from the secrets provider
