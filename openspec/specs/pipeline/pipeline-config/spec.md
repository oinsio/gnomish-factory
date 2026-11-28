# pipeline-config

## Purpose

Load a target project's `.gnomish/` directory — `config.yaml`, `pipeline.yaml`, and per-stage `stages/<name>/stage.yaml` — into an immutable, typed `PipelineDefinition`. Loading is deterministic, read-only, and fully validated (structural, semantic, and local-sanity) before any pipeline runs. Validation problems are aggregated and returned as located `ConfigError` data rather than thrown, so any factory instance can reproduce the same pipeline from the same configuration. The loader never executes configured commands, model calls, or external checks, and never reads outside the config root.

## Requirements

### Requirement: Load .gnomish/ into a typed pipeline definition
The loader SHALL read a `.gnomish/` directory — `config.yaml`, `pipeline.yaml`, and `stages/<name>/stage.yaml` for each stage — and build an immutable, typed `PipelineDefinition`. Loading SHALL be deterministic and read-only.
**Implements**: FR1, NFR-R1 of load-pipeline-config

#### Scenario: Valid configuration loads
- **WHEN** the loader is given a `.gnomish/` directory whose files are structurally and semantically valid
- **THEN** it returns a `PipelineDefinition` whose stages, order, verify checks, and resolved autonomy limits match the on-disk configuration exactly
- **AND** the returned model is immutable

#### Scenario: Loading never writes to disk
- **WHEN** the loader processes any `.gnomish/` directory, valid or invalid
- **THEN** no file under the directory is created, modified, or deleted
- **AND** loading the same directory twice yields an equal result

### Requirement: Stage model mirrors the stage contract and all verify-check types
Each `StageDefinition` SHALL represent the stage-contract sections (purpose, input, output, control, mechanism/executor, verify, failure/escalation limits, advancement) and SHALL model the verify checks as two typed variants: an executor check, which every `builtin`, `command`, `external` or `executor:` check maps to and which carries its executor name, its token, its `settings` and the engine-common fields, and the `judge` check.
**Implements**: FR2 of load-pipeline-config; FR7 of add-plugin-architecture; FR1 of migrate-verify-to-executor-contract

#### Scenario: All four check types are modeled distinctly
- **WHEN** a stage's `verify` list contains a `builtin`, a `command`, an `external`, and a `judge` check
- **THEN** the model exposes the first three as executor checks naming `files-exist`, `shell` and the `external` check's executor, each keeping the token it was declared with and its type-specific settings (e.g. `command` carries the executable), and the `judge` as its own variant carrying the acceptance-criteria path and vote count
- **AND** the ordered position of each check within the stage is preserved

#### Scenario: External check carries provider and opaque params
- **WHEN** an `external` check declares a `provider` and provider-specific `params`
- **THEN** the model carries the executor name mapped from `provider` and the `params` as the executor's settings alongside the engine-common `interval`, `timeout`, `timeoutClass`, and `pinPaths`
- **AND** no new verify-check sealed variant is added

#### Scenario: Unknown check type is rejected
- **WHEN** a `verify` entry declares a check `type` that is not one of the four known types
- **THEN** validation reports a located error naming the unknown type

### Requirement: Stage order is declared, not derived
`pipeline.yaml` SHALL be the source of truth for stage order as an explicit, non-empty, linear sequence of unique stage names. The artifact dependency graph SHALL be validated for consistency against that order and SHALL NOT be used to derive it.
**Implements**: FR3 of load-pipeline-config

#### Scenario: Order comes from pipeline.yaml
- **WHEN** `pipeline.yaml` lists stages in a given order
- **THEN** the `PipelineDefinition` presents stages in exactly that order

#### Scenario: Empty or duplicate order is rejected
- **WHEN** `pipeline.yaml` has an empty stage list or repeats a stage name
- **THEN** validation reports a located error

### Requirement: Artifact references by identifier
Each stage output SHALL declare a stable `id`, unique across the whole pipeline. An internal input SHALL reference the `id` of an output produced by a stage that appears **earlier** in the pipeline order. A `source` input SHALL declare that it has no producing stage.
**Implements**: FR4 of load-pipeline-config

#### Scenario: Internal input resolves to an earlier output
- **WHEN** a stage input references an output `id` produced by an earlier stage
- **THEN** validation accepts the reference and the model links input to producing stage

#### Scenario: Source input needs no producer
- **WHEN** a stage input is declared as `source`
- **THEN** validation accepts it without requiring a producing stage

#### Scenario: Dangling or forward reference is rejected
- **WHEN** an internal input references an `id` that no stage produces, or that is produced only by a later stage
- **THEN** validation reports a located error identifying the input and the missing/late producer

#### Scenario: Duplicate output id is rejected
- **WHEN** two stages (or one stage twice) declare outputs with the same `id`
- **THEN** validation reports a located error naming the duplicated `id` and both declaring stages

### Requirement: Structural validation
The loader SHALL reject invalid YAML schema, missing required fields, unknown enum values (executor type, verify-check type, advancement mode), and type mismatches.
**Implements**: FR5 of load-pipeline-config

