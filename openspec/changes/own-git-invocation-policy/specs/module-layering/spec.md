# module-layering — delta for own-git-invocation-policy

Written over the stable `openspec/specs/module-layering/spec.md`. The active
`add-subprocess-access-log` MODIFIES the same two requirements and is
sequenced after this change; its proposal already requires re-layering its
delta on the stable spec as it then stands, which carries the
`gittransfer`/`gitobjects` sentences below.

## MODIFIED Requirements

### Requirement: Layered Gradle module tree
The build SHALL be organized into layered Gradle modules by hexagonal layer:
`domain`, `subprocess`, `atomicfile`, `untrustedtext`, `operatorevent`,
`logtext`, `gitobjects`, `gittransfer`, `baseref`, `gnomish-plugin-api`,
`application`, one or more `adapters` modules, `sandbox` modules, and
`bootstrap`. `atomicfile`
is the dependency-free leaf holding the shared atomic file writer (temp file +
atomic rename) consumed by the host-side `.gnomish-task/` writers and the
dashboard writer; the container-side persisters reach durability at commit
granularity — round state committed in-box, lifecycle commits built from bare
objects — so neither consumes the writer. `untrustedtext` is the JDK-only leaf
holding the untrusted-text primitives — the one character-class table,
stripping, tail and record caps, newline flattening, and the console notation
— so text safety has exactly one owner, sitting low enough that the domain,
the published contract module and every layer above them can reach it; the
published contract and the logging leaf are its first two consumers. `operatorevent` is the
JDK-only leaf holding the operator-event catalog, so every emitter including
the domain's renders its `[GFnnn]` head from the constant. `logtext` is the
logging-support leaf holding the log-line sanitizer facade over
`untrustedtext`, the repeat suppressor, the MDC-propagation helper, the
shutdown-phase flag, and the subprocess access-log emitter with its argv
redactor — the pieces every layer's log emitters share, so the access-record
format and redaction have exactly one owner reachable from every spawn
family. `gittransfer` is the
JDK-only leaf holding the git invocation policy — the one command-scope
configuration set and stripped environment every factory git process carries —
and, layered on it, the git transfer owner — the closed set of transfer
source kinds, the common deny-by-default side-effect set, the per-source
protocol allowlist and configuration isolation, and the git version floor —
as pure values (an argument list and an environment map) with no subprocess
inside, so the git adapter that runs a transfer and the sandbox backend that
renders the seed clone into its helper script build it from one owner
although they share no other edge, and both git launchers — the factory git
runner and the git-object library — take their invocation policy from it. `baseref` is the base-resolution leaf holding the pure resolution
policy — allowed-bases pattern grammar, designator validation against the
allowed bases, source priority, and the decision value types — a function
from values to a decision, with no subprocess, no port, and no factory type
inside; extractability is a declared property.
<!-- implements FR9 of bound-subprocess-commands; originally FR1 of split-into-modules -->
<!-- implements FR5 of harden-task-branch-contract -->
<!-- implements FR4, FR6, FR8 of harden-logging-observability -->
<!-- implements FR2, FR3 of add-subprocess-access-log -->
<!-- implements FR10 of add-base-ref-resolution -->
<!-- implements FR1, FR4 of split-logtext-leaves -->
<!-- implements FR2 of own-git-transfer-argv -->
<!-- implements FR1, FR7 of own-git-invocation-policy -->

#### Scenario: Modules resolve as distinct Gradle projects
- **WHEN** `./gradlew projects` is run
- **THEN** `:domain`, `:subprocess`, `:atomicfile`, `:untrustedtext`,
  `:operatorevent`, `:logtext`, `:gitobjects`, `:gittransfer`, `:baseref`,
  `:gnomish-plugin-api`, `:application`, the adapter module(s),
  `:sandbox:core`, `:sandbox:docker`, and `:bootstrap` each appear as a
  separate project
- **AND** no production Java class remains in the former single root module

#### Scenario: Two media build one transfer from one leaf
- **WHEN** the git adapter builds a fetch and the sandbox backend builds the
  seed clone script
- **THEN** each takes its argument list and environment from `:gittransfer`,
  and neither module holds a transfer flag of its own

#### Scenario: Both git launchers take one invocation policy
- **WHEN** the factory git runner and the git-object library each start a git
  process
- **THEN** each takes its command-scope configuration and environment
  stripping from `:gittransfer`, and neither holds a policy key of its own


