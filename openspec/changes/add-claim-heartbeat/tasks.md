# Tasks: add-claim-heartbeat

TDD throughout (testing.md): each task starts from a failing Spock spec
referencing its FR. Design decisions referenced as D1–D12 (design.md).
Depends on `add-tracker-port` implementation being in place.

## 1. Port and value model extension

- [x] 1.1 Value model in `app/port/tracker`: `ClaimVersion` (marker identity +
      last-update fact), `OpenTask` (state, holder, version), heartbeat result
      (beaten | claim gone), stale-claim-removal result (removed |
      mismatch with current facts) — with unit specs for invariants (FR5, D5)
- [x] 1.2 `Tracker` port interface gains `listOpen`, `heartbeat`,
      `removeStaleClaim` with doc comments carrying traceability links
      (FR5, D5)

## 2. Contract suite extension and in-memory reference

- [x] 2.1 Contract property — `listOpen` filtering: only `Working`/
      `AwaitingHuman`, never `Ready`/`Finished`/`Gone`/non-tasks; `Working`
      entries carry holder and version; `listReady` unchanged (FR5)
- [x] 2.2 Contract property — heartbeat: a beat changes the version another
      instance reads; marker identity stable; beat after removal reports
      claim gone, distinguishable from infrastructure failure (FR1, FR5, FR8)
- [x] 2.3 Contract property — `removeStaleClaim`: round-trip (task `Ready`,
      claim gone, transition marker recoverable), version-mismatch no-op,
      concurrent-removal convergence via interleaving hooks (FR4, FR5, NFR-R2)
- [x] 2.4 In-memory adapter: implement the three operations + harness hooks
      for removal races and beat/removal interleavings (FR5)
- [x] 2.5 Extended contract suite green on the in-memory reference (M1)

## 3. GitHub adapter

- [x] 3.1 `heartbeat` as claim-comment PATCH: body refresh (structural marker +
      progress line), id stable, one write; 404 → claim gone, 5xx/network →
      infrastructure (WireMock specs) (FR1, FR8, D1)
- [x] 3.2 `listOpen` via working/needs-human labels with ETag conditional
      requests, PR exclusion; claim-comment resolution to (comment id,
      updated_at); missing claim comment → absent claim (FR5, NFR-P1)
- [x] 3.3 `removeStaleClaim` physics: pre-action version re-check,
      `stale-claim-removed` boundary marker (existing hidden-JSON convention),
      dead-comment deletion, point label flip; racing removal converges
      (FR4, D5, D12)
- [x] 3.4 Claim verify-read anchors on the removal marker as a boundary; spec
      for the post-removal lease round (FR4, D5)
- [x] 3.5 Extended contract suite green on the GitHub adapter over WireMock
      (M1, M2)

## 4. Core lease policy

- [x] 4.1 Staleness memory: per-claim observation store (version + first-seen
      monotonic instant), TTL judgment; controlled-clock specs including the
      fresh-observer grace period and the beaten-claim-never-stale property
      (FR2, D2, NFR-R1)
- [x] 4.2 Instance heartbeat thread: beats every held claim on the interval,
      payload assembled from `EngineEventListener` events (stage, attempt,
      alive-at); starts with the first claim, stops when none held (FR1, D3)
- [x] 4.3 Reaper duty on the heartbeat tick: `listOpen` → feed staleness
      memory → `removeStaleClaim` for stale `Working` claims; never claims
      for itself (FR4, D4)
- [x] 4.4 Beat-failure taxonomy: 5xx/network → WARN and continue; claim gone →
      flag for the next round boundary, reaction identical to revocation
      (stop, salvage push, no tracker writes) (FR8, D7)
- [x] 4.5 Outage behavior spec: no observations during an outage → no TTL
      progress; recovery restarts windows (FR9, D2)

## 5. Config keys