#### Scenario: Missing required field is reported
- **WHEN** a `stage.yaml` omits a required field
- **THEN** validation reports a located error naming the file and the missing field

#### Scenario: Unknown enum value is reported
- **WHEN** a stage declares an executor type or advancement mode outside the allowed set
- **THEN** validation reports a located error naming the offending value

### Requirement: Semantic validation
The loader SHALL enforce cross-file rules: unique stage names; every `pipeline.yaml` stage has a matching `stages/<name>/stage.yaml`; every stage directory is referenced by `pipeline.yaml` (no dangling definitions); every internal input is satisfied by an earlier stage's output; and every referenced file (`instructions.md`, `judge` acceptance-criteria files) exists.
**Implements**: FR6 of load-pipeline-config

#### Scenario: Pipeline stage without a manifest is rejected
- **WHEN** `pipeline.yaml` names a stage that has no `stages/<name>/stage.yaml`
- **THEN** validation reports a located error

#### Scenario: Dangling stage directory is rejected
- **WHEN** a `stages/<name>/` directory exists but `pipeline.yaml` does not reference it
- **THEN** validation reports a located error naming the unreferenced stage

#### Scenario: Missing referenced file is rejected
- **WHEN** a stage's `instructions.md` or a `judge` check's acceptance-criteria file does not exist
- **THEN** validation reports a located error naming the missing file

### Requirement: Autonomy limit resolution
The loader SHALL resolve the stage attempt limit from the `config.yaml` default, with a per-stage override taking precedence. The resolved limit SHALL be an integer ≥ 1. Token budgets are out of scope for this capability version; monetary budgets are out of scope for the project.
**Implements**: FR7 of load-pipeline-config; FR16 of add-agent-executor

#### Scenario: Stage override wins over default
- **WHEN** `config.yaml` sets a default attempt limit and a stage overrides it
- **THEN** the resolved `StageDefinition` carries the stage's overriding value

#### Scenario: Default applies when no override
- **WHEN** a stage declares no override for a limit
- **THEN** the resolved `StageDefinition` carries the `config.yaml` default

#### Scenario: Non-positive attempt limit is rejected
- **WHEN** the resolved attempt limit (default or override) is less than 1
- **THEN** validation reports a located error

### Requirement: Aggregated, located validation results
The loader SHALL aggregate all validation problems and return a result that is either a valid `PipelineDefinition` or a non-empty list of `ConfigError`s, each naming its file and location. Validation failure SHALL NOT be signalled by exceptions; exceptions are reserved for I/O faults such as an unreadable file.
**Implements**: FR8, NFR-O1 of load-pipeline-config

#### Scenario: All problems reported in one pass
- **WHEN** a `.gnomish/` directory contains several independent validation problems
- **THEN** the returned error list contains all of them, not only the first
- **AND** each error names its file and the field or stage at fault

#### Scenario: Validation failure is data, not an exception
- **WHEN** the configuration is invalid
- **THEN** the loader returns an error result rather than throwing

### Requirement: Schema version recognition
A `schemaVersion` SHALL be declared in `config.yaml` — one version for the whole `.gnomish/` tree. A missing, unknown, or unsupported version SHALL be a validation error.
**Implements**: FR9 of load-pipeline-config

#### Scenario: Unsupported schema version is rejected
- **WHEN** `config.yaml` declares a `schemaVersion` the loader does not support
- **THEN** validation reports a located error naming the version

#### Scenario: Missing schema version is rejected
- **WHEN** `config.yaml` declares no `schemaVersion`
- **THEN** validation reports a located error naming `config.yaml`

