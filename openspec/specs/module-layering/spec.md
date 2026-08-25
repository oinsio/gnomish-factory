# module-layering

## Purpose

Defines the layered Gradle module tree of the factory — which modules exist, the enforced acyclic dependency direction between them, the isolation of the composition root in `bootstrap`, the inversion of use-case dependencies onto ports, and the vertical per-technology adapter modules — together with the requirement that the split preserves behavior.

## Requirements

### Requirement: Layered Gradle module tree
The build SHALL be organized into layered Gradle modules by hexagonal layer:
`domain`, `subprocess`, `gitobjects`, `gnomish-plugin-api`, `application`, one
or more `adapters` modules, `sandbox` modules, and `bootstrap`.
<!-- implements FR1 of split-into-modules; implements FR9 of bound-subprocess-commands -->

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
the backend realizes an application-owned port; no production module depends
on the test-fixtures module; `bootstrap` is the only module that wires
adapters together and the only one that reaches every adapter. `subprocess`
SHALL never acquire a dependency — its emptiness is what keeps `gitobjects`
domain-independent while depending on it.
<!-- implements FR2 of split-into-modules; implements FR9, NFR-S3 of bound-subprocess-commands -->

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

### Requirement: Composition root isolated in bootstrap
`app` SHALL be split into `application` (use cases and ports, adapter-free) and
`bootstrap` (the single composition root holding `@SpringBootApplication`,
`main()`, and all wiring). The split SHALL be by *role*: composition — `main()`,
`@Configuration`, and the assembly/factory classes whose job is to instantiate
and connect adapters — belongs to `bootstrap`; use-case logic stays in
`application`, with its adapter references inverted. The flat classpath is
preserved: the split introduces no new runtime failure mode.
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

### Requirement: Use-case dependencies inverted onto ports
`application` SHALL contain no import of an adapter implementation. Where a use
case reaches one, the dependency SHALL be inverted either by relocating the
referenced type — when it is a port interface or a pure value/utility type
merely misfiled under `adapter.*`, moved with its signature unchanged — or by
introducing a port interface owned by `application` (or `domain`, where the
engine already consumes it) that `bootstrap` binds to the concrete adapter. An
inverted seam SHALL express the smallest capability the use case needs, not a
mirror of the adapter's class surface.
<!-- implements FR12 of split-into-modules -->

#### Scenario: A use case reaches its collaborator through a port
- **WHEN** a use case in `application` needs a git subprocess, the console,
  pipeline loading, a workspace, a check runner, or the container-availability
  probe
- **THEN** it declares a port interface owned by `application` or `domain`
- **AND** the concrete adapter satisfying it is supplied by `bootstrap`

#### Scenario: A relocated port keeps its signature
- **WHEN** a type misfiled under `adapter.*` is moved to its correct layer
- **THEN** only its package declaration and its importers' import lines change
- **AND** no method signature, field, or behavior of that type changes

#### Scenario: An adapter import in application fails the build
- **WHEN** a class under `application` imports any `..adapter..` type
- **THEN** `check` fails with the named ArchUnit rule identifying the offending
  class and the imported adapter type

### Requirement: Behavior-preserving split
The module split SHALL be behavior-preserving: every existing capability spec
holds unchanged and every pre-existing Spock spec passes. Spec-file edits SHALL
be confined to the collaborator-construction sites forced by the port inversion.
<!-- implements FR9 of split-into-modules -->

#### Scenario: Full suite passes after the split
- **WHEN** the full test suite runs after the modules are in place
- **THEN** all pre-existing specs pass

#### Scenario: Spec edits are confined to construction sites
- **WHEN** the diff over the test sources is reviewed
- **THEN** it changes only imports, constructor arguments, and the test doubles
  standing in for a newly introduced port
- **AND** no scenario name, `given`/`when`/`then` block, or assertion is changed

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
