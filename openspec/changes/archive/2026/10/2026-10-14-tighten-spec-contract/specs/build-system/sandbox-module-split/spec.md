# Spec Delta: build-system/sandbox-module-split

## MODIFIED Requirements

### Requirement: Sandbox core separated from backends
The sandbox port SHALL split into `:sandbox:core` — a first-party port-layer
module holding the `TaskExecutionEnvironment` port, capability-passport
negotiation, reconciliation, and the `IsolationLevel` / `AdapterBinding` types —
and per-backend adapter modules. The core SHALL carry no backend-specific code
or dependencies; each backend's mechanics (today: the subprocess docker-CLI
backend; later: backends bringing their own SDKs) live only in that backend's
module.
**Implements**: FR8 of split-into-modules

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

## ADDED Requirements

### Requirement: Sandbox port contract and the discovered AdapterBinding registry
The `TaskExecutionEnvironment` port contract and the capability-passport
reconciliation semantics (operator binds, repo only tightens, fail-closed on
mismatch) SHALL be unchanged by the split and by the opening of `AdapterBinding`,
which `open-adapter-binding-registry` (change C) turned from a sealed type into a
discovered first-party registry.
**Implements**: FR8, FR9 of split-into-modules; FR1 of open-adapter-binding-registry
**Notes**: The scenario `Existing sandbox specs pass unchanged` was the acceptance of the archived change `split-into-modules` and left with the old heading `Sandbox port contract preserved; AdapterBinding opened by change C` (FR16 of tighten-spec-contract).

#### Scenario: AdapterBinding is a discovered registry
- **WHEN** `AdapterBinding` is inspected after change C
- **THEN** it is resolved from a discovered first-party registry, not a sealed
  enum of core-defined constants
- **AND** `host` and `container` remain available with their prior passports

#### Scenario: A backend contributes its binding without a core edit
- **WHEN** a backend module contributes a new adapter binding and its capability passport
- **THEN** the binding is discovered from that module through the first-party registry, with no edit to a core enum or any other `:sandbox:core` type

## REMOVED Requirements

### Requirement: Sandbox port contract preserved; AdapterBinding opened by change C
**Reason**: its scenario `Existing sandbox specs pass unchanged` measured the spec files against the state before the archived change `split-into-modules`; no test, build step or document can observe that state now (finding F120, FR16 of tighten-spec-contract).
**Migration**: the requirement text and its standing scenarios `AdapterBinding is a discovered registry` and `A backend contributes its binding without a core edit` continue unchanged under `Sandbox port contract and the discovered AdapterBinding registry`; the port's own specs keep verifying its contract.
