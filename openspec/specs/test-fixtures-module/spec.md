# test-fixtures-module

## Purpose

Defines the dedicated module that holds Spock test fixtures shared across modules, so fixtures are reused rather than duplicated and never leak into production dependencies.

## Requirements

### Requirement: Shared test fixtures in a dedicated module
Shared Spock test fixtures SHALL live in a dedicated `test-fixtures` module,
consumed by other modules via `testImplementation`.
<!-- implements FR7 of split-into-modules -->

#### Scenario: A module reuses shared fixtures
- **WHEN** a module's tests need a shared fixture
- **THEN** the fixture is provided by the `:test-fixtures` module via
  `testImplementation`
- **AND** the fixture is not duplicated in the consuming module

#### Scenario: Fixtures module carries no production dependency
- **WHEN** the module dependencies are inspected
- **THEN** no production (non-test) module depends on `:test-fixtures`