### Requirement: Local sanity validation of mechanism and check configs
The loader SHALL apply catalog-free sanity rules, needing no live target, to the executor types `api`, `agent-cli`, `program` and `command`. The executor `model` SHALL be present and non-blank for the agent executor types (`api`, `agent-cli`), and `settings` SHALL be a well-formed mapping whose keys and values only the executor's schema checks. The loader SHALL NOT validate target liveness — whether a CI-check name exists, whether a `model` is real, or whether `judge` criteria are gradeable.
**Implements**: FR11 of load-pipeline-config; FR6 of add-plugin-architecture; FR13 of add-plugin-architecture; UX1 of add-plugin-architecture; FR9, FR10 of define-executor-contract; FR1, FR2, UX2 of add-command-executor; FR1, FR13 of migrate-verify-to-executor-contract; FR4, FR13 of extract-agent-round-runner
**Notes**: The model is pinned in the stage manifest so any instance reproduces the stage identically, never left to an executor default.
**Notes**: Executor existence is in-process knowledge (the executor registry: law declarations plus core's built-ins), not target liveness.
**Notes**: The scenario "Judge vote count must be positive and odd" keeps its title from before votes were removed; no count is accepted now.

#### Scenario: Missing model is rejected
- **WHEN** an `api` or `agent-cli` stage's `model` is absent or blank
- **THEN** validation reports a located error
- **AND** the sanity rule accepts `settings` present as a mapping without inspecting its keys or values

#### Scenario: Agent settings are checked against the executor's schema
- **WHEN** an `agent-cli` executor block, a stage's or a `judge` check's, declares a `settings` key
  other than `allowedTools`, `disallowedTools`, `maxTurns` or `roundTimeout`,
  or a malformed value for one of them
- **THEN** the load fails with a located error naming the stage or check and
  the key

#### Scenario: Program stage names a declared executor
- **WHEN** a stage declares `executor: {type: program, name: acme}` and `executors.acme` exists
- **THEN** validation passes and the typed model carries the executor reference

#### Scenario: Program stage without a name is rejected
- **WHEN** a `program` stage omits `executor.name`
- **THEN** validation reports a located error naming the stage and the missing field

#### Scenario: Program stage may omit the model
- **WHEN** a `program` stage declares no `model`
- **THEN** validation passes; a present `model` is carried to the executor as a setting

#### Scenario: Model on a command stage is rejected
- **WHEN** a `command` stage declares an `executor.model`
- **THEN** validation reports a located error naming the stage and the forbidden field

#### Scenario: Command stage requires its command
- **WHEN** a `command` stage omits `executor.command` or declares it blank
- **THEN** validation reports a located error naming the stage and the missing field

#### Scenario: Command field on another executor type is rejected
- **WHEN** an `api`, `agent-cli` or `program` stage declares `executor.command`
- **THEN** validation reports a located error naming the stage and the forbidden field

#### Scenario: Well-formed command stage is accepted
- **WHEN** a stage declares `executor: {type: command, command: "./gradlew generateSources"}`
  and no `model`
- **THEN** validation passes and the typed model carries the command

#### Scenario: External check timing is sane
- **WHEN** an `external` check has a non-positive `interval` or `timeout`, or `interval > timeout`
- **THEN** validation reports a located error identifying the check
- **AND** a check with positive `interval`, positive `timeout`, and `interval ≤ timeout` is accepted

#### Scenario: Unknown provider is a located load error
- **WHEN** an `external` check declares a `provider` that names no executor of
  the registry (no built-in and no law declaration)
- **THEN** validation reports a located error naming the unknown executor and
  the registry's names, before any stage runs

#### Scenario: Defaulted github provider absent from the registry is a located error
- **WHEN** an `external` check omits `provider` and the law declares an executor
  that shadows or misdeclares `github-actions`
- **THEN** validation reports a located error naming `github-actions` as a
  built-in's name; the built-in is core's own and cannot be absent by removing
  the github vendor bundle, whose removal disables the tracker only

#### Scenario: External check provider defaults to github and validates its params
- **WHEN** an `external` check omits `provider` but declares provider-specific `params`
- **THEN** the loader records executor `github-actions` and validates the `params` against that built-in's describe-declared settings schema
- **AND** a param problem is reported as a located `ConfigError` identifying the check

#### Scenario: Judge vote count must be positive and odd
- **WHEN** a `judge` check declares `votes`, whatever its value
- **THEN** validation reports a located error identifying the check

#### Scenario: Target liveness is not validated
- **WHEN** an `external` check names a CI check, or a `judge`/executor declares a model, that does not exist in any live system
- **THEN** validation does not attempt to confirm its existence and does not fail on that ground

#### Scenario: Settings that are not a mapping are rejected
- **WHEN** a stage's `settings` is present but is not a mapping
- **THEN** validation reports a located error

#### Scenario: Settings key with no value is rejected
- **WHEN** a stage's executor `settings`, a `judge` check's executor `settings`, a check's `params` or an
  executor declaration's `settings` declares a key with no value
- **THEN** the load fails with a located error naming the file and `<where>.<key>`, before any
  stage runs

#### Scenario: External check without an identifier is rejected
- **WHEN** an `external` check's check identifier is absent or blank
- **THEN** validation reports a located error identifying the check

### Requirement: External check declarations carry a timeout class
The external check declaration SHALL accept an optional timeout class —
`quality` or `infrastructure` — defaulting to `quality` when absent. The
value SHALL load into the typed model; a value outside the two known
classes SHALL be a located validation error identifying the check.
**Implements**: FR9 of add-external-check-github-actions

#### Scenario: Absent timeout class defaults to quality
- **WHEN** an `external` check declares no timeout class
- **THEN** the typed model carries the `quality` class and engine behavior
  is unchanged

#### Scenario: Unknown timeout class is rejected
- **WHEN** an `external` check declares a timeout class other than
  `quality` or `infrastructure`
- **THEN** validation reports a located error identifying the check

### Requirement: No execution and no path traversal
The loader SHALL NOT execute any command, model call, or external check defined in the configuration, and SHALL reject file references that resolve outside the `.gnomish/` directory root.
**Implements**: NFR-S1, NFR-S2, NFR-C1 of load-pipeline-config

#### Scenario: No configured action is executed
- **WHEN** the configuration defines `command`, `external`, and `judge` checks
- **THEN** loading parses and validates them as data without running any command, network request, or model call

#### Scenario: Path escaping the config root is rejected
- **WHEN** a file reference resolves outside the `.gnomish/` directory (e.g. via `../` or an absolute path)
- **THEN** validation reports a located error rather than reading the outside file
- **AND** that reference is reported as escaping the root only, never also as "does not exist"

#### Scenario: Symlink escaping the config root is rejected
- **WHEN** a referenced file is a symlink inside `.gnomish/` whose real target resolves outside the root
- **THEN** validation reports a located error rather than reading the outside file

### Requirement: Domain purity guarded by ArchUnit
The `domain` package SHALL remain free of filesystem and Jackson dependencies and SHALL NOT depend on the `adapter` package; an ArchUnit rule wired into `./gradlew check` SHALL fail the build on violation.
**Implements**: FR10 of load-pipeline-config

#### Scenario: Domain dependency on I/O fails the build
- **WHEN** a `domain` class gains a dependency on `java.nio.file..`, `com.fasterxml.jackson..`, or the `adapter` package
- **THEN** the ArchUnit test fails `./gradlew check` naming the violating class

### Requirement: Optional tracker section with core keys
`.gnomish/config.yaml` SHALL support an optional `tracker` section whose core
keys — the only ones the loader itself knows — are `type` (adapter discriminator)
and `abort-threshold` (positive integer, default 3, the core abort-fuse policy
shared by all instances). An absent `tracker` section SHALL be valid: tracker
subcommands are unavailable and all previously specified loading behavior is
unchanged.
**Implements**: FR17 of add-tracker-port

#### Scenario: No tracker section
- **WHEN** a `.gnomish/` without a `tracker` section is loaded
- **THEN** loading succeeds exactly as before and the definition reports no
  tracker configuration

#### Scenario: Defaulted threshold
- **WHEN** a `tracker` section declares `type` but no `abort-threshold`
- **THEN** the definition carries threshold 3

#### Scenario: Non-positive threshold
- **WHEN** a `tracker` section declares `abort-threshold: 0` (or a negative value)
- **THEN** loading fails with a located error naming `tracker.abort-threshold` —
  the threshold must be a positive integer

### Requirement: Adapter-owned subsection validated at the seam
The `tracker` section SHALL contain a typed subsection named after `type` (e.g.
`github:`), whose schema is declared and validated by the adapter — the loader
delegates subsection validation and never interprets adapter keys. Seam
violations SHALL be located load errors under the existing aggregation contract:
`type` naming an unknown adapter; `type` present without its matching subsection;
a subsection present that does not match `type` (never silently ignored).
**Implements**: FR17 of add-tracker-port

#### Scenario: Missing subsection
- **WHEN** the section declares `type: github` with no `github:` subsection
- **THEN** loading fails with a located error naming the missing subsection

#### Scenario: Mismatched subsection
- **WHEN** the section declares `type: github` but contains a `jira:` subsection
- **THEN** loading fails with a located error — the stray subsection is not
  silently ignored

#### Scenario: Adapter errors aggregate with core errors
- **WHEN** the `github` subsection is invalid (bad color hex) and the pipeline
  also has an unrelated core config error
- **THEN** one aggregated result reports both located errors

### Requirement: Heartbeat protocol keys in the tracker section
The `tracker` section of `.gnomish/config.yaml` SHALL gain two core keys —
protocol constants shared by all instances, beside `abort-threshold`:
`heartbeat-interval` (duration, default 5 minutes) and
`heartbeat-ttl-multiplier` (integer ≥ 3, default 3; TTL = multiplier ×
interval, so an inconsistent beat/TTL pair is inexpressible). Validation
failures SHALL be located load errors under the existing aggregation
contract.
**Implements**: FR3 of add-claim-heartbeat

#### Scenario: Defaults apply
- **WHEN** a `tracker` section declares `type` but neither heartbeat key
- **THEN** the definition carries a 5-minute interval and multiplier 3

#### Scenario: Multiplier below the floor is a load error
- **WHEN** the section declares `heartbeat-ttl-multiplier: 1`
- **THEN** loading fails with a located error naming the minimum of 3

#### Scenario: Heartbeat errors aggregate
- **WHEN** the interval is malformed and the pipeline also has an unrelated
  core config error
- **THEN** one aggregated result reports both located errors

### Requirement: WIP-limit key in the tracker section
The `tracker` section of `.gnomish/config.yaml` SHALL gain the core key
`wip-limit` (integer ≥ 1, default 10) — a protocol constant shared by all
instances, beside `abort-threshold` and the heartbeat keys, read only from
the factory's own clone (a gnome must not be able to raise the project's WIP
limit). Validation failures SHALL be located load errors under the existing
aggregation contract.
**Implements**: FR6 of add-factory-serve; NFR-S3 of add-factory-serve

