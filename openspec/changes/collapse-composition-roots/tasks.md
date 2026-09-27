## 0. Baseline

- [x] 0.1 Re-run the parameter-count scanner of `introduce-take-order` task 0.2 over
      `src/main`; verify it reports 32 = 22 (`:application` / `:bootstrap`) + 10 (NG1) at
      the file:line positions the design and this file cite (confirmed 2026-09-26). If it
      differs, correct the artifacts through `/opsx:update` before editing any signature.
      *Scan 2026-09-26 at `f8db5e10`:* **32** = 22 + 10, every site at the file:line and
      parameter count of `introduce-slot-wiring`'s residual list (task 6.2) — no artifact
      correction needed. Scanner as in 0.2: `JavacTask.parse` over every
      `*/src/main/java/**/*.java`, declarations with more than seven parameters, `record`
      members and `@Override` methods excluded.

## 1. The largest reduction first

- [x] 1.1 Change `ManualRunRunner` (`:bootstrap`, ctor:152) to take an injected
      `ManualRunAssembly` instead of building it inline from its ten ingredients (FR2, D3),
      deriving the listener-carrying copy with `withExtraListener` as today; the three
      ingredients the runner holds for nothing else (`filesExistCheckRunner`,
      `shellCommandCheckRunner`, `threadSleeper`) leave the constructor, the other seven stay
      as the runner's own parameters until sections 2 and 3 decide their clusters. Verify the
      constructor goes from 28 to 25 parameters, the runner reads no collaborator back
      through the assembly's package-private fields, the Spring context spec passes unedited
      (NFR-R2; it constructs nothing by hand) and `grep -rn "new ManualRunAssembly(" bootstrap/src/main` returns only the
      bean method.
      *Done 2026-09-26:* `ManualRunConfiguration#manualRunAssembly` builds the assembly from
      the ten ingredients (10 parameters — the bean method task 3.5 brings under the limit);
      the runner takes it and derives the listener copy with `withExtraListener`. Constructor
      28 → 25 (→ 24 after 1.3). The runner reads no field of the assembly. The grep returns the
      bean method plus the class's own `copyWith` (`ManualRunAssembly.java:171`), which is the
      copy construction inside the owner, not a second builder. `FactoryApplicationSpec` and
      the runner / assembly / dispatch specs pass unedited; `AppAssemblyFixture` builds the
      assembly from the same instances it hands the runner.