- [x] 5.1 `tracker.heartbeat-interval` (duration, default 5 min) and
      `tracker.heartbeat-ttl-multiplier` (integer ≥ 3, default 3) parsing and
      validation with located, aggregated errors (FR3)
- [x] 5.2 Spec pinning that protocol constants load from the factory's clone
      only — a worktree config edit never changes the running instance's
      beat/TTL (FR3, NFR-S1)

## 6. take integration

- [x] 6.1 Heartbeat-thread lifecycle in the take run: start at first claim,
      stop at terminal result or claim loss; integration spec that the claim
      comment is beaten during a long fake round (FR1)
- [x] 6.2 Disposition matrix update: `Working` → takeover path — facts shown
      (holder, last-beat age), TTY confirmation or `--takeover` flag,
      refusal naming the holder otherwise; confirmed path =
      `removeStaleClaim` + ordinary claim + branch resume (FR6, D9)
- [x] 6.3 Zombie pre-write checks: conditional "claim still ours" before
      park/finish/release; verify no force-push exists on any task-branch
      code path (FR7, D6)
- [x] 6.4 Reconcile-on-resume at the head of the claim path: branch terminal
      outcome without tracker counterpart → deferred finish/park, zero engine
      rounds, exit by the reconciled outcome (FR10, D10, M4)
      (Completed/finish reconcile done + M4; Escalated/Paused park-reconcile
      folded into 6.5 — needs the durable "tracker-write pending" marker to be
      decidable post-claim)
- [x] 6.5 Terminal-write retry with backoff bounded (~10 min), ERROR naming
      the unreconciled state on give-up; abort path stays best-effort
      (FR10, D10) + durable "tracker-write pending" marker for finish AND park,
      and the deferred-park reconcile it enables (moved from 6.4)
      (park marker on task.json + confirmTerminalWrite clear; finish keeps
      6.4's cleanup-detection reconcile — documented asymmetry; TerminalWriteRetry
      seam with injected Sleeper/Clock; deferred-park reconcile at resumeExisting)
- [x] 6.6 Integration test — death and recovery: instance A's claim goes
      stale under a controlled clock, instance B's run reaps it, a later run
      resumes from the branch (M2)
      (MonotonicTime injected into TakeHeartbeat.forRun + threaded through
      TakeCommand, defaulting to SystemMonotonicTime; TakeDeathAndRecoverySpecBase
      + InMemoryTakeDeathAndRecoverySpec drive A-dies -> B-reaps -> resume)
- [x] 6.7 Integration test — zombie fence over a local bare repo: two holders
      push the same task branch, exactly one lands, the loser takes the
      normal abort path (M3)
      (ZombieFenceSpec drives the real GitAttemptPersistence + BestEffortPush +
      RevocationCheckingAttemptPersistence over one bare origin with two clones:
      holder's round fast-forwards, zombie's round is non-ff rejected and throws
      the revocation the engine turns into Aborted; origin stays at holder's tip
      — runtime proof of no force — NoForcePushGuardSpec pins the source guard)

## 7. Command-check credential scrub

- [x] 7.1 Feed the adapter-declared credential list into the command-check
      process construction; spec: `GNOMISH_GITHUB_TOKEN` absent from a check's
      environment, env unchanged when no tracker is configured (FR11, D11)

## 8. Documentation

- [x] 8.1 Operator guide: rewrite the stuck-`Working` section (automatic
      reaping in long-lived runs, confirmed `--takeover`, cron-only manual
      escape hatch until `serve`), document heartbeat/TTL keys and the shared
      write-budget coupling (FR3, FR6, NFR-P1, UX3)
- [x] 8.2 Adapter author guide: the three new operations, claim-version
      obligations, removal-marker boundary semantics, the extended contract
      suite as law (FR5)

## 9. Final verification

- [x] 9.1 Traceability sweep: every FR/NFR/UX of the proposal grep-resolves to
      at least one implementing entity or spec (traceability.md)
