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
      keyed by the component (D2; the catch type is narrowed to `Exception` by task 12.1, D2 as
      amended). The interrupt check follows D3. `stop()` interrupts the worker only
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
- [x] 3.4 One carrier for real time: `TimeEquipment` (D20, single-owner row 8; FR22, G5, M9). Add
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
- [x] 3.5 Plugin SPI context objects (D21, single-owner row 9; FR20, FR23, G6, M10). In
      `gnomish-plugin-api`: add `TrackerAdapterContext` (`secrets()`, `config()`, `instanceId()`,
      `epochs()`, `timeEquipment()`) and `CheckClientContext` (`secrets()`, `subsection()`,
      `runContext()`, `timeEquipment()`) as interfaces, each with a javadoc stating the evolution
      rule (a new host collaborator is a new default accessor, never an overload) and
      `Implements FR23 of supervise-daemon-loops-and-embed-dashboard`; replace every `create`
      overload on `TrackerAdapterFactory` and `CheckClientFactory` with the single
      `create(<Context>)` — delete the old forms, no `@Deprecated` (NG10); update the class javadoc
      that today says "override this, never both". Bump `version` to `0.11.0` (0.10.0 was taken by the merged `make-checkpoint-gate-durable`) with the usual
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
- [x] 3.6 The two leaves the parameter limit pushed into hiding (D22 as revised 2026-10-09; FR18,
      FR22; single-owner rows "`SlotWiring.outcomeDispatch()`" and "`SandboxModeSelector` as an
      instance"). **(a) The slot's outcome dispatch.** Reshape `TakeOutcomeDispatch` (`:application`):
      fields `TerminalWriteRetry retry`, `AbortFuse abortFuse` (package-private constructor);
      `dispatch(TaskOutcome, TaskContext, String branchName, TakeOrder, TerminalTransitions)`; the
      javadoc states the per-call lifetime rule (the transitions are valid for the call and never
      retained) and names ADR 0010's three answers. Add `app/take/TerminalTransitions.java`: `record
      TerminalTransitions(ParkTransition park, FinishTransition finish)` with a compact constructor
      refusing a null half and a javadoc that names `fix-terminal-receipt-convergence` as the change
      that will reshape it. Add `SlotWiring.outcomeDispatch()` returning `new
      TakeOutcomeDispatch(terminalWriteRetry, abort)` — the one construction site. Both twins take
      `TakeOutcomeDispatch dispatch` in place of `abortFuse`; the container twin also drops `retry`
      (host 7 → 7, container 6 → 5 — state the counts); each builds its `TerminalTransitions` from
      its park and finish closures and calls `dispatch.dispatch(…)`. Delete the `new
      TerminalWriteRetry(assembly.timeEquipment(), DEFAULT_BOUND)` line and the "open decision"
      comment in `TakeEngineExecution`; update the four builder sites (`TakeFreshClaim`,
      `TakeResumeExecution`, `TakeContainerFreshClaim`, `TakeContainerResumeRunner`) to pass
      `wiring.outcomeDispatch()`; rewrite both `Kept in sync with` sentences to name the dispatch as
      the shared terminal path; record in `TakeOutcomeDispatch`'s javadoc why the retry is a member
      and not a decorator (it changes the outcome on give-up). Lifetimes: state in the dispatch
      javadoc that it holds nothing shorter-lived than the slot. **(b) The execution-mode selector.**
      Add `app/port/run/ContainerRuntimeProbe.java` (`boolean available()`, `Implements FR18 of
      supervise-daemon-loops-and-embed-dashboard` and FR14/D13 of add-sandbox-core for the
      fail-closed semantics). Turn `SandboxModeSelector` into an instance: constructor
      `(BindingProperties, SandboxProperties, AdapterBindingRegistry, ContainerRuntimeProbe)`,
      method `Plan plan(PipelineDefinition definition, RegisteredClone clone)`; the private static
      helpers become instance methods or stay static over their own parameters; the class stays
      under 200 lines (158 today). `ContainerTakeSupport` becomes `(SandboxModeSelector modeSelector,
      ContainerSupportFactory containerSupportFactory)`; `hostOnly()` builds the selector over
      a probe that throws if asked (D22, amended); `TakeWorkRouter.plan` calls `wiring.containerTakeSupport().modeSelector().plan(definition,
      wiring.registeredClone())`. `ContainerSupports`: one constructor `(Map<String, CheckClientFactory>,
      FactoryProperties, SandboxProperties, SandboxModeSelector, TaskGit, TimeEquipment)` — delete
      the 7-argument test constructor, the `@Autowired` marker and the `DockerRuntimeProbe` import;
      `plan` delegates to the selector; `takeSupport()` builds `new ContainerTakeSupport(modeSelector,
      supportFactory(TRACKED))`; `supportFactory` passes the equipment to
      `ContainerRunSupportFactory`'s installation constructor, which becomes the 7-argument
      `(…, FactoryProperties, TimeEquipment)` one (delete the 6-argument one and its "open decision"
      comment; the canonical 8-component form is unchanged). Root beans, beside the one that provides
      `AdapterBindingRegistry` (`grep -rn "AdapterBindingRegistry" bootstrap/src/main`): `@Bean
      ContainerRuntimeProbe containerRuntimeProbe()` returning `DockerRuntimeProbe::dockerAvailable`,
      and `@Bean SandboxModeSelector sandboxModeSelector(BindingProperties, SandboxProperties,
      AdapterBindingRegistry, ContainerRuntimeProbe)`. `ContainerEnvironments` (`sandbox/docker`)
      gains `BoxTiming timing` as its fifth constructor argument (passed by
      `ContainerEnvironmentFactory.forTask`) and a `timing()` accessor; `ContainerRunSupport` deletes
      its `time` and `instantSource` fields and the "open decision" comment, reading
      `environments.timing().equipment()` once into a local for the store, the suppressor, the round
      source and its `GitInfrastructureRetry`. Migrate the specs: the three `ContainerSupports`
      constructions (`AppAssemblyFixture`, `ContainerSupportsSpec`, `ManualRunRunnerContainerOwnershipSpec`)
      pass a selector over a scripted probe and the virtual equipment; the six
      `new ContainerTakeSupport(` sites (`grep -rl "new ContainerTakeSupport(" --include='*.groovy' .`)
      and `TakeContainerEngineExecutionSpec` move to the new shapes; `ContainerEnvironments`
      fixtures pass a `BoxTiming` over the virtual equipment. Verify: `TakeContainerEngineExecutionSpec`,
      `ContainerTakeSupportSpec`, `SlotWiringFactorySpec`, `TakeContainerFreshClaimSpec`,
      `TakeResumeRoutingSpec`, `TakeContainerResumeRoutingSpec`, `ContainerRunSupportSpec`,
      `ContainerSupportsSpec`, `ManualRunRunnerContainerOwnershipSpec`, `SandboxLifecyclePassFactorySpec`
      and the `SandboxModeSelector` specs green; write `TakeOutcomeDispatchSpec` (one feature per
      arm, plus the null-half refusal of `TerminalTransitions`) and the identity feature of the
      dispatch row (a slot assembled on a virtual equipment with a distinguishable retry bound; the
      dispatch of a run waits on that bound; red with `outcomeDispatch()` bypassed);
      `pitestVerifyAllKilled -PpitScope=com.github.oinsio.gnomish.app.TakeOutcomeDispatch,com.github.oinsio.gnomish.app.take.TerminalTransitions,com.github.oinsio.gnomish.app.SandboxModeSelector,com.github.oinsio.gnomish.app.ContainerTakeSupport,com.github.oinsio.gnomish.app.TakeEngineExecution,com.github.oinsio.gnomish.app.TakeContainerEngineExecution`.
      Old-way sweep: `grep -rn "open decision (task 3.3)\|InstantSource.system(\|GitInfrastructureRetry.system(\|new TerminalWriteRetry(\|BooleanSupplier\|DockerRuntimeProbe" --include='*.java' */src/main adapters/*/src/main sandbox/*/src/main`
      hits only the equipment bean and the retry bean in the root configuration files, the probe
      bean, and `DockerRuntimeProbe.java` itself; `grep -rn "new TakeOutcomeDispatch(" --include='*.java' .`
      hits only `SlotWiring.java`.
- [x] 3.7 Gates (D17, D20, FR18, FR21). Write `TimeSourceOwnerBoundarySpec` in `:bootstrap`
      (`BaseHeadDefaultBoundarySpec` shape): scan every module's `src/main` for `Clock.systemUTC(`,
      `Clock.systemDefaultZone(`, `InstantSource.system(`, `Instant\.now(` (dot escaped — the bare
      pattern matches `Instant now()` in `ObservabilityWiring`), `new SystemClock(`,
      `new ThreadSleeper(` and `\.system(`; allow only the one `:bootstrap` file that builds the
      time equipment (with its reason) and `EgressAllowlist.java` (`HostResolver.system()`, not
      time); assert the scan reached every allowlisted file. Two more literals with one-file
      allowlists, from D22 (revised): `new TerminalWriteRetry(` only in
      `TrackerCommandConfiguration.java` (a second producer of the time-built retry is the defect
      3.6 removed; *moved by 3.9 to `SlotWiring.java`*, which derives the retry from the slot's
      time equipment) and `new TakeOutcomeDispatch(` only in `SlotWiring.java`; and a sibling scan over
      `application/src/main` and `bootstrap/src/main` banning `BooleanSupplier` outright (the probe
      is `ContainerRuntimeProbe`), allowlist asserted reached. The ban's one pre-existing hit,
      `ConsoleTakeoverConfirmation(BooleanSupplier ttyPresent, …)`, is not exempted (user decision
      2026-10-09, clause (iii) of 3.8): add a role interface `TerminalPresence` (`boolean attached()`)
      in `app`, make the record take it, and move its specs to the new type. The literal scans
      cover `src/main` only — the same-package specs that build `new TakeOutcomeDispatch(` are
      outside them. Widen `TestTimeInjectionCheck`
      (`build-logic/src/main/groovy/test-conventions.gradle`) from `.system(` to the same literal set
      over every test tree and `:test-fixtures/src/main`; migrate the current hits (about 134 at
      design time, plus the 24 `real-time-wiring` markers 3.1 placed in `:bootstrap`): fixtures that
      assemble the shipped composition (`AppAssemblyFixture`, `ServeRestartIntegrationSpec`, the
      end-to-end bases) carry the `real-time-wiring` marker with the reason, every other hit moves to
      the virtual equipment. The migration also covers the hits the merge of
      `make-checkpoint-gate-durable` brought in after the design-time count, which today's
      `.system(` check does not see: `Instant.now()` in `bootstrap/.../app/ContainerContinuationMedium`,
      `app/RoundTokenIdentitySpec`, `app/killpoint/RequestSnapshotKillPoints` and
      `app/killpoint/TakeKillPointWorlds`; `Clock.systemUTC()` in `app/KillPointTakeRoutes`; and
      `Instant.now()` in `LocalBoxEnvironment`, which moved from the `adapters/git` test tree to
      `test-fixtures/src/main` (re-run the grep before migrating, since the list may have grown).
      Verify: the boundary spec is green and a scratch `Instant.now()` in
      `WorktreeJanitor`, a scratch `new ThreadSleeper()` in `FeedAssembly` (expected: does not
      compile — record that instead) and a scratch `InstantSource.system()` in `FinishedDecline` are
      each red naming the file (record the runs, then revert); `./gradlew checkTestTimeInjection` is
      green and a scratch `Clock.systemUTC()` in a spec is red; the `build-logic` functional spec for
      the check covers the new literals. Also write the identity spec of single-owner row 8
      (assigned here 2026-10-09, after 3.4 found no task owning it): `AppAssemblyFixture` assembled
      on a frozen `TimeEquipment` — every stamp a container run writes and every roll-up it logs
      reads the frozen instant (the shape of FR19, across the whole run); name it in row 8.
- [x] 3.8 Durable record (D15, D19, D20, D21; FR16's shape for the time source). Write
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
      equipment"; from D22 (revised): "execution mode" (host | container; the fail-closed decision
      one selector makes over the bindings, the sandbox settings, the registry and the runtime probe;
      type `SandboxModeSelector`; *Never:* "run mode", "sandbox flag") and "terminal transitions"
      (one run's park and finish steps as one value; type `TerminalTransitions`), and update the
      "Container supports" and "Slot wiring" entries (the probe and the four selector inputs leave
      the former; `outcomeDispatch()` joins the latter). Add to `.claude/rules/process-invariants.md`,
      beside the parameter-count rule, the three clauses D22 borrowed: (i) a constructor takes the
      collaborators and configuration stable for the object's lifetime, a method takes the data of
      one operation (van Deursen; the test for "field or parameter?"); (ii) a shorter constructor
      may supply only a Local Default (a no-op, a Null Object), never another module's adapter — a
      "test constructor" is that defect by another name (`grep -rn "test constructor" --include='*.java'`
      lists the survivors to fix as they are touched); (iii) a seam a spec fakes is a role interface
      in the owning module, never a JDK functional type. Each clause names D22 of this change as its
      provenance. Update `.claude/rules/testing.md` "Time is injected": the type is `InstantSource`,
      the carrier is `TimeEquipment`, the fake is the virtual equipment, the gate's literal set, and
      that no `system()` factory exists — real time is built in one root file. Verify: the files
      exist; `grep -rn "clock port\|domain clock" --include='*.java' --include='*.groovy' --include='*.md' .
      | grep -v archive` is empty outside this change.
- [x] 3.9 Measure M6–M10 for the task report: types for "now" in `src/main` (1), beans (1 equipment,
      1 instant source derived from it), production sites outside the root constructing real time
      (the 3.7 grep: 0), `system()` factories wiring time (0), signatures carrying the pair (0),
      fakes for the current instant (`VirtualClock` plus the `:logtext` copy), specs holding two
      clocks (0), `create` overloads per SPI factory (1). Verify: the numbers and the greps are in
      the report.
      *Result (2026-10-09):* M6 1 type, 1 equipment bean + 1 derived `InstantSource` bean; M7 0
      (the 3.7 literal grep hits only `ManualRunConfiguration` and `EgressAllowlist`); M9 0 `system()`
      factories, 0 pair signatures; M10 1 + 1. M8 first missed (a third fake `AdvancingClock`; two
      specs with two clocks through the `ServeAssembly.observability(…, InstantSource)` hatch) and was
      fixed systemically: the hatch removed; the slot's time derived from `RunAssembly.timeEquipment()`
      (`SlotWiringFactory` takes no clock or retry, `new TerminalWriteRetry(` pinned to `SlotWiring`,
      `new AbortHandler(` to `SlotWiringFactory`; `TakeCommandSeams.withClock` deleted); the heartbeat
      beater takes the tick's instant; clause (iii) seams in the group-3 classes became role
      interfaces; `checkTestTimeInjection` reports a marker that excuses nothing. After: M8 = 0.

*Group note:* FR23 modifies the stable requirement "SPI factories construct with no args and
receive dependencies as method arguments" of `plugin/plugin-discovery`. Its delta spec
(`specs/plugin/plugin-discovery/spec.md`) does not exist yet in this change; create it with
`/opsx:continue` before 3.5 runs.

## 4. Worktree janitor and sandbox sweep tick on the supervised loop (D7, D9, D14; FR6)

- [x] 4.1 Rewrite `app/serve/WorktreeJanitor.java` to hold a `SupervisedLoop` (tick → wait,
      `FixedInterval(1 h)`, `Unbounded(1 h, 10 min)`, component `JANITOR`, the janitor's own
      `InstantSource` per D16) and add `stop()`. Keep
      `tick()` and `lastRunAt`. Retire GF077. The tick's own codes (GF078, GF079) stay. Remove the
      `Kept in sync with SandboxLifecycleTick` paragraph. Verify: the janitor policy spec is unchanged
      and green. Its lifecycle spec asserts survival of an `Error` (daemon-supervision "The worktree
      cleaner survives an Error") and that `stop()` ends it.
- [x] 4.2 The same for `app/serve/SandboxLifecycleTick.java` (component `SWEEP`, its configured
      interval, its own `InstantSource` per D16, retiring GF073), removing its `Kept in sync with WorktreeJanitor` paragraph. Verify:
      its specs are green, and `grep -rn "Kept in sync with" application/src/main/java/com/github/oinsio/gnomish/app/serve/WorktreeJanitor.java
      application/src/main/java/com/github/oinsio/gnomish/app/serve/SandboxLifecycleTick.java` is
      empty (the pair is dissolved, D14).
- [x] 4.3 Give `app/serve/ServeShutdown.java` a `DaemonLoops` component (reaper, janitor, sweep
      tick) in place of the bare `StandingReaper`, stopped where the reaper is stopped today, before
      the grace wait (D9). Wire it in `ServeRuntimeAssembly`/`ServeAssembly`. Verify: a
      `ServeShutdown` spec asserts all three stop before `awaitDrained` and that a second `shutdown`
      is a no-op. The factory-serve scenario "No cleaner run during drain" passes in
      `ServeShutdownWiringSpec` (or its successor).
- [x] 4.4 Add the GF073 and GF077 rows to the "Retired codes" subsection created in 2.2, and
      in `docs/guides/operator-guide-serve.md` state that `serve` shutdown stops the janitor and the
      sweep tick. Verify: grep as in 2.2.

## 5. Snapshot writer on the supervised loop (D3, D7, D8; FR6, FR7)

- [x] 5.1 Rewrite `serveobservability/writer/SnapshotWriter.java` to hold a `SupervisedLoop` (tick
      → wait, `IntervalOrSignal(interval)`, `Unbounded(interval, 10 min)`, component `SNAPSHOT`, the
      writer's own `InstantSource` per D16).
      `markDirty()` becomes `wait.signal()`. `stopAfterFinalWrite()` becomes `loop.stopAndJoin()` then
      `writeCycle.writeOnce()`. Delete `awaitNextWake`, its own semaphore and the `log-contract-exempt`
      outer guard. Retire GF105. Verify: the writer's existing content and coalescing specs are
      green, and the spec "Interrupted writer does not spin" asserts one write per timer period after
      a stray interrupt. Old-way sweep: `grep -n "log-contract-exempt\|Semaphore\|Thread.ofVirtual"
      SnapshotWriter.java` is empty.
- [x] 5.2 Identity spec `SnapshotWriterFinalWriteRaceSpec` (real threads, M5): the writer's tick is
      made to throw an `Error` on a latch so its thread dies, and `stopAfterFinalWrite()` is called
      while the respawn backoff is latched. Assert that the file's last content is the `stopped`
      record and that the counting writer saw no write after it. Also: a single death is followed
      by a write within two intervals on virtual time (serve-observability "Writer death is not a dead
      daemon"). Verify: 20 consecutive green runs.
- [x] 5.3 Add the GF105 row to the "Retired codes" subsection created in 2.2. Verify: grep as in 2.2.

## 6. Enforcement and durable guidance (D14, D15, single-owner rows 1–2; FR16, M1, M2)

- [x] 6.1 Write `DaemonLoopOwnerBoundarySpec` in `:bootstrap` (`ClaimlessGitBoundarySpec` shape). It
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
- [x] 6.2 Write `docs/adr/0013-supervised-daemon-loop.md`: context (four loops, the silent-death
      history, `fix-reaper-idle-liveness` D4/D5), the decision (D1–D3, D5, D6), alternatives
      (`ScheduledExecutorService`, base class, per-loop codes), and the restart-policy choice (Unbounded
      / Bounded / unsupervised-by-design, the heartbeat). Confirm 0013 is still free (`ls docs/adr`,
      and `grep -rn "0013" openspec/changes`), else take the next number and update D15. Verify: the
      file exists and is linked from `SupervisedLoop`'s javadoc (the javadoc already cites it since
      task 1.3; this task makes the link resolve).
- [x] 6.3 Write `.claude/rules/daemon-loops.md` in the shape of `lock-scope.md`. It covers what
      counts as a daemon loop, when to use `SupervisedLoop` and when not to (the five exemptions with
      reasons), how to pick the order, the wait and the restart policy, adding a `DaemonComponent`
      constant, the gate (`DaemonLoopOwnerBoundarySpec`, its scope `application/src/main` and its
      banned patterns, the scheduled executors and `Timer` included) and the review/audit obligation. Add its
      row to the "Process Rules" table in `CLAUDE.md`. Verify: the row exists, and the rule names
      the spec, the ADR and the glossary term.
- [x] 6.4 Measure M1: `grep -rln "Thread.ofVirtual().name(\"gnomish-" application/src/main` lists only
      `SupervisedLoop.java` (or nothing, if the name is built there) and allowlisted files. Verify:
      the grep output is in the task report.

## 7. WIP stat from the board (D13, single-owner row 5; FR12–FR14, UX3)

- [x] 7.1 Give `board/BoardModel.java` an `int wipLimit` (set in `build` from
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
- [x] 7.2 Pass the `BoardSectionView` to `dashboard/DashboardStatusCardRenderer.java` (from
      `DashboardHtmlRenderer.java:92`). Split `appendStats` so the slots and failures stats need a
      snapshot and the WIP stat needs a board model: value `n / W`, title `n of W open fronts: a
      working, b waiting for a human`, never `bad`. Verify: a status-card spec covers the
      dashboard-page scenarios "WIP stat with its split", "Full WIP is not an alarm", "No daemon
      yet" and "Board never loaded", plus a cached model after a failed refresh.
- [x] 7.3 Identity spec (single-owner row 5): build one `BoardModel` through `BoardComposition.compose`
      with limit 3, three open fronts and a WIP-held ready row. Assert that the JSON `wipLimit`, the
      rendered WIP denominator and the limit the held row was judged against are all 3 (dashboard-page
      "Limit matches the held rows"). Verify: the spec passes.
- [x] 7.4 Document the WIP stat in `docs/guides/operator-guide-dashboard.md` (the reference for
      `gnomish dashboard`, in its status-card description): what counts as an open front, that the limit is the project's `wip-limit`, and that it
      is a reference number and not an alarm. Verify: the section exists.

## 8. One dashboard assembly (D10, single-owner row 3; FR9, FR14)

- [x] 8.1 Extract `app/DashboardWatch.java` taking `(ProjectLayout, String instanceName, @Nullable
      Path outOverride, BoardSource)`, with `BoardSource(Tracker, TrackerConfig,
      FactoryProperties.Tracker)` as a record. It owns `DEFAULT_FILE_NAME`, `BOARD_READY_LIMIT`, the
      `BoardComposition.compose` call, the render cycle, the board cache and a `SupervisedLoop`
      (`FixedInterval(10 s)`, `Bounded(10 s, 10 min, 5, 10 min)`, component `DASHBOARD`). Methods:
      `outputFile()`, `renderOnce()`, `start()`, `awaitEnd()` (true if given up), and
      `stopAndRenderFinal()`. Move `DashboardWatchLoop.renderOnce` into the tick and delete
      `DashboardWatchLoop.run`. Verify: the existing watch-loop specs, retargeted to the tick, are green,
      and the dashboard-page scenario "Tracker outage is not a death" holds on virtual time.
- [x] 8.2 Route `app/DashboardCommand.java` through `DashboardWatch`: one-shot = `renderOnce`;
      `--watch` = `start()` then `awaitEnd()`, exiting 1 if the loop gave up (dashboard-page
      "Standalone renderer gives up"). It still builds its source from `TrackerWiring.resolveReadOnly`.
      Verify: `DashboardCommand` specs green. Old-way sweep: `grep -rn "\"dashboard.html\"\|BoardComposition.compose("
      application/src/main` hits `"dashboard.html"` only in `DashboardWatch.java` and
      `BoardComposition.compose(` only in `DashboardWatch.java` and `BoardCommand.java`.
- [x] 8.3 Extend `DaemonLoopOwnerBoundarySpec` (6.1) with the row-3 checks, one allowlist per
      marker, each file asserted reached: the `"dashboard.html"` literal only in `DashboardWatch.java`;
      `BoardComposition.compose(` only in `DashboardWatch.java` and `BoardCommand.java`. Verify: the spec is green, and a scratch violation is red (record the run). These row-3 dashboard checks and 9.4's now live in `DashboardOwnerBoundarySpec`, split out of the daemon-loop gate when its thread detectors grew (audit fix, 2026-10-10).

## 9. `serve --dashboard` (D9, D11, D12, single-owner row 4; FR8–FR11, FR15, NFR-R1, NFR-S1, NFR-P1, NFR-O2, UX1, UX2, UX5)

- [x] 9.1 Add `dashboard` and `dashboardOut` to `app/ServeArguments.java` and
      `ServeArgumentsParser`, and `@ConfigLevel(Level.ANY) @Nullable Boolean dashboard` to
      `ServeProperties` (default `false`). `--dashboard-out` with the effective switch off throws
      `UsageException`. Verify: the parser spec covers both flags, `--dashboard-out` resolved like
      `dashboard --out`, and the factory-serve scenario "Output path without the dashboard". The
      `ConfigLevelCoverageSpec` and the unknown-option spec stay green.
- [x] 9.2 Add `Tracker boardReader(BoundTracker bound, InstanceId readerId)` to
      `app/TrackerWiring.java` (D11): `bound.factory().create(secrets, bound.trackerConfig(),
      readerId.value())`, no `Path dir`, not health-wrapped, not epoch-stamped. Expose it through a new
      role interface `BoardReaders` that `TrackerWiring` implements, as it implements `RefResolution`.
      Verify: a `TrackerWiring` spec asserts the reader is built from the bound configuration (a
      checkout config is never read) under the given reader id, and `TrackerWiringOwnerBoundarySpec`
      stays green unchanged (no new `SecretsProvider` holder).
- [x] 9.3 In the serve runtime assembly (`app/ServeRuntimeAssembly.java` / `ServeAssembly.java`),
      when the switch is on, build `BoardSource` from `BoardReaders.boardReader(bound,
      scope.mintInstanceId())` (a minted reader id, as `resolveReadOnly` mints one) with
      `bound.trackerConfig()`, and a `DashboardWatch` for the serve's layout and instance name. The
      assembly receives `BoardReaders`, never a `SecretsProvider`. In `ServeCommand`, start it after `observability().start()`,
      print `gnomish serve: dashboard -> <path>` on the human console, and add `dashboard` and
      `dashboardOut` to `AnchorLog.ServeConfig`. Verify: a serve-level spec asserts the printed line
      and the anchor fields (UX1, NFR-O2). Old-way sweep: `grep -rn "resolveReadOnly(" application/src/main`
      hits only `TrackerWiring.java`, `DashboardCommand.java` and `BoardCommand.java`.
- [x] 9.4 Extend `DaemonLoopOwnerBoundarySpec` with row 4, as its own marker allowlist:
      `resolveReadOnly(` appears only in `TrackerWiring.java` (the declaration),
      `DashboardCommand.java` and `BoardCommand.java`, each asserted reached. Verify: green, and a
      scratch call from `ServeRuntimeAssembly` is red (record the run).
- [x] 9.5 In `app/ServeShutdownWiring.java`, call `dashboard.stopAndRenderFinal()` after
      `observability.finalizeStopped(...)` on both `runDrain` and `runForever` paths when enabled
      (D9). Verify: a spec drives `serve --dashboard --drain` with the in-memory tracker and fake
      agent through the real composition root (`ServeRuntimeWiringSpec` style) and asserts that the
      page's last render shows the stopped state (factory-serve "Page after Ctrl-C", UX2), and that a
      second shutdown pass changes nothing.
- [x] 9.6 Isolation specs: (a) board reads failing for the whole run leave the snapshot's tracker
      `consecutiveFailures` at 0 while slots complete ("Board outage is not a daemon tracker
      failure"). (b) A dashboard loop given up by injected tick `Error`s leaves the daemon claiming
      and completing tasks ("Disabled dashboard, working daemon"). (c) At most one `listReady` and one
      `listOpen` per board interval from the dashboard client over virtual time ("Tracker reads
      bounded"). Verify: all three green.
- [x] 9.7 Identity spec (single-owner row 4): a `serve --dashboard` run over a bare origin whose
      default branch sets `wip-limit: 10` while the clone's checkout sets `wip-limit: 3`. The rendered
      WIP denominator is 10 ("Checkout differs from origin"). Verify: the spec passes on the real
      git medium (`BareGitRepoFixture`).
- [x] 9.8 Switch `.gnomish/factory/gnomish-up` to `serve --dashboard --dashboard-out="$dashboard_out"`.
      Drop the background renderer, its PID, its log and its cleanup, and keep `--no-open`, `--no-logs`,
      the first-render wait (now polling the file while `serve` runs in the background until the page
      exists, then foregrounding it, or an equivalent that keeps one daemon process) and the log
      follower. Update `.gnomish/README.md`. Verify: `bash -n` and `shellcheck` are clean, and a
      manual run is recorded in the task report: one `gnomish` JVM in `ps`, the page opens, `Ctrl-C`
      leaves a "stopped" page (M3).
- [x] 9.9 Document in `docs/guides/operator-guide-serve.md`: `--dashboard`, `--dashboard-out` and
      `factory.serve.dashboard`, the printed line, the final render, the bounded give-up, and that a
      `serve --dashboard` and a standalone `dashboard --watch` writing one file overwrite each other
      (UX5), with a pointer to `operator-guide-dashboard.md` for the page itself. In
      `operator-guide-dashboard.md` (its `--watch` wall-display recipe), note that the standalone
      `--watch` exits 1 when its loop is disabled, and mention `serve --dashboard` as the
      one-process alternative. Verify: both sections exist.

## 10. Traceability check

- [x] 10.1 For every FR, NFR and UX in proposal.md, grep the specs and code comments for `FRn of
      supervise-daemon-loops-and-embed-dashboard` (`traceability.md`). Verify: each ID has at least
      one implementing spec or class, and the list is in the task report.

      **Recorded (2026-10-10, audit fix):** ID → an implementing class · a spec (each found by
      `grep "<ID>.*of supervise-daemon-loops-and-embed-dashboard"` or a `<ID>:` feature name).

      | ID | Class or file | Spec |
      |----|---------------|------|
      | FR1, FR2, FR4, FR5 | `SupervisedLoop`, `LoopControl`, `LoopWait` | `SupervisedLoopSpec`, `SupervisedLoopWaitSpec`, `SupervisedLoopInterruptSpec`, `SupervisedLoopStopSpec` |
      | FR3 | `RestartPolicy`, `LoopControl` | `SupervisedLoopRestartSpec`, `SupervisedLoopBoundedSpec` |
      | FR6 | `StandingReaper`, `DaemonLoops` | `StandingReaperInterruptedStopSpec`, `ServeShutdownDaemonLoopsSpec` |
      | FR7 | `SnapshotWriter` | `SnapshotWriterFinalWriteRaceSpec` |
      | FR8 | `ServeArgumentsParser`, `SwitchFlag`, `ServeProperties` | `ServeArgumentsParserSpec`, `SwitchFlagSpec` |
      | FR9, FR14 | `DashboardWatch`, `DashboardCommand` | `DashboardWatchSpec`, `DashboardCommandWatchSpec` |
      | FR10, NFR-S1 | `ServeDashboard`, `BoardReaders` | `ServeDashboardSpec`, `TrackerWiringSpec` |
      | FR11, UX2 | `DashboardWatch`, `ServeShutdownWiring` | `DashboardWatchFinalRenderSpec`, `ServeDashboardFinalRenderSpec` |
      | FR12, FR13, UX3 | `BoardModel`, `DashboardStatusCardRenderer` | `BoardWipLimitIdentitySpec`, `DashboardStatusCardWipSpec` |
      | FR15 | `.gnomish/factory/gnomish-up` | — (operator script) |
      | FR16 | `.claude/rules/daemon-loops.md`, `docs/adr/0013-supervised-daemon-loop.md` | `DaemonLoopOwnerBoundarySpec` |
      | FR17, FR18, NFR-R4 | `TimeEquipment`, `ManualRunConfiguration` | `TimeSourceOwnerBoundarySpec`, `FrozenTimeEquipmentRunSpec` |
      | FR19 | `InstanceHeartbeat`, `HeartbeatBeater` | `HeartbeatOutageSuppressionSpec` |
      | FR20, FR23 | `TrackerWiring`, `CheckEquipment` | `GithubTrackerAdapterFactorySpec`, `ProviderDispatchingExternalCheckClientSpec` |
      | FR21 | `TestTimeInjectionCheck`, `TakeCommands` | `TimeSourceOwnerBoundarySpec` (FR21 identity feature) |
      | FR22 | `ServeAssembly`, `RunAssembly`, `SlotWiring` | `SlotWiringFactorySpec`, `SlotPolicyProducerBoundarySpec` |
      | NFR-R1, NFR-P1 | `ServeDashboard`, `DashboardWatch` | `ServeDashboardIsolationSpec`, `ServeDashboardReadBoundSpec` |
      | NFR-R2, NFR-R3 | `SupervisedLoop`, `LoopControl` | `SupervisedLoopStopConcurrencySpec` |
      | NFR-O1 | `LoopEvents` | `SupervisedLoopSpec` |
      | NFR-O2, UX1 | `ServeCommand`, `ServeDashboard` | `ServeDashboardLaunchSpec` |
      | UX4 | `DashboardDisabledException` | `ServeDashboardIsolationSpec` (`UX4:` feature) |
      | UX5 | `docs/guides/operator-guide-dashboard.md`, `operator-guide-serve.md` | — (documentation) |

      **Metrics measured (2026-10-10):** M3 — `gnomish-up` starts one factory process,
      `gnomish serve --dashboard` (plus the log follower, not a renderer): daemon only. M4 — 4 of 4
      loops are ticking again within one backoff after an `Error` kills the worker (the respawn
      shape of D2 as amended, measured 2026-10-10 in `StandingReaperResilienceSpec`,
      `WorktreeJanitorLifecycleSpec`, `SandboxLifecycleTickLifecycleSpec`,
      `SnapshotWriterSupervisionSpec`). M6 — one type for "now" (`InstantSource`; no
      `domain…Clock`, no `java.time.Clock` import in production), one bean
      (`ManualRunConfiguration.instantSource`). M7 — 0 real-time sites outside the root
      (`TimeSourceOwnerBoundarySpec`). M8 — one fake (`VirtualClock` in `:test-fixtures`) plus the
      declared `:logtext` copy. M9 — 0 time-wiring `system()` factories (`HostResolver.system()` is
      not time); 0 production signatures carrying the pair as two parameters (single-owner row 8).
      M10 — one `create` per SPI factory (`TrackerAdapterFactory`, `CheckClientFactory`).

## 11. Final gate

- [x] 11.1 Run the root `./gradlew check` once (no `--tests`, no `-PpitScope`) and fix what fails.
      Verify: green, with 100% mutation on the changed classes and the log-contract, log-expectation,
      time-source and test-time-injection gates green (retired codes named by no test, new codes
      asserted, no real clock outside the root).

## 12. Narrow the guard: `Exception` continues, `Error` dies (D2 as amended, D5; FR2, FR3, FR6)

Added 2026-10-10 after the audit: the restart path was reachable only through the guard's own
reporting, and 15 spec files killed the worker with a message-throwing fixture (design D2,
rationale). Each task runs under `verification-scope.md`: the specs named, then
`pitestVerifyAllKilled -PpitScope=com.github.oinsio.gnomish.app.daemon.*`.

- [x] 12.1 In `app/daemon/SupervisedLoop.java` change both guards (`tickGuarded`, the wait in
      `awaitFull`) from `catch (Throwable)` to `catch (Exception)`; an `Error` leaves the guard and
      reaches `onWorkerDeath`. Update the class javadoc and `LoopEvents.failed`'s javadoc (the
      reason is `FailureReason.of`; a failure whose reporting throws still ends the thread, now as
      the rare case rather than the only one). In `SupervisedLoopSpec` turn the two
      `throw new Error(...)` features (tick, sleeper) into `RuntimeException` ones (daemon-supervision
      "An Exception in the work does not end the loop", "A throwing wait does not end the loop") and
      add the feature for "An Error in the work ends the worker and the restart policy takes over":
      no `DAEMON_LOOP_TICK_FAILED` line, one `DAEMON_LOOP_WORKER_DIED` line, the tick runs again
      after the backoff. Verify: `SupervisedLoopSpec`, `SupervisedLoopRestartSpec`,
      `SupervisedLoopBoundedSpec`, `SupervisedLoopDeathLineSpec` green; scoped PIT green.
- [x] 12.2 Replace every message-throwing kill with a plain `Error` (design D5, fixtures): delete
      `Unrenderable` from `app/daemon/SupervisedLoopHarness.groovy`, `app/lease/ReaperLoopRig.groovy`,
      `bootstrap/.../app/ServeDashboardIsolationSpec.groovy` and
      `serveobservability/VitalsSnapshotAssemblerSpec.groovy`, and in the files that throw it —
      `app/DashboardCommandFixture.groovy`, `app/DashboardCommandWatchSpec.groovy`,
      `app/DashboardWatchSupervisionSpec.groovy`, `app/daemon/SupervisedLoopBoundedSpec.groovy`,
      `app/daemon/SupervisedLoopRestartSpec.groovy`, `app/daemon/SupervisedLoopStopConcurrencySpec.groovy`,
      `app/lease/StandingReaperInterruptedStopSpec.groovy`, `app/lease/StandingReaperResilienceSpec.groovy`,
      `app/lease/StandingReaperSupervisionSpec.groovy`,
      `serveobservability/writer/SnapshotWriterFinalWriteRaceSpec.groovy` — throw
      `new Error('<what died>')` instead, keeping each feature's expectations. `Wordless` and
      `UnrenderableTwice` stay in `SupervisedLoopHarness`, used by `SupervisedLoopDeathLineSpec`
      only. Verify: the specs listed green; `ServeDashboardIsolationSpec` green;
      `grep -rn "Unrenderable\b" application/src/test bootstrap/src/test` lists no file but
      `SupervisedLoopHarness` and `SupervisedLoopDeathLineSpec` (the `UnrenderableTwice` fixture).
- [x] 12.3 Rewrite the four loop-level `Error` features to the respawn shape: an `Error` from the
      tick logs `DAEMON_LOOP_WORKER_DIED` and the tick runs again after one backoff on virtual time
      (daemon-supervision "The worktree cleaner is respawned after an Error"; M4) in
      `StandingReaperResilienceSpec`, `WorktreeJanitorLifecycleSpec`,
      `SandboxLifecycleTickLifecycleSpec` and `SnapshotWriterSupervisionSpec`. Where a feature
      meant "the loop survives a recoverable fault", throw a `RuntimeException` and keep the
      continue shape. Verify: the four specs green; the M4 line in task 10.1 updated to the respawn
      wording.
- [x] 12.4 Durable guidance: amend `docs/adr/0013-supervised-daemon-loop.md` — status line
      "amended 2026-10-10 (D2 of the same change, as amended)", D2 section ("The guard catches
      `Exception`; an `Error` ends the worker and the policy decides"), the "catch
      (RuntimeException)" alternative (rejected for the wait outside the guard, not for the catch
      type), the Consequences line on `OutOfMemoryError` (a death with backoff, not a continue), and
      a new rejected alternative "escalate `VirtualMachineError` to a process exit" (deferred).
      Update `.claude/rules/daemon-loops.md` ("the guard (`catch (Exception)` around both; an
      `Error` ends the worker and the restart policy decides)", and the specs paragraph: fixtures
      kill a worker with a plain `Error`) and the glossary entry "Supervised daemon loop" ("guarded
      so an `Exception` ... never ends the loop; an `Error` ends the worker and the restart policy
      decides"). Verify: `grep -n "catch (Throwable)" docs/adr/0013-supervised-daemon-loop.md
      .claude/rules/daemon-loops.md` is empty except the history paragraph; `DaemonLoopOwnerBoundarySpec`
      green.
- [x] 12.5 Final gate again: the root `./gradlew check` once more (11.1 no longer stands as the last
      run). Verify: green, 100% mutation on `app.daemon.*`. Superseded as the last run by 13.9.

## 13. Stand-in binaries are committed presets; PIT scan hygiene (D23–D25, single-owner row 12; FR24, FR25, NFR-P2, M11, M12)

Added 2026-10-10 after measuring where PIT's time goes (design D23: macOS assesses every new
executable file on its first run; every spec wrote a fresh stand-in per test). Each task runs under
`verification-scope.md`: the specs it names by `--tests`, then `pitestVerifyAllKilled -PpitScope=`
over the production classes it touched (most tasks touch none: they change test sources only, so
the PIT step is skipped and the named specs are the check). The `implementation.md` sweep applies:
13.6's report ends with the grep, its hits and what happened to each.

- [x] 13.1 Baseline measurement (D25). Rerun PIT for `:adapters:git` and `:bootstrap` with
      `--verbosity=VERBOSE` (the Gradle plugin has no switch: capture the `MutationCoverageReport`
      command line from `./gradlew :<module>:pitest --rerun --info`, add `--verbosity=VERBOSE`, run
      it under the build's `GIT_CONFIG_GLOBAL`); sample three minion main threads with `jcmd
      <pid> Thread.print` during the mutation phase. Record in the task report: coverage-phase
      seconds, mutation-phase wall, minion-seconds (wall × threads), the sum of per-mutant
      durations ("Running mutation" → result lines), and where the sampled threads stood. The
      2026-10-10 numbers for `:adapters:git` (214 s / 979 s / 8811 / 505–899 s / `ProcessImpl.waitFor`
      on a stand-in script) are the reference; `:bootstrap` has no verbose baseline yet.
- [x] 13.2 The library and its owner. `test-fixtures/src/main/resources/stand-in/`: `git.sh` reading
      `$0.params` (rows `<subcommand> refuse <exit> <stderr-file>` | `answer <stdout-file> <exit>` |
      `stall <seconds>` | `record` | `delegate`; unknown subcommand → `delegate` to the real git on
      `PATH`; a recording appends argv to `$0.log`), `docker.sh` with the same table, and the
      supervisor fakes (`sleeping`, `trapping`, `forking`) parameterised by `$0.params`. `presets/`
      directories for the scenarios 13.3–13.6 need, each a `git` (or other) link to the script plus
      its `.params` and any stdout/stderr files, no absolute path anywhere. `StandIn` in
      `test-fixtures/src/main/groovy/.../stand-in/`: `git(preset)`, `docker(preset)`, `agent(...)`
      return the committed path; `recording(tempDir, preset)` creates the one per-run link and
      returns it; nothing else in the class touches the filesystem. Specs: `StandInLibrarySpec`
      (data-driven over every preset directory: the link resolves, the params parse, refuse/answer/
      stall/record/delegate each behave, exit code and stderr pass through, `$0.log` lands beside a
      per-run link and nowhere else; no preset contains `/Users`, `/home` or `/var/folders`),
      `StandInSpec` (the owner creates links only; `recording` twice in one directory is two links,
      two logs). Verify: the two specs green.
- [x] 13.3 Migrate the `test-fixtures` builders onto the library: `StallingGit` (keeps its API,
      becomes a preset selector over `stall` rows; `StallingGitOwnerSpec`'s `sleep` scan gains the
      library as the one allowed site), `FailingSubcommandGitFixture` (`refuse` rows, the health
      marker becomes a preset variant), `TransferAdversaryFixture`, `BareGitRepoFixture`'s script,
      `FakeAgentSupport` (per-run link to `fake-agent.sh` for recordings). Delete the shebang
      strings. Verify: `StallingGitOwnerSpec`, `StallingGitSpec`, and the specs of each builder
      green; `grep -rn '#!/bin/' test-fixtures/src/main` hits only the library.
- [x] 13.4 Migrate `:adapters:git`: `RecordingGit` becomes `StandIn.recording` over the `record`
      preset; the 25 specs with inline scripts (`BaseRefreshSpec`, `CloneMutationConcurrencySpec`,
      `ContainerHarvestFetchSpec`, `EnvironmentSalvageSpec`, `FactoryCloneHardeningSpec`,
      `FetchRefusalOutcomeSpec`, `FirstPushSpec`, `GitNetworkCommandsSpec`,
      `GitObjectsTaskRepositorySpec`, `GitProcessRunnerBoundedNetworkSpec`, `GitProcessRunnerSpec`,
      `GitVersionCheckSpec`, `OriginRemoteSpec`, `ParkDeliveryFenceSpec`, `RefspecPushSpec`,
      `RemoteAttemptDeliverySpec`, `RemoteDefaultBranchSpec`, `ReplicaPairReconcilerSpec`,
      `TaskBranchListerSpec`, `TaskBranchLocatorSpec`, `TipStateCursorTerminationSpec`,
      `UsageHistoryWalkerEdgeCasesSpec`, `WorktreeSalvageSpec`, `ContainerGitMechanicsSpec` —
      exempt, container-mounted) select presets; a refusal text a spec asserts is read from the
      preset file. Verify: every named spec green; `grep -rn '#!/bin/' adapters/git/src/test` hits
      only the exempt file.
- [x] 13.5 Migrate `:bootstrap` (`AdversarialGitConfigSpec`, `AgentDecisionRoundTripSpec`,
      `BaseRefLawBindingSpec`, `ContainerRunSupportSpec`, `GitModeLawBindingSpec`,
      `GitModeMidRoundPushSpec`, `GitResumeBootstrapRefusalSpec`, `GitVersionFloorSpec`,
      `ManualRunAssemblySpec`, `TakeCommandCredentialScrubSpec`, `e2e/paidsmoke/ArgvRecorder`),
      `:gitobjects` (`GitObjectsTreeSpec`, `GitObjectsListTreeSpec`), `:sandbox:docker`
      (`FakeDockerBinary`, `HostExecHandleTreeKillSpec`), `:subprocess` (`FakeBinaries`),
      `:adapters:agent` (`CliStageExecutorCredentialScrubSpec`). Verify: every named spec green;
      the shebang grep over each module's test tree hits only the exemptions of D23.
- [x] 13.6 The gate and the durable record. `StandInOwnerSpec` in `:bootstrap` (shape of
      `ProcessEnvironmentOwnerSpec`): scans every module's `src/test` and `test-fixtures/src/main`
      for `executable = true`, `setExecutable(`, `PosixFilePermissions.fromString(` followed by an
      exec of that file, and `#!/bin/`; allowed only in `StandIn`, the library resources and the
      exemptions (`ContainerGitMechanicsSpec`, `FakeAgentSandboxImage`, `LauncherScriptSpec`,
      `ReleasePreflightScriptSpec`, `NightlyMutationIssueScriptSpec`), each with its reason;
      asserts the scan reached every listed file. Durable record, already written on 2026-10-10 and
      to be checked against the final code: ADR 0015 (`docs/adr/0015-stand-ins-are-committed-presets.md`),
      `testing.md` sections "Stand-ins are prepared, not generated" and "Diagnosing a slow gate",
      `design-decisions.md` "Alternative zero", glossary entries **Stand-in** and **Preset**, the
      architect skill's and `/audit-codebase`'s new items. Report: the sweep grep
      (`executable = true|setExecutable\(|PosixFilePermissions\.fromString|#!/bin/` over test
      sources), every hit, and its disposition. Verify: `StandInOwnerSpec` green and red when one
      exemption line is removed.
- [x] 13.7 `bootstrap/verification.gradle`: add the nine suites of D24 to `excludedTestClasses` with
      a rationale comment in the file's existing style, naming for each the in-process twin; add a
      comment naming the three `ScriptedSandboxDocker` suites as deliberately kept. Verify:
      `./gradlew :bootstrap:pitest --rerun --info` shows none of the nine among the tests sent to
      the coverage minion; `pitestVerifyAllKilled` for `:bootstrap` green.
- [x] 13.8 After-measurement (D25, M12): repeat 13.1 for both modules; record the same five numbers
      beside the baseline and the mutant counts (unchanged: 933 and 444 on the 2026-10-10 tree, or
      the new tree's own before/after pair). Targets: `:adapters:git:pitest` under 480 s,
      `:bootstrap:pitest` under 1200 s. A miss is reported with the sampled thread states, not
      hidden.
- [x] 13.9 Final gate: the root `./gradlew check` once (no `--tests`, no `-PpitScope`); fix what
      fails. Verify: green; whole-`check` wall time recorded in the report against M12's 45 min.

### 13b. Second review of the library (D26; FR24 as amended, FR26, M11)

Added 2026-10-10 after `/architect` reviewed ADR 0015 against prior art (no off-the-shelf stand-in
library fits; the assessment is keyed by inode, so a link a test creates costs what a committed one
costs). Each task runs under `verification-scope.md`; none touches production Java, so the check is
the named specs plus `StandInOwnerSpec`, `StallingGitOwnerSpec` and `StandInLibrarySpec`.

- [ ] 13.10 Links are derived (D26). `StandIn`: a per-JVM directory made once, lazily, under the
      JVM's temporary directory with deletion on exit; `git(id)`, `docker(id)`, `agent(id)` return
      `<jvm-dir>/<id> -> stand-in.sh`, created on first use; `link(at, id)` and `recording(dir, id)`
      target that link; `preset(id)` validates against `StandInTables` sections, not a file.
      `stand-in.sh`: resolve the chain to its own real path and take the library from there; the
      preset is the name of the last link before the script. Delete `links/`. `StandInLibrarySpec`:
      `presets()` from `StandInTables.sections()`, the "every section has its link" feature replaced
      by "the library holds no link", the direct-link refusal features run through a per-JVM link.
      `stand-in-conventions` unchanged. Verify: `StandInLibrarySpec`, `StandInSpec`, `StandInOwnerSpec`
      green; `find test-fixtures/src/main/resources/stand-in -type l` empty.
- [ ] 13.11 Each behaviour once, presets (FR26). `harvest-daemon-down|fsck-refused|not-a-repository|
      rejected` → one `harvest-refuse` taking the stderr section from its link's name
      (`@name` in the file column: `* refuse @code data/stderr#@name`, or the narrowest grammar
      addition that expresses it — record which in the task report); `agent-judged-by-*` ×4 → one
      `agent-judged` with one judge model name for every spec and argv capture always on
      (`FakeAgentSupport.judgeModel` callers migrate to the one name). Verify: `ContainerHarvestFetchSpec`
      and every spec `FakeAgentSupport` lists for the judged binary green; preset count recorded.
- [ ] 13.12 Each behaviour once, processes (FR26). `quick`, `local`, `stall` become rows of a
      `process.params` table (`StandIn.process(name)` resolves a table preset first, a script
      second, and the library spec pins that order); `process/noisy.sh` and `steps/noisy.sh` become
      one parameterised script. Verify: `ProcessSupervisorStallSpec`, `CaptureRunnerDrainSpec`,
      `DockerCliBoundedSpec` green; `ls process/` is nine scripts.
- [ ] 13.13 Dead rows (FR26). `StandInLibrarySpec`: each feature that runs a preset records the rows
      the run reached (the script marks them in a per-run `<link>.reached`, or the spec derives the
      set from the log and the answer); a final feature asserts that every row of every section was
      reached by some feature and names the misses. Verify: the feature red with one unreachable row
      added to a table, green after its removal.
- [ ] 13.14 Durable record. ADR 0015 (decision 2: a preset is a section; the link is made by the
      owner; the `GIT_CONFIG_PARAMETERS`/`core.hooksPath` note for hook presets), `testing.md`
      "Stand-ins are prepared, not generated" (drop `links/<preset>`), `design-decisions.md`
      "Alternative zero" (one sentence: the committed links were the registry that alternative zero
      removes on the second pass), glossary **Preset** and **Stand-in**. Verify: `grep -rn 'links/'
      docs .claude/rules` empty.

## 14. Specs above the git adapter fake its port (D27, single-owner row 13; FR27, M13)

Added 2026-10-10, second review. **Runs before `own-git-invocation-policy` and
`add-subprocess-access-log`**: both rewrite `GitProcessRunner.execute`; this group changes the
class's `implements` line and its holders' types only, so the later change rebases one line. Their
task lists get a one-line note pointing here. Each task runs under `verification-scope.md`: the
named specs, then `pitestVerifyAllKilled -PpitScope=` over the production classes it retyped (a
retype adds no mutant; the step is the proof that none was lost). `implementation.md` applies: 14.1
ends with the old-way sweep.

- [ ] 14.1 The port. `GitRunner` (package-private interface, `adapters/git`): `run(Path, String...)`,
      `run(Path, GitTransfer)`, javadoc naming `GitProcessRunner` as the one production
      implementation and `ScriptedGit` as the one fake; `GitProcessRunner implements GitRunner`,
      nothing else in it moves. Retype the 60 holders in `adapters/git` (`grep -rl 'private final
      GitProcessRunner' adapters/git/src/main`), the site in `:application` and the five in
      `:bootstrap`; constructors and factories take `GitRunner`; the composition root and the
      adapter's own specs keep constructing the class. Sweep: `GitProcessRunner` as a field or
      parameter type outside `GitProcessRunner.java` and `bootstrap/src/main` — expected empty, each
      survivor named with its disposition. Verify: `./gradlew :adapters:git:compileJava
      :application:compileJava :bootstrap:compileJava`; the specs of the retyped classes green.
- [ ] 14.2 The gate. `GitRunnerBoundarySpec` (`:bootstrap`, shape of `ClaimlessGitBoundarySpec`):
      scans `adapters/git/src/main`, `application/src/main` and `bootstrap/src/main`, comments
      stripped, for `GitProcessRunner` as a field or parameter type; allowed only in
      `GitProcessRunner.java` and the composition root files it lists; asserts the scan reached
      every listed file. Verify: green; red when one holder is retyped back.
- [ ] 14.3 The fake. `ScriptedGit` in `adapters/git/src/test` (same package as `GitCommandResult`):
      rows `prefix → GitCommandResult` (first match wins, the argv prefix matched after leading `-c`
      pairs exactly as `stand-in.sh` does), an unmatched call throws naming the argv, a call log
      every spec can assert (including "never called"), and `stalls(prefix, CountDownLatch)` that
      blocks the caller until released and answers `Termination.INTERRUPTED` on interrupt. Spec:
      `ScriptedGitSpec` (each row kind; the unmatched throw; the latch released from another thread,
      per `lock-scope.md`'s concurrency-spec shape). Verify: green.
- [ ] 14.4 Migrate the above-adapter specs onto `ScriptedGit`, one spec per sub-task in the report:
      `FirstPushSpec` (`first-push-absent`, `first-push-landed`, `missing-branch`),
      `TaskBranchLocatorSpec` (`locator-fetch-refused`, `locator-lying-fetch`, `locator-stall-network`),
      `ReplicaPairReconcilerSpec` (`reconcile-swap-loses`, `reconcile-swap-loses-silently`,
      `reconcile-diverged-fails`), `ReplicaPairReconcilerTerminationSpec` (`reconcile-stall`),
      `ContainerHarvestFetchSpec` (`harvest-refuse`, `stall-fetch`, `record-argv`),
      `UsageHistoryWalkerTerminationSpec` (`walker-stall`), `OriginRemoteSpec` (`blank-url`),
      `ParkDeliveryFenceTerminationSpec` and the `StallingGitFixture` users (`stall-push`,
      `stall-push-lands`, `stall-push-origin-gone`), `RefspecPushSpec`, `RemoteAttemptDeliverySpec`,
      `TipStateCursorTerminationSpec` (`closed-stdout-stall` — stays a stand-in if its subject is
      the capped read of a real pipe; decide and record). Delete each preset whose last consumer
      left; `StallingGit` and `StallingGitFixture` shrink to what the runner's own specs use or go.
      Verify: each migrated spec green; `pitestVerifyAllKilled -PpitScope=<the spec's subject
      class>` green per class.
- [ ] 14.5 What stays, named. In the design's D27 list and in ADR 0015, record the presets that
      remain and why each is a process matter (the four `ProcessBuilder` owners' specs, the mixed
      presets, `GitVersionCheck`, the end-to-end layer); update `manual-sync-pairs.md` only if
      `ScriptedGit` and `stand-in.sh` are declared a pair for the prefix-match rule (decide: the
      rule is one sentence each side; declare it). Verify: `grep -rn "Kept in sync with"` lists both
      ends or the design records why not.
- [ ] 14.6 Measure (D25, M12, M13): rerun 13.8's five numbers for `:adapters:git` after 14.4; record
      the preset count and the above-adapter stand-in count (expected 0) beside M13.
- [ ] 14.7 Final gate: the root `./gradlew check` once; fix what fails. Verify: green; wall time
      recorded against M12.
