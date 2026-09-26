## 0. Baseline

- [ ] 0.1 Re-run the parameter-count scanner of `introduce-take-order` task 0.2 over
      `src/main`; verify it reports 32 = 22 (`:application` / `:bootstrap`) + 10 (NG1) at
      the file:line positions the design and this file cite (confirmed 2026-09-26). If it
      differs, correct the artifacts through `/opsx:update` before editing any signature.

## 1. The largest reduction first

- [ ] 1.1 Change `ManualRunRunner` (`:bootstrap`, ctor:152) to take an injected
      `ManualRunAssembly` instead of building it inline from its ten ingredients (FR2, D3),
      deriving the listener-carrying copy with `withExtraListener` as today; the three
      ingredients the runner holds for nothing else (`filesExistCheckRunner`,
      `shellCommandCheckRunner`, `threadSleeper`) leave the constructor, the other seven stay
      as the runner's own parameters until sections 2 and 3 decide their clusters. Verify the
      constructor goes from 28 to 25 parameters, the runner reads no collaborator back
      through the assembly's package-private fields, the Spring context spec passes unedited
      (NFR-R2; it constructs nothing by hand) and `grep -rn "new ManualRunAssembly(" bootstrap/src/main` returns only the
      bean method.
- [ ] 1.2 Add `FactoryPaths` with `worktreesRoot()` and `homeDir()` accessors and one bean
      producing it, deleting the two `Path` beans in the same commit (FR3, FR5, D4); verify
      the context starts with the same graph, that
      `grep -rn "Path worktreesRoot\|Path homeDir"` over the files of the design's
      `FactoryPaths` row returns nothing, that
      `grep -rln "@Component" application/src/main bootstrap/src/main | xargs grep -ln "Path worktreesRoot\|Path homeDir"`
      returns nothing, and that `SlotWiring`'s `worktreesRoot` is filled from
      `FactoryPaths.worktreesRoot()` at both of its assembly points (the row's exemption).
- [ ] 1.3 Route every consumer in the design's `FactoryPaths` row through the new value,
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

## 2. Cluster by cluster, each judged against D2

- [ ] 2.1 Decide the report-commands cluster (`statusCommand`, `usageCommand`,
      `boardCommand`, `dashboardCommand`) against D2's three-part criterion; extract
      `ReportCommands` with at least one method beyond its accessors, or leave the cluster
      flat and record the reason. Verify the design's single-owner row is updated with the
      verdict either way.
- [ ] 2.2 Decide the vital-sources cluster shared by `ObservabilityAssembly.assemble:103`
      and `assembleSnapshot:175` against D2 — the ten parameters the design's `VitalSources`
      row lists, or the four vital feeds proper within them; extract `VitalSources` or record
      the rejection. Verify both sites come under the limit if extracted, and that the
      snapshot the writer produces is byte-identical either way.
- [ ] 2.3 Decide the time-sources cluster (`systemClock`, `javaTimeClock`, `threadSleeper`)
      against D2, keeping `testing.md`'s injected-time rule intact; verify
      `checkTestTimeInjection` still passes and no spec gained a `system()` call.
- [ ] 2.4 Decide the tracker-wiring cluster (`trackerAdapterRegistry`, `secretsProvider`,
      `pipelineSource` — the three members every listed site carries: `ManualRunRunner`
      ctor:152, `SubcommandDispatchFactory.of:30`, `TakeCommand` ctor:109, `ServeCommand`
      ctor:94) against D2; verify NFR-S1 by recording whether `SecretsProvider` gained any
      consumer, and enter the verdict in the design's table. The claim-epoch book is not a
      member: it is a bean that feeds only the `TaskGit` bundle and reaches
      `TrackerResolution.resolveTracker` through `git.epochs()` (design D2 of
      `fix-claim-epoch-fence`); it stays with `TaskGit` and no facade of this change carries it.
- [ ] 2.5 Decide the ledger-writers cluster of `ObservabilityWiring` ctor:47 (the four
      `*LedgerWriter`s and `ledgerAppender`, built together over one `RotatingLedgerAppender`
      in `ObservabilityAssembly.assemble:103`) against D2, per the design's `LedgerWriters`
      row; extract `LedgerWriters` with `newRunSummaryLedgerWriter()` or another method beyond
      accessors as its justification, or record the rejection. Verify the constructor comes
      under the limit if extracted, that every ledger line still reaches the same appender
      (the ledger specs pass with no expectation edited), and that the verdict is entered
      in the design's table.

## 3. The remaining composition sites

- [ ] 3.1 Bring `SubcommandDispatchFactory.of:30` and `ServeRuntimeAssembly.assemble:52`
      under the limit using the facades decided in section 2; verify each takes seven
      parameters or fewer and the serve specs pass with no expectation edited (their
      fixtures change with the signatures — task 3.8).
- [ ] 3.2 Bring `ObservabilityAssembly.assemble:103` and `assembleSnapshot:175` under the
      limit; verify the observability specs pass with no expectation edited and the
      snapshot JSON is unchanged, asserted against the existing snapshot round-trip spec.
- [ ] 3.3 Bring `TakeCommand` ctor:109, `TakeCommandFactory.of:24` and `:53`, `ServeCommand`
      ctor:94 and `ServeAssembly.feedAutomaton:60` under the limit; verify the
      command specs pass with no expectation edited.
- [ ] 3.4 Bring `TakeRefDispatch.run:25`, `TakeOutcomeDispatch.dispatch:50`,
      `TakeBatch.dispatch:117` and `InstanceHeartbeat` ctor:118 under the limit; verify
      their specs pass with no expectation edited.
- [ ] 3.5 Bring both `ManualRunAssembly` constructors (:89, :125) and the bean method that
      task 1.1 introduced under the limit; verify the assembly's own specs pass with no
      expectation edited and the context spec passes unedited.
- [ ] 3.6 Bring `ContainerRunSupport.create:130` and `ContainerRunSupportFactory.create:61`
      (`:bootstrap`) under the limit, judging any cluster against D2 and keeping plain
      `run`'s claimless path free of slot wiring; verify each takes seven parameters or
      fewer, the book still arrives only from `TaskGit.epochs()`, and
      `ContainerRunSupportSpec` and `ManualRunRunnerContainerOwnershipSpec` pass with no
      expectation edited.
- [ ] 3.7 Add `TakeClaimAndWork.workClaimed(RunOrder, TaskRef)` — fetch the claimed task,
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
- [ ] 3.8 Fixture sweep: every spec or fixture that constructs a site of this change by hand
      is updated to pass the new values (`FactoryPaths`, the facades of section 2, the
      injected `ManualRunAssembly`) — in the same task as the signature it constructs, with
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

## 4. `FeedAutomaton` — inspect, then decide (D5, Q1)

- [ ] 4.1 Read the three `FeedAutomaton` constructors (:65, :104, :142) and verify D5's
      hypothesis against the code: `IdleTiming` (built at `:160` from `idlePollInterval`,
      `backoffBase`, `backoffCap`, `random`) is the behavioral cluster, and the 11- and
      12-parameter constructors are a test seam with no production caller (`grep -rn "new
      FeedAutomaton(" application/src/main bootstrap/src/main` returns only
      `ServeAssembly.feedAutomaton`). Verify the finding is recorded either way, with the
      members of each candidate cluster named.
- [ ] 4.2 If the cluster holds, take `IdleTiming` as a constructor parameter, keep one
      constructor, and move the two defaulting constructors into a test fixture under
      `application/src/test` so the `RemoteOutageGates.system(...)` default leaves `src/main`
      and comes under `checkTestTimeInjection`; verify the one constructor is at seven or
      fewer and no spec expectation is edited (NFR-R1). If it does not hold, leave the class
      unchanged, record the SRP finding, and propose the follow-up change by name. Verify that whichever branch was taken enters the design's
      single-owner table as a `FeedAutomaton` row with its reason (the task report cites the
      row), and that no facade was invented to hit the number (M2).

## 5. Verification and handoff

- [ ] 5.1 Verify FR4 across the change against the design's single-owner table, the one
      record of every D2 verdict: every facade added carries at least one method beyond its
      accessors, named in its row, and every cluster rejected has its row with the failing
      criterion (M2). A verdict that exists only in a task report is a gap in the table.
- [ ] 5.2 Re-run the parameter-count scan over `src/main`; verify the twenty-two composition
      sites are gone (M1) and that exactly the ten NG1 sites remain, named by file and
      line as the input list for `add-parameter-count-gate` (M4).
- [ ] 5.3 Run `./gradlew :application:check :bootstrap:check` and verify every gate passes
      with no spec expectation edited (NFR-R1, M3), the context-start spec unedited
      (NFR-R2) and the log-expectation gate green with no expectation file edited (NFR-O1).
- [ ] 5.4 Add a glossary entry to `docs/glossary.md` for each facade that names a new
      domain concept, per `process-invariants.md`; verify no new term is left undefined.
- [ ] 5.5 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits).
