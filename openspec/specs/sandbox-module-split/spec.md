# sandbox-module-split

## Purpose

Defines the separation of the sandbox port layer (`:sandbox:core`) from per-backend adapter modules, so backend mechanics and their dependencies stay out of the port layer while the `TaskExecutionEnvironment` contract is preserved.

## Requirements

### Requirement: Sandbox core separated from backends
The sandbox port SHALL split into `:sandbox:core` — a first-party port-layer
module holding the `TaskExecutionEnvironment` port, capability-passport
negotiation, reconciliation, and the `IsolationLevel` / `AdapterBinding` types —
and per-backend adapter modules. The core SHALL carry no backend-specific code
or dependencies; each backend's mechanics (today: the subprocess docker-CLI
backend; later: backends bringing their own SDKs) live only in that backend's
module.
<!-- implements FR8 of split-into-modules -->

#### Scenario: Core is free of backend-specific code and dependencies
- **WHEN** `:sandbox:core`'s sources and dependencies are inspected
- **THEN** no backend-specific class or dependency (docker-CLI mechanics,
  testcontainers, any backend SDK) is present in core
- **AND** backend mechanics are present only in the backend module(s)

#### Scenario: Backend depends on core, not the reverse
- **WHEN** the module dependencies are inspected
- **THEN** `:sandbox:docker` depends on `:sandbox:core`
- **AND** `:sandbox:core` does not depend on any backend module

#### Scenario: Use cases reach the port through core
- **WHEN** `:application` uses the execution environment
- **THEN** it imports the port from `:sandbox:core`, never from a backend module

### Requirement: Sandbox port contract unchanged by the split
The `TaskExecutionEnvironment` port contract SHALL be unchanged by the split; the
capability-passport reconciliation semantics (operator binds, repo only tightens,
fail-closed on mismatch) are preserved, and `AdapterBinding` remains a closed
enum (opening it is change C, out of scope).
<!-- implements FR8, FR9 of split-into-modules -->

#### Scenario: Existing sandbox specs pass unchanged
- **WHEN** the existing execution-environment specs run against the split modules
- **THEN** they pass with no changes to the spec files

#### Scenario: AdapterBinding stays a closed enum
- **WHEN** `AdapterBinding` is inspected after the split
- **THEN** it remains an enum with the same constants as before the split
