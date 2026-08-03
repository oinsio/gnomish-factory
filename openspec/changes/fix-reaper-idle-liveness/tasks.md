## 1. StandingReaper — the standing reaper unit (TDD)

- [x] 1.1 Write `StandingReaperSpec` (red): drive one tick with an empty
  live-claims snapshot and a foreign `Working` claim unchanged past TTL — assert
  `removeStaleClaim` is invoked and the task returns to `Ready` (FR1, FR2;
  claim-heartbeat "Reaping continues while the instance holds no claim").
- [x] 1.2 Add resilience specs (red): an `Error` on one tick and a throwing
  sleeper each get logged and the next tick still reaps; `stop()` exits the loop
  cleanly; a truly dead thread is respawned unless `stopping` is set (FR3, FR4;
  "The reaper thread survives abnormal faults").
- [x] 1.3 Implement `StandingReaper` in `app.lease` (green): own virtual thread;
  `loop()` catching `Throwable` around both the reap tick and the interval wait;
  `volatile stopping` flag; `stop()` sets it and interrupts;
  `uncaughtExceptionHandler` respawns unless stopping. Depends on
  `ReaperDuty`/`Reaper` + a `Supplier<Collection<TaskRef>>` live snapshot +
  interval sleeper (design D1, D4).
- [x] 1.3a Implement the supervised-restart policy (design D5): exponential
  backoff (start at the beat interval, double per consecutive failure, cap
  **10 min**, reset once a respawned reaper completes one full tick without
  dying); unbounded restarts (never give up, never kill the daemon); ERROR log
  per respawn whose line carries a monotonic restart count — logs are the only
  exposure surface (NFR-O1, UX2). Spec the backoff progression, the reset, and
  the counter with a virtual sleeper/clock.
- [x] 1.4 Package-private tick/loop seams so specs drive it synchronously
  (mirror the existing `InstanceHeartbeat`/`WorktreeJanitor` test seams); keep
  the file within the size limit.

## 2. InstanceHeartbeat — beat-only, with a liveness snapshot

- [x] 2.1 Write specs (red) in `InstanceHeartbeatLifecycleSpec`:
  `liveClaimsSnapshot()` returns held while `running`, and empty after the
  worker dies (`running == false`) (FR2, design D3).
- [x] 2.2 Add `liveClaimsSnapshot()` = `running ? copyOf(held) : emptySet()`
  (green); leave `held`/`register`/`unregister`/`onWorkerDeath` semantics
  otherwise unchanged.
- [x] 2.3 Remove the reaper from the beat tick: drop the `ReaperDuty` field and
  the `reaper.reapOnce(...)` call in `tick()`; update the class Javadoc to
  "beats own claims only" (FR5, supersedes claim-heartbeat D4). Adjust the
  deterministic beat specs that asserted the per-tick reaper call.

## 3. Unified assembly

