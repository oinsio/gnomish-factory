# pipeline-config (delta)

## ADDED Requirements

### Requirement: Stage sandbox declarations in Mechanism
The stage model SHALL accept optional sandbox declarations in the `Mechanism` section: needs (e.g. docker-inside, resource asks), `requires-fresh`, and per-command-check `verify-in: same-box | fresh-box`. Declarations SHALL be typed into the immutable `PipelineDefinition`; loading remains read-only and executes nothing.
<!-- implements FR12, FR13 of add-sandbox-core -->

#### Scenario: Declarations load into the typed model
- **WHEN** a stage manifest declares `requires-fresh` and a command check with `verify-in: fresh-box`
- **THEN** the `PipelineDefinition` exposes both, typed, and validation passes

### Requirement: Repo declarations can only tighten
Validation SHALL reject any repo-side declaration that weakens isolation: requesting host execution, naming a concrete adapter binding, or relaxing freshness/limits. Adapter binding and any weakening SHALL exist only in factory installation config. Violations SHALL surface as located `ConfigError`s.
<!-- implements FR14 of add-sandbox-core -->

#### Scenario: Host request from the repo is rejected
- **WHEN** a stage manifest asks for host execution or a named adapter
- **THEN** loading reports a located `ConfigError` and no pipeline runs

#### Scenario: Tightening is accepted
- **WHEN** a stage manifest declares `requires-fresh` on top of the operator's container binding
- **THEN** validation passes and the stricter setting takes effect

### Requirement: External check declarations carry pin paths
The external check declaration in a stage manifest SHALL accept an optional list of pin paths — repo paths whose content defines the check (workflow files, analyzer configs, local actions). The list SHALL load into the typed model as law; the pin-check guard unions it with adapter-contributed paths. Declaring none is valid — the adapter's own contribution still applies.

Pin paths are repo-relative data, not file references: the loader SHALL NOT read them, and the "No execution and no path traversal" rule confining file references to `.gnomish/` SHALL NOT apply to them — pointing outside `.gnomish/` (e.g. `.github/workflows/ci.yml`) is their normal use. Validation SHALL still reject a pin path that is absolute or not in normalized relative form (containing `.` or `..` segments) as a located `ConfigError` — such a path can never match a repo object and would only pass the pin vacuously.
<!-- implements FR16 of add-sandbox-core -->

#### Scenario: Declared pin paths load into the typed model
- **WHEN** a stage manifest declares an external check with two pin paths
- **THEN** the `PipelineDefinition` exposes them, typed, and validation passes

#### Scenario: Pin path outside `.gnomish/` is accepted
- **WHEN** a stage manifest declares a pin path `.github/workflows/ci.yml`
- **THEN** validation passes and the loader does not read the referenced file

#### Scenario: Absolute or root-escaping pin path is rejected
- **WHEN** a stage manifest declares a pin path that is absolute or contains `..` segments
- **THEN** validation reports a located `ConfigError` identifying the check

### Requirement: Pipeline law binds per invocation
Pipeline law — `.gnomish/` stage manifests, stage instructions, and judge acceptance criteria — SHALL be bound at invocation start and frozen for the invocation's lifetime, including the in-process outcome loop. The law source SHALL be the factory-owned clone of the base branch in tracker-driven and git modes, and the workspace snapshot at startup in the git-less in-place mode. Control files and judge acceptance criteria SHALL be read from the law source, never from the gnome-writable working copy at use time. Copies of law files in the gnome's working copy are project content: editable, but never law for the current task. A contract test SHALL enforce the source in git modes.
<!-- implements FR19, NFR-S2 of add-sandbox-core -->

#### Scenario: Gnome edits to the law have no effect
- **WHEN** the gnome branch modifies `.gnomish/` manifests, stage instructions, or judge acceptance criteria
- **THEN** the running task continues under the law bound at invocation start, and the edits reach production law only via a human merge — for later tasks

#### Scenario: Criteria are not read lazily from the working copy
- **WHEN** a judge vote runs after the gnome edited the acceptance-criteria file in its working copy
- **THEN** the vote uses the criteria from the law source, and the working-copy edit plays no part in it

#### Scenario: Resume picks up human-fixed criteria
- **WHEN** a human fixes acceptance criteria on the base branch and returns an escalated task to work
- **THEN** the resuming invocation binds the corrected law from the base branch, and the gnome branch content plays no part in it
