## 1. StandingReaper — the standing reaper unit (TDD)

- [ ] 1.1 Write `StandingReaperSpec` (red): drive one tick with an empty
  live-claims snapshot and a foreign `Working` claim unchanged past TTL — assert
  `removeStaleClaim` is invoked and the task returns to `Ready` (FR1, FR2;
  claim-heartbeat "Reaping continues while the instance holds no claim").
- [ ] 1.2 Add resilience specs (red): an `Error` on one tick and a throwing
  sleeper each get logged and the next tick still reaps; `stop()` exits the loop
  cleanly; a truly dead thread is respawned unless `stopping` is set (FR3, FR4;
  "The reaper thread survives abnormal faults").
- [ ] 1.3 Implement `StandingReaper` in `app.lease` (green): own virtual thread;
  `loop()` catching `Throwable` around both the reap tick and the interval wait;
  `volatile stopping` flag; `stop()` sets it and interrupts;
  `uncaughtExceptionHandler` respawns unless stopping. Depends on
  `ReaperDuty`/`Reaper` + a `Supplier<Collection<TaskRef>>` live snapshot +
  interval sleeper (design D1, D4).
- [ ] 1.3a Implement the supervised-restart policy (design D5): exponential
  backoff (start at the beat interval, double per consecutive failure, cap
  **10 min**, reset once a respawned reaper completes one full tick without
  dying); unbounded restarts (never give up, never kill the daemon); ERROR log
  per respawn whose line carries a monotonic restart count — logs are the only
  exposure surface (NFR-O1, UX2). Spec the backoff progression, the reset, and
  the counter with a virtual sleeper/clock.
- [ ] 1.4 Package-private tick/loop seams so specs drive it synchronously
  (mirror the existing `InstanceHeartbeat`/`WorktreeJanitor` test seams); keep
  the file within the size limit.

## 2. InstanceHeartbeat — beat-only, with a liveness snapshot

- [ ] 2.1 Write specs (red) in `InstanceHeartbeatLifecycleSpec`:
  `liveClaimsSnapshot()` returns held while `running`, and empty after the
  worker dies (`running == false`) (FR2, design D3).
- [ ] 2.2 Add `liveClaimsSnapshot()` = `running ? copyOf(held) : emptySet()`
  (green); leave `held`/`register`/`unregister`/`onWorkerDeath` semantics
  otherwise unchanged.
- [ ] 2.3 Remove the reaper from the beat tick: drop the `ReaperDuty` field and
  the `reaper.reapOnce(...)` call in `tick()`; update the class Javadoc to
  "beats own claims only" (FR5, supersedes claim-heartbeat D4). Adjust the
  deterministic beat specs that asserted the per-tick reaper call.

## 3. Unified assembly

- [ ] 3.1 Update `TakeHeartbeat.forRun` to build a beat-only `InstanceHeartbeat`
  plus a `StandingReaper(reaper, sleeper, interval, heartbeat::liveClaimsSnapshot)`
  and return the standing reaper alongside the existing views (FR5, design D2;
  NFR-S1: the reaper ticks on the beat interval, and interval/TTL still come
  only from the factory's clone of `.gnomish/config.yaml`).
- [ ] 3.2 Update the `TakeHeartbeat` record/shape and its callers; keep the
  `MonotonicTime`-injecting overload for controlled-clock tests.

## 4. take wiring

- [ ] 4.1 In `TakeCommand`, start the standing reaper at the run start and
  `stop()` it in a `finally` around dispatch (FR1, FR5). Core claim→work→
  terminal flow untouched.
- [ ] 4.2 Wiring spec: a `take` run starts and stops the standing reaper and
  passes the beat-only heartbeat (no reaper on its tick) — covers tracker-take
  "Take runs the heartbeat thread and the reaper duty" as modified, including
  the "Reaping outlives the beat thread" scenario.

## 5. serve wiring

- [ ] 5.1 In `ServeCommand`, start the standing reaper beside `WorktreeJanitor`
  (daemon lifetime) using `heartbeat::liveClaimsSnapshot` (FR1, FR5).
- [ ] 5.2 Add `standingReaper.stop()` to the `ServeShutdown` sequence so SIGTERM
  and drain stop it cleanly without racing the supervisor (FR4).
- [ ] 5.3 Extend `ServeCommandSpec`: serve builds, starts, and registers `stop()`
  for the standing reaper; the heartbeat carries no reaper duty.
- [ ] 5.4 Cover `factory-serve` FR13 "Reaping while idle with no claims of its
  own": a serve instance in Idle-empty/just-restarted reaps a foreign stale
  claim without holding any claim first.
- [ ] 5.5 Cover the self-reap re-claim edge (FR2; factory-serve "Self-reaped
  task re-claimed by the same instance"): heartbeat death → own standing reaper
  returns a still-running slot's task → the feed re-claims it into a new slot →
  the old slot's next push/tracker write is fenced and ends via the ordinary
  abort path.

## 6. Repoint existing reaper specs

- [ ] 6.1 Move `RestartCleanlinessSpec` from the manually-driven `heartbeat.tick()`
  to the `StandingReaper`; prove the previous life's claims are reaped with the
  new instance holding nothing (FR1, FR2).
- [ ] 6.2 Move `ReapingWhileSaturatedSpec` to the `StandingReaper`: snapshot =
  both own claims (busy) still reaps the foreign one; add the empty-snapshot
  case.
- [ ] 6.3 Repoint any `ReaperSpec` assertions that assumed the reaper rode the
  beat tick to the standing reaper (behavior of `Reaper`/`StalenessMemory`
  itself is unchanged).
- [ ] 6.4 Cover claim-heartbeat "A dead heartbeat stops shielding its
  instance's claims" (FR2): the heartbeat worker dies abnormally → the
  snapshot goes empty → the instance's own standing reaper reaps its
  now-unbeaten claims after TTL.

## 7. Integration proof (NFR-R1, M1)

- [ ] 7.1 Integration spec over `InMemoryTracker`: a restarted single-instance
  `serve` with an empty ready queue returns two prior-life stale claims to
  circulation via the standing reaper, no manual intervention (factory-serve
  FR12 "Restart against an empty queue still recovers"; claim-heartbeat "A
  restarted daemon returns its previous life's claims with nothing to claim";
  UX1).

## 8. Operator guide

- [ ] 8.1 Update `docs/operator-guide.md` stuck-`Working` recovery: automatic
  reaping happens whenever any factory instance is running, claim in hand or
  not (bounded only by runs too short to observe a full TTL); keep the
  explicit-takeover and cron escape-hatch distinctions intact (tracker-take
  "Operator guide" as modified; UX1).

## 9. Quality gates

- [ ] 9.1 PIT mutation on `StandingReaper` and the changed `InstanceHeartbeat`
  paths at 100% (≥95% only with written justification per the testing rule),
  covering the `Throwable` catch, the `stopping` branch, the respawn branch, and
  the empty-vs-non-empty snapshot (M2, M3).
- [ ] 9.2 Pass Spotless, Error Prone + NullAway, and the unused-code checks; no
  new dependency-analysis violations.

## 10. Wrap-up

- [ ] 10.1 `grep` traceability: every FR1–FR5, NFR-R1/R2, NFR-O1, NFR-S1, and
  UX1–UX2 of `fix-reaper-idle-liveness` has an implementing entity in code,
  tests, or docs, and the modified `claim-heartbeat` / `factory-serve` /
  `tracker-take` requirements each have a covering spec.
- [ ] 10.2 Recommend a commit message (Conventional Commits subject + trailer
  referencing the change / FR IDs); do NOT commit.