#### Scenario: Default applies

- **WHEN** a `tracker` section declares `type` but no `wip-limit`
- **THEN** the definition carries WIP limit 10

#### Scenario: Zero limit is a load error

- **WHEN** the section declares `wip-limit: 0`
- **THEN** loading fails with a located error naming the minimum of 1

### Requirement: Stage sandbox declarations in Mechanism
The stage model SHALL accept optional sandbox declarations in the `Mechanism` section: needs (e.g. docker-inside, resource asks), `requires-fresh`, and per-command-check `verify-in: same-box | fresh-box`. Declarations SHALL be typed into the immutable `PipelineDefinition`; loading remains read-only and executes nothing.
**Implements**: FR12, FR13 of add-sandbox-core

#### Scenario: Declarations load into the typed model
- **WHEN** a stage manifest declares `requires-fresh` and a command check with `verify-in: fresh-box`
- **THEN** the `PipelineDefinition` exposes both, typed, and validation passes

### Requirement: Repo declarations can only tighten
Validation SHALL reject any repo-side declaration that weakens isolation: requesting host execution, naming a concrete adapter binding, or relaxing freshness/limits. Adapter binding and any weakening SHALL exist only in factory installation config. Violations SHALL surface as located `ConfigError`s.
**Implements**: FR14 of add-sandbox-core

