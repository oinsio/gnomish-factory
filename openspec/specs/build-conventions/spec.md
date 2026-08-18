# build-conventions

## Purpose

Defines how shared Gradle build logic is organized across the module tree: convention plugins in `build-logic`, a shared version catalog, per-module mutation-test scoping, and the build-time budget the split must respect.

## Requirements

### Requirement: Shared build logic in convention plugins
Common build configuration SHALL live in `build-logic` convention plugins, and
each module's build file SHALL stay within the project file-size cap.
<!-- implements FR6 of split-into-modules -->

#### Scenario: Module build files are thin
- **WHEN** each module's build file is measured
- **THEN** it applies a `build-logic` convention plugin
- **AND** it stays within the project file-size cap, replacing the former
  796-line monolith

#### Scenario: Version catalog is shared
- **WHEN** a module declares a dependency
- **THEN** the version comes from the shared `gradle/libs.versions.toml` catalog

### Requirement: Per-module mutation-test scoping
`check` / PIT SHALL be scoped per module: each module's `check` runs its own
PIT bound to that module's production Java classes, so a change touching a
single module mutates only that module's classes, while root `check` aggregates
every module's run (see the `quality-gates` capability).
<!-- implements FR6, FR11, NFR-P1 of split-into-modules -->

#### Scenario: Single-module change mutates only that module
- **WHEN** a developer runs `check` for the one module a change touches
- **THEN** `targetClasses` covers only that module's production Java packages
- **AND** the other modules' classes are not mutated

#### Scenario: Mutation targets exclude Groovy test bytecode
- **WHEN** PIT resolves its target classes for any module
- **THEN** only Java production classes are targeted, never Groovy test bytecode

### Requirement: Full clean-build time not regressed
Full clean-build wall-time SHALL NOT regress versus the monolith; module-level
parallelism offsets the multi-module overhead.
<!-- implements NFR-P2 of split-into-modules -->

#### Scenario: Clean build stays within the monolith baseline
- **WHEN** a full clean build runs with the module tree in place
- **THEN** its wall-time is within the pre-split monolith baseline

### Requirement: Documented single-module mutation invocation
A developer SHALL be able to run mutation tests for only the module they touched
via a documented Gradle invocation.
<!-- implements UX1 of split-into-modules -->

#### Scenario: Developer runs PIT for one module
- **WHEN** a developer runs the documented per-module PIT task for the module they
  changed
- **THEN** only that module's mutation tests execute

### Requirement: Functional verification of the api-compatibility gate
The arming of the api-compatibility gate SHALL be verified functionally: an
automated suite in the build-logic project SHALL execute the gate convention in
an isolated build against a controlled baseline and assert the gate's observable
behavior. No test SHALL assert the textual content of a convention script as a
proxy for the gate's behavior.
<!-- implements FR1, FR8, FR9 of add-functional-api-gate-test -->

#### Scenario: An incompatible change fails the isolated build
- **WHEN** the isolated build's public api surface has a binary-incompatible
  change relative to its baseline and the gate task runs
- **THEN** the build fails at the gate
- **AND** the failure output names a binary incompatibility

#### Scenario: A compatible addition passes the gate
- **WHEN** the isolated build's public api surface only adds a member relative
  to its baseline and the gate task runs
- **THEN** the build succeeds without regenerating the baseline

#### Scenario: An absent baseline fails as unarmed, not as passing
- **WHEN** the gate task runs with an empty baseline directory
- **THEN** the build fails with the arming error stating the gate cannot run
  without a baseline

#### Scenario: The gate executes as part of check
- **WHEN** the isolated build runs its `check` task
- **THEN** the gate task executes with a successful outcome — neither skipped
  nor absent from the task graph

#### Scenario: The baseline workflow re-arms the gate
- **WHEN** the baseline-update task regenerates the baseline from a surface the
  gate previously failed
- **THEN** a subsequent gate run against the regenerated baseline passes

#### Scenario: Refactoring the convention text alone breaks no test
- **WHEN** a convention script is refactored without changing the gate's
  observable behavior
- **THEN** no test fails on the wording or structure of the script

### Requirement: Single-purpose gate convention
The api-compatibility gate SHALL live in its own convention plugin with only
the prerequisites the gate needs, applied by the published-api convention, so
the functional suite can exercise the gate without the full library convention
chain and each convention file stays within the project file-size target.
<!-- implements FR7 of add-functional-api-gate-test -->

#### Scenario: The gate is exercisable in isolation
- **WHEN** the functional suite applies the gate convention to a minimal java
  library project
- **THEN** the gate tasks are available and behave as specified without the
  formatting, static-analysis, or mutation-testing conventions being applied

#### Scenario: The published-api module's build is unchanged
- **WHEN** the published-api convention is applied to `gnomish-plugin-api`
- **THEN** the gate, baseline-update, and version-verification tasks behave
  exactly as before the extraction
