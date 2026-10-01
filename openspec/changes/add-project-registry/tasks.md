# Tasks

Builds on the archived `fix-operator-blockers` (its argument owner and its default-binding
requirement). Lands before `remove-interactive-console` and `make-run-headless`, which rebase
onto it (design, Risks). Root `check` green after every section. Cut line if the change
overruns (proposal, Impact): §4.3–§4.5 (level enforcement) move to their own change.

## 1. Levels declared on the keys (design D4, D5)

- [x] 1.1 Create the JDK-only leaf `:operatorconfig` with `@ConfigLevel(Level)`
      and `Level { HOST, PROJECT, ANY, SANDBOX_BOUNDARY }`; wire it through
      `settings.gradle`, `library-conventions`, the layering gate and the
      dependency-analysis gate like `:operatorevent`. Verify
      `./gradlew :operatorconfig:check` and `verifyModuleLayering` pass.
- [x] 1.2 Annotate every component of `FactoryProperties` (+ `Tracker`),
      `ServeProperties`, `SandboxProperties` (+ `ResourceLimits`) and
      `BindingProperties` with the level from design D5 (FR6). Verify by 1.3.
- [x] 1.3 `ConfigLevelCoverageSpec` in `:bootstrap`: scans every
      `@ConfigurationProperties` class on the classpath, recursing into nested
      records, and fails on a component without `@ConfigLevel` (M2); asserts it
      reached every class it expects. Verify it goes red with one annotation
      removed.
- [x] 1.4 Remove `factory.agent-cli-env-passthrough` from `FactoryProperties`
      and its docs (FR12); move the `instance-name` default to the record as
      `default` and delete the `factory:` block of `application.yaml` (D5).
      Verify `git grep -n "agent-cli-env-passthrough\|agentCliEnvPassthrough" -- '*/src/main/*' docs`
      is empty.

## 2. One layout owner (design D1) — single-owner row 1, the owner only

The owner lands here because the registry (§3) and the loader (§4) read through it.
Its consumers move in §5 (5.2, 5.3): every project path needs the project name,
which exists only once the loader resolves the clone (4.6), and no interim name is
derived. Its gate lands in §6 (6.3), once the last old reader, `logback-spring.xml`,
is gone (6.1).

- [x] 2.1 Add `FactoryHome` and `ProjectLayout` (`:application`), `ProjectName`
      and `CloneName` value types (`[a-z0-9][a-z0-9._-]*` for project names);
      `FactoryHome` is built from the `GNOMISH_HOME` value the Spring `Environment`
      exposes (OS variable, or a same-named system property in specs), default
      `<user.home>/.gnomish` (FR1, D1). Spock spec for every path accessor and the
      `GNOMISH_HOME` override. Verify PIT 100% on the classes.

## 3. Registry and the `project` subcommands (design D2, D3)

- [x] 3.1 Add `RegisteredClone` (`:application`, with `worktrees()` returning the
      clone's worktree folder) and `ProjectRegistry` (`:application` since 4.x, so
      `:test-fixtures` can register through it): reads
      `projects/*/project.yaml` with `YamlPropertySourceLoader`, resolves an absolute
      directory by exact `toRealPath()` match, refuses an unregistered path with the
      `gnomish project add <suggested> --dir=<path>` line and a path inside a
      registered clone naming that clone (FR3, UX2). Spec covering match,
      unregistered, inside-a-clone, symlink. Verify PIT 100%.
- [x] 3.2 Registration write: `ProjectRegistry.add(name, path)` creates or
      extends `project.yaml` through `:atomicfile`, refuses a path owned by any
      project, a non-git directory and an invalid name, derives the clone name
      from the last path segment and refuses a duplicate clone name (FR2,
      NFR-R1). Verify a spec including "failed add leaves the file unchanged".