#### Scenario: Host request from the repo is rejected
- **WHEN** a stage manifest asks for host execution or a named adapter
- **THEN** loading reports a located `ConfigError` and no pipeline runs

#### Scenario: Tightening is accepted
- **WHEN** a stage manifest declares `requires-fresh` on top of the operator's container binding
- **THEN** validation passes and the stricter setting takes effect

### Requirement: External check declarations carry pin paths
The external check declaration in a stage manifest SHALL accept an optional list of pin paths — repo paths whose content defines the check (workflow files, analyzer configs, local actions). The list SHALL load into the typed model as law; the pin-check guard unions it with adapter-contributed paths. Declaring none is valid — the adapter's own contribution still applies.
**Implements**: FR16 of add-sandbox-core
**Notes**: Pin paths are repo-relative data, not file references, so the `.gnomish/` confinement of "No execution and no path traversal" does not cover them; an absolute or non-normalized path could never match a repo object and would pass the pin vacuously.

#### Scenario: Declared pin paths load into the typed model
- **WHEN** a stage manifest declares an external check with two pin paths
- **THEN** the `PipelineDefinition` exposes them, typed, and validation passes

#### Scenario: Pin path outside `.gnomish/` is accepted
- **WHEN** a stage manifest declares a pin path `.github/workflows/ci.yml`
- **THEN** validation passes and the loader does not read the referenced file

#### Scenario: Absolute or root-escaping pin path is rejected
- **WHEN** a stage manifest declares a pin path that is absolute or contains `..` segments
- **THEN** validation reports a located `ConfigError` identifying the check

#### Scenario: A pin path with a dot segment is rejected
- **WHEN** a stage manifest declares a pin path containing a `.` segment, such as `./ci.yml`
- **THEN** validation reports a located `ConfigError` identifying the check

### Requirement: Pipeline law binds per invocation
Pipeline law — `.gnomish/` stage manifests, stage instructions, and judge
acceptance criteria — SHALL be bound at invocation start and frozen for the
invocation's lifetime, including the in-process outcome loop.
**Implements**: FR19, NFR-S2 of add-sandbox-core; FR2, FR11, FR12, NFR-S1, M5 of add-base-ref-resolution

#### Scenario: Gnome edits to the law have no effect
- **WHEN** the gnome branch modifies `.gnomish/` manifests, stage instructions, or judge acceptance criteria
- **THEN** the running task continues under the law bound at invocation start, and the edits reach production law only via a human merge — for later tasks

#### Scenario: Criteria are not read lazily from the working copy
- **WHEN** a judge vote runs after the gnome edited the acceptance-criteria file in its working copy
- **THEN** the vote uses the criteria from the law source, and the working-copy edit plays no part in it

#### Scenario: Resume picks up human-fixed criteria
- **WHEN** a human fixes acceptance criteria on the base branch and returns an escalated task to work
- **THEN** the resuming invocation binds the corrected law from the tip of the pinned ref name, and the gnome branch content plays no part in it

#### Scenario: A reference that validates at load reads at run
- **WHEN** a stage manifest under `.gnomish/stages/work/` declares
  `instructions: stages/work/instructions.md` and the loader accepted it
- **THEN** the frozen law carries that file's content from the law root of
  the same commit or working tree, in the working-tree and the git-objects
  realization alike, and no copy at the repository root is consulted

#### Scenario: Symlinked law file is refused in every medium
- **WHEN** a law file under `.gnomish/` is a symlink entry, or an
  intermediate directory on a law file's path is a symlink entry, in a
  working tree or in a git tree
- **THEN** both realizations report it unreadable without following it,
  and the contract spec asserts the same verdict from both over one tree

#### Scenario: Pin guard never re-resolves
- **WHEN** the factory clone's `HEAD` moves after the law was bound in a
  manual `run` without `--base`
- **THEN** the external-check pin guard still compares against the commit
  id peeled at binding, not against the moved `HEAD`

#### Scenario: Base differs from the clone's checked-out ref
- **WHEN** the factory clone has `main` checked out and a task resolves to
  `release/1.18`, whose stage instructions differ from `main`'s
- **THEN** the frozen law and the pin guard use `release/1.18`'s content at
  the pinned SHA, and nothing from the checked-out `main` tree reaches the
  task

#### Scenario: Uncommitted clone edits are not law in autonomous modes
- **WHEN** an operator edits a stage instruction in the factory clone's
  working tree without committing and serve claims a task
