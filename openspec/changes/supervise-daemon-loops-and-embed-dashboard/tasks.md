# Tasks

Verification follows `.claude/rules/verification-scope.md`. Per task, run Spotless, compile the
touched modules, run the named specs, and run `pitestVerifyAllKilled -PpitScope=<the task's classes>`.
The root `./gradlew check` runs once, in group 11. Each sub-agent's report ends with the old-way
sweep its task names: the grep, the hits, and what happened to each (`implementation.md`).

## 1. Supervised daemon loop component (D1–D6; FR1–FR5, NFR-R2, NFR-R3, NFR-O1)

- [x] 1.1 Move `app/lease/RestartBackoff.java` to the new package `app.daemon` without changing
      behavior. Update its two users, `StandingReaper` and `app/serve/RemoteOutageProbeSchedule.java`,
      and move `RestartBackoffSpec` with it. Verify: `RestartBackoffSpec` and
      `RemoteOutageProbeSchedule*Spec` pass, and `grep -rn "app.lease.RestartBackoff" --include='*.java'
      --include='*.groovy' .` is empty.
- [x] 1.2 Add the operator-event codes `DAEMON_LOOP_TICK_FAILED`, `DAEMON_LOOP_STRAY_INTERRUPT`,
      `DAEMON_LOOP_WORKER_DIED`, `DAEMON_LOOP_GAVE_UP` and `DAEMON_LOOP_BACKOFF_SLEEP_FAILED` in
      `operatorevent/.../OperatorEvent.java` (D6), numbered after the highest code on `main` and in
      the active implementation of `make-checkpoint-gate-durable` (on 2026-10-08 that change holds
      `GF151`, so this change starts at `GF152` or later). Check both before numbering:
      `grep -o 'GF[0-9]*' OperatorEvent.java | sort | tail -1` on `main` and on that change's branch. Add `DASHBOARD("dashboard")`
      to `status/DaemonComponent.java` and to the spec that enumerates its keys. Verify: `:operatorevent`
      compiles, and the DaemonComponent spec passes with the new constant.
- [x] 1.3 Implement `app.daemon.SupervisedLoop` with `LoopShape(DaemonComponent, LoopOrder, LoopWait,
      RestartPolicy)`, the sealed `LoopWait` (`FixedInterval(Sleeper, Duration)`,
      `IntervalOrSignal(Duration)` with `signal()` and permit coalescing) and the sealed `RestartPolicy`
      (`Unbounded(base, cap)`, `Bounded(base, cap, maxRestarts, window, Clock)`), all over
      `RestartBackoff`. Provide `start()`, `stop()`, `stopAndJoin()` and `restartCount()`. The level-1
      guard is `catch (Throwable)` around the tick and the wait, reporting through `RepeatSuppressor`
      keyed by the component (D2). The interrupt check follows D3. `stop()` interrupts the worker only
      while the lock-guarded `waiting` flag is set, and releases `IntervalOrSignal` with `signal()`
      (D4). The death handler follows the
      three-phase shape of D4: nothing blocking under the lock, and `stopping` re-checked before
      spawning. The javadoc cites ADR 0013 and `.claude/rules/daemon-loops.md`, and states that the
      lock is a state-guarding lock (`lock-scope.md`). Verify: `SupervisedLoopSpec` (virtual time)
      covers both orders, both waits, an `Error` from the tick and from the wait, the WARN once and
      DEBUG repeats then INFO recovery (daemon-supervision "Repeated failures log edges"), coalesced
      signals, and a stray interrupt followed by a full wait. `SupervisedLoopRestartSpec` covers
      Unbounded backoff doubling and reset on a clean tick with an increasing count, and Bounded
      giving up on the sixth death within the window with no respawn. Every new code is asserted by
      the log capture. (The `Clock` named here is the domain port; task 3.1 turns it into
      `InstantSource`.)
