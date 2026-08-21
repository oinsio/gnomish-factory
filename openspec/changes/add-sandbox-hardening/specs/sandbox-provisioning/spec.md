# sandbox-provisioning

## ADDED Requirements

### Requirement: setup.sh runs only inside a provisioning container
`.gnomish/setup.sh` SHALL be read from the factory's clone of the
default branch (pipeline law, never a gnome-writable copy) and executed
only inside a one-shot provisioning container created from the base
image — never on the host and never in an environment a gnome has
touched.
<!-- implements FR12 of add-sandbox-hardening -->

#### Scenario: Malicious setup script is contained
- **WHEN** a setup.sh attempts to read host files or call non-allowlisted hosts
- **THEN** it is confined by the same container and egress boundaries as a task box, and the factory host is unaffected

#### Scenario: Law clone is the only source
- **WHEN** a gnome branch modifies `.gnomish/setup.sh`
- **THEN** provisioning for that task still executes the default-branch version; the modification takes effect only after a human merge

### Requirement: Snapshot cache with fingerprint naming
Provisioning SHALL snapshot the post-setup state as an image named by
project plus a fingerprint of setup.sh content and base-image digest.
Environment materialization SHALL reuse a matching, unexpired snapshot;
it SHALL rebuild on fingerprint mismatch, TTL expiry
(`factory.sandbox.snapshot-max-age`, default 7d), or explicit operator
rebuild via `gnomish env rebuild` or the `--rebuild-env` run flag.
Provisioning failure SHALL be an infrastructure failure, never
a silent fallback to an image without the project toolchain.
<!-- implements FR13, NFR-P1 of add-sandbox-hardening -->

#### Scenario: Second task starts from the snapshot
- **WHEN** a task starts for a project whose fingerprint matches an unexpired snapshot
- **THEN** the environment is created from the snapshot without re-running setup.sh, in seconds

#### Scenario: Changed script rebuilds exactly once
- **WHEN** setup.sh content changes and a task starts
- **THEN** the new fingerprint misses the cache, provisioning runs once, and subsequent tasks reuse the new snapshot

### Requirement: Snapshots exist only in the provisioning flow
The snapshot operation SHALL exist only in the provisioning flow; the
task-environment port SHALL remain snapshot-free, so an environment a
gnome has touched can never be persisted as an image.
<!-- implements FR14 of add-sandbox-hardening -->

#### Scenario: Gnome-touched box cannot be snapshotted
- **WHEN** any caller holds a live task-environment handle after rounds have run
- **THEN** no code path can commit that environment's state into the snapshot cache

### Requirement: Snapshot lifecycle is labeled, cleaned, and crash-safe
Snapshots and provisioning containers SHALL carry factory-owned labels
identifying them as provisioning objects with their project identity.
Snapshot images are project-scoped, not task-scoped, and are explicitly
outside the task-keyed `sandbox-lifecycle` ownership scheme (its stated
non-goal); this capability owns their cleanup: after a successful
build, superseded snapshots of the project SHALL be removed, and the
provisioning flow SHALL reclaim orphaned provisioning containers and
partial images by label. Interrupted provisioning SHALL leave only
labeled garbage; concurrent provisioning of one fingerprint SHALL be
serialized so losers reuse the winner's image.
<!-- implements FR15, NFR-R2 of add-sandbox-hardening -->

#### Scenario: Crash mid-provisioning leaves nothing permanent
- **WHEN** the factory dies during setup.sh execution and another instance starts
- **THEN** the orphaned provisioning container and partial image are found by label and removed, and the next task re-provisions cleanly

### Requirement: Setup secrets never reach the gnome phase
Any secret available during provisioning SHALL be absent from the gnome
phase: it SHALL NOT appear in task-environment exec env, in the
materialized filesystem, or in snapshot image layers (the working copy
and secret material are removed before commit).
<!-- implements FR16 of add-sandbox-hardening -->

#### Scenario: Provisioning credential is unobservable later
- **WHEN** provisioning ran with a secret in its environment and a task environment is later created from the snapshot
- **THEN** the secret value is not observable in the box env, filesystem, or image history
