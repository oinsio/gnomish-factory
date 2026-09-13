## 1. The largest reduction first

- [ ] 1.1 Change `ManualRunRunner` (`:bootstrap`, ctor:141) to take an injected
      `ManualRunAssembly` instead of its nine ingredients (FR2, D3), deriving the
      listener-carrying copy with `withExtraListener` as today; verify the constructor
      loses nine parameters, the Spring context spec passes unedited (NFR-R2) and
      `grep -rn "new ManualRunAssembly(" bootstrap/src/main` returns only the bean method.
- [ ] 1.2 Add `FactoryPaths` with `worktreesRoot()` and `homeDir()` accessors and one bean
      producing it (FR3, FR5, D4); verify the context starts with the same graph and that
      `grep -rn "Path worktreesRoot\|Path homeDir" application/src/main bootstrap/src/main`
      returns only `FactoryPaths`' own declaration.
- [ ] 1.3 Route every consumer in the design's `FactoryPaths` row through the new value;
      verify no signature takes two adjacent `Path` parameters and that each listed site
      lost both.

## 2. Cluster by cluster, each judged against D2

- [ ] 2.1 Decide the report-commands cluster (`statusCommand`, `usageCommand`,
      `boardCommand`, `dashboardCommand`) against D2's three-part criterion; extract
      `ReportCommands` with at least one method beyond its accessors, or leave the cluster
      flat and record the reason. Verify the design's single-owner row is updated with the
      verdict either way.
- [ ] 2.2 Decide the vital-sources cluster shared by `ObservabilityAssembly.assemble:103`,
      `assembleSnapshot:175` and `ObservabilityWiring` ctor:47 against D2; extract
      `VitalSources` or record the rejection. Verify all three sites come under the limit
      if extracted, and that the snapshot the writer produces is byte-identical either way.
- [ ] 2.3 Decide the time-sources cluster (`systemClock`, `javaTimeClock`, `threadSleeper`)
      against D2, keeping `testing.md`'s injected-time rule intact; verify
      `checkTestTimeInjection` still passes and no spec gained a `system()` call.
- [ ] 2.4 Decide the tracker-wiring cluster (`trackerAdapterRegistry`, `secretsProvider`,
      `pipelineSource`, `claimEpochBook`) against D2; verify NFR-S1 by recording whether
      `SecretsProvider` gained any consumer, and enter the verdict in the design's table.

## 3. The remaining composition sites

- [ ] 3.1 Bring `SubcommandDispatchFactory.of:30` and `ServeRuntimeAssembly.assemble:51`
      under the limit using the facades decided in section 2; verify each takes seven
      parameters or fewer and the serve specs pass unedited.
- [ ] 3.2 Bring `ObservabilityAssembly.assemble:103` and `assembleSnapshot:175` under the
      limit; verify the observability specs pass unedited and the snapshot JSON is
      unchanged, asserted against the existing snapshot round-trip spec.
- [ ] 3.3 Bring `TakeCommand` ctor:112, `TakeCommandFactory.of:53`, `ServeCommand` ctor:93,
      `ServeAssembly.slotRunner:47` and `feedAutomaton:85` under the limit; verify the
      command specs pass unedited.
- [ ] 3.4 Bring `TakeRefDispatch.run:25`, `TakeOutcomeDispatch.dispatch:55`,
      `TakeBatch.dispatch:116` and `InstanceHeartbeat` ctor:118 under the limit; verify
      their specs pass unedited.
- [ ] 3.5 Bring both `ManualRunAssembly` constructors (:82, :116) under the limit; verify
      the assembly's own specs and the context spec pass unedited.

## 4. `FeedAutomaton` — inspect, then decide (D5, Q1)

- [ ] 4.1 Read the three `FeedAutomaton` constructors (:65, :104, :142) and determine
      whether a behavioral cluster exists; verify the finding is recorded either way, with
      the members of each candidate cluster named.
- [ ] 4.2 If a cluster exists, extract it and bring all three constructors under the limit;
      if not, leave the class unchanged, record the SRP finding, and propose the follow-up
      change by name — verify that whichever branch was taken is stated in the task report
      with its reason, and that no facade was invented to hit the number (M2).

## 5. Verification and handoff

- [ ] 5.1 Verify FR4 across the change: every facade added carries at least one method
      beyond its accessors; list them with the method that justifies each, and list every
      cluster rejected with its reason (M2).
- [ ] 5.2 Re-run the parameter-count scan over `src/main`; verify the twenty composition
      sites are gone (M1) and that exactly the ten NG1 sites remain, named by file and
      line as the input list for `add-parameter-count-gate` (M4).
- [ ] 5.3 Run `./gradlew :application:check :bootstrap:check` and verify every gate passes
      with no spec expectation edited (NFR-R1, M3), the context-start spec unedited
      (NFR-R2) and the log-expectation gate green with no expectation file edited (NFR-O1).
- [ ] 5.4 Add a glossary entry to `docs/glossary.md` for each facade that names a new
      domain concept, per `process-invariants.md`; verify no new term is left undefined.
- [ ] 5.5 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits).