- **THEN** the task binds the committed instruction at its law commit, and
  the uncommitted edit plays no part

#### Scenario: Manual run without a base keeps working-tree law
- **WHEN** `gnomish run` starts without `--base` in a clone with uncommitted
  `.gnomish/` edits
- **THEN** the edited files are the law, exactly as before this change

#### Scenario: Manual run with a base reads that ref's law offline
- **WHEN** `gnomish run --base v1.2.3` starts in a clone with no reachable
  remote and uncommitted `.gnomish/` edits
- **THEN** no fetch runs, the law is read from git objects at `v1.2.3`, and
  the uncommitted edits play no part

#### Scenario: Resume after the pinned ref disappeared
- **WHEN** a task pinned to `release/1.18` is resumed after that branch was
  deleted from the remote
- **THEN** the task parks with a report naming the pinned ref and SHA, and
  no law is bound from the pinned SHA silently

#### Scenario: The chosen base's own `task-branch.base` section plays no part in choosing it
- **WHEN** a task resolves to base `release/1.18`, whose `.gnomish/config.yaml`
  carries a `task-branch.base` section differing from the default branch's
- **THEN** resolution used the default-branch `task-branch.base` section, and the rest of
  the law — including the remainder of `release/1.18`'s `config.yaml` —
  binds from `release/1.18`'s law commit

#### Scenario: Law has exactly two sources
- **WHEN** law is bound
- **THEN** it is read through the one law-source abstraction: from git objects at the law commit in every path that resolved a ref (tracker-driven modes, manual `run` with `--base`), and from the working tree only in the git-less in-place mode and in manual `run` without `--base`
- **AND** where a ref was resolved, the factory clone's working tree, index, and `HEAD` play no part in the law

#### Scenario: Pin paths are addressed from the repository root
- **WHEN** the external-check pin guard checks a declared pin path such as `.github/workflows/ci.yml`
- **THEN** it addresses the path from the repository root it was handed as a separate value, never from the law root

#### Scenario: An absolute law reference is refused in every medium
- **WHEN** a law reference is an absolute path, in a working tree or in a git tree
- **THEN** both realizations refuse it as an unreadable law file without touching anything outside the law root

### Requirement: Optional task-branch base section with allowed patterns
`.gnomish/config.yaml` SHALL support an optional `task-branch:` section holding a `base`
subsection: `type` (a discriminator; `patterns` is the only supported value in
this version, and it is the default when absent), `default` (optional ref
name), and `allowed` (a list of entries, each `pattern` plus optional `role`
of `development` | `release`, defaulting to `development`). Patterns SHALL
compile at load.
**Implements**: FR1 of add-base-ref-resolution; UX1 of add-base-ref-resolution
**Notes**: The root key `task-branch` is the glossary term for the branch it configures; the section is reserved for that branch's own settings (a name-prefix override is a candidate for a later change).
**Notes**: How a task names its base is the tracker adapter's own configuration (`tracker.<type>.designators`, owned and validated by the adapter per tracker-port); the loader parses and validates, and resolution semantics belong to base-ref-resolution.

#### Scenario: Settled shape loads
- **WHEN** `task-branch.base` declares `type: patterns`, `default: main`, and
  `allowed` entries `main` and `release/*` (role `release`)
- **THEN** loading succeeds and the typed definition exposes the compiled
  allowed bases, the default, and the roles

#### Scenario: No task-branch section
- **WHEN** a `.gnomish/` without a `task-branch:` section is loaded
- **THEN** loading succeeds exactly as before and the definition reports no
  allowed bases and no default

#### Scenario: Selection rule without allowed bases is a load error
- **WHEN** the tracker adapter reports that it extracts designator kind
  `base` (for GitHub, a `tracker.github.designators.base` rule) and the
  `task-branch.base` section is absent or declares no `allowed` entry
- **THEN** loading fails with a located error naming the rule's location and
  `task-branch.base.allowed`, stating that the rule can only ever reject a
  selection — allow a base or remove the rule

#### Scenario: Default outside the allowed bases is a load error
- **WHEN** the section declares `default: develop` and `allowed` containing
  only `release/*`
- **THEN** loading fails with a located error naming
  `task-branch.base.default` and the allowed patterns it failed to match

#### Scenario: Unknown discriminator is a load error
- **WHEN** the section declares `type: script`
- **THEN** loading fails with a located error naming the unknown type — the
  discriminator exists so future selection mechanisms arrive as new values,
  not as schema breaks

#### Scenario: Unknown keys are not ignored
- **WHEN** the section contains a misspelled key such as `defualt:`
- **THEN** loading fails with a located error naming the unknown key

#### Scenario: The earlier draft shape is not an alias
- **WHEN** `config.yaml` carries a root-level `base:` key, or
  `task-branch.base` carries a `menu` key
- **THEN** loading fails with a located error naming the unknown key, and
  nothing is silently read from it

#### Scenario: An invalid allowed pattern is a load error
- **WHEN** an `allowed` entry declares a pattern that does not compile
- **THEN** loading fails with a located error naming the entry, aggregated with every other validation problem