- [x] 1.2 Add `FactoryPaths` with `worktreesRoot()` and `homeDir()` accessors and one bean
      producing it, deleting the two `Path` beans in the same commit (FR3, FR5, D4); verify
      the context starts with the same graph, that
      `grep -rn "Path worktreesRoot\|Path homeDir"` over the files of the design's
      `FactoryPaths` row returns nothing, that
      `grep -rln "@Component" application/src/main bootstrap/src/main | xargs grep -ln "Path worktreesRoot\|Path homeDir"`
      returns nothing, and that `SlotWiring`'s `worktreesRoot` is filled from
      `FactoryPaths.worktreesRoot()` at both of its assembly points (the row's exemption).
      *Done 2026-09-26:* `FactoryPaths` (`:application`, record) with
      `underHome(Path)` as its one method beyond accessors (pinned by `FactoryPathsSpec`);
      bean `factoryPaths()` replaces `worktreesRoot()` / `homeDir()`. Both greps return
      nothing (the `@Component` grep lists `TakeCommand` only because its javadoc says "Not a
      Spring `@Component`"). `SlotWiring.worktreesRoot` is filled from `paths.worktreesRoot()`
      in `ServeRuntimeAssembly.assemble`; `TakeCommand.run:210` fills it from its own
      single-path field, which `SubcommandDispatchFactory.of` feeds from
      `paths.worktreesRoot()`.
- [x] 1.3 Route every consumer in the design's `FactoryPaths` row through the new value,
      each in the shape the row gives it: the four relays (`ManualRunRunner` ctor:152,
      `SubcommandDispatchFactory.of:30`, `ServeCommand` ctor:94,
      `ServeRuntimeAssembly.assemble:52`) take `FactoryPaths` whole and lose both `Path`
      parameters; the four plain-Java single-path leaves (`TakeCommand` ctor:109,
      `TakeCommandFactory.of:24` and `:53`, `ObservabilityAssembly.assemble:103`) keep their
      one `Path`, fed by the relay's accessor read (`SubcommandDispatchFactory.of:50`,
      `ServeRuntimeAssembly.assemble:142`) and never gain the second; the two Spring-fed
      leaves `StatusCommand` ctor:65 and `DashboardCommand` ctor:60 take `FactoryPaths` and
      read one accessor. Verify no signature takes two adjacent `Path` parameters, that each
      relay and Spring-fed leaf lost every bare `Path`, that each single-path leaf still
      declares exactly one, and that the two Spring-fed leaves compile with the `Path` beans
      gone.
      *Done 2026-09-26:* relays `ManualRunRunner`, `SubcommandDispatchFactory.of`,
      `ServeCommand`, `ServeRuntimeAssembly.assemble` take `FactoryPaths` (24, 17, 14, 17
      parameters); `StatusCommand` / `DashboardCommand` hold `FactoryPaths` and read one
      accessor; `TakeCommand`, `TakeCommandFactory.of` ×2 and `ObservabilityAssembly.assemble`
      each keep one `Path`. Spec call sites pass `new FactoryPaths(...)`; the single-path
      command specs use `FactoryPathsFixture.worktreesAt` / `homeAt`, whose other path never
      exists, so a wrong accessor fails. No expectation edited; `:application:test` (2467) and
      the affected `:bootstrap` specs (103) green.

## 2. Cluster by cluster, each judged against D2

- [x] 2.1 Decide the report-commands cluster (`statusCommand`, `usageCommand`,
      `boardCommand`, `dashboardCommand`) against D2's three-part criterion; extract
      `ReportCommands` with at least one method beyond its accessors, or leave the cluster
      flat and record the reason. Verify the design's single-owner row is updated with the
      verdict either way.
      *Done 2026-09-26:* extracted — see the design's `ReportCommands` row. `@Component`
      facade with `run(Subcommand, ApplicationArguments)`, the report routing moved out of
      `SubcommandDispatch` (now `(ReportCommands, TakeCommand, ServeCommand)`); pinned in
      `:application` by `ReportCommandsSpec`. Runner 24 → 21, `SubcommandDispatchFactory.of`
      17 → 14. `SubcommandDispatchSpec` and `FactoryApplicationSpec` pass, no expectation edited.
- [x] 2.2 Decide the vital-sources cluster shared by `ObservabilityAssembly.assemble:103`
      and `assembleSnapshot:175` against D2 — the ten parameters the design's `VitalSources`
      row lists, or the four vital feeds proper within them; extract `VitalSources` or record
      the rejection. Verify both sites come under the limit if extracted, and that the
      snapshot the writer produces is byte-identical either way.
      *Done 2026-09-26:* extracted over all ten under the name `SnapshotSources` — see the
      design's `SnapshotSources` row for why not `VitalSources`. `assembleSnapshot:175` became
      `SnapshotSources.snapshot(...)` (same body, so the snapshot is byte-identical by
      construction); `assemble` 16 → 7. `ObservabilityAssemblySpec` passes, no expectation
      edited.
- [x] 2.3 Decide the time-sources cluster (`systemClock`, `javaTimeClock`, `threadSleeper`)
      against D2, keeping `testing.md`'s injected-time rule intact; verify
      `checkTestTimeInjection` still passes and no spec gained a `system()` call.
      *Done 2026-09-26:* rejected, fails (b) — see the design's `TimeSources` row. Nothing
      changed; the only test-side `system(` lines in the diff are the two pre-existing
      `RemoteOutageGates.system` calls in `ObservabilityAssemblySpec`, re-indented with their
      `real-time-wiring` marker.
- [x] 2.4 Decide the tracker-wiring cluster (`trackerAdapterRegistry`, `secretsProvider`,
      `pipelineSource` — the three members every listed site carries: `ManualRunRunner`
      ctor:152, `SubcommandDispatchFactory.of:30`, `TakeCommand` ctor:109, `ServeCommand`
      ctor:94) against D2; verify NFR-S1 by recording whether `SecretsProvider` gained any
      consumer, and enter the verdict in the design's table. The claim-epoch book is not a
      member: it is a bean that feeds only the `TaskGit` bundle and reaches
      `TrackerResolution.resolveTracker` through `git.epochs()` (design D2 of
      `fix-claim-epoch-fence`); it stays with `TaskGit` and no facade of this change carries it.
      *Done 2026-09-26:* extracted — see the design's `TrackerWiring` row (verdict, shape, NFR-S1
      finding, enforcement). Scope widened with the user's agreement after a best-practices
      review: `TakeDispatcher` takes the narrow `RefResolution` face, `BoardCommand` /
      `DashboardCommand` take the wiring too, `TrackerResolution` and `TakeRefResolution` are
      folded into it (their specs into `TrackerWiringSpec`), and
      `TrackerWiringOwnerBoundarySpec` pins the reach. `SecretsProvider` gained no consumer.
      `:application:test` (2477) and the affected `:bootstrap` specs (114) green, no expectation
      edited.
- [x] 2.5 Decide the ledger-writers cluster of `ObservabilityWiring` ctor:47 (the four
      `*LedgerWriter`s and `ledgerAppender`, built together over one `RotatingLedgerAppender`
      in `ObservabilityAssembly.assemble:103`) against D2, per the design's `LedgerWriters`
      row; extract `LedgerWriters` with `newRunSummaryLedgerWriter()` or another method beyond
      accessors as its justification, or record the rejection. Verify the constructor comes
      under the limit if extracted, that every ledger line still reaches the same appender
      (the ledger specs pass with no expectation edited), and that the verdict is entered
      in the design's table.
      *Done 2026-09-26:* extracted — see the design's `LedgerWriters` row. The facade builds
      the four write points over the one appender and provides `newRunSummary()`;
      `ObservabilityWiring` ctor 9 → 4. The ledger specs (`ObservabilityAssemblySpec`,
      `ServeShutdownWiringSpec`, `ServeRuntimeWiringSpec`) pass with no expectation edited.

## 3. The remaining composition sites (re-planned 2026-09-26 after the architecture review — design D6–D10)

