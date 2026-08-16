## ADDED Requirements

### Requirement: Bindings are discovered, not enumerated in core
The set of adapter bindings SHALL be assembled at startup by discovering
first-party `SandboxBindingProvider` contributions, not from a core enum. Each
provider SHALL expose its config name and its fixed `CapabilityPassport` without
instantiating a live backend adapter, so binding enumeration and planning-time
reconciliation stay adapter-instance-free (no docker daemon touched).
<!-- implements FR1, FR2 of open-adapter-binding-registry -->

#### Scenario: A backend module contributes a binding without a core edit
- **WHEN** a first-party module ships a `SandboxBindingProvider` on the classpath
- **THEN** its binding appears in the registry with no edit to `:sandbox:core`
  binding source

#### Scenario: Passport enumerated without a running backend
- **WHEN** the registry is built and every binding's `configName()` and
  `passport()` are read
- **THEN** no live backend adapter is instantiated and no docker daemon is
  contacted

### Requirement: First-party trust boundary gates discovery
Discovery SHALL be gated by a core-owned allowlist of trusted first-party binding
ids. A discovered provider whose config name is not on the allowlist SHALL be
rejected fail-fast at startup with a named error; the sandbox passport is trusted
only because its provider is first-party.
<!-- implements FR7 of open-adapter-binding-registry -->

#### Scenario: Allowlisted provider is accepted
- **WHEN** a discovered provider's config name is on the core allowlist
- **THEN** its binding is registered

#### Scenario: Non-allowlisted provider is rejected
- **WHEN** a discovered provider's config name is not on the core allowlist
- **THEN** startup fails with a named error identifying the untrusted binding id
- **AND** the binding is not registered

### Requirement: Deterministic, fail-fast binding resolution
Binding resolution SHALL be deterministic and fail-fast. An unknown configured
binding name SHALL fail at startup with the valid discovered options named. Two
providers claiming the same config name SHALL fail at startup with the conflict
named. Neither case SHALL silently fall back.
<!-- implements FR5, FR8 of open-adapter-binding-registry -->

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
binding through the registry. If the container binding's module is absent, startup
SHALL fail fast naming the discovered options — it SHALL never silently fall back
to the host binding.
<!-- implements FR4 of open-adapter-binding-registry -->

#### Scenario: Unset default resolves to container
- **WHEN** `factory.bindings.default` is unset and `:sandbox:docker` is present
- **THEN** a stage with no override binds the `container` binding

#### Scenario: Absent container module fails fast, never host
- **WHEN** `factory.bindings.default` is unset and the container binding is absent
- **THEN** startup fails with an error naming the discovered bindings
- **AND** it does not fall back to the `host` binding

### Requirement: Reconciliation contract preserved by the registry
Opening the registry SHALL NOT change reconciliation: the operator binds, the repo
may only tighten, and an unmet need SHALL be refused fail-closed against the
bound binding's registry-resolved passport, exactly as before.
<!-- implements FR6, FR9 of open-adapter-binding-registry -->

#### Scenario: Unmet need is refused fail-closed
- **WHEN** a stage declares a sandbox need the bound binding's passport does not
  satisfy
- **THEN** the factory refuses with the unmet need named, before the stage runs

#### Scenario: Existing execution-environment specs pass unchanged
- **WHEN** the existing execution-environment specs run against the registry-backed
  bindings
- **THEN** they pass with no changes to the spec files

### Requirement: Discovered bindings are observable at startup
At startup the factory SHALL log the discovered bindings as config name →
isolation summary, so an operator can confirm which backend modules are active
before running a stage.
<!-- implements NFR-O1 of open-adapter-binding-registry -->

#### Scenario: Startup logs the discovered bindings
- **WHEN** the factory starts with `:sandbox:core` and `:sandbox:docker` present
- **THEN** the log lists `host` and `container` with their isolation levels
