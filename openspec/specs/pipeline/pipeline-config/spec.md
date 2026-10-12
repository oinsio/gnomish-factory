# pipeline-config

## Purpose

Load a target project's `.gnomish/` directory — `config.yaml`, `pipeline.yaml`, and per-stage `stages/<name>/stage.yaml` — into an immutable, typed `PipelineDefinition`. Loading is deterministic, read-only, and fully validated (structural, semantic, and local-sanity) before any pipeline runs. Validation problems are aggregated and returned as located `ConfigError` data rather than thrown, so any factory instance can reproduce the same pipeline from the same configuration. The loader never executes configured commands, model calls, or external checks, and never reads outside the config root.

## Requirements

### Requirement: Load .gnomish/ into a typed pipeline definition
The loader SHALL read a `.gnomish/` directory — `config.yaml`, `pipeline.yaml`, and `stages/<name>/stage.yaml` for each stage — and build an immutable, typed `PipelineDefinition`. Loading SHALL be deterministic and read-only.
<!-- implements FR1, NFR-R1 of load-pipeline-config -->

#### Scenario: Valid configuration loads
- **WHEN** the loader is given a `.gnomish/` directory whose files are structurally and semantically valid
- **THEN** it returns a `PipelineDefinition` whose stages, order, verify checks, and resolved autonomy limits match the on-disk configuration exactly
- **AND** the returned model is immutable

#### Scenario: Loading never writes to disk
- **WHEN** the loader processes any `.gnomish/` directory, valid or invalid
- **THEN** no file under the directory is created, modified, or deleted
- **AND** loading the same directory twice yields an equal result

### Requirement: Stage model mirrors the stage contract and all verify-check types
Each `StageDefinition` SHALL represent the stage-contract sections (purpose, input, output, control, mechanism/executor, verify, failure/escalation limits, advancement) and SHALL model the four verify-check types — `builtin`, `command`, `external`, `judge` — as distinct typed variants carrying their own configuration. The `external` variant SHALL additionally carry a `provider` discriminator and an opaque `params` map of flat JDK types (Jackson-free, like `builtin` params); its engine-common fields — `interval`, `timeout`, `timeoutClass`, `pinPaths` — remain modeled as before. No new sealed verify-check variant SHALL be introduced.
<!-- implements FR2 of load-pipeline-config -->
<!-- implements FR7 of add-plugin-architecture -->

#### Scenario: All four check types are modeled distinctly
- **WHEN** a stage's `verify` list contains a `builtin`, a `command`, an `external`, and a `judge` check
- **THEN** the model exposes each as its own typed variant with its type-specific fields (e.g. `command` carries the executable, `judge` carries the acceptance-criteria path and vote count)
- **AND** the ordered position of each check within the stage is preserved

#### Scenario: External check carries provider and opaque params
- **WHEN** an `external` check declares a `provider` and provider-specific `params`
- **THEN** the model carries the `provider` discriminator and the `params` as an opaque flat-typed map alongside the engine-common `interval`, `timeout`, `timeoutClass`, and `pinPaths`
- **AND** no new verify-check sealed variant is added

#### Scenario: Unknown check type is rejected
- **WHEN** a `verify` entry declares a check `type` that is not one of the four known types
- **THEN** validation reports a located error naming the unknown type

### Requirement: Stage order is declared, not derived
`pipeline.yaml` SHALL be the source of truth for stage order as an explicit, non-empty, linear sequence of unique stage names. The artifact dependency graph SHALL be validated for consistency against that order and SHALL NOT be used to derive it.
<!-- implements FR3 of load-pipeline-config -->

#### Scenario: Order comes from pipeline.yaml
- **WHEN** `pipeline.yaml` lists stages in a given order
- **THEN** the `PipelineDefinition` presents stages in exactly that order

#### Scenario: Empty or duplicate order is rejected
- **WHEN** `pipeline.yaml` has an empty stage list or repeats a stage name
- **THEN** validation reports a located error

### Requirement: Artifact references by identifier
Each stage output SHALL declare a stable `id`, unique across the whole pipeline. An internal input SHALL reference the `id` of an output produced by a stage that appears **earlier** in the pipeline order. A `source` input SHALL declare that it has no producing stage.
<!-- implements FR4 of load-pipeline-config -->

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
<!-- implements FR5 of load-pipeline-config -->

#### Scenario: Missing required field is reported
- **WHEN** a `stage.yaml` omits a required field
- **THEN** validation reports a located error naming the file and the missing field

#### Scenario: Unknown enum value is reported
- **WHEN** a stage declares an executor type or advancement mode outside the allowed set
- **THEN** validation reports a located error naming the offending value

### Requirement: Semantic validation
The loader SHALL enforce cross-file rules: unique stage names; every `pipeline.yaml` stage has a matching `stages/<name>/stage.yaml`; every stage directory is referenced by `pipeline.yaml` (no dangling definitions); every internal input is satisfied by an earlier stage's output; and every referenced file (`instructions.md`, `judge` acceptance-criteria files) exists.
<!-- implements FR6 of load-pipeline-config -->

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
<!-- implements FR7 of load-pipeline-config -->
<!-- implements FR16 of add-agent-executor -->

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
<!-- implements FR8, NFR-O1 of load-pipeline-config -->