- [x] 3.1 Update `TakeHeartbeat.forRun` to build a beat-only `InstanceHeartbeat`
  plus a `StandingReaper(reaper, sleeper, interval, heartbeat::liveClaimsSnapshot)`
  and return the standing reaper alongside the existing views (FR5, design D2;
  NFR-S1: the reaper ticks on the beat interval, and interval/TTL still come
  only from the factory's clone of `.gnomish/config.yaml`).
- [x] 3.2 Update the `TakeHeartbeat` record/shape and its callers; keep the
  `MonotonicTime`-injecting overload for controlled-clock tests.

## 4. take wiring

- [x] 4.1 In `TakeCommand`, start the standing reaper at the run start and
  `stop()` it in a `finally` around dispatch (FR1, FR5). Core claim→work→
  terminal flow untouched.
- [x] 4.2 Wiring spec: a `take` run starts and stops the standing reaper and
  passes the beat-only heartbeat (no reaper on its tick) — covers tracker-take
  "Take runs the heartbeat thread and the reaper duty" as modified, including
  the "Reaping outlives the beat thread" scenario.

## 5. serve wiring

- [x] 5.1 In `ServeCommand`, start the standing reaper beside `WorktreeJanitor`
  (daemon lifetime) using `heartbeat::liveClaimsSnapshot` (FR1, FR5).
- [x] 5.2 Add `standingReaper.stop()` to the `ServeShutdown` sequence so SIGTERM
  and drain stop it cleanly without racing the supervisor (FR4).
- [x] 5.3 Extend `ServeCommandSpec`: serve builds, starts, and registers `stop()`
  for the standing reaper; the heartbeat carries no reaper duty.
- [x] 5.4 Cover `factory-serve` FR13 "Reaping while idle with no claims of its
  own": a serve instance in Idle-empty/just-restarted reaps a foreign stale
  claim without holding any claim first.
- [x] 5.5 Cover the self-reap re-claim edge (FR2; factory-serve "Self-reaped
  task is not re-claimed while its old slot lives"; design D6). Originally
  BLOCKED: the scenario was specced as "a same-instance re-claim is equivalent
  to a foreign re-claim", which current code cannot honor — (1) `ClaimGuard`/
  `RevocationCheckingAttemptPersistence` compare against the process-wide
  `InstanceId`, so a same-instance re-claim never trips the "claim gone" fence
  the way a foreign re-claim does; (2) `SlotLedger.assign` throws
  `IllegalStateException` on a ref already occupied by a still-running old
  slot, uncaught on the `FeedAutomaton`/`ServeCommand` path — it would crash
  the feed thread. Resolved by design D6: the requirement is REPLACED, not
  implemented — the feed skips any claim candidate still in
  `SlotLedger.occupiedRefs()` (WARN-logged), so the unfenceable same-instance
  re-claim cannot arise; the old slot dies at its next round-boundary
  revocation, foreign instances are never blocked, and a claim epoch was
  rejected as the alternative (see design.md D6). Implemented in
  `FeedCycle.attemptClaim`; specced in `FeedCycleSpec` (skip + WARN, walk
  continues to the next candidate, only-candidate abandons the permit,
  ref claimable again after release).

## 6. Repoint existing reaper specs

- [x] 6.1 Move `RestartCleanlinessSpec` from the manually-driven `heartbeat.tick()`
  to the `StandingReaper`; prove the previous life's claims are reaped with the
  new instance holding nothing (FR1, FR2).
- [x] 6.2 Move `ReapingWhileSaturatedSpec` to the `StandingReaper`: snapshot =
  both own claims (busy) still reaps the foreign one; add the empty-snapshot
  case.
- [x] 6.3 Repoint any `ReaperSpec` assertions that assumed the reaper rode the
  beat tick to the standing reaper (behavior of `Reaper`/`StalenessMemory`
  itself is unchanged).
- [x] 6.4 Cover claim-heartbeat "A dead heartbeat stops shielding its
  instance's claims" (FR2): the heartbeat worker dies abnormally → the
  snapshot goes empty → the instance's own standing reaper reaps its
  now-unbeaten claims after TTL.

## 7. Integration proof (NFR-R1, M1)

- [x] 7.1 Integration spec over `InMemoryTracker`: a restarted single-instance
  `serve` with an empty ready queue returns two prior-life stale claims to
  circulation via the standing reaper, no manual intervention (factory-serve
  FR12 "Restart against an empty queue still recovers"; claim-heartbeat "A
  restarted daemon returns its previous life's claims with nothing to claim";
  UX1).

## 8. Operator guide

- [x] 8.1 Update `docs/operator-guide.md` stuck-`Working` recovery: automatic
  reaping happens whenever any factory instance is running, claim in hand or
  not (bounded only by runs too short to observe a full TTL); keep the
  explicit-takeover and cron escape-hatch distinctions intact (tracker-take
  "Operator guide" as modified; UX1).

## 9. Quality gates

- [x] 9.1 PIT mutation on `StandingReaper`, the changed `InstanceHeartbeat`
  paths, and the new `RestartBackoff` at 100% (≥95% only with written
  justification per the testing rule), covering the `Throwable` catch, the
  `stopping` branch, the respawn branch, the empty-vs-non-empty snapshot, and
  `RestartBackoff`'s doubling/cap/reset logic (M2, M3). `./gradlew pitest
  -PpitScope=com.github.oinsio.gnomish.app.lease.StandingReaper,com.github.oinsio.gnomish.app.lease.InstanceHeartbeat,com.github.oinsio.gnomish.app.lease.RestartBackoff`
  — 30/31 mutations KILLED, 97% score; no test changes needed, the existing
  `StandingReaperSpec`/`StandingReaperResilienceSpec`/
  `StandingReaperSupervisionSpec`/`InstanceHeartbeatLifecycleSpec`/
  `BeatFailureTaxonomySpec` suite already covers the `Throwable` catch, the
  `stopping` branch, the respawn branch, the empty-vs-non-empty snapshot, and
  `RestartBackoff`'s doubling/cap/reset logic. The 1 survivor (a
  `ConditionalsBoundaryMutator` on `RestartBackoff.nextBackoff`'s `>= MAX_BACKOFF`
  check, reproduced across two separate runs) is an equivalent mutant: the
  method always returns the same `MAX_BACKOFF` constant once triggered, and
  doubling is monotonic, so weakening `>=` to `>` only delays the trigger by one
  more iteration without ever changing the returned `Duration` — justified
  in-code at the `if` per the testing rule's written-justification exception.
  The D6 skip-occupied guard added by task 5.5: `./gradlew pitest --rerun
  -PpitScope=com.github.oinsio.gnomish.app.serve.FeedCycle` — 16/16 mutations
  KILLED, 100% (`FeedCycleSpec` covers the skip branch, the WARN line, the
  walk continuation, and the release-then-reclaim path).
- [x] 9.2 Pass Spotless, Error Prone + NullAway, and the unused-code checks; no
  new dependency-analysis violations.

## 10. Wrap-up

- [x] 10.1 `grep` traceability: every FR1–FR5, NFR-R1/R2, NFR-O1, NFR-S1, and
  UX1–UX2 of `fix-reaper-idle-liveness` has an implementing entity in code,
  tests, or docs, and the modified `claim-heartbeat` / `factory-serve` /
  `tracker-take` requirements each have a covering spec. Closed one tagging
  gap: `ServeRestartIntegrationSpec` implements the NFR-R1 integration proof
  (task 7.1) but its class doc omitted the NFR-R1 tag — added.
- [x] 10.2 Recommend a commit message (Conventional Commits subject + trailer
  referencing the change / FR IDs); do NOT commit.
