## ADDED Requirements

### Requirement: Enforced acyclic dependency direction
The module dependency direction SHALL be acyclic and enforced by the build: each
module SHALL declare only the internal edges its layer permits, and the layering
gate SHALL fail the build naming any declared edge outside that set.
**Implements**: FR9, NFR-S3 of bound-subprocess-commands; FR2 of split-into-modules; FR5 of harden-task-branch-contract; FR4, FR6, FR8 of harden-logging-observability; FR2, FR3 of add-subprocess-access-log; FR10, NFR-S3 of add-base-ref-resolution; FR6, FR7, FR11, NFR-S1 of split-logtext-leaves; FR2 of own-git-transfer-argv
**Notes**: "Depends only on" in the scenarios describes declared edges; a module's layering allowlist describes reach, since the gate walks the transitive production graph.
**Notes**: The dependency-free leafs stay empty because any edge added to one is pushed into every consumer: the emptiness of `subprocess` and `atomicfile` keeps their consumers free of transitive coupling, the domain reaches the event catalog and the published contract reaches both text leafs, and the git adapter and a sandbox backend reach `gittransfer` from opposite sides of the layering. `baseref` takes and returns plain values, which constructively keeps the resolution policy ignorant of subprocesses, trackers and configuration formats.

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

#### Scenario: Domain reaches only JDK-only leafs
- **WHEN** the layering gate runs against `:domain`
- **THEN** every project in its allowlist — `:operatorevent` and
  `:untrustedtext` today — declares no
  internal module and no external library, which is the whole admission rule:
  the next leaf the domain needs is admitted by adding the edge that uses it,
  not by amending this requirement
- **AND** a project in that allowlist that acquired an internal edge or an
  external artifact fails the build naming it

#### Scenario: A transitive leaf is listed by every module that reaches it
- **WHEN** the layering gate runs against a module whose production classpath
  reaches a leaf without declaring the edge — `:sandbox:core`,
  `:gnomish-plugin-api` and `:bootstrap` reaching `:operatorevent` through
  `:domain`, the sample plugin reaching `:operatorevent` and `:untrustedtext`
  through `:gnomish-plugin-api`, `:adapters`, `:adapters:agent` and
  `:bootstrap` reaching `:gittransfer` through `:sandbox:docker` or
  `:adapters:git`, and `:sandbox:docker`, `:adapters`, `:adapters:git` and
  `:adapters:agent` reaching `:operatorconfig` through `:application` or
  `:sandbox:core`
- **THEN** the gate passes only because that module's allowlist names the
  leaf; removing the entry fails the gate naming the leaf as an unlisted reach

#### Scenario: The subprocess leaf stays empty of dependencies
- **WHEN** the dependency gates run against `:subprocess`
- **THEN** it declares no internal module, framework, or logging dependency

#### Scenario: The atomicfile leaf stays empty of dependencies
- **WHEN** the dependency gates run against `:atomicfile`
- **THEN** it declares no internal module or framework dependency

#### Scenario: The untrustedtext and operatorevent leafs stay empty of dependencies
- **WHEN** the dependency gates run against `:untrustedtext` and
  `:operatorevent`
- **THEN** each declares no internal module dependency and no external
  dependency at all — the layering gate lists no allowed project for either

#### Scenario: The gittransfer leaf stays empty of dependencies
- **WHEN** the dependency gates run against `:gittransfer`
- **THEN** it declares no internal module dependency and no external
  dependency at all — the layering gate lists no allowed project for it —
  and both `:adapters:git` and `:sandbox:docker` declare it

#### Scenario: The logtext leaf carries only the logging API
- **WHEN** the dependency gates run against `:logtext`
- **THEN** it declares `:untrustedtext` as its only internal dependency and no
  external dependency beyond `slf4j-api`

#### Scenario: The baseref leaf depends only on the untrustedtext leaf
- **WHEN** the dependency gates run against `:baseref`
- **THEN** it declares no internal module dependency but the dependency-free
  `:untrustedtext` leaf, and no external dependency at all — the layering
  gate lists `:untrustedtext` as its one allowed project

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

#### Scenario: The dependency-free leafs depend on nothing internal
- **WHEN** the layering gate runs against `:subprocess`, `:atomicfile`, `:untrustedtext`, `:operatorevent`, `:operatorconfig` and `:gittransfer`
- **THEN** none of them declares an internal module dependency, now or in any later change

