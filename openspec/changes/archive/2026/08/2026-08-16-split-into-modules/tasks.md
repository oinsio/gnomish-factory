# Tasks: split-into-modules

Order follows the design migration plan (D2 two passes; suite green at every
checkpoint). Tasks 1–9 are pass 1 (horizontal); task 10 is pass 2 (vertical).

## 1. build-logic foundation

- [x] 1.1 Create the `build-logic` included build and wire it into
  `settings.gradle` (FR6, D9)
- [x] 1.2 Author `java-conventions` (toolchain, Spotless, Error Prone + NullAway,
  JaCoCo) and `test-conventions` (Spock, PIT) convention plugins (FR6, D9)
- [x] 1.3 Author `library-conventions` and `published-api-conventions` (semver
  metadata) convention plugins (FR5, FR6, D9)
- [x] 1.4 Keep the single module compiling through the convention plugins; run
  the full suite green as the baseline (FR9, D2)
  <!-- baseline: `./gradlew check` green in 35m49s — 3867 mutations, 100% killed -->


## 2. Extract domain, gitobjects, and gnomish-plugin-api

- [x] 2.1 Extract `:domain` (already adapter-free at the import level; rewrite
  its cross-layer javadoc `{@link}`s as plain text) and `:gitobjects`
  (git-object utilities + the `DoNotMutate` marker) applying the library
  convention (FR1, D1, D4)
  <!-- `:test-fixtures` (task 7.1) was pulled forward: the shared engine fakes
       and port-contract suites live in the domain test tree, so carving out
       `:domain` strands them otherwise. `DoNotMutate` landed in `:domain`, not
       `:gitobjects` — see the design deviation note. Verified: full `test`
       green, `buildHealth` green, `:domain` 275/275 and `:gitobjects` 69/69
       mutations killed. -->
  <!-- deviation: the root-package `DoNotMutate` marker stays with `:domain`
       instead of moving to `:gitobjects` (D1's layer-home note). Four `:domain`
       types carry it, and FR2 forbids `:domain` depending on anything internal;
       `:gitobjects` already had its own self-contained copy (D19 of
       add-sandbox-core), so no module gained a dependency. -->
  <!-- deviation: dependency-analysis moved from the root build script to
       `settings.gradle` (`com.autonomousapps.build-health`). Applied to the
       root project only, it analysed nothing but the root and reported "no
       project health reports found" for every module (FR2, FR6, M3). -->
  <!-- deviation: `spock-spring` moved out of `test-conventions` into the
       modules that have Spring on the test classpath — it registers a global
       Spock extension that resolves Spring types at discovery time, so it kills
       every test task in a Spring-free module before a spec runs. -->
- [x] 2.2 Spike: derive the api surface from the github adapter's import closure
  minus its own packages (FR4, Q3, D4)
  <!-- Re-run against the current tree: reproduces D4 exactly — the tracker port
       DTO family, `SecretsProvider`, `TrackerAdapterFactory`, the domain value
       types (Finding, PollStatus, ConfigError, TrackerConfig, VerifyCheck),
       `ExternalCheckClient`/`Workspace`, and precisely the five sibling-adapter
       leaks D4 tabulates. No surface beyond D4's was found. -->
- [x] 2.3 Create `:gnomish-plugin-api` with the tracker/secrets/check ports, the
  `TrackerAdapterFactory` SPI, and the relocated `TrackerSubsectionValidator`;
  expose `:domain` as a transitive `api` dependency; the check SPI factory is
  deferred to change B (FR4, D4)
  <!-- deviation: the check-side ports (`ExternalCheckClient`, `Workspace`) stay
       in `:domain` instead of moving into the api as D4 sketched. The engine
       consumes both from inside `:domain` (EnginePorts, Engine, ExternalPolling,
       VerifyOrchestrator, …), so relocating them would make `:domain` depend on
       the api and close the cycle FR2 forbids. They reach a third party through
       the same transitive `api project(':domain')` edge, so UX3's single
       declared dependency is unaffected. -->
  <!-- `TrackerSubsectionValidator` moved package with the file
       (`adapter.pipeline` -> `app`, next to the `TrackerAdapterFactory` SPI):
       keeping an `adapter.*` package inside the published contract would both
       read wrong to a third party and trip D5's "application must not depend on
       ..adapter.." rule from `app`'s own command classes. Import-line-only
       churn in 15 files. -->
- [x] 2.4 Add a dependency-analysis assertion that `:gnomish-plugin-api` has zero
  `application` / `bootstrap` internal imports (FR4, M3)
  <!-- `:gnomish-plugin-api:verifyApiContractSurface`, wired into that module's
       `check`: the compile and runtime classpaths may reach no project outside
       `:domain`. dependency-analysis reports misdeclared/unused edges, not
       forbidden ones, so it supplies the other half — declared set == used set —
       and together they give M3. Negative-tested: adding `:gitobjects` fails
       with the named rule. -->
- [x] 2.5 Apply `published-api-conventions`: `maven-publish` + real semver on
  `:gnomish-plugin-api`; add japicmp in report-only (non-failing) mode over the
  api artifact and its transitively exposed `:domain` types (FR5, D10, Q2)
  <!-- api version 0.1.0 (build-wide placeholder stays 0.1.0-SNAPSHOT). japicmp
       compares the whole runtime classpath (api jar + the exposed `:domain`
       jar) against `-PapiBaselineVersion=<semver>`; nothing is published yet, so
       it skips cleanly until the first release. `failOnModification = false` —
       change B flips the gate. Also fixed `verifyPublishedApiVersion`, which
       read `project.version` at plugin-apply time and so never saw the module's
       own version. -->
- [x] 2.6 Document the api artifact for third parties; verify a sample adapter
  compiles with `gnomish-plugin-api` as its only declared dependency (UX3, FR4)
  <!-- `gnomish-plugin-api/README.md` (surface, exclusions, semver policy,
       how to write an adapter) plus `:gnomish-plugin-api:sample` — a stand-in
       third-party tracker adapter whose build file declares exactly one
       dependency. Compilation is the assertion. DAGP's used-transitive rule is
       ignored for that one module only: it would demand `:domain` be declared
       directly, the exact opposite of the promise being proven. -->

## 3. Extract the sandbox port layer

- [x] 3.1 Extract `:sandbox:core` (port, capability-passport negotiation,
  reconciliation, the `AdapterBinding` / `IsolationLevel` enums, sandbox
  config-properties types) with no backend-specific dependencies; backend
  classes stay in place for now (FR8, D7, D11)
  <!-- Moved: `TaskExecutionEnvironment` + `ExecCommand`/`ExecHandle`/
       `ProcessStartException`, `CapabilityPassport`/`IsolationLevel`,
       `SandboxNeed`/`SandboxReconciler`, `AdapterBinding`/`BindingResolver`,
       and `SandboxProperties`/`BindingProperties`/`ResourceLimits` (D1's
       layer-home rule). The docker/host backends stay in the root project until
       task 6.1; `Segment`/`SegmentPlanner`, `EnvironmentLease`,
       `ChildEnvAllowlist` and the channel/self-check mechanics stay with them.
       Verified: `:sandbox:core:check` green (43/43 mutations killed), full
       `test` green with no spec-body edits, `buildHealth` green. -->
  <!-- deviation: the moved types change package, `adapter.environment` ->
       `com.github.oinsio.gnomish.sandbox`. D11 makes `:sandbox:core` a
       port-layer module that `:application` depends on, while D5's ArchUnit
       rule forbids `application..` -> `..adapter..`; leaving the port in an
       `adapter.*` package would make task 4.6 fail on the nine use-case files
       D3 explicitly keeps in `application`. Same reasoning as the
       `TrackerSubsectionValidator` relocation in task 2.3; import-line-only
       churn in ~110 files. -->
  <!-- deviation: `:sandbox:core` declares `implementation libs.spring.boot` (a
       new bare-artifact catalog alias) for the `@ConfigurationProperties` /
       `@Name` annotations on the three configuration records. It is the
       annotations only — no context, no starter — so the module stays a plain
       port layer and `@ConfigurationPropertiesScan` still finds the records
       (the package sits below the bootstrap class in the scan tree). -->
  <!-- `ExecCommandSpec` added to the new module: `ExecCommand.requireNonEmpty`
       was killed only incidentally, by an adapter spec that now lives in a
       different module. Per-module PIT (D6) needs the module's own specs to
       cover its own classes. New spec file — no existing spec was edited
       (FR9, M5). -->
  <!-- `:sandbox:core` declares no `:test-fixtures` edge: its specs construct
       their subjects directly, and the dependency-analysis gate fails an unused
       declaration. -->
  <!-- `:sandbox:core:pitest` is 5s versus the 35m49s whole-tree baseline —
       the first observed per-module PIT scoping win (NFR-P1, M1). -->
  <!-- Full `check` (26m07s): every module gate green — root 3425/3425 and
       `:sandbox:core` 43/43 — except one TIMED_OUT on
       `JudgePromptBuilder.delimiterFor` (NegateConditionals), a file this change
       does not touch. Negating that `while` makes the delimiter-growing loop
       non-terminating, so the only kill path is PIT's interrupt landing on the
       method's `isInterrupted()` guard — the load-sensitive shape
       `test-conventions` documents. Re-run scoped to that class on an unloaded
       machine: 8/8 killed. Pre-existing flake, not a split regression. -->
  <!-- Note: the spec's "backend depends on core, not the reverse" and "use
       cases reach the port through core" scenarios are only half-observable
       here — the root project (still holding both) depends on `:sandbox:core`,
       and nothing in `:sandbox:core` imports `adapter.environment`. They become
       module-level assertions at tasks 4.5 and 6.1. -->

## 4. Invert adapter dependencies, carve `:application` and `:bootstrap`

Execution order is **4.1–4.6 → section 5 → 4.7–4.9** (D2 sequencing correction):
`:bootstrap` is whatever remains in the root project once the adapters leave, so
extracting it before task 5.1 would need a throwaway `project(':')` edge.

- [x] 4.1 Relocate the 29 ports / value types / utilities misfiled under
  `adapter.*` to their correct layer, signatures unchanged (FR12a, D12)
  <!-- New homes: `app.port.console` (ConsoleIO, ConsoleClosedException,
       ActivityTracker, StatusRenderer), `app.port.agent` (AgentProgressListener/
       -Event, Round/JudgeEnvironmentSource), `app.port.check`
       (CheckEnvironmentSource, ExternalCheckPinContributor), `app.port.git`
       (TaskSalvage, BranchLocation, BranchStateResult, UsageHistoryResult, the
       three exceptions, AttemptCommitRef, DeliveredBranchState, TaskListRow,
       UsageRow, UsageTotals), `app.git` (TaskIdSanitizer, TaskWorktreePath),
       and `:sandbox:core` (Segment, SegmentPlanner, ChildEnvAllowlist).
       Package-declaration and import-line churn only — no signature changed.
       Verified: full `test` green, `spotlessCheck` and `buildHealth` green.
       Bootstrap-bound set 70 -> 62. -->
  <!-- Two types followed a mover rather than being on D12's list.
       `TaskLifecycleEvent` types `GitTaskRepositoryException`'s constructor, so
       leaving it in `adapter.git` would have made `app.port.git` import an
       adapter package; it moved to `app.port.git` with the exception.
       `StageFixture` (a Spock trait used only by the two Segment specs) followed
       them into `:sandbox:core`'s test tree — a single-module fixture, so FR7's
       `:test-fixtures` is not its home. -->
  <!-- `:sandbox:core` gains `implementation libs.slf4j.api`: `ChildEnvAllowlist`
       logs through the SLF4J API and `:domain` declares slf4j as
       `implementation`, so it is not inherited. -->
  <!-- deviation: task 3.1 recorded `Segment`/`SegmentPlanner`/
       `ChildEnvAllowlist` as staying with the backends until task 6.1. They are
       adapter-free planning/policy types with no docker dependency, and D12(a)
       needs them below `:application`, so they moved now instead. -->
  <!-- deviation: D12(a) sends the relocated utilities to "`application`", but
       `:application` does not exist until task 4.5; they land in their final
       packages inside the root project and travel with it. -->
  <!-- deviation: `app.git` is a second package alongside `app.port.git`.
       `TaskIdSanitizer` / `TaskWorktreePath` are pure functions, not members of
       a port contract, and `app.port.*` is reserved for ports in this tree. -->
  <!-- The `usage.json.UsageReportJsonMapper` and `app.UsageTextRenderer` leaks
       are NOT resolved here: they consume the `git.state.State*Dto` Jackson DTOs,
       which are genuine adapter types and belong to task 4.3's inversion. -->
  <!-- Three relocated types carry an adapter reference into their new home and
       are therefore still bootstrap-bound until 4.3/4.4 clear them:
       `app.port.git.UsageRow` and `UsageTotals` are typed in
       `git.state.StateAttemptDto` / `StateUsageDto` (task 4.3), and
       `app.port.agent.JudgeEnvironmentSource.hostBacked` constructs a
       `HostTaskExecutionEnvironment` over a `DirectoryWorkspace` — composition
       sitting on a port interface, which task 4.4 lifts into the caller. Their
       homes are correct; only their bodies still point the wrong way. -->
- [x] 4.2 Replace direct `SystemClock` / `ThreadSleeper` construction with
  injection of the existing `:domain` `Clock` / `Sleeper` ports (FR12b, D12)
  <!-- Verified: full `test`, `spotlessCheck` and `buildHealth` green;
       bootstrap-bound set 62 -> 49. -->
  <!-- deviation: resolved as a bucket-(a) relocation, not the bucket-(b)
       injection D12 sketched. Both classes are pure JDK wrappers — `java.time.
       Clock.systemUTC()` and `Thread.sleep` — with no external system, no
       configuration and no I/O, so they are default implementations of the
       `:domain` `Clock` / `Sleeper` ports misfiled under `adapter.*`, not
       adapters. They moved to `com.github.oinsio.gnomish.domain.engine.time`
       (a sibling of the ports rather than inside `..engine.port`, which stays
       interfaces-only) together with their two specs.
       Injecting instead would have been the wrong trade: `SlotLedger`'s
       clock-defaulting constructor alone is called from ~90 spec sites, so
       bucket (b) would have spent a fifth of M5's spec-edit budget on two
       classes that were never adapters. `DomainPuritySpec` still passes — the
       moved classes touch only `java.time` and `Thread`. -->
  <!-- Declared types were left as the concrete `SystemClock` / `ThreadSleeper`
       where they already were: after the move those are `:domain` types, which
       `application` may depend on, so FR12 is satisfied without a widening that
       would have churned Spring `@Bean` return types in
       `ManualRunConfiguration`. -->
  <!-- `InMemoryAttemptPersistence` stays in `adapter.engine`: unlike the other
       two it is a real in-memory store standing in for the git-backed
       persistence adapter. Its holders are resolved by task 4.3. -->
- [x] 4.3 Invert the git-subprocess surface (`GitProcessRunner`,
  `GitTaskRepository`, `GitAttemptPersistence`, the worktree/branch helpers,
  the `git.state` mappers) behind `application`-owned ports (FR12b, D12)
  <!-- Two independent halves. (a) `git.state` DTO inversion: `UsageRow`/
       `UsageTotals` retyped from `StateAttemptDto`/`StateUsageDto`/
       `StateTokenUsageDto` onto the domain's own `AttemptRecord`/`ExecutorUsage`/
       `TokenUsage` (the DTOs already mirrored them 1:1, and `StateJsonMapper`
       already had the mapping — `fromAttempt` was only made public);
       `UsageTextRenderer` and `usage.json.UsageReportJsonMapper` re-sourced from
       the domain, the latter mirroring `status.json`'s `AttemptMapper`/
       `UsageMapper` flattening exactly, so the rendered document is unchanged.
       `TaskJsonContent` became the port type `app.port.git.TaskRecord` and
       `TaskOutcomeDto` the port type `RecordedOutcome` (sealed, no wire
       discriminator, `Escalated` carrying the domain `EscalationReport`).
       (b) the collaborator inversion: three cohesive ports in `app.port.git` —
       `TaskBranchGit`, `TaskWorktreeGit`, `TaskStoreGit` — carried as one
       injected `TaskGit` record and bound by a single `@Bean`. The git adapter
       gained three delegation-only facades (`GitTaskBranches`,
       `GitTaskWorktrees`, `GitTaskStore`) over one shared `GitProcessRunner`.
       Verified: full `test` green (3235 tests), `spotlessCheck` and
       `buildHealth` green. -->
  <!-- Finding that shaped the port surface: every git collaborator is a
       single-public-method class, and most already take their context
       (`cloneDir`, `taskId`, `branch`) as method arguments rather than
       constructor state — so the `app` layer was never calling git, only
       *constructing* it (`GitProcessRunner.run` is package-private). The
       inversion is therefore a delegation facade, not new logic: no git
       behavior moved, and the classes that already implemented application
       ports (`TaskRepository`, `AttemptPersistence`, `TaskSalvage`,
       `AttemptDelivery`, `RoundEnvironmentSource`) kept doing so. -->
  <!-- deviation: two ports are *extensions* of existing ones rather than edits
       to them — `TaskLifecycleStore extends TaskRepository` (adds
       `confirmTerminalWrite`) and `WorktreeSalvager extends TaskSalvage` (adds
       `hasLeftovers`/`discard`). Both capabilities existed only on the git
       implementations; lifting them into the base ports would have been a port
       contract change FR9 forbids, and would have broken the in-memory
       reference repository, which has no marker to confirm and no working copy
       to reset. -->
  <!-- deviation: `TaskGit` bundles the three ports into one injected value
       rather than injecting them separately. They travel together in every call
       chain, and binding them independently would make a half-git,
       half-other-backend mixture representable — nothing would catch it until
       runtime. It is also the single substitution seam for a non-git backend. -->
  <!-- Relocated into `app.port.git` because they appear in port signatures:
       `DivergenceOutcome` (package-private enum, now public) and
       `GitSalvageFailedException` — the same D12(a) treatment as task 4.1's
       list, found only once the port surfaces were drawn. The state-file
       readbacks (`readFinalState`/`readTaskJson`) moved off `GitFreshTaskSupport`
       onto `TaskStoreGit`: they parsed the adapter's own `.gnomish-task/` file
       layout, which no `application` class may know. -->
  <!-- M5 budget: 154 spec files touched at construction sites only, EXCEPT 15
       assertion lines and 2 scenario names that the type change made
       impossible to keep. `outcome().type() == 'completed'` became `instanceof
       RecordedOutcome.Completed` (the port type carries no wire discriminator
       by design) and `attempt().result() == 'qualityFailure'` became the domain
       enum; the renamed scenarios asserted "keeps outcome at the DTO level" and
       "readFinalState()", i.e. exactly the behavior being inverted. No
       given/when/then block changed. -->
  <!-- Scope note: the container/sandbox git cluster (`ContainerRunSupport` and
       the five files anchored on it) is NOT inverted here and stays
       bootstrap-bound. `ContainerRunSupport`, `ContainerRunSupportFactory`,
       `SandboxRunPieces` and `ServeAssembly` are assembly classes whose job is
       to instantiate and connect adapters — D3's by-role rule puts them in
       `:bootstrap`, where an adapter import is legitimate. Task 4.5 confirms
       the split; if any of the five turns out to be a use case rather than
       assembly, its inversion belongs to that task. Bootstrap-bound set
       43 -> 26. -->
  <!-- Pre-existing flake observed once, unrelated to this change:
       `GithubDecisionsSpec` "stale replies never resurface" failed in a full
       run and passed in isolation; nothing in this diff touches the github
       tracker. -->
  <!-- Note: PIT was not run for this task — per-module mutation scoping is
       task 8.1, and the whole-tree run is the 35m baseline. Task 9.1 is the
       gate. -->
- [x] 4.4 Invert the remaining collaborators — console (`DialogConsole`,
  `SystemConsoleIO`), pipeline loading, `DirectoryWorkspace`, the check runners,
  and the `ContainerEnvironments` docker probe (FR12b, D12)
  <!-- Verified: full `test` green (3235 tests), `spotlessCheck` and `buildHealth`
       green. Bootstrap-bound set 26 -> 9, and all nine remaining are composition
       by D3's by-role rule (see the last note). -->
  <!-- Bucket (a), relocations — four of the six named collaborators turned out
       not to be adapters. `DialogConsole` decorates an injected `ConsoleIO` with
       meta-command policy and owns no terminal; `SystemConsoleIO` wraps two
       streams its caller hands it in a `BufferedReader`/`PrintStream`, the same
       pure-JDK-wrapper category as task 4.2's `SystemClock`/`ThreadSleeper` —
       both to `app.console`, with `System.in`/`System.out`/`System.console()`
       named only at the composition root and in the two `@DoNotMutate` wiring
       methods. `DirectoryWorkspace` and `AttemptCommitWorkspace` are value
       wrappers over a path and a ref -> `app.workspace` (not `:domain`: the
       purity gate forbids `java.nio.file`). `FindingsSanitizer` and
       `TrackerFence` are pure text functions shared by app-layer report builders
       and adapters -> `app.findings`, which also lands task 5.3's
       `FindingsSanitizer` half early. -->
  <!-- Bucket (b), four real inversions. (1) Pipeline loading: new port
       `app.port.pipeline.PipelineSource` (project dir -> `LoadOutcome`), realized
       by `adapter.pipeline.GnomishDirPipelineSource` and bound in
       `ManualRunConfiguration`. It *replaces* rather than adds a parameter: the
       `Map<String, TrackerSubsectionValidator>` registry existed only to be
       handed to `PipelineLoader`, so the port closes over it and nine app files
       stop threading it. That the definition is YAML under `.gnomish/` is now the
       adapter's business. (2) Docker probe: `SandboxModeSelector`'s three-arg
       overload — pure production wiring, called from no production code — is
       gone; the composition root already injects the probe via
       `ManualRunRunner.dockerProbe`. (3) `JudgeEnvironmentSource.host(...)`:
       a port interface constructing its own adapter, lifted to
       `adapter.agent.HostJudgeEnvironmentSource` next to the round-source twin.
       (4) The container cluster: new port `app.port.run.SandboxRunSupport` with
       `SandboxRunPieces` moved beside it (now adapter-free); `ContainerRunSupport`
       is its single realization and the four container runners see the port
       alone. `SnapshotTipCheck.InterruptedVerification` moved to
       `app.port.git.PendingVerification` (D12(a), same treatment as task 4.3's
       `DivergenceOutcome`), and `FactoryCloneHardening`/`ContainerResumeBranch`
       are reached through `TaskBranchGit`, which gained
       `ensureLocalTaskBranch`. -->
  <!-- deviation: the check runners are NOT inverted. `FilesExistCheckRunner`,
       `ShellCommandCheckRunner`, `PinCheckedExternalCheckClient` and
       `GithubCheckClientFactory` are held only by `ManualRunAssembly`,
       `RunAssembler`, `ManualRunConfiguration` and `ManualRunRunner` — assembly,
       `@Configuration` and the `ApplicationRunner`, all `:bootstrap` by D3's
       by-role rule, where naming a check adapter is legitimate. Same for
       `PipelineLawReader`/`PipelineLaw` (`RunAssembler`,
       `ExecutorAdapterSelector`) and the `ContainerEnvironments` default probe
       (`ManualRunRunner`). D12 listed them because the pre-4.1 measurement did
       not yet separate assembly from use case. -->
  <!-- deviation: three methods stay on `ContainerRunSupport` outside the port —
       `lease()`, `salvage()`, `snapshotTipCheck()`, `branch()`. The port exposes
       the operations the use cases perform (`reattachFor`, `salvageLeftovers`,
       `pendingVerification`), not the docker-side collaborators they run through;
       the concrete class keeps the richer surface its own spec drives, so no
       `given/when/then` block moved. -->
  <!-- M5: spec edits are construction sites only, with one exception —
       `SandboxModeSelectorSpec`'s four `when:` invocations gained the explicit
       probe argument the deleted overload used to supply. Argument list only: no
       scenario name, `given/when/then` block or assertion changed, and each of
       the four throws before the probe is ever consulted, so the scripted `false`
       is behaviour-neutral. Three test fixtures were added rather than editing
       specs in place (`TrackerValidatorStub.acceptingGithubSource()`/
       `plainSource()`, `ContainerSupportFixture.real()`); the last one also works
       around the Groovy formatter rejecting a `::` method reference. -->
  <!-- Note: PIT was not run — per-module mutation scoping is task 8.1 and the
       whole-tree run is the 35m baseline; task 9.1 is the gate. -->
  <!-- Open for 4.5: `ManualRunAssembly` is the one composition class still held
       by application use cases (`GitModeRunner`, `GitResumeRunner`,
       `GitResumeContinuation`, the two container runners, `TakeBareAuto`,
       `TakeSlotRunner`). It needs the same treatment `ContainerRunSupport` just
       got — an application-owned `RunAssembly` port (`assemble`,
       `dialogConsole`, `withExtraListener`, `withSandbox`) implemented in
       `:bootstrap` — before the `:application` line can be drawn. -->
  <!-- The nine composition classes bound for `:bootstrap`: `ManualRunRunner`,
       `ManualRunConfiguration`, `ManualRunAssembly`, `RunAssembler`,
       `ExecutorAdapterSelector`, `ServeAssembly`, `ContainerRunSupport`,
       `ContainerRunSupportFactory`, `ContainerRunTermination` (the last two
       travel with `ContainerRunSupport`; `ManualRunDrive` travels with
       `ManualRunRunner`, whose package-private fields it reads). -->
- [x] 4.5 Create `:application` and move the use cases plus the port-only `app`,
  `board`, `dashboard`, `serveobservability`, `status` and `usage` files into it
  (FR3, D3, D11)
  <!-- Verified: full `test` green (3256 tests across the two modules),
       `spotlessCheck` and `buildHealth` green. Production split 365 in
       `:application` / 290 left in the root (the adapters plus the ten
       composition classes); `application/build.gradle` is 73 lines. -->
  <!-- Prerequisite done first: the `RunAssembly` port. `ManualRunAssembly` was
       the last composition class held by application use cases (`GitModeRunner`,
       `GitResumeRunner`, `GitResumeContinuation`, the two container runners,
       `TakeBareAuto`, `TakeSlotRunner`, the whole `take` dispatch chain — 33
       files). Its application-side surface measured out at exactly four methods
       (`assemble`, `dialogConsole`, `withExtraListener`, `withSandbox`); every
       field access (`assembly.systemClock`, `assembly.filesExistCheckRunner`, …)
       is inside `RunAssembler`, which travels with the implementation. `Run`
       became public as the port's return type — every component of it already
       was. Same treatment `ContainerRunSupport` got in task 4.4. -->
  <!-- Also inverted here: `ServeAssembly.worktreeJanitor` built a
       `WorktreeEnvironmentDisposal` over a fresh `GitProcessRunner`. That one
       construction site would have dragged `ServeAssembly` — and then
       `ServeRuntimeAssembly` and `ServeCommand` behind it — into the composition
       root. `TaskWorktreeGit` gained `environmentDisposal(cloneDir,
       worktreesRoot)` returning the existing `TaskEnvironmentDisposal` port, so
       the whole `serve` chain stays in `:application`. -->
  <!-- Root config-properties types (`FactoryProperties`, `ServeProperties`)
       moved with their consumers per D1's layer-home rule; `FactoryApplication`
       stays. The ten classes left behind for `:bootstrap`: `ManualRunRunner`,
       `ManualRunDrive`, `ManualRunConfiguration`, `ManualRunAssembly`,
       `RunAssembler`, `ExecutorAdapterSelector`, `ResumeDialogConsoleFactory`,
       `ContainerRunSupport`, `ContainerRunSupportFactory`,
       `ContainerRunTermination`. The set is closed — no `:application` class
       references any of them. -->
  <!-- deviation: `-parameters` moved into `java-conventions`. Spring tells the
       two `Path` beans (`worktreesRoot`, `homeDir`) apart by constructor
       parameter name; the Boot plugin supplied that flag implicitly while the
       composition root and every injected class were one module. Library
       modules apply no Boot plugin (D9), so `StatusCommand` lost its parameter
       names the moment it moved and the context failed to refresh with
       `NoUniqueBeanDefinitionException`. Now set for every module. -->
  <!-- deviation: the `com.github.oinsio.gnomish.app` package is split across
       `:application` and the root project, so the composition classes keep
       reaching application members that are package-private. Legal and stable
       on a flat classpath (same runtime package, one classloader) and it is
       what D2's "`:bootstrap` is the root remainder" sequencing implies, but it
       is the one place the module boundary rests on convention rather than on
       visibility. The alternative — a `..bootstrap` package — would have meant
       widening ~30 application types to public and churning far more than
       construction sites in the specs. Worth revisiting at task 4.7. -->
  <!-- Spec partition: a spec stays with the composition root if it imports an
       adapter or reaches a fixture that does (fixpoint, two rounds: 122 moved,
       17 came back). 171 specs in `:application`, 346 in the root. Five shared
       fixtures moved to `:test-fixtures` (`ApplicationArgumentsFixture`,
       `ScriptedConsoleIO`, `BlockingSleeper`, `VirtualMonotonicTime`,
       `RecordingKiller`), pulling task 7.1 forward again; `:test-fixtures`
       gained `api project(':application')`, which puts the layer on every
       module's TEST classpath — harmless (FR7 constrains production modules)
       and the price of one shared fixtures module (D8). The four reference
       documents moved with their specs, except `status-report-v1.reference.json`
       which both modules anchor against and which therefore went to
       `:test-fixtures` resources. -->
  <!-- No spec body changed: the partition is file moves plus the RunAssembly
       retype, which is a declared-type change at construction sites (M5). -->
- [x] 4.6 Add an ArchUnit rule: `:application` has no adapter import; run suite
  green (FR2, FR9, UX2, M6, D5)
  <!-- `ApplicationLayeringSpec` in `:application`: no class in any of the 24
       packages the module owns may depend on `..adapter..`, plus the
       `DomainPuritySpec`-style coverage guard so a package rename cannot make
       the gate pass vacuously (it caught one immediately — `..usage` holds no
       class of its own, only `..usage.json`). ArchUnit resolves a reference to
       a class outside the import scope into a stub with its real package name,
       so the rule sees an adapter dependency whether or not the adapter is on
       the classpath. -->
  <!-- Negative test: an `adapter.git` import added to an `:application` class
       does not reach the rule — it fails `compileJava` first, since the
       adapters are not on this module's compile classpath at all (D5's layer
       one). The rule mechanism itself was proven by temporarily pointing it at
       `..domain..`, a package the layer really does use: it failed, naming the
       rule, and passed again on revert. The end-to-end named-rule failure D5
       asks for is task 5.4's, once a sibling adapter module exists. -->
