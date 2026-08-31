# module-layering — delta for add-subprocess-access-log

Layered on the module tree as modified by `harden-logging-observability`
(sequenced before this change), which introduces the `:logtext` leaf this
change extends.

## MODIFIED Requirements

### Requirement: Layered Gradle module tree
The build SHALL be organized into layered Gradle modules by hexagonal layer:
`domain`, `subprocess`, `atomicfile`, `logtext`, `gitobjects`,
`gnomish-plugin-api`, `application`, one or more `adapters` modules, `sandbox`
modules, and `bootstrap`. `atomicfile` is the dependency-free leaf holding the
shared atomic file writer (temp file + atomic rename) consumed by the host-side
`.gnomish-task/` writers and the dashboard writer; the container-side
persisters reach durability at commit granularity — round state committed
in-box, lifecycle commits built from bare objects — so neither consumes the
writer. `logtext` is the logging-support leaf holding the untrusted-text
sanitizer, the repeat suppressor, the MDC-propagation helper, and the
subprocess access-log emitter with its argv redactor — the pieces every
layer's log emitters share, so the access-record format and redaction have
exactly one owner reachable from every spawn family.
<!-- implements FR9 of bound-subprocess-commands; originally FR1 of split-into-modules -->
<!-- implements FR5 of harden-task-branch-contract -->
<!-- implements FR4, FR6, FR8 of harden-logging-observability -->
<!-- implements FR2, FR3 of add-subprocess-access-log -->

#### Scenario: Modules resolve as distinct Gradle projects
- **WHEN** `./gradlew projects` is run
- **THEN** `:domain`, `:subprocess`, `:atomicfile`, `:logtext`, `:gitobjects`,
  `:gnomish-plugin-api`, `:application`, the adapter module(s),
  `:sandbox:core`, `:sandbox:docker`, and `:bootstrap` each appear as a
  separate project
- **AND** no production Java class remains in the former single root module

### Requirement: Enforced acyclic dependency direction
The module dependency direction SHALL be acyclic and enforced by the build:
`domain`, `subprocess`, `atomicfile`, and `logtext` depend on nothing
internal; `gitobjects` depends only on `subprocess`; `gnomish-plugin-api`
depends only on `domain`; `:sandbox:core` depends only on `domain` /
`gitobjects`; `application` depends only on `domain`, `subprocess`,
`atomicfile`, `logtext`, `gitobjects`, `gnomish-plugin-api`, and
`:sandbox:core`; each adapter module depends on `gnomish-plugin-api` and
`application` (plus `subprocess` where it launches OS processes, `atomicfile`
where it writes factory-owned files atomically, `logtext` where it logs
untrusted text or emits subprocess access-log records, `:sandbox:core` where
it bridges to the execution environment, and a sandbox backend module where
it drives that backend) but never on a sibling adapter's internals — with one
declared exception: `:adapters:agent` depends on the coarse `:adapters`
remainder for the shared pipeline-law and briefing packages, narrowed to
exactly those packages by a named ArchUnit rule; sandbox backend modules
depend on `:sandbox:core` and `subprocess`, plus `logtext` where they log
untrusted text or emit subprocess access-log records, plus `application`
where the backend realizes an application-owned port; no production module
depends on the test-fixtures module; `bootstrap` is the only module that
wires adapters together and the only one that reaches every adapter.
`subprocess` and `atomicfile` SHALL never acquire a dependency — their
emptiness is what keeps their consumers free of transitive coupling.
`logtext` SHALL declare no internal module dependency and at most the logging
API (`slf4j-api`) — never an implementation, framework, or any other external
library. `gitobjects` SHALL likewise acquire no dependency for access-log
emission: it reports execution facts through a JDK-only observer hook on its
own public API, wired to the emitter by `bootstrap`, defaulting to a no-op.
<!-- implements FR9, NFR-S3 of bound-subprocess-commands; originally FR2 of split-into-modules -->
<!-- implements FR5 of harden-task-branch-contract -->
<!-- implements FR4, FR6, FR8 of harden-logging-observability -->
<!-- implements FR2, FR3 of add-subprocess-access-log -->

#### Scenario: A vendor adapter reaches the tenure record through the contract
- **WHEN** a vendor adapter module stamps its writes with the claim epoch of
  the tenure it is writing under
- **THEN** it reads that tenure through the published contract module, not
  through `application` — the read-only seam lives beside the tracker port so
  a bundle a third party could build reaches it, and no adapter keeps a
  tenure record of its own

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

#### Scenario: The atomicfile leaf stays empty of dependencies
- **WHEN** the dependency gates run against `:atomicfile`
- **THEN** it declares no internal module or framework dependency

#### Scenario: The logtext leaf carries only the logging API
- **WHEN** the dependency gates run against `:logtext`
- **THEN** it declares no internal module dependency and no external
  dependency beyond `slf4j-api`

#### Scenario: Host-side writers and the dashboard writer share one atomic writer
- **WHEN** the host persister and the dashboard writer perform an atomic file
  write
- **THEN** each consumes the `:atomicfile` writer — no module keeps a private
  copy of the temp-file-plus-rename discipline — while the container-side
  persisters reach durability at commit granularity and consume no host
  filesystem writer

#### Scenario: Access-log emission adds no edge to gitobjects
- **WHEN** the dependency gates run against `:gitobjects` after the access
  log lands
- **THEN** its production dependency set is unchanged (`:subprocess` only),
  and its access-record reporting reaches the emitter only through the
  caller-supplied observer hook
