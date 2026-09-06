# base-ref-resolution — delta for add-base-ref-resolution

## Purpose

Decide which ref a new task branch starts from: a project-configured menu of
allowed bases (patterns with roles), a per-task selection carried as tracker
metadata, a fixed source-priority order with fail-closed escalation when the
choice is ambiguous or outside the menu, remote-driven default-branch
discovery, and the base pin that makes the decision durable for the task's
lifetime.

## ADDED Requirements

### Requirement: Base menu governs allowed bases
The base menu SHALL be a list of patterns, each with a role — `development` or
`release`, defaulting to `development` — that names the set of refs a task may
branch from. A per-task selection SHALL be accepted only when it matches a
menu pattern; a selection matching no pattern SHALL be rejected as
underdetermined input, never silently replaced by another base. An empty menu
— the `base:` section absent or declaring no entries — accepts no per-task
selection: configuration that could deliver one is refused at load (see
pipeline-config), and should a designator reach resolution regardless, it is
underdetermined input like any out-of-menu selection. Environment-style
deploy pointers are not
menu material: the menu classifies branch roles precisely so later changes can
gate on them, and no role beyond the two named ones exists in this version.
<!-- implements FR1, FR4 of add-base-ref-resolution -->

#### Scenario: Selection inside the menu is accepted
- **WHEN** the menu holds `release/*` and the task selects `release/1.18`
- **THEN** resolution accepts `release/1.18` as the base and records the
  menu rule that matched it

#### Scenario: Selection outside the menu escalates
- **WHEN** the task selects `experiments/foo` and no menu pattern matches it
- **THEN** the task parks with a report naming the selected value and the
  configured menu, no branch is created, and no stage attempt is burned

#### Scenario: Empty menu with a designator is refused, not resolved
- **WHEN** the menu is empty and a `base` designator nevertheless reaches
  resolution (the load-time check was bypassed)
- **THEN** resolution treats the designator as outside the (empty) menu and
  escalates rather than branching from an unvetted ref

### Requirement: Resolution follows one priority order
Base resolution SHALL follow exactly one priority order: an explicit `--base`
argument; else the task's `base` designator validated against the menu; else
the configured `default`; else the repository default branch discovered from
the remote. Manual `run` without `--base` SHALL alone fall through to the
local HEAD of the clone, with no network interaction; autonomous paths SHALL
never fall back to the local HEAD. The priority chain reserves a named slot
for a future type-derived tier between the designator and the configured
default (`add-pipeline-routing`); that tier does not exist in this version.
The resolved decision SHALL carry the ref, the rule that produced it, and a
human-readable reason.
<!-- implements FR4, FR5, FR8 of add-base-ref-resolution -->

#### Scenario: Explicit base wins over a designator
- **WHEN** `take <ref> --base v1.2.3` runs on a task carrying a valid
  `base` designator
- **THEN** the branch starts from `v1.2.3` and the pin records the
  explicit-argument rule

#### Scenario: Designator wins over the configured default
- **WHEN** the config declares `default: develop` and the task carries the
  designator for `release/1.18` matching the menu
- **THEN** the branch starts from the refreshed `release/1.18`

#### Scenario: Zero configuration resolves to the remote default branch
- **WHEN** a project with no `base:` block is taken autonomously and the
  remote reports `trunk` as its default branch
- **THEN** the branch starts from the refreshed `trunk`, never from a
  hardcoded name and never from the clone's local HEAD

#### Scenario: Autonomous mode refuses without a remote
- **WHEN** an autonomous take reaches default-branch discovery in a clone
  with no origin remote
- **THEN** the take fails as an infrastructure failure instead of silently
  branching from the local HEAD

### Requirement: Designator conflicts and ambiguity escalate
A conflicting base designator (more than one value found on the task) SHALL
escalate with a report listing every value found; resolution SHALL never pick
one. All escalations of underdetermined base input — conflict or out-of-menu
— SHALL be quality-of-input escalations that park the task for a human,
without burning a stage attempt, and the report SHALL name what was found and
what the configuration allows.
<!-- implements FR3, FR4 of add-base-ref-resolution -->
<!-- implements UX2 of add-base-ref-resolution -->