### Requirement: Enforced acyclic dependency direction
The module dependency direction SHALL be acyclic and enforced by the build:
`subprocess`, `atomicfile`, `untrustedtext`, `operatorevent`, `gittransfer`,
and `baseref` depend on nothing internal; `domain` depends only on JDK-only leafs — today
exactly `operatorevent` — and never on a module that carries a logging API, a
framework, a serialization library or the filesystem; `logtext` depends only on
`untrustedtext`; `gitobjects` depends only on `subprocess` and on the `gittransfer` leaf
that owns its invocation policy;
`gnomish-plugin-api` depends only on `domain` and on the `untrustedtext` leaf
its findings sanitizer delegates to; `:sandbox:core` depends only on `domain` /
`gitobjects`; `application` depends only on `domain`, `subprocess`,
`atomicfile`, `untrustedtext`, `operatorevent`, `logtext`, `gitobjects`,
`baseref`, `gnomish-plugin-api`, and `:sandbox:core`; each adapter module
depends on `gnomish-plugin-api` and `application` (plus `subprocess` where it
launches OS processes, `atomicfile` where it writes factory-owned files
atomically, `logtext` where it logs untrusted text or emits subprocess
access-log records, `operatorevent` where it emits a coded operator line,
`baseref` where it maps configuration into the resolution policy's value
types, `gittransfer` where it runs a git transfer, `:sandbox:core` where it
bridges to the execution environment, and a sandbox backend module where it
drives that backend) but never on a sibling
adapter's internals — with one declared exception: `:adapters:agent` depends
on the coarse `:adapters` remainder for the shared pipeline-law and briefing
packages, narrowed to exactly those packages by a named ArchUnit rule;
sandbox backend modules depend on `:sandbox:core` and `subprocess`, plus
`logtext` and `operatorevent` where they log untrusted text, emit subprocess
access-log records or coded operator lines, plus `gittransfer` where they
render a git transfer into a helper script, plus `application` where the
backend realizes an application-owned port; no production module depends on
the test-fixtures module; `bootstrap` is the only module that wires adapters
together and the only one that reaches every adapter. `subprocess` and
`atomicfile` SHALL never acquire a dependency — their emptiness is what keeps
their consumers free of transitive coupling. `untrustedtext` and
`operatorevent` SHALL likewise never acquire a dependency: the domain reaches
the catalog and the published contract reaches both, so any edge added there is
pushed into both. `gittransfer` SHALL likewise never acquire a dependency: the
git adapter and a sandbox backend reach it from opposite sides of the
layering, so any edge added there is pushed into both; the git-object library
reaches it for the invocation policy alone, and extracting that library means
moving it with its two JDK-only leafs. A module SHALL declare
a leaf edge in the change that uses it
and not ahead of one — the dependency-analysis gate reports an unused
declaration, and an edge declared for a type that does not exist yet is exactly
that.
Because the layering gate walks the transitive production graph, every module
whose classpath reaches `untrustedtext`, `operatorevent`, or `gittransfer` —
through `domain`, through `logtext`, through `:sandbox:docker` or
`:adapters:git`, or directly — SHALL list it in its own allowlist with a
comment naming the edge it arrives through; "depends only on" above describes
declared edges, and the allowlist describes reach.
`logtext` SHALL declare no internal module dependency beyond `untrustedtext`
and at most the logging API (`slf4j-api`) — never an implementation,
framework, or any other external library. `baseref` SHALL declare no
internal module dependency and no external library — not Jackson, not slf4j:
its inputs and outputs are plain values, which is what constructively
guarantees the resolution policy can know nothing of subprocesses, trackers,
or configuration formats. `gitobjects` SHALL likewise acquire no dependency
for access-log emission: it reports execution facts through a JDK-only
observer hook on its own public API, wired to the emitter by `bootstrap`,
defaulting to a no-op.
<!-- implements FR9, NFR-S3 of bound-subprocess-commands; originally FR2 of split-into-modules -->
<!-- implements FR5 of harden-task-branch-contract -->
<!-- implements FR4, FR6, FR8 of harden-logging-observability -->
<!-- implements FR2, FR3 of add-subprocess-access-log -->
<!-- implements FR10, NFR-S3 of add-base-ref-resolution -->
<!-- implements FR6, FR7, FR11, NFR-S1 of split-logtext-leaves -->
<!-- implements FR2 of own-git-transfer-argv -->
<!-- implements FR1 of own-git-invocation-policy -->

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
- **THEN** every project in its allowlist — `:operatorevent` today — declares no
  internal module and no external library, which is the whole admission rule:
  the next leaf the domain needs is admitted by adding the edge that uses it,
  not by amending this requirement
- **AND** a project in that allowlist that acquired an internal edge or an
  external artifact fails the build naming it

#### Scenario: A transitive leaf is listed by every module that reaches it
- **WHEN** the layering gate runs against a module whose production classpath
  reaches `:untrustedtext` or `:operatorevent` without declaring the edge —
  `:sandbox:core` and `:gnomish-plugin-api` reaching the catalog through
  `:domain`, the sample plugin reaching both through `:gnomish-plugin-api`,
  `:application` and the adapters reaching the text leaf through `:logtext`,
  `:adapters`, `:adapters:agent`, and `:bootstrap` reaching `:gittransfer`
  through `:sandbox:docker` or `:adapters:git`
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

#### Scenario: The baseref leaf stays empty of dependencies
- **WHEN** the dependency gates run against `:baseref`
- **THEN** it declares no internal module dependency and no external
  dependency at all — the layering gate lists no allowed project for it

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