#### Scenario: An unknown role is a load error
- **WHEN** an `allowed` entry declares a `role` other than `development` or `release`
- **THEN** loading fails with a located error naming the unknown role

### Requirement: Definition validated at startup, bound per task from the base
`serve` and `take` SHALL load and validate the full definition from the
refreshed repository default branch at startup — fail-fast for the common
base, the source of the trusted tier, and the definition `board` and
`dashboard` display. That load SHALL NOT be authoritative for a task: after
base resolution the task tier SHALL be loaded from the task's law commit and
frozen from it, per task.
**Implements**: FR13, UX5 of add-base-ref-resolution
**Notes**: A base whose definition fails to load parks its task rather than retrying it because the failure is deterministic.

#### Scenario: Startup still fails fast on the default branch
- **WHEN** the default branch's `.gnomish/` carries a malformed stage manifest
- **THEN** `serve` exits at startup with the located errors, before any claim

#### Scenario: A broken base parks only its task
- **WHEN** the default branch loads cleanly and a task resolves to
  `release/1.18`, whose `.gnomish/` fails validation
- **THEN** that task parks with the located errors and the base ref in its
  report, the claim is not released into a retry loop, and other slots keep
  working

#### Scenario: A broken base's report names its law commit and burns no attempt
- **WHEN** a task's base definition fails to load from its law commit
- **THEN** the task parks with a configuration report naming the base ref, the law commit, and the located errors, and no stage attempt is burned

### Requirement: Configuration binds in two tiers
The trusted tier (`tracker:`, `task-branch:`, and future selector sections) SHALL bind once at startup from the refreshed repository default branch and SHALL NOT be re-read per task. The task tier (stages, instructions, criteria, the remainder of `config.yaml`) SHALL bind from the base's law commit — the pinned SHA on a fresh start, the current tip of the pinned ref name on resume.
**Implements**: FR19, NFR-S2 of add-sandbox-core; FR2, FR11, FR12, NFR-S1, M5 of add-base-ref-resolution; FR4 of tighten-spec-contract
**Notes**: Split from "Pipeline law binds per invocation". A tag or SHA base makes the fresh-start and resume commits equal.

#### Scenario: The trusted tier binds once at startup
- **WHEN** a daemon starts and later claims several tasks on different bases
- **THEN** `tracker:` and `task-branch:` were bound once, at startup, from the refreshed default branch, and no task's binding re-reads them

#### Scenario: The task tier binds from the base's law commit
- **WHEN** a task starts fresh on base `release/1.18`, pinned at a SHA, and is later resumed after `release/1.18` moved
- **THEN** the fresh start binds stages, instructions, criteria and the remainder of `config.yaml` from the pinned SHA, and the resume binds them from the current tip of `release/1.18`

### Requirement: Named executor declarations
The law MAY declare an `executors:` section mapping a name to: `type` (`program` or a
built-in id), `program` (argv array, `program` type only), `settings`, `credentials` (variable
names), `egress` (hosts). A stage's `executor.name` SHALL reference a declared executor; an
unknown name SHALL be a located error. Only a `program` stage SHALL name an executor: a name on a
stage of any other type SHALL be a located error. Declarations SHALL come from the law commit only.
**Implements**: FR9, NFR-S3 of define-executor-contract

#### Scenario: Unknown executor name is a located error
- **WHEN** a stage names `executor: {name: acme}` and no `executors.acme` exists
- **THEN** loading reports a located error naming the stage and `acme`

#### Scenario: Non-program stage naming a declared executor is rejected
- **WHEN** an `agent-cli` or `api` stage declares `executor: {name: acme}` and `executors.acme` exists
- **THEN** loading reports a located error naming the stage and `executor.name`, since the run's dispatch keys every non-program stage by its type and the name would pin an executor that never ran

#### Scenario: Executor declared in operator config is ignored
- **WHEN** operator configuration carries an `executors` key
- **THEN** loading reports an unknown-key error; executors come from `.gnomish/` only

#### Scenario: Structural errors are caught at law load
- **WHEN** `executors.acme` has an unknown `type`, or a `program` declaration has no argv
- **THEN** loading reports a located error naming the declaration and the field, and no process is spawned

### Requirement: Settings validated against the executor's declared schema
Before an executor's first round — once the task's execution environment is live and before the
engine runs — the factory SHALL obtain each declared executor's describe output and validate its
`settings` against the declared JSON Schema; each violation SHALL be a located error naming the
executor, the settings path and the schema rule, parking the task as an invalid law does.
**Implements**: FR9, FR4, FR13, UX1 of define-executor-contract

#### Scenario: Settings typo is caught before the first round
- **WHEN** `executors.acme.settings.maxTurn` is declared and the schema knows `maxTurns`
- **THEN** the describe preflight reports a located error at that path naming the unknown property, and no round runs

#### Scenario: Describe runs in the task's own environment
- **WHEN** a `program` stage's task is claimed in container mode
- **THEN** describe runs inside the task's box after it is live and before the engine starts, through the execution environment seam

