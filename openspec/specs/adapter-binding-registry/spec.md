# adapter-binding-registry

## Purpose

Opens `AdapterBinding` from a closed core enum into a discovered, first-party
registry: a backend module contributes its binding and `CapabilityPassport`
through a `SandboxBindingProvider`, ratified against a core-owned trust table,
so backend mechanics stay out of `:sandbox:core` while resolution stays
deterministic, fail-fast, and observable at startup.

## Requirements

### Requirement: Bindings are discovered, not enumerated in core
The set of adapter bindings SHALL be assembled at startup by discovering
first-party `SandboxBindingProvider` contributions, not from a core enum. Each
provider SHALL expose its config name and its fixed `CapabilityPassport` without
instantiating a live backend adapter, so binding enumeration and planning-time
reconciliation stay adapter-instance-free (no docker daemon touched).
<!-- implements FR1, FR2 of open-adapter-binding-registry -->

#### Scenario: A backend module contributes a binding without core logic edits
- **WHEN** a first-party module ships a `SandboxBindingProvider` on the classpath
  and its id → expected-passport entry is present in the trust table
- **THEN** its binding appears in the registry with no other edit to
  `:sandbox:core`

#### Scenario: Passport enumerated without a running backend
- **WHEN** the registry is built and every binding's `configName()` and
  `passport()` are read
- **THEN** no live backend adapter is instantiated and no docker daemon is
  contacted

### Requirement: First-party trust table ratifies discovery
Discovery SHALL be gated by a core-owned trust table mapping each trusted
first-party binding id — a binding's id is its config name; there is no
separate identifier — to its expected `CapabilityPassport`. A discovered
provider whose config name is not in the table SHALL be rejected fail-fast at
startup; a provider whose declared passport differs from the table's expected
passport SHALL be rejected fail-fast; each error SHALL name the fix. The
provider's self-declared passport is a cross-check, never the authority.
<!-- implements FR7, FR10, NFR-S1 of open-adapter-binding-registry -->

#### Scenario: Ratified provider is accepted
- **WHEN** a discovered provider's config name is in the trust table and its
  declared passport equals the expected one
- **THEN** its binding is registered

#### Scenario: Unknown binding id is rejected
- **WHEN** a discovered provider's config name is not in the trust table
- **THEN** startup fails with a named error identifying the untrusted binding id
  and how to resolve it
- **AND** the binding is not registered

#### Scenario: Passport mismatch is rejected
- **WHEN** a discovered provider's declared passport differs from the trust
  table's expected passport for its id
- **THEN** startup fails with a named error identifying the mismatch
- **AND** the binding is not registered

### Requirement: Deterministic, fail-fast binding resolution
Binding resolution SHALL be deterministic and fail-fast. An unknown configured
binding name SHALL fail at startup with the valid discovered options and the fix
named. Two providers claiming the same config name SHALL fail at startup with
the conflict and the fix named. Neither case SHALL silently fall back.
"Startup" means before any stage runs.
<!-- implements FR5, FR8, NFR-R1 of open-adapter-binding-registry -->
<!-- implements UX1 of open-adapter-binding-registry -->

#### Scenario: Unknown binding name lists discovered options
- **WHEN** `factory.bindings.*` names a binding no provider contributes
- **THEN** startup fails with an error listing the discovered binding names

#### Scenario: Duplicate config name fails with the conflict named
- **WHEN** two discovered providers report the same config name
- **THEN** startup fails with an error naming the conflicting config name

### Requirement: Host and container migrated to the registry
`HOST` SHALL be contributed by a provider in `:sandbox:core` and `CONTAINER` by a
provider in `:sandbox:docker`. Both SHALL keep their current config names
(`host`, `container`) and their current passports.
<!-- implements FR3 of open-adapter-binding-registry -->

#### Scenario: Host binding is contributed from core
- **WHEN** the registry is built with only `:sandbox:core` on the classpath
- **THEN** the `host` binding is present with the no-isolation passport

#### Scenario: Container binding is contributed from the docker module
- **WHEN** `:sandbox:docker` is on the classpath
- **THEN** the `container` binding is present with the container passport

### Requirement: Container-by-default preserved; absent module fails fast
With no operator binding configured, resolution SHALL yield the `container`
binding through the registry, resolved eagerly. If the container binding's
module is absent, startup SHALL fail fast naming the discovered options and the
ways out (restore the docker module, or explicitly bind `host`) — it SHALL never
silently fall back to the host binding, and it SHALL fail even when every stage
explicitly binds `host` (the declared default is unsatisfiable in that
distribution).
<!-- implements FR4 of open-adapter-binding-registry -->
<!-- implements UX2 of open-adapter-binding-registry -->

#### Scenario: Unset default resolves to container
- **WHEN** `factory.bindings.default` is unset and `:sandbox:docker` is present
- **THEN** a stage with no override binds the `container` binding

#### Scenario: Absent container module fails fast, never host
- **WHEN** `factory.bindings.default` is unset and the container binding is absent
- **THEN** startup fails with an error naming the discovered bindings and the
  ways out
- **AND** it does not fall back to the `host` binding

#### Scenario: Eager default — explicit host bindings do not mask the absence
- **WHEN** `factory.bindings.default` is unset, the container binding is absent,
  and every stage explicitly binds `host`
- **THEN** startup still fails naming the discovered options and the fix

### Requirement: Reconciliation contract preserved by the registry
Opening the registry SHALL NOT change reconciliation: the operator binds, the repo
may only tighten, and an unmet need SHALL be refused fail-closed against the
bound binding's registry-resolved passport, exactly as before.
<!-- implements FR6, FR9 of open-adapter-binding-registry -->

#### Scenario: Unmet need is refused fail-closed
- **WHEN** a stage declares a sandbox need the bound binding's passport does not
  satisfy
- **THEN** the factory refuses with the unmet need named, before the stage runs

#### Scenario: Existing execution-environment specs pass with assertions unchanged
- **WHEN** the existing execution-environment specs run against the registry-backed
  bindings
- **THEN** every behavioral assertion passes unchanged, with edits confined to
  construction sites that named the removed enum constants

### Requirement: Discovered bindings are observable at startup
At startup the factory SHALL report the discovered bindings through the same
provider-discovery report as the other ports — config name, provider class,
originating jar, and passport summary — before any stage runs, so an operator
can see exactly what the classpath loaded.
<!-- implements NFR-O1 of open-adapter-binding-registry -->
<!-- implements UX3 of open-adapter-binding-registry -->

#### Scenario: Startup reports the discovered bindings with their origin
- **WHEN** the factory starts with `:sandbox:core` and `:sandbox:docker` present
- **THEN** the report lists `host` and `container` with their isolation levels
  and the artifact each provider was loaded from
