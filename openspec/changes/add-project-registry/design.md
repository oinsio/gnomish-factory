# Design: add-project-registry

## Context

Driven by FR1–FR12 of the proposal; motivation in proposal.md, "Why". What the
code does today:

- Operator paths are derived in three places: `FactoryPaths.underHome(user.home)`
  (worktrees root, serve home) from `ManualRunConfiguration`, `ObservabilityPaths`
  (`<home>/.gnomish/serve/<instance-name>`), and `logback-spring.xml`
  (`${GNOMISH_LOG_DIR:-${user.home}/.gnomish/logs}`).
- `FactoryPaths` is held by `ManualRunConfiguration`, `TrackerCommandConfiguration`,
  `StatusCommand`, `ServeRuntimeAssembly` and `DashboardCommand`; the
  `(homeDir, instanceName)` pair `ObservabilityPaths` resolves against is threaded
  into `ObservabilityAssembly`, `ServeAssembly`, `DashboardCommand`,
  `DashboardRenderCycle`, `DashboardWatchLoop`, `LedgerAggregator`,
  `SweepActionAggregator` and `RotatingLedgerAppender`.
- The project name under the worktrees root is the clone directory's last
  segment, computed three times: `TaskWorktreePath.resolve`,
  `WorktreeJanitor.projectName()` and `TaskWorktreeManager.ensureWorktree` — an
  undeclared triplet. The adapters take the clone and the worktrees root as an
  adjacent `(Path cloneDir, Path worktreesRoot)` pair (`GitTaskRepository`,
  `GitTaskStore.taskRepository`, `GitTaskWorktrees`, `WorktreeEnvironmentDisposal`).
  The sandbox sweep has a separate identity (`ProjectIdentity`,
  normalized-origin digest), out of scope (NG1).
- `factory.instance-name` (default `gnomish-factory` in `application.yaml`)
  seeds `InstanceId.generate` in `TrackerWiring`, `TakeCommand` and
  `ServeCommand`, and keys the serve directory read by `ObservabilityAssembly`
  and `DashboardCommand`.
- `--dir` becomes an absolute `Path` in one place,
  `ArgumentsParsingSupport.projectDir` / `requiredProjectDir`
  (`fix-operator-blockers`), called by the `Board`, `Dashboard`, `Run`, `Serve` and
  `Take` argument parsers (and by `status`/`usage` for the required form); it
  reads `ApplicationArguments`, which Spring Boot builds only after the
  environment is prepared.
- Configuration is plain Spring: bundled `application.yaml` plus command line,
  environment (relaxed binding) and system properties; unknown keys are ignored.
  No `EnvironmentPostProcessor` exists.
- `EnvFileSecretsProvider` resolves `N_FILE` then `N` from the process
  environment; every secret already goes through the `SecretsProvider` port.
- Spring Boot 4.1 runs `EnvironmentPostProcessor`s (listener order HIGHEST+10)
  before `LoggingApplicationListener` initializes Logback (HIGHEST+20), so a
  post-processor can decide the log file before the first file line. The
  interface is `org.springframework.boot.EnvironmentPostProcessor`; the older
  `org.springframework.boot.env.EnvironmentPostProcessor` is deprecated.
- Nothing in the application context exists while a post-processor runs: a
  failure raised there reaches no `ExitCodeExceptionMapper` bean (the exit code
  then comes only from an exception implementing `ExitCodeGenerator`) and no
  console bean. A post-processor may take the `ConfigurableBootstrapContext` in
  its constructor; the bootstrap context's close event exposes the application
  context before refresh, which is Boot's documented way to hand a value
  computed during environment preparation to the bean factory.
- Operator-facing failure lines have one owner, `RunExceptionReporting.calmLine`;
  `ReportedFailureExceptionReporter` (`fix-operator-blockers`) suppresses Spring's
  "Application run failed" record for every failure that owner classifies.
- Eight `:bootstrap` specs boot a `SpringApplication` in process
  (`FactoryApplicationSpec`, `FactoryEnvironmentOverrideSpec`, `LoggingLevelSpec`,
  `OperatorLogIsolationSpec`, `CommandExitSpec`, `ManualRunConfigurationSpec`,
  `ApplicationBeanInventorySpec`, `BootstrapScanRootSpec`);
  `FactoryEnvironmentOverrideSpec` asserts that `FACTORY_INSTANCE_NAME` overrides
  the bundled value — the behaviour FR7 removes.