#### Scenario: All problems reported in one pass
- **WHEN** a `.gnomish/` directory contains several independent validation problems
- **THEN** the returned error list contains all of them, not only the first
- **AND** each error names its file and the field or stage at fault

#### Scenario: Validation failure is data, not an exception
- **WHEN** the configuration is invalid
- **THEN** the loader returns an error result rather than throwing

### Requirement: Schema version recognition
A `schemaVersion` SHALL be declared in `config.yaml` — one version for the whole `.gnomish/` tree. A missing, unknown, or unsupported version SHALL be a validation error.
<!-- implements FR9 of load-pipeline-config -->

#### Scenario: Unsupported schema version is rejected
- **WHEN** `config.yaml` declares a `schemaVersion` the loader does not support
- **THEN** validation reports a located error naming the version

#### Scenario: Missing schema version is rejected
- **WHEN** `config.yaml` declares no `schemaVersion`
- **THEN** validation reports a located error naming `config.yaml`

### Requirement: Local sanity validation of mechanism and check configs
The loader SHALL apply catalog-free sanity rules that do not require a live target. The executor `model` SHALL be present and non-blank for every executor type — the model is pinned in the stage manifest so any instance reproduces the stage identically, never left to an executor default — and `settings` SHALL be carried as an opaque, well-formed mapping (not validated by key, value, or range). An `external` check SHALL have a positive `interval`, a positive `timeout`, `interval ≤ timeout`, and a non-blank check identifier. An `external` check SHALL also carry a `provider`, defaulting to `github` when absent; a `provider` absent from the discovered check-provider registry SHALL be a located error naming the provider and the discovered set — provider existence is in-process knowledge, not target liveness; the check's provider-specific `params` SHALL be validated at the seam by that provider's `CheckParamsValidator`, whose problems are aggregated as located `ConfigError` data like every other validation problem. A `judge` check SHALL have `votes ≥ 1` and an odd `votes`. The loader SHALL NOT validate target liveness — whether a CI-check name exists, whether a `model` is real, or whether `judge` criteria are gradeable.
<!-- implements FR11 of load-pipeline-config -->
<!-- implements FR6 of add-plugin-architecture -->
<!-- implements FR13 of add-plugin-architecture -->
<!-- implements UX1 of add-plugin-architecture -->

#### Scenario: Missing model is rejected
- **WHEN** a stage's `model` is absent or blank, whatever the executor type
- **THEN** validation reports a located error
- **AND** `settings` present as a mapping is accepted without inspecting its keys or values

#### Scenario: External check timing is sane
- **WHEN** an `external` check has a non-positive `interval` or `timeout`, or `interval > timeout`
- **THEN** validation reports a located error identifying the check
- **AND** a check with positive `interval`, positive `timeout`, and `interval ≤ timeout` is accepted

#### Scenario: Unknown provider is a located load error
- **WHEN** an `external` check declares a `provider` that no discovered check
  provider serves
- **THEN** validation reports a located error naming the unknown provider and
  the discovered provider set, before any stage runs

#### Scenario: Defaulted github provider absent from the registry is a located error
- **WHEN** an `external` check omits `provider` and no discovered provider
  serves `github` (the github plugin jar is absent from the classpath)
- **THEN** validation reports a located error naming the defaulted `github`
  provider and the discovered set, exactly as for an explicitly named unknown
  provider — the factory itself still starts

#### Scenario: External check provider defaults to github and validates its params
- **WHEN** an `external` check omits `provider` but declares provider-specific `params`
- **THEN** the loader records `provider: github` and invokes the github `CheckParamsValidator` on the `params`
- **AND** a param problem is reported as a located `ConfigError` identifying the check

#### Scenario: Judge vote count must be positive and odd
- **WHEN** a `judge` check declares `votes` that is less than 1 or even
- **THEN** validation reports a located error identifying the check

#### Scenario: Target liveness is not validated
- **WHEN** an `external` check names a CI check, or a `judge`/executor declares a model, that does not exist in any live system
- **THEN** validation does not attempt to confirm its existence and does not fail on that ground

### Requirement: External check declarations carry a timeout class
The external check declaration SHALL accept an optional timeout class —
`quality` or `infrastructure` — defaulting to `quality` when absent. The
value SHALL load into the typed model; a value outside the two known
classes SHALL be a located validation error identifying the check.
<!-- implements FR9 of add-external-check-github-actions -->

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
<!-- implements NFR-S1, NFR-S2, NFR-C1 of load-pipeline-config -->

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
<!-- implements FR10 of load-pipeline-config -->

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
<!-- implements FR17 of add-tracker-port -->

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
<!-- implements FR17 of add-tracker-port -->

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
<!-- implements FR3 of add-claim-heartbeat -->

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
<!-- implements FR6 of add-factory-serve -->
<!-- implements NFR-S3 of add-factory-serve -->

