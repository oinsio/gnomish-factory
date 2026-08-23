## MODIFIED Requirements

### Requirement: Layered Gradle module tree
The build SHALL be organized into layered Gradle modules by hexagonal layer:
`domain`, `subprocess`, `gitobjects`, `gnomish-plugin-api`, `application`, one
or more `adapters` modules, `sandbox` modules, and `bootstrap`.
<!-- implements FR9 of bound-subprocess-commands; originally FR1 of split-into-modules -->

#### Scenario: Modules resolve as distinct Gradle projects
- **WHEN** `./gradlew projects` is run
- **THEN** `:domain`, `:subprocess`, `:gitobjects`, `:gnomish-plugin-api`,
  `:application`, the adapter module(s), `:sandbox:core`, `:sandbox:docker`,
  and `:bootstrap` each appear as a separate project
- **AND** no production Java class remains in the former single root module

### Requirement: Enforced acyclic dependency direction
The module dependency direction SHALL be acyclic and enforced by the build:
`domain` and `subprocess` depend on nothing internal; `gitobjects` depends only
on `subprocess`; `gnomish-plugin-api` depends only on `domain`; `:sandbox:core`
depends only on `domain` / `gitobjects`; `application` depends only on
`domain`, `subprocess`, `gitobjects`, `gnomish-plugin-api`, and
`:sandbox:core`; each adapter module depends on `gnomish-plugin-api` and
`application` (plus `subprocess` where it launches OS processes,
`:sandbox:core` where it bridges to the execution environment, and a sandbox
backend module where it drives that backend) but never on a sibling adapter's
internals — with one declared exception: `:adapters:agent` depends on the
coarse `:adapters` remainder for the shared pipeline-law and briefing packages,
narrowed to exactly those packages by a named ArchUnit rule; sandbox backend
modules depend on `:sandbox:core` and `subprocess`, plus `application` where
the backend realizes an application-owned port; no production module depends on
the test-fixtures module; `bootstrap` is the only module that wires adapters
together and the only one that reaches every adapter. `subprocess` SHALL never
acquire a dependency — its emptiness is what keeps `gitobjects`
domain-independent while depending on it.
<!-- implements FR9, NFR-S3 of bound-subprocess-commands; originally FR2 of split-into-modules -->

#### Scenario: Port-layer modules stay below the adapters
- **WHEN** the boundary rules run against `:sandbox:core` and
  `:gnomish-plugin-api`
- **THEN** neither depends on `application`, any adapter module, any sandbox
  backend module, or `bootstrap`

#### Scenario: Adapter importing a sibling adapter's internals fails the build
- **WHEN** an adapter module declares or imports a sibling adapter's internal type
- **THEN** the build fails: an undeclared sibling's types are absent from the
  compile classpath, so the import fails compilation
- **AND** a declared sibling dependency fails the module-layering gate naming
  the offending edge
- **AND** reach into the coarse `:adapters` remainder beyond `:adapters:agent`'s
  two declared packages fails the named ArchUnit rule

#### Scenario: Domain stays free of upper layers
- **WHEN** the boundary rules run against `:domain`
- **THEN** no dependency on `application`, any adapter, or `bootstrap` is present

#### Scenario: The subprocess leaf stays empty of dependencies
- **WHEN** the dependency gates run against `:subprocess`
- **THEN** it declares no internal module, framework, or logging dependency