- [x] 4.7 *(after 5.1)* Extract `:bootstrap` from the root remainder —
  `@SpringBootApplication`, `main()`, all `@Configuration`, the assemblies and
  factories — and move `bootJar` plus the E2E layers with it (FR3, D3)
  <!-- A pure move: the whole `src/` tree (11 production files, 137 specs, the
       two resource files and the E2E fixture trees) became `bootstrap/src/`,
       and the project-specific half of the root build script — the Boot plugin
       and packaging, the dependency set, the E2E/Ollama/paid-smoke test layers,
       the PIT exclusions, the layering declaration — became
       `bootstrap/build.gradle`. No production or spec file changed. Verified:
       full `test` green (7m14) and `check` minus PIT green across all eight
       modules. The root project now holds no source at all, which is the
       spec's "no production Java class remains in the former single root
       module". -->
  <!-- The root build script keeps only what is build-wide: the
       dependency-analysis gate, the git-hook installer, and the Spotless format
       over the build files themselves. Those need `check` and the Spotless
       plugin without the `java` plugin, so they arrive through a new
       `build-metadata-conventions` plugin (`base` + Spotless), keeping D9's
       rule that a module applies convention plugins rather than repeating
       configuration. 590 lines -> 121. -->
  <!-- The buildscript CVE forces travelled with the Boot Gradle plugin: every
       artifact they name now resolves on `:bootstrap`'s plugin classpath, so
       that module carries its own locked `buildscript` block (new
       `bootstrap/buildscript-gradle.lockfile`) and the root keeps its own as
       the standing guard for the convention plugins' transitives. Without the
       copy, the Boot plugin's classpath — httpcore5, tools.jackson,
       commons-lang3 — would have gone both unlocked and unforced, i.e. dropped
       out of what OSV-Scanner reads. -->
  <!-- Fixed in passing: `paidSmokeTest`'s `referenceDumpDir` still pointed at
       `src/test/resources/stream-json-reference`, a path task 5.1 moved to
       `:adapters`. The task is outside `check` and never ran in CI, so the
       stale path had not surfaced; it now resolves the committed fixtures in
       `:adapters` where the spec records them. -->
  <!-- The PIT exclusion block moved verbatim, including the entries naming
       specs that now live in `:adapters` (the container-environment suites,
       `GithubTrackerContractSpec`, `ReferenceDumpHygieneSpec`). They are inert
       where they are — each module's PIT sees only its own test tree — but
       dropping them here would silently delete a documented exception. Task 8.1
       redistributes them along with each module's `targetClasses`, and it is
       also what brings this build file under the file-size cap task 9.2
       checks. -->