#### Scenario: The domain carries no logging, framework, serialization or filesystem module
- **WHEN** the layering gate runs against `:domain`
- **THEN** it depends only on JDK-only leafs — today exactly `:operatorevent` and `:untrustedtext` — and never on a module that carries a logging API, a framework, a serialization library or the filesystem

#### Scenario: The plugin contract depends only on domain and the text leaf
- **WHEN** the layering gate runs against `:gnomish-plugin-api`
- **THEN** its only internal edges are `:domain` and the `:untrustedtext` leaf its findings sanitizer delegates to

#### Scenario: The sandbox core depends only on domain and two leafs
- **WHEN** the layering gate runs against `:sandbox:core`
- **THEN** its only internal edges are `:domain`, the `:untrustedtext` leaf its exec results carry, and the `:operatorconfig` leaf its configuration records name

#### Scenario: The application layer declares only its permitted edges
- **WHEN** the layering gate runs against `:application`
- **THEN** its only internal edges are `:domain`, `:subprocess`, `:atomicfile`, `:untrustedtext`, `:operatorevent`, `:operatorconfig`, `:logtext`, `:gitobjects`, `:baseref`, `:gnomish-plugin-api` and `:sandbox:core`

#### Scenario: An adapter module declares only its permitted edges
- **WHEN** the layering gate runs against an adapter module
- **THEN** it depends on `:domain` and `:untrustedtext`; on `:application` unless it is the GitHub vendor bundle, which builds on the published contract alone; on `:gnomish-plugin-api` unless it is `:adapters:agent`, which reaches the contract through `:application`; plus `:gitobjects` where it reads git objects, `:subprocess` where it launches OS processes, `:atomicfile` where it writes factory-owned files atomically, `:logtext` where it logs untrusted text or emits subprocess access-log records, `:operatorevent` where it emits a coded operator line, `:baseref` where it maps configuration into the resolution policy's value types, `:gittransfer` where it runs a git transfer, `:sandbox:core` where it bridges to the execution environment, and a sandbox backend module where it drives that backend
- **AND** its only edge to a sibling adapter is the one declared exception, `:adapters:agent` on the coarse `:adapters` remainder

#### Scenario: A sandbox backend declares only its permitted edges
- **WHEN** the layering gate runs against a sandbox backend module
- **THEN** it depends on `:sandbox:core`, `:domain`, `:subprocess` and `:untrustedtext`, plus `:atomicfile` for its non-atomic-write marker, plus `:logtext` and `:operatorevent` where it logs untrusted text or emits subprocess access-log records or coded operator lines, plus `:gittransfer` where it renders a git transfer into a helper script, plus `:application` where the backend realizes an application-owned port

#### Scenario: No production module depends on the test fixtures
- **WHEN** the layering gate runs against any production module
- **THEN** none declares the test-fixtures module as a production dependency

#### Scenario: Only bootstrap reaches every adapter
- **WHEN** the module dependencies are inspected
- **THEN** `bootstrap` is the only module that wires adapters together and the only one that reaches every adapter module

#### Scenario: A leaf edge declared ahead of its use fails the gate
- **WHEN** a module declares a leaf edge that none of its classes uses yet, such as an edge for a type that does not exist yet
- **THEN** the dependency-analysis gate reports the unused declaration and the build fails
- **AND** the edge is declared in the change whose code uses it

#### Scenario: A reach entry names the edge it arrives through
- **WHEN** a module's production classpath reaches `:untrustedtext`, `:operatorevent`, `:operatorconfig` or `:gittransfer` without declaring the edge
- **THEN** its layering allowlist lists the leaf, and outside the composition root `:bootstrap` with a comment naming the edge it arrives through — `:domain`, `:gnomish-plugin-api`, `:application`, `:sandbox:core`, `:logtext`, `:sandbox:docker`, `:adapters:git`, or a direct declaration

#### Scenario: The gitobjects observer hook defaults to a no-op
- **WHEN** `:gitobjects` runs a git command and no observer was supplied
- **THEN** it reports its execution facts to a no-op through its own JDK-only observer hook and declares no dependency for access-log emission
- **AND** in the shipped composition `bootstrap` wires that hook to the access-log emitter