- [x] 3.1 Add `BoundTracker` (`:application`, record: `definition`, `trustedBase`,
      `trackerConfig`, `factory`, `tracker`, `instanceId`; one derived method
      `credentialEnvVars()`; one wither `withTracker(Tracker)` for the serve root's derivation
      over the health-wrapped tracker; the membership rule in its javadoc — design D8 and its
      2026-09-27 amendment, FR8) and route the dispatch chain through it: builders
      `TakeCommand.run` and `ServeCommand.run`; relays, which take it whole,
      `TakeRefDispatch.run:25`, `TakeBatch.dispatch:117`, `ServeRuntimeAssembly.assemble:51`
      (which makes `bound.withTracker(trackerHealth)` its first use of the value and passes only
      the derived one on); leaves `TakeDispatcher.runOneRef:55`, `runBare`, `runExplicit`,
      `runBatch`, which use every member, take it whole and derive their `RunOrder` from
      `bound.definition()`, and `ServeAssembly.feedAutomaton:60` / `slotRunner`, which use three
      members each and take exactly those, read out by `assemble`. Verify `TakeRefDispatch.run`,
      `TakeBatch.dispatch` and `TakeDispatcher.runOneRef` come to seven or fewer (they stay
      static — D7's last sentence); that
      `grep -rn "credentialEnvVars(trackerConfig)" application/src/main bootstrap/src/main`
      returns only the record's method; that
      `grep -n "bound.tracker()\|BoundTracker " application/src/main/java/com/github/oinsio/gnomish/app/ServeCommand.java`
      returns only the construction and the `assemble` hand-off; that the record constructs
      nothing; and that the dispatch and batch specs pass with no expectation edited.
      *Done 2026-09-27:* see the design's "Applied, section 3" note on `BoundTracker`. `runOneRef`
      8 → 4, `TakeBatch.dispatch` 8 → 4, `TakeRefDispatch.run` 9 → 5, all static. The first grep
      returns only `BoundTracker.java:45`; the second returns one line, the construction handed
      straight to `runtimeAssembly.assemble` (`ServeCommand.java:131`) — the construction and the
      hand-off are one expression. The record constructs nothing (one read, one copy). Dispatch
      and batch specs changed only the line that passes the binding (`TakeRefDispatchSpec`,
      `TakeRefDispatchContainerBatchSpec`, `TakeSummaryAnchorSpec`, `TakeDispatcherBatchSpec`);
      new `BoundTrackerSpec`.
- [x] 3.2 Add `SlotWiringFactory` (`:application`; fields `assembly`, `git`, `worktreesRoot`,
      `taskIdMdcKey`, `clock`, `containerTakeSupport`, `pipelineSource`; one method
      `slotWiring(BoundTracker, TaskGit, TakeHeartbeat)` with the Metz test in its javadoc; no
      accessor — design D9 and its 2026-09-27 amendment: the abort handler is built over
      `bound.tracker()`, no separate `Tracker` parameter) and route both constructions through
      it: `TakeCommand.run` (its own bound tracker and git) and `ServeRuntimeAssembly.assemble`
      (the bound tracker derived over the health wrap, task 3.1, and the outage-decorated git).
      Then bring `TakeCommand` ctor:101,
      `TakeCommandFactory.of:21` and `:46` and `ServeCommand` ctor:88 under the limit: the
      commands take the factory in place of the hand-listed equipment, `TakeCommand` takes
      `TakeCommandSeams` whole instead of its five members unpacked (the seams record already
      exists; the factory's two overloads collapse or go — D7), `ServeCommand` keeps its extras
      flat. Verify `grep -rn "new SlotWiring(" application/src/main bootstrap/src/main` returns
      only the factory's method, no boolean or mode parameter exists on it, each listed site is
      at seven or fewer, and the command specs pass with no expectation edited.
      *Done 2026-09-27:* see the design's "Applied, section 3" note on `SlotWiringFactory` — six
      fields (no `git`: the method's parameter is its one source), `TakeCommand` 14 → 7 reading its
      MDC key from the built wiring, `TakeCommandFactory` deleted (both overloads), `ServeCommand`
      13 → 7 (with task 3.3). The grep returns only `SlotWiringFactory.java:67`, now also pinned by
      `SlotWiringOwnerBoundarySpec`. No boolean or mode parameter. The take specs build through
      the test-side `TakeCommands` (the old argument order, over the production factory); new
      `SlotWiringFactorySpec`.
- [x] 3.3 Make `ServeRuntimeAssembly` an instance held by `ServeCommand`, constructed from the
      command's fixed equipment, with `assemble(ServeArguments, BoundTracker, int
      effectiveSlots)` (design D7), and `ServeAssembly` an instance held by it over the daemon's
      fixed equipment (`factoryProperties`, `serveProperties`, `feedClock` — D7's 2026-09-27
      addition, Fowler's *Combine Functions into Class*), each builder taking only the per-call
      job and the exact bound-tracker members it uses: `feedAutomaton(trackerConfig, tracker,
      instanceId, slotLedger, slotRunner, dirtyNotifier, remoteOutageGate)` 10 → 7,
      `slotRunner(serveArguments, definition, tracker, instanceId, wiring)` unchanged at 5,
      `shutdown` / `worktreeJanitor` / `sandboxLifecycleTick` lose their `serveProperties`
      parameter. `ObservabilityAssembly.assemble` is already at seven (task 2.2) and
      `assembleSnapshot` is gone — no work. Verify `ServeRuntimeAssembly` carries no decision of
      its own, is listed in `:application`'s `pitest { excludedClasses }` with
      `ServeRuntimeWiringSpec` and `ServeShutdownWiringSpec` named as the covering suites and gets
      no spec of its own; that `ServeAssembly` is **not** excluded and `ServeAssemblySpec` /
      `ServeAssemblyBuildersSpec` pass with only their construction line changed; and that the
      serve specs pass with no expectation edited (their fixtures change with the signature —
      task 3.8).
      *Done 2026-09-27:* see the design's "Applied, section 3" note on the assembly instances.
      `ServeCommand` takes the `ServeRuntimeAssembly` its relay builds; `ServeRuntimeAssembly` holds
      seven and carries no decision (still excluded, both suites named in the rationale);
      `ServeAssembly` is an instance over the three, `feedAutomaton` 10 → 7, `slotRunner` 5 and
      static, and it also took the four constructions over the same three members, pinned by the
      new `ServeAssemblyEquipmentSpec`. `ServeAssemblySpec` / `ServeAssemblyBuildersSpec` changed
      only their construction lines. The serve specs build through the test-side `ServeCommands`.
- [x] 3.4 Make `ContainerRunSupportFactory` (`:bootstrap`) an instance over the fixed part
      (`sandboxProperties`, `factoryProperties`, the check credentials, the check-client
      registry, the ownership mode, `epochs`) with `create(cloneDir, taskId, segments,
      definition, credentialEnvVarsToScrub)`; `ContainerRunSupport.create:130` follows or is
      folded in (design D7, D10). Then add `ContainerSupports` (`@Component`) over the cluster
      D3 assigned here (`checkClientRegistry`, `factoryProperties`, `sandboxProperties`,
      `bindingProperties`, `bindingRegistry`, the Docker probe, `TaskGit.epochs()`), with
      `manualSupport()` and `takeSupport()` and a named test constructor taking the probe.
      Verify no signature takes two adjacent `List<String>`, the book still arrives only from
      `TaskGit.epochs()`, `grep -rn "containerSupportFactory(\|new ContainerTakeSupport("
      bootstrap/src/main` returns only the facade, and `ContainerRunSupportSpec` and
      `ManualRunRunnerContainerOwnershipSpec` pass with no expectation edited.
      *Done 2026-09-27:* see the design's "Applied, section 3" notes on `ContainerRunSupportFactory`
      (implements the seam; four fields; the static `ContainerRunSupport.create` and the runner's
      lambda removed) and `ContainerSupports` (`manualSupport()`, `takeSupport()`, `plan(...)`,
      the seven-parameter test constructor). No signature takes two adjacent `List<String>`; the
      book is read only in `ContainerSupports`' constructor from `git.epochs()`; the grep returns
      only `ContainerSupports.java:95`. `ManualRunRunnerContainerOwnershipSpec` asserts both labels
      off the facade with its assertions unchanged; `ManualRunContainerDispatchSpec` no longer
      writes `runner.@dockerProbe` (`AppAssemblyFixture.newManualRunRunnerProbing`). The E2E specs
      that built a bundle directly go through `ContainerSupportFixture.direct`, each still naming
      `ClaimEpochSource.NONE` itself (`ClaimlessGitBoundarySpec` unchanged).
- [x] 3.5 Extract `CheckEquipment` (`:bootstrap`; fields: the two built-in check runners, the
      check-client registry, the secrets provider — no accessor for the seam, NFR-S1) with the
      methods of the folded `CheckProviderWiring` (`credentialNames`, `externalCheckClient`, the
      subsection resolution) plus `builtinRunner(sandbox)` / `commandRunner(childEnv, sandbox)`
      moved from `RunAssembler` (design D11 and the `CheckEquipment` row). `ManualRunAssembly`
      takes it: the public constructor 10 → 7, the 14-parameter canonical constructor replaced by
      a copy constructor (the plain assembly plus the four wither members) so `copyWith` and the
      withers stay; the `manualRunAssembly` bean 10 → 7. Update `TrackerWiringOwnerBoundarySpec`'s
      allowlist (`ManualRunAssembly` out, `CheckEquipment` in). Verify `grep -rn "CheckProviderWiring"
      bootstrap/src` returns nothing, the assembly's own specs (`ManualRunAssemblyWiringSpec`,
      `ManualRunAssemblyCheckClientWiringSpec`) pass with no expectation edited, and the context
      spec passes unedited.
      *Done 2026-09-27:* see the design's "Applied, section 3" note on `CheckEquipment` — five
      fields, not four (the operator's `FactoryProperties`, read for the check subsections and
      connection profiles only). `ManualRunAssembly` 10 → 7 with a five-parameter copy
      constructor in place of the 14-parameter canonical one; beans `manualRunAssembly` 7 and
      `checkEquipment` 5. `CheckProviderWiring` deleted, the grep returns nothing. The two
      assembly specs, `ManualRunRunnerSpec`, `TrackerWiringOwnerBoundarySpec` (allowlist
      updated) and `FactoryApplicationSpec` (unedited) green; the fixtures changed only the
      construction (`AppAssemblyFixture`, `ManualRunAssemblyCheckClientWiringSpec`).
- [x] 3.6 Move the assembly out of `ManualRunRunner` (design D6, D11, FR7). Add `ManualRunners`
      (`:bootstrap`; fields: the four manual runners; `run(order, context, initialState, plan)` and
      `resume(order, resume, plan)` dispatching by `plan.mode()` — the switch that lives twice in
      `ManualRunDrive` today). `ManualRunConfiguration` gains the beans of the design's three new
      rows, each at seven or fewer: `manualRunners` (assembly, git, paths, sandboxProperties,
      factoryProperties, containerSupports, console), `manualRunDrive` (parser, startup,
      synthesizer, assembly, persistence, console, runners; `ManualRunDrive` becomes an instance
      holding them and reading `containerSupports.plan` through the runners), `slotWiringFactory`
      (5), `serveRuntimeAssembly` (7, building `ServeAssembly` and `SandboxLifecyclePass` inline),
      `takeCommand` (7), `serveCommand` (6), `subcommandDispatch` (reportCommands, takeCommand,
      serveCommand). `SubcommandDispatchFactory` is deleted; `TakeCommand` / `ServeCommand` lose
      the "not a Spring component" javadoc sentence. The runner takes `GitVersionCheck`,
      `SubcommandDispatch`, `ManualRunDrive` and the error console. Verify the runner is at four,
      `grep -n "runner\." bootstrap/src/main/java/com/github/oinsio/gnomish/app/ManualRunDrive.java`
      returns nothing, `grep -n "plan.mode()" -r bootstrap/src/main` returns only `ManualRunners`,
      `grep -rn "new GitModeRunner(\|new GitResumeRunner(\|new ContainerGitModeRunner(\|new
      ContainerResumeRunner(" bootstrap/src/main` returns only the `manualRunners` bean, every bean
      method is at seven or fewer, `FactoryApplicationSpec` passes unedited (NFR-R2), and
      `SubcommandDispatchSpec` / `ManualRunRunnerSpec` pass with no expectation edited (their
      fixtures build the same beans by hand — task 3.8).
      *Done 2026-09-27:* see the design's "Applied, section 3" note on the manual-run root.
      `ManualRunRunner` 24 → 4 (`GitVersionCheck`, `SubcommandDispatch`, `ManualRunDrive`, the
      error console); `ManualRunDrive` an instance over seven; `ManualRunners` with
      `run(order, context, initialState)` / `resume(order, resume)`, the plan read from
      `order.definition()`. Two beans more than the task listed — `sandboxLifecyclePass` and
      `serveAssembly` — because `serveRuntimeAssembly` has nine inputs with both built inline
      (agreed with the user 2026-09-27; D11 and NFR-R2 amended). The take/serve beans live in a
      new `TrackerCommandConfiguration` (`ManualRunConfiguration` was already past the 200-line
      cap; agreed with the user). `SubcommandDispatchFactory` deleted with its PIT exclusion. All
      greps as specified: `runner\.` nothing, `plan.mode()` only `ManualRunners`, the four
      `new …Runner(` only the `manualRunners` bean. Scan: no `:bootstrap` site over seven.
      `FactoryApplicationSpec` byte-unedited; `SubcommandDispatchSpec`, `ManualRunRunnerSpec`,
      `ManualRunConfigurationSpec`, `ManualRunContainerDispatchSpec`, `GitVersionFloorSpec`,
      `BoardCommandOutageSpec` green. `AppAssemblyFixture` now builds the runner through the
      production bean methods. One assertion line changed its path, not its claim:
      `ManualRunRunnerSpec:443` reads `runner.drive.assembly.hostGitPush` (was
      `runner.assembly…`) — the field it reads moved with the drive; this is the plain
      property-access debt `testing.md` names, not a new `.@`.
- [x] 3.7 Add `TakeClaimAndWork.workClaimed(RunOrder, TaskRef)` — fetch the claimed task,
      build the `TakeOrder`, call `dispatchAfterClaim` — and route both consumers named in
      the design's `workClaimed` row through it: `BareTakeClaimWalk.resolve:74-75` and
      `TakeSlotRunner.run:142-143` (FR6). `TakeDispatcher.runOneRef:89` is the exemption
      and is left as is: its order is built before the claim. MDC key, anchor log and
      summary stay with the callers. Verify
      `grep -rn "new TakeOrder(" application/src/main bootstrap/src/main` returns exactly
      `TakeOrder.withDefinition`, `workClaimed`'s body and `TakeDispatcher.runOneRef`, that
      the two "one of the three places" comments now point at the owner, and that the
      `BareTakeClaimWalk`, `TakeSlotRunner` and `TakeClaimAndWork` specs pass with no
      expectation edited (NFR-R1, NFR-O1).
      *Done 2026-09-26:* `workClaimed(RunOrder, TaskRef, Tracker, InstanceId)` — the tracker and
      instance id join the signature because `TakeClaimAndWork` holds neither (its fields come
      from `SlotWiring`, which carries no tracker); the design's row is corrected. Both consumers
      route through it; `dispatchAfterClaim` is now package-private, so `workClaimed` is the one
      public entry. The grep returns exactly `TakeOrder.withDefinition`, `workClaimed`'s body and
      `TakeDispatcher.runOneRef`, whose comment now names the exemption and the owner.
      `BareTakeClaimWalkSpec`, `TakeClaimAndWorkSpec`, `TakeBareAutoSpec`, `TakeSlotRunnerSpec`,
      `TakeDispositionSpec` green, no spec file touched.
- [x] 3.8 Fixture sweep: every spec or fixture that constructs a site of this change by hand
      is updated to pass the new values (`FactoryPaths`, the facades of section 2, the
      injected `ManualRunAssembly`, `BoundTracker`, `SlotWiringFactory`, `ContainerSupports`,
      the instance assemblies of D7 and the beans of D6) — in the same task as the signature it constructs, with
      no expectation edited (NFR-R1). Today's list, from
      `grep -rln "new TakeCommand(\|TakeCommandFactory.of(\|new ServeCommand(\|new ManualRunRunner(\|SubcommandDispatchFactory.of(\|ServeRuntimeAssembly.assemble(\|ObservabilityAssembly.assemble(\|new StatusCommand(\|new DashboardCommand(\|new ManualRunAssembly(\|new ObservabilityWiring(" bootstrap/src/test application/src/test`
      (25 files, 2026-09-26) — `:bootstrap`: `SubcommandDispatchSpec` (32 mentions of the two
      paths), `AppAssemblyFixture`, `TakeDeathAndRecoverySpecBase`,
      `ManualRunAssemblyCheckClientWiringSpec`, `TakeLifecycleRevocationSpecBase`,
      `TakeLifecycleReadyToDeliveredSpecBase`, `TakeHeartbeatLifecycleSpecBase`,
      `TakeContainerLifecycleE2ESpec`, `TakeCommandFixture`, `TakeCommandCredentialScrubSpec`,
      `TakeCommandBatchSpec`, `ServeShutdownWiringSpec`, `ServeRestartIntegrationSpec`,
      `ServeObservabilityFixture`, `ServeCommandSpecBase`, `ServeClaimEpochStampSpec`,
      `HealthyServeCycleLogSpec`, `BaseRefBareRemoteIntegrationSpec`, `AbortLifecycleFixture`;
      `:application`: `StatusUsageReadOnlySpec`, `StatusCommandSpec`,
      `ObservabilityAssemblySpec`, `StatusInterruptedHonestySpec`,
      `ObservabilityWiringTestFixtures`, `DashboardCommandSpec`. Verify at the end of section
      3 that the same grep enumerates no file still passing a bare `Path` pair or a
      hand-listed cluster to a listed site, that no fixture reaches a facade's members by
      field (`testing.md`, "Specs observe effects, not private fields"), and that the
      `FactoryApplicationSpec` context-start spec is byte-unedited (NFR-R2): it builds
      nothing by hand, so it alone keeps the stricter criterion.
      *Done 2026-09-27 (end of section 3):* the grep (with `TakeCommands.of(` / `ServeCommands.of(`
      added, the test-side builders of tasks 3.2/3.3) enumerates 13 files; none passes a bare
      `Path` pair or a hand-listed cluster to a listed site — every root pair goes as
      `FactoryPaths`, `AppAssemblyFixture` builds the runner through the production bean methods of
      `ManualRunConfiguration` / `TrackerCommandConfiguration`, and `TakeCommands` / `ServeCommands`
      build through the production `SlotWiringFactory` / `ServeAssembly` / `ServeRuntimeAssembly`.
      The change adds no `.@` read and removes one (`runner.@dockerProbe`); the survivors in
      `ServeShutdownWiringSpec` are the pre-existing debt `testing.md` names.
      `FactoryApplicationSpec` byte-unedited. Full `:application:test` (2487) and
      `:bootstrap:test` (1967) green.

- [x] 3.9 The two leaf sites left in the table. `TakeOutcomeDispatch.dispatch:50` (8) —
      *done 2026-09-27:* an instance over the four terminal transitions (`retry`, `park`,
      `abortFuse`, `finish`), `dispatch(outcome, context, branchName, order)`; 8 → 4; see the
      design's "Applied, section 3" note. `InstanceHeartbeat` ctor:118 (8) — per design D12,
      extract `BeatTiming(interval, lostDetection)` in `app.lease`: compact constructor asserting
      `lostDetection` is not shorter than `interval`, `rollUp()` moved from
      `InstanceHeartbeat.rollUpFor` with its `@DoNotMutate` trace; `LeaseThresholds.beatTiming(
      config)` is the one builder, `TakeHeartbeat.forRun` reads it from there, and the heartbeat's
      two defaulting constructors (`:76`, `:92`) derive their `BeatTiming` from `interval` alone.
      Verify the constructor is at seven, `grep -rn "new BeatTiming(" application/src/main`
      returns only `LeaseThresholds` and the two defaulting constructors (each listed in the row),
      `HeartbeatRollUpPeriodSpec` retargets to `BeatTiming.rollUp()` with its expectations
      unchanged, a new `BeatTimingSpec` pins the invariant, and `InstanceHeartbeatSpec` /
      `LeaseThresholdsSpec` pass with no expectation edited.
      *Done 2026-09-27 (`BeatTiming` half):* record in `app.lease`, compact constructor refusing a
      threshold shorter than the interval, `rollUp()` moved with its `@DoNotMutate` trace
      (`InstanceHeartbeat.rollUpFor` deleted). `InstanceHeartbeat` canonical constructor 8 → 7,
      taking the timing whole; `LeaseThresholds.beatTiming(config)` is the one builder and
      `TakeHeartbeat.forRun` reads the timing (and the reaper's interval) from it. `new BeatTiming(`
      in `src/main`: `LeaseThresholds` and one defaulting constructor — the six-parameter one
      delegates to the seven-parameter `Duration` one, so only that one spells it.
      `HeartbeatRollUpPeriodSpec` retargeted to `BeatTiming.rollUp()`, expectations unchanged;
      `InstanceHeartbeatFencingSpec` changed its construction line only; new `BeatTimingSpec`. The
      `app.lease` specs, `TakeHeartbeat*`, `ObservabilityAssemblySpec` and
      `VitalsSnapshotAssemblerSpec` green. Scan: the three `FeedAutomaton` constructors are the only
      `:application` / `:bootstrap` sites left over seven (section 4).

## 4. `FeedAutomaton` — inspect, then decide (D5, Q1)

- [x] 4.1 Read the three `FeedAutomaton` constructors (:65, :104, :142) and confirm against the
      code the 2026-09-26 refutation recorded under D5: `IdleTiming` (built at `:160`) is the
      one behavioral cluster, but `FeedSelection` (`:171`) is built from three of the same
      four, and `wipLimit`, `sleeper`, `clock`, `dirtyNotifier`, `remoteOutageGate` remain, so
      one constructor lands at ten; the 11- and 12-parameter constructors are a test seam with
      no production caller (`grep -rn "new FeedAutomaton(" application/src/main
      bootstrap/src/main` returns only `ServeAssembly.feedAutomaton`). Verify the finding is
      recorded either way, with the members of each candidate cluster named.
      *Confirmed 2026-09-27 against the code:* the refutation holds. Candidate clusters — (i)
      `IdleTiming` = `idlePollInterval`, `backoffBase`, `backoffCap`, `random` (behavioral:
      `jittered()`, `idleState(...)`); (ii) `FeedSelection` = `backoffBase`, `backoffCap`,
      `wipLimit`, `random` — three members shared with (i), so not a second parameter; (iii)
      the rest, `tracker`, `instanceId`, `slotLedger`, `slotRunner`, `sleeper`, `clock`,
      `wipLimit`, `dirtyNotifier`, `remoteOutageGate`, form no cluster: the constructor feeds
      them into five built collaborators (`FeedTracker`, `FeedOutageRetry`, `FeedResilience`,
      `FeedCycle`, `FeedViewTracker`). The 11- and 12-parameter constructors had no production
      caller — `grep -rn "new FeedAutomaton(" application/src/main bootstrap/src/main`
      returned only `ServeAssembly.feedAutomaton`.
- [x] 4.2 Take `IdleTiming` as a constructor parameter, keep one constructor, and move the two
      defaulting constructors into a test fixture under `application/src/test` so the
      `RemoteOutageGates.system(...)` default leaves `src/main` and comes under
      `checkTestTimeInjection`; record the SRP finding in the design's `FeedAutomaton` row and
      name the follow-up change `split-feed-automaton-composition` (the automaton takes its
      cycle and view tracker built). Verify the one constructor is at ten (13 → 10) and is the
      single site over the limit this change leaves (M1: 22 → 1), no spec expectation is edited
      (NFR-R1), no facade was invented to hit the number (M2), and the row cites the reason.
      *Done 2026-09-27:* one public constructor, 13 → 10 (`tracker`, `instanceId`,
      `slotLedger`, `slotRunner`, `sleeper`, `clock`, `idleTiming`, `wipLimit`,
      `dirtyNotifier`, `remoteOutageGate`). `IdleTiming` became public (the root is in `app`,
      not `app.serve`) and gained `selection(wipLimit)`, so `FeedSelection` is built from the
      same bounds and random the Idle split uses rather than from accessors read back out. The
      two defaulting constructors moved to `FeedAutomatonFixture` — in `:test-fixtures`
      (`app.serve`), not `application/src/test`, because three `:bootstrap` specs
      (`FeedAutomatonOutageIntegrationSpec` ×2, `ServeShutdownWiringSpec`) built them too and
      `:bootstrap` sees no `:application` test classes; `checkTestTimeInjection` scans
      `:test-fixtures`' `src/main` as well, so the purpose holds. The fixture's default gate
      runs on `VirtualClock` (closed, never opened — its clock is never read), so no
      `system()` call reaches test code. Thirteen spec sites switched to the fixture;
      `RemoteOutageServeEndToEndSpec` passes `new IdleTiming(...)` to the constructor; one
      feature title renamed ("eleven-arg constructor" → "notifier-less construction"); no
      expectation edited. `grep -rn "new FeedAutomaton(" application bootstrap test-fixtures`
      (excluding build output) returns `ServeAssembly.feedAutomaton`, the fixture and
      `RemoteOutageServeEndToEndSpec`.
      *Gate run 2026-09-27:* the full `:application:check` / `:bootstrap:check` surfaced two
      section-3 mutation gaps, closed here with specs rather than exemptions:
      `TrackerWiring.bindStartupLaw` (NO_COVERAGE in `:application` — reached only through the
      commands, whose specs live in `:bootstrap`) gained a `TrackerWiringSpec` feature asserting
      the bind goes through the wiring's own definition source; `CheckEquipment.commandRunner`
      (null-return SURVIVED) gained `CheckEquipmentCommandRunnerSpec`, asserting the runner
      carries the run's allowlist in both modes and the sandbox pieces' check environments in
      container mode. PIT: zero undetected mutants in both modules.
      `TakeSlotRunnerContainerConcurrencySpec` failed once and passed on rerun — the known
      `.git/config` lock race in `harden()`, unrelated to this change.

## 5. Verification and handoff

- [x] 5.1 Verify FR4 across the change against the design's single-owner table, the one
      record of every D2 verdict: every facade added carries at least one method beyond its
      accessors, named in its row, and every cluster rejected has its row with the failing
      criterion (M2). A verdict that exists only in a task report is a gap in the table.
- [x] 5.2 Re-run the parameter-count scan over `src/main`; verify twenty-one of the twenty-two
      composition sites are gone and the one left is the `FeedAutomaton` constructor (M1), and
      that exactly eleven sites remain — the ten of NG1 plus that one — named by file and line
      as the input list for `add-parameter-count-gate` (M4), whose proposal is told about the
      eleventh in the same task.
      *Done 2026-09-27:* the scanner of task 0.1, unchanged, reports **11**: the ten of NG1
      (`ExecutorRoundExecution.java:49`, `JudgeRoundExecution.java:48`, `GithubMarkerJson.java:61`,
      `PipelineModelBuilder.java:50`, `ContainerEnvironmentBuilder.java:22`,
      `ContainerEnvironments.java:64` and `:106`, `ContainerMaterializer.java:42` and `:74`,
      `ContainerTaskExecutionEnvironment.java:83`) plus `FeedAutomaton.java:74` ctor (10) — no
      `:application` / `:bootstrap` site besides it (M1 22 → 1, M4 11). `add-parameter-count-gate`'s
      proposal gained the eleventh in What Changes, FR6, the re-taken baseline list and **Q2**
      (fix it there, or depend on `split-feed-automaton-composition`); its design and tasks still
      say ten and are left to that change's `/opsx:update`, which Q2 names.
- [x] 5.3 Run `./gradlew :application:check :bootstrap:check` and verify every gate passes
      with no spec expectation edited (NFR-R1, M3), the context-start spec unedited
      (NFR-R2) and the log-expectation gate green with no expectation file edited (NFR-O1).
      *Done 2026-09-27:* `./gradlew :application:check :bootstrap:check` — BUILD SUCCESSFUL,
      both `pitestVerifyAllKilled` green (no undetected mutant). No spec expectation edited in
      section 5; `FactoryApplicationSpec` absent from `git diff HEAD` (byte-unedited, NFR-R2); no
      expectation file in the diff. Root `checkLogExpectationGate`: 148 codes watched, 0 failing
      (NFR-O1).
- [x] 5.4 Add a glossary entry to `docs/glossary.md` for each facade that names a new
      domain concept, per `process-invariants.md`: **bound tracker** (D8, beside **take order**
      and **slot wiring**, stating its membership rule and how it differs from both) and
      **container supports** (D10) and **beat timing** (D12, beside **claim heartbeat**);
      `SlotWiringFactory`, `CheckEquipment` and `ManualRunners` name none new (the slot wiring,
      the check ports and the manual runners are defined by the code they wrap). Verify no new
      term is left undefined.
      *Done 2026-09-27:* `docs/glossary.md` gained **beat timing** (beside **beat** — the glossary
      has no **claim heartbeat** entry; the heartbeat is defined by **beat**), **bound tracker**
      (after **slot wiring**, with its membership rule and the contrast with **take order** and
      **slot wiring**) and **container supports** (after **ownership mode**, which it varies).
      Every term the three entries lean on is defined (**ownership mode**, **take order**,
      **slot wiring**, **claim epoch**); none uses a banned synonym.
- [x] 5.5 Amend the durable records: ADR 0010 gains four paragraphs — an abstract factory with
      one return type is a legitimate facade shape and is named as a factory (D9), a context
      object carries a membership rule and constructs nothing (D8), `@Bean` methods obey the
      limit and a large root is split into factories and facades rather than exempted (D11, with
      the sources named there), and criterion (a) admits Fowler's deletion test while (b) keeps
      the invariant inside the type (D12); `testing.md` gains the note
      that an assembly object of D7's shape gets no spec of its own and is exempted with its
      covering suite named. Verify each cites this change as provenance and the ADR's verdict
      table lists every row the design's table added on 2026-09-26 and 2026-09-27.
      *Done 2026-09-27:* ADR 0010 already carried the D9 (abstract factory), D8 (context object)
      and assembly-object paragraphs from 2026-09-26; this task added the D11 section (`@Bean`
      methods obey the limit; split, not exempted — Seemann, *Composition Root Reuse*;
      Checkstyle `ParameterNumber`; Spring's split-by-concern), the D12 amendment of (a) (the
      deletion test — Fowler *Data Clumps*, Evans/Vernon) and of (b) (the invariant inside the
      type), the `withTracker` copy in the context-object rules (D8 amendment b), a status line
      naming the amendments, and the 2026-09-27 verdict rows (`ManualRunAssembly`,
      `CheckEquipment`, `ManualRunners`, the beans, the static sites that stay static,
      `BeatTiming`; `ServeAssembly` and `TakeOutcomeDispatch` joined the instance row, whose
      "no spec" claim now distinguishes the decision-free instance from the ones that kept a
      decision). Two stale rows corrected: `SlotWiringFactory`'s signature (no separate
      tracker) and `BoundTracker`'s wither. Every added paragraph cites
      `collapse-composition-roots` as provenance. `testing.md`'s assembly-object note (landed
      with task 3.3) had `ContainerRunSupportFactory` as a precedent, which it is not — it merges
      credential sources in `create` and stays in the mutation scope with its spec; the precedent
      is now `ServeRuntimeAssembly` alone, with the distinction stated. The stale comment in
      `bootstrap/verification.gradle` naming `ManualRunRunner`'s removed container-support lambda
      as a `@DoNotMutate` line is corrected. The ADR is 290 lines, in line with ADRs 0004/0008.
- [x] 5.6 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits).
      *Done 2026-09-27:* recommended in the apply report.