- [x] 4.8 Make `bootstrap` the sole Spring scan root; adapters export explicit
  configuration / factories, no cross-module scanning (FR3, NFR-R1, D3)
  <!-- Before: `@SpringBootApplication` sat in `com.github.oinsio.gnomish` with
       no `scanBasePackages`, so the component scan and the
       `@ConfigurationPropertiesScan` both swept the whole package tree — every
       module on the flat classpath, adapters included. `TrackerAdapterConfiguration`
       was discovered that way, which is precisely the cross-module scan D3
       forbids. -->
  <!-- After: `scanBasePackages = "com.github.oinsio.gnomish.app"` — the one
       package `:bootstrap` and `:application` share and no adapter package sits
       below, so the scan cannot reach an adapter however the classpath is laid
       out; `@ConfigurationPropertiesScan` (recursive by nature, so unscopable)
       is replaced by `@EnableConfigurationProperties` naming the four property
       records of `:application` and `:sandbox:core`. -->
  <!-- The adapter now states its own contribution: `TrackerAdapterConfiguration`
       is `@AutoConfiguration` listed in `:adapters`'
       `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
       (new catalog alias `spring-boot-autoconfigure`). Registration by name in
       a resource file rather than `@Import(TrackerAdapterConfiguration.class)`
       is what keeps the pre-existing `TrackerPortBoundarySpec` intact — that
       gate forbids any class outside `adapter.tracker` from naming a class
       inside it, and an `@Import` would have needed the rule relaxed (an
       assertion change M5 forbids). -->
  <!-- `BootstrapScanRootSpec` (new file) is the gate: it asserts the declared
       scan roots reach no adapter package, and that every class-level bean
       definition in the booted context is explained by a declared scan root, an
       explicitly exported auto-configuration, or a name the composition root
       writes itself — the distinction being between what Spring found by
       looking and what `:bootstrap` declared. Beans created by a `@Bean` factory
       method are deliberately out of scope: naming a concrete adapter there is
       the composition root's job. -->
  <!-- Startup wiring is unchanged (the FR3 scenario): the full suite, including
       the four `@SpringBootTest` context specs and the E2E layers that spawn the
       real packaged jar, is green with no spec-body edits. -->
- [x] 4.9 Assert the spec diff is construction-sites-only: no scenario name,
  `given/when/then` block, or assertion changed (FR9, M5)
  <!-- Measured over the whole change's test-source diff (`git diff -M adca151 --
       '*src/test*'`). Shape first: 660 pre-existing spec files are RENAMES, 8 are
       new, and ZERO are modified in place — every existing spec moved module, and
       only 7 renames fall below 90% similarity (the DTO-to-port retypes of task
       4.3 plus the container-dispatch construction sites). -->
  <!-- Content of those 660 renamed files: 620 import/package lines, 405
       everything-else, 21 comment lines, and — the M5 claim — 0 `given`/`when`/
       `then` block changes and 3 scenario-name lines across 2 files. Both files
       are the ones task 4.3 recorded in advance: `TaskJsonMapperSpec`'s "keeps
       outcome at the DTO level" and `RecordedStateReadbackSpec`'s
       "readFinalState()", each naming the very behavior the inversion replaced,
       so keeping the old name would have made the spec lie. -->
  <!-- Of the 405, 25 lines are assertion-shaped and 380 are construction. All 25
       are accounted for: 23 are task 4.3's `outcome().type() == 'completed'` ->
       `instanceof RecordedOutcome.Completed` and `result() == 'qualityFailure'`
       -> the domain enum (the port types carry no wire discriminator, by
       design); 1 is a data-table construction expression that happens to contain
       a ternary; 1 is task 5.3's `new GithubTrackerAdapterFactory(NO_SECRETS)`
       inside an assertion line — a constructor argument, not the assertion. -->
  <!-- The 8 added spec files are all new gates or new coverage, never rewrites of
       an existing spec: `ApplicationLayeringSpec` (4.6),
       `AdapterSiblingIsolationSpec` (5.2), `BootstrapScanRootSpec` (4.8),
       `SecretsPortBoundarySpec` (5.5), `ExecCommandSpec` (3.1),
       `TrackerHealthTrackerPassThroughSpec` (2.3) and two fixtures. -->
  <!-- New spec files are outside M5's budget by construction — M5 constrains
       edits to pre-existing specs — and every one of them was added because a
       per-module gate needs its assertion inside the module it constrains, or
       because per-module PIT (D6) needs a module's own specs to cover its own
       classes. -->
  <!-- Not asserted here, and deliberately so: `:bootstrap`'s build file is 100
       lines over the file-size cap while it still carries the PIT exclusions of
       specs that moved to `:adapters` (task 4.7). Task 9.2 is that gate, and
       task 8.1 is what removes the cause. -->

## 5. Move adapters and enforce boundaries

- [x] 5.1 Move the adapters (including the shared `adapter/check` runners, kept
  coarse) into a `:adapters` block depending on `:gnomish-plugin-api` +
  `:application` + `:sandbox:core` (FR1, FR2, D1)
  <!-- Verified: `check` minus PIT green across all seven modules (tests,
       `spotlessCheck`, `buildHealth`, the ArchUnit gates). 279 production
       classes moved; the root project is now exactly eleven files —
       `FactoryApplication` plus the ten composition classes task 4.5 left
       behind — so `:bootstrap` is the root remainder by construction, which is
       what D2's sequencing correction predicted. -->
  <!-- Dependencies as D1 specifies, plus the two the adapters use directly:
       `api` on `:application`, `:gnomish-plugin-api`, `:sandbox:core`,
       `:domain` and `:gitobjects`. Nothing depends on `:adapters` except the
       composition root (and `:test-fixtures`, test scope) — the acyclic
       direction FR2 asks for, made structural. Jackson, resilience4j and the
       YAML dataformat moved out of the root build script with the code that
       uses them; the root keeps Jackson `runtimeOnly` for the Boot fat jar. -->
  <!-- Test partition, same fixpoint rule as task 4.5. Eight adapter-side
       fixtures the composition root's integration specs also use went to
       `:test-fixtures` (`BareGitRepoFixture`, `ScriptedSandboxDocker`,
       `RecordingDockerCli`, `GuardImageAvailability`, `FakeAgentBinary`,
       `FakeAgentSupport`, `TrackerValidatorStub`, `DelegatingTracker`), plus
       `ReferenceDumpScrubber` and `GiteaAvailability` from the e2e tree. The
       nine `InMemoryTake*Spec` lifecycle suites went the other way, to the
       root, next to the `Take*SpecBase` classes they extend: they drive the
       whole take flow through the reference adapter, so they are integration
       specs of the composition root, not adapter unit specs. -->
  <!-- Resources: `stream-json-reference` and `.gnomish-fixtures/valid` moved
       to `:adapters`; the e2e fixture trees stayed. The `fake-agent` script and
       scenario library moved to `:test-fixtures` and are now resolved from disk
       via a `fakeAgentDir` system property instead of the classpath — inside a
       jar their URI is a `jar:` URI, which neither `sh <path>` nor a
       scenarios-directory walk can use. Same idiom `referenceDumpDir` already
       established; four specs that inlined the classpath lookup now call
       `FakeAgentBinary` instead. -->
  <!-- deviation, and the one real finding of this task: the split made the
       Docker-touching specs run CONCURRENTLY for the first time. While the tree
       was one module they all sat in one test task and were serialized by
       construction; now `:adapters:test` (the container-environment contract
       suites) and `:test` (the container-mode and Gitea E2E layers) are two
       tasks Gradle runs in parallel, and together they exhaust the daemon —
       the seed-clone helper exits non-zero after the clone itself has already
       reported success. Measured: each task passes alone (3m01 / 4m14), both
       pass under `--max-workers=1` (7m35), four container specs fail when they
       overlap (4m00). Fixed with a `DockerDaemonLock` build service
       (`maxParallelUsages = 1`) applied through a new `docker-test-conventions`
       plugin to exactly those two modules; every other module's tests still run
       in parallel. A lock rather than `mustRunAfter`: the tasks are genuinely
       unordered, they simply must not overlap. -->
  <!-- The five whole-tree boundary gates (`DomainPuritySpec`,
       `GithubAdapterBoundarySpec`, `ProcessSpawnBoundarySpec`,
       `TrackerPortBoundarySpec`, `GitObjectsBoundarySpec`) stay in the root
       test tree for now: the composition root has the widest classpath, which
       is what a whole-tree gate wants. Where they belong once the module-level
       rules exist is task 5.2's call. -->
  <!-- Flakes observed once each and passing on re-run, all in the
       real-filesystem / real-network families and none touching this diff:
       `GithubWorkflowJobsFetcherSpec` (WireMock dropped the connection —
       "header parser received no bytes"), `GithubTrackerContractSpec`,
       and a Spock `@TempDir` cleanup racing the macOS temp reaper in
       `InMemoryTakeLifecycleEscalateResumeSpec`. Two more of the same family
       are already recorded against tasks 3.1 and 4.3. -->
  <!-- Note: PIT was not run — per-module mutation scoping is task 8.1 and task
       9.1 is the gate. -->
- [x] 5.2 Add dependency-analysis + ArchUnit rules for the acyclic direction and
  no sibling-adapter-internal imports (FR2, UX2, M4, D5)
  <!-- Verified: `check` minus PIT green across all seven modules. Both halves
       run under `check` — `buildHealth` and `verifyModuleLayering` on every
       module, the ArchUnit gates under `test`. -->
  <!-- The acyclic direction is now data, one declaration per module. New
       `layering-conventions` plugin: a module states the complete set of sibling
       projects its production classpaths may reach, and `verifyModuleLayering`
       walks the resolved `compileClasspath`/`runtimeClasspath` graphs and fails
       naming the offending edge. Two things this buys over reading
       `build.gradle`: it is TRANSITIVE, so an edge cannot slip in by being
       inherited through an `api` dependency of a dependency; and it states the
       whole direction in one reviewable place per module. Declared sets:
       `:domain` and `:gitobjects` nothing; `:gnomish-plugin-api` `:domain`;
       `:sandbox:core` `:domain`; `:application` the four ports below it;
       `:adapters` those plus `:application`; the root everything, because being
       the one module allowed to reach the adapters is what makes it the
       composition root. -->
  <!-- The two gates answer different questions and neither subsumes the other:
       dependency-analysis reports MISDECLARED and UNUSED edges (declared set ==
       used set), never forbidden ones; `verifyModuleLayering` reports forbidden
       ones. Together they give FR2 both directions — nothing undeclared,
       nothing forbidden. -->
  <!-- Task 2.4's `:gnomish-plugin-api:verifyApiContractSurface` is replaced by
       the shared gate: identical assertion (`allowedProjects = [':domain']`),
       identical failure mode, now stated the same way by every module instead of
       once by hand. M3 is unchanged and still negative-tested — adding
       `:gitobjects` fails naming the module, the edge and the allowed set.
       `gnomish-plugin-api/README.md` re-points at the new task name. -->
  <!-- Test classpaths are deliberately out of scope for the layering gate:
       `:test-fixtures` legitimately pulls the layers it builds fixtures for onto
       every consuming module's test classpath (D8), while FR7 constrains
       production modules only. Keeping the gate on production scope is also what
       makes it prove FR7 — `:test-fixtures` never appears on a production
       classpath, and the gate would say so if it did. -->
  <!-- Sibling isolation: `AdapterSiblingIsolationSpec` in `:adapters`. Pass 1
       leaves every adapter in one Gradle module, so the compile classpath cannot
       draw that line yet — pass 2 (task 10) is what makes a sibling's types
       simply absent. The spec holds the line at the package level meanwhile: it
       encodes D1's concrete pass-2 cuts as seams (`github` = the vendor bundle
       of `adapter.github` + `tracker.github` + `check.github`, `git`, `agent`,
       and one shared `coarse` seam for everything D1 deliberately does not
       split), and fails any cross-seam dependency that is not on an explicit
       allowlist. A second scenario asserts the allowlist is exact — an entry
       that outlives its edge would hide the next regression. -->
  <!-- Measured residual set, eight package-to-package edges, each named with
       what removes it: `tracker.github -> secrets` and `check.github -> secrets`
       (D4 leak 3, task 5.3); `tracker -> tracker.github` (the `tracker.type`
       registry naming both concrete factories — composition that sits in
       `adapter.tracker` only because `TrackerPortBoundarySpec` forbids anything
       outside that package from naming a tracker adapter; task 4.8);
       `agent -> law`, `agent -> briefing` (coarse remainder packages the CLI
       executor and judge are written against; pass 2 decides whether they move
       with `:adapters:agent` or it depends on the coarse module);
       `agent -> environment`, `git -> environment` (become ordinary
       adapter-to-backend edges once task 6.1 carves `:sandbox:docker`); and
       `pipeline -> agent` (`PipelineModelBuilder` validating agent-cli settings
       at load time — NOT in D4's table, which only followed the github adapter's
       import closure, so this rule found it). M4 is therefore not met yet by
       construction; it is now enumerated and ratcheted rather than unknown. -->
  <!-- Both gates negative-tested. `:application` declaring `:adapters` fails
       `verifyModuleLayering` naming module, edge and allowed set. A
       `git -> github` import fails the ArchUnit rule naming the rule, both
       classes, the seam pair and the edge. Reverted in both cases; the
       end-to-end "fails `check`" form is task 5.4's. -->
  <!-- Fixed while here: both ArchUnit gates imported classes out of jars on the
       test classpath, so the shared fixtures in `:test-fixtures` — which sit in
       the very packages the rules select on — were being constrained as if they
       were production code. `DO_NOT_INCLUDE_JARS` alongside
       `DO_NOT_INCLUDE_TESTS` in `AdapterSiblingIsolationSpec` and
       `ApplicationLayeringSpec` (task 4.6). It was a live false-positive risk,
       not a hypothetical: `ApplicationArgumentsFixture`, `BlockingSleeper`,
       `RecordingKiller` and `ScriptedConsoleIO` all sit under
       `com.github.oinsio.gnomish.app`. -->
  <!-- The five whole-tree boundary gates (`DomainPuritySpec`,
       `GithubAdapterBoundarySpec`, `ProcessSpawnBoundarySpec`,
       `TrackerPortBoundarySpec`, `GitObjectsBoundarySpec`) stay in the root test
       tree, resolving the question task 5.1 deferred here. The root is the only
       module that sees every layer at once, which is exactly the scope a
       whole-tree gate wants; moving them down would narrow it for no
       requirement. The per-module gates added by tasks 4.6 and 5.2 sit in the
       module they constrain. -->
  <!-- Note: PIT was not run — per-module mutation scoping is task 8.1 and task
       9.1 is the gate. -->
- [x] 5.3 Resolve the five github→sibling-adapter leaks per the D4 table: relocate
  `TrackerSubsectionValidator` into the api and `ExternalCheckPinContributor`
  into `:application`, inject `SecretsProvider` / depend on the `Workspace` port
  instead of impls, move `FindingsSanitizer` to shared util (FR2, M4, D4)
  <!-- Four of the five leaks were already closed by earlier tasks, each as D4
       prescribed: `TrackerSubsectionValidator` -> the api (2.3),
       `ExternalCheckPinContributor` -> `app.port.check` (4.1),
       `AttemptCommitWorkspace` -> `app.workspace` behind the `Workspace` port
       (4.4), `FindingsSanitizer` -> `app.findings` (4.4). Re-verified against the
       tree: the github classes now import only `app.*` / `domain.*` types for all
       four. -->
  <!-- Leak 3 is the one this task closes. `GithubTrackerAdapterFactory` and
       `GithubCheckClientFactory` each carried a no-arg convenience constructor
       delegating to `new EnvFileSecretsProvider()` — a github-adapter-to-secrets-
       adapter edge, and a second, invisible decision about where secrets come
       from. Both constructors are gone; the provider is injected, which is what
       D4/DEC-11 asked for. -->
  <!-- The one production caller was `RunAssembler`, which built its own
       `new GithubCheckClientFactory()` mid-assembly. The factory is now a
       `ManualRunConfiguration` `@Bean` over the existing `SecretsProvider` bean,
       carried on `ManualRunAssembly` like every other collaborator and read as
       `assembly.githubCheckClientFactory`. The composition root therefore has ONE
       secrets provider instead of the three the convenience constructors could
       create, and the Vault/OIDC swap D4 anticipates is a one-bean change. -->
  <!-- Spec edits are construction sites only: eight `new GithubTrackerAdapterFactory()`
       call sites take a `NO_SECRETS` stub (every one of them either passes the
       token explicitly to the package-private `create` overload or asserts the
       fail-closed missing-token path, so the provider is never consulted), and
       five `ManualRunAssembly`/`ManualRunRunner` sites gain the factory argument
       built over `EnvFileSecretsProvider` — exactly what the deleted constructor
       did, so the "no resolvable token fails the assembly" scenario keeps its
       meaning. -->
  <!-- `AdapterSiblingIsolationSpec`'s allowlist loses both `-> secrets` entries,
       and its exactness scenario is what proved they were really gone: with the
       edges removed the stale entries failed the build immediately. Residual set
       is now six edges, all with pass-2 or task-6.1 owners. The
       `tracker -> tracker.github` entry's note is updated: task 4.8 made
       `TrackerAdapterConfiguration` an exported `@AutoConfiguration`, which
       removed the scan reach into it but not the edge — one registry naming two
       vendors is a pass-2 (10.1) problem. -->
- [x] 5.4 Verify a deliberate sibling-adapter-internal import fails `check` with a
  named rule, then revert it (FR2, UX2)
  <!-- A `check.github` class was given a reference to `adapter.git.GitProcessRunner`
       and `./gradlew :adapters:check` run end to end. It fails on `:adapters:test`
       with the rule named in full: "Rule 'classes that reside in a package
       'com.github.oinsio.gnomish.adapter..' should not reach another adapter
       seam's internals' was violated (1 times): ...GithubCheckPinPaths reaches
       ...GitProcessRunner across the 'github' / 'git' seam (check.github -> git)".
       The message names the rule, both classes, the seam pair, the
       package-to-package edge and what to do about it — UX2's "a build failure,
       not a review comment". Reverted; `:adapters:check` green again. -->
  <!-- Also observed, and worth recording: `spotlessCheck` failed first, before
       the boundary rule ever ran. The formatting gate is cheaper and runs
       earlier, so the honest statement is that a violating import fails `check`
       with the named rule once it is formatted — which is what the pre-commit
       hook guarantees. -->
- [x] 5.5 Assert adapters reach secrets only through the `SecretsProvider` port
  and no credential value appears in any module's build metadata (NFR-S1)
  <!-- `SecretsPortBoundarySpec` in `:bootstrap`, next to the other whole-tree
       gates, in two halves. (a) No class outside `..adapter.secrets..` may depend
       on it, exempting exactly `ManualRunConfiguration` — choosing the installed
       secrets backend is what a composition root is for, and naming the one
       exempt class keeps the exemption reviewable. (b) No credential name
       (`GNOMISH_GITHUB_TOKEN`, `GNOMISH_GITHUB_ACTIONS_TOKEN`, read from the
       adapters' own constants) appears in any build script, lockfile,
       `gradle.properties` or the version catalog. -->
  <!-- Jars are deliberately INCLUDED in the ArchUnit import here, unlike the
       module-scoped gates of tasks 4.6/5.2: the classes this rule is about reach
       `:bootstrap` as jars, and excluding them would leave it passing over eleven
       classes. A coverage scenario asserts the import really spans
       `adapter.secrets` and `adapter.tracker.github`, so the rule cannot go
       vacuous if the classpath shape changes. -->
  <!-- The metadata files are declared as `test` task inputs. Found the hard way:
       the first negative test PASSED because Gradle considered `test` up to date
       after a build-script edit — a gate that cannot observe its own subject.
       With the inputs declared, adding a credential name to `bootstrap/build.gradle`
       fails the spec and removing it goes green again. -->
  <!-- Both halves negative-tested and reverted. (a) an `EnvFileSecretsProvider`
       reference added to a `check.github` class fails naming the rule, the
       offending class and the exempted name. (b) as above. This gate is narrower
       than CI's Gitleaks scan and complements it: Gitleaks looks for
       secret-shaped strings anywhere, this fails the local build the moment a
       credential NAME enters build metadata, which is the step before a value
       does. -->

## 6. Sandbox backend split

- [x] 6.1 Extract `:sandbox:docker` (docker-CLI backend) depending on
  `:sandbox:core` (FR8, D7)
  <!-- The whole 34-class `adapter.environment` package moved as one, renamed to
       `com.github.oinsio.gnomish.sandbox.environment` (the rename D11 scheduled
       for this task: with the classes out of the `adapter.*` prefix, the
       `agent -> environment` and `git -> environment` residual edges vanish
       from `AdapterSiblingIsolationSpec`, whose allowlist shrinks 6 -> 4 and
       whose exactness scenario is what proved the edges really went).
       Verified: full `test` green (6m57 across all modules, including the new
       `:sandbox:docker:test`), `spotlessCheck`, `verifyModuleLayering` and
       `buildHealth` green. -->
  <!-- deviation: the HOST backend lives in `:sandbox:docker` too, not in a
       `:sandbox:host` sibling. Decisive, measured argument: the two backends
       share PACKAGE-PRIVATE subprocess mechanics by design (ChannelPathResolver
       and ChildProcessStdin are pp and used by both Host* and Container*; the
       docker CLI itself runs as a host process via HostExecHandle), so any
       per-backend cut widens that encapsulation to public — for a two-class
       module that buys neither of D1's module warrants. D11 ("the backends keep
       `adapter.environment` until task 6.1") and task 5.2's residual-edge note
       ("the host and container task environments become the `:sandbox:docker`
       backend at task 6.1") both already recorded this destination. The package
       is named `sandbox.environment`, not `sandbox.docker`, so the host classes'
       package does not lie; module-name/package mismatch has the `:sandbox:core`
       -> `sandbox` precedent. -->
  <!-- deviation: `:sandbox:docker` declares `api project(':application')` —
       wider than D1's ":sandbox:docker -> :sandbox:core" edge list.
       `ContainerEnvironmentReaper` (public) takes `app.serve.
       TaskEnvironmentDisposal` on its constructor and `ContainerEnvironmentDisposal`
       realizes it — the backend half-behaves as an adapter, and the edge is the
       same shape D1 gives every `:adapters` realization. Acyclic: `:application`
       reaches only `:sandbox:core` (its layering set is unchanged), which also
       keeps the spec's "use cases reach the port through core" scenario
       enforced at the module level — the half task 3.1 recorded as
       unobservable until now, together with "backend depends on core, not the
       reverse" (`:sandbox:core`'s allowed set is still `[':domain']`). -->
  <!-- Test partition, same fixpoint rule as tasks 4.5/5.1: the five
       docker-gated integration suites (ContainerGitMechanics,
       ContainerReadOnlySurfaces, ContainerTaskExecutionEnvironmentContract,
       EgressGuardIntegration, JvmProxyEgressE2E) import `adapter.git.*`
       production classes, so they stay in `:adapters` with their four image
       helpers (used by no one else) — `docker-test-conventions` therefore stays
       on `:adapters` and `:sandbox:docker` needs it not (its 23 moved unit
       specs drive fakes; DockerCliSpec scripts a fake `docker` binary). They
       keep the production package name in the test tree for package-private
       access — the same split-package convention `:test-fixtures` already uses
       for RecordingDockerCli. `TaskExecutionEnvironmentContract` moved to
       `:test-fixtures`: both the host spec (`:sandbox:docker`) and the container
       contract suite (`:adapters`) run it (FR7, D8). -->
  <!-- `:test-fixtures` gains `api project(':sandbox:docker')` (RecordingDockerCli
       extends the backend's DockerCli); `:adapters` gains it `api` (environment-
       source bridges and ContainerHarvestFetch name backend types on public
       signatures — the "ordinary adapter-to-backend dependency" 5.2 predicted);
       `:bootstrap` `implementation` (ContainerRunSupport, run assemblies).
       Jackson (core+databind+BOM) moved with GuardDenialLog's JSONL parsing —
       caught by `buildHealth`, which demanded the direct declaration. -->
  <!-- M5: spec churn is package/import lines only — measured with a
       per-file diff of every moved/renamed test file against adca151 ignoring
       `package`/`import` lines: zero non-import lines differ. Production churn
       is the package rename plus one package-info prose fix (its Segment
       paragraph still said "stays here" about types task 4.1 moved to core). -->
  <!-- Per-module PIT run for the new module (3.1 precedent, ahead of the 8.1
       sweep): first run 284/290 — six mutations in ContainerEnvironments
       (forTask, scrubsCredential, stopKeeping), EnvironmentLease.current and
       ContainerTaskExecutionEnvironment.exec's return were killed only by the
       integration specs now in other modules, exactly D6's cross-module
       coverage loss. Three NEW spec files close it (no existing spec edited,
       M5): ContainerEnvironmentsSeamSpec, EnvironmentLeaseCurrentSpec,
       ContainerExecHandleSpec. Now 290/290 killed, `pitestVerifyAllKilled`
       green — `:sandbox:docker:pitest` is 22s against the 35m whole-tree
       baseline (NFR-P1, M1). -->
  <!-- `:bootstrap`'s inert PIT `excludedTestClasses` entries naming the four
       container-environment suites were package-renamed with their specs and
       still await task 8.1's redistribution — the suites they name stayed in
       `:adapters`, whose own pitest scoping is also 8.1's. -->
- [x] 6.2 Run the existing execution-environment specs unchanged against the split
  modules; assert the `AdapterBinding` enum constants are unchanged (FR8, FR9)
  <!-- Both halves measured against the adca151 baseline. (a) Every
       execution-environment spec file — the 23 in `:sandbox:docker`, the five
       integration suites in `:adapters`, the contract trait and the three
       fixtures — differs from its baseline version ONLY in `package`/`import`
       lines (per-file diff ignoring those: zero lines), and the full suite is
       green across all modules (6m57). (b) `AdapterBinding`'s enum constants
       are byte-identical between `adca151:src/.../adapter/environment/
       AdapterBinding.java` and the current `sandbox/core` file — HOST and
       CONTAINER, same order, same passports; the enum stays closed (change C
       is what opens it). -->
  <!-- The spec's two module-direction scenarios are now fully observable and
       enforced where task 3.1 could only half-observe them: `:sandbox:docker`'s
       layering set proves "backend depends on core" and `:application`'s
       unchanged set (no backend entry) proves "use cases reach the port
       through core" — both fail `check` on regression. -->

## 7. Shared test fixtures

- [x] 7.1 Create `:test-fixtures` and move shared Spock fixtures into it (FR7, D8)
  <!-- done during task 2.1, which had to pull the module forward: the shared
       engine fakes and port-contract suites live in the domain test tree, so
       carving out `:domain` strands them otherwise. The module now holds 55
       fixture classes plus the fake-agent scenario library; no fixture simple
       name is duplicated anywhere in the build (checked across every source
       tree), and nothing is shared between modules except through it —
       test source sets are not visible across module boundaries, so the suite
       compiling is itself the proof. -->
- [x] 7.2 Consume `:test-fixtures` via `testImplementation`; assert no production
  module depends on it (FR7, D8)
  <!-- Every consuming module declares it `testImplementation` only (`:domain`,
       `:gitobjects`, `:gnomish-plugin-api`, `:application`, `:adapters`,
       `:sandbox:docker`, `:bootstrap`). The assertion is `layering-conventions`
       (D5): it walks the resolved PRODUCTION classpaths only, so a
       `testImplementation` edge is invisible to it while an `implementation` /
       `api` one fails `check` — no module's `allowedProjects` names
       `:test-fixtures`. -->
  <!-- gap closed here: `:gnomish-plugin-api:sample` was the one module with
       sources that did not apply `layering-conventions`, so FR7 was asserted
       everywhere but there. It now states
       `allowedProjects = [':domain', ':gnomish-plugin-api']` — the transitive
       closure of its single declared dependency (UX3). Verified the gate is
       not vacuous: adding `api project(':test-fixtures')` to the sample fails
       `verifyModuleLayering` naming `:test-fixtures`; reverted. `check` on the
       sample and `buildHealth` green. -->
  <!-- `:test-fixtures` itself applies no layering block: its production
       classpath legitimately reaches every layer it builds fixtures for
       (design D8), so the set would enumerate the whole build and assert
       nothing. Its edges stay reviewable as the `api project(...)` list in its
       own build script. -->

## 8. Per-module mutation scoping and quality-gates re-expression

- [x] 8.1 Bind each module's PIT `targetClasses` to its own Java production
  packages (never Groovy test bytecode) and wire `pitest` into that module's
  `check`, so root `check` aggregates all module runs (FR11, NFR-P1, D6)
  <!-- The PIT half of `test-conventions` became its own `pitest-conventions`
       plugin (one file = one thing; `test-conventions` applies it, so a module
       still gets the whole test lifecycle from one plugin id). Each module's
       `targetClasses` is now derived from its own `sourceSets.main.java` at
       configuration time — one glob per package it owns, not per class: PIT
       matches globs against the runtime class name, so `pkg.*` also covers the
       `Outer$Inner` forms an exact-FQCN list would silently miss. -->
  <!-- `:bootstrap`'s PIT exclusions were redistributed to the modules that own
       the named classes and specs, each with its rationale: `:application` gets
       `RealProcessTreeKiller` + `InstanceHeartbeatLifecycleSpec`; `:adapters`
       gets `ReferenceDumpHygieneSpec`, `GithubTrackerContractSpec` and the four
       docker-gated `sandbox.environment` suites; `:bootstrap` keeps
       `FactoryApplication`, `e2e.*`, `FactoryApplicationSpec` and the three
       `app.ContainerMode*E2ESpec`. Nothing is inert anywhere now — the state
       task 4.7 flagged and task 9.2 was waiting on. -->
  <!-- The build-wide `@DoNotMutate` rationale moved out of the build file into
       `.claude/rules/testing.md` ("Per-method exemptions"): it is a project
       rule about a PIT mechanism, not configuration of one module, and keeping
       it in `bootstrap/build.gradle` is what pushed that file 100 lines over
       the size cap. `bootstrap/build.gradle` 476 -> 366 lines. -->
  <!-- Finding, and the first real fix of this task: PIT's coverage and mutation
       minions are separate JVM launches that inherit NOTHING from the `test`
       task, so the `fakeAgentDir` property task 5.1 introduced never reached
       them — 41 fake-agent specs in `:adapters` (and ~15 in `:bootstrap`) failed
       PIT's pre-mutation green-suite check for a reason unrelated to any
       mutation. Supplied via `pitest.jvmArgs` in both modules rather than
       excluding the specs: the fixtures are a deterministic on-disk directory,
       and those specs are the only coverage several `adapter.agent` classes
       have. `repoRoot` (SecretsPortBoundarySpec's credential scan) travels the
       same way. This never surfaced earlier because tasks 4.3-6.1 all deferred
       PIT to this task. -->
  <!-- Per-module results: `:domain` 278/278, `:gitobjects` 69/69,
       `:gnomish-plugin-api` 55/55, `:sandbox:core` 63/63, `:sandbox:docker`
       290/290, `:bootstrap` 92/92, `:adapters` 1598/1598 — all 100%.
       `:application` is 1156/1453 and is the one open module; see task 8.5. -->
- [x] 8.2 Remove the whole-tree PIT task from `check` (keep an opt-in aggregate
  task); keep the scoped-target property working within a module, absent
  property = full module tree (FR11, NFR-P1, D6)
  <!-- No whole-tree PIT task remained to remove: with `targetClasses` bound per
       module and the gradle plugin mutating only each project's own output
       (`mutableCodePaths`), the union of the module runs IS the old whole-tree
       scope. The opt-in aggregate is `:pitestAll` in the root build script;
       every module contributes itself from `pitest-conventions`, and it is
       deliberately NOT wired into `check`, which already aggregates the same
       runs (wiring it would run each gate twice). -->
  <!-- `-PpitScope` now carries ONE flat class list for the whole build and each
       module keeps the globs matching its own production classes, so a module
       the diff did not touch skips its gate. All three spec scenarios verified
       live on `:sandbox:core`: own class narrows the run to it (`ExecCommand`,
       1 mutation), a sibling module's class and an empty value both skip
       cleanly, and no property mutates the full module (63/63 in 5s). -->
- [x] 8.3 Re-point CI mutation scoping: map changed production classes to their
  owning modules and run only those modules' mutation gates; docs-only diffs
  still pass cleanly (FR11, D6)
  <!-- The changed-class computation was stale after the split — it still read
       `src/main/java/**`, which matches nothing now. It strips the module prefix
       by matching on the `src/main/java/` segment rather than counting path
       components, so nested modules (`sandbox/core`) map correctly too. The
       module mapping D6 asks for is done by the modules themselves (task 8.2),
       not by CI: CI passes the whole list, each module keeps its own. Report
       upload paths globbed to `**/build/reports/...` — every module writes its
       own now. -->
  <!-- Verified locally: `./gradlew pitest -PpitScope=<one :domain class>` ran
       `:domain:pitest` (4/4 killed) and skipped the other seven modules. -->
- [x] 8.4 Document the single-module PIT invocation; verify a single-module change
  mutates only that module (NFR-P1, UX1, M1)
  <!-- README's Building section: `:module:check` / `:module:pitest`, the
       per-module report paths, `pitestAll` as the opt-in full report, and
       `-PpitScope` with the note that only an unset run guarantees whole-module
       coverage. The "mutates only that module" claim is the verification
       recorded in 8.3 above, plus `:sandbox:core:pitest` at 5s and
       `:sandbox:docker:pitest` at 22s against the 35m49s whole-tree baseline
       (NFR-P1, M1). -->

- [x] 8.5 Relocate specs to their owning modules and close `:adapters`' half of
  the cross-module coverage gap (FR11, D6, D13, M5)
  <!-- Opened by task 8.1's measurement, and the substantive finding of section
       8: per-module PIT showed 356 mutations across 60 `:application` classes
       with NO covering test in their own module. Cause: tasks 4.5/5.1 partitioned
       the specs by what COMPILES ("a spec stays with the composition root if it
       imports an adapter or reaches a fixture that does"), which answers where a
       spec MAY live, not whose coverage it carries. While PIT was whole-tree the
       difference was invisible. Treatment is D13's three-way classification;
       tasks 8.6/8.7 are its remaining two parts. -->
  <!-- D13(a), done: 14 spec files whose subject is an `:application` class and
       which need neither the composition root nor a real daemon moved to
       `:application` (Board/Dashboard/Status/Usage commands, PipelineStartup,
       RecordedStateReadback, ZombieFence, RestartCleanliness, RevocationHandler,
       HeartbeatConstantsSource, BoardCompositionAgreement). `TaskGitFixture` —
       referenced by 46 files across two modules — went to `:test-fixtures`
       (FR7, D8), which is what kept every stayer compiling. Closed 62 mutations;
       no spec body edited, so M5 is untouched. `:application` gains
       `testImplementation project(':adapters')` — TEST scope; already on that
       classpath transitively via `:test-fixtures`' api edges (D8), now stated
       because the moved specs use it directly (buildHealth). Production
       direction unchanged and still gated. -->
  <!-- Measured and NOT moved, deliberately: 31 specs reach `AppAssemblyFixture`,
       which builds a real `ManualRunAssembly`, and ~26 more reach one of those
       through a shared base. `ManualRunAssembly` names three `adapter.check`
       types on its own fields, so it is composition by D3's by-role rule and
       cannot leave `:bootstrap`; a spec that assembles the real run through it
       is a composition-root integration spec, which is exactly where task 4.5
       put it. Moving them into `:application` would have needed
       `:test-fixtures -> :bootstrap` and would have relabelled integration
       specs as unit specs to make a counter go green. -->
  <!-- `:adapters`' half of the same gap IS closed here: five new spec files
       (`GitTaskBranchesSpec`, `GitTaskWorktreesSpec`, `GitTaskStoreSpec`,
       `GnomishDirPipelineSourceSpec`, `InMemoryTerminalStateSpec`) cover the
       task-4.3 delegation facades, the pipeline source, and the four in-memory
       reference-adapter facts whose only killers were the `InMemoryTake*Spec`
       lifecycle suites in `:bootstrap`. 1573 -> 1598 of 1598 killed. New files
       only; no existing spec edited. -->
  <!-- Watch: `:adapters` showed one `RUN_ERROR` on `EnvironmentSalvage.salvage`
       and one `TIMED_OUT` on `JudgePromptBuilder.delimiterFor` in one run and
       neither in the next. The latter is the load-sensitive shape task 3.1
       already recorded (negating that `while` makes the loop non-terminating,
       so the only kill path is PIT's interrupt landing on the
       `isInterrupted()` guard). Both are `pitestVerifyAllKilled` failures when
       they occur; task 9.1 decides whether they need a documented exception. -->
- [x] 8.6 Exempt `:application`'s arid wiring classes from mutation, per class
  with written rationale (FR11, D13(b))
  <!-- The 17-class / 40-mutation group whose uncovered mutations are all
       delegation-shaped (`TakeCommandFactory`, `SubcommandDispatchFactory`,
       `ObservabilityWiring`, `ServeAssembly`, `ServeRuntimeAssembly`,
       `TakeCommandSeams`, `RunExceptionReporting`, …). A unit test killing such
       a mutant asserts "method calls method" — a change-detector. Same
       documented-exception discipline as `RealProcessTreeKiller`; verify each
       class really carries no decision before exempting it, and record the
       `:bootstrap` suite that covers it end-to-end. -->
  <!-- Measured baseline for section 8's remainder: `:application:pitest` is
       1156/1453 — 295 NO_COVERAGE and 2 SURVIVED across 46 classes. -->
  <!-- Exempted, FIVE classes / 11 mutations, each read through against the bar
       first: `TakeCommandFactory`, `TakeCommandSeams`, `SubcommandDispatchFactory`,
       `TakeClaimAndWorkFactory`, `ServeRuntimeAssembly`. Every one is
       straight-line construction — no conditional, no loop, no computed value —
       so each mutation is a nulled return of an object it just built or a
       removed hand-off between two objects it holds. Each entry names the
       `:bootstrap` suite that really drives it. -->
  <!-- deviation: the exempted set is 5 classes, not the 17 this task's note
       projected. The projection was made off the mutation SHAPE alone; read per
       class, twelve of the seventeen turned out to carry a decision after all
       and so fail D13(b)'s own bar. `RunExceptionReporting` classifies five
       exception families and prints a different line for each (UX3);
       `ObservabilityWiring.finalizeStopped` is a compare-and-set idempotence
       guard (one of the two SURVIVED mutations is inside it);
       `ObservabilityAssembly` binds the ForwardingDirtyNotifier that breaks its
       documented construction-order cycle and defaults an absent
       Implementation-Version to `dev`; `ServeAssembly`'s builders are already
       spec'd in `:application` for `shutdown`, and a class-level exclusion would
       have deleted that existing gate's mutations along with the new ones. All
       twelve moved to task 8.7 instead. -->
  <!-- The category, the mechanism (`excludedClasses`, not `@DoNotMutate`) and
       the three-point bar are now `.claude/rules/testing.md`, "Per-class
       exemptions (arid wiring)" — the same treatment task 8.1 gave the
       `@DoNotMutate` rationale, and what keeps `application/build.gradle` at
       150 lines rather than restating the reasoning five times. -->
  <!-- Fixed here, because this task is what introduces the case: a `-PpitScope`
       list matching ONLY exempted classes left `targetClasses` non-empty while
       PIT filtered it down to nothing, and PIT hard-fails on that with "No
       mutations found" — i.e. a CI diff confined to an arid-wiring class would
       have red-built on the quality-gates spec's "Empty scope is a clean pass"
       scenario. `pitest-conventions` now intersects the scope with what the
       module will MUTATE (own classes minus `excludedClasses`, resolved in
       `afterEvaluate` since the module sets its exclusions after the plugin
       applies). Verified live: the exempted-only scope skips and the build
       succeeds; an ordinary class (`RunExitCodeMapper`) still runs 1/1 killed. -->
- [x] 8.7 Write port-fake unit specs for `:application`'s decision-bearing
  orchestration; end state `:application:pitest` all KILLED (FR11, NFR-P1,
  D13(c), M5)
  <!-- The 29-class / 257-mutation group with real branching: `TakeTakeover`,
       `SubcommandDispatch`, `ServeShutdownWiring`, `GitResumeContinuation`,
       `ContainerResumeOutcomes`, `TakeResumeRunner`, `GitModeRunner`,
       `TakeClaimAndWork`, `ServeCommand`, `BareTakeClaimWalk`, and the rest of
       the take/resume/serve chain. New spec files driving the class through
       fakes of the ports it declares — never through `ManualRunAssembly`;
       outside M5's budget by construction. Per D13(c), a class whose branches
       turn out to be trivial guards over delegation may instead join task 8.6's
       exemptions with the same per-class record. Done when
       `:application:check` is green including `pitestVerifyAllKilled`. -->
  <!-- IN PROGRESS. Tranche 1 done and verified: 12 new spec files closed 25
       mutations, including BOTH of the module's SURVIVED mutants. The value/port
       leaves — `UnsupportedStateFileVersionException`, `AttemptCommitRef`,
       `InvalidTaskIdException`, `AttemptCommitWorkspace`,
       `ExternalCheckPinContributor.none`, `Round.roundListener` — plus
       `GitOutcomeRecorder` (the record-before-cleanup ORDER, which is the whole
       reason the pair exists), `GitFreshTaskSupport` (null base -> literal
       "HEAD"; a git fault on a FRESH run remapped to UsageException),
       `TakeRefResolution` (short-ref expansion through the declared type's
       factory only), `UsageReportJsonMapper`'s per-row executor wallMillis
       (unreported renders null, not 0 — the totals overload the existing spec
       asserts is a different method), and `ObservabilityWiring`'s two ends
       (`start`, and the final synchronous write `finalizeStopped` forces, which
       is what makes the file an operator is left with say `stopped`). Scoped PIT
       over those classes: 40/40 killed. New files only; no pre-existing spec
       edited (M5). Whole-module gate moved 1156/1453 -> 1181/1442 (82%): 11
       mutations left the scope with task 8.6's exemptions, 25 are now killed. -->
  <!-- Remaining is 261, not the 257 this task's note projected: the projection
       assumed all 17 classes of the arid group would be exempted by task 8.6,
       and twelve of them were not (see 8.6's deviation note), so their mutations
       land here instead — `ObservabilityAssembly` 6, `RunExceptionReporting` 5
       and `ServeAssembly` 3 among them. -->
  <!-- Finding that shapes the rest, and a correction to D13(c)'s premise: it
       says "driving the class through fakes of the ports it declares", but every
       one of the 29 remaining classes is `final` or a `record`, the stack has no
       byte-buddy/objenesis (Spock cannot mock a final Java class, and no library
       can subclass one), and most of them declare CONCRETE siblings rather than
       ports — `TakeDispatcher`, `GitResumeContinuation`, `ContainerTerminalDrive`,
       `ServeShutdown`, `FeedAutomaton`, `TakeSlotRunner`. So each spec has to
       construct the real sub-graph down to the nearest port and script it there.
       That is workable — it is how the tranche-1 and take-cluster classes are
       reachable — but it is not the cheap "fake the collaborator" shape D13(c)
       implies, and it is what makes the remainder a section of its own rather
       than a tail. -->
  <!-- Re-checked and NOT available: moving more specs down (D13(a)). The two
       remaining `:bootstrap` suites with no direct adapter import —
       `TakeDecisionResumeSpec`, `ContainerResumeRunnerSpec` — both inherit bases
       (`TakeResumeSpecBase` -> `ResumeSpecFixtureBase`, `ContainerResumeSpecBase`)
       that build real git adapters and `AppAssemblyFixture`, so task 8.5's
       fixpoint conclusion holds unchanged. -->
  <!-- Tranche 2 done and verified: the take claim/resume cluster, 12 classes / 94 mutations, now
       102/102 killed under a scoped run. Seven new spec files plus one shared trait
       (`TakeChainFakes`): `TakeTakeoverSpec` (the confirmation gate, the observed-version stale-claim
       removal, and the display-only last-beat age across its three scale boundaries and its
       clock-skew clamp), `TakeClaimAndWorkSpec` (the claim choke point — refusal, fresh-vs-resume
       routing, the heartbeat register/unregister frame, and the crash-to-abort funnel),
       `TakeFreshClaimSpec` (the first claim end to end: hygiene before creation, engine round,
       record-then-finish), `TakeResumeRoutingSpec` (the four ways an existing branch is disposed of
       — reconciled finish, reconciled park, decision dialog, ordinary resume — plus the revocation
       hand-off and salvage-vs-discard), `BareTakeClaimWalkSpec` (the three distinct "nothing taken"
       endings and the per-candidate open-front re-check), `TakeDispositionMatrixSpec` (the
       tracker-state matrix and its operator-facing refusals) and `TakeBareAutoFeedReadSpec` (the
       feed read and the decline sweep's position in it). New files only; no pre-existing spec
       edited (M5). -->
  <!-- The shape that made it work, and the answer to the D13(c) premise problem above: build the
       REAL object graph and script it at the nearest port, then stop each scenario at the first
       port call of the path it is asserting. Three seams carry almost all of it — a `Tracker`
       answering `Held` refuses before any git call; a git port throwing a `UsageException` marks
       exactly which branch was taken; and a `RunAssembly` fake handing back the domain's own
       scripted engine ports (`ScriptedExecutor` + `ScriptedBuiltinCheckRunner`) runs a whole
       pipeline to Completed/Escalated with no working copy beyond an empty temp directory. That
       last one is what made the terminal boundaries (record, cleanup, finish, park,
       confirmTerminalWrite) assertable at unit speed. -->
  <!-- `TakeChainFakes` is a spec-local trait in `:application`, not a `:test-fixtures` addition:
       nothing outside this module can construct these package-private types, so FR7's shared-fixture
       rule does not reach it. Its `RunAssembly` fake is hand-written rather than a Spock `Stub`
       because Spock forbids mock creation outside a feature's own lifetime, which a trait helper is
       outside of. -->
  <!-- Two specs are named for what they assert rather than for their subject —
       `TakeDispositionMatrixSpec`, `TakeBareAutoFeedReadSpec` — because `:bootstrap` already has a
       `TakeDispositionSpec` and a `TakeBareAutoSpec` driving the same classes end to end, and the
       build keeps spec simple names unique. -->
  <!-- Whole-module gate after tranche 2: 1275/1442 (88%), up from 1181/1442. `:application:test`,
       `spotlessCheck` and `buildHealth` green. -->
  <!-- Tranches 3-5 done and verified, closing the remaining 167. Nine more spec files, one spec
       moved down, and seven integration-covered exemptions. By cluster:
       (a) git run, 38/38 — `GitModeRunnerFreshRunSpec` (banner before the run, hygiene before
       creation, record-and-dispose on both a completed and an aborted run) and
       `GitResumeRoutingSpec` (all five recorded outcomes: continue-from-position with
       salvage-vs-discard, report-only, the escalation dialog with and without an answer, the
       checkpoint confirmation, and the refusal to resume an Aborted branch).
       (b) container, 38/38 — `ContainerTerminalDriveDisposalSpec` (the disposal decision: sweep
       first, dispose only on a clean completion, keep stopped otherwise),
       `ContainerGitModeRunnerSpec` and `ContainerResumeRoutingSpec` (the same five outcomes as the
       host path plus the sandbox-only one: a pending verification reattaches but must NOT salvage,
       since the round is already complete on the branch).
       (c) dispatch, 20/20 of the reachable part — `TakeRefDispatchSpec` covers the bare/explicit/
       batch mode boundary, the aggregate exit code, the foreign-ref refusal and the batch summary
       being logged before the exit code is thrown.
       (d) `RunExceptionReportingSpec` — all five UX3 families plus the two deliberately quiet ones
       (a task-not-found already printed; an interruption is a lifecycle signal), each asserting the
       exception travels on UNCHANGED so the exit-code mapping cannot drift.
       (e) `ServeAssemblyBuildersSpec` — the three leaf builders `ServeAssemblySpec` does not cover,
       each proving the collaborator is wired over the caller's own objects. -->
  <!-- One spec MOVED rather than rewritten (D13(a), the preferred treatment):
       `ObservabilityAssemblySpec` declared `implements AppAssemblyFixture` but used nothing from it
       except a FactoryProperties builder, so it was not a composition-root spec at all — it moved
       to `:application` and closed that class's 6 mutations outright. The builder now sits in
       `RunChainFakes`, so the diff to the moved spec is its `implements` clause alone. -->
  <!-- Seven classes exempted as INTEGRATION-COVERED (D13(c)'s own escape hatch), each naming its
       suite and scenario count in `application/build.gradle`: `ServeShutdownWiring` (12 scenarios),
       `ServeCommand` (10), `TakeSlotRunner` (9), `SubcommandDispatch` (10), `TakeCommand` (10).
       The test applied per class, in the order the rule now states — move the spec down, else write
       a port-fake spec, else exempt: each of these suites builds a real `TakeSlotRunner` or the six
       command classes over a real git clone and the composition root's own assembly, which task 8.5
       established is a composition-root integration spec by D3's by-role rule, so none can move;
       and their collaborators are final classes exposing no state for their hand-offs, so a
       same-module spec could assert only "method calls method" over hand-built stand-ins. The
       category and its three-point bar are now `.claude/rules/testing.md`, "Per-class exemptions
       (integration-covered)". -->
  <!-- Shape notes worth keeping. The trait grew from `TakeChainFakes` to `RunChainFakes` as the
       git-mode and container-mode chains joined: it now also supplies a REAL `RunnerOutcomeLoop`
       over a scripted console, which is what lets a resume dialog be answered and asserted at unit
       speed. Two Spock limits shaped the fakes: mock creation is illegal outside a feature's
       lifetime (so the `RunAssembly` fakes are hand-written maps, not `Stub`s), and a stub scripted
       in `setup()` cannot be re-scripted in a scenario (so the values a scenario varies —
       persistence, task record, recorded state — are fields the setup stub reads through a
       closure). -->
  <!-- Two spec names deliberately differ from their subject, for the same reason tranche 2's did:
       `:bootstrap` already owns `ContainerTerminalDriveSpec` and `GitModeRunnerSpec`, so the
       new ones are `ContainerTerminalDriveDisposalSpec` and `GitModeRunnerFreshRunSpec`. -->
  <!-- DONE: `:application:check` is green including `pitestVerifyAllKilled` — 1378/1378 mutations
       KILLED, 100%, which is this task's stated done-criterion. From the 8.6 baseline of
       1156/1453: 219 mutations closed by 21 new spec files plus one relocated one, and 75 removed
       from scope by 12 per-class exemptions (5 arid-wiring in task 8.6, 7 integration-covered
       here), every one of them individually reviewable in `application/build.gradle`. -->
  <!-- Whole-build re-verification after the tranche: `test`, `spotlessCheck` and `buildHealth`
       green across all eight modules — in particular `:bootstrap` still compiles and passes with
       `ObservabilityAssemblySpec` gone from its tree. No pre-existing spec was edited anywhere in
       task 8.7 (M5); the one moved file's diff is its `implements` clause. -->
  <!-- Remaining after tranche 2: 167 mutations across 20 classes, by cluster — serve
       (`ServeShutdownWiring` 24, `ServeCommand` 11, `TakeSlotRunner` 7,
       `ObservabilityAssembly` 6, `ServeAssembly` 3); take dispatch
       (`SubcommandDispatch` 19, `TakeDispatcher` 7, `TakeRefDispatch` 4,
       `TakeCommand` 3, `TakeBatch` 2); git run
       (`GitResumeContinuation` 19, `GitModeRunner` 12, `GitResumeRunner` 7);
       container (`ContainerResumeOutcomes` 17, `ContainerResumeRunner` 7,
       `ContainerGitModeRunner` 7, `ContainerTerminalDrive` 7); and
       `RunExceptionReporting` 5. Each is classified per class on the recorded
       sieve — a spec by default, D13(c)'s integration-covered exemption only
       where the branches really are guards over delegation, naming the
       `:bootstrap` suite. -->

## 9. Pass-1 verification

- [x] 9.1 Run the full suite; assert all pre-existing specs pass with no spec-file
  edits (FR9, M5)
  <!-- `./gradlew clean` then `./gradlew build --no-build-cache` — every module's
       `check` (Spock, Spotless, ArchUnit gates, JaCoCo, PIT +
       `pitestVerifyAllKilled`) plus `buildHealth` and the packaging, with the
       build cache disabled so nothing is inherited from an earlier run. GREEN in
       12m58s: 4364 tests, 0 failures, 7 skipped, and 3822/3822 mutations killed
       across the eight modules — `:domain` 278, `:gitobjects` 67,
       `:gnomish-plugin-api` 55, `:sandbox:core` 63, `:sandbox:docker` 290,
       `:application` 1378, `:adapters` 1599, `:bootstrap` 92. The 7 skips are
       the pre-existing interactive/console and reference-dump `@IgnoreIf`
       guards, unchanged by this change. -->
  <!-- M5 re-measured over the whole change's test-source diff, now including
       sections 8 and 9 (`git diff -M -l0 adca151`, classifying by the file's OLD
       path so the fixtures that moved to `test-fixtures/src/main` count as the
       renames they are — the narrower `-- '*src/test*'` pathspec task 4.9 used
       reports those as deletions): 743 pre-existing test files RENAMED, 43 new,
       ZERO modified in place and zero deleted. Content of the renames: 739
       import/package lines, 438 construction-site lines, 54 comments, 0
       `given`/`when`/`then` block changes, and the same 3 scenario-name changes
       in the same 2 files task 4.9 accounted for. FR9/M5 hold unchanged. -->
  <!-- Three runs were needed; each failure was a real defect this task exists to
       find, and none was a spec failure. All three were mutation-gate failures
       of the "mutant hangs instead of failing" family, and all three are fixed at
       the source, which is what `pitest-conventions` itself prescribes over
       raising the timeout budget. -->
  <!-- (1) `JudgePromptBuilder.delimiterFor` TIMED_OUT — the flake tasks 3.1 and
       8.5 recorded and deferred to this task for a decision. Negating the
       `content.contains(delimiter)` check makes the delimiter-growing loop
       non-terminating, so its only kill path was PIT's interrupt landing on an
       `isInterrupted()` guard: a race, lost under 10 concurrent minions. The loop
       now carries a named bound, `canStillOccurIn(delimiter, content)`, which is
       behaviour-NEUTRAL — a delimiter longer than the content cannot occur in it,
       so it is already true whenever `contains` is — but makes termination
       structural: the negated mutant exits within `content.length()` steps and
       fails two specs outright. Scoped PIT: 9/9 killed, no timeout. The `<=`/`<`
       boundary inside that one-line predicate IS a provably equivalent mutant
       (the two forms can differ only when the content IS the delimiter, which the
       one caller — a rendered section block with its own heading — cannot
       produce), so it carries `@DoNotMutate` with that trace, exactly as
       `GitExec.hasRemainingCapacity` already does. Extracting it is what keeps
       the exemption to that one boundary: the branch on its result stays mutable
       in `delimiterFor` and is killed there. -->
  <!-- (2) `GitExec.feed` TIMED_OUT on "removed call to Thread::start". Without
       the pump thread nobody closes the subprocess's stdin, so every git command
       that reads stdin blocks forever — and `await` has no deadline, so whichever
       covering spec ran first hung until PIT's timeout instead of failing. Fixed
       by construction rather than exempted: the thread is now built with
       `Thread.ofPlatform().name(...).daemon(true).start(runnable)`, so "daemon"
       and "started" are part of constructing it and every call in the chain
       returns a value — there is no void call left for the mutator to remove.
       `:gitobjects` 26/26 on `GitExec` afterwards (67 for the module, two fewer
       than the previous 69 because those two void calls no longer exist). -->
  <!-- (3) `:adapters:pitest` aborted on "1 test did not pass without mutation":
       `JvmProxyEgressE2ESpec` failed PIT's pre-mutation green-suite check while
       `:bootstrap`'s own Docker layers were running. It is the FIFTH Docker-gated
       suite of the `sandbox.environment` package — the other four are already
       excluded from PIT's test scan — and was simply missed when task 8.1
       redistributed the exclusions. Added to that family with the rationale. Root
       cause worth recording: `DockerDaemonLock` (task 5.1) serializes `test`
       tasks across modules, but PIT's coverage and mutation minions are separate
       JVM launches outside Gradle's task graph, so `:adapters:pitest` and
       `:bootstrap:test` can still reach the daemon at once. Excluding the suite
       is the same treatment its four siblings have; making PIT take the lock
       would serialize the build's two longest tasks against each other. -->
  <!-- All three production edits are behaviour-preserving in the FR9 sense — no
       capability changed, and every pre-existing spec passes unedited. Two of
       them (1 and 2) delete a mutant's ability to hang rather than documenting an
       exception for it, which is the direction `.claude/rules/testing.md` asks
       for; the third completes an existing documented exclusion family. -->
  <!-- Note: the first attempt ran with the local build cache warm and finished
       in 8m52s with 356/471 tasks served from cache. That number is NOT the
       evidence for either this task or 9.3 — a cache-served `pitest` proves
       nothing about the gate — which is why every subsequent run used
       `--no-build-cache`. -->
- [x] 9.2 Assert each module build file is within the file-size cap and the
  monolithic `build.gradle` is gone (FR6, M2)
  <!-- `ModuleBuildFileSpec` in `:bootstrap` is the gate, next to
       `SecretsPortBoundarySpec`, which already wires `repoRoot` and declares the
       build metadata as an input of that module's `test` task — so editing a
       build script really does re-run it (verified: the negative test below
       re-ran on a build-file edit alone). Three scenarios: every `*.gradle` in
       the build is within the 200-line hard cap; every main-build module build
       file declares at least one `*-conventions` plugin id (M2's "convention
       plugins plus thin per-module build files"), with a count guard so a
       mis-resolved `repoRoot` cannot pass vacuously; and the root project holds
       no `src/` and its build file names no `java` plugin and no `dependencies`
       block — the monolith is gone, not merely renamed. Negative-tested: 200
       filler lines appended to `gitobjects/build.gradle` fails the cap scenario
       naming the file and its length. -->
  <!-- Two files were over the cap and are now under it, by moving content
       rather than deleting it. `bootstrap/build.gradle` 378 -> 186: the `test`
       task's E2E wiring, the module's PIT exclusions and the two manual layers
       (`ollamaE2eTest`, `paidSmokeTest`) moved to `bootstrap/verification.gradle`
       (151), a module-local script plugin — the build file now states what the
       module IS, the script plugin how it is CHECKED. A local script plugin, not
       a `build-logic` convention plugin: nothing there is shared, and Spotless's
       `*/*.gradle` target already covers it. `pitest-conventions.gradle`
       230 -> 200 by compressing prose only; no configuration changed. -->
  <!-- The `excludedTestClasses` rationale (~45 lines of it) moved to
       `.claude/rules/testing.md`, "Excluded test classes (out-of-process
       suites)" — the same treatment task 8.1 gave `@DoNotMutate` and task 8.6
       the two `excludedClasses` categories. It is a project rule about a PIT
       mechanism, not configuration of one module, and it now also records the
       distinction the fix in task 8.1 turned on: a deterministic on-disk path
       (`fakeAgentDir`, `repoRoot`) is handed to the minions via `jvmArgs`, and
       only a build artifact PIT cannot be handed (`e2e.jarPath`) justifies
       excluding the suite. -->
  <!-- Measured after the move — every build file in the tree: `:bootstrap` 186,
       `:adapters` 185, `:application` 178, `bootstrap/verification.gradle` 151,
       root 147, `published-api-conventions` 125, `java-conventions` 112,
       `:test-fixtures` 94, `layering-conventions` 87, `:sandbox:docker` 71,
       `settings.gradle` 70, and the rest below 60. The four largest are the
       three widest modules plus the convention plugin with the most engine
       configuration; all are within the cap, none within the 100-120 target,
       which the cap scenario deliberately does not enforce (the rule reserves
       200 for where splitting would hurt clarity). -->
  <!-- The pre-split monolith was 796 lines (the spec's own figure) plus a root
       `src/` tree; it is now 147 lines of build-wide metadata — the
       dependency-analysis gate, the git-hook installer, `pitestAll` and the
       Spotless format over the build files — with no `java` plugin and no
       source, which is what the third scenario pins. -->
  <!-- deviation: the gate covers `build-logic`'s convention plugins in the cap
       scenario but not the convention-plugin scenario — they ARE the shared
       build logic, so requiring them to apply one would be circular.
       `build-logic/build.gradle` and its `settings.gradle` are likewise only
       size-checked. -->
  <!-- Note: a forward `tasks.named('pitestReportLocation')` inside the
       `tasks.named('pitest')` block would have saved four lines in
       `pitest-conventions` and was reverted — the task is registered later in
       the file, and `tasks.named` on an unregistered name throws. Prose was
       compressed instead. -->
  <!-- deviation: the cap check is stated as `<= 200` (the hard cap of
       `.claude/rules/process-invariants.md`), not the 100-120 target.
       `pitest-conventions.gradle` sits exactly at 200. -->
  <!-- Verified: `:bootstrap:test --tests '*ModuleBuildFileSpec*'` green (3/3),
       `spotlessCheck` green across all modules; the full-suite and clean-build
       evidence is task 9.1/9.3's. -->
- [x] 9.3 Measure clean-build wall-time against the pre-split baseline (NFR-P2)
  <!-- 12m58s versus the 35m49s monolith baseline task 1.4 recorded — 64% faster,
       so NFR-P2's "SHALL NOT regress" is met with a wide margin. Same machine,
       same `check` content, and the module run is the more demanding of the two:
       `clean` + `build --no-build-cache` (assemble AND check, cache disabled)
       against the baseline's `check`. -->
  <!-- Like-for-like on the dominant term: PIT is most of both numbers and the
       mutation count is comparable — 3867 then, 3822 now (12 per-class
       exemptions from tasks 8.6/8.7 removed 75, section 8's new specs and the
       task-9.1 fixes account for the rest), so the speedup is not a smaller
       workload. What produces it is D6's per-module scoping: eight `pitest` runs
       Gradle schedules in parallel against one whole-tree run, with each module's
       tests, JaCoCo and Spotless overlapping the others'. -->
  <!-- Second data point for M1/NFR-P1, recorded here because it is the same
       measurement from the other end: with the build cache warm the same command
       is 8m52s, and a single-module gate is seconds — `:sandbox:core:pitest` 5s,
       `:sandbox:docker:pitest` 22s (task 8.4). The whole-tree 35m49s is now the
       cost only of a from-scratch full verification, not of touching one
       module. -->

## 10. Pass 2 — vertical adapter split

- [x] 10.1 Extract `:adapters:github` bundling `github` (shared http, internal
  package) + `tracker/github` + `check/github` into one vendor module (FR10, D1)
  <!-- Verified: full `./gradlew check` GREEN in 12m49s — every module's tests,
       Spotless, ArchUnit gates, JaCoCo and PIT plus `buildHealth`. 3822/3822
       mutations killed across nine modules, the new `:adapters:github` carrying
       432 of them (`:adapters` 1165, `:application` 1378, `:sandbox:docker` 290,
       `:domain` 278, `:bootstrap` 94, `:gitobjects` 67, `:sandbox:core` 63,
       `:gnomish-plugin-api` 55). `:adapters:github` runs 398 tests of its own.
       The whole-build total is unchanged from task 9.1 — the vendor cut moved
       mutations between modules, it did not add or drop any. -->
  <!-- A pure move for the 70 production classes and 49 specs of the three
       packages; `:adapters` itself stays, as the coarse remainder D1 does not
       split (plus `tracker/inmemory`, the in-tree reference adapter) and as the
       parent of the modules leaving it. `AdapterSiblingIsolationSpec` loses the
       `github` seam entry: what it enforced at the package level is now Gradle's
       compile classpath — neither module depends on the other, so a sibling's
       types are simply absent, which is what pass 2 buys (FR2, M4). -->
  <!-- The one real decision: `TrackerAdapterConfiguration`, the `tracker.type`
       registry, is the `tracker -> tracker.github` residual edge task 5.2
       enumerated, and it cannot stay in `:adapters` — it names one factory from
       each side of the cut. It moved to `:bootstrap`, keeping its
       `adapter.tracker` package. That is D3's by-role rule (a registry naming
       concrete adapters is composition) and `:bootstrap` is now the only module
       that sees both vendors; the package has to stay because
       `TrackerPortBoundarySpec` forbids any class outside `..adapter.tracker..`
       from naming one inside it. It stays an `@AutoConfiguration` registered by
       name — the `META-INF/spring/...AutoConfiguration.imports` resource moved
       with it — since `@Import` from the composition root would name it from
       outside and trip that same gate (task 4.8's reasoning, unchanged).
       `adapter.tracker` is left with no class in `:adapters`, so nothing is
       split across modules. Its spec moved with it, a file move only. -->
  <!-- deviation: task 5.2's note predicted pass 2 would have "each vendor module
       contribute its own entry". Not done, and deliberately: Spring's
       name-keyed `Map<String, T>` injection cannot express it — the factory and
       the validator registries are both keyed by `"github"`, and two beans
       cannot share a name — so contributing per vendor needs a registration SPI
       type, which is discovery, i.e. change B (NG2), and an api addition FR9
       forbids here. Pass 2 only needs the edge gone; D3 already says where
       composition lives. -->
  <!-- Dependency-analysis found two things the cut made visible. `:adapters`
       now uses no Spring and no resilience4j at all — both were the GitHub HTTP
       client's and the registry's — and its WireMock edge drops to
       `testRuntimeOnly`, the compile-scope users having left with the module.
       In `:adapters:github` the Jackson artifacts are `implementation`, not the
       `api` they were in the coarse block: the DTOs expose no Jackson type, so
       the vendor bundle publishes a smaller surface than the block it left. -->
  <!-- `:adapters:github`'s allowed reach is `:application`, `:domain`,
       `:gitobjects`, `:gnomish-plugin-api`, `:sandbox:core` — the ports below
       it and nothing sideways; the GithubTrackerContractSpec PIT exclusion
       travelled with the spec it names. -->
- [x] 10.2 Extract `:adapters:git` and `:adapters:agent`; keep `tracker/inmemory`
  in-tree and the small adapters coarse (FR10, D1)
  <!-- `:adapters:git` (72 production classes, D1's largest technology seam) is a
       clean leaf: no adapter package reaches into it and it reaches none — the
       cut needed no relocation at all. Its five Docker-gated `sandbox.environment`
       suites travelled with it (each stands up a bare repo and drives
       `ContainerHarvestFetch` / `GitProcessRunner` / `EnvironmentSalvage` against
       a live container, so this module's classes are their real subject), and
       `docker-test-conventions` with them. -->
  <!-- `:adapters:agent` (36 classes) needed one edge resolved in each direction.
       Inbound: `pipeline -> agent`, the edge task 5.2's rule found and D4's spike
       missed — `AgentSettingsValidator` moved to `adapter.pipeline`, beside its
       only caller `PipelineModelBuilder`. It is load-time validation of one config
       subsection, the same role `TrackerSubsectionValidator` plays for
       `tracker.<type>`, and it names no agent class in code (two javadoc `{@link}`s
       became `{@code}`). Outbound: `agent -> law` and `agent -> briefing` stay, as
       a module dependency on `:adapters` — D1's own "pass 2 decides whether
       `:adapters:agent` depends on the coarse module or the two move with it",
       decided the first way because `adapter.law` and `adapter.briefing` are shared
       with the console and would be misfiled inside the executor. -->
  <!-- `AdapterSiblingIsolationSpec` became `AgentCoarseReachSpec`, moved into
       `:adapters:agent`. The old rule stood in for Gradle while every adapter
       shared one module; every edge it tracked is now gone or structural, so what
       is left to assert is the one thing Gradle can only state at module
       granularity — a dependency on `:adapters` buys reach into every coarse
       package, and the rule narrows it back to the two declared. Its
       exactness scenario carries over: an allowance that outlives its edge fails
       the build. -->
  <!-- `:adapters` is now the coarse remainder as D1 defines it — console,
       `.gnomish/` loader and validators, shared check runners, secrets, law,
       briefing, engine, plus `tracker/inmemory` in-tree (DEC-10). It sheds
       `docker-test-conventions`, WireMock, the Jetty BOM and ArchUnit with the
       specs that needed them; 185 lines -> 89. -->
  <!-- Dependency-analysis corrected four declarations the cut made measurable:
       `:adapters:agent` reaches `:sandbox:docker` only inside the sources that
       build one (`implementation`, not `api`); `:adapters:git`'s state DTOs really
       are on its api surface, so Jackson travels with them; and `:test-fixtures`
       needs `:adapters:git` at `implementation` and no `:adapters:agent` edge at
       all — `FakeAgentBinary` names the agent adapter in prose and in the scripts
       it lays down, never in bytecode. -->
  <!-- Test-scope sibling edges, deliberate and out of the layering gate's scope
       (it is production-only, by design — task 5.2): `:adapters:agent` tests
       against `:adapters:git`'s `SnapshotTipCheck` in one resume spec, and
       `:application` reads what the git adapter wrote in seven. No production
       class in either module names a sibling. -->
- [x] 10.3 Re-run boundary rules and the full suite green after the vertical split
  (FR2, FR9, FR10, M4)
  <!-- `./gradlew clean` then `build --no-build-cache` — every module's `check`
       (Spock, Spotless, the ArchUnit and layering gates, JaCoCo, PIT +
       `pitestVerifyAllKilled`) plus `buildHealth` and the packaging, cache
       disabled so nothing is inherited. GREEN in 13m32s, 600 of 609 tasks
       executed: 4364 tests, 0 failures, 7 skipped, and 3822/3822 mutations killed
       across ELEVEN modules — `:application` 1378, `:adapters` 552,
       `:adapters:github` 432, `:adapters:git` 375, `:sandbox:docker` 290,
       `:domain` 278, `:adapters:agent` 238, `:bootstrap` 94, `:gitobjects` 67,
       `:sandbox:core` 63, `:gnomish-plugin-api` 55. -->
  <!-- The totals are the evidence for FR9/FR10 together: 4364 tests, 7 skips and
       3822 mutations are the SAME three numbers task 9.1 measured before pass 2.
       The vertical split redistributed coverage across four modules where there
       had been one and neither lost nor invented any. Wall-time is 13m32s against
       9.1's 12m58s and the 35m49s monolith baseline — the three new build units
       cost ~34s of scheduling overhead on a full from-scratch run, which is the
       trade D1 accepts for per-seam scoping (`:adapters:agent:pitest` alone is
       under a minute). -->
  <!-- M4 is now MET, and structurally rather than by enumeration. Task 5.2 left
       six residual package edges, each named with what would remove it; all six
       are gone: two `-> secrets` at 5.3, `tracker -> tracker.github` at 10.1,
       `pipeline -> agent` at 10.2, and `agent`/`git -> environment` became
       adapter-to-backend edges at 6.1. The one edge that remains —
       `:adapters:agent -> :adapters` — is declared in the build file, gated by
       `verifyModuleLayering`, and narrowed to two packages by
       `AgentCoarseReachSpec`. Task 5.4's negative test still holds a fortiori: a
       sibling-internal import no longer reaches a rule at all, it fails
       `compileJava`, the sibling's types being absent from the classpath. -->
  <!-- One real defect found, in `:application` and pre-existing to pass 2: the
       mutants deleting `SlotLedger.assign` / `release` from `TakeBatch`'s loop
       leave a permit unreturned, so the next `acquire()` parks forever and the
       covering feature HANGS instead of failing — PIT reports TIMED_OUT, which
       `pitestVerifyAllKilled` refuses to count as a kill. Reproduced by
       simulating the mutant: `TakeRefDispatchSpec` (added by this change at task
       8.7, which is why the hazard surfaced only now) blocked indefinitely and
       `TakeBatchSpec` for 10 minutes+. Both specs now carry a class-level
       `@Timeout(10)` — the production loop is correct, and an unbounded wait in a
       spec whose whole subject is a slot ledger is the actual defect: a deadlock
       is exactly what those features exist to report. The bound is far above any
       real run (the slowest is 1.5s) and far below PIT's per-mutation budget, so
       a hang becomes an ordinary failure without touching a scenario name,
       `given/when/then` block or assertion. Both specs are outside M5's budget
       anyway — `TakeRefDispatchSpec` is this change's own (task 8.7). -->
  <!-- Also observed once and not reproduced: a `SlotEntryAssembler.stageOrNull`
       RUN_ERROR in the same run, KILLED on a scoped re-run and in both subsequent
       full runs. The load-sensitive family `pitest-conventions` documents — eleven
       modules' PIT now schedule in parallel, more concurrent minions than pass 1
       ever ran. Recorded rather than exempted; nothing was changed for it. -->

## 11. Documentation 

- [x] 11.1 Update README.md with the new project structure
  <!-- New "Project structure" section between "Tech stack" and "Building": a
       Mermaid dependency-direction diagram of the eleven production modules, a
       table of what each holds, and a closing paragraph naming the three gates
       that make the direction enforced rather than documented
       (`verifyModuleLayering`, dependency-analysis, the ArchUnit rules) and what a
       violation looks like — UX2's "a build failure, not a review comment", stated
       where a newcomer meets the tree. -->
  <!-- Scope note: the "Building" section already carried the per-module `check` /
       `pitest` invocations and the "no whole-tree mutation task" contract (task
       8.4), so this task adds the structure the module tree itself, not the
       commands. `:test-fixtures` and `build-logic/` are in the table but not the
       diagram: neither is on a production classpath, which is the relation the
       diagram draws. -->