- [x] 3.3 `project` subcommand in `Subcommand` with `add`, `list`, `show`;
      accepted options declared through `fix-operator-blockers`'
      `rejectUnknownOptions`; `project add` resolves its `--dir` with
      `ArgumentsParsingSupport.projectDir` (the one exemption of D9); `show`
      without a name takes the `RegisteredClone` bean, and prints paths and every
      effective `factory.*` value with its origin via Spring `OriginLookup` (FR4,
      NFR-O1, U5). Verify a spec per verb, including the "where a value came
      from" scenario of the project-registry spec.

## 4. `OperatorConfigLoader` (design D6, D9) — single-owner rows 3, 4 and 5

- [x] 4.1 Add `ConfigLevels`: derives key → level from the annotated records
      with Spring's relaxed names, plugin `Map` roots levelled whole (D4). Spec
      over a fixture record set. Verify PIT 100%. (Landed with 3.3, whose `project
      show` reads its level column and built-in defaults from it.)
- [x] 4.2 Add `OperatorConfigLoader` implementing
      `org.springframework.boot.EnvironmentPostProcessor`, registered in
      `META-INF/spring.factories` under that interface's name and ordered after
      `ConfigDataEnvironmentPostProcessor`: reads the arguments from the bootstrap
      context (4.6), the subcommand via `Subcommand.parse`, loads `factory.yaml`,
      resolves the project via `ProjectRegistry` unless the subcommand is
      `project add`/`project list` — `project show <name>` takes the named project,
      not the `--dir` one, so the values it prints (3.3) are that project's —
      loads the project's `factory:` block, adds both
      below the command line (FR5). Verify `OperatorConfigSourcesSpec` precedence
      rows (project over host, command line over project for a permitted key) and
      its read-count feature: the host file and each project file are read once
      per process (NFR-P1, the "reads each file once" scenario).
- [x] 4.3 Level checks per source, unknown `factory.*` keys, `FACTORY_*`
      variables, group/other-writable configuration files, and the unregistered or
      inside-a-clone `--dir` as one more line — collected into one
      `ConfigurationViolationsException` whose lines carry location, key, reason
      and fix (FR6, FR7, NFR-S1, NFR-S2, NFR-O2, UX1). Verify the 16-cell
      level × source matrix (M3: host file, project file, command line,
      `FACTORY_*` variable) and the operator-configuration scenarios.
- [x] 4.4 Exit before the context exists (D6): `ConfigurationViolationsException`
      implements `ExitCodeGenerator` returning 2; the loader prints the report to
      stderr through a `ConsoleIO` it builds over the error stream; the exception
      joins `RunExceptionReporting`'s "prints its own message" family so
      `ReportedFailureExceptionReporter` claims it; a new `OperatorEvent` only if a
      WARN/ERROR line is emitted (`logging.md`). Verify an E2E run of the packaged
      jar with a violating `factory.yaml` exits 2, prints every violation once with
      no stack trace, and creates no worktree; and one with an unregistered
      `--dir` exits 2 with the `project add` line.
- [x] 4.5 Resource scan in `OperatorConfigSourcesSpec`: no `application*.yaml`
      holds a `factory.*` key or a `spring.config.import`. Verify red with a
      planted key.
- [x] 4.6 One resolution handed to the context (D9): `CommandExit.start` registers
      the raw arguments as `ApplicationArguments` in the bootstrap registry; the
      loader takes the `ConfigurableBootstrapContext` in its constructor, obtains
      the directory from `ArgumentsParsingSupport.projectDir` /
      `requiredProjectDir` (for `status`/`usage`), and registers the resolved
      `RegisteredClone` — and the `FactoryHome` it resolved against, so the process
      builds the home once (D1) — as singletons from the bootstrap context's close
      listener; `ProjectRegistry` and `EnvFileSecretsProvider` take that bean, and the
      interim `factoryHome` bean of `ProjectConfiguration` (added by 3.3) is deleted.
      Add `ProjectDirResolutionBoundarySpec` in `:bootstrap`: `projectDir` and
      `requiredProjectDir` are called only from `OperatorConfigLoader` and the
      `project add` command, asserting it scanned every production source. Verify
      the spec goes red with a planted `projectDir` call in a parser, and the
      project-registry "symlinked path" scenario. (Landed with the seven command
      parsers still calling it listed as `UNTIL_TASK_5_5` in the spec; 5.5 empties
      that list. The loader's checks live in `OperatorConfigCheck`; the specs are
      `OperatorConfigSourcesSpec`, `OperatorConfigResolutionSpec`,
      `OperatorConfigViolationsSpec`, `OperatorConfigRefusalsSpec` and
      `ConfigurationViolationE2eSpec`.)