## Decisions

**D1 — One layout owner: `FactoryHome` and `ProjectLayout`.** `FactoryHome`
(`:application`) is built once from `GNOMISH_HOME` (default
`<user.home>/.gnomish`) and is the only code that knows the folder names:
`hostConfig()`, `hostSecrets()`, `hostLogFile()`, `project(ProjectName)` →
`ProjectLayout` with `config()`, `secrets()`, `logFile(instance)`,
`serveDir(instance)`, `worktrees(CloneName)`. `FactoryPaths` and the home part of
`ObservabilityPaths` are deleted; `GNOMISH_LOG_DIR` is deleted (FR1, FR10,
FR11). Every holder of the `(homeDir, instanceName)` pair listed in Context takes
the serve directory `ProjectLayout.serveDir(instance)` instead; `ObservabilityPaths`
keeps only the file names inside that directory. `GNOMISH_HOME` is read from the
Spring `Environment` (the OS variable, or a system property of the same name in
in-process specs), never from `System.getenv` directly.
*Sequencing:* the owner lands before the registry, which reads through it; its
consumers move only once the loader has resolved the clone (D9), because every
project path needs the project name and no interim name is derived; its boundary
spec switches on once the last old reader, `logback-spring.xml`, is gone.
*Rationale:* three derivations of `~/.gnomish` are how logs stayed host-wide
while worktrees were per clone.
*Alternative rejected:* XDG split (`~/.config`, `~/.local/state`) — not native
on macOS, and one root is what `GNOMISH_HOME` relocation and backups want; the
config/state/cache distinction is kept inside the root and documented.

