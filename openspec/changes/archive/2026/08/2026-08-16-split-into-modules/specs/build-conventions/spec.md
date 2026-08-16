## ADDED Requirements

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
every module's run (see the `quality-gates` delta of this change).
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