## 5. Consumers take `RegisteredClone` (design D1, D2, D9) — single-owner rows 1 (consumers), 2 and 3

- [x] 5.1 `TaskWorktreePath.resolve(RegisteredClone, taskId)` →
      `projects/<name>/worktrees/<clone>/<task>`; `WorktreeJanitor` and
      `TaskWorktreeManager` take `RegisteredClone`, the janitor sweeping only its
      clone's folder; delete all three basename derivations (FR9, NFR-R2). The
      janitor's loop shape stays as its declared pair with `SandboxLifecycleTick`
      requires (design, Sync surfaces). Verify the two new git-task-persistence
      scenarios and
      `git grep -n -e "projectName" -e "cloneDir.*getFileName()" -- application/src/main/java/com/github/oinsio/gnomish/app/git/TaskWorktreePath.java application/src/main/java/com/github/oinsio/gnomish/app/serve/WorktreeJanitor.java adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/TaskWorktreeManager.java`
      is empty (the janitor's task-key read `dir.getFileName()` stays).
- [x] 5.2 Serve directory from the registered project (D1, FR10): replace the
      `(homeDir, instanceName)` pair of `ObservabilityPaths` in
      `ObservabilityAssembly`, `ServeAssembly`, `ServeRuntimeAssembly`,
      `DashboardCommand`, `DashboardRenderCycle`, `DashboardWatchLoop`,
      `LedgerAggregator`, `SweepActionAggregator` and `RotatingLedgerAppender` with
      the serve directory `RegisteredClone.layout().serveDir(instance)`, the
      dashboard's default output included; `ObservabilityPaths` keeps only the file
      names; `FactoryPaths` loses `homeDir`. Verify the serve-observability and
      dashboard-page scenarios and
      `git grep -n "homeDir" -- '*/src/main/*'` is empty.
- [x] 5.3 Replace every `(Path cloneDir, Path worktreesRoot)` pair with
      `RegisteredClone` in `GitTaskRepository`, `GitTaskStore.taskRepository`,
      `GitTaskWorktrees`, `WorktreeEnvironmentDisposal`, and every `Path
      worktreesRoot` relay with the `RegisteredClone` in: `SlotWiring`,
      `SlotWiringFactory`, `ServeAssembly`, `ServeRuntimeAssembly`, `TakeWorkRouter`,
      `TakeFreshClaim`, `TakeEngineExecution`, `TakeResumeBootstrap`,
      `TakeResumeRunner`, `TakeResumeExecution`, `GitResumeRunner`,
      `GitModeRunner`, `HostResumeMechanics`, `StatusCommand`, `TaskStoreGit`,
      `TaskWorktreeGit`, `TrackerCommandConfiguration`, `ManualRunConfiguration`;
      and in the fixtures `SeededCloneFixture` and `TaskSeedFixture`
      (`:test-fixtures`), which obtain the value through the production
      `ProjectRegistry.add`. With its last reader gone, delete `FactoryPaths` and
      the `factoryPaths()` bean (the `user.home` read in `ManualRunConfiguration`),
      and update the `-parameters` comment in
      `build-logic/src/main/groovy/java-conventions.gradle` that names the
      `worktreesRoot`/`homeDir` beans. Verify
      `git grep -n -e "worktreesRoot" -e "FactoryPaths" -- '*/src/main/*' build-logic`
      is empty.
- [x] 5.4 Instance identity: `InstanceId.generate(project + "-" + instance)` in
      `TrackerWiring`, `TakeCommand`, `ServeCommand` (FR10). Verify a spec that the
      instance id begins with `<project>-<instance>` (serve-observability, "begin
      with the project name").
- [x] 5.5 The seven project-scoped commands and `project show` take the
      `RegisteredClone` bean (D9); the `Board`, `Dashboard`, `Run`, `Serve` and
      `Take` argument parsers and the `status`/`usage` `--dir` reads stop calling
      `projectDir`/`requiredProjectDir` and fill their `dir` component from
      `clonePath()`. Verify each command's spec runs against a registered fixture
      and refuses an unregistered one (project-registry scenarios), and 4.6's
      boundary spec is green.

## 6. Logging, secrets, layout gate (design D1, D6, D7)

- [x] 6.1 `logback-spring.xml` reads `gnomish.internal.log-file` via
      `<springProperty>`; the loader publishes `projects/<name>/logs/<instance>.log`
      or `logs/factory.log` (FR11). Rewrite `LogbackConfigSpec`'s
      `GNOMISH_LOG_DIR` feature, `OperatorLogIsolationSpec` and the
      `GNOMISH_LOG_DIR` note in `bootstrap/src/test/resources/logback-test.xml` for
      the property. Verify the manual-run "Two projects keep separate logs"
      scenario (FR11, UX3).
- [x] 6.2 `EnvFileSecretsProvider`: project folder, host folder, `N_FILE`, `N`;
      refuse a group/other-readable or -writable secret file naming the `chmod`
      (FR8, NFR-S2). Verify the four secrets-provider scenarios in
      `EnvFileSecretsProviderSpec`. (The three folder scenarios landed in
      `EnvFileSecretsFolderSpec` beside it, keeping both under the file-size cap.)
- [x] 6.3 `FactoryHomeBoundarySpec` in `:bootstrap` (design D1, single-owner row 1),
      green from here: its last old readers — `FactoryPaths` and the
      `ManualRunConfiguration` bean (5.3), `logback-spring.xml` (6.1) — are gone. No
      production source or resource outside `FactoryHome` reads `user.home` or
      spells the exact literal `".gnomish"` in code — comments are not scanned,
      `test-fixtures` is not production and is not scanned — with
      `LawBinding.LAW_ROOT` (the target repository's law root) the one listed
      exemption, with its reason; asserts every scanned file was reached. Verify
      red with a planted `System.getProperty("user.home")`.
- [x] 6.4 Old-way sweep report (`implementation.md`): run
      `git grep -n -e "user.home" -e "\.gnomish\"" -e "worktreesRoot" -e "homeDir" -e "instanceName()" -e "GNOMISH_LOG_DIR" -e "projectDir(" -e "requiredProjectDir(" -- '*/src/main/*' '*.xml' build-logic`,
      list every hit and what happened to it; attach to the task report.

## 7. Fixtures, messages, docs (design D8)

- [x] 7.1 `E2eProcessHarness`: `GNOMISH_HOME` in a temporary folder, clone
      registered through the production `ProjectRegistry.add`, boundary keys
      written to that `project.yaml`; migrate the specs that pass
      `--factory.bindings.*`, `--factory.sandbox.image`,
      `--factory.sandbox.egress-allowlist`, `--factory.sandbox.env-passthrough`,
      `--factory.instance-name` or `GNOMISH_LOG_DIR`, including the journeys still
      on `--interactive` (`ReferenceE2ESessionSpec`, `ExitCodeMatrixSpec`,
      `E2eProcessHarnessSmokeSpec`), otherwise unchanged. Verify
      `git grep -n -e "--factory.bindings" -e "--factory.sandbox.image" -e "GNOMISH_LOG_DIR" -e "FACTORY_[A-Z_]*=" -- '*/src/test/*'`
      is empty. (Pulled forward into §4 to keep `check` green once the loader
      refused unregistered clones: `GNOMISH_HOME` and the registration through
      `ProjectRegistry.add` in the harness, and `E2eFixture` trees made git working
      trees. Closed here: `PaidSmokePermissionModeSpec` binds host through the
      harness's `projectConfig`, and the harness copies non-git fixture trees
      into git working trees through `E2eGitTree`. The one surviving grep hit,
      `LogbackConfigSpec`, asserts that `GNOMISH_LOG_DIR` is absent.)
- [x] 7.2 Error messages that advise `factory.bindings.default=host` or
      `factory.sandbox.image` (`SandboxModeSelector`, `BindingResolver`, the
      binding discovery) name the project file instead. Verify the updated
      message specs and the adapter-binding-registry scenarios of "The default-binding
      key binds from the project file".
- [x] 7.3 Identity spec `RegisteredProjectLayoutE2eSpec`: packaged jar,
      temporary `GNOMISH_HOME`, registered clone; the task's worktree, the log
      file and the serve snapshot all sit under `projects/<name>/` (design,
      identity spec). Verify red with `FactoryHome` bypassed for any one path.
- [x] 7.4 `docs/adr/0011-operator-configuration-levels.md` (four sources,
      declared levels, sandbox-boundary keys, violation report);
      `docs/glossary.md` entries: factory home, registered project, clone name,
      configuration level, sandbox-boundary key. Verify the design references
      the ADR rather than restating it.
- [x] 7.5 Operator guides: every `~/.gnomish` path and every `factory.*`
      example updated to the project layout and file levels
      (`docs/guides/operator-guide*.md`, `docs/guides/adapter-author-guide.md`,
      `README.md`, `docs/examples/sandbox-image/README.md`); a "Setting up a
      project" section with `project add` and a sample `project.yaml`; the exit-code
      table notes that a configuration violation exits 2. Verify
      `git grep -n -e "~/.gnomish/logs/gnomish.log" -e "~/.gnomish/worktrees" -e "~/.gnomish/serve" -e "GNOMISH_LOG_DIR" -- docs README.md`
      is empty.
- [x] 7.6 In-process specs that boot Spring (D8): add `OperatorHomeFixture` to
      `:test-fixtures` (temporary factory home as the `GNOMISH_HOME` system
      property, clone registered through `ProjectRegistry.add`, boot through the
      `CommandExit` argument registration); move `FactoryApplicationSpec`,
      `LoggingLevelSpec`, `OperatorLogIsolationSpec`, `CommandExitSpec`,
      `ManualRunConfigurationSpec`, `ApplicationBeanInventorySpec` and
      `BootstrapScanRootSpec` onto it; rewrite `FactoryEnvironmentOverrideSpec` into
      the spec that `FACTORY_INSTANCE_NAME` stops startup with the equivalent
      file line (the "Environment variable is refused" scenario). Verify
      `./gradlew :bootstrap:test` green and
      `git grep -ln "new SpringApplication\|SpringApplicationBuilder" -- '*/src/test/*'`
      lists only files that use the fixture. (Pulled forward into §4; the boot step
      is the `:bootstrap` test helper `FactoryBoot`, since `:test-fixtures` cannot
      reach `CommandExit`.)

## 8. Wrap-up

- [x] 8.1 Traceability: every FR/NFR/UX of the proposal named by a spec or
      javadoc. Verify
      `git grep -n "of add-project-registry" -- '*.java' '*.groovy'` against the
      proposal's ID list.
- [x] 8.2 Root `./gradlew check` green (PIT 100% on touched classes). Verify the
      exit code.
- [ ] 8.3 Operator-stand acceptance (M1) — a human step, not a gate; every
      behaviour it exercises is already covered by the specs above. Register the
      stand's clones, move its launcher options into `project.yaml` and secrets
      into the project folder, and run a task with only `--dir`. Verify the
      launcher carries no `--factory.*` option and no exported path variable.