**D2 — `RegisteredClone` is the value every project-scoped consumer takes.**
`ProjectRegistry` (`:application`, beside `RegisteredClone`, so the `:test-fixtures`
fixtures can register through it — `testing.md`, "Fixtures assemble through production
owners") reads `projects/*/project.yaml`, matches the
absolute `--dir` from `fix-operator-blockers`' argument owner by exact path, and
yields `RegisteredClone(ProjectName, CloneName, Path clonePath, ProjectLayout)`
(`:application`), with `worktrees()` returning the clone's own worktree folder.
`RegisteredClone` replaces the adjacent `(Path cloneDir, Path worktreesRoot)` pair
everywhere it travels — `TaskWorktreePath.resolve`, `WorktreeJanitor`,
`TaskWorktreeManager`, `GitTaskRepository`, `GitTaskStore.taskRepository`,
`GitTaskWorktrees`, `WorktreeEnvironmentDisposal` (`:adapters:git` already depends
on `:application`) — and the relays that carried `worktreesRoot` alone carry the
`RegisteredClone`. No raw worktrees-root `Path` survives in a signature, so no
caller can hand a folder the registry did not compute. The basename derivation
is deleted in all three places (FR3, FR9, NFR-R2). The instance identity becomes
`InstanceId.generate(project + "-" + instance)` (FR10).
*Rationale:* a typed value makes "compute the project name yourself" impossible;
the triplet disappears instead of being declared, and the `Path, Path`
transposition hazard (`process-invariants.md`) goes with it.
*Alternative rejected:* keep the basename as the project name with the registry
only validating it — keeps the `~/work/api` vs `~/oss/api` collision and the
twin.

**D3 — `project.yaml` is one YAML document read by Spring's loader.**
Top level: `clones:` (map clone name → absolute path) and `factory:` (the
project's configuration). Both files are read with Spring's
`YamlPropertySourceLoader`, which yields origin-tracked properties (file, line)
for the violation report and `project show` (FR2, FR4, NFR-O1). Registration
writes through the `:atomicfile` leaf (NFR-R1). Lookup reads every
`projects/*/project.yaml` once per process.
*Alternatives rejected:* a separate `clones` index file (two files to keep in
step for one project); Jackson for the registry and Spring for configuration
(two YAML parsers, and Jackson carries no line origins).

**D4 — Levels are annotations; the table is derived, never written.** The
four sources, the four levels and the sandbox-boundary keys are the durable
policy of ADR 0011 (`docs/adr/0011-operator-configuration-levels.md`), which
this decision implements: a new JDK-only leaf `:operatorconfig` holds
`@ConfigLevel(Level)` with `Level { HOST, PROJECT, ANY, SANDBOX_BOUNDARY }` on
every component of every `@ConfigurationProperties` record, and `ConfigLevels`
(`:bootstrap`) derives key → level from them (FR6).
*Rationale:* a separate key table would be a hand-synced pair with the records
(`manual-sync-pairs.md`); on the component, a new key without a level fails the
build (M2).
*Alternatives rejected:* the annotation in `:domain` (the engine layer has no
business knowing operator files) or in `:sandbox:core` (both `:application` and
`:sandbox:core` reach a leaf; only one of them reaches the other);
`additional-spring-configuration-metadata.json` (hand-written, a pair again).

**D5 — The level of every existing key.**

| Level              | Keys                                                                                                                                                                                                              |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `HOST`             | `docker-command-timeout`, `agent-cli-tail-drain-grace`, `sandbox.runtime`, `sandbox.guard-image`, `sandbox.enforce-disk-quota`, `sandbox.minimum-age`, `sandbox.kept-reap-age`, `sandbox.manual-running-stop-age` |
| `PROJECT`          | `check.*`, `sandbox.project-id`                                                                                                                                                                                   |
| `ANY`              | `instance-name`, `agent-cli-binary`, `git-network-timeout`, `check-command-timeout`, `tracker.abort-backoff-base`, `tracker.abort-backoff-cap`, `connections.*`, `sandbox.limits.*`, `serve.*`                    |
| `SANDBOX_BOUNDARY` | `bindings.*`, `sandbox.image`, `sandbox.egress-allowlist`, `sandbox.env-passthrough`                                                                                                                              |
| removed            | `agent-cli-env-passthrough`                                                                                                                                                                                       |

`instance-name`'s built-in default becomes `default` on the record, and
`application.yaml` loses its `factory:` block — the bundled file is not a
second home for defaults.

**D6 — `OperatorConfigLoader`: one post-processor assembles and checks.** An
`org.springframework.boot.EnvironmentPostProcessor` in `:bootstrap`, registered in
`META-INF/spring.factories` under that interface's name and ordered after
`ConfigDataEnvironmentPostProcessor` (so the bundled `application.yaml` is
already present to be checked), reads the subcommand and `--dir` (D9), loads
`factory.yaml`, resolves the project (D2) unless the subcommand is `project add`
or `project list` (the only project-less commands; `project show` without a name
resolves like any project-scoped command), loads `project.yaml`, and checks all
sources against `ConfigLevels` — the checks and the report are ADR 0011's
"One check, one complete report". The collected violations are thrown as one
`ConfigurationViolationsException` (FR5, FR7, NFR-R1, NFR-O2). Accepted files are added to the environment in
precedence order, below the command line. The loader publishes the resolved log
file as the internal property `gnomish.internal.log-file`, which
`logback-spring.xml` reads with `<springProperty>` (FR11). No project-less path
other than `project add`/`project list` exists: `Subcommand` has no help token.

*Exit and rendering before the context exists.* `ConfigurationViolationsException`
implements `ExitCodeGenerator` and returns 2, the usage-error code — the only
route to an exit code before any mapper bean exists; no new code is introduced
(the table's retired 4 stays a gap, `remove-interactive-console`). The loader
prints the report to stderr through a `ConsoleIO` it constructs over the error
stream, with the line text from `RunExceptionReporting.calmLine`, which gains the
exception in its "prints its own message" family; `ReportedFailureExceptionReporter`
therefore claims it, and Spring's trace is not printed.
*Rationale:* the check must precede every bean, and the log file must be known
before Logback starts; both are true only in a post-processor.
*Alternatives rejected:* `spring.config.import` of the two files (no per-source
check, no project resolution, unknown keys silent — option A of the design
session); validating in each `@ConfigurationProperties` constructor (sees the
merged value, not its source).

**D7 — Secrets folders join the existing adapter.** `EnvFileSecretsProvider`
gains two folders ahead of `N_FILE`/`N`: the project's and the host's, from
`FactoryHome`; the file name is the secret's exact variable name, so no mapping
table exists and plugin credentials need nothing (FR8, NFR-S2). A file readable
by group or others is refused, never read.
*Alternative rejected:* friendly file names (`github-token`) — a derivation rule
that plugin credential names would not fit, i.e. a table.

**D8 — Fixtures register through the production registry.** The E2E harness
sets `GNOMISH_HOME` to a temporary folder and registers its clone with the
production `ProjectRegistry` writer, then writes the test's boundary keys into
that `project.yaml` (`testing.md`, "Fixtures assemble through production
owners"). Specs that passed `--factory.bindings.*` or `--factory.sandbox.image`
on the command line move those keys into the fixture's project file. The E2E
journeys still driven by `--interactive` (`ReferenceE2ESessionSpec`,
`ExitCodeMatrixSpec`, `E2eProcessHarnessSmokeSpec`) move to the registered
harness here, unchanged otherwise; `remove-interactive-console` later rewrites
their scripts on top of it.

The eight in-process specs that boot a `SpringApplication` (Context) run the
loader too. A `:test-fixtures` fixture, `OperatorHomeFixture`, creates a temporary
factory home, passes it as the `GNOMISH_HOME` system property, and registers the
spec's clone through the production `ProjectRegistry.add`; the specs boot through
the same argument-registration step `CommandExit` uses (D9), never around it.
`FactoryEnvironmentOverrideSpec` becomes the spec of the `FACTORY_*` refusal. The boot step
itself (`CommandExit.registerArguments`, then `run`) is a `:bootstrap` test helper, `FactoryBoot`:
`:test-fixtures` cannot reach the composition root.

**D9 — One `--dir` resolution, handed to the context.** `CommandExit.start`
registers the raw arguments as `ApplicationArguments` in Spring Boot's bootstrap
registry before `run`. The loader takes the `ConfigurableBootstrapContext` in its
constructor, reads the arguments from it, and obtains the subcommand from
`Subcommand.parse` and the directory from `ArgumentsParsingSupport.projectDir` /
`requiredProjectDir` — the existing owners, unchanged, not a second parser. It
resolves the directory to a `RegisteredClone` once and registers that value —
together with the `FactoryHome` it resolved against, so the process builds the
home once (D1) and `ProjectRegistry` and `EnvFileSecretsProvider` take it as a
bean — as singletons through the bootstrap context's close listener, which runs
before the context refreshes. Every consumer — the seven project-scoped commands, their
argument parsers, `ManualRunConfiguration`, `TrackerCommandConfiguration` — takes
the `RegisteredClone` bean; the argument parsers stop calling `projectDir` and
fill their `dir` component from the clone's `clonePath()` (FR3), so the
directory a command works in is by construction the one the registry matched. `project add` is the one exemption: it registers a path
rather than looking one up, so it calls `projectDir` itself.
*Rationale:* the environment must know the project before any bean exists, and
the commands must not re-derive what the loader already decided; the bootstrap
context is the Boot seam built for exactly that hand-off.
*Alternatives rejected:* publishing the project name as an internal property and
re-resolving in a bean (a second lookup, and the two can disagree if a
`project.yaml` changes in between); a static holder (process-global mutable
state, `process-invariants.md`, "Immutable after construction").

### Sync surfaces

This change collapses two undeclared duplications rather than declaring them:
the clone-basename project name (`TaskWorktreePath.resolve`,
`WorktreeJanitor.projectName()`, `TaskWorktreeManager.ensureWorktree`) folds into
`RegisteredClone` (D2), and the log-directory derivation in `logback-spring.xml`
folds into `FactoryHome` via the published property (D1, D6). It adds no
parallel implementation — host and container mode resolve the project once,
before either mode is chosen — and touches no row of the `manual-sync-pairs.md`
registry (the ledger readers change only the folder they read, not the wire
tokens). It touches one end of a declared pair: `WorktreeJanitor`, kept in sync
with `SandboxLifecycleTick` on the immediate-then-cadence daemon-loop shape. Only
the janitor's constructor and swept folder change; the loop shape
(`start`/`loop`/`tick`/`lastRunAt`) is untouched, so `SandboxLifecycleTick` needs
no mirrored change.

### Single-owner mechanisms

| Owner                                                                          | Value (type)                                                                                               | Consumers                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Old way removed                                                                                                                                                                                                                                                                                                   | Enforced by                                                                                                                                                                                                                                                                                                                                                                                                                            |
|--------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FactoryHome` / `ProjectLayout` (`:application`)                               | operator paths (`FactoryHome`, `ProjectLayout`; the serve directory as `ProjectLayout.serveDir(instance)`) | `ManualRunConfiguration`, `TrackerCommandConfiguration`, `StatusCommand`, `ServeRuntimeAssembly`, `DashboardCommand` (were `FactoryPaths`); `ObservabilityAssembly`, `ServeAssembly`, `DashboardRenderCycle`, `DashboardWatchLoop`, `LedgerAggregator`, `SweepActionAggregator`, `RotatingLedgerAppender` (were `ObservabilityPaths.*(homeDir, instanceName)` or relays of `homeDir`); `logback-spring.xml` (via `gnomish.internal.log-file`); `EnvFileSecretsProvider`; `OperatorConfigLoader`; `ProjectRegistry`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `FactoryPaths` deleted; the `(homeDir, instanceName)` parameters of `ObservabilityPaths` and its callers; `System.getProperty("user.home")` in `ManualRunConfiguration`; `${user.home}/.gnomish` and `GNOMISH_LOG_DIR` in `logback-spring.xml`; the `.gnomish` literal in `FactoryPaths` and `ObservabilityPaths` | `FactoryHomeBoundarySpec` in `:bootstrap` (precedent `BaseHeadDefaultBoundarySpec`): no production source or resource outside `FactoryHome` reads `user.home` or spells the exact literal `".gnomish"` in code (comments are not scanned; `test-fixtures` is not production and is not scanned), asserting it scanned every file; one listed exemption, `LawBinding.LAW_ROOT` (the target repository's law root, not the factory home) |
| `RegisteredClone` (`:application`) built by `ProjectRegistry` (`:application`) | the resolved clone (`RegisteredClone`; its worktree folder via `worktrees()`)                              | `TaskWorktreePath`, `WorktreeJanitor`, `TaskWorktreeManager`, `GitTaskRepository`, `GitTaskStore`, `GitTaskWorktrees`, `WorktreeEnvironmentDisposal` (were `(Path cloneDir, Path worktreesRoot)`); the relays `SlotWiring`, `SlotWiringFactory`, `ServeAssembly`, `ServeRuntimeAssembly`, `TakeWorkRouter`, `TakeFreshClaim`, `TakeEngineExecution`, `TakeResumeBootstrap`, `TakeResumeRunner`, `TakeResumeExecution`, `GitResumeRunner`, `GitModeRunner`, `HostResumeMechanics`, `StatusCommand`, `TaskStoreGit`, `TaskWorktreeGit`, `ManualRunConfiguration` (its `manualRunners` bean, `@Lazy`, since the clone bean exists only once a project resolves), `ManualRunDrive` (takes `ObjectProvider<ManualRunners>`), `TrackerCommandConfiguration` (were `Path worktreesRoot`); the fixtures `SeededCloneFixture`, `TaskSeedFixture` (`:test-fixtures`, built through the production registry, `testing.md`); `ProjectScope` (instance identity, minted once for `TakeCommand`, `ServeCommand` and the read-only id `TrackerWiring` receives) | the basename derivation in `TaskWorktreePath.resolve`, `WorktreeJanitor.projectName()` and `TaskWorktreeManager.ensureWorktree`; every `Path worktreesRoot` parameter and field                                                                                                                                   | parameter type: no signature takes a worktrees-root `Path` (`TaskWorktreePath.resolve(RegisteredClone, taskId)`); a grep gate in task 6.4; identity spec (below)                                                                                                                                                                                                                                                                       |
| `OperatorConfigLoader` (D9) → `RegisteredClone` bean                           | the clone `--dir` names (`RegisteredClone`, resolved once per process)                                     | the seven project-scoped commands (`run` through its drive `ManualRunDrive`, `take`, `serve`, `status`, `usage`, `board`, `dashboard`), which read the bean through `ProjectScope` (`:application`), and `project show`; the `Board`, `Dashboard`, `Run`, `Serve`, `Take` argument parsers and the `status`/`usage` `--dir` reads (fill `dir` from `clonePath()`: `parse(args, RegisteredClone)`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `ArgumentsParsingSupport.projectDir` / `requiredProjectDir` called by a command for its own directory; exemption: `project add` (registers a path, looks nothing up)                                                                                                                                              | parameter type: no project-scoped parser can be called without the clone; `ProjectDirResolutionBoundarySpec` in `:bootstrap`: `projectDir`/`requiredProjectDir` are called only from `OperatorConfigLoader` and the `project add` command, asserting it scanned every production source                                                                                                                                                |
| `ProjectScope.mintInstanceId` (FR10)                                           | the instance id `<project>-<instance>-<suffix>`                                                            | `take`, `serve` (claims, snapshot, ledger, start anchor), `board`, `dashboard` (the read-only tracker's throwaway id, handed to `TrackerWiring.resolveReadOnly`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `InstanceId.generate(factory.instance-name)` at each command                                                                                                                                                                                                                                                      | `InstanceIdMintBoundarySpec` in `:bootstrap`: `InstanceId.generate` has one production caller, `ProjectScope`                                                                                                                                                                                                                                                                                                                          |
| `ConfigLevels` (`:bootstrap`)                                                  | key → level (`Map<String, Level>` derived from `@ConfigLevel`)                                             | `OperatorConfigLoader` (checks), `project show` (level column)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | none today; no hand-written key list may appear                                                                                                                                                                                                                                                                   | `ConfigLevelCoverageSpec`: every component of every `@ConfigurationProperties` class carries `@ConfigLevel` (M2)                                                                                                                                                                                                                                                                                                                       |
| `OperatorConfigLoader` (`:bootstrap`)                                          | the host and project property sources (`OriginTrackedMapPropertySource`)                                   | the Spring `Environment` (every `@ConfigurationProperties` bean), `project show`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | the `factory:` block of `application.yaml`; environment-variable binding of `factory.*` (rejected as a violation)                                                                                                                                                                                                 | `OperatorConfigSourcesSpec`: the 16-cell level × source matrix (M3); a resource scan that no `application*.yaml` holds a `factory.*` key or a `spring.config.import`; a read-count feature asserting the host file and each project file are read once per process (NFR-P1)                                                                                                                                                            |

**Identity spec.** D1 and D2 claim the project's log file, serve folder and
worktrees are keyed by one name by construction. `RegisteredProjectLayoutE2eSpec`
runs the packaged jar with `GNOMISH_HOME` in a temporary folder against a
registered clone and asserts the task's worktree, the log file and (for
`serve`) the snapshot all sit under `projects/<name>/`.

## Risks / Trade-offs

- [Sequencing: this change lands before `remove-interactive-console` and
  `make-run-headless`, and the `RegisteredClone` signature change touches ~25
  files several of which they also touch (`TakeEngineExecution`,
  `GitResumeRunner`, `TakeResumeRunner`, the E2E harness and the take/resume
  specs)] → the substitution has no behaviour of its own and lands first; the two
  later changes rebase onto it, with the compiler listing every site, and are
  brought in step with `/opsx:update` once this change is applied. The E2E
  journeys still on `--interactive` move to the registered harness here (D8).
- [In-process specs that boot Spring now run the loader] → they boot through
  `OperatorHomeFixture` and the `CommandExit` argument registration (D8, D9); a
  spec that boots around them is refused at startup, which is the point.
- [Exact-path lookup refuses a symlinked path to a registered clone] → paths are
  compared after `toRealPath()` on both sides; the refusal names the registered
  path.
- [Operators lose the quick `--factory.bindings.default=host` toggle] → the
  violation message names the project file line to add; intentional (NFR-S1).
- [Two processes of one project and instance write one log file] → the same as
  today's single host-wide file, narrowed to one project; a second daemon sets
  `--factory.instance-name`.
- [Scanning every project file per process] → a handful of small files; no
  index to keep in step.

## Migration Plan

No automatic migration (NG3). The first release's notes tell the operator to
finish or discard aborted tasks, delete `~/.gnomish/{logs,serve,worktrees}`,
move tokens into `~/.gnomish/projects/<name>/secrets/` or `~/.gnomish/secrets/`,
register each clone, and move launcher options into the project file. `git
worktree prune` at the next start drops the dangling worktree records.
