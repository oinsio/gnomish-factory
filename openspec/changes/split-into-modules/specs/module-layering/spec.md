## ADDED Requirements

### Requirement: Layered Gradle module tree
The build SHALL be organized into layered Gradle modules by hexagonal layer:
`domain`, `gitobjects`, `gnomish-plugin-api`, `application`, one or more
`adapters` modules, `sandbox` modules, and `bootstrap`.
<!-- implements FR1 of split-into-modules -->

#### Scenario: Modules resolve as distinct Gradle projects
- **WHEN** `./gradlew projects` is run
- **THEN** `:domain`, `:gitobjects`, `:gnomish-plugin-api`, `:application`, the
  adapter module(s), `:sandbox:core`, `:sandbox:docker`, and `:bootstrap` each
  appear as a separate project
- **AND** no production Java class remains in the former single root module

### Requirement: Enforced acyclic dependency direction
The module dependency direction SHALL be acyclic and enforced by the build:
`domain` and `gitobjects` depend on nothing internal; `gnomish-plugin-api`
depends only on `domain`; `:sandbox:core` depends only on `domain` /
`gitobjects`; `application` depends only on `domain`, `gitobjects`,
`gnomish-plugin-api`, and `:sandbox:core`; each adapter module depends on
`gnomish-plugin-api` and `application` (plus `:sandbox:core` where it bridges to
the execution environment) but never on a sibling adapter's internals; sandbox
backend modules depend on `:sandbox:core`; no production module depends on the
test-fixtures module; `bootstrap` is the only module that depends on adapters.
<!-- implements FR2 of split-into-modules -->

#### Scenario: Port-layer modules stay below the adapters
- **WHEN** the boundary rules run against `:sandbox:core` and
  `:gnomish-plugin-api`
- **THEN** neither depends on `application`, any adapter module, any sandbox
  backend module, or `bootstrap`

#### Scenario: Adapter importing a sibling adapter's internals fails the build
- **WHEN** an adapter module declares or imports a sibling adapter's internal type
- **THEN** `check` fails with a named dependency-direction / ArchUnit rule
  violation identifying the offending edge

#### Scenario: Domain stays free of upper layers
- **WHEN** the boundary rules run against `:domain`
- **THEN** no dependency on `application`, any adapter, or `bootstrap` is present

### Requirement: Composition root isolated in bootstrap
`app` SHALL be split into `application` (use cases and ports, adapter-free) and
`bootstrap` (the single composition root holding `@SpringBootApplication`,
`main()`, and all wiring); the `app` files that import adapter *implementations*
move into `bootstrap`, while files consuming only ports — including the
execution-environment port from `:sandbox:core` — stay in `application`. The
flat classpath is preserved: the split introduces no new runtime failure mode.
<!-- implements FR3, NFR-R1 of split-into-modules -->

#### Scenario: application module has no adapter imports
- **WHEN** the boundary rules run against `:application`
- **THEN** no import of any adapter module is present
- **AND** its uses of the execution environment go through the `:sandbox:core`
  port, not a backend module

#### Scenario: bootstrap is the only Spring scan root
- **WHEN** the application starts
- **THEN** component scanning is rooted in `bootstrap` only, and adapters are
  contributed via explicit configuration or factories, not cross-module scanning

#### Scenario: Startup wiring is unchanged by the split
- **WHEN** `bootstrap` starts the factory with the same configuration as the
  pre-split monolith
- **THEN** the same adapter set is wired and startup succeeds on the flat
  classpath, with no new wiring failure mode

### Requirement: Behavior-preserving split
The module split SHALL be behavior-preserving: every existing capability spec and
its tests pass with no changes to the specs.
<!-- implements FR9 of split-into-modules -->

#### Scenario: Full suite passes unchanged after the split
- **WHEN** the full test suite runs after the modules are in place
- **THEN** all pre-existing specs pass
- **AND** no pre-existing spec file was edited to make them pass

### Requirement: Vertical adapter modules per technology
In the second pass, adapters SHALL be split vertically per technology:
`:adapters:github` bundles the shared github HTTP core with the github tracker
and github check adapters as one vendor module; `:adapters:git` and
`:adapters:agent` get their own modules; the in-memory tracker stays in-tree as
the reference/test double and the small adapters stay in the coarse remainder
module.
<!-- implements FR10 of split-into-modules -->

#### Scenario: The github vendor bundle is one module
- **WHEN** `./gradlew projects` is run after the vertical split
- **THEN** `:adapters:github`, `:adapters:git`, and `:adapters:agent` appear as
  separate projects
- **AND** the shared github HTTP core is an internal package of
  `:adapters:github`, not a module other adapters can depend on

#### Scenario: Sibling isolation holds after the vertical split
- **WHEN** the boundary rules re-run after the vertical split
- **THEN** no adapter module imports a sibling adapter's internals
- **AND** the full suite passes unchanged