#### Scenario: Default applies

- **WHEN** a `tracker` section declares `type` but no `wip-limit`
- **THEN** the definition carries WIP limit 10

#### Scenario: Zero limit is a load error

- **WHEN** the section declares `wip-limit: 0`
- **THEN** loading fails with a located error naming the minimum of 1

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
Pipeline law — `.gnomish/` stage manifests, stage instructions, and judge
acceptance criteria — SHALL be bound at invocation start and frozen for the
invocation's lifetime, including the in-process outcome loop. The law source
SHALL be read through one law-source abstraction with exactly two
realizations: **git objects at one commit — the law commit —** in every path
that resolved a ref (tracker-driven modes, manual `run` with `--base`), and
the **working tree** in the git-less in-place mode and in manual `run`
without `--base`. Where a ref was resolved, the factory clone's working tree,
index, and `HEAD` SHALL play no part in the law. Configuration has two tiers:
the trusted tier (`tracker:`, `task-branch:`, and future selector sections) binds
once at startup from the refreshed repository default branch and is never
re-read per task; the task tier (stages,
instructions, criteria, the remainder of `config.yaml`) binds from the base's
law commit — the pinned SHA on a fresh start, the current tip of the pinned
ref name on resume (a tag or SHA base makes these equal). The external-check
pin guard SHALL compare against the law commit as a commit id peeled once at
binding; the guard SHALL NOT resolve a ref name itself. Both realizations SHALL be
rooted at the **law root** — the `.gnomish/` directory of the law commit or
of the working tree, the same root the loader validates file references
against — so a stage file reference that validates at load reads at run in
every medium; the repository root reaches the pin guard as a separate value
and is never the law root. A symlink entry at any segment of a reference's
path under the law root SHALL be refused as an unreadable law file by both
realizations, never followed and regardless of its target; the verdict SHALL
come from one shared walk, so both realizations report the same category
for one tree; an absolute reference SHALL be refused by both. Control files and judge
acceptance criteria SHALL be read from the law source, never from the
gnome-writable working copy at use time. Copies of law files in the gnome's
working copy are project content: editable, but never law for the current
task. A contract test SHALL enforce the source in git modes — including a
base that differs from the clone's checked-out ref and the default-branch
source of the trusted tier.
<!-- implements FR19, NFR-S2 of add-sandbox-core -->
<!-- implements FR2, FR11, FR12, NFR-S1, M5 of add-base-ref-resolution -->

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

### Requirement: Optional task-branch base section with allowed patterns
`.gnomish/config.yaml` SHALL support an optional `task-branch:` section — the
root key is the glossary term for the branch it configures — holding a `base`
subsection: `type` (a discriminator; `patterns` is the only supported value in
this version, and it is the default when absent), `default` (optional ref
name), and `allowed` (a list of entries, each `pattern` plus optional `role`
of `development` | `release`, defaulting to `development`). Patterns SHALL
compile at load. Located `ConfigError`s under the existing aggregation
contract SHALL cover: an unknown `type` value, an unknown key anywhere in the
section — a root-level `base:` and a `menu` key included, since the earlier
draft shape has no alias — an invalid pattern, an unknown `role`, and a
`default` that matches no allowed pattern when any is declared. The
subsection holds no tracker-specific selection rule: how a task names its
base is the tracker adapter's own configuration (`tracker.<type>.designators`,
owned and validated by the adapter per tracker-port). An absent section SHALL
be valid: no allowed bases, no configured default, and all previously
specified loading behavior unchanged. The loader parses and validates only —
resolution semantics belong to base-ref-resolution. The `task-branch:`
section is reserved for settings of the task branch itself; a name-prefix
override is a named candidate for a later change, not part of this one.
<!-- implements FR1 of add-base-ref-resolution -->
<!-- implements UX1 of add-base-ref-resolution -->

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

### Requirement: Definition validated at startup, bound per task from the base
`serve` and `take` SHALL load and validate the full definition from the
refreshed repository default branch at startup — fail-fast for the common
base, the source of the trusted tier, and the definition `board` and
`dashboard` display. That load SHALL NOT be authoritative for a task: after
base resolution the task tier SHALL be loaded from the task's law commit and
frozen from it, per task. A definition that fails to load from a base SHALL
park the task with a configuration report naming the base ref, the law
commit, and the located errors — no stage attempt burned, no claim released
for retry, because the failure is deterministic.
<!-- implements FR13, UX5 of add-base-ref-resolution -->

#### Scenario: Startup still fails fast on the default branch
- **WHEN** the default branch's `.gnomish/` carries a malformed stage manifest
- **THEN** `serve` exits at startup with the located errors, before any claim

#### Scenario: A broken base parks only its task
- **WHEN** the default branch loads cleanly and a task resolves to
  `release/1.18`, whose `.gnomish/` fails validation
- **THEN** that task parks with the located errors and the base ref in its
  report, the claim is not released into a retry loop, and other slots keep
  working
