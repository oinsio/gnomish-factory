# Tasks

Verification follows `.claude/rules/verification-scope.md`. Per task, run Spotless, compile the
touched modules, run the named specs, and run `pitestVerifyAllKilled -PpitScope=<the task's classes>`.
The root `./gradlew check` runs once, in group 10. Each sub-agent's report ends with the old-way
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
      the log capture.
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

## 2. Standing reaper on the supervised loop (D5, D6, D7; FR6)

- [ ] 2.1 Rewrite `app/lease/StandingReaper.java` to hold a `SupervisedLoop` (wait → tick,
      `FixedInterval(interval)`, `Unbounded(interval, 10 min)`, component `REAPER`). Delete
      `loop()`, `onWorkerDeath`, `spawnWorker`, its `RestartBackoff` field and its own
      lock/stopping/worker. `restartCount()` delegates to the loop. Retire GF067, GF068 and GF069 from
      the catalog. Verify: the reaper's lifecycle and resilience specs are rewritten to assert the
      component codes plus `component=reaper`, and `VitalsSnapshotAssembler`'s
      spec still shows a grown `restartCount` after a respawn (daemon-supervision "Reaper restarts stay
      visible"). Old-way sweep: `grep -n "Thread.ofVirtual\|uncaughtExceptionHandler\|RestartBackoff"
      application/src/main/java/com/github/oinsio/gnomish/app/lease/StandingReaper.java` is empty.
- [ ] 2.2 Create a "Retired codes" subsection in `docs/guides/operator-guide-observability.md`,
      right after the paragraph on `[GFnnn]` codes (the one ending "the practical way to find the
      ones a given incident produced"). No such table exists today: the guide names the
      `OperatorEvent` enum as the full list. Columns: retired code, replacement code, `component`
      filter (D6). Add the GF067, GF068 and GF069 rows. Verify: `grep -rn "GF06[789]" docs` finds them
      only in that subsection.

## 3. Worktree janitor and sandbox sweep tick on the supervised loop (D7, D9, D14; FR6)

- [ ] 3.1 Rewrite `app/serve/WorktreeJanitor.java` to hold a `SupervisedLoop` (tick → wait,
      `FixedInterval(1 h)`, `Unbounded(1 h, 10 min)`, component `JANITOR`) and add `stop()`. Keep
      `tick()` and `lastRunAt`. Retire GF077. The tick's own codes (GF078, GF079) stay. Remove the
      `Kept in sync with SandboxLifecycleTick` paragraph. Verify: the janitor policy spec is unchanged
      and green. Its lifecycle spec asserts survival of an `Error` (daemon-supervision "The worktree
      cleaner survives an Error") and that `stop()` ends it.
- [ ] 3.2 The same for `app/serve/SandboxLifecycleTick.java` (component `SWEEP`, its configured
      interval, retiring GF073), removing its `Kept in sync with WorktreeJanitor` paragraph. Verify:
      its specs are green, and `grep -rn "Kept in sync with" application/src/main/java/com/github/oinsio/gnomish/app/serve/WorktreeJanitor.java
      application/src/main/java/com/github/oinsio/gnomish/app/serve/SandboxLifecycleTick.java` is
      empty (the pair is dissolved, D14).
- [ ] 3.3 Give `app/serve/ServeShutdown.java` a `DaemonLoops` component (reaper, janitor, sweep
      tick) in place of the bare `StandingReaper`, stopped where the reaper is stopped today, before
      the grace wait (D9). Wire it in `ServeRuntimeAssembly`/`ServeAssembly`. Verify: a
      `ServeShutdown` spec asserts all three stop before `awaitDrained` and that a second `shutdown`
      is a no-op. The factory-serve scenario "No cleaner run during drain" passes in
      `ServeShutdownWiringSpec` (or its successor).
- [ ] 3.4 Add the GF073 and GF077 rows to the "Retired codes" subsection created in 2.2, and
      in `docs/guides/operator-guide-serve.md` state that `serve` shutdown stops the janitor and the
      sweep tick. Verify: grep as in 2.2.

## 4. Snapshot writer on the supervised loop (D3, D7, D8; FR6, FR7)

- [ ] 4.1 Rewrite `serveobservability/writer/SnapshotWriter.java` to hold a `SupervisedLoop` (tick
      → wait, `IntervalOrSignal(interval)`, `Unbounded(interval, 10 min)`, component `SNAPSHOT`).
      `markDirty()` becomes `wait.signal()`. `stopAfterFinalWrite()` becomes `loop.stopAndJoin()` then
      `writeCycle.writeOnce()`. Delete `awaitNextWake`, its own semaphore and the `log-contract-exempt`
      outer guard. Retire GF105. Verify: the writer's existing content and coalescing specs are
      green, and the spec "Interrupted writer does not spin" asserts one write per timer period after
      a stray interrupt. Old-way sweep: `grep -n "log-contract-exempt\|Semaphore\|Thread.ofVirtual"
      SnapshotWriter.java` is empty.
- [ ] 4.2 Identity spec `SnapshotWriterFinalWriteRaceSpec` (real threads, M5): the writer's tick is
      made to throw an `Error` on a latch so its thread dies, and `stopAfterFinalWrite()` is called
      while the respawn backoff is latched. Assert that the file's last content is the `stopped`
      record and that the counting writer saw no write after it. Also: a single death is followed
      by a write within two intervals on virtual time (serve-observability "Writer death is not a dead
      daemon"). Verify: 20 consecutive green runs.
- [ ] 4.3 Add the GF105 row to the "Retired codes" subsection created in 2.2. Verify: grep as in 2.2.

## 5. Enforcement and durable guidance (D14, D15, single-owner rows 1–2; FR16, M1, M2)

- [ ] 5.1 Write `DaemonLoopOwnerBoundarySpec` in `:bootstrap` (`ClaimlessGitBoundarySpec` shape). It
      scans `application/src/main` for `Thread.ofVirtual(`, `Thread.ofPlatform(` and `new Thread(`
      and allows only `app/daemon/SupervisedLoop.java`, `app/lease/HeldClaims.java`,
      `app/serve/FeedCycle.java`, `app/TakeBatch.java` and `app/ServeShutdownWiring.java`, each with
      its reason in the allowlist. It also fails on `Executors.newScheduled`,
      `Executors.newSingleThreadScheduled`, `ScheduledExecutorService` and `new Timer(` anywhere in
      `application/src/main` (none exist today), the silent-death shape D1 rejects. It allows `new RestartBackoff(` only in `app/daemon/RestartPolicy.java`
      (or the file holding the policies) and `app/serve/RemoteOutageProbeSchedule.java`, and asserts
      that the scan reached every allowlisted file. Verify: the spec is green, and a scratch
      `Thread.ofVirtual()` added to `WorktreeJanitor` makes it fail naming the file, and so does a
      scratch `Executors.newSingleThreadScheduledExecutor()` (record both red runs, then revert).
- [ ] 5.2 Write `docs/adr/0013-supervised-daemon-loop.md`: context (four loops, the silent-death
      history, `fix-reaper-idle-liveness` D4/D5), the decision (D1–D3, D5, D6), alternatives
      (`ScheduledExecutorService`, base class, per-loop codes), and the restart-policy choice (Unbounded
      / Bounded / unsupervised-by-design, the heartbeat). Confirm 0013 is still free (`ls docs/adr`,
      and `grep -rn "0013" openspec/changes`), else take the next number and update D15. Verify: the
      file exists and is linked from `SupervisedLoop`'s javadoc.
- [ ] 5.3 Write `.claude/rules/daemon-loops.md` in the shape of `lock-scope.md`. It covers what
      counts as a daemon loop, when to use `SupervisedLoop` and when not to (the five exemptions with
      reasons), how to pick the order, the wait and the restart policy, adding a `DaemonComponent`
      constant, the gate (`DaemonLoopOwnerBoundarySpec`, its scope `application/src/main` and its
      banned patterns, the scheduled executors and `Timer` included) and the review/audit obligation. Add its
      row to the "Process Rules" table in `CLAUDE.md`. Verify: the row exists, and the rule names
      the spec, the ADR and the glossary term.
- [ ] 5.4 Measure M1: `grep -rln "Thread.ofVirtual().name(\"gnomish-" application/src/main` lists only
      `SupervisedLoop.java` (or nothing, if the name is built there) and allowlisted files. Verify:
      the grep output is in the task report.

## 6. WIP stat from the board (D13, single-owner row 5; FR12–FR14, UX3)

- [ ] 6.1 Give `board/BoardModel.java` an `int wipLimit` (set in `build` from
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
- [ ] 6.2 Pass the `BoardSectionView` to `dashboard/DashboardStatusCardRenderer.java` (from
      `DashboardHtmlRenderer.java:92`). Split `appendStats` so the slots and failures stats need a
      snapshot and the WIP stat needs a board model: value `n / W`, title `n of W open fronts: a
      working, b waiting for a human`, never `bad`. Verify: a status-card spec covers the
      dashboard-page scenarios "WIP stat with its split", "Full WIP is not an alarm", "No daemon
      yet" and "Board never loaded", plus a cached model after a failed refresh.
- [ ] 6.3 Identity spec (single-owner row 5): build one `BoardModel` through `BoardComposition.compose`
      with limit 3, three open fronts and a WIP-held ready row. Assert that the JSON `wipLimit`, the
      rendered WIP denominator and the limit the held row was judged against are all 3 (dashboard-page
      "Limit matches the held rows"). Verify: the spec passes.
- [ ] 6.4 Document the WIP stat in `docs/guides/operator-guide-dashboard.md` (the reference for
      `gnomish dashboard`, in its status-card description): what counts as an open front, that the limit is the project's `wip-limit`, and that it
      is a reference number and not an alarm. Verify: the section exists.

## 7. One dashboard assembly (D10, single-owner row 3; FR9, FR14)

- [ ] 7.1 Extract `app/DashboardWatch.java` taking `(ProjectLayout, String instanceName, @Nullable
      Path outOverride, BoardSource)`, with `BoardSource(Tracker, TrackerConfig,
      FactoryProperties.Tracker)` as a record. It owns `DEFAULT_FILE_NAME`, `BOARD_READY_LIMIT`, the
      `BoardComposition.compose` call, the render cycle, the board cache and a `SupervisedLoop`
      (`FixedInterval(10 s)`, `Bounded(10 s, 10 min, 5, 10 min)`, component `DASHBOARD`). Methods:
      `outputFile()`, `renderOnce()`, `start()`, `awaitEnd()` (true if given up), and
      `stopAndRenderFinal()`. Move `DashboardWatchLoop.renderOnce` into the tick and delete
      `DashboardWatchLoop.run`. Verify: the existing watch-loop specs, retargeted to the tick, are green,
      and the dashboard-page scenario "Tracker outage is not a death" holds on virtual time.
- [ ] 7.2 Route `app/DashboardCommand.java` through `DashboardWatch`: one-shot = `renderOnce`;
      `--watch` = `start()` then `awaitEnd()`, exiting 1 if the loop gave up (dashboard-page
      "Standalone renderer gives up"). It still builds its source from `TrackerWiring.resolveReadOnly`.
      Verify: `DashboardCommand` specs green. Old-way sweep: `grep -rn "\"dashboard.html\"\|BoardComposition.compose("
      application/src/main` hits `"dashboard.html"` only in `DashboardWatch.java` and
      `BoardComposition.compose(` only in `DashboardWatch.java` and `BoardCommand.java`.
- [ ] 7.3 Extend `DaemonLoopOwnerBoundarySpec` (5.1) with the row-3 checks, one allowlist per
      marker, each file asserted reached: the `"dashboard.html"` literal only in `DashboardWatch.java`;
      `BoardComposition.compose(` only in `DashboardWatch.java` and `BoardCommand.java`. Verify: the spec is green, and a scratch violation is red (record the run).

## 8. `serve --dashboard` (D9, D11, D12, single-owner row 4; FR8–FR11, FR15, NFR-R1, NFR-S1, NFR-P1, NFR-O2, UX1, UX2, UX5)

- [ ] 8.1 Add `dashboard` and `dashboardOut` to `app/ServeArguments.java` and
      `ServeArgumentsParser`, and `@ConfigLevel(Level.ANY) @Nullable Boolean dashboard` to
      `ServeProperties` (default `false`). `--dashboard-out` with the effective switch off throws
      `UsageException`. Verify: the parser spec covers both flags, `--dashboard-out` resolved like
      `dashboard --out`, and the factory-serve scenario "Output path without the dashboard". The
      `ConfigLevelCoverageSpec` and the unknown-option spec stay green.
- [ ] 8.2 Add `Tracker boardReader(BoundTracker bound, InstanceId readerId)` to
      `app/TrackerWiring.java` (D11): `bound.factory().create(secrets, bound.trackerConfig(),
      readerId.value())`, no `Path dir`, not health-wrapped, not epoch-stamped. Expose it through a new
      role interface `BoardReaders` that `TrackerWiring` implements, as it implements `RefResolution`.
      Verify: a `TrackerWiring` spec asserts the reader is built from the bound configuration (a
      checkout config is never read) under the given reader id, and `TrackerWiringOwnerBoundarySpec`
      stays green unchanged (no new `SecretsProvider` holder).
- [ ] 8.3 In the serve runtime assembly (`app/ServeRuntimeAssembly.java` / `ServeAssembly.java`),
      when the switch is on, build `BoardSource` from `BoardReaders.boardReader(bound,
      scope.mintInstanceId())` (a minted reader id, as `resolveReadOnly` mints one) with
      `bound.trackerConfig()`, and a `DashboardWatch` for the serve's layout and instance name. The
      assembly receives `BoardReaders`, never a `SecretsProvider`. In `ServeCommand`, start it after `observability().start()`,
      print `gnomish serve: dashboard -> <path>` on the human console, and add `dashboard` and
      `dashboardOut` to `AnchorLog.ServeConfig`. Verify: a serve-level spec asserts the printed line
      and the anchor fields (UX1, NFR-O2). Old-way sweep: `grep -rn "resolveReadOnly(" application/src/main`
      hits only `TrackerWiring.java`, `DashboardCommand.java` and `BoardCommand.java`.
- [ ] 8.4 Extend `DaemonLoopOwnerBoundarySpec` with row 4, as its own marker allowlist:
      `resolveReadOnly(` appears only in `TrackerWiring.java` (the declaration),
      `DashboardCommand.java` and `BoardCommand.java`, each asserted reached. Verify: green, and a
      scratch call from `ServeRuntimeAssembly` is red (record the run).
- [ ] 8.5 In `app/ServeShutdownWiring.java`, call `dashboard.stopAndRenderFinal()` after
      `observability.finalizeStopped(...)` on both `runDrain` and `runForever` paths when enabled
      (D9). Verify: a spec drives `serve --dashboard --drain` with the in-memory tracker and fake
      agent through the real composition root (`ServeRuntimeWiringSpec` style) and asserts that the
      page's last render shows the stopped state (factory-serve "Page after Ctrl-C", UX2), and that a
      second shutdown pass changes nothing.
- [ ] 8.6 Isolation specs: (a) board reads failing for the whole run leave the snapshot's tracker
      `consecutiveFailures` at 0 while slots complete ("Board outage is not a daemon tracker
      failure"). (b) A dashboard loop given up by injected tick `Error`s leaves the daemon claiming
      and completing tasks ("Disabled dashboard, working daemon"). (c) At most one `listReady` and one
      `listOpen` per board interval from the dashboard client over virtual time ("Tracker reads
      bounded"). Verify: all three green.
- [ ] 8.7 Identity spec (single-owner row 4): a `serve --dashboard` run over a bare origin whose
      default branch sets `wip-limit: 10` while the clone's checkout sets `wip-limit: 3`. The rendered
      WIP denominator is 10 ("Checkout differs from origin"). Verify: the spec passes on the real
      git medium (`BareGitRepoFixture`).
- [ ] 8.8 Switch `.gnomish/factory/gnomish-up` to `serve --dashboard --dashboard-out="$dashboard_out"`.
      Drop the background renderer, its PID, its log and its cleanup, and keep `--no-open`, `--no-logs`,
      the first-render wait (now polling the file while `serve` runs in the background until the page
      exists, then foregrounding it, or an equivalent that keeps one daemon process) and the log
      follower. Update `.gnomish/README.md`. Verify: `bash -n` and `shellcheck` are clean, and a
      manual run is recorded in the task report: one `gnomish` JVM in `ps`, the page opens, `Ctrl-C`
      leaves a "stopped" page (M3).
- [ ] 8.9 Document in `docs/guides/operator-guide-serve.md`: `--dashboard`, `--dashboard-out` and
      `factory.serve.dashboard`, the printed line, the final render, the bounded give-up, and that a
      `serve --dashboard` and a standalone `dashboard --watch` writing one file overwrite each other
      (UX5), with a pointer to `operator-guide-dashboard.md` for the page itself. In
      `operator-guide-dashboard.md` (its `--watch` wall-display recipe), note that the standalone
      `--watch` exits 1 when its loop is disabled, and mention `serve --dashboard` as the
      one-process alternative. Verify: both sections exist.

## 9. Traceability check

- [ ] 9.1 For every FR, NFR and UX in proposal.md, grep the specs and code comments for `FRn of
      supervise-daemon-loops-and-embed-dashboard` (`traceability.md`). Verify: each ID has at least
      one implementing spec or class, and the list is in the task report.

## 10. Final gate

- [ ] 10.1 Run the root `./gradlew check` once (no `--tests`, no `-PpitScope`) and fix what fails.
      Verify: green, with 100% mutation on the changed classes and the log-contract and
      log-expectation gates green (retired codes named by no test, new codes asserted).
