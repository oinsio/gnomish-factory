# build-conventions — delta for add-functional-api-gate-test

## ADDED Requirements

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
<!-- implements FR2 of add-functional-api-gate-test -->

#### Scenario: A compatible addition passes the gate
- **WHEN** the isolated build's public api surface only adds a member relative
  to its baseline and the gate task runs
- **THEN** the build succeeds without regenerating the baseline
<!-- implements FR3 of add-functional-api-gate-test -->

#### Scenario: An absent baseline fails as unarmed, not as passing
- **WHEN** the gate task runs with an empty baseline directory
- **THEN** the build fails with the error stating the gate cannot be armed
<!-- implements FR4 of add-functional-api-gate-test -->

#### Scenario: The gate executes as part of check
- **WHEN** the isolated build runs its `check` task
- **THEN** the gate task executes with a successful outcome — neither skipped
  nor absent from the task graph
<!-- implements FR5 of add-functional-api-gate-test -->

#### Scenario: The baseline workflow re-arms the gate
- **WHEN** the baseline-update task regenerates the baseline from a surface the
  gate previously failed
- **THEN** a subsequent gate run against the regenerated baseline passes
<!-- implements FR6 of add-functional-api-gate-test -->

#### Scenario: Refactoring the convention text alone breaks no test
- **WHEN** a convention script is refactored without changing the gate's
  observable behavior
- **THEN** no test fails on the wording or structure of the script
<!-- implements FR8 of add-functional-api-gate-test -->

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
<!-- implements FR7, NFR-R1 of add-functional-api-gate-test -->

#### Scenario: The published-api module's build is unchanged
- **WHEN** the published-api convention is applied to `gnomish-plugin-api`
- **THEN** the gate, baseline-update, and version-verification tasks behave
  exactly as before the extraction
<!-- implements FR7 of add-functional-api-gate-test -->