- [x] 1.4 Write `SupervisedLoopStopConcurrencySpec` with real threads, per `lock-scope.md`
      "Specs". (a) A stop during a respawn backoff spawns no thread. (b) `stopAndJoin` racing a
      respawn returns only after the respawned thread exits, and no tick runs afterward. (c) A
      `stop()` caller returns promptly while the death handler sits in a latched backoff. (d) A
      `stop()` during a tick blocked on a latch does not interrupt it (the tick observes no interrupt
      flag), the tick completes, no WARN/ERROR is logged, and no further tick runs; a `stop()` during
      the wait ends it promptly (daemon-supervision "Stop during a run lets the run finish without a
      warning", D4). Verify:
      the spec passes 20 consecutive runs (`--rerun-tasks` loop in the task report).
- [x] 1.5 Add the glossary entry "supervised daemon loop" to `docs/glossary.md`, covering its
      two restart policies and *Never:* "background worker", "scheduler thread" (D15). Verify: the
      entry exists and no banned synonym appears in `application/src/main` (grep).
- [x] 1.6 Verify group 1 against the per-task rule: `pitestVerifyAllKilled
      -PpitScope=com.github.oinsio.gnomish.app.daemon.*` is green.

## 2. Standing reaper on the supervised loop (D2, D5, D6, D7; FR2, FR6)

- [x] 2.1 Rewrite `app/lease/StandingReaper.java` to hold a `SupervisedLoop` (wait → tick,
      `FixedInterval(interval)`, `Unbounded(interval, 10 min)`, component `REAPER`). Delete
      `loop()`, `onWorkerDeath`, `spawnWorker`, its `RestartBackoff` field and its own
      lock/stopping/worker. `restartCount()` delegates to the loop. Retire GF067, GF068 and GF069 from
      the catalog. Verify: the reaper's lifecycle and resilience specs are rewritten to assert the
      component codes plus `component=reaper`, and `VitalsSnapshotAssembler`'s
      spec still shows a grown `restartCount` after a respawn (daemon-supervision "Reaper restarts stay
      visible"). Old-way sweep: `grep -n "Thread.ofVirtual\|uncaughtExceptionHandler\|RestartBackoff"
      application/src/main/java/com/github/oinsio/gnomish/app/lease/StandingReaper.java` is empty.
- [x] 2.2 Create a "Retired codes" subsection in `docs/guides/operator-guide-observability.md`,
      right after the paragraph on `[GFnnn]` codes (the one ending "the practical way to find the
      ones a given incident produced"). No such table exists today: the guide names the
      `OperatorEvent` enum as the full list. Columns: retired code, replacement code, `component`
      filter (D6). Add the GF067, GF068 and GF069 rows. Verify: `grep -rn "GF06[789]" docs` finds them
      only in that subsection.
- [x] 2.3 Give the loop its own suppressor (D2, single-owner row 3). Add `app.daemon.RollUpPeriod`
      with `forInterval(Duration)` = `max(6 × interval, RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL)`;
      `BeatTiming.rollUp()` delegates to it, and `HeartbeatRollUpPeriodSpec`'s table becomes
      `RollUpPeriodSpec` in `app.daemon` (the heartbeat spec keeps one feature asserting the
      delegation). Add `interval()` to `LoopWait`. `SupervisedLoop` takes a `Clock` in place of the
      `RepeatSuppressor` and builds `new RepeatSuppressor(clock, RollUpPeriod.forInterval(interval))`
      itself; `SupervisedLoopHarness` passes its virtual clock. `StandingReaper` passes its own
      `clock` where it built `RepeatSuppressor.system()`. Record the rule durably: one sentence in
      `docs/adr/0004-logging-policy.md` ("Repeat suppression has one owner") and one line in
      `.claude/rules/logging.md` ("Suppress repeats"): a loop's roll-up period is derived from its
      interval, never taken from the catalog default. Verify: `SupervisedLoopSpec` gains the two
      scenarios of daemon-supervision "Repeated failures log edges" added for this task (a 5 min loop
      failing for an hour logs the first WARN and at most one roll-up per six ticks; roll-up and
      recovery observed on the virtual clock); `RollUpPeriodSpec`, `HeartbeatRollUpPeriodSpec`,
      `StandingReaperResilienceSpec` green; `pitestVerifyAllKilled
      -PpitScope=com.github.oinsio.gnomish.app.daemon.*,com.github.oinsio.gnomish.app.lease.BeatTiming,com.github.oinsio.gnomish.app.lease.StandingReaper*`.
      Old-way sweep: `grep -rn "RepeatSuppressor.system(\|new RepeatSuppressor(\|DEFAULT_ROLL_UP_INTERVAL"
      application/src/main/java/com/github/oinsio/gnomish/app/daemon application/src/main/java/com/github/oinsio/gnomish/app/lease/StandingReaper.java
      application/src/main/java/com/github/oinsio/gnomish/app/lease/BeatTiming.java` hits only
      `RollUpPeriod.java` and the constructor call inside `SupervisedLoop.java`.
      *Note (2026-10-08):* this task met a type gap — the loop held the domain `Clock` port while
      `RepeatSuppressor` takes `java.time.Clock` — and bridged it with `app.daemon.SuppressorClock`
      plus `SuppressorClockSpec`. Design D16 removes the gap; task 3.2 deletes the bridge.

## 3. One time source (D16–D22, single-owner rows 7–9, D14 pairs; FR17–FR23, NFR-R4, G5, G6, M6–M10)

This group lands before the loop migration so that groups 4–8 are written against the final
type. Each task's sub-agent is handed the file lists below; "every holder" is never left to the
sub-agent to discover.

- [x] 3.1 Replace the domain port with `java.time.InstantSource` (D16, FR17). Delete
      `domain/.../engine/port/Clock.java` and `domain/.../engine/time/SystemClock.java`. In every
      production file that imports the port (`grep -rl "domain.engine.port.Clock" */src/main
      adapters/*/src/main sandbox/*/src/main` — 16 in `:application`, 14 in `:adapters`, 6 in
      `sandbox/*`, 6 in `:domain` at design time), change the field or parameter type to
      `InstantSource` and `now()` to `instant()`; `RestartPolicy.Bounded`'s window clock included.
      In `:test-fixtures`, `VirtualClock implements InstantSource`: keep `advance(Duration)` and the
      `Instant.EPOCH` start, add `instant()`, drop the Groovy property named `instant` (it would
      shadow the method; D18). In every test source, `Mock(Clock)`/`Stub(Clock)`/`as Clock` of the
      port become `InstantSource` doubles (32 at design time), and `.now()` stubs become
      `.instant()`. `EnginePorts` and the `stage-engine` javadoc name the `InstantSource` environment
      port. `docs/glossary.md`: entries that say "the clock" for an injected time source (feed
      assembly, agent round equipment, box timing) say "the instant source". Verify: every module
      compiles; `:domain:test`, `:adapters:test`, `:adapters:git:test`, `:adapters:github:test`,
      `sandbox:*:test` and `:application:test` run whole (the rename touches most of their
      classes, so the task's scope is the module); `:bootstrap` only by the specs of the files it
      changed, by name. Old-way sweep: `grep -rn "domain.engine.port.Clock\|domain.engine.time.SystemClock\|\bSystemClock\b"
      --include='*.java' --include='*.groovy' --include='*.md' . | grep -v "openspec/changes/archive"`
      hits only this change's artifacts and `docs/adr/0014` (history).
- [x] 3.2 Narrow every infrastructure parameter from `java.time.Clock` to `InstantSource` and delete
      the bridge (D16, D18, FR17, FR19, FR21). Files (`grep -rl "import java.time.Clock;" */src/main
      adapters/*/src/main` — 37 at design time): `logtext/RepeatSuppressor` (the constructor;
      `system()` keeps `InstantSource.system()`), `serveobservability/writer/*` (`SnapshotWriter`,
      `SnapshotWriteCycle`, `RotatingLedgerAppender`, `TaskOutcomeLedgerWriter`, `RunSummaryLedgerWriter`,
      `SweepLedgerWriter`, `LifecycleLedgerWriter`, `LedgerRetentionSweeper`), `dashboard/DashboardWatchLoop`,
      `board/BoardComposition`, `app/sandboxlifecycle/SweepTickLog`, the `app` commands and seams
      (`BoardCommand`, `DashboardCommand`, `AdHocTaskSynthesizer`, `EscalationResume`, `TakeDispatcher`,
      `TakeDisposition`, `TakeTakeover`, `TakeBareAuto`, `take/AbortHandler`, `LedgerWriters`,
      `ObservabilityAssembly`, `ObservabilityWiring`, `ServeAssembly`, `ServeRuntimeAssembly`,
      `SlotWiringFactory`, `TakeCommandSeams`, `ResumeDecisionCommit`, `GitResumeContinuation`,
      `ContainerResumeOutcomes`, `BareTakeClaimWalk`), `adapters/git/GitObjectsTaskRepository`, and the
      four `:bootstrap` files (`ManualRunConfiguration`, `TrackerCommandConfiguration`,
      `ContainerRunSupportFactory`, `SandboxLifecyclePassFactory`). Delete `app/daemon/SuppressorClock.java`
      and `SuppressorClockSpec`; `SupervisedLoop` hands its `InstantSource` to the suppressor directly.
      `InstanceHeartbeat` builds its suppressor from its own `clock` (FR19). Delete
      `test-fixtures/.../time/MovableClock.groovy` and `application/src/test/.../testsupport/StepClock.groovy`;
      their callers use `VirtualClock` (the three `StepClock` specs — `DashboardWatchLoopSpec`,
      `SnapshotWriterSpec`, `RotatingLedgerAppenderSpec` — advance it explicitly between reads), and the
      eleven specs that built both a `VirtualClock` and a JDK clock build one. Verify: the specs of
      every listed class green; `HeartbeatOutageSuppressionSpec` gains the daemon-supervision scenario
      "Heartbeat suppression runs on the heartbeat's own time source" (roll-up and recovery observed on
      the virtual source, no real time); `pitestVerifyAllKilled
      -PpitScope=com.github.oinsio.gnomish.app.daemon.*,com.github.oinsio.gnomish.app.lease.InstanceHeartbeat*,com.github.oinsio.gnomish.logtext.RepeatSuppressor`.
      Old-way sweep: `grep -rn "import java.time.Clock;" */src/main adapters/*/src/main sandbox/*/src/main`
      is empty; `grep -rn "SuppressorClock\|MovableClock\|StepClock" --include='*.java' --include='*.groovy' .`
      hits only the declared copy inside `logtext/.../RepeatSuppressorSpec.groovy`.
      *Note (2026-10-09):* the scoped `:application` PIT run kills all 121 mutants but fails the
      gate on a pre-existing `MEMORY_ERROR` minion death on `InstanceHeartbeat` mutants
      (reproduced with this task's changes reverted; `pitest-conventions.gradle` documents the
      mode). Carried to the final gate (11.1).
- [x] 3.3 Sweep every hidden instant source outside the composition root (D17, FR18, FR20). One
      `@Bean InstantSource instantSource()` in `ManualRunConfiguration` replaces `systemClock()` and
      `javaTimeClock()`; `TrackerCommandConfiguration` and `SandboxLifecyclePassFactory` take it.
      By kind, each site receives the source (or the built retry/suppressor) through the wiring
      object it already takes, and its convenience constructor or field initializer goes:
      (a) second clock beside an injected one — `app/lease/InstanceHeartbeat.java` (done in 3.2),
      `app/ServeAssembly.java` (janitor, sweep tick), `app/TakeHeartbeat.java` (heartbeat, reaper),
      `app/serve/RemoteOutageGates.java` (`forServe(…)` takes the source; the `system(…)` overloads
      go), `app/serve/SlotLedger.java` (delete `SlotLedger(int)`), `app/TakeCommandSeams.java`
      (`defaults(InstantSource)` built by the root), and the sites the live grep found beyond the
      design list: `app/TakeBareAuto.java`, `app/TakeBatch.java`, `app/serve/RemoteOutageWiring.java`,
      `app/serve/RemoteOutageGate.java`;
      (b) no seam — `adapters/github`: the six marker/lease/heartbeat/decision classes take the source
      from the factory's package seam; `adapters/git/GitTaskRepository` takes it like its twin and
      both `Kept in sync with` sentences name the `createdAt` stamp (D14); the `adapters/git` relay
      chain (`GitTaskStore`, `GitTaskBranches`, `DeliveredBranchReader`, `BranchStateReader`,
      `UsageHistoryWalker`, `ContainerResumeBranch`, `PushBestEffortTaskLifecycleStore`,
      `PushBestEffortTaskRepository`) relays the retry and source; `adapters/.../inmemory/InMemoryTrackerHarness.reply`
      takes the instant; `app/ResumeDecisionCommit.decisionFor` takes the instant;
      `app/GitResumeContinuation`, `app/ContainerResumeOutcomes`, `TakeResumeRunner`,
      `TakeContainerResumeRunner` read `RunAssembly.instantSource()` (new accessor);
      `adapters/.../check/CommandProcessRunner`, `ShellCommandCheckRunner` lose their real-time
      constructors;
      (c) `X.system()` called outside the root — `RepeatSuppressor.system()` deleted in favour of
      `RepeatSuppressor.withDefaultRollUp(InstantSource)` (`:logtext`), used by `take/FinishedDecline`,
      `serve/FeedAssembly`, `serve/RemoteOutageGates`, `adapters/git/MidRoundPushRounds`,
      `SandboxRoundEnvironmentSource`, `FirstPush`, `adapters/github/.../GithubCheckExternalClient`;
      `TerminalWriteRetry` rides on `SlotWiring.terminalWriteRetry()` through `TakeWorkRouter`,
      `TakeDispositionResume`, `TakeLoadedBranchRoutes`, `TakeReconcileFinish`, `TakeReconcile`,
      `TakeFinishReport`, `TakeEscalationExit`, `TakePauseExit`, `TakeContainerEngineExecution`;
      `GitInfrastructureRetry` reaches `TaskBranchLocator` and `FirstPush` through the relay chain.
      Verified: `:logtext`, `:adapters`, `:adapters:git`, `:adapters:github`, `:application` whole;
      `:bootstrap` by name (63 + 31 spec classes); PIT 100 % on 32 `:application` classes, 14
      `adapters/git`, 9 `adapters/github`, the check runners and harness, `RepeatSuppressor`.
      Old-way sweep (the grep of 3.7) left exactly the sites that 3.4–3.6 own: the two surviving
      `system()` factories and their callers (`TakeEngineExecution`), the two GitHub SPI entry
      points, `ContainerRunSupportFactory`/`ContainerRunSupport` — each marked in code with
      "open decision (task 3.3)" — plus seven `new ThreadSleeper()` sites (D20), which this task's
      grep did not cover.
- [ ] 3.4 One carrier for real time: `TimeEquipment` (D20, single-owner row 8; FR22, G5, M9). Add
      `domain/.../engine/time/TimeEquipment.java`: `record TimeEquipment(InstantSource clock, Sleeper
      sleeper)` with `sleepUntil(Instant)` and `remaining(Instant)` — answer ADR 0010's three
      questions in the record's javadoc; if (b) fails, record the rejection in the design table and
      stop (the pair stays flat, the rest of this task still applies to `ThreadSleeper` and the
      factories). Move `domain/.../engine/time/ThreadSleeper.java` to `:bootstrap` (package of the
      root; `VirtualSleeper` is the only other implementor and lives in `:test-fixtures`). Build the
      equipment once: `@Bean TimeEquipment timeEquipment()` in `ManualRunConfiguration` beside the
      `instantSource()` bean (the bean returns `equipment.clock()`, so one identity), `threadSleeper()`
      goes. Delete `TerminalWriteRetry.system()` and `GitInfrastructureRetry.system()`; their
      constructors take `(TimeEquipment, bound…)`. Collapse the pair on every signature that carries
      it (live grep: `grep -rln "InstantSource" */src/main adapters/*/src/main sandbox/*/src/main |
      xargs grep -l "Sleeper"` — at design time `SupervisedLoop`, `LoopWait`, `InstanceHeartbeat`,
      `StandingReaper`, `FeedAssembly`, `FeedAutomaton`, `WorktreeJanitor`, `SandboxLifecycleTick`,
      `DashboardWatchLoop`, `TerminalWriteRetry`, `TakeCommandSeams`, `TakeHeartbeat`,
      `ServeAssembly`, `ServeRuntimeAssembly`, `MonotonicTime` (javadoc only), `ManualRunAssembly`,
      `ManualRunConfiguration`, `TrackerCommandConfiguration`, `ContainerRunSupportFactory`,
      `BoxTiming`, `EnginePorts`, `ExternalPolling`): each takes `TimeEquipment` where it took the
      two; `BoxTiming` becomes `(TimeEquipment, Duration dockerCommandTimeout)`; `EnginePorts` carries
      the equipment as its environment ports. Delete the seven in-place `new ThreadSleeper()`
      (`TakeCommandSeams` ×2, `ServeAssembly` ×3, `ServeRuntimeAssembly`, and inside the two
      factories). `:test-fixtures`: `VirtualTimeRetries` builds on a `TimeEquipment` of
      `VirtualClock` + `BudgetedVirtualSleeper`, exposed as one fixture (`VirtualTimeEquipment` or a
      static factory) that every spec building the pair uses. `docs/glossary.md`: entry "time
      equipment" (*Never:* "clock and sleeper pair", "system defaults"). Verify: every module
      compiles with every root `@Bean` at or below its previous parameter count (`manualRunAssembly`
      7→6, `slotWiringFactory` 7→6 — state the counts in the report); the specs of every listed class
      green on the virtual equipment; `ServeRuntimeWiringSpec` and the assembly specs green;
      `pitestVerifyAllKilled -PpitScope=com.github.oinsio.gnomish.domain.engine.time.*,com.github.oinsio.gnomish.app.take.TerminalWriteRetry,com.github.oinsio.gnomish.adapter.git.GitInfrastructureRetry,com.github.oinsio.gnomish.app.daemon.*`.
      Old-way sweep: `grep -rn "new ThreadSleeper(\|static .* system(" --include='*.java' */src/main
      adapters/*/src/main sandbox/*/src/main` hits only the equipment bean in `ManualRunConfiguration`
      and `HostResolver.system()` (not time); `grep -rn "InstantSource [a-z]*, *Sleeper\|Sleeper [a-z]*, *InstantSource"
      --include='*.java' */src/main adapters/*/src/main sandbox/*/src/main` hits only
      `TimeEquipment.java` itself.
- [ ] 3.5 Plugin SPI context objects (D21, single-owner row 9; FR20, FR23, G6, M10). In
      `gnomish-plugin-api`: add `TrackerAdapterContext` (`secrets()`, `config()`, `instanceId()`,
      `epochs()`, `timeEquipment()`) and `CheckClientContext` (`secrets()`, `subsection()`,
      `runContext()`, `timeEquipment()`) as interfaces, each with a javadoc stating the evolution
      rule (a new host collaborator is a new default accessor, never an overload) and
      `Implements FR23 of supervise-daemon-loops-and-embed-dashboard`; replace every `create`
      overload on `TrackerAdapterFactory` and `CheckClientFactory` with the single
      `create(<Context>)` — delete the old forms, no `@Deprecated` (NG10); update the class javadoc
      that today says "override this, never both". Bump `version` to `0.10.0` with the usual
      header-comment line naming this change and FR23, run `updateApiCompatibilityBaseline`, and
      update the breaking-release table in `gnomish-plugin-api/README.md` (it lags today — bring it
      to the full list from the build file). Move the implementors: `GithubTrackerAdapterFactory`
      (the package seam stays; the public `create` reads everything, including the equipment, from
      the context — delete its `InstantSource.system()`), `GithubCheckClientFactory` (same),
      `InMemoryTrackerAdapterFactory`, the sample plugin in `gnomish-plugin-api/sample`, and the
      `http` check provider that overrode the run-context overload. Move the builders: `TrackerWiring`
      gains a `TimeEquipment` constructor parameter (Spring injects it) and builds the context in
      `resolveTracker` and `resolveReadOnly`; `CheckEquipment` gains the equipment from the
      `checkEquipment` bean and hands it to `ProviderDispatchingExternalCheckClient`, which builds
      the check context. Keep every changed constructor at or below 7 (`CheckEquipment` 5→6). Verify:
      `:gnomish-plugin-api:check` (japicmp accepts the re-baselined break; the sample compiles);
      `GithubTrackerAdapterFactorySpec`/the GitHub contract spec and check-client specs green on a
      virtual equipment handed through the context; `TrackerWiringSpec`, `TrackerWiringOwnerBoundarySpec`,
      `CheckEquipment`/`ProviderDispatchingExternalCheckClient` specs and `ApplicationBeanInventorySpec`
      green; `TrackerAdapterDiscovery`/registry specs green (the no-arg constructor rule is unchanged);
      `pitestVerifyAllKilled -PpitScope=` the two factories, `TrackerWiring`,
      `ProviderDispatchingExternalCheckClient`. Old-way sweep: `grep -rn "create(SecretsProvider"
      --include='*.java' --include='*.groovy' .` hits nothing outside `openspec/changes/archive`;
      `grep -rn "InstantSource.system(" adapters/github/src/main` is empty; the stable spec
      `openspec/specs/plugin/plugin-discovery/spec.md` requirement "SPI factories construct with no
      args and receive dependencies as method arguments" is the one this change's delta MODIFIES
      (created by `/opsx:continue`, see the group note below).
- [ ] 3.6 The two leaves the parameter limit pushed into hiding (D22; FR18, FR22). (a)
      `TakeEngineExecution`: test the `abortFuse` + `retry` cluster against ADR 0010's three questions
      in the design table (used together in `TakeOutcomeDispatch`; behavior = `dispatch`; the name
      "terminal protocol"). If all three hold, introduce the member type (`TerminalProtocol` or the
      name the glossary's take-chain entries already use) holding the fuse and the retry, built by
      `TakeWorkRouter`/`TakeFreshClaim`/`TakeResumeExecution` from `SlotWiring`, and give both twins
      that member in place of `abortFuse` (host 7→7, container 6→5); update both `Kept in sync with`
      sentences. If one fails, record the rejection in the table and take the retry as the eighth
      member with `@ParameterLimitExemption(reason = …)` citing it. Either way delete the
      `TerminalWriteRetry.system()` call and the "open decision" comment. (b) `ContainerRunSupportFactory`
      gains a fifth component, the `BoxTiming` the root builds from the equipment (`ContainerSupports`
      takes a `BoxTiming` parameter in place of its separate timing values, so its test constructor
      stays at 7 — state the count); `ContainerEnvironments` gains `timing()`; `ContainerRunSupport`
      drops its `InstantSource` field and reads `environments.timing().equipment()`, and builds its
      `GitInfrastructureRetry` from the same equipment; `SandboxLifecyclePassFactory.create` takes the
      equipment. Delete both "open decision" comments. Verify: `TakeEngineExecutionSpec`,
      `TakeContainerEngineExecutionSpec`, `TakeOutcomeDispatchSpec`, `ContainerRunSupportSpec`,
      `ContainerSupportsSpec`, `ManualRunRunnerContainerOwnershipSpec`, `SandboxLifecyclePassFactorySpec`
      green; `pitestVerifyAllKilled -PpitScope=` the host and container engine executions,
      `TakeOutcomeDispatch` and the new member type. Old-way sweep:
      `grep -rn "open decision (task 3.3)\|InstantSource.system(\|GitInfrastructureRetry.system(" --include='*.java' .`
      hits only the equipment bean in `ManualRunConfiguration`.
- [ ] 3.7 Gates (D17, D20, FR18, FR21). Write `TimeSourceOwnerBoundarySpec` in `:bootstrap`
      (`BaseHeadDefaultBoundarySpec` shape): scan every module's `src/main` for `Clock.systemUTC(`,
      `Clock.systemDefaultZone(`, `InstantSource.system(`, `Instant\.now(` (dot escaped — the bare
      pattern matches `Instant now()` in `ObservabilityWiring`), `new SystemClock(`,
      `new ThreadSleeper(` and `\.system(`; allow only the one `:bootstrap` file that builds the
      time equipment (with its reason) and `EgressAllowlist.java` (`HostResolver.system()`, not
      time); assert the scan reached every allowlisted file. Widen `TestTimeInjectionCheck`
      (`build-logic/src/main/groovy/test-conventions.gradle`) from `.system(` to the same literal set
      over every test tree and `:test-fixtures/src/main`; migrate the current hits (about 134 at
      design time, plus the 24 `real-time-wiring` markers 3.1 placed in `:bootstrap`): fixtures that
      assemble the shipped composition (`AppAssemblyFixture`, `ServeRestartIntegrationSpec`, the
      end-to-end bases) carry the `real-time-wiring` marker with the reason, every other hit moves to
      the virtual equipment. Verify: the boundary spec is green and a scratch `Instant.now()` in
      `WorktreeJanitor`, a scratch `new ThreadSleeper()` in `FeedAssembly` (expected: does not
      compile — record that instead) and a scratch `InstantSource.system()` in `FinishedDecline` are
      each red naming the file (record the runs, then revert); `./gradlew checkTestTimeInjection` is
      green and a scratch `Clock.systemUTC()` in a spec is red; the `build-logic` functional spec for
      the check covers the new literals.
- [ ] 3.8 Durable record (D15, D19, D20, D21; FR16's shape for the time source). Write
      `docs/adr/0014-one-time-source.md`: the decision (one type, `InstantSource`; one carrier,
      `TimeEquipment`; one production source, the root; the real sleeper lives in the root), the
      history (the port came from `add-stage-engine` D8 with the motivation of JDK-8266847, and
      `InstantSource` appeared in no artifact; the sleeper half was found after the clock half was
      swept), the rejected shapes (D16 a–c, D20's gate-only alternative), wall versus monotonic time
      with the exemption table of the seven `System.nanoTime()` sites and the deliberate wall-time
      intervals (roll-up, restart window), the plugin SPI context as the way host equipment reaches
      a `ServiceLoader`-built plugin (D21), and the two gates. Confirm 0014 is free (`ls docs/adr`;
      `grep -rn "0014" openspec/changes`), else take the next number and update D15. Add the glossary
      entries "instant source" (*Never:* "clock port", "domain clock") and, if 3.4 did not, "time
      equipment". Update `.claude/rules/testing.md` "Time is injected": the type is `InstantSource`,
      the carrier is `TimeEquipment`, the fake is the virtual equipment, the gate's literal set, and
      that no `system()` factory exists — real time is built in one root file. Verify: the files
      exist; `grep -rn "clock port\|domain clock" --include='*.java' --include='*.groovy' --include='*.md' .
      | grep -v archive` is empty outside this change.
- [ ] 3.9 Measure M6–M10 for the task report: types for "now" in `src/main` (1), beans (1 equipment,
      1 instant source derived from it), production sites outside the root constructing real time
      (the 3.7 grep: 0), `system()` factories wiring time (0), signatures carrying the pair (0),
      fakes for the current instant (`VirtualClock` plus the `:logtext` copy), specs holding two
      clocks (0), `create` overloads per SPI factory (1). Verify: the numbers and the greps are in
      the report.

*Group note:* FR23 modifies the stable requirement "SPI factories construct with no args and
receive dependencies as method arguments" of `plugin/plugin-discovery`. Its delta spec
(`specs/plugin/plugin-discovery/spec.md`) does not exist yet in this change; create it with
`/opsx:continue` before 3.5 runs.

## 4. Worktree janitor and sandbox sweep tick on the supervised loop (D7, D9, D14; FR6)

- [ ] 4.1 Rewrite `app/serve/WorktreeJanitor.java` to hold a `SupervisedLoop` (tick → wait,
      `FixedInterval(1 h)`, `Unbounded(1 h, 10 min)`, component `JANITOR`, the janitor's own
      `InstantSource` per D16) and add `stop()`. Keep
      `tick()` and `lastRunAt`. Retire GF077. The tick's own codes (GF078, GF079) stay. Remove the
      `Kept in sync with SandboxLifecycleTick` paragraph. Verify: the janitor policy spec is unchanged
      and green. Its lifecycle spec asserts survival of an `Error` (daemon-supervision "The worktree
      cleaner survives an Error") and that `stop()` ends it.
- [ ] 4.2 The same for `app/serve/SandboxLifecycleTick.java` (component `SWEEP`, its configured
      interval, its own `InstantSource` per D16, retiring GF073), removing its `Kept in sync with WorktreeJanitor` paragraph. Verify:
      its specs are green, and `grep -rn "Kept in sync with" application/src/main/java/com/github/oinsio/gnomish/app/serve/WorktreeJanitor.java
      application/src/main/java/com/github/oinsio/gnomish/app/serve/SandboxLifecycleTick.java` is
      empty (the pair is dissolved, D14).
- [ ] 4.3 Give `app/serve/ServeShutdown.java` a `DaemonLoops` component (reaper, janitor, sweep
      tick) in place of the bare `StandingReaper`, stopped where the reaper is stopped today, before
      the grace wait (D9). Wire it in `ServeRuntimeAssembly`/`ServeAssembly`. Verify: a
      `ServeShutdown` spec asserts all three stop before `awaitDrained` and that a second `shutdown`
      is a no-op. The factory-serve scenario "No cleaner run during drain" passes in
      `ServeShutdownWiringSpec` (or its successor).
- [ ] 4.4 Add the GF073 and GF077 rows to the "Retired codes" subsection created in 2.2, and
      in `docs/guides/operator-guide-serve.md` state that `serve` shutdown stops the janitor and the
      sweep tick. Verify: grep as in 2.2.

## 5. Snapshot writer on the supervised loop (D3, D7, D8; FR6, FR7)

- [ ] 5.1 Rewrite `serveobservability/writer/SnapshotWriter.java` to hold a `SupervisedLoop` (tick
      → wait, `IntervalOrSignal(interval)`, `Unbounded(interval, 10 min)`, component `SNAPSHOT`, the
      writer's own `InstantSource` per D16).
      `markDirty()` becomes `wait.signal()`. `stopAfterFinalWrite()` becomes `loop.stopAndJoin()` then
      `writeCycle.writeOnce()`. Delete `awaitNextWake`, its own semaphore and the `log-contract-exempt`
      outer guard. Retire GF105. Verify: the writer's existing content and coalescing specs are
      green, and the spec "Interrupted writer does not spin" asserts one write per timer period after
      a stray interrupt. Old-way sweep: `grep -n "log-contract-exempt\|Semaphore\|Thread.ofVirtual"
      SnapshotWriter.java` is empty.
- [ ] 5.2 Identity spec `SnapshotWriterFinalWriteRaceSpec` (real threads, M5): the writer's tick is
      made to throw an `Error` on a latch so its thread dies, and `stopAfterFinalWrite()` is called
      while the respawn backoff is latched. Assert that the file's last content is the `stopped`
      record and that the counting writer saw no write after it. Also: a single death is followed
      by a write within two intervals on virtual time (serve-observability "Writer death is not a dead
      daemon"). Verify: 20 consecutive green runs.
- [ ] 5.3 Add the GF105 row to the "Retired codes" subsection created in 2.2. Verify: grep as in 2.2.

## 6. Enforcement and durable guidance (D14, D15, single-owner rows 1–2; FR16, M1, M2)

- [ ] 6.1 Write `DaemonLoopOwnerBoundarySpec` in `:bootstrap` (`ClaimlessGitBoundarySpec` shape). It
      scans `application/src/main` for `Thread.ofVirtual(`, `Thread.ofPlatform(` and `new Thread(`
      and allows only `app/daemon/SupervisedLoop.java`, `app/lease/HeldClaims.java`,
      `app/serve/FeedCycle.java`, `app/TakeBatch.java` and `app/ServeShutdownWiring.java`, each with
      its reason in the allowlist. It also fails on `Executors.newScheduled`,
      `Executors.newSingleThreadScheduled`, `ScheduledExecutorService` and `new Timer(` anywhere in
      `application/src/main` (none exist today), the silent-death shape D1 rejects. It allows `new RestartBackoff(` only in `app/daemon/RestartPolicy.java`
      (or the file holding the policies) and `app/serve/RemoteOutageProbeSchedule.java`. Row 3 (D2,
      task 2.3): `RepeatSuppressor.system(` and `new RepeatSuppressor(` fail in the five loop files
      and in `app/lease/BeatTiming.java`, and `RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL` is read
      only in `app/daemon/RollUpPeriod.java`. The spec asserts that the scan reached every
      allowlisted file. Verify: the spec is green, and a scratch
      `Thread.ofVirtual()` added to `WorktreeJanitor` makes it fail naming the file, and so does a
      scratch `Executors.newSingleThreadScheduledExecutor()` (record both red runs, then revert).
- [ ] 6.2 Write `docs/adr/0013-supervised-daemon-loop.md`: context (four loops, the silent-death
      history, `fix-reaper-idle-liveness` D4/D5), the decision (D1–D3, D5, D6), alternatives
      (`ScheduledExecutorService`, base class, per-loop codes), and the restart-policy choice (Unbounded
      / Bounded / unsupervised-by-design, the heartbeat). Confirm 0013 is still free (`ls docs/adr`,
      and `grep -rn "0013" openspec/changes`), else take the next number and update D15. Verify: the
      file exists and is linked from `SupervisedLoop`'s javadoc (the javadoc already cites it since
      task 1.3; this task makes the link resolve).
- [ ] 6.3 Write `.claude/rules/daemon-loops.md` in the shape of `lock-scope.md`. It covers what
      counts as a daemon loop, when to use `SupervisedLoop` and when not to (the five exemptions with
      reasons), how to pick the order, the wait and the restart policy, adding a `DaemonComponent`
      constant, the gate (`DaemonLoopOwnerBoundarySpec`, its scope `application/src/main` and its
      banned patterns, the scheduled executors and `Timer` included) and the review/audit obligation. Add its
      row to the "Process Rules" table in `CLAUDE.md`. Verify: the row exists, and the rule names
      the spec, the ADR and the glossary term.
- [ ] 6.4 Measure M1: `grep -rln "Thread.ofVirtual().name(\"gnomish-" application/src/main` lists only
      `SupervisedLoop.java` (or nothing, if the name is built there) and allowlisted files. Verify:
      the grep output is in the task report.

## 7. WIP stat from the board (D13, single-owner row 5; FR12–FR14, UX3)

- [ ] 7.1 Give `board/BoardModel.java` an `int wipLimit` (set in `build` from
      `EligibilityInputs.wipLimit()`) and an `openFrontCount()` method. Drop the `wipLimit` parameter
      from `board/json/BoardJsonMapper.serialize/toDto` and have it read the model. Rewrite that
      class's javadoc paragraph on `wipLimit` to state the reversed decision and why (D13). Update
      `app/BoardCommand.java:76`. Delete the four-argument `BoardModel.build(ready, open, truncated,
      generatedAt)` overload, which fills `wipLimit` with `Integer.MAX_VALUE` and has no caller in
      `src/main` (`BoardComposition` uses the five-argument form). Move its test callers
      (`grep -rn 'BoardModel.build(' application/src/test`: `BoardModelSpec`, `BoardJsonMapperSpec`,
      `DashboardBoardFixtures` and others) and every direct `new BoardModel(` in the tests to the
      five-argument form or to a fixture that states its limit. Verify: the board JSON
      reference-fixture spec is byte-identical (FR14), and `grep -rn "workingRows().size() +
      \|serialize(model, \|Integer.MAX_VALUE" application/src/main/java/com/github/oinsio/gnomish/board`
      is empty.
- [ ] 7.2 Pass the `BoardSectionView` to `dashboard/DashboardStatusCardRenderer.java` (from
      `DashboardHtmlRenderer.java:92`). Split `appendStats` so the slots and failures stats need a
      snapshot and the WIP stat needs a board model: value `n / W`, title `n of W open fronts: a
      working, b waiting for a human`, never `bad`. Verify: a status-card spec covers the
      dashboard-page scenarios "WIP stat with its split", "Full WIP is not an alarm", "No daemon
      yet" and "Board never loaded", plus a cached model after a failed refresh.
- [ ] 7.3 Identity spec (single-owner row 5): build one `BoardModel` through `BoardComposition.compose`
      with limit 3, three open fronts and a WIP-held ready row. Assert that the JSON `wipLimit`, the
      rendered WIP denominator and the limit the held row was judged against are all 3 (dashboard-page
      "Limit matches the held rows"). Verify: the spec passes.
- [ ] 7.4 Document the WIP stat in `docs/guides/operator-guide-dashboard.md` (the reference for
      `gnomish dashboard`, in its status-card description): what counts as an open front, that the limit is the project's `wip-limit`, and that it
      is a reference number and not an alarm. Verify: the section exists.

## 8. One dashboard assembly (D10, single-owner row 3; FR9, FR14)

- [ ] 8.1 Extract `app/DashboardWatch.java` taking `(ProjectLayout, String instanceName, @Nullable
      Path outOverride, BoardSource)`, with `BoardSource(Tracker, TrackerConfig,
      FactoryProperties.Tracker)` as a record. It owns `DEFAULT_FILE_NAME`, `BOARD_READY_LIMIT`, the
      `BoardComposition.compose` call, the render cycle, the board cache and a `SupervisedLoop`
      (`FixedInterval(10 s)`, `Bounded(10 s, 10 min, 5, 10 min)`, component `DASHBOARD`). Methods:
      `outputFile()`, `renderOnce()`, `start()`, `awaitEnd()` (true if given up), and
      `stopAndRenderFinal()`. Move `DashboardWatchLoop.renderOnce` into the tick and delete
      `DashboardWatchLoop.run`. Verify: the existing watch-loop specs, retargeted to the tick, are green,
      and the dashboard-page scenario "Tracker outage is not a death" holds on virtual time.
- [ ] 8.2 Route `app/DashboardCommand.java` through `DashboardWatch`: one-shot = `renderOnce`;
      `--watch` = `start()` then `awaitEnd()`, exiting 1 if the loop gave up (dashboard-page
      "Standalone renderer gives up"). It still builds its source from `TrackerWiring.resolveReadOnly`.
      Verify: `DashboardCommand` specs green. Old-way sweep: `grep -rn "\"dashboard.html\"\|BoardComposition.compose("
      application/src/main` hits `"dashboard.html"` only in `DashboardWatch.java` and
      `BoardComposition.compose(` only in `DashboardWatch.java` and `BoardCommand.java`.
- [ ] 8.3 Extend `DaemonLoopOwnerBoundarySpec` (6.1) with the row-3 checks, one allowlist per
      marker, each file asserted reached: the `"dashboard.html"` literal only in `DashboardWatch.java`;
      `BoardComposition.compose(` only in `DashboardWatch.java` and `BoardCommand.java`. Verify: the spec is green, and a scratch violation is red (record the run).

## 9. `serve --dashboard` (D9, D11, D12, single-owner row 4; FR8–FR11, FR15, NFR-R1, NFR-S1, NFR-P1, NFR-O2, UX1, UX2, UX5)

- [ ] 9.1 Add `dashboard` and `dashboardOut` to `app/ServeArguments.java` and
      `ServeArgumentsParser`, and `@ConfigLevel(Level.ANY) @Nullable Boolean dashboard` to
      `ServeProperties` (default `false`). `--dashboard-out` with the effective switch off throws
      `UsageException`. Verify: the parser spec covers both flags, `--dashboard-out` resolved like
      `dashboard --out`, and the factory-serve scenario "Output path without the dashboard". The
      `ConfigLevelCoverageSpec` and the unknown-option spec stay green.
- [ ] 9.2 Add `Tracker boardReader(BoundTracker bound, InstanceId readerId)` to
      `app/TrackerWiring.java` (D11): `bound.factory().create(secrets, bound.trackerConfig(),
      readerId.value())`, no `Path dir`, not health-wrapped, not epoch-stamped. Expose it through a new
      role interface `BoardReaders` that `TrackerWiring` implements, as it implements `RefResolution`.
      Verify: a `TrackerWiring` spec asserts the reader is built from the bound configuration (a
      checkout config is never read) under the given reader id, and `TrackerWiringOwnerBoundarySpec`
      stays green unchanged (no new `SecretsProvider` holder).
- [ ] 9.3 In the serve runtime assembly (`app/ServeRuntimeAssembly.java` / `ServeAssembly.java`),
      when the switch is on, build `BoardSource` from `BoardReaders.boardReader(bound,
      scope.mintInstanceId())` (a minted reader id, as `resolveReadOnly` mints one) with
      `bound.trackerConfig()`, and a `DashboardWatch` for the serve's layout and instance name. The
      assembly receives `BoardReaders`, never a `SecretsProvider`. In `ServeCommand`, start it after `observability().start()`,
      print `gnomish serve: dashboard -> <path>` on the human console, and add `dashboard` and
      `dashboardOut` to `AnchorLog.ServeConfig`. Verify: a serve-level spec asserts the printed line
      and the anchor fields (UX1, NFR-O2). Old-way sweep: `grep -rn "resolveReadOnly(" application/src/main`
      hits only `TrackerWiring.java`, `DashboardCommand.java` and `BoardCommand.java`.
- [ ] 9.4 Extend `DaemonLoopOwnerBoundarySpec` with row 4, as its own marker allowlist:
      `resolveReadOnly(` appears only in `TrackerWiring.java` (the declaration),
      `DashboardCommand.java` and `BoardCommand.java`, each asserted reached. Verify: green, and a
      scratch call from `ServeRuntimeAssembly` is red (record the run).
- [ ] 9.5 In `app/ServeShutdownWiring.java`, call `dashboard.stopAndRenderFinal()` after
      `observability.finalizeStopped(...)` on both `runDrain` and `runForever` paths when enabled
      (D9). Verify: a spec drives `serve --dashboard --drain` with the in-memory tracker and fake
      agent through the real composition root (`ServeRuntimeWiringSpec` style) and asserts that the
      page's last render shows the stopped state (factory-serve "Page after Ctrl-C", UX2), and that a
      second shutdown pass changes nothing.
- [ ] 9.6 Isolation specs: (a) board reads failing for the whole run leave the snapshot's tracker
      `consecutiveFailures` at 0 while slots complete ("Board outage is not a daemon tracker
      failure"). (b) A dashboard loop given up by injected tick `Error`s leaves the daemon claiming
      and completing tasks ("Disabled dashboard, working daemon"). (c) At most one `listReady` and one
      `listOpen` per board interval from the dashboard client over virtual time ("Tracker reads
      bounded"). Verify: all three green.
- [ ] 9.7 Identity spec (single-owner row 4): a `serve --dashboard` run over a bare origin whose
      default branch sets `wip-limit: 10` while the clone's checkout sets `wip-limit: 3`. The rendered
      WIP denominator is 10 ("Checkout differs from origin"). Verify: the spec passes on the real
      git medium (`BareGitRepoFixture`).
- [ ] 9.8 Switch `.gnomish/factory/gnomish-up` to `serve --dashboard --dashboard-out="$dashboard_out"`.
      Drop the background renderer, its PID, its log and its cleanup, and keep `--no-open`, `--no-logs`,
      the first-render wait (now polling the file while `serve` runs in the background until the page
      exists, then foregrounding it, or an equivalent that keeps one daemon process) and the log
      follower. Update `.gnomish/README.md`. Verify: `bash -n` and `shellcheck` are clean, and a
      manual run is recorded in the task report: one `gnomish` JVM in `ps`, the page opens, `Ctrl-C`
      leaves a "stopped" page (M3).
- [ ] 9.9 Document in `docs/guides/operator-guide-serve.md`: `--dashboard`, `--dashboard-out` and
      `factory.serve.dashboard`, the printed line, the final render, the bounded give-up, and that a
      `serve --dashboard` and a standalone `dashboard --watch` writing one file overwrite each other
      (UX5), with a pointer to `operator-guide-dashboard.md` for the page itself. In
      `operator-guide-dashboard.md` (its `--watch` wall-display recipe), note that the standalone
      `--watch` exits 1 when its loop is disabled, and mention `serve --dashboard` as the
      one-process alternative. Verify: both sections exist.

## 10. Traceability check

- [ ] 10.1 For every FR, NFR and UX in proposal.md, grep the specs and code comments for `FRn of
      supervise-daemon-loops-and-embed-dashboard` (`traceability.md`). Verify: each ID has at least
      one implementing spec or class, and the list is in the task report.

## 11. Final gate

- [ ] 11.1 Run the root `./gradlew check` once (no `--tests`, no `-PpitScope`) and fix what fails.
      Verify: green, with 100% mutation on the changed classes and the log-contract, log-expectation,
      time-source and test-time-injection gates green (retired codes named by no test, new codes
      asserted, no real clock outside the root).