#### Scenario: Two base labels park the task
- **WHEN** a claimed task carries designator values `release/1.18` and
  `release/1.19`
- **THEN** the task parks with a report listing both values, and the claim
  protocol's park path runs as for any escalation

### Requirement: Default branch is discovered, not assumed
The repository default branch SHALL be discovered from the remote at
resolution time. Discovery failure in an autonomous path SHALL classify as an
infrastructure failure (bounded retries, claim released, no attempt burned),
never as a fallback to a guessed name.
<!-- implements FR5, FR9 of add-base-ref-resolution -->

#### Scenario: Renamed default branch is honored
- **WHEN** the remote's default branch was changed from `main` to `develop`
  before a task is claimed
- **THEN** a zero-config resolution branches from the refreshed `develop`
  with no configuration change

### Requirement: Base configuration is read from the refreshed default branch only
The `base:` configuration SHALL be read factory-side from the repository
default branch, refreshed by fetch, and never from a task branch or a
gnome-writable working copy. Copies of the configuration in a gnome's working
copy are project content — law only after a human merge. The read SHALL go
through the git-objects law source at the refreshed default-branch tip, never
through a checkout. The `base:` block is trusted-tier configuration; the task
tier of the law binds from the chosen base's law commit (see pipeline-config,
"Pipeline law binds per invocation"). This requirement is what breaks the
"the config picks the base, but which ref holds the config" cycle.
<!-- implements FR2, NFR-S1 of add-base-ref-resolution -->

#### Scenario: A gnome edit to the base block has no effect
- **WHEN** a gnome branch modifies the `base:` block in its working copy and
  another task is claimed afterwards
- **THEN** the new task resolves under the default-branch configuration, and
  the gnome's edit participates only after a human merges it

### Requirement: The base decision is pinned at claim and never re-resolved
The resolved base — ref, commit SHA, and source rule — SHALL be pinned into
the task's durable state in the task-creation commit, before any agent runs.
Every resume, on any instance and in any execution mode, SHALL read the pin
and SHALL NOT re-resolve the base from tracker metadata or configuration; a
designator or menu change after the pin affects only tasks not yet pinned.
Before the pin exists, re-resolution from scratch is the recovery of every
crash window, and a later resolution answering differently is legal — nothing
durable references the earlier answer.
The pinned SHA is the law commit of the fresh start; on resume the law commit
is the current tip of the pinned ref name (pipeline-config owns that rule),
which is a law-freshness matter, not a re-resolution of the base.
<!-- implements FR7, NFR-R2, NFR-S2 of add-base-ref-resolution -->

#### Scenario: Retargeting a pinned task has no effect
- **WHEN** a human changes the task's base label after the task branch exists
  and the task is later resumed
- **THEN** the resume continues on the pinned base and the label change is
  reflected nowhere in the run

#### Scenario: Pin guard and law share one commit
- **WHEN** an external check with pinned law paths runs on a fresh task
- **THEN** the guard compares the attempt's law files against the pinned
  base SHA — the same commit the law was frozen from — never against the
  clone's `HEAD`

#### Scenario: A crash before the pin re-resolves cleanly
- **WHEN** an instance dies after refreshing the base but before the
  task-creation commit, and any instance picks the task up later
- **THEN** the pickup resolves the base from scratch — possibly to a newer
  tip — and no state anywhere references the first resolution

### Requirement: Resolution is declarative and executes no repository code
Base resolution SHALL be deterministic pattern matching over values — the
menu, the task's designators, the discovered default branch, and the
invocation mode. No repository-provided executable, hook, or script SHALL
run to choose a base. External automation that computes a base and records
it as task metadata is the supported customization path and SHALL be
documented in the operator guide.
<!-- implements FR10, NFR-S3, NFR-C1, UX4 of add-base-ref-resolution -->

#### Scenario: Same inputs, same decision
- **WHEN** two factory instances resolve the same task against the same
  configuration and the same task metadata
- **THEN** both produce the same decision (ref, rule) with no environment-
  or repo-content-dependent variation

#### Scenario: Automation-set label drives the choice
- **WHEN** an external job computes the base for a task and sets the
  corresponding label before the task is claimed
- **THEN** the factory validates the label against the menu and branches
  accordingly, with no factory-side custom code involved