### Requirement: A program that cannot be described parks its task
A declared program that is absent from the environment, cannot be started, exits non-zero on
describe, does not answer within the bound, or writes a malformed, oversized or missing answer
SHALL park the task as an invalid law does, with a located error naming the executor and the
failure; it SHALL never be a mid-stage failure, an operator precondition or a daemon condition.
**Implements**: FR4, FR13, UX1 of define-executor-contract
**Notes**: The law names the program, so the failure is the task's (ADR 0019, decision 5); an image lacking what the image contract guarantees stays the operator's.

#### Scenario: Describe that cannot run parks the task as a configuration error
- **WHEN** a declared program is absent from the image or exits non-zero on describe
- **THEN** the task parks as an invalid law does, with a located error naming the executor and the describe failure, no round runs, no attempt is burned and no daemon condition opens

#### Scenario: Describe that cannot start parks the task as an external failure
- **WHEN** the environment refuses to start the declared program, or describe does not exit within the bound
- **THEN** the task parks the same way, with a located error naming the executor and the failure as external, no round runs, no attempt is burned and no daemon condition opens

### Requirement: Credentials and egress are checked against describe
In the describe preflight, a declared `credentials` or `egress` entry absent from the executor's
describe SHALL be a located error; a describe-declared credential the law does not grant SHALL be
a located error naming the missing grant, so no executor runs without its declared inputs.
**Implements**: FR4, NFR-S1 of define-executor-contract

#### Scenario: Missing credential grant
- **WHEN** describe declares `ACME_API_KEY` and `executors.acme.credentials` omits it
- **THEN** the describe preflight reports a located error naming the executor and the variable, before the first round

### Requirement: Check tokens map to executor names
The loader SHALL map `type: builtin` to executor `files-exist`, `type: command` to `shell`, and
`type: external` to the executor named by `provider` (default `github-actions`), and SHALL
accept `executor: <name>` directly; the named executor SHALL declare role `verdict` or the
check is a located error.
**Implements**: FR1, UX2 of migrate-verify-to-executor-contract

#### Scenario: Default external provider maps
- **WHEN** an `external` check omits `provider`
- **THEN** the model names executor `github-actions`

#### Scenario: Explicit executor form
- **WHEN** a check declares `executor: sonarqube` and the law declares `executors.sonarqube`
- **THEN** validation passes and the check's settings validate against that executor's schema

### Requirement: SCM status checks declare expected contexts and a threshold
An `scm-status` check SHALL declare a non-empty `expected` list of context names; any verdict
check MAY declare `threshold` (`error` default, `warning`, `note`) used by the engine's verdict
derivation. A missing `expected` list on `scm-status` SHALL be a located error.
**Implements**: FR4, FR7 of migrate-verify-to-executor-contract

#### Scenario: Missing expected list
- **WHEN** an `scm-status` check declares no `expected`
- **THEN** validation reports a located error naming the check

### Requirement: Credentialed checks must run in a fresh box
A check whose executor describe declares credentials SHALL be a located error when it declares
`verify-in: same-box`; the default for such checks is `fresh-box`.
**Implements**: NFR-S1 of migrate-verify-to-executor-contract

#### Scenario: Same-box with a secret is refused
- **WHEN** a check names an executor declaring `SONAR_TOKEN` and `verify-in: same-box`
- **THEN** validation reports a located error naming the check and the credential

### Requirement: A judge check declares the stage's executor block
A `judge` check SHALL declare who judges with the `executor:` block a stage declares, parsed and
validated by the same owner and the same rules. No judge SHALL be defaulted: a `judge` check
with no `executor:` block, an agent block with no `model`, or a block whose executor does not
support the `verdict` role SHALL be a located load error, and the factory SHALL NOT start.
**Implements**: FR13, FR14, UX1 of extract-agent-round-runner
**Notes**: The executor, the judge and the arbiter are one mechanism configured one way; they differ in instructions and role.

#### Scenario: A judge check with an agent block is accepted
- **WHEN** a `judge` check declares `criteriaFile` and `executor: {type: agent-cli, model: m, settings: {maxTurns: 60}}`
- **THEN** validation passes and the typed check carries the same executor type a stage's block maps to

#### Scenario: A judge check without an executor block is rejected
- **WHEN** a `judge` check declares no `executor:` block
- **THEN** the load fails with a located error naming the stage, the check and `executor`

#### Scenario: A judge block without a model is rejected
- **WHEN** a `judge` check's `agent-cli` block declares no `model`
- **THEN** the load fails with the located error a stage's block without a model gets

#### Scenario: A judge executor without the verdict role is rejected
- **WHEN** a `judge` check's block names a `program` whose describe lists no `verdict` role
- **THEN** the load fails with a located error naming the check, the executor and the role

#### Scenario: Removed judge keys are rejected
- **WHEN** a `judge` check declares `votes`, or `model` or `settings` beside its `executor:` block
- **THEN** the load fails with a located error naming the check and the unknown field

#### Scenario: An agent block resolves to the reserved executor name
- **WHEN** a stage or a `judge` check declares `type: agent-cli`
- **THEN** it resolves in the executor registry to the entry named `claude`
- **AND** a law that declares an executor named `claude` is refused at load as a built-in's name