- [x] 9.2 Full `./gradlew check` green; coverage and mutation gates hold for
      all new production code (M5)
      (functional gate green; mutation gate 100% for all change classes via
      scoped pitestVerifyAllKilled; the only full-tree residual is a
      load-induced TIMED_OUT on the non-change adapter.agent classes, which
      kill 18/18 in isolation — killed on normal-load hardware/CI)

## 10. Self-reap and latch hardening (post-review)

- [x] 10.1 Reaper excludes the instance's own held claims from staleness
      observation: `ReaperDuty.reapOnce` takes the held snapshot, the tick
      passes it, the reaper filters own claims before `observe` — a run whose
      beats fail while `listOpen` succeeds never reaps its own live claim
      (FR4, D13, G2)
- [x] 10.2 `StalenessMemory.retryEmission` re-arms the once-per-version latch
      for a claim whose removal failed (version-guarded); the reaper calls it on
      a `removeStaleClaim` infrastructure failure so the removal is retried, not
      suppressed until the version changes (FR4, D14)

## 11. Vanished-task claim-gone symmetry (post-review)

- [x] 11.1 Reference and live adapters map a task the tracker no longer holds to
      the SAME observable result, never an exception: in-memory `heartbeat`/
      `removeStaleClaim` stop routing through `requireTask` (which is right for the
      v1 ops, where an unknown ref is a programming error) and return
      `ClaimGone`/`Mismatch(null)` on an absent ref; GitHub maps a 404 on the
      comment listing (the issue itself is gone) to the same, keeping only
      non-404 non-2xx and transport errors as infrastructure failures (FR8,
      NFR-R2, D5, D7)
- [x] 11.2 Extend the shared contract suite with the two "unknown task"
      properties (beat → `ClaimGone`, `removeStaleClaim` → `Mismatch(null)`),
      make the GitHub fixture transformer 404 a comment listing for an unknown
      issue (faithful to GitHub, so the property exercises the real 404 path),
      and pin the GitHub 404-listing mapping directly in the WireMock beat/reap
      specs (FR8, NFR-R2, M1)

## 12. Give-up ERROR log coverage (post-review)

- [x] 12.1 Pin the terminal-write give-up ERROR as an observable requirement: a
      new `tracker-take` scenario ("Give-up past the bound names the unreconciled
      state") plus a `ListAppender` assertion in both `TakeParkRetrySpec`
      give-up cases (escalation + checkpoint) — exactly one ERROR naming the task
      and its "tracker-write pending" state — and the matching tightening of the
      finish give-up check in `TakeFinishReportSpec`. Closes the gap where the
      only requirement living in the log channel was unverified (PIT's
      `avoidCallsTo` slf4j never mutates the `log.error`, so a refactor could
      silently drop the message) (FR10, D10, NFR-R3)

## 13. Heartbeat-thread death observability (post-review)

- [x] 13.1 Make the beat thread's abnormal death loud and restart-safe without
      resurrecting it (degradation stays the designed death path): give the
      worker a name (`gnomish-heartbeat`) and an uncaught-exception handler that
      logs at ERROR — an `Error` from deep in an adapter (OOM/StackOverflow) or a
      throwing sleeper would otherwise reach only the default handler's stderr,
      past slf4j and unlinked from its later stale-and-reaped effect — and, in
      the same handler, clear `running` under the lock so a subsequent `register`
      (a different claim of the run) starts a FRESH thread instead of assuming the
      dead one alive (inert under single-task take, load-bearing once serve holds
      several claims). New `InstanceHeartbeatLifecycleSpec` scenario: a sleeper
      that throws on its second call → the named worker dies, one ERROR naming
      the thread ("heartbeat thread gnomish-heartbeat died…") is logged, and a
      later claim starts a distinct live worker. The handler is a method
      reference, not a lambda, so PIT sees no synthetic wrapper carrying an
      unmutatable cross-thread call (FR1, D3)
